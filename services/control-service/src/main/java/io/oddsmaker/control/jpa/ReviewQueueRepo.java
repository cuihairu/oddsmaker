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
public interface ReviewQueueRepo extends JpaRepository<ReviewQueueEntity, String> {

    /**
     * 根据风险案例查找审核项
     */
    @Query("SELECT rq FROM ReviewQueueEntity rq WHERE rq.riskCaseId = :riskCaseId")
    Optional<ReviewQueueEntity> findByRiskCaseId(@Param("riskCaseId") String riskCaseId);

    /**
     * 查找游戏的审核队列
     */
    @Query("SELECT rq FROM ReviewQueueEntity rq WHERE rq.gameId = :gameId ORDER BY rq.priority DESC, rq.createdAt ASC")
    List<ReviewQueueEntity> findByGameId(@Param("gameId") String gameId);

    /**
     * 查找待处理的审核项
     */
    @Query("SELECT rq FROM ReviewQueueEntity rq WHERE rq.gameId = :gameId AND rq.reviewStatus IN ('PENDING', 'ASSIGNED', 'CLAIMED', 'IN_REVIEW') ORDER BY rq.priority DESC, rq.createdAt ASC")
    List<ReviewQueueEntity> findPendingByGameId(@Param("gameId") String gameId);

    /**
     * 查找分配给特定审核人的项
     */
    @Query("SELECT rq FROM ReviewQueueEntity rq WHERE rq.assignedTo = :reviewer OR rq.claimedBy = :reviewer ORDER BY rq.priority DESC, rq.createdAt ASC")
    List<ReviewQueueEntity> findByReviewer(@Param("reviewer") String reviewer);

    /**
     * 查找高优先级审核项
     */
    @Query("SELECT rq FROM ReviewQueueEntity rq WHERE rq.gameId = :gameId AND rq.priority >= :minPriority AND rq.reviewStatus IN ('PENDING', 'ASSIGNED', 'CLAIMED', 'IN_REVIEW') ORDER BY rq.priority DESC, rq.createdAt ASC")
    List<ReviewQueueEntity> findHighPriority(@Param("gameId") String gameId, @Param("minPriority") int minPriority);

    /**
     * 查找逾期项
     */
    @Query("SELECT rq FROM ReviewQueueEntity rq WHERE rq.slaDueAt < :now AND rq.reviewStatus NOT IN ('COMPLETED', 'CANCELLED') ORDER BY rq.slaDueAt ASC")
    List<ReviewQueueEntity> findOverdue(@Param("now") LocalDateTime now);

    /**
     * 搜索审核队列
     */
    @Query("SELECT rq FROM ReviewQueueEntity rq WHERE rq.gameId = :gameId AND (rq.caseNumber LIKE %:query% OR rq.targetId LIKE %:query% OR rq.targetName LIKE %:query%)")
    List<ReviewQueueEntity> search(@Param("gameId") String gameId, @Param("query") String query);

    /**
     * 查找需要升级的项（处理时间过长）
     */
    @Query("SELECT rq FROM ReviewQueueEntity rq WHERE rq.createdAt < :threshold AND rq.reviewStatus IN ('IN_REVIEW', 'CLAIMED') AND rq.escalated = false")
    List<ReviewQueueEntity> findNeedsEscalation(@Param("threshold") LocalDateTime threshold);

    // ========== 玩家数据删除（GDPR erasure）：定位后匿名化占位 ==========

    /** 目标命中的审核项（Java 侧把 target 替换为 erased:&lt;reqId&gt;） */
    List<ReviewQueueEntity> findByGameIdAndTargetIdIn(String gameId, List<String> targetIds);
}
