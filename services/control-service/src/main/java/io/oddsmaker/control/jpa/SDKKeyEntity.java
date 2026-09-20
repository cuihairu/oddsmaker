package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * SDK密钥实体
 * 管理SDK特定的密钥和配置
 */
@Entity
@Table(name = "sdk_keys")
public class SDKKeyEntity {

    /**
     * SDK平台
     */
    public enum SDKPlatform {
        WEB,           // Web/JavaScript
        ANDROID,       // Android
        IOS,           // iOS
        UNITY,         // Unity
        UNREAL,        // Unreal Engine
        REACT_NATIVE,  // React Native
        FLUTTER,       // Flutter
        Cocos2d,       // Cocos2d
        SERVER,        // 服务端SDK
        CUSTOM         // 自定义
    }

    /**
     * 密钥状态
     */
    public enum KeyStatus {
        ACTIVE,        // 活跃
        SUSPENDED,     // 暂停
        EXPIRED,       // 过期
        REVOKED        // 已撤销
    }

    /**
     * 事件交付模式
     */
    public enum DeliveryMode {
        REALTIME,      // 实时交付
        BATCH,         // 批量交付
        HYBRID         // 混合模式
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public String id;

    @Column(name = "game_id", nullable = false)
    public String gameId;  // 游戏ID

    @Column(name = "environment_id")
    public String environmentId;  // 环境ID

    @Column(name = "environment", length = 50)
    public String environment;  // 环境名称

    @Column(name = "key_name", nullable = false, length = 100)
    public String keyName;  // 密钥名称

    @Column(name = "public_key", nullable = false, length = 64)
    public String publicKey;  // 公钥（客户端使用）

    @Column(name = "secret_key_hash", length = 128)
    public String secretKeyHash;  // 密钥哈希（服务端使用）

    @Column(name = "platform", nullable = false)
    @Enumerated(EnumType.STRING)
    public SDKPlatform platform;  // SDK平台

    @Column(name = "key_status", nullable = false)
    @Enumerated(EnumType.STRING)
    public KeyStatus keyStatus = KeyStatus.ACTIVE;  // 密钥状态

    @Column(name = "delivery_mode", nullable = false)
    @Enumerated(EnumType.STRING)
    public DeliveryMode deliveryMode = DeliveryMode.REALTIME;  // 交付模式

    @Column(name = "sdk_version_constraint", length = 50)
    public String sdkVersionConstraint;  // SDK版本约束

    @Column(name = "min_sdk_version", length = 20)
    public String minSdkVersion;  // 最低SDK版本

    @Column(name = "max_sdk_version", length = 20)
    public String maxSdkVersion;  // 最高SDK版本

    @Column(name = "allowed_domains", columnDefinition = "TEXT")
    public String allowedDomains;  // JSON格式的允许域名列表

    @Column(name = "allowed_ips", columnDefinition = "TEXT")
    public String allowedIps;  // JSON格式的允许IP列表

    @Column(name = "rate_limit_rpm", columnDefinition = "INTEGER")
    public Integer rateLimitRpm = 1000;  // 每分钟请求数限制

    @Column(name = "rate_limit_rps", columnDefinition = "INTEGER")
    public Integer rateLimitRps = 100;  // 每秒请求数限制

    @Column(name = "batch_size_limit", columnDefinition = "INTEGER")
    public Integer batchSizeLimit = 500;  // 批量大小限制

    @Column(name = "batch_interval_ms", columnDefinition = "INTEGER")
    public Integer batchIntervalMs = 3000;  // 批量间隔（毫秒）

    @Column(name = "max_event_size_bytes", columnDefinition = "INTEGER")
    public Integer maxEventSizeBytes = 65536;  // 单事件最大大小

    @Column(name = "max_batch_size_bytes", columnDefinition = "INTEGER")
    public Integer maxBatchSizeBytes = 1048576;  // 批量最大大小

    @Column(name = "enable_compression", columnDefinition = "BOOLEAN")
    public Boolean enableCompression = true;  // 启用压缩

    @Column(name = "enable_encryption", columnDefinition = "BOOLEAN")
    public Boolean enableEncryption = false;  // 启用加密

    @Column(name = "retry_policy", columnDefinition = "TEXT")
    public String retryPolicy;  // JSON格式的重试策略

    @Column(name = "offline_config", columnDefinition = "TEXT")
    public String offlineConfig;  // JSON格式的离线配置

    @Column(name = "flush_config", columnDefinition = "TEXT")
    public String flushConfig;  // JSON格式的刷新配置

    @Column(name = "telemetry_config", columnDefinition = "TEXT")
    public String telemetryConfig;  // JSON格式的遥测配置

    @Column(name = "custom_config", columnDefinition = "TEXT")
    public String customConfig;  // JSON格式的自定义配置

    @Column(name = "total_events_sent", columnDefinition = "BIGINT")
    public Long totalEventsSent = 0L;  // 总发送事件数

    @Column(name = "total_batches_sent", columnDefinition = "BIGINT")
    public Long totalBatchesSent = 0L;  // 总发送批次数

    @Column(name = "total_errors", columnDefinition = "BIGINT")
    public Long totalErrors = 0L;  // 总错误数

    @Column(name = "last_event_at")
    public LocalDateTime lastEventAt;  // 最后事件时间

    @Column(name = "last_error_at")
    public LocalDateTime lastErrorAt;  // 最后错误时间

    @Column(name = "last_error_message", columnDefinition = "TEXT")
    public String lastErrorMessage;  // 最后错误消息

    @Column(name = "expires_at")
    public LocalDateTime expiresAt;  // 过期时间

    @Column(name = "created_by", nullable = false, length = 64)
    public String createdBy;  // 创建人

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @Column(name = "updated_at")
    public LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    public LocalDateTime deletedAt;

    // 辅助方法

    public boolean isActive() {
        return keyStatus == KeyStatus.ACTIVE &&
               (expiresAt == null || expiresAt.isAfter(LocalDateTime.now()));
    }

    public boolean isExpired() {
        return keyStatus == KeyStatus.EXPIRED ||
               (expiresAt != null && expiresAt.isBefore(LocalDateTime.now()));
    }

    public boolean isSuspended() {
        return keyStatus == KeyStatus.SUSPENDED;
    }

    public boolean isRevoked() {
        return keyStatus == KeyStatus.REVOKED;
    }

    public boolean isRealtime() {
        return deliveryMode == DeliveryMode.REALTIME;
    }

    public boolean isBatch() {
        return deliveryMode == DeliveryMode.BATCH;
    }

    public boolean isHybrid() {
        return deliveryMode == DeliveryMode.HYBRID;
    }

    public void suspend() {
        this.keyStatus = KeyStatus.SUSPENDED;
    }

    public void activate() {
        this.keyStatus = KeyStatus.ACTIVE;
    }

    public void revoke() {
        this.keyStatus = KeyStatus.REVOKED;
    }

    public void expire() {
        this.keyStatus = KeyStatus.EXPIRED;
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (keyStatus == null) {
            keyStatus = KeyStatus.ACTIVE;
        }
        if (deliveryMode == null) {
            deliveryMode = DeliveryMode.REALTIME;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
