package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface HealthMetricRepo extends JpaRepository<HealthMetricEntity, String> {

    /**
     * 查找最近的指标
     */
    @Query("SELECT hm FROM HealthMetricEntity hm WHERE hm.metricType = :type AND hm.collectedAt >= :since ORDER BY hm.collectedAt DESC")
    List<HealthMetricEntity> findRecentByType(@Param("type") HealthMetricEntity.MetricType type, @Param("since") LocalDateTime since);

    /**
     * 删除过期指标
     */
    @Query("DELETE FROM HealthMetricEntity hm WHERE hm.collectedAt < :expireBefore")
    int deleteExpired(@Param("expireBefore") LocalDateTime expireBefore);

}
