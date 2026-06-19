package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EventPropertyDefinitionRepo extends JpaRepository<EventPropertyDefinitionEntity, String> {

    /**
     * 查找事件定义的所有属性
     */
    List<EventPropertyDefinitionEntity> findByEventDefinitionIdAndDeletedAtIsNullOrderByDisplayOrderAsc(String eventDefinitionId);

    /**
     * 根据属性名查找
     */
    @Query("SELECT epd FROM EventPropertyDefinitionEntity epd WHERE epd.eventDefinitionId = :eventDefinitionId AND epd.propertyName = :propertyName AND epd.deletedAt IS NULL")
    Optional<EventPropertyDefinitionEntity> findByEventDefinitionIdAndPropertyName(@Param("eventDefinitionId") String eventDefinitionId, @Param("propertyName") String propertyName);

    /**
     * 查找必需属性
     */
    @Query("SELECT epd FROM EventPropertyDefinitionEntity epd WHERE epd.eventDefinitionId = :eventDefinitionId AND epd.required = true AND epd.deletedAt IS NULL")
    List<EventPropertyDefinitionEntity> findRequiredByEventDefinitionId(@Param("eventDefinitionId") String eventDefinitionId);

    /**
     * 查找PII属性
     */
    @Query("SELECT epd FROM EventPropertyDefinitionEntity epd WHERE epd.eventDefinitionId = :eventDefinitionId AND epd.isPii = true AND epd.deletedAt IS NULL")
    List<EventPropertyDefinitionEntity> findPiiByEventDefinitionId(@Param("eventDefinitionId") String eventDefinitionId);

    /**
     * 查找索引属性
     */
    @Query("SELECT epd FROM EventPropertyDefinitionEntity epd WHERE epd.eventDefinitionId = :eventDefinitionId AND epd.isIndexed = true AND epd.deletedAt IS NULL")
    List<EventPropertyDefinitionEntity> findIndexedByEventDefinitionId(@Param("eventDefinitionId") String eventDefinitionId);

    /**
     * 统计属性数量
     */
    @Query("SELECT COUNT(epd) FROM EventPropertyDefinitionEntity epd WHERE epd.eventDefinitionId = :eventDefinitionId AND epd.deletedAt IS NULL")
    long countByEventDefinitionId(@Param("eventDefinitionId") String eventDefinitionId);

    /**
     * 软删除
     */
    @Query("UPDATE EventPropertyDefinitionEntity epd SET epd.deletedAt = CURRENT_TIMESTAMP WHERE epd.id = :id")
    void softDelete(@Param("id") String id);
}
