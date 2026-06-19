package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 漏斗步骤实体
 * 定义漏斗中的单个步骤配置
 */
@Entity
@Table(name = "funnel_steps")
public class FunnelStepEntity {

    @Id
    @Column(length = 32)
    public String id;

    @Column(name = "funnel_analysis_id", nullable = false, length = 32)
    public String funnelAnalysisId;

    @Column(nullable = false)
    public Integer stepOrder;  // 步骤顺序（从1开始）

    @Column(nullable = false, length = 100)
    public String eventName;  // 事件名称

    @Column(name = "display_name", length = 200)
    public String displayName;  // 显示名称

    @Column(length = 1000)
    public String description;  // 描述

    // 步骤条件
    @Column(name = "step_condition", columnDefinition = "TEXT")
    public String stepCondition;  // JSON格式的步骤条件

    @Column(name = "event_filter", columnDefinition = "TEXT")
    public String eventFilter;  // JSON格式的事件过滤条件

    // 时间限制
    @Column(name = "time_from_previous")
    public Integer timeFromPrevious;  // 距上一步的最大时间（分钟）

    @Column(name = "time_from_start")
    public Integer timeFromStart;  // 距开始的最大时间（分钟）

    @Column(name = "time_to_next")
    public Integer timeToNext;  // 到下一步的最大时间（分钟）

    // 可选步骤
    @Column(name = "is_optional")
    public Boolean isOptional = false;  // 是否为可选步骤

    @Column(name = "allow_skip")
    public Boolean allowSkip = false;  // 是否允许跳过

    // 分支配置
    @Column(name = "branch_condition", columnDefinition = "TEXT")
    public String branchCondition;  // JSON格式的分支条件

    @Column(name = "is_branch_point")
    public Boolean isBranchPoint = false;  // 是否为分支点

    // 目标转化率
    @Column(name = "target_conversion_rate")
    public Double targetConversionRate;  // 目标转化率（0-1）

    // 自定义属性
    @Column(name = "custom_attributes", columnDefinition = "TEXT")
    public String customAttributes;  // JSON格式的自定义属性

    // 状态
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public StepStatus status = StepStatus.ACTIVE;

    // 统计（由计算结果填充）
    @Column(name = "conversion_rate")
    public Double conversionRate;  // 实际转化率

    @Column(name = "drop_off_rate")
    public Double dropOffRate;  // 流失率

    @Column(name = "median_time")
    public Long medianTime;  // 中位时间（毫秒）

    @Column(name = "average_time")
    public Long averageTime;  // 平均时间（毫秒）

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
    @JoinColumn(name = "funnel_analysis_id", insertable = false, updatable = false)
    public FunnelAnalysisEntity funnelAnalysis;

    public enum StepStatus {
        ACTIVE,         // 活跃
        DISABLED,       // 已禁用
        HIDDEN          // 隐藏
    }

    // 业务方法
    public boolean isActive() {
        return status == StepStatus.ACTIVE && deletedAt == null;
    }

    public boolean isOptional() {
        return Boolean.TRUE.equals(isOptional);
    }

    public boolean canBeSkipped() {
        return Boolean.TRUE.equals(allowSkip);
    }

    public boolean isBranchPoint() {
        return Boolean.TRUE.equals(isBranchPoint);
    }

    public boolean hasTimeLimit() {
        return timeFromPrevious != null || timeFromStart != null;
    }

    public boolean hasTarget() {
        return targetConversionRate != null && targetConversionRate > 0;
    }

    public boolean meetsTarget() {
        if (!hasTarget() || conversionRate == null) return false;
        return conversionRate >= targetConversionRate;
    }

    public Double getEfficiency() {
        if (conversionRate == null) return null;
        // 简单效率计算：转化率 / 步骤顺序权重
        return conversionRate / (1 + stepOrder * 0.1);
    }

    public Long getAverageTimeInSeconds() {
        return averageTime != null ? averageTime / 1000 : null;
    }

    public Long getMedianTimeInSeconds() {
        return medianTime != null ? medianTime / 1000 : null;
    }
}
