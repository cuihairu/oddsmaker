package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 事件采样规则：定义特定事件的采样配置
 */
@Entity
@Table(name = "event_sampling_rules")
public class EventSamplingRuleEntity {

    @Id
    @Column(length = 32)
    public String id;

    @Column(name = "sampling_policy_id", nullable = false, length = 32)
    public String samplingPolicyId;

    @Column(name = "event_name", nullable = false, length = 100)
    public String eventName;

    @Column(name = "event_type", length = 50)
    public String eventType;

    @Column(name = "sample_rate")
    public Double sampleRate = 1.0;  // 采样率

    @Column(name = "priority")
    public Integer priority = 0;  // 优先级（用于优先级采样）

    @Column(name = "min_sample_rate")
    public Double minSampleRate = 0.0;  // 最小采样率

    @Column(name = "max_sample_rate")
    public Double maxSampleRate = 1.0;  // 最大采样率

    // 条件采样
    @Column(name = "condition_expression", columnDefinition = "TEXT")
    public String conditionExpression;  // JSON格式的条件表达式

    // 统计
    @Column(name = "sampled_count")
    public Long sampledCount = 0L;

    @Column(name = "dropped_count")
    public Long droppedCount = 0L;

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
    @JoinColumn(name = "sampling_policy_id", insertable = false, updatable = false)
    public SamplingPolicyEntity samplingPolicy;

    public enum RuleStatus {
        ACTIVE,       // 活跃
        DISABLED,     // 已禁用
        DEPRECATED    // 已弃用
    }

    // 业务方法
    public boolean isActive() {
        return status == RuleStatus.ACTIVE && deletedAt == null;
    }

    public double getEffectiveSampleRate() {
        return sampleRate != null ? sampleRate : 1.0;
    }

    public boolean shouldSample() {
        return isActive() && getEffectiveSampleRate() > 0;
    }

    public double getCurrentDropRate() {
        long total = sampledCount + droppedCount;
        return total > 0 ? (double) droppedCount / total : 0.0;
    }
}
