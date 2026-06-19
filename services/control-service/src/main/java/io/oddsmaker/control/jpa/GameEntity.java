package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 游戏实体。
 * 每个游戏有独立的配置、环境和API密钥。
 */
@Entity
@Table(name = "games")
public class GameEntity {

    @Id
    @Column(length = 32)
    public String id;

    @Column(nullable = false, length = 100)
    public String name;

    @Column(name = "display_name", length = 200)
    public String displayName;

    @Column(length = 1000)
    public String description;

    /**
     * 游戏类型: action, rpg, strategy, puzzle, casual, simulation, sports, other
     */
    @Enumerated(EnumType.STRING)
    public GameGenre genre;

    /**
     * 支持平台: web, mobile, pc, console
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "game_platforms", joinColumns = @JoinColumn(name = "game_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "platform")
    public Set<GamePlatform> platforms;

    /**
     * 游戏状态: development, testing, live, maintenance, discontinued
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public GameStatus status = GameStatus.DEVELOPMENT;

    // 版本信息
    @Column(name = "current_version", length = 20)
    public String currentVersion;

    @Column(name = "min_supported_version", length = 20)
    public String minSupportedVersion;

    // 发布信息
    @Column(name = "release_date")
    public LocalDateTime releaseDate;

    @Column(name = "app_store_url", length = 500)
    public String appStoreUrl;

    @Column(name = "google_play_url", length = 500)
    public String googlePlayUrl;

    @Column(name = "steam_url", length = 500)
    public String steamUrl;

    // 游戏专业配置
    @Column(name = "default_currency", length = 10)
    public String defaultCurrency = "USD";

    @Column(name = "virtual_currencies")
    public String virtualCurrencies; // JSON格式存储: ["coins", "gems", "energy"]

    @Column(name = "max_level")
    public Integer maxLevel;

    @Column(name = "has_multiplayer")
    public Boolean hasMultiplayer = false;

    @Column(name = "has_guilds")
    public Boolean hasGuilds = false;

    @Column(name = "has_pvp")
    public Boolean hasPvp = false;

    // 数据配置
    @Column(name = "data_retention_days")
    public Integer dataRetentionDays = 90;

    @Column(name = "enable_real_time_analytics")
    public Boolean enableRealTimeAnalytics = true;

    @Column(name = "enable_crash_reporting")
    public Boolean enableCrashReporting = true;

    @Column(name = "sample_rate")
    public Double sampleRate = 1.0; // 采样率 0.0-1.0

    // 隐私和合规配置
    @Column(name = "pii_detection_enabled")
    public Boolean piiDetectionEnabled = true;

    @Column(name = "gdpr_compliance")
    public Boolean gdprCompliance = false;

    @Column(name = "coppa_compliance")
    public Boolean coppaCompliance = false;

    // 时间戳
    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    public LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    public LocalDateTime deletedAt;

    @OneToMany(mappedBy = "game", fetch = FetchType.LAZY)
    public List<GameEnvironmentEntity> environments;

    @OneToMany(mappedBy = "game", fetch = FetchType.LAZY)
    public List<ApiKeyEntity> apiKeys;

    public enum GameGenre {
        ACTION,      // 动作
        RPG,         // 角色扮演
        STRATEGY,    // 策略
        PUZZLE,      // 解谜
        CASUAL,      // 休闲
        SIMULATION,  // 模拟
        SPORTS,      // 体育
        RACING,      // 竞速
        SHOOTER,     // 射击
        MMORPG,      // 大型多人在线角色扮演
        MOBA,        // 多人在线战斗竞技
        BATTLE_ROYALE, // 大逃杀
        OTHER        // 其他
    }

    public enum GamePlatform {
        WEB,         // 网页
        MOBILE,      // 移动端
        PC,          // PC端
        CONSOLE,     // 主机
        VR,          // 虚拟现实
        AR           // 增强现实
    }

    public enum GameStatus {
        DEVELOPMENT,  // 开发中
        TESTING,      // 测试中
        LIVE,         // 已上线
        MAINTENANCE,  // 维护中
        DISCONTINUED  // 已停服
    }

    // 业务方法
    public boolean isLive() {
        return status == GameStatus.LIVE && deletedAt == null;
    }

    public boolean supportsPlatform(GamePlatform platform) {
        return platforms != null && platforms.contains(platform);
    }

    public boolean isMultiplayer() {
        return hasMultiplayer != null && hasMultiplayer;
    }

    public String getFullName() {
        return displayName != null ? displayName : name;
    }
}
