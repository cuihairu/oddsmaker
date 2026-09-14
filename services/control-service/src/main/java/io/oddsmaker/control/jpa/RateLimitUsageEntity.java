package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 限流使用记录实体
 * 跟踪当前限流使用情况
 */
@Entity
@Table(name = "rate_limit_usage")
public class RateLimitUsageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public String id;

    @Column(name = "rate_limit_id", nullable = false)
    public String rateLimitId;  // 关联的限流规则ID

    @Column(name = "game_id")
    public String gameId;  // 游戏ID（用于快速查询）

    @Column(name = "api_key_id")
    public String apiKeyId;  // API密钥ID

    @Column(name = "endpoint", length = 200)
    public String endpoint;  // 端点路径

    @Column(name = "user_id", length = 100)
    public String userId;  // 用户ID

    @Column(name = "window_start", nullable = false)
    public LocalDateTime windowStart;  // 窗口开始时间

    @Column(name = "window_end", nullable = false)
    public LocalDateTime windowEnd;  // 窗口结束时间

    @Column(name = "request_count", columnDefinition = "INTEGER")
    public Integer requestCount = 0;  // 请求计数

    @Column(name = "blocked_count", columnDefinition = "INTEGER")
    public Integer blockedCount = 0;  // 被阻塞的请求计数

    @Column(name = "last_request_at")
    public LocalDateTime lastRequestAt;  // 最后请求时间

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
