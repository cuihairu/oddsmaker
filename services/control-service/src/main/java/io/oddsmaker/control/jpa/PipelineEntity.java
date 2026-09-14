package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 数据管道实体
 * 管理数据处理管道定义
 */
@Entity
@Table(name = "pipelines")
public class PipelineEntity {

    /**
     * 管道类型
     */
    public enum PipelineType {
        BATCH,          // 批处理
        STREAMING,      // 流处理
        HYBRID,         // 混合处理
        REALTIME,       // 实时处理
        ETL             // ETL
    }

    /**
     * 管道状态
     */
    public enum PipelineStatus {
        DRAFT,          // 草稿
        ACTIVE,         // 活跃
        PAUSED,         // 暂停
        STOPPED,        // 已停止
        FAILED,         // 失败
        ARCHIVED        // 已归档
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public String id;

    @Column(name = "game_id", nullable = false)
    public String gameId;  // 游戏ID

    @Column(name = "environment_id")
    public String environmentId;  // 环境ID

    @Column(name = "pipeline_name", nullable = false, length = 100)
    public String pipelineName;  // 管道名称

    @Column(name = "pipeline_type", nullable = false)
    @Enumerated(EnumType.STRING)
    public PipelineType pipelineType = PipelineType.BATCH;

    @Column(name = "pipeline_status", nullable = false)
    @Enumerated(EnumType.STRING)
    public PipelineStatus pipelineStatus = PipelineStatus.DRAFT;

    @Column(name = "description", length = 500)
    public String description;  // 描述

    @Column(name = "version", columnDefinition = "INTEGER")
    public Integer version = 1;  // 管道版本

    @Column(name = "source_config", columnDefinition = "TEXT")
    public String sourceConfig;  // JSON格式的数据源配置

    @Column(name = "transform_config", columnDefinition = "TEXT")
    public String transformConfig;  // JSON格式的转换配置

    @Column(name = "destination_config", columnDefinition = "TEXT")
    public String destinationConfig;  // JSON格式的目标配置

    @Column(name = "quality_rules", columnDefinition = "TEXT")
    public String qualityRules;  // JSON格式的数据质量规则

    @Column(name = "error_handling", columnDefinition = "TEXT")
    public String errorHandling;  // JSON格式的错误处理配置

    @Column(name = "schedule_config", columnDefinition = "TEXT")
    public String scheduleConfig;  // JSON格式的调度配置

    @Column(name = "resource_config", columnDefinition = "TEXT")
    public String resourceConfig;  // JSON格式的资源配置

    @Column(name = "partition_config", columnDefinition = "TEXT")
    public String partitionConfig;  // JSON格式的分区配置

    @Column(name = "priority", columnDefinition = "INTEGER")
    public Integer priority = 5;  // 优先级（1-10）

    @Column(name = "max_retries", columnDefinition = "INTEGER")
    public Integer maxRetries = 3;  // 最大重试次数

    @Column(name = "timeout_seconds", columnDefinition = "INTEGER")
    public Integer timeoutSeconds = 3600;  // 超时时间（秒）

    @Column(name = "enabled", columnDefinition = "BOOLEAN")
    public Boolean enabled = true;  // 是否启用

    @Column(name = "last_run_at")
    public LocalDateTime lastRunAt;  // 最后运行时间

    @Column(name = "last_success_at")
    public LocalDateTime lastSuccessAt;  // 最后成功时间

    @Column(name = "last_failure_at")
    public LocalDateTime lastFailureAt;  // 最后失败时间

    @Column(name = "run_count", columnDefinition = "INTEGER")
    public Integer runCount = 0;  // 运行次数

    @Column(name = "success_count", columnDefinition = "INTEGER")
    public Integer successCount = 0;  // 成功次数

    @Column(name = "failure_count", columnDefinition = "INTEGER")
    public Integer failureCount = 0;  // 失败次数

    @Column(name = "last_error", columnDefinition = "TEXT")
    public String lastError;  // 最后错误信息

    @Column(name = "created_by", nullable = false, length = 64)
    public String createdBy;  // 创建人

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @Column(name = "updated_at")
    public LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    public LocalDateTime deletedAt;

    // 辅助方法

    public boolean isActive() {
        return pipelineStatus == PipelineStatus.ACTIVE && enabled;
    }

    public boolean isPaused() {
        return pipelineStatus == PipelineStatus.PAUSED;
    }

    public boolean isFailed() {
        return pipelineStatus == PipelineStatus.FAILED;
    }

    public boolean isStopped() {
        return pipelineStatus == PipelineStatus.STOPPED;
    }

    public boolean isBatch() {
        return pipelineType == PipelineType.BATCH;
    }

    public boolean isStreaming() {
        return pipelineType == PipelineType.STREAMING;
    }

    public double getSuccessRate() {
        if (runCount == null || runCount == 0) {
            return 0.0;
        }
        return ((double) successCount) / runCount * 100;
    }

    public boolean needsRetry() {
        return failureCount > 0 && (maxRetries == null || failureCount < maxRetries);
    }

    public void activate() {
        this.pipelineStatus = PipelineStatus.ACTIVE;
    }

    public void pause() {
        this.pipelineStatus = PipelineStatus.PAUSED;
    }

    public void stop() {
        this.pipelineStatus = PipelineStatus.STOPPED;
    }

    public void recordRun(boolean success, String error) {
        this.runCount++;
        this.lastRunAt = LocalDateTime.now();

        if (success) {
            this.successCount++;
            this.lastSuccessAt = LocalDateTime.now();
            this.lastError = null;
        } else {
            this.failureCount++;
            this.lastFailureAt = LocalDateTime.now();
            this.lastError = error;
        }
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (pipelineStatus == null) {
            pipelineStatus = PipelineStatus.DRAFT;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
