package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 用户角色数据访问接口
 */
@Repository
public interface UserRoleRepo extends JpaRepository<UserRoleEntity, String> {

    /**
     * 根据用户ID查找角色
     */
    List<UserRoleEntity> findByUserId(String userId);

    /**
     * 根据用户ID和游戏ID查找角色
     */
    List<UserRoleEntity> findByUserIdAndGameId(String userId, String gameId);

    /**
     * 根据游戏ID查找所有用户角色
     */
    List<UserRoleEntity> findByGameId(String gameId);

    /**
     * 查找指定角色的用户
     */
    List<UserRoleEntity> findByRole(UserRoleEntity.RoleType role);

    /**
     * 查找指定范围的角色
     */
    List<UserRoleEntity> findByScope(UserRoleEntity.PermissionScope scope);

    /**
     * 检查用户是否有指定权限
     */
    @Query("SELECT COUNT(ur) > 0 FROM UserRoleEntity ur WHERE " +
           "ur.userId = :userId AND ur.role = :role AND " +
           "(:gameId IS NULL OR ur.gameId = :gameId) AND " +
           "(:environmentId IS NULL OR ur.environmentId = :environmentId) AND " +
           "(ur.expiresAt IS NULL OR ur.expiresAt > :now)")
    boolean hasRole(@Param("userId") String userId,
                   @Param("role") UserRoleEntity.RoleType role,
                   @Param("gameId") String gameId,
                   @Param("environmentId") String environmentId,
                   @Param("now") LocalDateTime now);

    /**
     * 查找待接受的邀请角色
     */
    List<UserRoleEntity> findByInvitationAcceptedFalseAndInvitationExpiresAfter(LocalDateTime now);

    /**
     * 查找过期的角色
     */
    List<UserRoleEntity> findByExpiresAtBefore(LocalDateTime now);

    /**
     * 根据邀请令牌查找角色
     */
    Optional<UserRoleEntity> findByInvitationToken(String token);

    /**
     * 删除用户的所有角色
     */
    void deleteByUserId(String userId);

    /**
     * 删除游戏下的所有角色
     */
    void deleteByGameId(String gameId);
}
