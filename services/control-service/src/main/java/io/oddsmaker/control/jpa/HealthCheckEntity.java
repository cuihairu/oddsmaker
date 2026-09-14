package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 健康检查实体
 * 记录系统健康检查结果
 */
@Entity
@Table(name = "health_checks")
public class HealthCheckEntity {

    /**
     * 检查类型
     */
    public enum CheckType {
        DATABASE,           // 数据库连接
        STORAGE,            // 存储系统
        CACHE,              // 缓存服务
        QUEUE,              // 消息队列
        EXTERNAL_API,       // 外部API
        FLINK_CLUSTER,      // Flink集群
        KAFKA,              // Kafka服务
        ELASTICSEARCH,      // Elasticsearch
        SYSTEM_RESOURCE     // 系统资源
    }

    /**
     * 检查状态
     */
    public enum HealthStatus {
        HEALTHY,            // 健康
        DEGRADED,           // 降级
        UNHEALTHY,          // 不健康
        DOWN,               // 离线
        UNKNOWN             // 未知
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public String id;

    @Column(name = "check_type", nullable = false)
    @Enumerated(EnumType.STRING)
    public CheckType checkType;

    @Column(name = "check_name", nullable = false, length = 100)
    public String checkName;  // 检查名称（如：primary-db, cache-redis）

    @Column(name = "health_status", nullable = false)
    @Enumerated(EnumType.STRING)
    public HealthStatus healthStatus = HealthStatus.UNKNOWN;

    @Column(name = "status_message", length = 500)
    public String statusMessage;  // 状态描述

    @Column(name = "response_time_ms", columnDefinition = "BIGINT")
    public Long responseTimeMs;  // 响应时间（毫秒）

    @Column(name = "endpoint", length = 500)
    public String endpoint;  // 检查端点URL

    @Column(name = "details", columnDefinition = "TEXT")
    public String details;  // JSON格式的详细信息

    @Column(name = "metrics", columnDefinition = "TEXT")
    public String metrics;  // JSON格式的指标数据

    @Column(name = "threshold_config", columnDefinition = "TEXT")
    public String thresholdConfig;  // JSON格式的阈值配置

    @Column(name = "warning_threshold_ms", columnDefinition = "INTEGER")
    public Integer warningThresholdMs = 1000;  // 警告阈值（毫秒）

    @Column(name = "critical_threshold_ms", columnDefinition = "INTEGER")
    public Integer criticalThresholdMs = 5000;  // 严重阈值（毫秒）

    @Column(name = "consecutive_failures", columnDefinition = "INTEGER")
    public Integer consecutiveFailures = 0;  // 连续失败次数

    @Column(name = "last_healthy_at")
    public LocalDateTime lastHealthyAt;  // 最后健康时间

    @Column(name = "last_unhealthy_at")
    public LocalDateTime lastUnhealthyAt;  // 最后不健康时间

    @Column(name = "total_checks", columnDefinition = "INTEGER")
    public Integer totalChecks = 0;  // 总检查次数

    @Column(name = "failed_checks", columnDefinition = "INTEGER")
    public Integer failedChecks = 0;  // 失败检查次数

    @Column(name = "enabled", columnDefinition = "BOOLEAN")
    public Boolean enabled = true;

    @Column(name = "check_interval_seconds", columnDefinition = "INTEGER")
    public Integer checkIntervalSeconds = 60;  // 检查间隔（秒）

    @Column(name = "last_checked_at")
    public LocalDateTime lastCheckedAt;  // 最后检查时间

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @Column(name = "updated_at")
    public LocalDateTime updatedAt;

    // 辅助方法

    public boolean isHealthy() {
        return healthStatus == HealthStatus.HEALTHY;
    }

    public boolean isDegraded() {
        return healthStatus == HealthStatus.DEGRADED;
    }

    public boolean isUnhealthy() {
        return healthStatus == HealthStatus.UNHEALTHY || healthStatus == HealthStatus.DOWN;
    }

    public double getSuccessRate() {
        if (totalChecks == 0) {
            return 0.0;
        }
        return ((double) (totalChecks - failedChecks)) / totalChecks * 100;
    }

    public boolean isSlowResponse() {
        return responseTimeMs != null && responseTimeMs > criticalThresholdMs;
    }

    public boolean isWarningResponse() {
        return responseTimeMs != null && responseTimeMs > warningThresholdMs && responseTimeMs <= criticalThresholdMs;
    }

    public void markAsHealthy(String message) {
        this.healthStatus = HealthStatus.HEALTHY;
        this.statusMessage = message;
        this.lastHealthyAt = LocalDateTime.now();
        this.consecutiveFailures = 0;
    }

    public void markAsDegraded(String message) {
        this.healthStatus = HealthStatus.DEGRADED;
        this.statusMessage = message;
        this.consecutiveFailures = 0;
    }

    public void markAsUnhealthy(String message) {
        this.healthStatus = HealthStatus.UNHEALTHY;
        this.statusMessage = message;
        this.lastUnhealthyAt = LocalDateTime.now();
        this.consecutiveFailures++;
    }

    public void markAsDown(String message) {
        this.healthStatus = HealthStatus.DOWN;
        this.statusMessage = message;
        this.lastUnhealthyAt = LocalDateTime.now();
        this.consecutiveFailures++;
    }

    public void incrementChecks() {
        this.totalChecks++;
    }

    public void incrementFailures() {
        this.failedChecks++;
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
