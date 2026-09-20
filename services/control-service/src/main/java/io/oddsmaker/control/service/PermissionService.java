package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 权限检查服务
 * 提供基于RBAC的权限检查功能
 */
@Service
@Transactional
public class PermissionService {

    private static final Logger logger = LoggerFactory.getLogger(PermissionService.class);

    @Autowired
    private UserRepo userRepo;

    @Autowired
    private RoleRepo roleRepo;

    @Autowired
    private PermissionRepo permissionRepo;

    @Autowired
    private UserRoleRepo userRoleRepo;

    /**
     * 检查用户是否有指定权限
     */
    public boolean hasPermission(String userId, String permissionId) {
        logger.debug("Checking permission {} for user {}", permissionId, userId);

        // 获取用户
        UserEntity user = userRepo.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        // 检查用户是否启用
        if (!user.isActive()) {
            logger.debug("User {} is not active", userId);
            return false;
        }

        // 不再检查 isLocked()：isActive()（status==ACTIVE 且未删除）与 isLocked()（status==LOCKED）
        // 互斥，LOCKED 用户已在上方 !isActive() 提前返回，此分支不可达。

        // 获取用户的有效角色分配
        List<UserRoleEntity> userRoles = userRoleRepo.findValidByUserId(userId, LocalDateTime.now());

        // 检查每个角色是否包含该权限
        for (UserRoleEntity userRole : userRoles) {
            RoleEntity role = roleRepo.findById(userRole.roleId)
                .orElse(null);

            if (role != null && role.isEnabled() && role.hasPermission(permissionId)) {
                logger.debug("User {} has permission {} through role {}", userId, permissionId, role.id);
                return true;
            }
        }

        logger.debug("User {} does not have permission {}", userId, permissionId);
        return false;
    }

    /**
     * 检查用户是否有指定资源类型和操作的权限
     */
    public boolean hasPermission(String userId, String resourceType, PermissionEntity.PermissionAction action) {
        logger.debug("Checking permission for resource {} action {} for user {}", resourceType, action, userId);

        // 查找权限
        PermissionEntity permission = permissionRepo.findByResourceTypeAndAction(resourceType, action)
            .orElse(null);

        if (permission == null) {
            logger.debug("Permission not found for resource {} action {}", resourceType, action);
            return false;
        }

        return hasPermission(userId, permission.id);
    }

    /**
     * 检查用户是否有指定游戏的权限
     */
    public boolean hasGamePermission(String userId, String gameId, String permissionId) {
        logger.debug("Checking game permission {} for user {} in game {}", permissionId, userId, gameId);

        // 获取用户
        UserEntity user = userRepo.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        // 检查用户是否启用
        if (!user.isActive()) {
            return false;
        }

        // 不再检查 isLocked()：isActive()（status==ACTIVE 且未删除）与 isLocked()（status==LOCKED）
        // 互斥，LOCKED 用户已在上方 !isActive() 提前返回，此分支不可达。

        // 获取用户在该游戏中的角色分配
        List<UserRoleEntity> userRoles = userRoleRepo.findByUserIdAndGameId(userId, gameId);

        // 检查每个角色是否包含该权限
        for (UserRoleEntity userRole : userRoles) {
            if (!userRole.isValid()) {
                continue;
            }

            RoleEntity role = roleRepo.findById(userRole.roleId)
                .orElse(null);

            if (role != null && role.isEnabled() && role.hasPermission(permissionId)) {
                logger.debug("User {} has game permission {} through role {} in game {}", 
                    userId, permissionId, role.id, gameId);
                return true;
            }
        }

        // 检查全局角色
        List<UserRoleEntity> globalRoles = userRoleRepo.findGlobalByUserId(userId);
        for (UserRoleEntity userRole : globalRoles) {
            if (!userRole.isValid()) {
                continue;
            }

            RoleEntity role = roleRepo.findById(userRole.roleId)
                .orElse(null);

            if (role != null && role.isEnabled() && role.hasPermission(permissionId)) {
                logger.debug("User {} has game permission {} through global role {}", 
                    userId, permissionId, role.id);
                return true;
            }
        }

        logger.debug("User {} does not have game permission {} in game {}", userId, permissionId, gameId);
        return false;
    }

