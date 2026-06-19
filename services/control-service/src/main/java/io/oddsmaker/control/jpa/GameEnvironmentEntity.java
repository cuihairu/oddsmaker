package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 游戏逻辑环境。
 * Environment 只表达发布阶段；物理落盘与路由由 storage profile 决定。
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
    public String name; // dev, qa, staging, prod, loadtest

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

    @Column(name = "storage_profile_id", length = 64)
    public String storageProfileId;

    // 环境配置
    @Column(name = "api_endpoint", length = 500)
    public String apiEndpoint; // 专用API端点

    @Column(name = "data_namespace", length = 100)
    public String dataNamespace; // 逻辑数据命名空间，物理后端由 storage profile 决定

    @Column(name = "kafka_topic_prefix", length = 50)
    public String kafkaTopicPrefix; // Kafka主题前缀

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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "storage_profile_id", insertable = false, updatable = false)
    public StorageProfileEntity storageProfile;

    @OneToMany(mappedBy = "environment", fetch = FetchType.LAZY)
    public List<ApiKeyEntity> apiKeys;

    public enum EnvironmentType {
        DEVELOPMENT, // 开发环境
        TESTING,     // 测试环境
        STAGING,     // 预发环境
        PRODUCTION,  // 生产环境
        LOADTEST     // 压测环境
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

    public boolean isNonProduction() {
        return !isProduction();
    }

    public boolean isActive() {
        return status == EnvironmentStatus.ACTIVE && deletedAt == null;
    }

    public String getFullName() {
        return String.format("%s-%s", game != null ? game.name : "unknown", name);
    }

    public String getDataPartition() {
        return dataNamespace != null ? dataNamespace : String.format("%s_%s", gameId, name);
    }

    public boolean shouldSample() {
        return enableSampling && sampleRate != null && sampleRate < 1.0;
    }

    public boolean usesDedicatedStorage() {
        return storageProfile != null && storageProfile.isDedicated();
    }
}
