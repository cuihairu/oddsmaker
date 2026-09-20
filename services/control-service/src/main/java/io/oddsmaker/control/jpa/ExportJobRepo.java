package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ExportJobRepo extends JpaRepository<ExportJobEntity, String> {

    /**
     * 根据游戏查找导出任务
     */
    @Query("SELECT ej FROM ExportJobEntity ej WHERE ej.gameId = :gameId AND ej.deletedAt IS NULL ORDER BY ej.createdAt DESC")
    List<ExportJobEntity> findByGameId(@Param("gameId") String gameId);

    /**
     * 根据用户查找导出任务
     */
    @Query("SELECT ej FROM ExportJobEntity ej WHERE ej.userId = :userId AND ej.deletedAt IS NULL ORDER BY ej.createdAt DESC")
    List<ExportJobEntity> findByUserId(@Param("userId") String userId);

    /**
     * 查找待处理的任务
     */
    @Query("SELECT ej FROM ExportJobEntity ej WHERE ej.exportStatus = 'PENDING' AND ej.deletedAt IS NULL ORDER BY ej.createdAt ASC")
    List<ExportJobEntity> findPending();

    /**
     * 查找处理中的任务
     */
    @Query("SELECT ej FROM ExportJobEntity ej WHERE ej.exportStatus = 'PROCESSING' AND ej.deletedAt IS NULL")
    List<ExportJobEntity> findProcessing();

    /**
     * 查找过期的任务
     */
    @Query("SELECT ej FROM ExportJobEntity ej WHERE ej.expiresAt < :now AND ej.exportStatus = 'COMPLETED'")
    List<ExportJobEntity> findExpired(@Param("now") LocalDateTime now);

    /**
     * 根据导出类型查找
     */
    @Query("SELECT ej FROM ExportJobEntity ej WHERE ej.gameId = :gameId AND ej.exportType = :exportType AND ej.deletedAt IS NULL ORDER BY ej.createdAt DESC")
    List<ExportJobEntity> findByGameIdAndType(@Param("gameId") String gameId, @Param("exportType") String exportType);

    /**
     * 根据时间范围查找
     */
    @Query("SELECT ej FROM ExportJobEntity ej WHERE ej.gameId = :gameId AND ej.createdAt >= :startTime AND ej.createdAt <= :endTime AND ej.deletedAt IS NULL ORDER BY ej.createdAt DESC")
    List<ExportJobEntity> findByGameIdAndTimeRange(@Param("gameId") String gameId, @Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime);

    /**
     * 统计用户的总导出次数
     */
    @Query("SELECT COUNT(ej) FROM ExportJobEntity ej WHERE ej.userId = :userId AND ej.deletedAt IS NULL")
    long countByUserId(@Param("userId") String userId);

    /**
     * 统计成功导出的次数
     */
    @Query("SELECT COUNT(ej) FROM ExportJobEntity ej WHERE ej.userId = :userId AND ej.exportStatus = 'COMPLETED' AND ej.deletedAt IS NULL")
    long countCompletedByUserId(@Param("userId") String userId);

    /**
     * 统计游戏的总导出数据量
     */
    @Query("SELECT SUM(ej.fileSizeBytes) FROM ExportJobEntity ej WHERE ej.gameId = :gameId AND ej.exportStatus = 'COMPLETED' AND ej.deletedAt IS NULL")
    Long sumFileSizeByGameId(@Param("gameId") String gameId);

    /**
     * 删除过期任务
     */
    @Query("DELETE FROM ExportJobEntity ej WHERE ej.expiresAt < :expireAt")
    int deleteExpired(@Param("expireAt") LocalDateTime expireAt);

}
