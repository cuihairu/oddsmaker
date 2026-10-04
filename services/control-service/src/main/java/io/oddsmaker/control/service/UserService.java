package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.UserEntity;
import io.oddsmaker.control.jpa.UserRepo;
import io.oddsmaker.control.jpa.UserRoleEntity;
import io.oddsmaker.control.jpa.UserRoleRepo;
import io.oddsmaker.control.jpa.RoleRepo;
import io.oddsmaker.control.jpa.AuditLogEntity;
import io.oddsmaker.control.jpa.AuditLogRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 用户管理服务
 * 提供用户生命周期管理
 */
@Service
@Transactional
public class UserService {

    private static final Logger logger = LoggerFactory.getLogger(UserService.class);

    @Autowired
    private UserRepo userRepo;

    @Autowired
    private AuditLogRepo auditLogRepo;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private UserRoleRepo userRoleRepo;

    @Autowired
    private RoleRepo roleRepo;

    /**
     * 创建用户
     */
    public UserEntity createUser(UserEntity user, String operatorId) {
        logger.info("Creating user: {}", user.username);

        // 必填前置校验：迁移真源侧 users.email 是 NOT NULL UNIQUE，username 挂部分唯一索引
        // 且登录端点只认 username——缺项若放到 flush 才撞约束，对外是 500 而不是 400。
        if (user.username == null || user.username.isBlank()) {
            throw new IllegalArgumentException("Username is required");
        }
        if (user.email == null || user.email.isBlank()) {
            throw new IllegalArgumentException("Email is required");
        }

        // 检查用户名是否已存在
        if (userRepo.existsByUsername(user.username)) {
            throw new IllegalArgumentException("Username already exists: " + user.username);
        }

        // 检查邮箱是否已存在
        if (userRepo.existsByEmail(user.email)) {
            throw new IllegalArgumentException("Email already exists: " + user.email);
        }

        // 生成ID
        if (user.id == null || user.id.trim().isEmpty()) {
            user.id = "user_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }

        // 设置默认状态
        if (user.status == null) {
            user.status = UserEntity.UserStatus.ACTIVE;
        }

        // 全局角色：迁移列 NOT NULL，请求体显式传 null 也要兜住（字段默认值只管省略情形）
        if (user.globalRole == null) {
            user.globalRole = UserEntity.GlobalRole.USER;
        }

        // 设置默认角色
        if (user.roles == null || user.roles.isEmpty()) {
            user.roles = Set.of(UserEntity.UserRole.VIEWER);
        }

        user = userRepo.save(user);

        // 权限单真源（B2）：user_role_assignments 是权限门唯一读取对象，建号即落行——
        // 此前只写 users.roles，账号登录后受权限门端点全量拒绝（deploy/demo README §5
        // 曾被迫用手工 psql 补插兜底）。
        syncGlobalRoleAssignments(user.id, user.roles, operatorId);

        // 记录审计日志
        auditLogRepo.save(createAuditLog(
            operatorId, "operator", AuditLogEntity.AuditAction.CREATE,
            "user", user.id, user.username,
            null, "User created", "SUCCESS", null
        ));

        logger.info("User created successfully: {} (ID: {})", user.username, user.id);
        return user;
    }

    /**
     * 更新用户
     */
    public UserEntity updateUser(String userId, UserEntity updates, String operatorId) {
        logger.info("Updating user: {}", userId);

        UserEntity user = userRepo.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        // 记录旧值
        String oldValue = String.format("username=%s, email=%s, status=%s",
            user.username, user.email, user.status);

        // 更新字段
        if (updates.displayName != null) {
            user.displayName = updates.displayName;
        }
        if (updates.email != null) {
            // 检查邮箱是否已被其他用户使用
            if (!user.email.equals(updates.email) && userRepo.existsByEmail(updates.email)) {
                throw new IllegalArgumentException("Email already exists: " + updates.email);
            }
            user.email = updates.email;
        }
        if (updates.avatarUrl != null) {
            user.avatarUrl = updates.avatarUrl;
        }

        if (updates.timeZone != null) {
            user.timeZone = updates.timeZone;
        }
        if (updates.language != null) {
            user.language = updates.language;
        }
        if (updates.status != null) {
            user.status = updates.status;
        }
        if (updates.roles != null) {
            user.roles = updates.roles;
        }

        user = userRepo.save(user);

        // 角色经 updateUser 变更时同样同步权限单真源（否则改角色又不落 assignments，
        // 双轨坑从建号蔓延到改档）
        if (updates.roles != null) {
            syncGlobalRoleAssignments(user.id, user.roles, operatorId);
        }

        // 记录审计日志
        String newValue = String.format("username=%s, email=%s, status=%s",
            user.username, user.email, user.status);
        auditLogRepo.save(createAuditLog(
            operatorId, "operator", AuditLogEntity.AuditAction.UPDATE,
            "user", user.id, user.username,
            oldValue, newValue, "SUCCESS", null
        ));

        logger.info("User updated successfully: {}", userId);
        return user;
    }

    /**
     * 删除用户（软删除）
     */
    public void deleteUser(String userId, String operatorId) {
        logger.info("Deleting user: {}", userId);

        UserEntity user = userRepo.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        user.deletedAt = LocalDateTime.now();
        user.status = UserEntity.UserStatus.INACTIVE;
        userRepo.save(user);

        // 记录审计日志
        auditLogRepo.save(createAuditLog(
            operatorId, "operator", AuditLogEntity.AuditAction.DELETE,
            "user", user.id, user.username,
            "User active", "User deleted", "SUCCESS", null
        ));

        logger.info("User deleted successfully: {}", userId);
    }

    /**
     * 根据ID查找用户
     */
    public Optional<UserEntity> findById(String userId) {
        return userRepo.findById(userId)
            .filter(user -> user.deletedAt == null);
    }

    /**
     * 根据用户名查找用户
     */
    public Optional<UserEntity> findByUsername(String username) {
        return userRepo.findByUsername(username)
            .filter(user -> user.deletedAt == null);
    }

    /**
     * 记录一次成功登录（last_login / login_count）。不写审计日志——
     * 登录不是业务数据变更，走 updateUser 会误报 UPDATE 审计。
     */
    public void recordLogin(UserEntity user) {
        user.lastLoginAt = LocalDateTime.now();
        user.loginCount = (user.loginCount == null ? 0L : user.loginCount) + 1;
        userRepo.save(user);
    }

    /**
     * 根据邮箱查找用户
     */
    public Optional<UserEntity> findByEmail(String email) {
        return userRepo.findByEmail(email)
            .filter(user -> user.deletedAt == null);
    }

    /**
     * 根据Keycloak ID查找用户
     */
    public Optional<UserEntity> findByKeycloakId(String keycloakId) {
        return userRepo.findByKeycloakId(keycloakId)
            .filter(user -> user.deletedAt == null);
    }

    /**
     * 分页查询用户
     */
    public Page<UserEntity> listUsers(Pageable pageable) {
        return userRepo.findByStatusAndDeletedAtIsNull(UserEntity.UserStatus.ACTIVE, pageable);
    }

    /**
     * 搜索用户
     */
    public Page<UserEntity> searchUsers(String query, Pageable pageable) {
        return userRepo.searchByName(query, pageable);
    }

    /**
     * 根据角色查找用户
     */
    public List<UserEntity> findByRole(UserEntity.UserRole role) {
        return userRepo.findByRole(role);
    }

    /**
     * 更新用户角色
     */
    public UserEntity updateRoles(String userId, Set<UserEntity.UserRole> roles, String operatorId) {
        logger.info("Updating roles for user: {}", userId);

        UserEntity user = userRepo.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        Set<UserEntity.UserRole> oldRoles = new HashSet<>(user.roles);
        user.roles = roles;
        user = userRepo.save(user);

        // 权限单真源（B2）：PUT /{id}/roles 以 user_role_assignments 为准，users.roles 只是展示投影
        syncGlobalRoleAssignments(user.id, roles, operatorId);

        // 记录审计日志
        auditLogRepo.save(createAuditLog(
            operatorId, "operator", AuditLogEntity.AuditAction.GRANT_ROLE,
            "user", user.id, user.username,
            "Roles: " + oldRoles, "Roles: " + roles, "SUCCESS", null
        ));

        logger.info("Roles updated successfully for user: {}", userId);
        return user;
    }

    /**
     * 权限单真源同步（B2）：把目标角色集写入 user_role_assignments 的全局作用域行
     * （gameId/environment 均空）。权限门（PermissionService.findValidByUserId →
     * RoleEntity.hasPermission）只读这张表；users.roles 自 V0.9.16 起只是展示投影。
     *
     * 映射规则：role_id = "role_" + 枚举名小写；roles 表不存在该 id 的角色
     * （ADMIN/MANAGER/SUPER_ADMIN——登录即有 ROLE_ADMIN 直通，V0.2.3 种子无对应行）
     * 不落行，避免指向不存在角色的哑行（与回填迁移 V0.9.16 JOIN roles 同口径）。
     * 目标集合内的既有禁用行重新启用；目标集合外的既有启用全局行删除
     * （与 PermissionService.revokeRole 的删除语义一致，不留下陈旧授权）。
     * 游戏/环境作用域行不在此管理（RoleAssignmentController 的专职范畴）。
     */
    private void syncGlobalRoleAssignments(String userId, Set<UserEntity.UserRole> targetRoles, String operatorId) {
        List<String> targetRoleIds = targetRoles.stream()
            .map(UserService::toRoleId)
            .filter(roleId -> roleRepo.existsById(roleId))
            .toList();

        List<UserRoleEntity> globalAssignments = userRoleRepo.findByUserId(userId).stream()
            .filter(a -> a.gameId == null && a.environment == null)
            .toList();

        for (String roleId : targetRoleIds) {
            UserRoleEntity existing = globalAssignments.stream()
                .filter(a -> roleId.equals(a.roleId))
                .findFirst().orElse(null);
            if (existing == null) {
                UserRoleEntity assignment = new UserRoleEntity();
                assignment.userId = userId;
                assignment.roleId = roleId;
                assignment.enabled = true;
                assignment.assignedBy = operatorId;
                assignment.assignedAt = LocalDateTime.now();
                userRoleRepo.save(assignment);
            } else if (!existing.isEnabled()) {
                existing.enabled = true;
                existing.assignedBy = operatorId;
                existing.expiresAt = null;
                userRoleRepo.save(existing);
            }
        }

        List<Long> staleIds = globalAssignments.stream()
            .filter(a -> a.isEnabled() && !targetRoleIds.contains(a.roleId))
            .map(a -> a.id)
            .toList();
        if (!staleIds.isEmpty()) {
            userRoleRepo.deleteAllById(staleIds);
        }
    }

    /** UserRole 枚举 → roles 表 id（"role_" + 小写枚举名） */
    private static String toRoleId(UserEntity.UserRole role) {
        return "role_" + role.name().toLowerCase(Locale.ROOT);
    }

    /**
     * 启用/禁用双因素认证
     */
    public UserEntity toggleTwoFactor(String userId, boolean enabled, String operatorId) {
        logger.info("Toggling two-factor authentication for user: {} to {}", userId, enabled);

        UserEntity user = userRepo.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        user.twoFactorEnabled = enabled;
        if (!enabled) {
            user.twoFactorSecret = null;
        }
        user = userRepo.save(user);

        // 记录审计日志
        auditLogRepo.save(createAuditLog(
            operatorId, "operator",
            enabled ? AuditLogEntity.AuditAction.ENABLE : AuditLogEntity.AuditAction.DISABLE,
            "user", user.id, user.username,
            "Two-factor: " + !enabled, "Two-factor: " + enabled, "SUCCESS", null
        ));

        logger.info("Two-factor authentication toggled successfully for user: {}", userId);
        return user;
    }

    /**
     * 锁定用户
     */
    public UserEntity lockUser(String userId, String operatorId) {
        logger.info("Locking user: {}", userId);

        UserEntity user = userRepo.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        user.status = UserEntity.UserStatus.LOCKED;
        user = userRepo.save(user);

        // 记录审计日志
        auditLogRepo.save(createAuditLog(
            operatorId, "operator", AuditLogEntity.AuditAction.UPDATE,
            "user", user.id, user.username,
            "Status: ACTIVE", "Status: LOCKED", "SUCCESS", null
        ));

        logger.info("User locked successfully: {}", userId);
        return user;
    }

    /**
     * 解锁用户
     */
    public UserEntity unlockUser(String userId, String operatorId) {
        logger.info("Unlocking user: {}", userId);

        UserEntity user = userRepo.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        user.status = UserEntity.UserStatus.ACTIVE;
        user = userRepo.save(user);

        // 记录审计日志
        auditLogRepo.save(createAuditLog(
            operatorId, "operator", AuditLogEntity.AuditAction.UPDATE,
            "user", user.id, user.username,
            "Status: LOCKED", "Status: ACTIVE", "SUCCESS", null
        ));

        logger.info("User unlocked successfully: {}", userId);
        return user;
    }

    /**
     * 获取用户统计信息
     */
    public Map<String, Object> getUserStatistics() {
        LocalDateTime since = LocalDateTime.now().minusDays(30);
        List<Object> stats = userRepo.getUserStatistics(since);
        if (stats.isEmpty()) {
            return Map.of();
        }
        return (Map<String, Object>) stats.get(0);
    }

    /**
     * 获取最近登录的用户
     */
    public List<UserEntity> getRecentlyLoggedInUsers(int limit) {
        return userRepo.findRecentlyLoggedIn(
            org.springframework.data.domain.PageRequest.of(0, limit));
    }

    /**
     * 创建审计日志
     */
    private AuditLogEntity createAuditLog(
            String userId, String username, AuditLogEntity.AuditAction action,
            String resourceType, String resourceId, String resourceName,
            String oldValue, String newValue, String status, String ip) {
        
        AuditLogEntity log = new AuditLogEntity();
        log.userId = userId;
        log.username = username;
        log.action = action;
        log.resourceType = resourceType;
        log.resourceId = resourceId;
        log.resourceName = resourceName;
        log.oldValue = oldValue;
        log.newValue = newValue;
        log.status = AuditLogEntity.AuditStatus.valueOf(status);
        log.ipAddress = ip;
        log.createdAt = LocalDateTime.now();
        return log;
    }
}