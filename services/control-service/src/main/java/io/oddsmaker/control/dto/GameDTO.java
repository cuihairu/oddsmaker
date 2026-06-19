package io.oddsmaker.control.dto;

import io.oddsmaker.control.jpa.GameEntity;
import jakarta.validation.constraints.*;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 游戏数据传输对象
 */
public class GameDTO {

    public String id;

    @NotBlank(message = "游戏名称不能为空")
    @Size(max = 100, message = "游戏名称不能超过100个字符")
    public String name;

    @Size(max = 200, message = "显示名称不能超过200个字符")
    public String displayName;

    @Size(max = 1000, message = "描述不能超过1000个字符")
    public String description;

    public GameEntity.GameGenre genre;
    public Set<GameEntity.GamePlatform> platforms;
    public GameEntity.GameStatus status;

    // 版本信息
    @Pattern(regexp = "^\\d+\\.\\d+\\.\\d+.*$", message = "版本格式应为 x.y.z")
    public String currentVersion;
    public String minSupportedVersion;
    public LocalDateTime releaseDate;

    // 应用商店链接
    @Pattern(regexp = "^https?://.*", message = "应用商店链接必须是有效的URL")
    public String appStoreUrl;
    public String googlePlayUrl;
    public String steamUrl;

    // 游戏专业配置
    @Pattern(regexp = "^[A-Z]{3}$", message = "货币代码必须是3位大写字母")
    public String defaultCurrency;
    public String virtualCurrencies; // JSON数组字符串

    @Min(value = 1, message = "最大等级至少为1")
    public Integer maxLevel;

    public Boolean hasMultiplayer;
    public Boolean hasGuilds;
    public Boolean hasPvp;

    // 数据配置
    @Min(value = 30, message = "数据保留天数至少为30天")
    public Integer dataRetentionDays;

    public Boolean enableRealTimeAnalytics;
    public Boolean enableCrashReporting;

    @DecimalMin(value = "0.0", message = "采样率不能小于0")
    @DecimalMax(value = "1.0", message = "采样率不能大于1")
    public Double sampleRate;

    // 隐私合规
    public Boolean piiDetectionEnabled;
    public Boolean gdprCompliance;
    public Boolean coppaCompliance;

    // 统计信息（只读）
    public Integer totalEnvironments;
    public Integer totalApiKeys;
    public Long totalEventsProcessed;

    // 时间戳
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;

    // 构造函数
    public GameDTO() {}

    public GameDTO(GameEntity entity) {
        this.id = entity.id;
        this.name = entity.name;
        this.displayName = entity.displayName;
        this.description = entity.description;
        this.genre = entity.genre;
        this.platforms = entity.platforms;
        this.status = entity.status;
        this.currentVersion = entity.currentVersion;
        this.minSupportedVersion = entity.minSupportedVersion;
        this.releaseDate = entity.releaseDate;
        this.appStoreUrl = entity.appStoreUrl;
        this.googlePlayUrl = entity.googlePlayUrl;
        this.steamUrl = entity.steamUrl;
        this.defaultCurrency = entity.defaultCurrency;
        this.virtualCurrencies = entity.virtualCurrencies;
        this.maxLevel = entity.maxLevel;
        this.hasMultiplayer = entity.hasMultiplayer;
        this.hasGuilds = entity.hasGuilds;
        this.hasPvp = entity.hasPvp;
        this.dataRetentionDays = entity.dataRetentionDays;
        this.enableRealTimeAnalytics = entity.enableRealTimeAnalytics;
        this.enableCrashReporting = entity.enableCrashReporting;
        this.sampleRate = entity.sampleRate;
        this.piiDetectionEnabled = entity.piiDetectionEnabled;
        this.gdprCompliance = entity.gdprCompliance;
        this.coppaCompliance = entity.coppaCompliance;
        this.createdAt = entity.createdAt;
        this.updatedAt = entity.updatedAt;
    }

