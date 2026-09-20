package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 数据导出任务实体
 * 管理用户数据导出请求和执行状态
 */
@Entity
@Table(name = "export_jobs")
public class ExportJobEntity {

    @Id
    @Column(length = 32)
    public String id;

    // 关联信息
    @Column(name = "game_id", nullable = false, length = 32)
    public String gameId;

    @Column(name = "environment_id", length = 32)
    public String environmentId;

    @Column(name = "user_id", length = 64)
    public String userId;  // 请求用户

    // 导出配置
    @Column(name = "export_type", length = 50)
    public String exportType;  // 导出类型：events, users, sessions, reports, risk_cases

    @Column(name = "data_source", columnDefinition = "TEXT")
    public String dataSource;  // 数据源配置（JSON格式）

    @Column(name = "filters", columnDefinition = "TEXT")
    public String filters;  // 过滤条件（JSON格式）

    @Column(name = "columns", columnDefinition = "TEXT")
    public String columns;  // 导出列配置（JSON数组）

    // 时间范围
    @Column(name = "start_time")
    public LocalDateTime startTime;  // 数据开始时间

    @Column(name = "end_time")
    public LocalDateTime endTime;  // 数据结束时间

    // 格式配置
    @Column(name = "export_format", length = 20)
    public String exportFormat = "csv";  // 导出格式：csv, excel, json, parquet

    @Column(name = "file_name", length = 255)
    public String fileName;  // 文件名

    @Column(name = "compression", length = 20)
    public String compression;  // 压缩格式：none, gzip, zip

    // 执行状态
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public ExportStatus exportStatus = ExportStatus.PENDING;

    @Column(name = "status_message", length = 500)
    public String statusMessage;  // 状态消息

    @Column(name = "error_message", columnDefinition = "TEXT")
    public String errorMessage;  // 错误信息

    // 进度跟踪
    @Column(name = "total_rows")
    public Long totalRows;  // 总行数

    @Column(name = "exported_rows")
    public Long exportedRows;  // 已导出行数

    @Column(name = "progress_percent")
    public Integer progressPercent;  // 进度百分比

    // 结果信息
    @Column(name = "file_path", length = 500)
    public String filePath;  // 文件存储路径

    @Column(name = "file_size_bytes")
    public Long fileSizeBytes;  // 文件大小（字节）

    @Column(name = "download_url", length = 500)
    public String downloadUrl;  // 下载URL

    @Column(name = "expires_at")
    public LocalDateTime expiresAt;  // 文件过期时间

    // 性能指标
    @Column(name = "started_at")
    public LocalDateTime startedAt;  // 开始时间

    @Column(name = "completed_at")
    public LocalDateTime completedAt;  // 完成时间

    @Column(name = "execution_time_ms")
    public Long executionTimeMs;  // 执行时间（毫秒）

    // 通知配置
    @Column(name = "notify_on_complete")
    public Boolean notifyOnComplete = false;  // 完成后通知

    @Column(name = "notification_email", length = 255)
    public String notificationEmail;  // 通知邮箱

    // 时间戳
    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @Column(name = "deleted_at")
    public LocalDateTime deletedAt;

    public enum ExportStatus {
        PENDING,           // 待处理
        PROCESSING,        // 处理中
        COMPLETED,         // 已完成
        FAILED,            // 失败
        CANCELLED,         // 已取消
        EXPIRED            // 已过期
    }

    // 业务方法
    public boolean isPending() {
        return exportStatus == ExportStatus.PENDING;
    }

    public boolean isProcessing() {
        return exportStatus == ExportStatus.PROCESSING;
    }

    public boolean isCompleted() {
        return exportStatus == ExportStatus.COMPLETED;
    }

    public boolean isFailed() {
        return exportStatus == ExportStatus.FAILED;
    }

    public boolean isExpired() {
        return exportStatus == ExportStatus.EXPIRED ||
               (expiresAt != null && LocalDateTime.now().isAfter(expiresAt));
    }

    public void markAsProcessing() {
        this.exportStatus = ExportStatus.PROCESSING;
        this.startedAt = LocalDateTime.now();
    }

    public void markAsCompleted(String filePath, Long fileSizeBytes, Long totalRows) {
        this.exportStatus = ExportStatus.COMPLETED;
        this.filePath = filePath;
        this.fileSizeBytes = fileSizeBytes;
        this.totalRows = totalRows;
        this.exportedRows = totalRows;
        this.progressPercent = 100;
        this.completedAt = LocalDateTime.now();

        if (this.startedAt != null) {
            this.executionTimeMs = java.time.temporal.ChronoUnit.MILLIS.between(this.startedAt, this.completedAt);
        }

        // 设置7天后过期
        this.expiresAt = LocalDateTime.now().plusDays(7);
    }

    public void markAsFailed(String errorMessage) {
        this.exportStatus = ExportStatus.FAILED;
        this.errorMessage = errorMessage;
        this.completedAt = LocalDateTime.now();
    }

    public void markAsCancelled(String reason) {
        this.exportStatus = ExportStatus.CANCELLED;
        this.statusMessage = reason;
        this.completedAt = LocalDateTime.now();
    }

    public void updateProgress(Long exportedRows, Integer progressPercent) {
        this.exportedRows = exportedRows;
        this.progressPercent = progressPercent;
    }

    public String getFileSizeDisplay() {
        if (fileSizeBytes == null) return "Unknown";

        long bytes = fileSizeBytes;
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        } else {
            return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }

    public String getStatusDescription() {
        return String.format("[%s] %s - %s (%d/%d rows, %s)",
            exportStatus.name(),
            exportType,
            fileName != null ? fileName : "no-filename",
            exportedRows != null ? exportedRows : 0,
            totalRows != null ? totalRows : 0,
            getFileSizeDisplay()
        );
    }

    public long getExecutionTimeMinutes() {
        if (executionTimeMs == null) return 0;
        return executionTimeMs / 60000;
    }
}
