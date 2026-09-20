package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface RiskCaseRepo extends JpaRepository<RiskCaseEntity, String> {

    /**
     * 根据规则查找案例
     */
    @Query("SELECT rc FROM RiskCaseEntity rc WHERE rc.riskRuleId = :riskRuleId ORDER BY rc.createdAt DESC")
    List<RiskCaseEntity> findByRiskRuleId(@Param("riskRuleId") String riskRuleId);

    /**
     * 根据游戏查找案例
     */
    @Query("SELECT rc FROM RiskCaseEntity rc WHERE rc.gameId = :gameId ORDER BY rc.createdAt DESC")
    List<RiskCaseEntity> findByGameId(@Param("gameId") String gameId);

    /**
     * 查找待审核案例
     */
    @Query("SELECT rc FROM RiskCaseEntity rc WHERE rc.gameId = :gameId AND rc.reviewStatus IN ('pending', 'reviewing') ORDER BY rc.createdAt ASC")
    List<RiskCaseEntity> findPendingReview(@Param("gameId") String gameId);

    /**
     * 查找已封禁但未解除的案例
     */
    @Query("SELECT rc FROM RiskCaseEntity rc WHERE rc.actionTaken = 'BLOCK' AND rc.executionStatus = 'EXECUTED' AND (rc.unblockedAt IS NULL OR rc.unblockedAt < rc.executedAt) ORDER BY rc.executedAt DESC")
    List<RiskCaseEntity> findActiveBlocks();

    /**
     * 根据时间范围查找案例
     */
    @Query("SELECT rc FROM RiskCaseEntity rc WHERE rc.gameId = :gameId AND rc.createdAt >= :startTime AND rc.createdAt <= :endTime ORDER BY rc.createdAt DESC")
    List<RiskCaseEntity> findByGameIdAndTimeRange(@Param("gameId") String gameId, @Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime);

    /**
     * 统计游戏的风险案例数
     */
    @Query("SELECT COUNT(rc) FROM RiskCaseEntity rc WHERE rc.gameId = :gameId AND rc.createdAt >= :since")
    long countByGameIdSince(@Param("gameId") String gameId, @Param("since") LocalDateTime since);

    /**
     * 查找重复目标的高频案例
     */
    @Query("SELECT rc.targetType, rc.targetId, COUNT(rc) as caseCount FROM RiskCaseEntity rc WHERE rc.createdAt >= :since GROUP BY rc.targetType, rc.targetId HAVING COUNT(rc) >= :threshold ORDER BY caseCount DESC")
    List<Object[]> findFrequentTargets(@Param("since") LocalDateTime since, @Param("threshold") long threshold);

    // ========== 玩家数据删除（GDPR erasure）：定位后 Java 侧匿名化（保留案件记录） ==========

    /** 目标直接命中的案例 */
    List<RiskCaseEntity> findByGameIdAndTargetIdIn(String gameId, List<String> targetIds);

    /** 证据 JSON 内嵌标识的案例（LIKE 粗筛，精确替换在 Java 侧做） */
    List<RiskCaseEntity> findByGameIdAndEvidenceDataContaining(String gameId, String probe);

    /** 上下文 JSON 内嵌标识的案例（LIKE 粗筛） */
    List<RiskCaseEntity> findByGameIdAndContextDataContaining(String gameId, String probe);
}
