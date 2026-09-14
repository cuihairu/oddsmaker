package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.UserEntity;
import io.oddsmaker.control.jpa.UserRepo;
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

    /**
     * 创建用户
     */
    public UserEntity createUser(UserEntity user, String operatorId) {
        logger.info("Creating user: {}", user.username);

        // 检查用户名是否已存在
        if (userRepo.existsByUsername(user.username)) {
            throw new IllegalArgumentException("Username already exists: " + user.username);
        }

        // 检查邮箱是否已存在
        if (user.email != null && userRepo.existsByEmail(user.email)) {
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

        // 设置默认角色
        if (user.roles == null || user.roles.isEmpty()) {
            user.roles = Set.of(UserEntity.UserRole.VIEWER);
        }

        user = userRepo.save(user);

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
        if (updates.avatar != null) {
            user.avatar = updates.avatar;
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
     * 记录用户登录
     */
    public void recordLogin(String userId, String ip) {
        logger.info("Recording login for user: {} from IP: {}", userId, ip);

        UserEntity user = userRepo.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        user.recordLogin(ip);
        userRepo.save(user);

        // 记录审计日志
        auditLogRepo.save(createAuditLog(
            userId, user.username, AuditLogEntity.AuditAction.LOGIN,
            "user", user.id, user.username,
            null, "Login from " + ip, "SUCCESS", ip
        ));
    }

    /**
     * 记录用户登出
     */
    public void recordLogout(String userId, String ip) {
        logger.info("Recording logout for user: {} from IP: {}", userId, ip);

        UserEntity user = userRepo.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        // 记录审计日志
        auditLogRepo.save(createAuditLog(
            userId, user.username, AuditLogEntity.AuditAction.LOGOUT,
            "user", user.id, user.username,
            null, "Logout from " + ip, "SUCCESS", ip
        ));
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