    /**
     * 转换为实体对象
     */
    public GameEntity toEntity() {
        GameEntity entity = new GameEntity();
        entity.id = this.id;
        entity.name = this.name;
        entity.displayName = this.displayName;
        entity.description = this.description;
        entity.genre = this.genre;
        entity.platforms = this.platforms;
        entity.status = this.status != null ? this.status : GameEntity.GameStatus.DEVELOPMENT;
        entity.currentVersion = this.currentVersion;
        entity.minSupportedVersion = this.minSupportedVersion;
        entity.releaseDate = this.releaseDate;
        entity.appStoreUrl = this.appStoreUrl;
        entity.googlePlayUrl = this.googlePlayUrl;
        entity.steamUrl = this.steamUrl;
        entity.defaultCurrency = this.defaultCurrency != null ? this.defaultCurrency : "USD";
        entity.virtualCurrencies = this.virtualCurrencies;
        entity.maxLevel = this.maxLevel;
        entity.hasMultiplayer = this.hasMultiplayer != null ? this.hasMultiplayer : false;
        entity.hasGuilds = this.hasGuilds != null ? this.hasGuilds : false;
        entity.hasPvp = this.hasPvp != null ? this.hasPvp : false;
        entity.dataRetentionDays = this.dataRetentionDays != null ? this.dataRetentionDays : 90;
        entity.enableRealTimeAnalytics = this.enableRealTimeAnalytics != null ? this.enableRealTimeAnalytics : true;
        entity.enableCrashReporting = this.enableCrashReporting != null ? this.enableCrashReporting : true;
        entity.sampleRate = this.sampleRate != null ? this.sampleRate : 1.0;
        entity.piiDetectionEnabled = this.piiDetectionEnabled != null ? this.piiDetectionEnabled : true;
        entity.gdprCompliance = this.gdprCompliance != null ? this.gdprCompliance : false;
        entity.coppaCompliance = this.coppaCompliance != null ? this.coppaCompliance : false;
        return entity;
    }

    /**
     * 更新实体对象
     */
    public void updateEntity(GameEntity entity) {
        if (this.name != null) entity.name = this.name;
        if (this.displayName != null) entity.displayName = this.displayName;
        if (this.description != null) entity.description = this.description;
        if (this.genre != null) entity.genre = this.genre;
        if (this.platforms != null) entity.platforms = this.platforms;
        if (this.status != null) entity.status = this.status;
        if (this.currentVersion != null) entity.currentVersion = this.currentVersion;
        if (this.minSupportedVersion != null) entity.minSupportedVersion = this.minSupportedVersion;
        if (this.releaseDate != null) entity.releaseDate = this.releaseDate;
        if (this.appStoreUrl != null) entity.appStoreUrl = this.appStoreUrl;
        if (this.googlePlayUrl != null) entity.googlePlayUrl = this.googlePlayUrl;
        if (this.steamUrl != null) entity.steamUrl = this.steamUrl;
        if (this.defaultCurrency != null) entity.defaultCurrency = this.defaultCurrency;
        if (this.virtualCurrencies != null) entity.virtualCurrencies = this.virtualCurrencies;
        if (this.maxLevel != null) entity.maxLevel = this.maxLevel;
        if (this.hasMultiplayer != null) entity.hasMultiplayer = this.hasMultiplayer;
        if (this.hasGuilds != null) entity.hasGuilds = this.hasGuilds;
        if (this.hasPvp != null) entity.hasPvp = this.hasPvp;
        if (this.dataRetentionDays != null) entity.dataRetentionDays = this.dataRetentionDays;
        if (this.enableRealTimeAnalytics != null) entity.enableRealTimeAnalytics = this.enableRealTimeAnalytics;
        if (this.enableCrashReporting != null) entity.enableCrashReporting = this.enableCrashReporting;
        if (this.sampleRate != null) entity.sampleRate = this.sampleRate;
        if (this.piiDetectionEnabled != null) entity.piiDetectionEnabled = this.piiDetectionEnabled;
        if (this.gdprCompliance != null) entity.gdprCompliance = this.gdprCompliance;
        if (this.coppaCompliance != null) entity.coppaCompliance = this.coppaCompliance;
    }

    // 业务方法
    public boolean isLive() {
        return status == GameEntity.GameStatus.LIVE;
    }

    public boolean isMultiplayer() {
        return hasMultiplayer != null && hasMultiplayer;
    }

    public boolean supportsPlatform(GameEntity.GamePlatform platform) {
        return platforms != null && platforms.contains(platform);
    }

    public String getDisplayName() {
        return displayName != null && !displayName.trim().isEmpty() ? displayName : name;
    }

    public boolean isCompliant() {
        return (gdprCompliance != null && gdprCompliance) || (coppaCompliance != null && coppaCompliance);
    }
}
