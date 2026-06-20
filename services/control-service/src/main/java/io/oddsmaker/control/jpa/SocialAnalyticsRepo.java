package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface SocialAnalyticsRepo extends JpaRepository<SocialAnalyticsEntity, String> {

    List<SocialAnalyticsEntity> findByGameIdAndAnalysisDateBetween(
        String gameId, LocalDate startDate, LocalDate endDate);

    @Query("SELECT s.analysisDate, SUM(s.totalFriendships), SUM(s.totalGuilds), AVG(s.viralCoefficient) " +
           "FROM SocialAnalyticsEntity s WHERE s.gameId = :gameId " +
           "AND s.analysisDate BETWEEN :startDate AND :endDate " +
           "GROUP BY s.analysisDate ORDER BY s.analysisDate")
    List<Object[]> getSocialTrends(
        @Param("gameId") String gameId,
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate);

    @Query("SELECT s.socialEventType, COUNT(s) " +
           "FROM SocialAnalyticsEntity s WHERE s.gameId = :gameId " +
           "AND s.analysisDate = :date GROUP BY s.socialEventType")
    List<Object[]> getSocialEventsByType(
        @Param("gameId") String gameId,
        @Param("date") LocalDate date);

    @Query("SELECT AVG(s.socialUsersRetentionD7), AVG(s.nonSocialUsersRetentionD7) " +
           "FROM SocialAnalyticsEntity s WHERE s.gameId = :gameId " +
           "AND s.analysisDate = :date")
    Object[] getSocialRetentionImpact(
        @Param("gameId") String gameId,
        @Param("date") LocalDate date);
}