    /**
     * 为用户分配角色
     */
    public UserRoleEntity assignRole(String userId, String roleId, String gameId, String environment, String assignedBy) {
        logger.info("Assigning role {} to user {} in game {} environment {}", 
            roleId, userId, gameId, environment);

        // 检查用户是否存在
        if (!userRepo.existsById(userId)) {
            throw new IllegalArgumentException("User not found: " + userId);
        }

        // 检查角色是否存在
        if (!roleRepo.existsById(roleId)) {
            throw new IllegalArgumentException("Role not found: " + roleId);
        }

        // 检查是否已分配（scope-aware：roleId+gameId+environment 三元组精确匹配，与 revokeRole 同语义——
        // 原 findByUserIdAndRoleId 不带 scope，global 已有时 game 级被误拒）
        List<UserRoleEntity> sameScope = userRoleRepo.findByUserId(userId).stream()
            .filter(ur -> roleId.equals(ur.roleId))
            .filter(ur -> Objects.equals(ur.gameId, gameId))
            .filter(ur -> Objects.equals(ur.environment, environment))
            .toList();
        if (!sameScope.isEmpty()) {
            UserRoleEntity existing = sameScope.get(0);
            if (existing.isValid()) {
                throw new IllegalArgumentException("Role already assigned to user in this scope");
            }
            // 已有失效行（禁用/过期）则复活该行，不另起新行（表无唯一约束，防重复行堆积）
            existing.enabled = true;
            existing.expiresAt = null;
            existing.assignedBy = assignedBy;
            return userRoleRepo.save(existing);
        }

        // 创建角色分配
        UserRoleEntity userRole = new UserRoleEntity();
        userRole.userId = userId;
        userRole.roleId = roleId;
        userRole.gameId = gameId;
        userRole.environment = environment;
        userRole.assignedBy = assignedBy;
        userRole.enabled = true;

        return userRoleRepo.save(userRole);
    }

    /**
     * 撤销用户的角色（scope 化：精确匹配 gameId/environment，null 表示该维度不限）
     */
    public void revokeRole(String userId, String roleId, String gameId, String environment) {
        logger.info("Revoking role {} from user {} (gameId={}, environment={})",
            roleId, userId, gameId, environment);

        List<UserRoleEntity> assignments = userRoleRepo.findByUserId(userId).stream()
            .filter(ur -> roleId.equals(ur.roleId))
            .filter(ur -> Objects.equals(ur.gameId, gameId))
            .filter(ur -> Objects.equals(ur.environment, environment))
            .toList();
        if (assignments.isEmpty()) {
            throw new IllegalArgumentException(
                "Role assignment not found: user=" + userId + ", role=" + roleId
                    + ", gameId=" + gameId + ", environment=" + environment);
        }
        userRoleRepo.deleteAll(assignments);
    }

    /**
     * 列出用户的全部角色分配（含 scope 与生效状态）
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listAssignments(String userId) {
        if (!userRepo.existsById(userId)) {
            throw new IllegalArgumentException("User not found: " + userId);
        }
        return userRoleRepo.findByUserId(userId).stream()
            .map(ur -> {
                RoleEntity role = roleRepo.findById(ur.roleId).orElse(null);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("roleId", ur.roleId);
                out.put("roleName", role != null ? role.name : ur.roleId);
                out.put("scope", ur.isGlobal() ? "global" : ur.isEnvironmentScoped() ? "environment" : "game");
                out.put("gameId", ur.gameId);
                out.put("environment", ur.environment);
                out.put("assignedBy", ur.assignedBy);
                out.put("assignedAt", ur.assignedAt);
                out.put("valid", ur.isValid());
                return out;
            })
            .collect(Collectors.toList());
    }
}