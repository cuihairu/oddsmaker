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

}
