package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 报表执行实体
 * 记录每次报表运行的详细信息
 */
@Entity
@Table(name = "report_executions")
public class ReportExecutionEntity {

    @Id
    @Column(length = 32)
    public String id;

    // 关联信息
    @Column(name = "report_id", nullable = false, length = 32)
    public String reportId;

    @Column(name = "game_id", nullable = false, length = 32)
    public String gameId;

    // 执行信息
    @Column(name = "execution_number")
    public Long executionNumber;  // 执行编号

    @Column(name = "triggered_by", length = 64)
    public String triggeredBy;  // 触发人

    @Column(name = "trigger_type", length = 20)
    public String triggerType;  // 触发类型：manual, scheduled, api

    // 参数和过滤条件
    @Column(name = "parameters", columnDefinition = "TEXT")
    public String parameters;  // 运行参数（JSON格式）

    @Column(name = "filters", columnDefinition = "TEXT")
    public String filters;  // 过滤条件（JSON格式）

    @Column(name = "time_range", length = 50)
    public String timeRange;  // 时间范围

    @Column(name = "start_time")
    public LocalDateTime startTime;  // 开始时间

    @Column(name = "end_time")
    public LocalDateTime endTime;  // 结束时间

    // 执行状态
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public ExecutionStatus executionStatus = ExecutionStatus.PENDING;

    @Column(name = "status_message", length = 500)
    public String statusMessage;  // 状态消息

    @Column(name = "error_message", columnDefinition = "TEXT")
    public String errorMessage;  // 错误信息

    // 结果数据
    @Column(name = "row_count")
    public Long rowCount;  // 结果行数

    @Column(name = "result_summary", columnDefinition = "TEXT")
    public String resultSummary;  // 结果摘要（JSON格式）

    // 数据存储
    @Column(name = "result_data", columnDefinition = "TEXT")
    public String resultData;  // 结果数据（JSON格式，小数据集）

    @Column(name = "result_storage_path", length = 500)
    public String resultStoragePath;  // 结果存储路径（大数据集）

    @Column(name = "result_size_bytes")
    public Long resultSizeBytes;  // 结果大小（字节）

    // 导出信息
    @Column(name = "export_format", length = 20)
    public String exportFormat;  // 导出格式：csv, excel, json, pdf

    @Column(name = "export_path", length = 500)
    public String exportPath;  // 导出文件路径

    @Column(name = "export_size_bytes")
    public Long exportSizeBytes;  // 导出文件大小

    // 性能指标
    @Column(name = "execution_time_ms")
    public Long executionTimeMs;  // 执行时间（毫秒）

    @Column(name = "query_time_ms")
    public Long queryTimeMs;  // 查询时间（毫秒）

    @Column(name = "render_time_ms")
    public Long renderTimeMs;  // 渲染时间（毫秒）

    // 时间戳
    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @Column(name = "completed_at")
    public LocalDateTime completedAt;  // 完成时间

    // 关联关系
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "report_id", insertable = false, updatable = false)
    public ReportEntity report;

    public enum ExecutionStatus {
        PENDING,           // 待执行
        RUNNING,           // 运行中
        COMPLETED,         // 已完成
        FAILED,            // 失败
        CANCELLED,         // 已取消
        TIMEOUT            // 超时
    }

    // 业务方法
    public boolean isPending() {
        return executionStatus == ExecutionStatus.PENDING;
    }

    public boolean isRunning() {
        return executionStatus == ExecutionStatus.RUNNING;
    }

    public boolean isCompleted() {
        return executionStatus == ExecutionStatus.COMPLETED;
    }

    public boolean isFailed() {
        return executionStatus == ExecutionStatus.FAILED || executionStatus == ExecutionStatus.TIMEOUT;
    }

    public void markAsRunning() {
        this.executionStatus = ExecutionStatus.RUNNING;
        this.startTime = LocalDateTime.now();
    }

    public void markAsCompleted(Long rowCount, String resultSummary, Long executionTimeMs) {
        this.executionStatus = ExecutionStatus.COMPLETED;
        this.rowCount = rowCount;
        this.resultSummary = resultSummary;
        this.executionTimeMs = executionTimeMs;
        this.completedAt = LocalDateTime.now();
    }

    public void markAsFailed(String errorMessage) {
        this.executionStatus = ExecutionStatus.FAILED;
        this.errorMessage = errorMessage;
        this.completedAt = LocalDateTime.now();
    }

    public void markAsCancelled(String reason) {
        this.executionStatus = ExecutionStatus.CANCELLED;
        this.statusMessage = reason;
        this.completedAt = LocalDateTime.now();
    }

    public long getExecutionTimeMinutes() {
        if (executionTimeMs == null) return 0;
        return executionTimeMs / 60000;
    }

    public String getStatusDescription() {
        return String.format("[%s] %s - %d rows (%dms)",
            executionStatus.name(),
            reportId,
            rowCount != null ? rowCount : 0,
            executionTimeMs != null ? executionTimeMs : 0
        );
    }

    public boolean hasResults() {
        return isCompleted() && rowCount != null && rowCount > 0;
    }

}
