package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface RevenueAnalysisRepo extends JpaRepository<RevenueAnalysisEntity, String> {

    List<RevenueAnalysisEntity> findByGameIdAndAnalysisDateBetween(
        String gameId, LocalDate startDate, LocalDate endDate);

    @Query("SELECT r.analysisDate, SUM(r.totalRevenue), SUM(r.iapRevenue), SUM(r.adRevenue) " +
           "FROM RevenueAnalysisEntity r WHERE r.gameId = :gameId " +
           "AND r.analysisDate BETWEEN :startDate AND :endDate " +
           "GROUP BY r.analysisDate ORDER BY r.analysisDate")
    List<Object[]> getDailyRevenueSummary(
        @Param("gameId") String gameId,
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate);

    @Query("SELECT r.platform, SUM(r.totalRevenue), AVG(r.arpu), AVG(r.arppu) " +
           "FROM RevenueAnalysisEntity r WHERE r.gameId = :gameId " +
           "AND r.analysisDate = :date GROUP BY r.platform")
    List<Object[]> getRevenueByPlatform(
        @Param("gameId") String gameId,
        @Param("date") LocalDate date);
}
