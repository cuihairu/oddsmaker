package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 限流规则：特定条件下的限流配置
 */
@Entity
@Table(name = "rate_limit_rules")
public class RateLimitRuleEntity {

    @Id
    @Column(length = 32)
    public String id;

    @Column(name = "rate_limit_policy_id", nullable = false, length = 32)
    public String rateLimitPolicyId;

    @Column(name = "rule_name", nullable = false, length = 100)
    public String ruleName;

    @Column(name = "event_type", length = 50)
    public String eventType;  // 事件类型过滤

    @Column(name = "event_name", length = 100)
    public String eventName;  // 事件名过滤

    @Column(name = "condition_expression", columnDefinition = "TEXT")
    public String conditionExpression;  // JSON格式的条件表达式

    // 覆盖默认限制
    @Column(name = "override_requests_per_second")
    public Integer overrideRequestsPerSecond;

    @Column(name = "override_events_per_second")
    public Integer overrideEventsPerSecond;

    @Column(name = "override_events_per_day")
    public Long overrideEventsPerDay;

    // 白名单
    @Column(name = "enable_whitelist")
    public Boolean enableWhitelist = false;

    @Column(name = "whitelist", columnDefinition = "TEXT")
    public String whitelist;  // JSON格式的白名单（API Keys, IPs等）

    // 统计
    @Column(name = "triggered_count")
    public Long triggeredCount = 0L;

    @Column(name = "last_triggered_at")
    public LocalDateTime lastTriggeredAt;

    // 状态
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public RuleStatus status = RuleStatus.ACTIVE;

    // 时间戳
    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    public LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    public LocalDateTime deletedAt;

    // 关联关系
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rate_limit_policy_id", insertable = false, updatable = false)
    public RateLimitPolicyEntity rateLimitPolicy;

    public enum RuleStatus {
        ACTIVE,       // 活跃
        DISABLED,     // 已禁用
        DEPRECATED    // 已弃用
    }

    // 业务方法
    public boolean isActive() {
        return status == RuleStatus.ACTIVE && deletedAt == null;
    }

    public boolean matchesCondition(String eventType, String eventName) {
        if (!isActive()) return false;
        if (this.eventType != null && !this.eventType.equals(eventType)) return false;
        if (this.eventName != null && !this.eventName.equals(eventName)) return false;
        return true;
    }

    public void recordTriggered() {
        triggeredCount = (triggeredCount != null ? triggeredCount : 0) + 1;
        lastTriggeredAt = LocalDateTime.now();
    }

    public Integer getEffectiveRequestsPerSecond(Integer defaultValue) {
        return overrideRequestsPerSecond != null ? overrideRequestsPerSecond : defaultValue;
    }

    public Integer getEffectiveEventsPerSecond(Integer defaultValue) {
        return overrideEventsPerSecond != null ? overrideEventsPerSecond : defaultValue;
    }

    public Long getEffectiveEventsPerDay(Long defaultValue) {
        return overrideEventsPerDay != null ? overrideEventsPerDay : defaultValue;
    }
}
