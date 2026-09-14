package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 管道任务实体
 * 跟踪管道执行任务
 */
@Entity
@Table(name = "pipeline_jobs")
public class PipelineJobEntity {

    /**
     * 任务状态
     */
    public enum JobStatus {
        PENDING,        // 待执行
        RUNNING,        // 运行中
        COMPLETED,      // 已完成
        FAILED,         // 失败
        CANCELLED,      // 已取消
        TIMEOUT,        // 超时
        RETRYING        // 重试中
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public String id;

    @Column(name = "pipeline_id", nullable = false)
    public String pipelineId;  // 管道ID

    @Column(name = "game_id", nullable = false)
    public String gameId;  // 游戏ID

    @Column(name = "environment_id")
    public String environmentId;  // 环境ID

    @Column(name = "job_name", nullable = false, length = 100)
    public String jobName;  // 任务名称

    @Column(name = "job_status", nullable = false)
    @Enumerated(EnumType.STRING)
    public JobStatus jobStatus = JobStatus.PENDING;

    @Column(name = "run_id", length = 100)
    public String runId;  // 运行ID

    @Column(name = "trigger_type", length = 50)
    public String triggerType;  // 触发类型：schedule, manual, event

    @Column(name = "triggered_by", length = 64)
    public String triggeredBy;  // 触发人

    @Column(name = "input_params", columnDefinition = "TEXT")
    public String inputParams;  // JSON格式的输入参数

    @Column(name = "output_params", columnDefinition = "TEXT")
    public String outputParams;  // JSON格式的输出参数

    @Column(name = "stage_results", columnDefinition = "TEXT")
    public String stageResults;  // JSON格式的阶段结果

    @Column(name = "quality_metrics", columnDefinition = "TEXT")
    public String qualityMetrics;  // JSON格式的质量指标

    @Column(name = "processed_rows", columnDefinition = "BIGINT")
    public Long processedRows = 0L;  // 处理行数

    @Column(name = "error_rows", columnDefinition = "BIGINT")
    public Long errorRows = 0L;  // 错误行数

    @Column(name = "skipped_rows", columnDefinition = "BIGINT")
    public Long skippedRows = 0L;  // 跳过行数

    @Column(name = "data_source_size_bytes", columnDefinition = "BIGINT")
    public Long dataSourceSizeBytes;  // 数据源大小

    @Column(name = "data_destination_size_bytes", columnDefinition = "BIGINT")
    public Long dataDestinationSizeBytes;  // 数据目标大小

    @Column(name = "started_at")
    public LocalDateTime startedAt;  // 开始时间

    @Column(name = "completed_at")
    public LocalDateTime completedAt;  // 完成时间

    @Column(name = "duration_ms", columnDefinition = "BIGINT")
    public Long durationMs;  // 执行时长（毫秒）

    @Column(name = "queue_time_ms", columnDefinition = "BIGINT")
    public Long queueTimeMs;  // 排队等待时长

    @Column(name = "retry_count", columnDefinition = "INTEGER")
    public Integer retryCount = 0;  // 重试次数

    @Column(name = "max_retries", columnDefinition = "INTEGER")
    public Integer maxRetries = 3;  // 最大重试次数

    @Column(name = "error_message", columnDefinition = "TEXT")
    public String errorMessage;  // 错误消息

    @Column(name = "error_stack_trace", columnDefinition = "TEXT")
    public String errorStackTrace;  // 错误堆栈

    @Column(name = "warning_message", columnDefinition = "TEXT")
    public String warningMessage;  // 警告消息

    @Column(name = "worker_node", length = 100)
    public String workerNode;  // 工作节点

    @Column(name = "partition_key", length = 100)
    public String partitionKey;  // 分区键

    @Column(name = "batch_id", length = 100)
    public String batchId;  // 批次ID

    @Column(name = "checkpoint_location", length = 500)
    public String checkpointLocation;  // 检查点位置

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @Column(name = "updated_at")
    public LocalDateTime updatedAt;

    // 辅助方法

    public boolean isPending() {
        return jobStatus == JobStatus.PENDING;
    }

    public boolean isRunning() {
        return jobStatus == JobStatus.RUNNING;
    }

    public boolean isCompleted() {
        return jobStatus == JobStatus.COMPLETED;
    }

    public boolean isFailed() {
        return jobStatus == JobStatus.FAILED;
    }

    public boolean isCancelled() {
        return jobStatus == JobStatus.CANCELLED;
    }

    public boolean isRetryable() {
        return isFailed() && (maxRetries == null || retryCount < maxRetries);
    }

    public long getExecutionDurationMinutes() {
        if (startedAt != null && completedAt != null) {
            return java.time.Duration.between(startedAt, completedAt).toMinutes();
        }
        if (startedAt != null) {
            return java.time.Duration.between(startedAt, LocalDateTime.now()).toMinutes();
        }
        return 0;
    }

    public double getProcessingRate() {
        if (durationMs != null && durationMs > 0 && processedRows != null) {
            return (processedRows * 1000.0) / durationMs;  // rows per second
        }
        return 0;
    }

    public double getErrorRate() {
        long total = processedRows != null ? processedRows : 0;
        long errors = errorRows != null ? errorRows : 0;
        if (total > 0) {
            return (errors * 100.0) / total;
        }
        return 0;
    }

    public void start() {
        this.jobStatus = JobStatus.RUNNING;
        this.startedAt = LocalDateTime.now();
        if (this.createdAt != null) {
            this.queueTimeMs = java.time.Duration.between(this.createdAt, this.startedAt).toMillis();
        }
    }

    public void complete(long processedRows, long errorRows) {
        this.jobStatus = JobStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
        this.processedRows = processedRows;
        this.errorRows = errorRows;

        if (this.startedAt != null) {
            this.durationMs = java.time.Duration.between(this.startedAt, this.completedAt).toMillis();
        }
    }

    public void fail(String error) {
        this.jobStatus = JobStatus.FAILED;
        this.completedAt = LocalDateTime.now();
        this.errorMessage = error;
        this.retryCount++;

        if (this.startedAt != null) {
            this.durationMs = java.time.Duration.between(this.startedAt, this.completedAt).toMillis();
        }
    }

    public void cancel() {
        this.jobStatus = JobStatus.CANCELLED;
        this.completedAt = LocalDateTime.now();
    }

    public void retry() {
        this.jobStatus = JobStatus.RETRYING;
        this.retryCount++;
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (jobStatus == null) {
            jobStatus = JobStatus.PENDING;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
