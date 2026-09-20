package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 用户-角色关联数据访问接口
 */
@Repository
public interface UserRoleRepo extends JpaRepository<UserRoleEntity, Long> {

    /**
     * 根据用户ID查找用户角色关联
     */
    List<UserRoleEntity> findByUserId(String userId);

    /**
     * 根据用户ID和游戏ID查找用户角色关联
     */
    List<UserRoleEntity> findByUserIdAndGameId(String userId, String gameId);

    /**
     * 根据用户ID、游戏ID和环境查找用户角色关联
     */
    List<UserRoleEntity> findByUserIdAndGameIdAndEnvironment(String userId, String gameId, String environment);

    /**
     * 查找有效的用户角色关联（启用且未过期）
     */
    @Query("SELECT ur FROM UserRoleEntity ur WHERE ur.userId = :userId AND ur.enabled = true AND (ur.expiresAt IS NULL OR ur.expiresAt > :now)")
    List<UserRoleEntity> findValidByUserId(@Param("userId") String userId, @Param("now") LocalDateTime now);

    /**
     * 查找全局角色分配
     */
    @Query("SELECT ur FROM UserRoleEntity ur WHERE ur.userId = :userId AND ur.gameId IS NULL AND ur.enabled = true")
    List<UserRoleEntity> findGlobalByUserId(@Param("userId") String userId);

    /**
     * 查找过期的用户角色关联
     */
    @Query("SELECT ur FROM UserRoleEntity ur WHERE ur.expiresAt IS NOT NULL AND ur.expiresAt <= :now AND ur.enabled = true")
    List<UserRoleEntity> findExpired(@Param("now") LocalDateTime now);

    /**
     * 统计用户的角色数量
     */
    @Query("SELECT ur.userId, COUNT(ur) as count FROM UserRoleEntity ur WHERE ur.enabled = true GROUP BY ur.userId")
    List<Object> countByUserId();
}