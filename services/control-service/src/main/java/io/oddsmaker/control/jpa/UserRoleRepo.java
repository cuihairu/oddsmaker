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
     * 根据角色ID查找用户角色关联
     */
    List<UserRoleEntity> findByRoleId(String roleId);

    /**
     * 根据用户ID和角色ID查找用户角色关联
     */
    Optional<UserRoleEntity> findByUserIdAndRoleId(String userId, String roleId);

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
     * 查找游戏级角色分配
     */
    @Query("SELECT ur FROM UserRoleEntity ur WHERE ur.userId = :userId AND ur.gameId = :gameId AND ur.environment IS NULL AND ur.enabled = true")
    List<UserRoleEntity> findGameScopedByUserIdAndGameId(@Param("userId") String userId, @Param("gameId") String gameId);

    /**
     * 查找环境级角色分配
     */
    @Query("SELECT ur FROM UserRoleEntity ur WHERE ur.userId = :userId AND ur.gameId = :gameId AND ur.environment = :environment AND ur.enabled = true")
    List<UserRoleEntity> findEnvironmentScopedByUserIdAndGameIdAndEnvironment(
            @Param("userId") String userId, @Param("gameId") String gameId, @Param("environment") String environment);

    /**
     * 检查用户是否拥有指定角色
     */
    @Query("SELECT COUNT(ur) > 0 FROM UserRoleEntity ur WHERE ur.userId = :userId AND ur.roleId = :roleId AND ur.enabled = true AND (ur.expiresAt IS NULL OR ur.expiresAt > :now)")
    boolean existsByUserIdAndRoleId(@Param("userId") String userId, @Param("roleId") String roleId, @Param("now") LocalDateTime now);

    /**
     * 检查用户是否拥有指定角色（在指定游戏中）
     */
    @Query("SELECT COUNT(ur) > 0 FROM UserRoleEntity ur WHERE ur.userId = :userId AND ur.roleId = :roleId AND ur.gameId = :gameId AND ur.enabled = true AND (ur.expiresAt IS NULL OR ur.expiresAt > :now)")
    boolean existsByUserIdAndRoleIdAndGameId(@Param("userId") String userId, @Param("roleId") String roleId, @Param("gameId") String gameId, @Param("now") LocalDateTime now);

    /**
     * 查找过期的用户角色关联
     */
    @Query("SELECT ur FROM UserRoleEntity ur WHERE ur.expiresAt IS NOT NULL AND ur.expiresAt <= :now AND ur.enabled = true")
    List<UserRoleEntity> findExpired(@Param("now") LocalDateTime now);

    /**
     * 删除用户的所有角色关联
     */
    void deleteByUserId(String userId);

    /**
     * 删除用户在指定游戏中的角色关联
     */
    void deleteByUserIdAndGameId(String userId, String gameId);

    /**
     * 统计角色分配数量
     */
    @Query("SELECT ur.roleId, COUNT(ur) as count FROM UserRoleEntity ur WHERE ur.enabled = true GROUP BY ur.roleId")
    List<Object> countByRoleId();

    /**
     * 统计用户的角色数量
     */
    @Query("SELECT ur.userId, COUNT(ur) as count FROM UserRoleEntity ur WHERE ur.enabled = true GROUP BY ur.userId")
    List<Object> countByUserId();
}