package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EventDefinitionRepo extends JpaRepository<EventDefinitionEntity, String>, JpaSpecificationExecutor<EventDefinitionEntity> {

    /**
     * 查找追踪计划的所有事件定义
     */
    List<EventDefinitionEntity> findByTrackingPlanIdAndDeletedAtIsNullOrderByEventNameAsc(String trackingPlanId);

    /**
     * 查找活跃的事件定义
     */
    @Query("SELECT ed FROM EventDefinitionEntity ed WHERE ed.trackingPlanId = :trackingPlanId AND ed.status = 'ACTIVE' AND ed.deletedAt IS NULL ORDER BY ed.importance DESC, ed.eventName")
    List<EventDefinitionEntity> findActiveByTrackingPlanId(@Param("trackingPlanId") String trackingPlanId);

    /**
     * 检查事件名是否存在
     */
    @Query("SELECT CASE WHEN COUNT(ed) > 0 THEN true ELSE false END FROM EventDefinitionEntity ed WHERE ed.trackingPlanId = :trackingPlanId AND ed.eventName = :eventName AND ed.deletedAt IS NULL")
    boolean existsByTrackingPlanIdAndEventName(@Param("trackingPlanId") String trackingPlanId, @Param("eventName") String eventName);

    /**
     * 统计追踪计划的事件定义数量
     */
    @Query("SELECT COUNT(ed) FROM EventDefinitionEntity ed WHERE ed.trackingPlanId = :trackingPlanId AND ed.deletedAt IS NULL")
    long countByTrackingPlanId(@Param("trackingPlanId") String trackingPlanId);

}
