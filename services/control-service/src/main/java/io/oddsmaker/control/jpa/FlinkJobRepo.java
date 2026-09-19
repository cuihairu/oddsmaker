package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FlinkJobRepo extends JpaRepository<FlinkJobEntity, String> {

    /**
     * 查找游戏的所有作业
     */
    @Query("SELECT fj FROM FlinkJobEntity fj WHERE fj.gameId = :gameId AND fj.deletedAt IS NULL ORDER BY fj.createdAt DESC")
    List<FlinkJobEntity> findByGameId(@Param("gameId") String gameId);

    /**
     * 查找环境的作业
     */
    @Query("SELECT fj FROM FlinkJobEntity fj WHERE fj.gameId = :gameId AND (fj.environmentId = :environmentId OR fj.environmentId IS NULL) AND fj.deletedAt IS NULL ORDER BY fj.createdAt DESC")
    List<FlinkJobEntity> findByGameIdAndEnvironment(@Param("gameId") String gameId, @Param("environmentId") String environmentId);

    /**
     * 查找运行中的作业
     */
    @Query("SELECT fj FROM FlinkJobEntity fj WHERE fj.gameId = :gameId AND fj.status = 'RUNNING' AND fj.deletedAt IS NULL")
    List<FlinkJobEntity> findRunningJobs(@Param("gameId") String gameId);

    /**
     * 根据作业类型查找
     */
    @Query("SELECT fj FROM FlinkJobEntity fj WHERE fj.gameId = :gameId AND fj.jobType = :jobType AND fj.deletedAt IS NULL ORDER BY fj.createdAt DESC")
    List<FlinkJobEntity> findByGameIdAndJobType(@Param("gameId") String gameId, @Param("jobType") String jobType);

    /**
     * 根据名称查找
     */
    @Query("SELECT fj FROM FlinkJobEntity fj WHERE fj.gameId = :gameId AND fj.name = :name AND fj.deletedAt IS NULL")
    Optional<FlinkJobEntity> findByGameIdAndName(@Param("gameId") String gameId, @Param("name") String name);

    /**
     * 根据Flink作业ID查找
     */
    @Query("SELECT fj FROM FlinkJobEntity fj WHERE fj.flinkJobId = :flinkJobId AND fj.deletedAt IS NULL")
    Optional<FlinkJobEntity> findByFlinkJobId(@Param("flinkJobId") String flinkJobId);

    /**
     * 查找需要重新部署的失败作业
     */
    @Query("SELECT fj FROM FlinkJobEntity fj WHERE fj.status = 'FAILED' AND fj.deletedAt IS NULL ORDER BY fj.failureCount DESC")
    List<FlinkJobEntity> findFailedJobs();

    /**
     * 查找指定活动状态的作业（全局，状态对账用）
     */
    @Query("SELECT fj FROM FlinkJobEntity fj WHERE fj.status IN :statuses AND fj.deletedAt IS NULL ORDER BY fj.createdAt ASC")
    List<FlinkJobEntity> findByStatusInAndDeletedAtIsNull(@Param("statuses") List<FlinkJobEntity.JobStatus> statuses);

    /**
     * 统计游戏的运行中作业数
     */
    @Query("SELECT COUNT(fj) FROM FlinkJobEntity fj WHERE fj.gameId = :gameId AND fj.status = 'RUNNING' AND fj.deletedAt IS NULL")
    long countRunningJobs(@Param("gameId") String gameId);

    /**
     * 搜索作业
     */
    @Query("SELECT fj FROM FlinkJobEntity fj WHERE fj.gameId = :gameId AND (fj.name LIKE %:query% OR fj.displayName LIKE %:query% OR fj.description LIKE %:query%) AND fj.deletedAt IS NULL")
    List<FlinkJobEntity> search(@Param("gameId") String gameId, @Param("query") String query);

    /**
     * 查找特定版本的作业
     */
    @Query("SELECT fj FROM FlinkJobEntity fj WHERE fj.parentJobId = :parentJobId AND fj.deletedAt IS NULL ORDER BY fj.createdAt DESC")
    List<FlinkJobEntity> findVersions(@Param("parentJobId") String parentJobId);
}
