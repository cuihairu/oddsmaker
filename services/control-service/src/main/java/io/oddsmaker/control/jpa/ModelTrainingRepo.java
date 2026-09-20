package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 模型训练任务仓库接口
 */
@Repository
public interface ModelTrainingRepo extends JpaRepository<ModelTrainingEntity, String> {

    /**
     * 根据模型ID查找训练任务
     */
    List<ModelTrainingEntity> findByModelIdOrderByCreatedAtDesc(String modelId);

    /**
     * 根据游戏ID查找训练任务
     */
    List<ModelTrainingEntity> findByGameIdOrderByCreatedAtDesc(String gameId);

    /**
     * 查找运行中的训练任务
     */
    @Query("SELECT t FROM ModelTrainingEntity t WHERE t.trainingStatus = 'RUNNING'")
    List<ModelTrainingEntity> findRunningJobs();

    /**
     * 查找超时的训练任务
     */
    @Query("SELECT t FROM ModelTrainingEntity t WHERE t.trainingStatus = 'RUNNING' AND t.startedAt < :threshold")
    List<ModelTrainingEntity> findTimedOutJobs(@Param("threshold") LocalDateTime threshold);

    /**
     * 查找最近的训练任务
     */
    @Query("SELECT t FROM ModelTrainingEntity t WHERE t.modelId = :modelId ORDER BY t.createdAt DESC")
    List<ModelTrainingEntity> findRecentByModelId(@Param("modelId") String modelId);

    /**
     * 统计各状态训练任务数量
     */
    @Query("SELECT t.trainingStatus, COUNT(t) FROM ModelTrainingEntity t GROUP BY t.trainingStatus")
    List<Object[]> countByStatus();

    /**
     * 统计模型的训练次数
     */
    @Query("SELECT COUNT(t) FROM ModelTrainingEntity t WHERE t.modelId = :modelId")
    long countByModelId(@Param("modelId") String modelId);

    /**
     * 计算平均训练时长
     */
    @Query("SELECT AVG(t.durationMs) FROM ModelTrainingEntity t WHERE t.modelId = :modelId AND t.trainingStatus = 'COMPLETED'")
    Double calculateAverageDuration(@Param("modelId") String modelId);

    /**
     * 计算总GPU使用时长
     */
    @Query("SELECT SUM(t.gpuHours) FROM ModelTrainingEntity t WHERE t.trainingStatus = 'COMPLETED'")
    Double calculateTotalGpuHours();

    /**
     * 计算总CPU使用时长
     */
    @Query("SELECT SUM(t.cpuHours) FROM ModelTrainingEntity t WHERE t.trainingStatus = 'COMPLETED'")
    Double calculateTotalCpuHours();

}
