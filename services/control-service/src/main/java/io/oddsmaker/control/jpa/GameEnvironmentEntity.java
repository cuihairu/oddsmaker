package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 游戏环境实体 - 开发/测试/生产环境隔离
 * 每个游戏可以有多个环境，实现完整的开发生命周期管理
 */
@Entity
@Table(name = "game_environments")
public class GameEnvironmentEntity {

    @Id
    @Column(length = 32)
    public String id;

    @Column(name = "game_id", nullable = false, length = 32)
    public String gameId;

    @Column(nullable = false, length = 50)
    public String name; // development, testing, staging, production

    @Column(name = "display_name", length = 100)
    public String displayName;

    @Column(length = 500)
    public String description;

    /**
     * 环境类型: development, testing, staging, production
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public EnvironmentType type;

    /**
     * 环境状态: active, inactive, maintenance
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public EnvironmentStatus status = EnvironmentStatus.ACTIVE;

    // 环境配置
    @Column(name = "api_endpoint", length = 500)
    public String apiEndpoint; // 专用API端点

    @Column(name = "data_namespace", length = 100)
    public String dataNamespace; // 数据命名空间，用于ClickHouse分区

    @Column(name = "kafka_topic_prefix", length = 50)
    public String kafkaTopicPrefix; // Kafka主题前缀

    // 数据隔离配置
    @Column(name = "isolated_storage")
    public Boolean isolatedStorage = false; // 是否使用独立存储

    @Column(name = "data_retention_days")
    public Integer dataRetentionDays; // 继承自游戏配置，可覆盖

    @Column(name = "max_events_per_day")
    public Long maxEventsPerDay; // 每日事件限额

    // 功能开关
    @Column(name = "enable_debug_mode")
    public Boolean enableDebugMode = false;

    @Column(name = "enable_sampling")
    public Boolean enableSampling = true;

    @Column(name = "sample_rate")
    public Double sampleRate = 1.0; // 采样率

    @Column(name = "enable_real_time")
    public Boolean enableRealTime = true;

    // 安全配置
    @Column(name = "require_https")
    public Boolean requireHttps = true;

    @Column(name = "allowed_origins")
    public String allowedOrigins; // CORS允许的域名

    @Column(name = "ip_whitelist")
    public String ipWhitelist; // IP白名单

    // 监控和告警
    @Column(name = "enable_alerts")
    public Boolean enableAlerts = true;

    @Column(name = "alert_email")
    public String alertEmail;

    @Column(name = "error_threshold")
    public Double errorThreshold = 0.05; // 错误率阈值

    // 版本控制
    @Column(name = "schema_version", length = 20)
    public String schemaVersion;

    @Column(name = "config_version", length = 20)
    public String configVersion;

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
    @JoinColumn(name = "game_id", insertable = false, updatable = false)
    public GameEntity game;

    @OneToMany(mappedBy = "environment", fetch = FetchType.LAZY)
    public List<ApiKeyEntity> apiKeys;

    public enum EnvironmentType {
        DEVELOPMENT, // 开发环境
        TESTING,     // 测试环境
        STAGING,     // 预发环境
        PRODUCTION   // 生产环境
    }

    public enum EnvironmentStatus {
        ACTIVE,      // 活跃
        INACTIVE,    // 非活跃
        MAINTENANCE  // 维护中
    }

    // 业务方法
    public boolean isProduction() {
        return type == EnvironmentType.PRODUCTION;
    }

    public boolean isDevelopment() {
        return type == EnvironmentType.DEVELOPMENT;
    }

    public boolean isActive() {
        return status == EnvironmentStatus.ACTIVE && deletedAt == null;
    }

    public String getFullName() {
        return String.format("%s-%s", game != null ? game.name : "unknown", name);
    }

    public String getDataPartition() {
        // 生成ClickHouse分区标识
        return String.format("%s_%s_%s",
            gameId,
            name,
            dataNamespace != null ? dataNamespace : name);
    }

    public boolean shouldSample() {
        return enableSampling && sampleRate != null && sampleRate < 1.0;
    }
}
