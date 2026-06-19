package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EventFieldRepo extends JpaRepository<EventFieldEntity, String> {

    /**
     * 根据标准事件查找字段
     */
    @Query("SELECT ef FROM EventFieldEntity ef WHERE ef.standardEventId = :standardEventId AND ef.deletedAt IS NULL ORDER BY ef.displayOrder, ef.fieldName")
    List<EventFieldEntity> findByStandardEventId(@Param("standardEventId") String standardEventId);

    /**
     * 根据事件和字段名查找
     */
    @Query("SELECT ef FROM EventFieldEntity ef WHERE ef.standardEventId = :standardEventId AND ef.fieldName = :fieldName AND ef.deletedAt IS NULL")
    Optional<EventFieldEntity> findByEventAndFieldName(@Param("standardEventId") String standardEventId, @Param("fieldName") String fieldName);

    /**
     * 查找必需字段
     */
    @Query("SELECT ef FROM EventFieldEntity ef WHERE ef.standardEventId = :standardEventId AND ef.required = true AND ef.deletedAt IS NULL ORDER BY ef.displayOrder")
    List<EventFieldEntity> findRequiredByEventId(@Param("standardEventId") String standardEventId);

    /**
     * 查找维度字段
     */
    @Query("SELECT ef FROM EventFieldEntity ef WHERE ef.standardEventId = :standardEventId AND ef.isDimension = true AND ef.deletedAt IS NULL ORDER BY ef.displayOrder")
    List<EventFieldEntity> findDimensionsByEventId(@Param("standardEventId") String standardEventId);

    /**
     * 查找度量字段
     */
    @Query("SELECT ef FROM EventFieldEntity ef WHERE ef.standardEventId = :standardEventId AND ef.isMetric = true AND ef.deletedAt IS NULL ORDER BY ef.displayOrder")
    List<EventFieldEntity> findMetricsByEventId(@Param("standardEventId") String standardEventId);

    /**
     * 查找标识字段
     */
    @Query("SELECT ef FROM EventFieldEntity ef WHERE ef.standardEventId = :standardEventId AND ef.purpose = 'IDENTITY' AND ef.deletedAt IS NULL ORDER BY ef.displayOrder")
    List<EventFieldEntity> findIdentityFieldsByEventId(@Param("standardEventId") String standardEventId);

    /**
     * 查找收入字段
     */
    @Query("SELECT ef FROM EventFieldEntity ef WHERE ef.purpose = 'REVENUE' AND ef.deletedAt IS NULL ORDER BY ef.fieldName")
    List<EventFieldEntity> findRevenueFields();

    /**
     * 根据分组查找字段
     */
    @Query("SELECT ef FROM EventFieldEntity ef WHERE ef.standardEventId = :standardEventId AND ef.fieldGroup = :fieldGroup AND ef.deletedAt IS NULL ORDER BY ef.displayOrder")
    List<EventFieldEntity> findByEventAndGroup(@Param("standardEventId") String standardEventId, @Param("fieldGroup") String fieldGroup);

    /**
     * 搜索字段
     */
    @Query("SELECT ef FROM EventFieldEntity ef WHERE (ef.fieldName LIKE %:query% OR ef.displayName LIKE %:query% OR ef.description LIKE %:query%) AND ef.deletedAt IS NULL")
    List<EventFieldEntity> search(@Param("query") String query);
}
