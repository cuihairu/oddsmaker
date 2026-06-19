package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 采样策略：定义事件采集的采样规则
 * 控制哪些事件被采样、采样率等
 */
@Entity
@Table(name = "sampling_policies")
public class SamplingPolicyEntity {

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

    // 采样配置
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public SamplingStrategy strategy = SamplingStrategy.UNIFORM;  // 采样策略

    @Column(name = "default_sample_rate")
    public Double defaultSampleRate = 1.0;  // 默认采样率

    @Column(name = "max_events_per_second")
    public Integer maxEventsPerSecond;  // 每秒最大事件数

    @Column(name = "max_events_per_day")
    public Long maxEventsPerDay;  // 每天最大事件数

    // 采样算法
    @Column(name = "sampling_algorithm", length = 50)
    public String samplingAlgorithm = "consistent_hash";  // 采样算法：consistent_hash, random, reservoir

    @Column(name = "hash_key", length = 100)
    public String hashKey = "user_id";  // 哈希键：user_id, device_id, event_id

    // 动态采样
    @Column(name = "enable_dynamic_sampling")
    public Boolean enableDynamicSampling = false;  // 启用动态采样

    @Column(name = "dynamic_sampling_rules", columnDefinition = "TEXT")
    public String dynamicSamplingRules;  // JSON格式的动态采样规则

    // 优先级采样
    @Column(name = "enable_priority_sampling")
    public Boolean enablePrioritySampling = false;  // 启用优先级采样

    @Column(name = "priority_events", columnDefinition = "TEXT")
    public String priorityEvents;  // JSON数组：高优先级事件列表

    // 统计
    @Column(name = "total_sampled_count")
    public Long totalSampledCount = 0L;  // 总采样数

    @Column(name = "total_dropped_count")
    public Long totalDroppedCount = 0L;  // 总丢弃数

    @Column(name = "effective_sample_rate")
    public Double effectiveSampleRate = 1.0;  // 实际采样率

    @Column(name = "last_calculated_at")
    public LocalDateTime lastCalculatedAt;  // 最后计算时间

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

    @OneToMany(mappedBy = "samplingPolicy", fetch = FetchType.LAZY)
    public List<EventSamplingRuleEntity> eventRules;

    public enum PolicyStatus {
        ACTIVE,       // 活跃
        DISABLED,     // 已禁用
        ARCHIVED      // 已归档
    }

    public enum SamplingStrategy {
        UNIFORM,      // 统一采样：所有事件相同采样率
        EVENT_BASED,  // 基于事件：不同事件不同采样率
        USER_BASED,   // 基于用户：保证用户事件的完整性
        ADAPTIVE,     // 自适应：根据负载动态调整
        PRIORITY      // 优先级：优先采样重要事件
    }

    // 业务方法
    public boolean isActive() {
        return status == PolicyStatus.ACTIVE && deletedAt == null;
    }

    public boolean isGlobal() {
        return environmentId == null;
    }

    public boolean isDynamicSamplingEnabled() {
        return Boolean.TRUE.equals(enableDynamicSampling);
    }

    public boolean isPrioritySamplingEnabled() {
        return Boolean.TRUE.equals(enablePrioritySampling);
    }

    public double getEffectiveSampleRate() {
        return effectiveSampleRate != null ? effectiveSampleRate : defaultSampleRate;
    }

    public boolean shouldSample(String eventType) {
        // TODO: 实现基于事件类型的采样逻辑
        return true;
    }

    public void incrementSampled() {
        totalSampledCount = (totalSampledCount != null ? totalSampledCount : 0) + 1;
    }

    public void incrementDropped() {
        totalDroppedCount = (totalDroppedCount != null ? totalDroppedCount : 0) + 1;
    }

    public double getCurrentDropRate() {
        long total = totalSampledCount + totalDroppedCount;
        return total > 0 ? (double) totalDroppedCount / total : 0.0;
    }
}
