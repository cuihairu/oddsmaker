package io.oddsmaker.control.jpa;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 用户数据访问接口
 */
@Repository
public interface UserRepo extends JpaRepository<UserEntity, String> {

    /**
     * 根据用户名查找用户
     */
    Optional<UserEntity> findByUsername(String username);

    /**
     * 根据邮箱查找用户
     */
    Optional<UserEntity> findByEmail(String email);

    /**
     * 根据Keycloak ID查找用户
     */
    Optional<UserEntity> findByKeycloakId(String keycloakId);

    /**
     * 查找所有活跃用户（分页）
     */
    Page<UserEntity> findByStatusAndDeletedAtIsNull(UserEntity.UserStatus status, Pageable pageable);

    /**
     * 根据角色查找用户
     */
    @Query("SELECT u FROM UserEntity u JOIN u.roles r WHERE r = :role AND u.deletedAt IS NULL")
    List<UserEntity> findByRole(@Param("role") UserEntity.UserRole role);

    /**
     * 根据状态查找用户
     */
    List<UserEntity> findByStatusAndDeletedAtIsNull(UserEntity.UserStatus status);

    /**
     * 根据名称搜索用户
     */
    @Query("SELECT u FROM UserEntity u WHERE " +
           "(LOWER(u.username) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(u.email) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(u.displayName) LIKE LOWER(CONCAT('%', :query, '%'))) AND " +
           "u.deletedAt IS NULL")
    Page<UserEntity> searchByName(@Param("query") String query, Pageable pageable);

    /**
     * 统计活跃用户数量
     */
    long countByStatusAndDeletedAtIsNull(UserEntity.UserStatus status);

    /**
     * 查找最近登录的用户
     */
    @Query("SELECT u FROM UserEntity u WHERE u.lastLoginAt IS NOT NULL AND u.deletedAt IS NULL ORDER BY u.lastLoginAt DESC")
    List<UserEntity> findRecentlyLoggedIn(Pageable pageable);

    /**
     * 查找指定时间后登录的用户
     */
    @Query("SELECT u FROM UserEntity u WHERE u.lastLoginAt >= :since AND u.deletedAt IS NULL")
    List<UserEntity> findLoggedInSince(@Param("since") LocalDateTime since);

    /**
     * 查找从未登录的用户
     */
    @Query("SELECT u FROM UserEntity u WHERE u.lastLoginAt IS NULL AND u.deletedAt IS NULL")
    List<UserEntity> findNeverLoggedIn();

    /**
     * 检查用户名是否存在
     */
    boolean existsByUsername(String username);

    /**
     * 检查邮箱是否存在
     */
    boolean existsByEmail(String email);

    /**
     * 检查Keycloak ID是否存在
     */
    boolean existsByKeycloakId(String keycloakId);

    /**
     * 获取用户统计信息
     */
    @Query("SELECT new map(" +
           "COUNT(u) as totalUsers, " +
           "COUNT(CASE WHEN u.status = 'ACTIVE' THEN 1 END) as activeUsers, " +
           "COUNT(CASE WHEN u.status = 'INACTIVE' THEN 1 END) as inactiveUsers, " +
           "COUNT(CASE WHEN u.status = 'LOCKED' THEN 1 END) as lockedUsers, " +
           "COUNT(CASE WHEN u.lastLoginAt >= :since THEN 1 END) as activeSince" +
           ") FROM UserEntity u WHERE u.deletedAt IS NULL")
    List<Object> getUserStatistics(@Param("since") LocalDateTime since);

    /**
     * 查找启用双因素认证的用户
     */
    List<UserEntity> findByTwoFactorEnabledTrueAndDeletedAtIsNull();

    /**
     * 根据时区查找用户
     */
    List<UserEntity> findByTimeZoneAndDeletedAtIsNull(String timezone);

    /**
     * 根据语言查找用户
     */
    List<UserEntity> findByLanguageAndDeletedAtIsNull(String language);
}