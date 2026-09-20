package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReportRepo extends JpaRepository<ReportEntity, String> {

    /**
     * 查找游戏的所有报表
     */
    @Query("SELECT r FROM ReportEntity r WHERE r.gameId = :gameId AND r.deletedAt IS NULL ORDER BY r.createdAt DESC")
    List<ReportEntity> findByGameId(@Param("gameId") String gameId);

    /**
     * 查找环境的报表
     */
    @Query("SELECT r FROM ReportEntity r WHERE r.gameId = :gameId AND (r.environmentId = :environmentId OR r.environmentId IS NULL) AND r.deletedAt IS NULL ORDER BY r.createdAt DESC")
    List<ReportEntity> findByGameIdAndEnvironment(@Param("gameId") String gameId, @Param("environmentId") String environmentId);

    /**
     * 查找已发布的报表
     */
    @Query("SELECT r FROM ReportEntity r WHERE r.gameId = :gameId AND r.status = 'PUBLISHED' AND r.deletedAt IS NULL ORDER BY r.createdAt DESC")
    List<ReportEntity> findPublishedByGameId(@Param("gameId") String gameId);

    /**
     * 根据报表类型查找
     */
    @Query("SELECT r FROM ReportEntity r WHERE r.gameId = :gameId AND r.reportType = :reportType AND r.deletedAt IS NULL ORDER BY r.createdAt DESC")
    List<ReportEntity> findByGameIdAndType(@Param("gameId") String gameId, @Param("reportType") ReportEntity.ReportType reportType);

    /**
     * 查找定时报表
     */
    @Query("SELECT r FROM ReportEntity r WHERE r.status = 'SCHEDULED' AND r.deletedAt IS NULL")
    List<ReportEntity> findScheduledReports();

    /**
     * 根据名称查找
     */
    @Query("SELECT r FROM ReportEntity r WHERE r.gameId = :gameId AND r.name = :name AND r.deletedAt IS NULL")
    Optional<ReportEntity> findByGameIdAndName(@Param("gameId") String gameId, @Param("name") String name);

    /**
     * 搜索报表
     */
    @Query("SELECT r FROM ReportEntity r WHERE r.gameId = :gameId AND (r.name LIKE %:query% OR r.displayName LIKE %:query% OR r.description LIKE %:query%) AND r.deletedAt IS NULL")
    List<ReportEntity> search(@Param("gameId") String gameId, @Param("query") String query);

    /**
     * 查找热门报表（按运行次数排序）
     */
    @Query("SELECT r FROM ReportEntity r WHERE r.gameId = :gameId AND r.deletedAt IS NULL ORDER BY r.totalRuns DESC")
    List<ReportEntity> findPopularReports(@Param("gameId") String gameId);

    /**
     * 查找最近运行的报表
     */
    @Query("SELECT r FROM ReportEntity r WHERE r.gameId = :gameId AND r.lastRunAt IS NOT NULL AND r.deletedAt IS NULL ORDER BY r.lastRunAt DESC")
    List<ReportEntity> findRecentlyRun(@Param("gameId") String gameId);

}
