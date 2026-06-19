package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 限流策略：定义API和事件上报的速率限制
 * 保护系统免受流量冲击
 */
@Entity
@Table(name = "rate_limit_policies")
public class RateLimitPolicyEntity {

    @Id
    @Column(length = 32)
    public String id;

    @Column(name = "game_id", nullable = false, length = 32)
    public String gameId;

    @Column(name = "environment_id", length = 32)
    public String environmentId;  // null表示全局策略

    @Column(nullable = false, length = 100)
    public String name;

    @Column(name = "display_name", length = 200)
    public String displayName;

    @Column(name = "description", length = 1000)
    public String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public PolicyStatus status = PolicyStatus.ACTIVE;

    // 限流范围
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public LimitScope scope = LimitScope.GLOBAL;  // 限流范围

    // 速率限制
    @Column(name = "requests_per_second")
    public Integer requestsPerSecond;  // 每秒请求数

    @Column(name = "requests_per_minute")
    public Integer requestsPerMinute;  // 每分钟请求数

    @Column(name = "requests_per_hour")
    public Integer requestsPerHour;  // 每小时请求数

    @Column(name = "requests_per_day")
    public Integer requestsPerDay;  // 每天请求数

    // 事件限制
    @Column(name = "events_per_second")
    public Integer eventsPerSecond;  // 每秒事件数

    @Column(name = "events_per_minute")
    public Integer eventsPerMinute;  // 每分钟事件数

    @Column(name = "events_per_day")
    public Long eventsPerDay;  // 每天事件数

    // 突发流量控制
    @Column(name = "burst_size")
    public Integer burstSize = 100;  // 突发流量大小

    @Column(name = "burst_window_seconds")
    public Integer burstWindowSeconds = 10;  // 突发时间窗口（秒）

    // 限流算法
    @Column(name = "limit_algorithm", length = 50)
    public String limitAlgorithm = "token_bucket";  // 限流算法：token_bucket, leaky_bucket, fixed_window, sliding_window

    // 超限处理
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public OverLimitAction overLimitAction = OverLimitAction.REJECT;  // 超限动作

    @Column(name = "retry_after_seconds")
    public Integer retryAfterSeconds = 60;  // 重试等待时间

    @Column(name = "enable_rate_limit_headers")
    public Boolean enableRateLimitHeaders = true;  // 启用速率限制响应头

    // 白名单
    @Column(name = "enable_whitelist")
    public Boolean enableWhitelist = false;  // 启用白名单

    @Column(name = "whitelist", columnDefinition = "TEXT")
    public String whitelist;  // JSON格式的白名单

    // 统计
    @Column(name = "total_limited_count")
    public Long totalLimitedCount = 0L;  // 总限流次数

    @Column(name = "current_usage")
    public Long currentUsage = 0L;  // 当前使用量

    @Column(name = "last_reset_at")
    public LocalDateTime lastResetAt;  // 最后重置时间

    @Column(name = "limit_exceeded_at")
    public LocalDateTime limitExceededAt;  // 最后超限时间

    // 时间戳
    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    public LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    public LocalDateTime deletedAt;

    @Column(name = "created_by", length = 64)
    public String createdBy;

    // 关联关系
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "game_id", insertable = false, updatable = false)
    public GameEntity game;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "environment_id", insertable = false, updatable = false)
    public GameEnvironmentEntity environment;

    @OneToMany(mappedBy = "rateLimitPolicy", fetch = FetchType.LAZY)
    public List<RateLimitRuleEntity> rules;

    public enum PolicyStatus {
        ACTIVE,       // 活跃
        DISABLED,     // 已禁用
        ARCHIVED      // 已归档
    }

    public enum LimitScope {
        GLOBAL,       // 全局限流
        PER_API_KEY,  // 按API Key限流
        PER_USER,     // 按用户限流
        PER_DEVICE,   // 按设备限流
        PER_IP        // 按IP限流
    }

    public enum OverLimitAction {
        REJECT,       // 拒绝请求
        QUEUE,        // 排队等待
        THROTTLE,     // 节流（延迟处理）
        SAMPLE        // 采样（丢弃部分请求）
    }

    // 业务方法
    public boolean isActive() {
        return status == PolicyStatus.ACTIVE && deletedAt == null;
    }

    public boolean isGlobal() {
        return environmentId == null && scope == LimitScope.GLOBAL;
    }

    public boolean isOverLimit() {
        return limitExceededAt != null &&
               (lastResetAt == null || limitExceededAt.isAfter(lastResetAt));
    }

    public void recordLimitExceeded() {
        totalLimitedCount = (totalLimitedCount != null ? totalLimitedCount : 0) + 1;
        limitExceededAt = LocalDateTime.now();
    }

    public void resetUsage() {
        currentUsage = 0L;
        lastResetAt = LocalDateTime.now();
    }

    public long getLimitPerDay() {
        if (eventsPerDay != null) return eventsPerDay;
        if (eventsPerHour != null) return eventsPerHour * 24;
        if (eventsPerMinute != null) return eventsPerMinute * 1440;
        return Long.MAX_VALUE;
    }

    public double getUsagePercentage() {
        long limit = getLimitPerDay();
        return limit > 0 && currentUsage != null
            ? (double) currentUsage / limit
            : 0.0;
    }
}
