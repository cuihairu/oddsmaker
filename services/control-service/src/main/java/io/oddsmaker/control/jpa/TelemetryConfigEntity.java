package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime;

/**
 * 遥测配置实体
 * 管理事件交付和遥测配置
 */
@Entity
@Table(name = "telemetry_configs")
public class TelemetryConfigEntity {

    /**
     * 配置类型
     */
    public enum ConfigType {
        EVENT_DELIVERY,  // 事件交付
        BATCH,           // 批量处理
        COMPRESSION,     // 压缩
        ENCRYPTION,      // 加密
        RETRY,           // 重试
        OFFLINE,         // 离线模式
        MONITORING,      // 监控
        CUSTOM           // 自定义
    }

    /**
     * 配置状态
     */
    public enum ConfigStatus {
        DRAFT,           // 草稿
        ACTIVE,          // 活跃
        INACTIVE,        // 非活跃
        ARCHIVED         // 已归档
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public String id;

    @Column(name = "game_id")
    public String gameId;  // 游戏ID（为空表示全局配置）

    @Column(name = "environment_id")
    public String environmentId;  // 环境ID

    @Column(name = "config_name", nullable = false, length = 100)
    public String configName;  // 配置名称

    @Column(name = "config_type", nullable = false)
    @Enumerated(EnumType.STRING)
    public ConfigType configType;  // 配置类型

    @Column(name = "config_status", nullable = false)
    @Enumerated(EnumType.STRING)
    public ConfigStatus configStatus = ConfigStatus.DRAFT;  // 配置状态

    @Column(name = "description", length = 500)
    public String description;  // 描述

    @Column(name = "is_default", columnDefinition = "BOOLEAN")
    public Boolean isDefault = false;  // 是否为默认配置

    @Column(name = "priority", columnDefinition = "INTEGER")
    public Integer priority = 100;  // 优先级（数字越小优先级越高）

    // 事件交付配置
    @Column(name = "delivery_mode", length = 30)
    public String deliveryMode;  // 交付模式：REALTIME, BATCH, HYBRID

    @Column(name = "batch_size", columnDefinition = "INTEGER")
    public Integer batchSize = 500;  // 批量大小

    @Column(name = "batch_interval_ms", columnDefinition = "INTEGER")
    public Integer batchIntervalMs = 3000;  // 批量间隔（毫秒）

    @Column(name = "max_queue_size", columnDefinition = "INTEGER")
    public Integer maxQueueSize = 10000;  // 最大队列大小

    @Column(name = "flush_on_background", columnDefinition = "BOOLEAN")
    public Boolean flushOnBackground = true;  // 后台时刷新

    @Column(name = "flush_on_app_close", columnDefinition = "BOOLEAN")
    public Boolean flushOnAppClose = true;  // 应用关闭时刷新

    // 压缩配置
    @Column(name = "enable_compression", columnDefinition = "BOOLEAN")
    public Boolean enableCompression = true;  // 启用压缩

    @Column(name = "compression_algorithm", length = 30)
    public String compressionAlgorithm = "GZIP";  // 压缩算法

    @Column(name = "compression_level", columnDefinition = "INTEGER")
    public Integer compressionLevel = 6;  // 压缩级别

    @Column(name = "compression_threshold_bytes", columnDefinition = "INTEGER")
    public Integer compressionThresholdBytes = 1024;  // 压缩阈值

    // 加密配置
    @Column(name = "enable_encryption", columnDefinition = "BOOLEAN")
    public Boolean enableEncryption = false;  // 启用加密

    @Column(name = "encryption_algorithm", length = 50)
    public String encryptionAlgorithm;  // 加密算法

    @Column(name = "encryption_key_id", length = 100)
    public String encryptionKeyId;  // 加密密钥ID

    // 重试配置
    @Column(name = "max_retries", columnDefinition = "INTEGER")
    public Integer maxRetries = 3;  // 最大重试次数

    @Column(name = "retry_interval_ms", columnDefinition = "INTEGER")
    public Integer retryIntervalMs = 1000;  // 重试间隔（毫秒）

    @Column(name = "retry_backoff_multiplier", columnDefinition = "DECIMAL(5,2)")
    @JdbcTypeCode(SqlTypes.NUMERIC)
    public Double retryBackoffMultiplier = 2.0;  // 重试退避乘数

    @Column(name = "max_retry_interval_ms", columnDefinition = "INTEGER")
    public Integer maxRetryIntervalMs = 30000;  // 最大重试间隔

    @Column(name = "retry_on_status_codes", length = 100)
    public String retryOnStatusCodes;  // 重试的状态码列表

    // 离线配置
    @Column(name = "enable_offline_storage", columnDefinition = "BOOLEAN")
    public Boolean enableOfflineStorage = true;  // 启用离线存储

    @Column(name = "offline_storage_max_mb", columnDefinition = "INTEGER")
    public Integer offlineStorageMaxMb = 50;  // 离线存储最大大小（MB）

    @Column(name = "offline_storage_ttl_hours", columnDefinition = "INTEGER")
    public Integer offlineStorageTtlHours = 72;  // 离线存储TTL（小时）

    @Column(name = "offline_batch_size", columnDefinition = "INTEGER")
    public Integer offlineBatchSize = 100;  // 离线批量大小

    // 监控配置
    @Column(name = "enable_telemetry", columnDefinition = "BOOLEAN")
    public Boolean enableTelemetry = true;  // 启用SDK遥测

    @Column(name = "telemetry_interval_ms", columnDefinition = "INTEGER")
    public Integer telemetryIntervalMs = 60000;  // 遥测间隔（毫秒）

    @Column(name = "report_errors", columnDefinition = "BOOLEAN")
    public Boolean reportErrors = true;  // 报告错误

    @Column(name = "report_performance", columnDefinition = "BOOLEAN")
    public Boolean reportPerformance = true;  // 报告性能指标

    @Column(name = "sample_rate", columnDefinition = "DECIMAL(5,4)")
    @JdbcTypeCode(SqlTypes.NUMERIC)
    public Double sampleRate = 1.0;  // 采样率

    // 超时配置
    @Column(name = "connection_timeout_ms", columnDefinition = "INTEGER")
    public Integer connectionTimeoutMs = 10000;  // 连接超时

    @Column(name = "read_timeout_ms", columnDefinition = "INTEGER")
    public Integer readTimeoutMs = 30000;  // 读取超时

    @Column(name = "write_timeout_ms", columnDefinition = "INTEGER")
    public Integer writeTimeoutMs = 30000;  // 写入超时

    // 自定义配置
    @Column(name = "custom_config", columnDefinition = "TEXT")
    public String customConfig;  // JSON格式的自定义配置

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
        return configStatus == ConfigStatus.ACTIVE;
    }

    public boolean isDraft() {
        return configStatus == ConfigStatus.DRAFT;
    }

    public boolean isInactive() {
        return configStatus == ConfigStatus.INACTIVE;
    }

    public boolean isArchived() {
        return configStatus == ConfigStatus.ARCHIVED;
    }

    public boolean isDefault() {
        return isDefault != null && isDefault;
    }

    public boolean isGlobal() {
        return gameId == null;
    }

    public boolean isGameSpecific() {
        return gameId != null;
    }

    public void activate() {
        this.configStatus = ConfigStatus.ACTIVE;
    }

    public void deactivate() {
        this.configStatus = ConfigStatus.INACTIVE;
    }

    public void archive() {
        this.configStatus = ConfigStatus.ARCHIVED;
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (configStatus == null) {
            configStatus = ConfigStatus.DRAFT;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
