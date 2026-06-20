package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface PerformanceMetricRepo extends JpaRepository<PerformanceMetricEntity, String> {

    List<PerformanceMetricEntity> findByGameIdAndCreatedAtBetween(
        String gameId, LocalDateTime startDate, LocalDateTime endDate);

    List<PerformanceMetricEntity> findByGameIdAndMetricType(
        String gameId, PerformanceMetricEntity.MetricType metricType);

    @Query("SELECT p.metricType, AVG(p.metricValue), COUNT(p) " +
           "FROM PerformanceMetricEntity p WHERE p.gameId = :gameId " +
           "AND p.createdAt BETWEEN :startDate AND :endDate " +
           "GROUP BY p.metricType")
    List<Object[]> getPerformanceSummary(
        @Param("gameId") String gameId,
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate);

    @Query("SELECT p FROM PerformanceMetricEntity p WHERE p.gameId = :gameId " +
           "AND p.metricType = 'CRASH' ORDER BY p.createdAt DESC")
    List<PerformanceMetricEntity> getRecentCrashes(@Param("gameId") String gameId);

    @Query("SELECT p.crashHash, COUNT(p), MIN(p.createdAt), MAX(p.createdAt) " +
           "FROM PerformanceMetricEntity p WHERE p.gameId = :gameId " +
           "AND p.metricType = 'CRASH' GROUP BY p.crashHash ORDER BY COUNT(p) DESC")
    List<Object[]> getCrashGroups(@Param("gameId") String gameId);
}
