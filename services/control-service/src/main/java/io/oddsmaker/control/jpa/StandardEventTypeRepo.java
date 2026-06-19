package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StandardEventTypeRepo extends JpaRepository<StandardEventTypeEntity, String> {

    /**
     * 根据代码查找事件类型
     */
    Optional<StandardEventTypeEntity> findByCodeAndDeletedAtIsNull(String code);

    /**
     * 查找所有活跃的事件类型
     */
    @Query("SELECT et FROM StandardEventTypeEntity et WHERE et.status = 'ACTIVE' AND et.deletedAt IS NULL ORDER BY et.displayOrder, et.code")
    List<StandardEventTypeEntity> findActive();

    /**
     * 根据分类查找
     */
    @Query("SELECT et FROM StandardEventTypeEntity et WHERE et.category = :category AND et.deletedAt IS NULL ORDER BY et.displayOrder")
    List<StandardEventTypeEntity> findByCategory(@Param("category") String category);

    /**
     * 查找核心事件类型
     */
    @Query("SELECT et FROM StandardEventTypeEntity et WHERE et.isCore = true AND et.deletedAt IS NULL ORDER BY et.displayOrder")
    List<StandardEventTypeEntity> findCoreTypes();

    /**
     * 查找支持聚合的事件类型
     */
    @Query("SELECT et FROM StandardEventTypeEntity et WHERE et.enableAggregation = true AND et.deletedAt IS NULL ORDER BY et.displayOrder")
    List<StandardEventTypeEntity> findAggregatableTypes();
}
