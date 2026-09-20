package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TrackingPlanRepo extends JpaRepository<TrackingPlanEntity, String>, JpaSpecificationExecutor<TrackingPlanEntity> {

    /**
     * 查找游戏的所有追踪计划（未删除）
     */
    List<TrackingPlanEntity> findByGameIdAndDeletedAtIsNullOrderByCreatedAtDesc(String gameId);

    /**
     * 查找游戏活跃的追踪计划
     */
    @Query("SELECT tp FROM TrackingPlanEntity tp WHERE tp.gameId = :gameId AND tp.status = 'ACTIVE' AND tp.deletedAt IS NULL ORDER BY tp.activatedAt DESC")
    List<TrackingPlanEntity> findActiveByGameId(@Param("gameId") String gameId);

    /**
     * 查找游戏特定环境的追踪计划
     */
    @Query("SELECT tp FROM TrackingPlanEntity tp WHERE tp.gameId = :gameId AND (tp.environmentId = :environmentId OR tp.environmentId IS NULL) AND tp.deletedAt IS NULL ORDER BY tp.createdAt DESC")
    List<TrackingPlanEntity> findByGameIdAndEnvironment(@Param("gameId") String gameId, @Param("environmentId") String environmentId);

    /**
     * 根据名称查找
     */
    Optional<TrackingPlanEntity> findByIdAndDeletedAtIsNull(String id);

}
