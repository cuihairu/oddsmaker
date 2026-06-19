package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 收入聚合配置
 * 定义IAP和广告收入的聚合规则
 */
@Entity
@Table(name = "revenue_aggregations")
public class RevenueAggregationEntity {

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

    // 收入类型
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public RevenueType revenueType = RevenueType.IAP;  // IAP或广告

    // 聚合维度
    @Column(name = "group_by_dimensions", columnDefinition = "TEXT")
    public String groupByDimensions;  // JSON：["date", "platform", "country"]

    @Column(name = "include_refunds")
    public Boolean includeRefunds = true;  // 包含退款

    @Column(name = "include_chargebacks")
    public Boolean includeChargebacks = true;  // 包含拒付

    // 货币处理
    @Column(name = "base_currency", length = 10)
    public String baseCurrency = "USD";  // 基础货币

    @Column(name = "exchange_rate_source", length = 50)
    public String exchangeRateSource = "daily";  // 汇率来源

    // 时间聚合
    @Column(name = "time_granularity", length = 20)
    public String timeGranularity = "daily";  // 时间粒度：hourly, daily, weekly, monthly

    @Column(name = "enable_realtime")
    public Boolean enableRealtime = false;  // 实时聚合

    // LTV计算
    @Column(name = "enable_ltv")
    public Boolean enableLtv = true;  // 启用LTV计算

    @Column(name = "ltv_time_windows", columnDefinition = "TEXT")
    public String ltvTimeWindows;  // JSON：[7, 30, 90, 180, 365] 天

    // 预测
    @Column(name = "enable_forecast")
    public Boolean enableForecast = false;  // 启用预测

    @Column(name = "forecast_days")
    public Integer forecastDays = 30;  // 预测天数

    // 状态
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public AggregationStatus status = AggregationStatus.ACTIVE;

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

    public enum RevenueType {
        IAP,           // 应用内购买
        AD,            // 广告收入
        SUBSCRIPTION,  // 订阅收入
        ALL            // 所有收入
    }

    public enum AggregationStatus {
        ACTIVE,        // 活跃
        PAUSED,        // 暂停
        ARCHIVED       // 已归档
    }

    // 业务方法
    public boolean isActive() {
        return status == AggregationStatus.ACTIVE && deletedAt == null;
    }

    public boolean isAutoCalcEnabled() {
        return Boolean.TRUE.equals(enableAutoCalc);
    }

    public boolean isRealtimeEnabled() {
        return Boolean.TRUE.equals(enableRealtime);
    }

    public boolean includesRefunds() {
        return Boolean.TRUE.equals(includeRefunds);
    }
}
