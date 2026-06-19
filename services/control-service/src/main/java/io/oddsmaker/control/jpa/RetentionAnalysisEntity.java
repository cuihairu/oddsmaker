package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 留存分析配置
 * 定义N-Day留存和Rolling留存的分析配置
 */
@Entity
@Table(name = "retention_analyses")
public class RetentionAnalysisEntity {

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

    // 留存类型
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public RetentionType retentionType = RetentionType.N_DAY;  // N-Day或Rolling

    // 触发事件（首次出现事件）
    @Column(name = "trigger_event", nullable = false, length = 100)
    public String triggerEvent;  // 如：session_start, user_register, level_complete

    @Column(name = "trigger_condition", columnDefinition = "TEXT")
    public String triggerCondition;  // JSON格式的触发条件

    // 返回事件（用于计算留存）
    @Column(name = "return_event", nullable = false, length = 100)
    public String returnEvent;  // 如：session_start

    @Column(name = "return_condition", columnDefinition = "TEXT")
    public String returnCondition;  // JSON格式的返回条件

    // 时间窗口配置
    @Column(name = "time_windows", columnDefinition = "TEXT")
    public String timeWindows;  // JSON数组：[1, 3, 7, 14, 30] 表示Day 1/3/7/14/30留存

    // Rolling留存配置
    @Column(name = "rolling_window_days")
    public Integer rollingWindowDays = 7;  // Rolling窗口天数（7天、14天、30天）

    @Column(name = "rolling_interval_days")
    public Integer rollingIntervalDays = 1;  // Rolling间隔天数

    // 用户群配置
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public CohortType cohortType = CohortType.ALL_USERS;  // 队列类型

    @Column(name = "cohort_filter", columnDefinition = "TEXT")
    public String cohortFilter;  // JSON格式的队列过滤条件

    // 计算设置
    @Column(name = "include_same_day")
    public Boolean includeSameDay = false;  // 是否包含当天留存（Day 0）

    @Column(name = "minimum_cohort_size")
    public Integer minimumCohortSize = 100;  // 最小队列大小

    @Column(name = "confidence_level")
    public Double confidenceLevel = 0.95;  // 置信水平

    // 分组
    @Column(name = "group_by_dimensions", columnDefinition = "TEXT")
    public String groupByDimensions;  // JSON数组：分组维度

    // 状态
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public AnalysisStatus status = AnalysisStatus.ACTIVE;

    // 计算设置
    @Column(name = "enable_auto_calc")
    public Boolean enableAutoCalc = true;  // 启用自动计算

    @Column(name = "calc_frequency")
    public String calcFrequency = "daily";  // 计算频率：hourly, daily, weekly

    @Column(name = "last_calculated_at")
    public LocalDateTime lastCalculatedAt;  // 最后计算时间

    // 结果存储
    @Column(name = "result_table", length = 100)
    public String resultTable;  // 结果表名

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

    public enum RetentionType {
        N_DAY,         // N-Day留存：固定队列的留存率
        ROLLING,       // Rolling留存：滚动窗口留存
        CURVE         // 留存曲线：完整留存曲线
    }

    public enum CohortType {
        ALL_USERS,           // 所有用户
        NEW_USERS,           // 新用户
        PAYING_USERS,        // 付费用户
        ACTIVE_USERS,        // 活跃用户
        CUSTOM               // 自定义队列
    }

    public enum AnalysisStatus {
        ACTIVE,              // 活跃
        PAUSED,              // 暂停
        ARCHIVED,            // 已归档
        DEPRECATED           // 已弃用
    }

    // 业务方法
    public boolean isActive() {
        return status == AnalysisStatus.ACTIVE && deletedAt == null;
    }

    public boolean isAutoCalcEnabled() {
        return Boolean.TRUE.equals(enableAutoCalc);
    }

    public boolean isNDayRetention() {
        return retentionType == RetentionType.N_DAY;
    }

    public boolean isRollingRetention() {
        return retentionType == RetentionType.ROLLING;
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
            default -> lastCalculatedAt.plusDays(1);
        };
    }

    public String getQualifiedName() {
        return game != null ? game.id + "_" + name : name;
    }
}
