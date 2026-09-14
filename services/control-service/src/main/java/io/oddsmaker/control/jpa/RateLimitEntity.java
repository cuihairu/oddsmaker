package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 限流规则实体
 * 管理API密钥或端点的限流规则
 */
@Entity
@Table(name = "rate_limits")
public class RateLimitEntity {

    /**
     * 限流窗口类型
     */
    public enum WindowType {
        SECOND,     // 每秒
        MINUTE,     // 每分钟
        HOUR,       // 每小时
        DAY,        // 每天窗口
        WEEK,       // 每周窗口
        MONTH       // 每月窗口
    }

    /**
     * 限流算法
     */
    public enum Algorithm {
        FIXED_WINDOW,    // 固定窗口
        SLIDING_WINDOW,  // 滑动窗口
        TOKEN_BUCKET,    // 令牌桶
        LEAKY_BUCKET     // 漏桶
    }

    /**
     * 限流范围
     */
    public enum Scope {
        GLOBAL,      // 全局限流
        GAME,        // 游戏级别限流
        API_KEY,     // API密钥级别限流
        ENDPOINT,    // 端点级别限流
        USER         // 用户级别限流
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public String id;

    @Column(name = "game_id")
    public String gameId;  // 游戏ID（游戏级别限流时使用）

    @Column(name = "api_key_id")
    public String apiKeyId;  // API密钥ID（API密钥级别限流时使用）

    @Column(name = "endpoint", length = 200)
    public String endpoint;  // 端点路径（端点级别限流时使用）

    @Column(name = "user_id", length = 100)
    public String userId;  // 用户ID（用户级别限流时使用）

    @Column(name = "scope", nullable = false)
    @Enumerated(EnumType.STRING)
    public Scope scope = Scope.GLOBAL;

    // 迁移中列名为 limit_value（limit 为 SQL 保留字），validate 按列名对齐
    @Column(name = "limit_value", nullable = false)
    public Integer limit = 100;  // 限流阈值

    @Column(name = "window_type", nullable = false)
    @Enumerated(EnumType.STRING)
    public WindowType windowType = WindowType.MINUTE;

    @Column(name = "window_size", columnDefinition = "INTEGER")
    public Integer windowSize = 1;  // 窗口大小（多个窗口）

    @Column(name = "algorithm", nullable = false)
    @Enumerated(EnumType.STRING)
    public Algorithm algorithm = Algorithm.SLIDING_WINDOW;

    @Column(name = "burst", columnDefinition = "INTEGER")
    public Integer burst = 0;  // 突发流量允许量

    @Column(name = "priority", columnDefinition = "INTEGER")
    public Integer priority = 0;  // 优先级（数字越大优先级越高）

    @Column(name = "enabled", columnDefinition = "BOOLEAN")
    public Boolean enabled = true;

    @Column(name = "description", length = 500)
    public String description;

    @Column(name = "config", columnDefinition = "TEXT")
    public String config;  // JSON格式的额外配置

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @Column(name = "updated_at")
    public LocalDateTime updatedAt;

    @Column(name = "created_by", length = 64)
    public String createdBy;

    @Column(name = "deleted_at")
    public LocalDateTime deletedAt;

    // 辅助方法

    public boolean isEnabled() {
        return enabled != null && enabled && deletedAt == null;
    }

    public boolean isGlobal() {
        return scope == Scope.GLOBAL;
    }

    public boolean isGameLevel() {
        return scope == Scope.GAME;
    }

    public boolean isApiKeyLevel() {
        return scope == Scope.API_KEY;
    }

    public boolean isEndpointLevel() {
        return scope == Scope.ENDPOINT;
    }

    public boolean isUserLevel() {
        return scope == Scope.USER;
    }

    public long getWindowDurationMs() {
        return switch (windowType) {
            case SECOND -> 1000L * windowSize;
            case MINUTE -> 60000L * windowSize;
            case HOUR -> 3600000L * windowSize;
            case DAY -> 86400000L * windowSize;
            case WEEK -> 604800000L * windowSize;
            case MONTH -> 2592000000L * windowSize;
        };
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
