package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AuditLogRepo extends JpaRepository<AuditLogEntity, String> {

    /**
     * 根据用户ID查询审计日志
     */
    @Query("SELECT al FROM AuditLogEntity al WHERE al.userId = :userId ORDER BY al.createdAt DESC")
    List<AuditLogEntity> findByUserId(@Param("userId") String userId);

    /**
     * 根据资源类型和ID查询审计日志
     */
    @Query("SELECT al FROM AuditLogEntity al WHERE al.resourceType = :resourceType AND al.resourceId = :resourceId ORDER BY al.createdAt DESC")
    List<AuditLogEntity> findByResource(@Param("resourceType") String resourceType, @Param("resourceId") String resourceId);

    /**
     * 根据游戏ID查询审计日志
     */
    @Query("SELECT al FROM AuditLogEntity al WHERE al.scopeGameId = :gameId ORDER BY al.createdAt DESC")
    List<AuditLogEntity> findByGameId(@Param("gameId") String gameId);

    /**
     * 查询安全事件
     */
    @Query("SELECT al FROM AuditLogEntity al WHERE al.action IN ('SECURITY_ALERT', 'RATE_LIMIT_EXCEEDED', 'UNAUTHORIZED_ACCESS', 'SUSPICIOUS_ACTIVITY') ORDER BY al.createdAt DESC")
    List<AuditLogEntity> findSecurityEvents();

    /**
     * 查询敏感操作
     */
    @Query("SELECT al FROM AuditLogEntity al WHERE al.action IN ('API_KEY_REVEAL', 'PASSWORD_CHANGE', 'DELETE', 'BULK_DELETE', 'GRANT', 'REVOKE') AND al.result = 'SUCCESS' ORDER BY al.createdAt DESC")
    List<AuditLogEntity> findSensitiveActions();

    /**
     * 根据操作类型查询
     */
    @Query("SELECT al FROM AuditLogEntity al WHERE al.action = :action ORDER BY al.createdAt DESC")
    List<AuditLogEntity> findByAction(@Param("action") AuditLogEntity.AuditAction action);

    /**
     * 查询失败的操作
     */
    @Query("SELECT al FROM AuditLogEntity al WHERE al.result IN ('FAILURE', 'ERROR', 'BLOCKED') ORDER BY al.createdAt DESC")
    List<AuditLogEntity> findFailedOperations();

    /**
     * 按时间范围查询
     */
    @Query("SELECT al FROM AuditLogEntity al WHERE al.createdAt >= :startTime AND al.createdAt <= :endTime ORDER BY al.createdAt DESC")
    List<AuditLogEntity> findByTimeRange(@Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime);

    /**
     * 查询需要立即告警的日志
     */
    @Query("SELECT al FROM AuditLogEntity al WHERE (al.action IN ('SECURITY_ALERT', 'RATE_LIMIT_EXCEEDED', 'UNAUTHORIZED_ACCESS', 'SUSPICIOUS_ACTIVITY') OR (al.result IN ('FAILURE', 'ERROR', 'BLOCKED') AND al.action IN ('API_KEY_REVEAL', 'PASSWORD_CHANGE', 'DELETE', 'BULK_DELETE', 'GRANT', 'REVOKE'))) AND al.alerted = false ORDER BY al.createdAt ASC")
    List<AuditLogEntity> findRequiringImmediateAlert();

    /**
     * 清理过期日志
     */
    @Query("DELETE FROM AuditLogEntity al WHERE al.expireAt < :currentTime")
    int deleteExpiredLogs(@Param("currentTime") LocalDateTime currentTime);

    /**
     * 统计用户操作次数
     */
    @Query("SELECT COUNT(al) FROM AuditLogEntity al WHERE al.userId = :userId AND al.createdAt >= :since")
    long countOperationsByUser(@Param("userId") String userId, @Param("since") LocalDateTime since);

    /**
     * 统计失败操作次数
     */
    @Query("SELECT COUNT(al) FROM AuditLogEntity al WHERE al.userId = :userId AND al.result IN ('FAILURE', 'ERROR', 'BLOCKED') AND al.createdAt >= :since")
    long countFailedOperationsByUser(@Param("userId") String userId, @Param("since") LocalDateTime since);

    /**
     * 标记已告警
     */
    @Query("UPDATE AuditLogEntity al SET al.alerted = true WHERE al.id = :logId")
    void markAsAlerted(@Param("logId") String logId);
}
