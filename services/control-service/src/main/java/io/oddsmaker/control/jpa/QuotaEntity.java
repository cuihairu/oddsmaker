package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime;

/**
 * 资源配额实体
 * 管理游戏或环境的资源配额限制
 */
@Entity
@Table(name = "quotas")
public class QuotaEntity {

    /**
     * 资源类型
     */
    public enum ResourceType {
        EVENTS_PER_DAY,         // 每天事件数
        EVENTS_PER_MONTH,       // 每月事件数
        STORAGE_GB,             // 存储空间（GB）
        API_CALLS_PER_DAY,      // 每天API调用次数
        USERS,                  // 用户数量
        CONCURRENT_SESSIONS,    // 并发会话数
        REPORTS_PER_MONTH,      // 每月报告数
        EXPORTS_PER_MONTH,      // 每月导出次数
        CUSTOM_REPORTS,         // 自定义报告数量
        INTEGRATIONS,           // 集成数量
        RETENTION_DAYS         // 数据保留天数
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public String id;

    @Column(name = "game_id", nullable = false)
    public String gameId;

    @Column(name = "environment_id")
    public String environmentId;  // 环境ID（环境级别配额时使用）

    @Column(name = "resource_type", nullable = false)
    @Enumerated(EnumType.STRING)
    public ResourceType resourceType;

    @Column(name = "quota_limit", nullable = false)
    public Long quotaLimit;  // 配额限制

    @Column(name = "current_usage", columnDefinition = "BIGINT")
    public Long currentUsage = 0L;  // 当前使用量

    @Column(name = "usage_percent", columnDefinition = "DECIMAL(5,2)")
    @JdbcTypeCode(SqlTypes.NUMERIC)
    public Double usagePercent;  // 使用百分比（缓存）

    @Column(name = "warning_threshold", columnDefinition = "DECIMAL(5,2)")
    @JdbcTypeCode(SqlTypes.NUMERIC)
    public Double warningThreshold = 80.0;  // 警告阈值（百分比）

    @Column(name = "alert_threshold", columnDefinition = "DECIMAL(5,2)")
    @JdbcTypeCode(SqlTypes.NUMERIC)
    public Double alertThreshold = 95.0;  // 告警阈值（百分比）

    @Column(name = "warning_sent", columnDefinition = "BOOLEAN")
    public Boolean warningSent = false;  // 是否已发送警告

    @Column(name = "alert_sent", columnDefinition = "BOOLEAN")
    public Boolean alertSent = false;  // 是否已发送告警

    @Column(name = "hard_limit", columnDefinition = "BOOLEAN")
    public Boolean hardLimit = false;  // 是否为硬限制（超出后拒绝请求）

    @Column(name = "grace_period_days", columnDefinition = "INTEGER")
    public Integer gracePeriodDays = 0;  // 宽限期（天）

    @Column(name = "reset_at")
    public LocalDateTime resetAt;  // 重置时间

    @Column(name = "last_calculated_at")
    public LocalDateTime lastCalculatedAt;  // 最后计算时间

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @Column(name = "updated_at")
    public LocalDateTime updatedAt;

    @Column(name = "created_by", length = 64)
    public String createdBy;

    @Column(name = "deleted_at")
    public LocalDateTime deletedAt;

    // 辅助方法

    public boolean isOverLimit() {
        return currentUsage >= quotaLimit;
    }

    public boolean isNearWarningThreshold() {
        return getUsagePercent() >= warningThreshold;
    }

    public boolean isNearAlertThreshold() {
        return getUsagePercent() >= alertThreshold;
    }

    public double getUsagePercent() {
        if (quotaLimit == null || quotaLimit == 0) {
            return 0.0;
        }
        return (currentUsage * 100.0) / quotaLimit;
    }

    public boolean shouldSendWarning() {
        return isNearWarningThreshold() && (warningSent == null || !warningSent);
    }

    public boolean shouldSendAlert() {
        return isNearAlertThreshold() && (alertSent == null || !alertSent);
    }

    public void markWarningSent() {
        this.warningSent = true;
    }

    public void markAlertSent() {
        this.alertSent = true;
    }

    public void incrementUsage(long amount) {
        this.currentUsage += amount;
        this.usagePercent = getUsagePercent();
    }

    public void resetUsage() {
        this.currentUsage = 0L;
        this.usagePercent = 0.0;
        this.warningSent = false;
        this.alertSent = false;
        this.lastCalculatedAt = LocalDateTime.now();
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
