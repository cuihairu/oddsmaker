package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface AdAnalysisRepo extends JpaRepository<AdAnalysisEntity, String> {

    List<AdAnalysisEntity> findByGameIdAndAnalysisDateBetween(
        String gameId, LocalDate startDate, LocalDate endDate);

    @Query("SELECT a.adNetwork, SUM(a.revenue), SUM(a.impressions), AVG(a.ecpm), AVG(a.fillRate) " +
           "FROM AdAnalysisEntity a WHERE a.gameId = :gameId " +
           "AND a.analysisDate BETWEEN :startDate AND :endDate " +
           "GROUP BY a.adNetwork")
    List<Object[]> getAdPerformanceByNetwork(
        @Param("gameId") String gameId,
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate);

    @Query("SELECT a.adFormat, SUM(a.revenue), AVG(a.ecpm), AVG(a.ctr) " +
           "FROM AdAnalysisEntity a WHERE a.gameId = :gameId " +
           "AND a.analysisDate = :date GROUP BY a.adFormat")
    List<Object[]> getAdPerformanceByFormat(
        @Param("gameId") String gameId,
        @Param("date") LocalDate date);
}
