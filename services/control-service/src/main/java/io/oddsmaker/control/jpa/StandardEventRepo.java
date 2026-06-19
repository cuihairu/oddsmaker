package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StandardEventRepo extends JpaRepository<StandardEventEntity, String> {

    /**
     * 根据事件类型查找
     */
    @Query("SELECT se FROM StandardEventEntity se WHERE se.eventTypeId = :eventTypeId AND se.deletedAt IS NULL ORDER BY se.displayOrder, se.eventName")
    List<StandardEventEntity> findByEventTypeId(@Param("eventTypeId") String eventTypeId);

    /**
     * 根据名称查找
     */
    @Query("SELECT se FROM StandardEventEntity se WHERE se.eventName = :eventName AND se.deletedAt IS NULL")
    Optional<StandardEventEntity> findByEventName(@Param("eventName") String eventName);

    /**
     * 根据事件类型和名称查找
     */
    @Query("SELECT se FROM StandardEventEntity se WHERE se.eventTypeId = :eventTypeId AND se.eventName = :eventName AND se.deletedAt IS NULL")
    Optional<StandardEventEntity> findByTypeIdAndName(@Param("eventTypeId") String eventTypeId, @Param("eventName") String eventName);

    /**
     * 查找核心事件
     */
    @Query("SELECT se FROM StandardEventEntity se WHERE se.importance IN ('CRITICAL', 'HIGH') AND se.deletedAt IS NULL ORDER BY se.importance DESC, se.displayOrder")
    List<StandardEventEntity> findImportantEvents();

    /**
     * 查找支持漏斗的事件
     */
    @Query("SELECT se FROM StandardEventEntity se WHERE se.enableFunnel = true AND se.deletedAt IS NULL ORDER BY se.displayOrder")
    List<StandardEventEntity> findFunnelEvents();

    /**
     * 查找支持留存的事件
     */
    @Query("SELECT se FROM StandardEventEntity se WHERE se.enableRetention = true AND se.deletedAt IS NULL ORDER BY se.displayOrder")
    List<StandardEventEntity> findRetentionEvents();

    /**
     * 查找收入事件
     */
    @Query("SELECT se FROM StandardEventEntity se WHERE se.enableRevenue = true AND se.deletedAt IS NULL ORDER BY se.displayOrder")
    List<StandardEventEntity> findRevenueEvents();

    /**
     * 根据用途查找
     */
    @Query("SELECT se FROM StandardEventEntity se WHERE se.useCase = :useCase AND se.deletedAt IS NULL ORDER BY se.displayOrder")
    List<StandardEventEntity> findByUseCase(@Param("useCase") String useCase);

    /**
     * 搜索事件
     */
    @Query("SELECT se FROM StandardEventEntity se WHERE (se.eventName LIKE %:query% OR se.displayName LIKE %:query% OR se.description LIKE %:query%) AND se.deletedAt IS NULL")
    List<StandardEventEntity> search(@Param("query") String query);
}
