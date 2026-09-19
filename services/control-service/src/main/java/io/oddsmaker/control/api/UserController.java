package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.UserEntity;
import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用户管理API控制器
 * 提供用户的CRUD操作和权限管理；鉴权走 AccessGuard 行内风格（user:read / user:update），
 * updateUser/toggleTwoFactor 保留自助语义（本人操作放行，他人操作需 user:update）。
 * 历史形态为 @PreAuthorize hasRole('ADMIN') or hasRole('MANAGER') 布尔式，而全仓只签发 ROLE_* authority
 * 且 ROLE_MANAGER 从未签发，方法安全开启后这些注解仅 ADMIN 可用——故换成权限种子（V0.9.8）+ AccessGuard 解析。
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    @Autowired
    private UserService userService;

    @Autowired
    private AccessGuard accessGuard;

    /**
     * 获取当前用户信息
     */
    @GetMapping("/me")
    public ResponseEntity<UserEntity> getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String username = auth.getName();

        return userService.findByUsername(username)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 获取用户列表
     */
    @GetMapping
    public ResponseEntity<Page<UserEntity>> listUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {
        accessGuard.requirePermission("user:read");

        Sort sort = sortDir.equalsIgnoreCase("desc") ?
            Sort.by(sortBy).descending() : Sort.by(sortBy).ascending();
        Pageable pageable = PageRequest.of(page, size, sort);

        Page<UserEntity> users = userService.listUsers(pageable);
        return ResponseEntity.ok(users);
    }

    /**
     * 搜索用户
     */
    @GetMapping("/search")
    public ResponseEntity<Page<UserEntity>> searchUsers(
            @RequestParam String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        accessGuard.requirePermission("user:read");

        Pageable pageable = PageRequest.of(page, size);
        Page<UserEntity> users = userService.searchUsers(query, pageable);
        return ResponseEntity.ok(users);
    }

    /**
     * 获取用户详情
     */
    @GetMapping("/{userId}")
    public ResponseEntity<UserEntity> getUser(@PathVariable String userId) {
        accessGuard.requirePermission("user:read");
        return userService.findById(userId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 创建用户
     */
    @PostMapping
    public ResponseEntity<UserEntity> createUser(@RequestBody UserEntity user) {
        accessGuard.requirePermission("user:update");
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String operatorId = auth.getName();

        UserEntity created = userService.createUser(user, operatorId);
        return ResponseEntity.ok(created);
    }

    /**
     * 更新用户（自助：本人可改自己，他人需 user:update）
     */
    @PutMapping("/{userId}")
    public ResponseEntity<UserEntity> updateUser(
            @PathVariable String userId,
            @RequestBody UserEntity updates) {

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String operatorId = auth.getName();
        if (!userId.equals(operatorId)) {
            accessGuard.requirePermission("user:update");
        }

        UserEntity updated = userService.updateUser(userId, updates, operatorId);
        return ResponseEntity.ok(updated);
    }

    /**
     * 删除用户
     */
    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> deleteUser(@PathVariable String userId) {
        accessGuard.requirePermission("user:update");
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String operatorId = auth.getName();

        userService.deleteUser(userId, operatorId);
        return ResponseEntity.ok().build();
    }

    /**
     * 更新用户角色
     */
    @PutMapping("/{userId}/roles")
    public ResponseEntity<UserEntity> updateRoles(
            @PathVariable String userId,
            @RequestBody Set<UserEntity.UserRole> roles) {
        accessGuard.requirePermission("user:update");
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String operatorId = auth.getName();

        UserEntity updated = userService.updateRoles(userId, roles, operatorId);
        return ResponseEntity.ok(updated);
    }

    /**
     * 锁定用户
     */
    @PostMapping("/{userId}/lock")
    public ResponseEntity<UserEntity> lockUser(@PathVariable String userId) {
        accessGuard.requirePermission("user:update");
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String operatorId = auth.getName();

        UserEntity locked = userService.lockUser(userId, operatorId);
        return ResponseEntity.ok(locked);
    }

    /**
     * 解锁用户
     */
    @PostMapping("/{userId}/unlock")
    public ResponseEntity<UserEntity> unlockUser(@PathVariable String userId) {
        accessGuard.requirePermission("user:update");
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String operatorId = auth.getName();

        UserEntity unlocked = userService.unlockUser(userId, operatorId);
        return ResponseEntity.ok(unlocked);
    }

    /**
     * 启用/禁用双因素认证（自助：本人可改自己，他人需 user:update）
     */
    @PutMapping("/{userId}/two-factor")
    public ResponseEntity<UserEntity> toggleTwoFactor(
            @PathVariable String userId,
            @RequestBody Map<String, Boolean> request) {

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String operatorId = auth.getName();
        if (!userId.equals(operatorId)) {
            accessGuard.requirePermission("user:update");
        }

        boolean enabled = request.getOrDefault("enabled", false);
        UserEntity updated = userService.toggleTwoFactor(userId, enabled, operatorId);
        return ResponseEntity.ok(updated);
    }

    /**
     * 获取用户统计信息
     */
    @GetMapping("/statistics")
    public ResponseEntity<Map<String, Object>> getUserStatistics() {
        accessGuard.requirePermission("user:read");
        Map<String, Object> stats = userService.getUserStatistics();
        return ResponseEntity.ok(stats);
    }

    /**
     * 获取最近登录的用户
     */
    @GetMapping("/recent-logins")
    public ResponseEntity<List<UserEntity>> getRecentLogins(
            @RequestParam(defaultValue = "10") int limit) {
        accessGuard.requirePermission("user:read");

        List<UserEntity> users = userService.getRecentlyLoggedInUsers(limit);
        return ResponseEntity.ok(users);
    }

    /**
     * 根据角色查找用户
     */
    @GetMapping("/role/{role}")
    public ResponseEntity<List<UserEntity>> getUsersByRole(
            @PathVariable UserEntity.UserRole role) {
        accessGuard.requirePermission("user:read");

        List<UserEntity> users = userService.findByRole(role);
        return ResponseEntity.ok(users);
    }
}
