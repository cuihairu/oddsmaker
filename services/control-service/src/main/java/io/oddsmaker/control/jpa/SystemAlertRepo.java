package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface SystemAlertRepo extends JpaRepository<SystemAlertEntity, String> {

    /**
     * 查找开放的告警
     */
    @Query("SELECT sa FROM SystemAlertEntity sa WHERE sa.alertStatus = 'OPEN' AND sa.deletedAt IS NULL ORDER BY sa.severity DESC, sa.createdAt DESC")
    List<SystemAlertEntity> findOpen();

    /**
     * 查找活跃的告警
     */
    @Query("SELECT sa FROM SystemAlertEntity sa WHERE sa.alertStatus IN ('OPEN', 'ACKNOWLEDGED', 'INVESTIGATING') AND sa.deletedAt IS NULL ORDER BY sa.severity DESC, sa.createdAt DESC")
    List<SystemAlertEntity> findActive();

    /**
     * 查找严重告警
     */
    @Query("SELECT sa FROM SystemAlertEntity sa WHERE sa.severity IN ('CRITICAL', 'EMERGENCY') AND sa.alertStatus IN ('OPEN', 'ACKNOWLEDGED') AND sa.deletedAt IS NULL ORDER BY sa.createdAt DESC")
    List<SystemAlertEntity> findCritical();

    /**
     * 根据类型查找
     */
    @Query("SELECT sa FROM SystemAlertEntity sa WHERE sa.alertType = :type AND sa.deletedAt IS NULL ORDER BY sa.createdAt DESC")
    List<SystemAlertEntity> findByType(@Param("type") SystemAlertEntity.AlertType type);

    /**
     * 根据来源查找
     */
    @Query("SELECT sa FROM SystemAlertEntity sa WHERE sa.source = :source AND sa.deletedAt IS NULL ORDER BY sa.createdAt DESC")
    List<SystemAlertEntity> findBySource(@Param("source") String source);

    /**
     * 查找需要升级的告警
     */
    @Query("SELECT sa FROM SystemAlertEntity sa WHERE sa.severity IN ('CRITICAL', 'EMERGENCY') AND sa.alertStatus = 'OPEN' AND sa.escalationLevel = 0 AND sa.deletedAt IS NULL")
    List<SystemAlertEntity> findNeedingEscalation();

    /**
     * 查找需要解除暂停的告警
     */
    @Query("SELECT sa FROM SystemAlertEntity sa WHERE sa.alertStatus = 'SNOOZED' AND sa.snoozedUntil < :now AND sa.deletedAt IS NULL")
    List<SystemAlertEntity> findSnoozedExpired(@Param("now") LocalDateTime now);

    /**
     * 统计告警数量
     */
    @Query("SELECT COUNT(sa) FROM SystemAlertEntity sa WHERE sa.alertStatus = :status AND sa.deletedAt IS NULL")
    long countByStatus(@Param("status") SystemAlertEntity.AlertStatus status);

    /**
     * 统计严重级别的告警数量
     */
    @Query("SELECT COUNT(sa) FROM SystemAlertEntity sa WHERE sa.severity = :severity AND sa.alertStatus IN ('OPEN', 'ACKNOWLEDGED') AND sa.deletedAt IS NULL")
    long countActiveBySeverity(@Param("severity") SystemAlertEntity.Severity severity);

    /**
     * 按规则查找最近的活跃告警（业务指标告警沿触发去重）
     */
    @Query("SELECT sa FROM SystemAlertEntity sa WHERE sa.ruleId = :ruleId AND sa.alertStatus IN ('OPEN', 'ACKNOWLEDGED', 'INVESTIGATING', 'SNOOZED') AND sa.deletedAt IS NULL ORDER BY sa.lastOccurredAt DESC")
    List<SystemAlertEntity> findActiveByRuleId(@Param("ruleId") String ruleId);

    /**
     * 按游戏查找告警（业务告警历史）
     */
    @Query("SELECT sa FROM SystemAlertEntity sa WHERE sa.gameId = :gameId AND sa.deletedAt IS NULL ORDER BY sa.lastOccurredAt DESC")
    List<SystemAlertEntity> findByGameId(@Param("gameId") String gameId, org.springframework.data.domain.Pageable pageable);

    /**
     * 删除过期告警
     */
    @Query("DELETE FROM SystemAlertEntity sa WHERE sa.alertStatus = 'CLOSED' AND sa.resolvedAt < :expireBefore")
    int deleteClosedBefore(@Param("expireBefore") LocalDateTime expireBefore);
}
