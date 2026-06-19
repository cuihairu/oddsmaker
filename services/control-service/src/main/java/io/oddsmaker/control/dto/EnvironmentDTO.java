package io.oddsmaker.control.dto;

import io.oddsmaker.control.jpa.GameEnvironmentEntity;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 游戏环境数据传输对象。
 */
public class EnvironmentDTO {

    public String id;

    public String gameId;

    @NotBlank(message = "环境名称不能为空")
    @Size(max = 50, message = "环境名称不能超过50个字符")
    public String name;

    @Size(max = 100, message = "显示名称不能超过100个字符")
    public String displayName;

    @Size(max = 500, message = "描述不能超过500个字符")
    public String description;

    public GameEnvironmentEntity.EnvironmentType type;
    public GameEnvironmentEntity.EnvironmentStatus status;

    public String storageProfileId;
    public String apiEndpoint;
    public String dataNamespace;
    public String kafkaTopicPrefix;
    public Integer dataRetentionDays;
    public Long maxEventsPerDay;

    public Boolean enableDebugMode;
    public Boolean enableSampling;

    @DecimalMin(value = "0.0", message = "采样率不能小于0")
    @DecimalMax(value = "1.0", message = "采样率不能大于1")
    public Double sampleRate;

    public Boolean enableRealTime;
    public Boolean requireHttps;
    public String allowedOrigins;
    public String ipWhitelist;
    public Boolean enableAlerts;
    public String alertEmail;

    @DecimalMin(value = "0.0", message = "错误阈值不能小于0")
    @DecimalMax(value = "1.0", message = "错误阈值不能大于1")
    public Double errorThreshold;

    public String schemaVersion;
    public String configVersion;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;

    public EnvironmentDTO() {}

    public EnvironmentDTO(GameEnvironmentEntity entity) {
        this.id = entity.id;
        this.gameId = entity.gameId;
        this.name = entity.name;
        this.displayName = entity.displayName;
        this.description = entity.description;
        this.type = entity.type;
        this.status = entity.status;
        this.storageProfileId = entity.storageProfileId;
        this.apiEndpoint = entity.apiEndpoint;
        this.dataNamespace = entity.dataNamespace;
        this.kafkaTopicPrefix = entity.kafkaTopicPrefix;
        this.dataRetentionDays = entity.dataRetentionDays;
        this.maxEventsPerDay = entity.maxEventsPerDay;
        this.enableDebugMode = entity.enableDebugMode;
        this.enableSampling = entity.enableSampling;
        this.sampleRate = entity.sampleRate;
        this.enableRealTime = entity.enableRealTime;
        this.requireHttps = entity.requireHttps;
        this.allowedOrigins = entity.allowedOrigins;
        this.ipWhitelist = entity.ipWhitelist;
        this.enableAlerts = entity.enableAlerts;
        this.alertEmail = entity.alertEmail;
        this.errorThreshold = entity.errorThreshold;
        this.schemaVersion = entity.schemaVersion;
        this.configVersion = entity.configVersion;
        this.createdAt = entity.createdAt;
        this.updatedAt = entity.updatedAt;
    }

    public GameEnvironmentEntity toEntity() {
        GameEnvironmentEntity entity = new GameEnvironmentEntity();
        entity.id = this.id;
        entity.gameId = this.gameId;
        entity.name = this.name;
        entity.displayName = this.displayName;
        entity.description = this.description;
        entity.type = this.type != null ? this.type : inferType(this.name);
        entity.status = this.status != null ? this.status : GameEnvironmentEntity.EnvironmentStatus.ACTIVE;
        entity.storageProfileId = this.storageProfileId;
        entity.apiEndpoint = this.apiEndpoint;
        entity.dataNamespace = this.dataNamespace;
        entity.kafkaTopicPrefix = this.kafkaTopicPrefix;
        entity.dataRetentionDays = this.dataRetentionDays;
        entity.maxEventsPerDay = this.maxEventsPerDay;
        entity.enableDebugMode = this.enableDebugMode != null ? this.enableDebugMode : false;
        entity.enableSampling = this.enableSampling != null ? this.enableSampling : true;
        entity.sampleRate = this.sampleRate != null ? this.sampleRate : 1.0;
        entity.enableRealTime = this.enableRealTime != null ? this.enableRealTime : true;
        entity.requireHttps = this.requireHttps != null ? this.requireHttps : true;
        entity.allowedOrigins = this.allowedOrigins;
        entity.ipWhitelist = this.ipWhitelist;
        entity.enableAlerts = this.enableAlerts != null ? this.enableAlerts : true;
        entity.alertEmail = this.alertEmail;
        entity.errorThreshold = this.errorThreshold != null ? this.errorThreshold : 0.05;
        entity.schemaVersion = this.schemaVersion;
        entity.configVersion = this.configVersion;
        return entity;
    }

    public void updateEntity(GameEnvironmentEntity entity) {
        if (this.displayName != null) entity.displayName = this.displayName;
        if (this.description != null) entity.description = this.description;
        if (this.type != null) entity.type = this.type;
        if (this.status != null) entity.status = this.status;
        if (this.storageProfileId != null) entity.storageProfileId = this.storageProfileId;
        if (this.apiEndpoint != null) entity.apiEndpoint = this.apiEndpoint;
        if (this.dataNamespace != null) entity.dataNamespace = this.dataNamespace;
        if (this.kafkaTopicPrefix != null) entity.kafkaTopicPrefix = this.kafkaTopicPrefix;
        if (this.dataRetentionDays != null) entity.dataRetentionDays = this.dataRetentionDays;
        if (this.maxEventsPerDay != null) entity.maxEventsPerDay = this.maxEventsPerDay;
        if (this.enableDebugMode != null) entity.enableDebugMode = this.enableDebugMode;
        if (this.enableSampling != null) entity.enableSampling = this.enableSampling;
        if (this.sampleRate != null) entity.sampleRate = this.sampleRate;
        if (this.enableRealTime != null) entity.enableRealTime = this.enableRealTime;
        if (this.requireHttps != null) entity.requireHttps = this.requireHttps;
        if (this.allowedOrigins != null) entity.allowedOrigins = this.allowedOrigins;
        if (this.ipWhitelist != null) entity.ipWhitelist = this.ipWhitelist;
        if (this.enableAlerts != null) entity.enableAlerts = this.enableAlerts;
        if (this.alertEmail != null) entity.alertEmail = this.alertEmail;
        if (this.errorThreshold != null) entity.errorThreshold = this.errorThreshold;
        if (this.schemaVersion != null) entity.schemaVersion = this.schemaVersion;
        if (this.configVersion != null) entity.configVersion = this.configVersion;
    }

    private static GameEnvironmentEntity.EnvironmentType inferType(String name) {
        if (name == null) {
            return GameEnvironmentEntity.EnvironmentType.DEVELOPMENT;
        }
        return switch (name.trim().toLowerCase()) {
            case "prod", "production" -> GameEnvironmentEntity.EnvironmentType.PRODUCTION;
            case "staging", "stage", "pre" -> GameEnvironmentEntity.EnvironmentType.STAGING;
            case "qa", "test", "testing" -> GameEnvironmentEntity.EnvironmentType.TESTING;
            case "loadtest" -> GameEnvironmentEntity.EnvironmentType.LOADTEST;
            default -> GameEnvironmentEntity.EnvironmentType.DEVELOPMENT;
        };
    }
}
