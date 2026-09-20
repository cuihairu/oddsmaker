package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 模型预测记录仓库接口
 */
@Repository
public interface ModelPredictionRepo extends JpaRepository<MLModelPredictionEntity, String> {

    /**
     * 根据模型ID查找预测记录
     */
    List<MLModelPredictionEntity> findByModelIdOrderByCreatedAtDesc(String modelId);

    /**
     * 根据游戏ID查找预测记录
     */
    List<MLModelPredictionEntity> findByGameIdOrderByCreatedAtDesc(String gameId);

    /**
     * 统计各状态预测数量
     */
    @Query("SELECT p.predictionStatus, COUNT(p) FROM MLModelPredictionEntity p WHERE p.modelId = :modelId GROUP BY p.predictionStatus")
    List<Object[]> countByStatus(@Param("modelId") String modelId);

    /**
     * 统计反馈类型分布
     */
    @Query("SELECT p.feedbackType, COUNT(p) FROM MLModelPredictionEntity p WHERE p.modelId = :modelId AND p.feedbackType IS NOT NULL GROUP BY p.feedbackType")
    List<Object[]> countByFeedbackType(@Param("modelId") String modelId);

    /**
     * 计算平均延迟
     */
    @Query("SELECT AVG(p.latencyMs) FROM MLModelPredictionEntity p WHERE p.modelId = :modelId AND p.latencyMs IS NOT NULL")
    Double calculateAverageLatency(@Param("modelId") String modelId);

    /**
     * 计算缓存命中率
     */
    @Query("SELECT COUNT(p) FROM MLModelPredictionEntity p WHERE p.modelId = :modelId AND p.cacheHit = true")
    long countCacheHits(@Param("modelId") String modelId);

    /**
     * 查找时间范围内的预测记录
     */
    @Query("SELECT p FROM MLModelPredictionEntity p WHERE p.modelId = :modelId AND p.createdAt BETWEEN :startTime AND :endTime ORDER BY p.createdAt DESC")
    List<MLModelPredictionEntity> findByTimeRange(
            @Param("modelId") String modelId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /**
     * 查找最近的预测记录
     */
    @Query("SELECT p FROM MLModelPredictionEntity p WHERE p.modelId = :modelId ORDER BY p.createdAt DESC")
    List<MLModelPredictionEntity> findRecentByModelId(@Param("modelId") String modelId);

    /**
     * 删除指定时间之前的预测记录
     */
    void deleteByCreatedAtBefore(LocalDateTime threshold);

    /**
     * 统计总预测数量
     */
    @Query("SELECT COUNT(p) FROM MLModelPredictionEntity p WHERE p.modelId = :modelId")
    long countByModelId(@Param("modelId") String modelId);
}
