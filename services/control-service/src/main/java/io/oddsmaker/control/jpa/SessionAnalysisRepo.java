package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface SessionAnalysisRepo extends JpaRepository<SessionAnalysisEntity, String> {

    List<SessionAnalysisEntity> findByGameIdAndAnalysisDateBetween(
        String gameId, LocalDate startDate, LocalDate endDate);

    @Query("SELECT s.analysisDate, AVG(s.avgSessionDuration), AVG(s.avgEventsPerSession), AVG(s.bounceRate) " +
           "FROM SessionAnalysisEntity s WHERE s.gameId = :gameId " +
           "AND s.analysisDate BETWEEN :startDate AND :endDate " +
           "GROUP BY s.analysisDate ORDER BY s.analysisDate")
    List<Object[]> getSessionTrends(
        @Param("gameId") String gameId,
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate);

    @Query("SELECT s.platform, AVG(s.avgSessionDuration), AVG(s.bounceRate) " +
           "FROM SessionAnalysisEntity s WHERE s.gameId = :gameId " +
           "AND s.analysisDate = :date GROUP BY s.platform")
    List<Object[]> getSessionMetricsByPlatform(
        @Param("gameId") String gameId,
        @Param("date") LocalDate date);
}
