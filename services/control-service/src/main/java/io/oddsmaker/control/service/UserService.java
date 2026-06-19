package io.oddsmaker.control.service;

import io.oddsmaker.control.dto.UserDTO;
import io.oddsmaker.control.jpa.*;
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
import java.util.stream.Collectors;

/**
 * 用户权限管理服务
 * 提供完整的用户生命周期和权限管理功能
 */
@Service
@Transactional
public class UserService {

    private static final Logger logger = LoggerFactory.getLogger(UserService.class);

    @Autowired
    private UserRepo userRepo;

    @Autowired
    private UserRoleRepo userRoleRepo;

    @Autowired
    private GameRepo gameRepo;

    @Autowired
    private GameEnvironmentRepo gameEnvironmentRepo;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /**
     * 创建新用户
     */
    public UserDTO createUser(UserDTO dto, String password) {
        logger.info("Creating user: {}", dto.email);

        // 检查邮箱是否已存在
        if (userRepo.findByEmailAndDeletedAtIsNull(dto.email).isPresent()) {
            throw new IllegalArgumentException("Email already exists: " + dto.email);
        }

        // 生成唯一ID
        if (dto.id == null || dto.id.trim().isEmpty()) {
            dto.id = "user_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }

        // 设置默认值
        if (dto.globalRole == null) {
            dto.globalRole = UserEntity.GlobalRole.USER;
        }

        // 转换并保存
        UserEntity entity = dto.toEntity();
        if (password != null && !password.trim().isEmpty()) {
            entity.passwordHash = passwordEncoder.encode(password);
        }

        // 生成邮箱验证令牌
        entity.emailVerificationToken = UUID.randomUUID().toString();
        entity.emailVerified = false;

        entity = userRepo.save(entity);

        logger.info("User created successfully: {} (ID: {})", entity.email, entity.id);
        return new UserDTO(entity);
    }

    /**
     * 更新用户信息
     */
    public UserDTO updateUser(String userId, UserDTO dto) {
        logger.info("Updating user: {}", userId);

        UserEntity entity = userRepo.findById(userId)
            .filter(user -> user.deletedAt == null)
            .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        // 检查邮箱变更
        if (dto.email != null && !dto.email.equals(entity.email)) {
            if (userRepo.findByEmailAndDeletedAtIsNull(dto.email).isPresent()) {
                throw new IllegalArgumentException("Email already exists: " + dto.email);
            }
            entity.emailVerified = false;
            entity.emailVerificationToken = UUID.randomUUID().toString();
        }

        dto.updateEntity(entity);
        entity = userRepo.save(entity);

        logger.info("User updated successfully: {}", userId);
        return new UserDTO(entity);
    }

    /**
     * 删除用户（软删除）
     */
    public void deleteUser(String userId) {
        logger.info("Deleting user: {}", userId);

        UserEntity entity = userRepo.findById(userId)
            .filter(user -> user.deletedAt == null)
            .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        // 检查是否是超级管理员：禁止删除最后一位 SUPER_ADMIN
        if (entity.globalRole == UserEntity.GlobalRole.SUPER_ADMIN) {
            long cnt = userRepo.countByGlobalRoleAndDeletedAtIsNull(UserEntity.GlobalRole.SUPER_ADMIN);
            if (cnt <= 1) {
                throw new IllegalStateException("Cannot delete the last SUPER_ADMIN user");
            }
        }

        // 软删除用户
        entity.deletedAt = LocalDateTime.now();
        entity.status = UserEntity.UserStatus.DELETED;
        userRepo.save(entity);

        // 删除所有角色关联
        userRoleRepo.deleteByUserId(userId);

        logger.info("User deleted successfully: {}", userId);
    }

    /**
     * 根据ID获取用户
     */
    @Transactional(readOnly = true)
    public Optional<UserDTO> getUser(String userId) {
        return userRepo.findById(userId)
            .filter(user -> user.deletedAt == null)
            .map(entity -> {
                UserDTO dto = new UserDTO(entity);
                enrichWithRoles(dto);
                return dto;
            });
    }

    /**
     * 根据邮箱获取用户
     */
    @Transactional(readOnly = true)
    public Optional<UserDTO> getUserByEmail(String email) {
        return userRepo.findByEmailAndDeletedAtIsNull(email)
            .map(entity -> {
                UserDTO dto = new UserDTO(entity);
                enrichWithRoles(dto);
                return dto;
            });
    }

    /**
     * 搜索用户
     */
    @Transactional(readOnly = true)
    public Page<UserDTO> searchUsers(String query, Pageable pageable) {
        return userRepo.searchUsers(query, pageable)
            .map(entity -> {
                UserDTO dto = new UserDTO(entity);
                enrichWithRoles(dto);
                return dto;
            });
    }

    /**
     * 验证用户邮箱
     */
    public boolean verifyEmail(String token) {
        logger.info("Verifying email with token: {}", token);

        Optional<UserEntity> userOpt = userRepo.findByEmailVerificationTokenAndDeletedAtIsNull(token);
        if (userOpt.isEmpty()) {
            return false;
        }

        UserEntity user = userOpt.get();
        user.emailVerified = true;
        user.emailVerificationToken = null;
        userRepo.save(user);

        logger.info("Email verified successfully for user: {}", user.email);
        return true;
    }

    /**
     * 重置密码请求
     */
    public boolean requestPasswordReset(String email) {
        logger.info("Password reset requested for email: {}", email);

        Optional<UserEntity> userOpt = userRepo.findByEmailAndDeletedAtIsNull(email);
        if (userOpt.isEmpty()) {
            // 为安全起见，不透露用户是否存在
            return true;
        }

        UserEntity user = userOpt.get();
        user.passwordResetToken = UUID.randomUUID().toString();
        user.passwordResetExpires = LocalDateTime.now().plusHours(1); // 1小时过期
        userRepo.save(user);

        logger.info("Password reset token generated for user: {}", email);
        return true;
    }

    /**
     * 重置密码
     */
    public boolean resetPassword(String token, String newPassword) {
        logger.info("Resetting password with token: {}", token);

        Optional<UserEntity> userOpt = userRepo.findByPasswordResetTokenAndDeletedAtIsNull(token);
        if (userOpt.isEmpty()) {
            return false;
        }

        UserEntity user = userOpt.get();
        if (user.passwordResetExpires == null || user.passwordResetExpires.isBefore(LocalDateTime.now())) {
            return false;
        }

        user.passwordHash = passwordEncoder.encode(newPassword);
        user.passwordResetToken = null;
        user.passwordResetExpires = null;
        user.resetLoginAttempts(); // 重置登录尝试次数
        userRepo.save(user);

        logger.info("Password reset successfully for user: {}", user.email);
        return true;
    }

    /**
     * 验证用户密码
     */
    @Transactional(readOnly = true)
    public boolean validatePassword(String email, String password) {
        Optional<UserEntity> userOpt = userRepo.findByEmailAndDeletedAtIsNull(email);
        if (userOpt.isEmpty()) {
            return false;
        }

        UserEntity user = userOpt.get();
        if (user.passwordHash == null) {
            return false;
        }

        return passwordEncoder.matches(password, user.passwordHash);
    }

    /**
     * 记录登录成功
     */
    public void recordSuccessfulLogin(String userId, String ipAddress) {
        userRepo.findById(userId).ifPresent(user -> {
            user.lastLogin = LocalDateTime.now();
            user.lastLoginIp = ipAddress;
            user.resetLoginAttempts();
            userRepo.save(user);
        });
    }

    /**
     * 记录登录失败
     */
    public void recordFailedLogin(String email, String ipAddress) {
        userRepo.findByEmailAndDeletedAtIsNull(email).ifPresent(user -> {
            user.incrementLoginAttempts();
            userRepo.save(user);
        });
    }

    /**
     * 为用户分配角色
     */
    public void assignRole(String userId, UserRoleEntity.RoleType role,
                          UserRoleEntity.PermissionScope scope,
                          String gameId, String environmentId) {
        logger.info("Assigning role {} to user {}", role, userId);

        // 验证用户存在
        UserEntity user = userRepo.findById(userId)
            .filter(u -> u.deletedAt == null)
            .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        // 验证权限范围的一致性
        validateRoleScope(role, scope, gameId, environmentId);

        // 检查是否已有相同角色
        List<UserRoleEntity> existingRoles = gameId != null
            ? userRoleRepo.findByUserIdAndGameId(userId, gameId)
            : userRoleRepo.findByUserId(userId);
        boolean hasRole = existingRoles.stream()
            .anyMatch(r -> r.role == role && Objects.equals(r.gameId, gameId) && Objects.equals(r.environmentId, environmentId));

        if (hasRole) {
            throw new IllegalStateException("User already has this role in the specified scope");
        }

        // 创建新角色
        UserRoleEntity roleEntity = new UserRoleEntity();
        roleEntity.id = "role_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        roleEntity.userId = userId;
        roleEntity.gameId = gameId;
        roleEntity.environmentId = environmentId;
        roleEntity.role = role;
        roleEntity.scope = scope;
        roleEntity.invitationAccepted = true; // 直接分配，无需邀请

        userRoleRepo.save(roleEntity);

        logger.info("Role assigned successfully: {} to user {}", role, userId);
    }

    /**
     * 移除用户角色
     */
    public void removeRole(String userId, String roleId) {
        logger.info("Removing role {} from user {}", roleId, userId);

        UserRoleEntity role = userRoleRepo.findById(roleId)
            .filter(r -> r.userId.equals(userId))
            .orElseThrow(() -> new IllegalArgumentException("Role not found or not owned by user"));

        userRoleRepo.delete(role);

        logger.info("Role removed successfully: {} from user {}", roleId, userId);
    }

    /**
     * 检查用户权限
     */
    @Transactional(readOnly = true)
    public boolean hasPermission(String userId, UserRoleEntity.RoleType role, String gameId, String environmentId) {
        return userRoleRepo.hasRole(userId, role, gameId, environmentId, LocalDateTime.now());
    }

    /**
     * 获取用户所有角色
     */
    @Transactional(readOnly = true)
    public List<UserRoleEntity> getUserRoles(String userId) {
        return userRoleRepo.findByUserId(userId);
    }

    // 私有辅助方法

    /**
     * 验证角色范围的一致性
     */
    private void validateRoleScope(UserRoleEntity.RoleType role, UserRoleEntity.PermissionScope scope,
                                  String gameId, String environmentId) {
        switch (scope) {
            case GLOBAL:
                if (gameId != null || environmentId != null) {
                    throw new IllegalArgumentException("Global scope should not have game/environment ID");
                }
                break;
            case GAME:
                if (gameId == null || environmentId != null) {
                    throw new IllegalArgumentException("Game scope requires game ID only");
                }
                break;
            case ENVIRONMENT:
                if (gameId == null || environmentId == null) {
                    throw new IllegalArgumentException("Environment scope requires game ID and environment ID");
                }
                break;
        }

        if (gameId != null) {
            GameEntity game = gameRepo.findById(gameId)
                .filter(game -> game.deletedAt == null)
                .orElseThrow(() -> new IllegalArgumentException("Game not found: " + gameId));

            if (environmentId != null) {
                GameEnvironmentEntity environment = gameEnvironmentRepo.findById(environmentId)
                    .filter(env -> env.deletedAt == null)
                    .orElseThrow(() -> new IllegalArgumentException("Environment not found: " + environmentId));

                if (!Objects.equals(environment.gameId, game.id)) {
                    throw new IllegalArgumentException("Environment does not belong to game: " + environmentId);
                }
            }
        }
    }

    /**
     * 丰富用户信息的角色数据
     */
    private void enrichWithRoles(UserDTO dto) {
        List<UserRoleEntity> roles = userRoleRepo.findByUserId(dto.id);
        dto.roles = roles.stream()
            .map(role -> Map.of(
                "id", role.id,
                "role", role.role.toString(),
                "scope", role.scope.toString(),
                "gameId", role.gameId != null ? role.gameId : "",
                "environmentId", role.environmentId != null ? role.environmentId : ""
            ))
            .collect(Collectors.toList());
    }
}
