package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 业务指标告警规则实体
 * 按游戏配置 DAU/收入/崩溃率等指标的告警条件（绝对阈值或较昨日同时段的偏差百分比）。
 */
@Entity
@Table(name = "metric_alert_rules")
public class MetricAlertRuleEntity {

    /** 指标类型 */
    public enum MetricType {
        DAU,            // 日活（近窗口去重主体数）
        REVENUE,        // 收入（窗口内正收入合计）
        CRASH_RATE      // 崩溃率（error 主体数 / 活跃主体数）
    }

    /** 条件类型 */
    public enum ConditionType {
        ABSOLUTE,               // 绝对阈值：metric 与 threshold 直接比较
        BASELINE_DEVIATION      // 同比偏差：较昨日同时段偏差超 ±deviationPct
    }

    /** 比较方向（BASELINE_DEVIATION 的 BOTH 表示双向 ±N%） */
    public enum Comparison {
        GT,     // 高于阈值 / 仅报上涨
        LT,     // 低于阈值 / 仅报下跌
        BOTH    // 双向偏差（仅 BASELINE_DEVIATION）
    }

    /** 评估窗口 */
    public enum Window {
        TODAY,      // 今日 00:00 至今 vs 昨日同时段
        HOUR_1      // 近 1 小时 vs 昨日同一小时
    }

    /** 最近一次评估状态 */
    public enum State {
        OK,
        FIRING
    }

    @Id
    @Column(length = 32)
    public String id;

    @Column(name = "game_id", nullable = false, length = 32)
    public String gameId;

    @Column(nullable = false, length = 100)
    public String name;

    @Column(name = "metric_type", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    public MetricType metricType;

    @Column(name = "condition_type", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    public ConditionType conditionType;

    @Column(nullable = false, length = 10)
    @Enumerated(EnumType.STRING)
    public Comparison comparison;

    @Column(columnDefinition = "DECIMAL(20,4)")
    @JdbcTypeCode(SqlTypes.NUMERIC)
    public Double threshold;        // ABSOLUTE 必填

    @Column(name = "deviation_pct", columnDefinition = "DECIMAL(10,4)")
    @JdbcTypeCode(SqlTypes.NUMERIC)
    public Double deviationPct;     // BASELINE_DEVIATION 必填（正数）

    @Column(length = 32)
    public String environment;      // NULL = 全部环境

    @Column(name = "eval_window", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    public Window window = Window.TODAY;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    public SystemAlertEntity.Severity severity = SystemAlertEntity.Severity.WARNING;

    @Column(nullable = false)
    public Boolean enabled = true;

    @Column(name = "notify_webhook", nullable = false)
    public Boolean notifyWebhook = true;

    @Column(name = "snooze_minutes")
    public Integer snoozeMinutes;   // 预留：静默期，v1 不做 UI

    // 最近一次评估结果（前端展示 + 恢复判定）
    @Column(name = "last_evaluated_at")
    public LocalDateTime lastEvaluatedAt;

    @Column(name = "last_value", columnDefinition = "DECIMAL(20,4)")
    @JdbcTypeCode(SqlTypes.NUMERIC)
    public Double lastValue;

    @Column(name = "last_baseline", columnDefinition = "DECIMAL(20,4)")
    @JdbcTypeCode(SqlTypes.NUMERIC)
    public Double lastBaseline;

    @Column(name = "last_state", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    public State lastState = State.OK;

    @Column(name = "created_by", length = 64)
    public String createdBy;

    @Column(name = "updated_by", length = 64)
    public String updatedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    public LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    public LocalDateTime deletedAt;     // 软删除

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }
}
