package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 系统告警实体
 * 记录系统级别的告警
 */
@Entity
@Table(name = "system_alerts")
public class SystemAlertEntity {

    /**
     * 告警严重级别
     */
    public enum Severity {
        INFO,           // 信息
        WARNING,        // 警告
        ERROR,          // 错误
        CRITICAL,       // 严重
        EMERGENCY       // 紧急
    }

    /**
     * 告警类型
     */
    public enum AlertType {
        SYSTEM_DOWN,        // 系统离线
        HIGH_CPU,           // CPU过高
        HIGH_MEMORY,        // 内存过高
        HIGH_DISK,          // 磁盘过高
        SLOW_RESPONSE,      // 响应缓慢
        HIGH_ERROR_RATE,     // 错误率过高
        QUEUE_BUILDUP,      // 队列堆积
        CONNECTION_LEAK,    // 连接泄漏
        DATABASE_ISSUE,     // 数据库问题
        STORAGE_ISSUE,      // 存储问题
        EXTERNAL_SERVICE,   // 外部服务问题
        SECURITY_EVENT,     // 安全事件
        QUOTA_EXCEEDED,     // 配额超限
        ANOMALY_DETECTED    // 异常检测
    }

    /**
     * 告警状态
     */
    public enum AlertStatus {
        OPEN,           // 开放
        ACKNOWLEDGED,   // 已确认
        INVESTIGATING,  // 调查中
        RESOLVED,       // 已解决
        CLOSED,         // 已关闭
        SNOOZED         // 暂时忽略
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public String id;

    @Column(name = "alert_type", nullable = false)
    @Enumerated(EnumType.STRING)
    public AlertType alertType;

    @Column(name = "severity", nullable = false)
    @Enumerated(EnumType.STRING)
    public Severity severity = Severity.WARNING;

    @Column(name = "alert_status", nullable = false)
    @Enumerated(EnumType.STRING)
    public AlertStatus alertStatus = AlertStatus.OPEN;

    @Column(name = "title", nullable = false, length = 200)
    public String title;  // 告警标题

    @Column(name = "description", columnDefinition = "TEXT")
    public String description;  // 告警描述

    @Column(name = "source", length = 100)
    public String source;  // 告警来源

    @Column(name = "affected_resource", length = 200)
    public String affectedResource;  // 受影响的资源

    @Column(name = "metric_value", columnDefinition = "DECIMAL(20,4)")
    public Double metricValue;  // 触发告警的指标值

    @Column(name = "threshold_value", columnDefinition = "DECIMAL(20,4)")
    public Double thresholdValue;  // 阈值

    @Column(name = "condition", length = 50)
    public String condition;  // 触发条件（gt, lt, eq）

    @Column(name = "details", columnDefinition = "TEXT")
    public String details;  // JSON格式的详细信息

    @Column(name = "context", columnDefinition = "TEXT")
    public String context;  // JSON格式的上下文信息

    @Column(name = "runbook_url", length = 500)
    public String runbookUrl;  // 运维手册URL

    @Column(name = "acknowledged_by", length = 64)
    public String acknowledgedBy;  // 确认人

    @Column(name = "acknowledged_at")
    public LocalDateTime acknowledgedAt;  // 确认时间

    @Column(name = "acknowledgement_comment", columnDefinition = "TEXT")
    public String acknowledgementComment;  // 确认备注

    @Column(name = "assigned_to", length = 64)
    public String assignedTo;  // 分配给谁

    @Column(name = "resolved_by", length = 64)
    public String resolvedBy;  // 解决人

    @Column(name = "resolved_at")
    public LocalDateTime resolvedAt;  // 解决时间

    @Column(name = "resolution_comment", columnDefinition = "TEXT")
    public String resolutionComment;  // 解决备注

    @Column(name = "escalation_level", columnDefinition = "INTEGER")
    public Integer escalationLevel = 0;  // 升级级别

    @Column(name = "escalated_at")
    public LocalDateTime escalatedAt;  // 升级时间

    @Column(name = "notification_sent", columnDefinition = "BOOLEAN")
    public Boolean notificationSent = false;  // 是否已发送通知

    @Column(name = "snoozed_until")
    public LocalDateTime snoozedUntil;  // 暂时忽略直到

    @Column(name = "occurrence_count", columnDefinition = "INTEGER")
    public Integer occurrenceCount = 1;  // 发生次数

    @Column(name = "first_occurred_at")
    public LocalDateTime firstOccurredAt;  // 首次发生时间

    @Column(name = "last_occurred_at")
    public LocalDateTime lastOccurredAt;  // 最后发生时间

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @Column(name = "updated_at")
    public LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    public LocalDateTime deletedAt;

    // 辅助方法

    public boolean isOpen() {
        return alertStatus == AlertStatus.OPEN;
    }

    public boolean isAcknowledged() {
        return alertStatus == AlertStatus.ACKNOWLEDGED;
    }

    public boolean isResolved() {
        return alertStatus == AlertStatus.RESOLVED || alertStatus == AlertStatus.CLOSED;
    }

    public boolean isCritical() {
        return severity == Severity.CRITICAL || severity == Severity.EMERGENCY;
    }

    public boolean isWarning() {
        return severity == Severity.WARNING || severity == Severity.INFO;
    }

    public boolean needsEscalation() {
        return isCritical() && !isResolved() && escalationLevel == 0;
    }

    public void acknowledge(String acknowledgedBy, String comment) {
        this.alertStatus = AlertStatus.ACKNOWLEDGED;
        this.acknowledgedBy = acknowledgedBy;
        this.acknowledgedAt = LocalDateTime.now();
        this.acknowledgementComment = comment;
    }

    public void resolve(String resolvedBy, String comment) {
        this.alertStatus = AlertStatus.RESOLVED;
        this.resolvedBy = resolvedBy;
        this.resolvedAt = LocalDateTime.now();
        this.resolutionComment = comment;
    }

    public void escalate() {
        this.escalationLevel++;
        this.escalatedAt = LocalDateTime.now();
    }

    public void snooze(LocalDateTime until) {
        this.alertStatus = AlertStatus.SNOOZED;
        this.snoozedUntil = until;
    }

    public void unsnooze() {
        if (alertStatus == AlertStatus.SNOOZED) {
            this.alertStatus = AlertStatus.OPEN;
            this.snoozedUntil = null;
        }
    }

    public long getDurationMinutes() {
        if (resolvedAt != null) {
            return java.time.Duration.between(firstOccurredAt, resolvedAt).toMinutes();
        }
        return java.time.Duration.between(firstOccurredAt, LocalDateTime.now()).toMinutes();
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (firstOccurredAt == null) {
            firstOccurredAt = LocalDateTime.now();
        }
        if (lastOccurredAt == null) {
            lastOccurredAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
