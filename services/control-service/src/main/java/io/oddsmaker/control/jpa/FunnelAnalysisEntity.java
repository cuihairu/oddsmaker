package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 漏斗分析配置
 * 定义N步可配置漏斗的分析配置
 */
@Entity
@Table(name = "funnel_analyses")
public class FunnelAnalysisEntity {

    @Id
    @Column(length = 32)
    public String id;

    @Column(name = "game_id", nullable = false, length = 32)
    public String gameId;

    @Column(nullable = false, length = 100)
    public String name;

    @Column(name = "display_name", length = 200)
    public String displayName;

    @Column(length = 1000)
    public String description;

    // 漏斗类型
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public FunnelType funnelType = FunnelType.SEQUENTIAL;  // 顺序漏斗

    @Column(name = "window_type", nullable = false, length = 50)
    public String windowType = "fixed";  // 窗口类型：fixed, rolling

    @Column(name = "window_size")
    public Integer windowSize = 7;  // 窗口大小（天）

    // 步骤配置（JSON数组）
    @Column(name = "funnel_steps", columnDefinition = "TEXT")
    public String funnelSteps;  // JSON: [{"event": "level_start", "order": 1}, {"event": "level_complete", "order": 2}]

    @Column(name = "total_steps")
    public Integer totalSteps = 0;  // 总步骤数

    // 用户群配置
    @Column(name = "cohort_filter", columnDefinition = "TEXT")
    public String cohortFilter;  // JSON格式的队列过滤条件

    @Column(name = "entry_event", length = 100)
    public String entryEvent;  // 入口事件（第一步）

    @Column(name = "exit_event", length = 100)
    public String exitEvent;  // 出口事件（最后一步）

    // 时间限制
    @Column(name = "max_completion_time")
    public Integer maxCompletionTime;  // 最大完成时间（分钟）

    @Column(name = "step_timeout")
    public Integer stepTimeout;  // 步骤超时（分钟）

    // 回溯配置
    @Column(name = "allow_backtracking")
    public Boolean allowBacktracking = false;  // 允许回溯（倒退）

    @Column(name = "strict_order")
    public Boolean strictOrder = true;  // 严格顺序（必须按顺序）

    @Column(name = "allow_repeats")
    public Boolean allowRepeats = false;  // 允许重复步骤

    // 分组
    @Column(name = "group_by_dimensions", columnDefinition = "TEXT")
    public String groupByDimensions;  // JSON数组：分组维度

    // 转化率计算
    @Column(name = "conversion_calculation")
    public String conversionCalculation = "linear";  // 线性或总体转化率

    @Column(name = "include_drop_off_analysis")
    public Boolean includeDropOffAnalysis = true;  // 包含流失分析

    @Column(name = "include_time_analysis")
    public Boolean includeTimeAnalysis = true;  // 包含时间分析

    // 状态
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public AnalysisStatus status = AnalysisStatus.ACTIVE;

    // 计算设置
    @Column(name = "enable_auto_calc")
    public Boolean enableAutoCalc = true;

    @Column(name = "calc_frequency")
    public String calcFrequency = "daily";

    @Column(name = "last_calculated_at")
    public LocalDateTime lastCalculatedAt;

    // 结果存储
    @Column(name = "result_table", length = 100)
    public String resultTable;

    // 可视化配置
    @Column(name = "chart_type", length = 50)
    public String chartType = "bar";  // 可视化类型

    @Column(name = "color_scheme", length = 50)
    public String colorScheme = "default";  // 配色方案

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

    @OneToMany(mappedBy = "funnelAnalysis", fetch = FetchType.LAZY)
    public List<FunnelStepEntity> steps;

    public enum FunnelType {
        SEQUENTIAL,      // 顺序漏斗：按固定顺序完成
        ANY_ORDER,       // 任意顺序：可以任意顺序完成
        TIME_BASED,      // 基于时间：在时间窗口内完成
        CONDITIONAL      // 条件漏斗：满足特定条件
    }

    public enum AnalysisStatus {
        ACTIVE,          // 活跃
        PAUSED,          // 暂停
        ARCHIVED,        // 已归档
        DEPRECATED       // 已弃用
    }

    // 业务方法
    public boolean isActive() {
        return status == AnalysisStatus.ACTIVE && deletedAt == null;
    }

    public boolean isAutoCalcEnabled() {
        return Boolean.TRUE.equals(enableAutoCalc);
    }

    public boolean isSequential() {
        return funnelType == FunnelType.SEQUENTIAL;
    }

    public boolean isStrictOrder() {
        return Boolean.TRUE.equals(strictOrder);
    }

    public boolean allowsBacktracking() {
        return Boolean.TRUE.equals(allowBacktracking);
    }

    public boolean needsCalculation() {
        if (!isActive() || !isAutoCalcEnabled()) return false;

        LocalDateTime nextCalcTime = calculateNextCalcTime();
        return nextCalcTime != null &&
               (lastCalculatedAt == null || lastCalculatedAt.isBefore(nextCalcTime));
    }

    public LocalDateTime calculateNextCalcTime() {
        if (lastCalculatedAt == null) return LocalDateTime.now();

        return switch (calcFrequency) {
            case "hourly" -> lastCalculatedAt.plusHours(1);
            case "daily" -> lastCalculatedAt.plusDays(1);
            case "weekly" -> lastCalculatedAt.plusWeeks(1);
            case "realtime" -> LocalDateTime.now();
            default -> lastCalculatedAt.plusDays(1);
        };
    }

    public String getQualifiedName() {
        return game != null ? game.id + "_" + name : name;
    }

    public Integer getWindowSizeInMinutes() {
        return windowSize != null ? windowSize * 1440 : 10080;  // 默认7天
    }
}
