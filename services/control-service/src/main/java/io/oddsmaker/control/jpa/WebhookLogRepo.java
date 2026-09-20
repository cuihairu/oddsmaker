package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface WebhookLogRepo extends JpaRepository<WebhookLogEntity, String> {

    /**
     * 根据Webhook配置查找日志
     */
    @Query("SELECT wl FROM WebhookLogEntity wl WHERE wl.webhookConfigId = :webhookConfigId ORDER BY wl.createdAt DESC")
    List<WebhookLogEntity> findByWebhookConfigId(@Param("webhookConfigId") String webhookConfigId);

    /**
     * 根据游戏查找日志
     */
    @Query("SELECT wl FROM WebhookLogEntity wl WHERE wl.gameId = :gameId ORDER BY wl.createdAt DESC")
    List<WebhookLogEntity> findByGameId(@Param("gameId") String gameId);

    /**
     * 根据风险案例查找日志
     */
    @Query("SELECT wl FROM WebhookLogEntity wl WHERE wl.riskCaseId = :riskCaseId ORDER BY wl.createdAt DESC")
    List<WebhookLogEntity> findByRiskCaseId(@Param("riskCaseId") String riskCaseId);

    /**
     * 查找待重试的日志
     */
    @Query("SELECT wl FROM WebhookLogEntity wl WHERE wl.deliveryStatus = 'RETRYING' AND wl.nextRetryAt <= :now")
    List<WebhookLogEntity> findPendingRetries(@Param("now") LocalDateTime now);

    /**
     * 查找待处理的日志
     */
    @Query("SELECT wl FROM WebhookLogEntity wl WHERE wl.deliveryStatus = 'PENDING' ORDER BY wl.createdAt ASC")
    List<WebhookLogEntity> findPending();

    /**
     * 根据时间范围查找日志
     */
    @Query("SELECT wl FROM WebhookLogEntity wl WHERE wl.gameId = :gameId AND wl.createdAt >= :startTime AND wl.createdAt <= :endTime ORDER BY wl.createdAt DESC")
    List<WebhookLogEntity> findByGameIdAndTimeRange(@Param("gameId") String gameId, @Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime);

    /**
     * 删除过期日志
     */
    @Query("DELETE FROM WebhookLogEntity wl WHERE wl.createdAt < :expireAt")
    int deleteExpiredLogs(@Param("expireAt") LocalDateTime expireAt);

}
