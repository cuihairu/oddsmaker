package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 广告分析实体
 * 记录广告收入和效果数据
 */
@Entity
@Table(name = "ad_analysis")
public class AdAnalysisEntity {

    /**
     * 广告网络
     */
    public enum AdNetwork {
        ADMOB,          // Google AdMob
        UNITY_ADS,      // Unity Ads
        APPLOVIN,       // AppLovin
        MOPUB,          // MoPub
        IRON_SOURCE,    // IronSource
        CHARTBOOST,     // Chartboost
        VUNGLE,         // Vungle
        CUSTOM          // 自定义
    }

    /**
     * 广告类型
     */
    public enum AdFormat {
        BANNER,         // 横幅广告
        INTERSTITIAL,   // 插屏广告
        REWARDED,       // 激励视频
        NATIVE,         // 原生广告
        PLAYABLE        // 试玩广告
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

    @Column(name = "ad_network")
    @Enumerated(EnumType.STRING)
    public AdNetwork adNetwork;

    @Column(name = "ad_format")
    @Enumerated(EnumType.STRING)
    public AdFormat adFormat;

    @Column(name = "ad_placement", length = 100)
    public String adPlacement;

    // 展示指标
    @Column(name = "impressions", columnDefinition = "BIGINT DEFAULT 0")
    public Long impressions = 0L;

    @Column(name = "clicks", columnDefinition = "BIGINT DEFAULT 0")
    public Long clicks = 0L;

    @Column(name = "rewards", columnDefinition = "BIGINT DEFAULT 0")
    public Long rewards = 0L;

    // 收入指标
    @Column(name = "revenue", columnDefinition = "DECIMAL(18,4) DEFAULT 0")
    public Double revenue = 0.0;

    @Column(name = "ecpm", columnDefinition = "DECIMAL(18,4) DEFAULT 0")
    public Double ecpm = 0.0;

    // 填充率
    @Column(name = "requests", columnDefinition = "BIGINT DEFAULT 0")
    public Long requests = 0L;

    @Column(name = "fills", columnDefinition = "BIGINT DEFAULT 0")
    public Long fills = 0L;

    @Column(name = "fill_rate", columnDefinition = "DECIMAL(5,4) DEFAULT 0")
    public Double fillRate = 0.0;

    // 点击率
    @Column(name = "ctr", columnDefinition = "DECIMAL(5,4) DEFAULT 0")
    public Double ctr = 0.0;

    // 用户指标
    @Column(name = "unique_users", columnDefinition = "BIGINT DEFAULT 0")
    public Long uniqueUsers = 0L;

    @Column(name = "platform", length = 30)
    public String platform;

    @Column(name = "country", length = 10)
    public String country;

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
