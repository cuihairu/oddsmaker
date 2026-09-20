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
     * 检查用户是否有指定环境的权限
     */
    public boolean hasEnvironmentPermission(String userId, String gameId, String environment, String permissionId) {
        logger.debug("Checking environment permission {} for user {} in game {} environment {}", 
            permissionId, userId, gameId, environment);

        // 获取用户
        UserEntity user = userRepo.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        // 检查用户是否启用
        if (!user.isActive()) {
            return false;
        }

        // 不再检查 isLocked()：isActive()（status==ACTIVE 且未删除）与 isLocked()（status==LOCKED）
        // 互斥，LOCKED 用户已在上方 !isActive() 提前返回，此分支不可达。

        // 获取用户在该游戏环境中的角色分配
        List<UserRoleEntity> userRoles = userRoleRepo.findByUserIdAndGameIdAndEnvironment(userId, gameId, environment);

        // 检查每个角色是否包含该权限
        for (UserRoleEntity userRole : userRoles) {
            if (!userRole.isValid()) {
                continue;
            }

            RoleEntity role = roleRepo.findById(userRole.roleId)
                .orElse(null);

            if (role != null && role.isEnabled() && role.hasPermission(permissionId)) {
                logger.debug("User {} has environment permission {} through role {} in game {} environment {}", 
                    userId, permissionId, role.id, gameId, environment);
                return true;
            }
        }

        // 检查游戏级角色
        List<UserRoleEntity> gameRoles = userRoleRepo.findByUserIdAndGameId(userId, gameId);
        for (UserRoleEntity userRole : gameRoles) {
            if (!userRole.isValid()) {
                continue;
            }

            RoleEntity role = roleRepo.findById(userRole.roleId)
                .orElse(null);

            if (role != null && role.isEnabled() && role.hasPermission(permissionId)) {
                logger.debug("User {} has environment permission {} through game role {} in game {}", 
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
                logger.debug("User {} has environment permission {} through global role {}", 
                    userId, permissionId, role.id);
                return true;
            }
        }

        logger.debug("User {} does not have environment permission {} in game {} environment {}", 
            userId, permissionId, gameId, environment);
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

    /**
     * 初始化默认权限和角色
     */
    @Transactional
    public void initializeDefaults() {
        logger.info("Initializing default permissions and roles");

        // 创建默认权限
        createDefaultPermissions();

        // 创建默认角色
        createDefaultRoles();

        logger.info("Default permissions and roles initialized");
    }

    /**
     * 创建默认权限
     */
    private void createDefaultPermissions() {
        // 游戏管理权限
        createPermissionIfNotExists("game:create", "Create games", 
            PermissionEntity.PermissionType.API, "game", PermissionEntity.PermissionAction.CREATE, PermissionEntity.PermissionScope.GLOBAL);
        createPermissionIfNotExists("game:read", "Read games", 
            PermissionEntity.PermissionType.API, "game", PermissionEntity.PermissionAction.READ, PermissionEntity.PermissionScope.GLOBAL);
        createPermissionIfNotExists("game:update", "Update games", 
            PermissionEntity.PermissionType.API, "game", PermissionEntity.PermissionAction.UPDATE, PermissionEntity.PermissionScope.GLOBAL);
        createPermissionIfNotExists("game:delete", "Delete games", 
            PermissionEntity.PermissionType.API, "game", PermissionEntity.PermissionAction.DELETE, PermissionEntity.PermissionScope.GLOBAL);

        // 环境管理权限
        createPermissionIfNotExists("environment:create", "Create environments", 
            PermissionEntity.PermissionType.API, "environment", PermissionEntity.PermissionAction.CREATE, PermissionEntity.PermissionScope.GAME);
        createPermissionIfNotExists("environment:read", "Read environments", 
            PermissionEntity.PermissionType.API, "environment", PermissionEntity.PermissionAction.READ, PermissionEntity.PermissionScope.GAME);
        createPermissionIfNotExists("environment:update", "Update environments", 
            PermissionEntity.PermissionType.API, "environment", PermissionEntity.PermissionAction.UPDATE, PermissionEntity.PermissionScope.GAME);
        createPermissionIfNotExists("environment:delete", "Delete environments", 
            PermissionEntity.PermissionType.API, "environment", PermissionEntity.PermissionAction.DELETE, PermissionEntity.PermissionScope.GAME);

        // API Key管理权限
        createPermissionIfNotExists("api_key:create", "Create API keys", 
            PermissionEntity.PermissionType.API, "api_key", PermissionEntity.PermissionAction.CREATE, PermissionEntity.PermissionScope.GAME);
        createPermissionIfNotExists("api_key:read", "Read API keys", 
            PermissionEntity.PermissionType.API, "api_key", PermissionEntity.PermissionAction.READ, PermissionEntity.PermissionScope.GAME);
        createPermissionIfNotExists("api_key:update", "Update API keys", 
            PermissionEntity.PermissionType.API, "api_key", PermissionEntity.PermissionAction.UPDATE, PermissionEntity.PermissionScope.GAME);
        createPermissionIfNotExists("api_key:delete", "Delete API keys", 
            PermissionEntity.PermissionType.API, "api_key", PermissionEntity.PermissionAction.DELETE, PermissionEntity.PermissionScope.GAME);

        // 实验管理权限
        createPermissionIfNotExists("experiment:create", "Create experiments", 
            PermissionEntity.PermissionType.API, "experiment", PermissionEntity.PermissionAction.CREATE, PermissionEntity.PermissionScope.GAME);
        createPermissionIfNotExists("experiment:read", "Read experiments", 
            PermissionEntity.PermissionType.API, "experiment", PermissionEntity.PermissionAction.READ, PermissionEntity.PermissionScope.GAME);
        createPermissionIfNotExists("experiment:update", "Update experiments", 
            PermissionEntity.PermissionType.API, "experiment", PermissionEntity.PermissionAction.UPDATE, PermissionEntity.PermissionScope.GAME);
        createPermissionIfNotExists("experiment:delete", "Delete experiments", 
            PermissionEntity.PermissionType.API, "experiment", PermissionEntity.PermissionAction.DELETE, PermissionEntity.PermissionScope.GAME);

        // 风控规则管理权限
        createPermissionIfNotExists("risk_rule:create", "Create risk rules", 
            PermissionEntity.PermissionType.API, "risk_rule", PermissionEntity.PermissionAction.CREATE, PermissionEntity.PermissionScope.GAME);
        createPermissionIfNotExists("risk_rule:read", "Read risk rules", 
            PermissionEntity.PermissionType.API, "risk_rule", PermissionEntity.PermissionAction.READ, PermissionEntity.PermissionScope.GAME);
        createPermissionIfNotExists("risk_rule:update", "Update risk rules", 
            PermissionEntity.PermissionType.API, "risk_rule", PermissionEntity.PermissionAction.UPDATE, PermissionEntity.PermissionScope.GAME);
        createPermissionIfNotExists("risk_rule:delete", "Delete risk rules", 
            PermissionEntity.PermissionType.API, "risk_rule", PermissionEntity.PermissionAction.DELETE, PermissionEntity.PermissionScope.GAME);

        // 用户管理权限
        createPermissionIfNotExists("user:create", "Create users", 
            PermissionEntity.PermissionType.SYSTEM, "user", PermissionEntity.PermissionAction.CREATE, PermissionEntity.PermissionScope.GLOBAL);
        createPermissionIfNotExists("user:read", "Read users", 
            PermissionEntity.PermissionType.SYSTEM, "user", PermissionEntity.PermissionAction.READ, PermissionEntity.PermissionScope.GLOBAL);
        createPermissionIfNotExists("user:update", "Update users", 
            PermissionEntity.PermissionType.SYSTEM, "user", PermissionEntity.PermissionAction.UPDATE, PermissionEntity.PermissionScope.GLOBAL);
        createPermissionIfNotExists("user:delete", "Delete users", 
            PermissionEntity.PermissionType.SYSTEM, "user", PermissionEntity.PermissionAction.DELETE, PermissionEntity.PermissionScope.GLOBAL);

        // 审计日志权限
        createPermissionIfNotExists("audit_log:read", "Read audit logs", 
            PermissionEntity.PermissionType.SYSTEM, "audit_log", PermissionEntity.PermissionAction.READ, PermissionEntity.PermissionScope.GLOBAL);

        // 系统管理权限
        createPermissionIfNotExists("system:manage", "Manage system",
            PermissionEntity.PermissionType.SYSTEM, "system", PermissionEntity.PermissionAction.MANAGE, PermissionEntity.PermissionScope.GLOBAL);

        // 业务指标告警权限（生产种子见 V0.9.1__metric_alerts.sql）
        createPermissionIfNotExists("alert:read", "Read metric alerts",
            PermissionEntity.PermissionType.API, "alert", PermissionEntity.PermissionAction.READ, PermissionEntity.PermissionScope.GAME);
        createPermissionIfNotExists("alert:manage", "Manage metric alerts",
            PermissionEntity.PermissionType.API, "alert", PermissionEntity.PermissionAction.UPDATE, PermissionEntity.PermissionScope.GAME);

        // 隐私合规权限（生产种子见 V0.9.2__player_data_erasure.sql）
        createPermissionIfNotExists("privacy:read", "View privacy compliance",
            PermissionEntity.PermissionType.API, "privacy", PermissionEntity.PermissionAction.READ, PermissionEntity.PermissionScope.GAME);
        createPermissionIfNotExists("privacy:manage", "Manage privacy compliance",
            PermissionEntity.PermissionType.API, "privacy", PermissionEntity.PermissionAction.CREATE, PermissionEntity.PermissionScope.GAME);

        // 数据保留权限（生产种子见 V0.9.3__retention_enforcement.sql）
        createPermissionIfNotExists("retention:read", "View data retention",
            PermissionEntity.PermissionType.API, "retention", PermissionEntity.PermissionAction.READ, PermissionEntity.PermissionScope.GAME);
        createPermissionIfNotExists("retention:manage", "Manage data retention",
            PermissionEntity.PermissionType.API, "retention", PermissionEntity.PermissionAction.UPDATE, PermissionEntity.PermissionScope.GAME);
    }

    /**
     * 创建默认角色
     */
    private void createDefaultRoles() {
        // 超级管理员角色
        RoleEntity superAdmin = createRoleIfNotExists("super_admin", "Super Administrator", 
            RoleEntity.RoleType.SYSTEM, 100);
        if (superAdmin.permissions == null) {
            superAdmin.permissions = new HashSet<>();
        }
        superAdmin.permissions.addAll(permissionRepo.findAll());
        roleRepo.save(superAdmin);

        // 管理员角色
        RoleEntity admin = createRoleIfNotExists("admin", "Administrator", 
            RoleEntity.RoleType.SYSTEM, 90);
        if (admin.permissions == null) {
            admin.permissions = new HashSet<>();
        }
        admin.permissions.addAll(permissionRepo.findByType(PermissionEntity.PermissionType.API));
        admin.permissions.addAll(permissionRepo.findByType(PermissionEntity.PermissionType.SYSTEM));
        roleRepo.save(admin);

        // 经理角色
        RoleEntity manager = createRoleIfNotExists("manager", "Manager", 
            RoleEntity.RoleType.SYSTEM, 80);
        if (manager.permissions == null) {
            manager.permissions = new HashSet<>();
        }
        manager.permissions.addAll(permissionRepo.findByResourceType("game"));
        manager.permissions.addAll(permissionRepo.findByResourceType("environment"));
        manager.permissions.addAll(permissionRepo.findByResourceType("api_key"));
        manager.permissions.addAll(permissionRepo.findByResourceType("experiment"));
        roleRepo.save(manager);

        // 分析师角色
        RoleEntity analyst = createRoleIfNotExists("analyst", "Analyst", 
            RoleEntity.RoleType.SYSTEM, 70);
        if (analyst.permissions == null) {
            analyst.permissions = new HashSet<>();
        }
        analyst.permissions.addAll(permissionRepo.findByResourceTypeAndAction("game", PermissionEntity.PermissionAction.READ)
            .map(List::of).orElseGet(List::of));
        analyst.permissions.addAll(permissionRepo.findByResourceTypeAndAction("environment", PermissionEntity.PermissionAction.READ)
            .map(List::of).orElseGet(List::of));
        analyst.permissions.addAll(permissionRepo.findByResourceTypeAndAction("experiment", PermissionEntity.PermissionAction.READ)
            .map(List::of).orElseGet(List::of));
        roleRepo.save(analyst);

        // 开发者角色
        RoleEntity developer = createRoleIfNotExists("developer", "Developer", 
            RoleEntity.RoleType.SYSTEM, 60);
        if (developer.permissions == null) {
            developer.permissions = new HashSet<>();
        }
        developer.permissions.addAll(permissionRepo.findByResourceType("api_key"));
        developer.permissions.addAll(permissionRepo.findByResourceType("risk_rule"));
        roleRepo.save(developer);

        // 查看者角色
        RoleEntity viewer = createRoleIfNotExists("viewer", "Viewer",
            RoleEntity.RoleType.SYSTEM, 50);
        if (viewer.permissions == null) {
            viewer.permissions = new HashSet<>();
        }
        viewer.permissions.addAll(permissionRepo.findByAction(PermissionEntity.PermissionAction.READ));
        roleRepo.save(viewer);

        createBusinessRoles();
    }

    /**
     * P1 单公司多游戏业务角色（global/game/environment 三级 scope 均可分配）：
     * owner / operator / analyst / developer / risk_admin / viewer
     */
    private void createBusinessRoles() {
        List<PermissionEntity> all = permissionRepo.findAll();
        Set<PermissionEntity> allReads = new HashSet<>(permissionRepo.findByAction(PermissionEntity.PermissionAction.READ));

        // owner：公司所有者，全部权限（含用户与系统管理）
        RoleEntity owner = createRoleIfNotExists("owner", "Company Owner",
            RoleEntity.RoleType.SYSTEM, 95);
        if (owner.permissions == null) owner.permissions = new HashSet<>();
        owner.permissions.addAll(all);
        roleRepo.save(owner);

        // operator：运营，管理游戏配置/环境/密钥/实验，不碰用户与系统
        RoleEntity operator = createRoleIfNotExists("operator", "Game Operator",
            RoleEntity.RoleType.SYSTEM, 85);
        if (operator.permissions == null) operator.permissions = new HashSet<>();
        operator.permissions.addAll(resources(all, "game:read", "game:update",
            "environment:create", "environment:read", "environment:update", "environment:delete",
            "api_key:create", "api_key:read", "api_key:update", "api_key:delete",
            "experiment:create", "experiment:read", "experiment:update", "experiment:delete"));
        roleRepo.save(operator);

        // analyst：分析师，读全部 + 实验管理
        RoleEntity analyst = createRoleIfNotExists("analyst", "Data Analyst",
            RoleEntity.RoleType.SYSTEM, 75);
        if (analyst.permissions == null) analyst.permissions = new HashSet<>();
        analyst.permissions.addAll(allReads);
        analyst.permissions.addAll(resources(all, "experiment:create", "experiment:update", "experiment:delete"));
        roleRepo.save(analyst);

        // risk_admin：风控管理员，读全部 + 风控规则全权
        RoleEntity riskAdmin = createRoleIfNotExists("risk_admin", "Risk Admin",
            RoleEntity.RoleType.SYSTEM, 72);
        if (riskAdmin.permissions == null) riskAdmin.permissions = new HashSet<>();
        riskAdmin.permissions.addAll(allReads);
        riskAdmin.permissions.addAll(resources(all,
            "risk_rule:create", "risk_rule:update", "risk_rule:delete"));
        roleRepo.save(riskAdmin);

        // developer：开发者，读全部 + 密钥/实验/环境管理（调试接入向）
        RoleEntity developer = createRoleIfNotExists("developer", "Developer",
            RoleEntity.RoleType.SYSTEM, 65);
        if (developer.permissions == null) developer.permissions = new HashSet<>();
        developer.permissions.addAll(allReads);
        developer.permissions.addAll(resources(all,
            "api_key:create", "api_key:update", "api_key:delete",
            "experiment:create", "experiment:update", "experiment:delete",
            "environment:create", "environment:update"));
        roleRepo.save(developer);

        // viewer：只读（复用 createDefaultRoles 中的 viewer，READ 全部）
    }

    private Set<PermissionEntity> resources(List<PermissionEntity> all, String... permissionIds) {
        Set<String> ids = new HashSet<>(List.of(permissionIds));
        return all.stream().filter(p -> ids.contains(p.id)).collect(Collectors.toSet());
    }

    /**
     * 创建权限（如果不存在）
     */
    private void createPermissionIfNotExists(String id, String name, 
            PermissionEntity.PermissionType type, String resourceType,
            PermissionEntity.PermissionAction action, PermissionEntity.PermissionScope scope) {
        
        if (!permissionRepo.existsById(id)) {
            PermissionEntity permission = new PermissionEntity();
            permission.id = id;
            permission.name = name;
            permission.type = type;
            permission.resourceType = resourceType;
            permission.action = action;
            permission.scope = scope;
            permission.enabled = true;
            permission.system = true;
            permissionRepo.save(permission);
        }
    }

    /**
     * 创建角色（如果不存在）
     */
    private RoleEntity createRoleIfNotExists(String id, String name, 
            RoleEntity.RoleType type, Integer level) {
        
        return roleRepo.findById(id)
            .orElseGet(() -> {
                RoleEntity role = new RoleEntity();
                role.id = id;
                role.name = name;
                role.type = type;
                role.level = level;
                role.enabled = true;
                role.system = true;
                return roleRepo.save(role);
            });
    }
}