package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 收入分析实体
 * 记录每日收入聚合数据
 */
@Entity
@Table(name = "revenue_analysis")
public class RevenueAnalysisEntity {

    /**
     * 收入类型
     */
    public enum RevenueType {
        IAP,            // 应用内购买
        AD,             // 广告收入
        SUBSCRIPTION,   // 订阅
        VIRTUAL,        // 虚拟货币购买
        BUNDLE          // 捆绑销售
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public String id;

    @Column(name = "game_id", nullable = false)
    public String gameId;

    @Column(name = "environment", length = 50)
    public String environment;

    @Column(name = "analysis_date", nullable = false)
    public LocalDate analysisDate;

    @Column(name = "revenue_type")
    @Enumerated(EnumType.STRING)
    public RevenueType revenueType;

    // 收入指标
    @Column(name = "total_revenue", columnDefinition = "DECIMAL(18,4) DEFAULT 0")
    public Double totalRevenue = 0.0;

    @Column(name = "iap_revenue", columnDefinition = "DECIMAL(18,4) DEFAULT 0")
    public Double iapRevenue = 0.0;

    @Column(name = "ad_revenue", columnDefinition = "DECIMAL(18,4) DEFAULT 0")
    public Double adRevenue = 0.0;

    @Column(name = "subscription_revenue", columnDefinition = "DECIMAL(18,4) DEFAULT 0")
    public Double subscriptionRevenue = 0.0;

    // 用户指标
    @Column(name = "total_users", columnDefinition = "BIGINT DEFAULT 0")
    public Long totalUsers = 0L;

    @Column(name = "paying_users", columnDefinition = "BIGINT DEFAULT 0")
    public Long payingUsers = 0L;

    @Column(name = "new_paying_users", columnDefinition = "BIGINT DEFAULT 0")
    public Long newPayingUsers = 0L;

    // ARPU/ARPPU
    @Column(name = "arpu", columnDefinition = "DECIMAL(18,4) DEFAULT 0")
    public Double arpu = 0.0;

    @Column(name = "arppu", columnDefinition = "DECIMAL(18,4) DEFAULT 0")
    public Double arppu = 0.0;

    // 交易指标
    @Column(name = "total_transactions", columnDefinition = "BIGINT DEFAULT 0")
    public Long totalTransactions = 0L;

    @Column(name = "avg_transaction_value", columnDefinition = "DECIMAL(18,4) DEFAULT 0")
    public Double avgTransactionValue = 0.0;

    // 维度
    @Column(name = "platform", length = 30)
    public String platform;

    @Column(name = "country", length = 10)
    public String country;

    @Column(name = "currency", length = 10)
    public String currency;

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
