package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface QuotaRepo extends JpaRepository<QuotaEntity, String> {

    /**
     * 查找游戏的所有配额
     */
    @Query("SELECT q FROM QuotaEntity q WHERE q.gameId = :gameId AND q.environmentId IS NULL AND q.deletedAt IS NULL ORDER BY q.resourceType")
    List<QuotaEntity> findByGameId(@Param("gameId") String gameId);

    /**
     * 查找特定资源类型的配额
     */
    @Query("SELECT q FROM QuotaEntity q WHERE q.gameId = :gameId AND q.environmentId IS NULL AND q.resourceType = :resourceType AND q.deletedAt IS NULL")
    Optional<QuotaEntity> findByGameAndResourceType(@Param("gameId") String gameId, @Param("resourceType") QuotaEntity.ResourceType resourceType);

    /**
     * 查找环境的特定资源类型配额
     */
    @Query("SELECT q FROM QuotaEntity q WHERE q.gameId = :gameId AND q.environmentId = :environmentId AND q.resourceType = :resourceType AND q.deletedAt IS NULL")
    Optional<QuotaEntity> findByGameEnvironmentAndResourceType(@Param("gameId") String gameId, @Param("environmentId") String environmentId, @Param("resourceType") QuotaEntity.ResourceType resourceType);

    /**
     * 原子自增当前用量（读-改-写 save 在并发下会丢扣减）。
     * usagePercent 派生列同语句维护；limit<=0 时置 0。
     */
    @Modifying
    @Query("UPDATE QuotaEntity q SET q.currentUsage = COALESCE(q.currentUsage, 0) + :amount, "
        + "q.usagePercent = CASE WHEN COALESCE(q.quotaLimit, 0) > 0 "
        + "THEN (COALESCE(q.currentUsage, 0) + :amount) * 100.0 / q.quotaLimit ELSE 0 END, "
        + "q.lastCalculatedAt = CURRENT_TIMESTAMP WHERE q.id = :id")
    int incrementUsageAtomic(@Param("id") String id, @Param("amount") long amount);

    /** 原子置警告已发（整实体 save 会把并发自增的 currentUsage 覆盖回旧值） */
    @Modifying
    @Query("UPDATE QuotaEntity q SET q.warningSent = true WHERE q.id = :id")
    int markWarningSentAtomic(@Param("id") String id);

    /** 原子置告警已发 */
    @Modifying
    @Query("UPDATE QuotaEntity q SET q.alertSent = true WHERE q.id = :id")
    int markAlertSentAtomic(@Param("id") String id);

    /**
     * 查找需要重置的配额
     */
    @Query("SELECT q FROM QuotaEntity q WHERE q.resetAt IS NOT NULL AND q.resetAt < :now AND q.deletedAt IS NULL")
    List<QuotaEntity> findResetNeeded(@Param("now") LocalDateTime now);

}
