package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 会话分析实体
 * 记录会话深度和质量指标
 */
@Entity
@Table(name = "session_analysis")
public class SessionAnalysisEntity {

    /**
     * 会话质量等级
     */
    public enum SessionQuality {
        HIGH,           // 高质量（长会话、多事件）
        MEDIUM,         // 中等质量
        LOW,            // 低质量（短会话、少事件）
        BOUNCE          // 跳出（只有1个事件）
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

    // 会话指标
    @Column(name = "total_sessions", columnDefinition = "BIGINT DEFAULT 0")
    public Long totalSessions = 0L;

    @Column(name = "unique_users", columnDefinition = "BIGINT DEFAULT 0")
    public Long uniqueUsers = 0L;

    // 时长指标
    @Column(name = "avg_session_duration", columnDefinition = "BIGINT DEFAULT 0")
    public Long avgSessionDuration = 0L;  // 毫秒

    @Column(name = "median_session_duration", columnDefinition = "BIGINT DEFAULT 0")
    public Long medianSessionDuration = 0L;

    @Column(name = "p95_session_duration", columnDefinition = "BIGINT DEFAULT 0")
    public Long p95SessionDuration = 0L;

    // 深度指标
    @Column(name = "avg_events_per_session", columnDefinition = "DECIMAL(10,2) DEFAULT 0")
    public Double avgEventsPerSession = 0.0;

    @Column(name = "avg_pages_per_session", columnDefinition = "DECIMAL(10,2) DEFAULT 0")
    public Double avgPagesPerSession = 0.0;

    // 跳出率
    @Column(name = "bounce_sessions", columnDefinition = "BIGINT DEFAULT 0")
    public Long bounceSessions = 0L;

    @Column(name = "bounce_rate", columnDefinition = "DECIMAL(5,4) DEFAULT 0")
    public Double bounceRate = 0.0;

    // 会话质量分布
    @Column(name = "high_quality_sessions", columnDefinition = "BIGINT DEFAULT 0")
    public Long highQualitySessions = 0L;

    @Column(name = "medium_quality_sessions", columnDefinition = "BIGINT DEFAULT 0")
    public Long mediumQualitySessions = 0L;

    @Column(name = "low_quality_sessions", columnDefinition = "BIGINT DEFAULT 0")
    public Long lowQualitySessions = 0L;

    // 维度
    @Column(name = "platform", length = 30)
    public String platform;

    @Column(name = "session_source", length = 50)
    public String sessionSource;  // direct, organic, paid

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
