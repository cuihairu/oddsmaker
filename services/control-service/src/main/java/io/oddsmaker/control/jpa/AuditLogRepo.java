package io.oddsmaker.control.jpa;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 审计日志数据访问接口
 */
@Repository
public interface AuditLogRepo extends JpaRepository<AuditLogEntity, String> {

    /**
     * 根据用户ID查找审计日志
     */
    Page<AuditLogEntity> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

    /**
     * 根据资源类型和ID查找审计日志
     */
    Page<AuditLogEntity> findByResourceTypeAndResourceIdOrderByCreatedAtDesc(
            String resourceType, String resourceId, Pageable pageable);

    /**
     * 根据操作类型查找审计日志
     */
    Page<AuditLogEntity> findByActionOrderByCreatedAtDesc(
            AuditLogEntity.AuditAction action, Pageable pageable);

    /**
     * 根据状态查找审计日志
     */
    Page<AuditLogEntity> findByStatusOrderByCreatedAtDesc(
            AuditLogEntity.AuditStatus status, Pageable pageable);

    /**
     * 根据时间范围查找审计日志
     */
    Page<AuditLogEntity> findByCreatedAtBetweenOrderByCreatedAtDesc(
            LocalDateTime start, LocalDateTime end, Pageable pageable);

    /**
     * 根据游戏ID查找审计日志
     */
    Page<AuditLogEntity> findByGameIdOrderByCreatedAtDesc(String gameId, Pageable pageable);

    /**
     * 查找失败的审计日志
     */
    @Query("SELECT a FROM AuditLogEntity a WHERE a.status = 'FAILURE' ORDER BY a.createdAt DESC")
    Page<AuditLogEntity> findFailedActions(Pageable pageable);

    /**
     * 查找认证相关的审计日志
     */
    @Query("SELECT a FROM AuditLogEntity a WHERE a.action IN ('LOGIN', 'LOGOUT', 'LOGIN_FAILED', 'PASSWORD_CHANGE', 'PASSWORD_RESET') ORDER BY a.createdAt DESC")
    Page<AuditLogEntity> findAuthActions(Pageable pageable);

    /**
     * 查找敏感操作的审计日志
     */
    @Query("SELECT a FROM AuditLogEntity a WHERE a.action IN ('DELETE', 'EXPORT', 'GRANT_ROLE', 'REVOKE_ROLE', 'SYSTEM_BACKUP') ORDER BY a.createdAt DESC")
    Page<AuditLogEntity> findSensitiveActions(Pageable pageable);

    /**
     * 统计用户操作次数
     */
    @Query("SELECT a.userId, a.username, COUNT(a) as actionCount FROM AuditLogEntity a WHERE a.createdAt >= :since GROUP BY a.userId, a.username ORDER BY actionCount DESC")
    List<Object> countActionsByUser(@Param("since") LocalDateTime since);

    /**
     * 统计资源操作次数
     */
    @Query("SELECT a.resourceType, COUNT(a) as actionCount FROM AuditLogEntity a WHERE a.createdAt >= :since GROUP BY a.resourceType ORDER BY actionCount DESC")
    List<Object> countActionsByResourceType(@Param("since") LocalDateTime since);

    /**
     * 统计操作类型次数
     */
    @Query("SELECT a.action, COUNT(a) as actionCount FROM AuditLogEntity a WHERE a.createdAt >= :since GROUP BY a.action ORDER BY actionCount DESC")
    List<Object> countActionsByActionType(@Param("since") LocalDateTime since);

    /**
     * 统计每小时操作次数
     */
    @Query("SELECT FUNCTION('DATE_FORMAT', a.createdAt, '%Y-%m-%d %H:00') as hour, COUNT(a) as actionCount FROM AuditLogEntity a WHERE a.createdAt >= :since GROUP BY FUNCTION('DATE_FORMAT', a.createdAt, '%Y-%m-%d %H:00') ORDER BY hour")
    List<Object> countActionsByHour(@Param("since") LocalDateTime since);

    /**
     * 查找最近的审计日志
     */
    @Query("SELECT a FROM AuditLogEntity a ORDER BY a.createdAt DESC")
    Page<AuditLogEntity> findRecentLogs(Pageable pageable);

    /**
     * 搜索审计日志
     */
    @Query("SELECT a FROM AuditLogEntity a WHERE " +
           "(LOWER(a.username) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(a.resourceType) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(a.resourceName) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(a.details) LIKE LOWER(CONCAT('%', :query, '%'))) " +
           "ORDER BY a.createdAt DESC")
    Page<AuditLogEntity> searchLogs(@Param("query") String query, Pageable pageable);

    /**
     * 清理旧的审计日志
     */
    @Query("DELETE FROM AuditLogEntity a WHERE a.createdAt < :before")
    int deleteLogsBefore(@Param("before") LocalDateTime before);

    /**
     * 统计审计日志总数
     */
    @Query("SELECT COUNT(a) FROM AuditLogEntity a WHERE a.createdAt >= :since")
    long countLogsSince(@Param("since") LocalDateTime since);
}