package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ReportExecutionRepo extends JpaRepository<ReportExecutionEntity, String> {

    /**
     * 根据报表查找执行记录
     */
    @Query("SELECT re FROM ReportExecutionEntity re WHERE re.reportId = :reportId ORDER BY re.createdAt DESC")
    List<ReportExecutionEntity> findByReportId(@Param("reportId") String reportId);

    /**
     * 根据游戏查找执行记录
     */
    @Query("SELECT re FROM ReportExecutionEntity re WHERE re.gameId = :gameId ORDER BY re.createdAt DESC")
    List<ReportExecutionEntity> findByGameId(@Param("gameId") String gameId);

    /**
     * 查找待执行的记录
     */
    @Query("SELECT re FROM ReportExecutionEntity re WHERE re.executionStatus = 'PENDING' ORDER BY re.createdAt ASC")
    List<ReportExecutionEntity> findPending();

    /**
     * 统计报表的执行次数
     */
    @Query("SELECT COUNT(re) FROM ReportExecutionEntity re WHERE re.reportId = :reportId")
    long countByReportId(@Param("reportId") String reportId);

    /**
     * 统计成功执行的次数
     */
    @Query("SELECT COUNT(re) FROM ReportExecutionEntity re WHERE re.reportId = :reportId AND re.executionStatus = 'COMPLETED'")
    long countSuccessByReportId(@Param("reportId") String reportId);

    /**
     * 统计平均执行时间
     */
    @Query("SELECT AVG(re.executionTimeMs) FROM ReportExecutionEntity re WHERE re.reportId = :reportId AND re.executionStatus = 'COMPLETED'")
    Double averageExecutionTime(@Param("reportId") String reportId);

    /**
     * 删除过期记录
     */
    @Query("DELETE FROM ReportExecutionEntity re WHERE re.createdAt < :expireAt")
    int deleteExpired(@Param("expireAt") LocalDateTime expireAt);

    /**
     * 统计总行数
     */
    @Query("SELECT SUM(re.rowCount) FROM ReportExecutionEntity re WHERE re.reportId = :reportId AND re.executionStatus = 'COMPLETED'")
    Long sumRowCountByReportId(@Param("reportId") String reportId);

    /**
     * 查找超时的执行记录
     */
    @Query("SELECT re FROM ReportExecutionEntity re WHERE re.executionStatus = 'RUNNING' AND re.startTime < :timeout")
    List<ReportExecutionEntity> findTimeout(@Param("timeout") LocalDateTime timeout);

}
