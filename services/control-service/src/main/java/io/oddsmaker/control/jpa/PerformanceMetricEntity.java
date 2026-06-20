package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 性能指标实体
 * 记录游戏性能监控数据
 */
@Entity
@Table(name = "performance_metrics")
public class PerformanceMetricEntity {

    /**
     * 指标类型
     */
    public enum MetricType {
        FPS,            // 帧率
        LAG,            // 卡顿
        CRASH,          // 崩溃
        MEMORY,         // 内存使用
        LOAD_TIME,      // 加载时间
        NETWORK,        // 网络延迟
        BATTERY         // 电池消耗
    }

    /**
     * 严重程度
     */
    public enum Severity {
        INFO,           // 信息
        WARNING,        // 警告
        ERROR,          // 错误
        CRITICAL        // 严重
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public String id;

    @Column(name = "game_id", nullable = false)
    public String gameId;

    @Column(name = "environment", length = 50)
    public String environment;

    @Column(name = "metric_type", nullable = false)
    @Enumerated(EnumType.STRING)
    public MetricType metricType;

    @Column(name = "severity")
    @Enumerated(EnumType.STRING)
    public Severity severity;

    @Column(name = "user_id", length = 128)
    public String userId;

    @Column(name = "device_id", length = 128)
    public String deviceId;

    @Column(name = "session_id", length = 128)
    public String sessionId;

    // 设备信息
    @Column(name = "platform", length = 30)
    public String platform;

    @Column(name = "device_model", length = 100)
    public String deviceModel;

    @Column(name = "os_version", length = 50)
    public String osVersion;

    @Column(name = "app_version", length = 50)
    public String appVersion;

    // 性能指标值
    @Column(name = "metric_value", columnDefinition = "DECIMAL(18,4)")
    public Double metricValue;

    @Column(name = "metric_unit", length = 20)
    public String metricUnit;  // ms, fps, mb, etc.

    // 崩溃信息
    @Column(name = "crash_type", length = 50)
    public String crashType;  // ANR, OOM, native, java

    @Column(name = "crash_message", columnDefinition = "TEXT")
    public String crashMessage;

    @Column(name = "stack_trace", columnDefinition = "TEXT")
    public String stackTrace;

    @Column(name = "crash_hash", length = 64)
    public String crashHash;  // 用于崩溃分组

    // 上下文信息
    @Column(name = "context_data", columnDefinition = "TEXT")
    public String contextData;  // JSON格式的上下文

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
