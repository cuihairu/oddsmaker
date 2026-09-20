package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WebhookConfigRepo extends JpaRepository<WebhookConfigEntity, String> {

    /**
     * 查找游戏的所有Webhook配置
     */
    @Query("SELECT wc FROM WebhookConfigEntity wc WHERE wc.gameId = :gameId AND wc.deletedAt IS NULL ORDER BY wc.createdAt DESC")
    List<WebhookConfigEntity> findByGameId(@Param("gameId") String gameId);

    /**
     * 查找环境的Webhook配置
     */
    @Query("SELECT wc FROM WebhookConfigEntity wc WHERE wc.gameId = :gameId AND (wc.environmentId = :environmentId OR wc.environmentId IS NULL) AND wc.deletedAt IS NULL ORDER BY wc.createdAt DESC")
    List<WebhookConfigEntity> findByGameIdAndEnvironment(@Param("gameId") String gameId, @Param("environmentId") String environmentId);

    /**
     * 查找激活的Webhook配置
     */
    @Query("SELECT wc FROM WebhookConfigEntity wc WHERE wc.gameId = :gameId AND wc.status = 'ACTIVE' AND wc.deletedAt IS NULL")
    List<WebhookConfigEntity> findActiveByGameId(@Param("gameId") String gameId);

    /**
     * 根据名称查找
     */
    @Query("SELECT wc FROM WebhookConfigEntity wc WHERE wc.gameId = :gameId AND wc.name = :name AND wc.deletedAt IS NULL")
    Optional<WebhookConfigEntity> findByGameIdAndName(@Param("gameId") String gameId, @Param("name") String name);

    /**
     * 查找需要重试的Webhook日志
     */
    @Query("SELECT wl FROM WebhookLogEntity wl WHERE wl.deliveryStatus = 'RETRYING' AND wl.nextRetryAt <= :now")
    List<WebhookLogEntity> findPendingRetries(@Param("now") java.time.LocalDateTime now);

    /**
     * 搜索Webhook配置
     */
    @Query("SELECT wc FROM WebhookConfigEntity wc WHERE wc.gameId = :gameId AND (wc.name LIKE %:query% OR wc.displayName LIKE %:query% OR wc.webhookUrl LIKE %:query%) AND wc.deletedAt IS NULL")
    List<WebhookConfigEntity> search(@Param("gameId") String gameId, @Param("query") String query);
}
