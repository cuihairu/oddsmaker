package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface PipelineJobRepo extends JpaRepository<PipelineJobEntity, String> {

    /**
     * 根据管道ID查找任务
     */
    @Query("SELECT j FROM PipelineJobEntity j WHERE j.pipelineId = :pipelineId ORDER BY j.createdAt DESC")
    List<PipelineJobEntity> findByPipelineId(@Param("pipelineId") String pipelineId);

    /**
     * 根据游戏查找任务
     */
    @Query("SELECT j FROM PipelineJobEntity j WHERE j.gameId = :gameId ORDER BY j.createdAt DESC")
    List<PipelineJobEntity> findByGameId(@Param("gameId") String gameId);

    /**
     * 查找待执行的任务
     */
    @Query("SELECT j FROM PipelineJobEntity j WHERE j.jobStatus = 'PENDING' ORDER BY j.createdAt ASC")
    List<PipelineJobEntity> findPending();

    /**
     * 查找超时的任务
     */
    @Query("SELECT j FROM PipelineJobEntity j WHERE j.jobStatus = 'RUNNING' AND j.startedAt < :timeoutSince")
    List<PipelineJobEntity> findTimeout(@Param("timeoutSince") LocalDateTime timeoutSince);

    /**
     * 统计任务状态
     */
    @Query("SELECT COUNT(j) FROM PipelineJobEntity j WHERE j.jobStatus = :status")
    long countByStatus(@Param("status") PipelineJobEntity.JobStatus status);

    /**
     * 删除旧的任务记录
     */
    @Query("DELETE FROM PipelineJobEntity j WHERE j.completedAt < :expireBefore")
    int deleteCompletedBefore(@Param("expireBefore") LocalDateTime expireBefore);
}
