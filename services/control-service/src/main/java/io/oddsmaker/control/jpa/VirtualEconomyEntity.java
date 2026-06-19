package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 虚拟经济监控配置
 * 定义source/sink和通胀监控的配置
 */
@Entity
@Table(name = "virtual_economies")
public class VirtualEconomyEntity {

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

    // 货币配置
    @Column(name = "currency_id", nullable = false, length = 100)
    public String currencyId;  // 货币ID

    @Column(name = "currency_name", length = 200)
    public String currencyName;  // 货币名称

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public CurrencyType currencyType = CurrencyType.PREMIUM;  // 货币类型

    // Source/Sink追踪
    @Column(name = "enable_source_tracking")
    public Boolean enableSourceTracking = true;  // 启用来源追踪

    @Column(name = "enable_sink_tracking")
    public Boolean enableSinkTracking = true;  // 启用去向追踪

    @Column(name = "source_events", columnDefinition = "TEXT")
    public String sourceEvents;  // JSON：来源事件列表

    @Column(name = "sink_events", columnDefinition = "TEXT")
    public String sinkEvents;  // JSON：去向事件列表

    // 通胀监控
    @Column(name = "enable_inflation_monitoring")
    public Boolean enableInflationMonitoring = true;  // 启用通胀监控

    @Column(name = "inflation_calc_method", length = 50)
    public String inflationCalcMethod = "circulating_supply";  // 通胀计算方法

    @Column(name = "inflation_threshold")
    public Double inflationThreshold = 0.05;  // 通胀阈值（5%）

    // 流量分析
    @Column(name = "enable_flow_analysis")
    public Boolean enableFlowAnalysis = true;  // 启用流量分析

    @Column(name = "flow_balance_threshold")
    public Double flowBalanceThreshold = 0.8;  // 流量平衡阈值

    // 告警
    @Column(name = "enable_alerts")
    public Boolean enableAlerts = true;  // 启用告警

    @Column(name = "alert_threshold_low")
    public Double alertThresholdLow = 0.2;  // 低阈值

    @Column(name = "alert_threshold_high")
    public Double alertThresholdHigh = 0.8;  // 高阈值

    // 分组
    @Column(name = "group_by_dimensions", columnDefinition = "TEXT")
    public String groupByDimensions;  // JSON：分组维度

    // 时间聚合
    @Column(name = "time_granularity", length = 20)
    public String timeGranularity = "daily";  // 时间粒度

    // 状态
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public EconomyStatus status = EconomyStatus.ACTIVE;

    @Column(name = "enable_auto_calc")
    public Boolean enableAutoCalc = true;

    @Column(name = "calc_frequency")
    public String calcFrequency = "daily";

    @Column(name = "last_calculated_at")
    public LocalDateTime lastCalculatedAt;

    @Column(name = "result_table", length = 100)
    public String resultTable;

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

    public enum CurrencyType {
        PREMIUM,       // 高级货币（付费）
        SOFT,          // 软货币（免费获得）
        HARD,          // 硬货币（付费获得）
        SOCIAL         // 社交货币
    }

    public enum EconomyStatus {
        ACTIVE,        // 活跃
        PAUSED,        // 暂停
        ARCHIVED       // 已归档
    }

    // 业务方法
    public boolean isActive() {
        return status == EconomyStatus.ACTIVE && deletedAt == null;
    }

    public boolean isAutoCalcEnabled() {
        return Boolean.TRUE.equals(enableAutoCalc);
    }

    public boolean tracksInflation() {
        return Boolean.TRUE.equals(enableInflationMonitoring);
    }

    public boolean hasFlowAnalysis() {
        return Boolean.TRUE.equals(enableFlowAnalysis);
    }

    public boolean isPremiumCurrency() {
        return currencyType == CurrencyType.PREMIUM || currencyType == CurrencyType.HARD;
    }

    public boolean needsAlert(Double currentValue) {
        if (!Boolean.TRUE.equals(enableAlerts) || currentValue == null) return false;

        return currentValue <= (alertThresholdLow != null ? alertThresholdLow : 0.2) ||
               currentValue >= (alertThresholdHigh != null ? alertThresholdHigh : 0.8);
    }
}
