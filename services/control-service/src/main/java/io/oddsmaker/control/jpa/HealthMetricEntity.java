package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime;

/**
 * 健康指标实体
 * 记录系统性能指标
 */
@Entity
@Table(name = "health_metrics")
public class HealthMetricEntity {

    /**
     * 指标类型
     */
    public enum MetricType {
        CPU_USAGE,          // CPU使用率
        MEMORY_USAGE,       // 内存使用率
        DISK_USAGE,         // 磁盘使用率
        NETWORK_IN,         // 网络入站流量
        NETWORK_OUT,        // 网络出站流量
        DISK_IO_READ,      // 磁盘读取速率
        DISK_IO_WRITE,     // 磁盘写入速率
        ACTIVE_CONNECTIONS, // 活跃连接数
        QUEUE_DEPTH,       // 队列深度
        THREAD_COUNT,      // 线程数
        GC_COUNT,          // 垃圾回收次数
        GC_TIME,           // 垃圾回收时间
        REQUEST_RATE,      // 请求速率
        ERROR_RATE,        // 错误率
        LATENCY_P50,       // 延迟P50
        LATENCY_P95,       // 延迟P95
        LATENCY_P99        // 延迟P99
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public String id;

    @Column(name = "metric_type", nullable = false)
    @Enumerated(EnumType.STRING)
    public MetricType metricType;

    @Column(name = "metric_name", nullable = false, length = 100)
    public String metricName;  // 指标名称

    @Column(name = "source", length = 100)
    public String source;  // 指标来源（如：server-1, db-primary）

    @Column(name = "metric_value", columnDefinition = "DECIMAL(20,4)")
    @JdbcTypeCode(SqlTypes.NUMERIC)
    public Double metricValue;  // 指标值

    @Column(name = "unit", length = 20)
    public String unit;  // 单位（percent, bytes, count, ms, etc.）

    @Column(name = "min_value", columnDefinition = "DECIMAL(20,4)")
    @JdbcTypeCode(SqlTypes.NUMERIC)
    public Double minValue;  // 最小值

    @Column(name = "max_value", columnDefinition = "DECIMAL(20,4)")
    @JdbcTypeCode(SqlTypes.NUMERIC)
    public Double maxValue;  // 最大值

    @Column(name = "avg_value", columnDefinition = "DECIMAL(20,4)")
    @JdbcTypeCode(SqlTypes.NUMERIC)
    public Double avgValue;  // 平均值

    @Column(name = "percentile_50", columnDefinition = "DECIMAL(20,4)")
    @JdbcTypeCode(SqlTypes.NUMERIC)
    public Double percentile50;  // P50

    @Column(name = "percentile_95", columnDefinition = "DECIMAL(20,4)")
    @JdbcTypeCode(SqlTypes.NUMERIC)
    public Double percentile95;  // P95

    @Column(name = "percentile_99", columnDefinition = "DECIMAL(20,4)")
    @JdbcTypeCode(SqlTypes.NUMERIC)
    public Double percentile99;  // P99

    @Column(name = "warning_threshold", columnDefinition = "DECIMAL(20,4)")
    @JdbcTypeCode(SqlTypes.NUMERIC)
    public Double warningThreshold;  // 警告阈值

    @Column(name = "critical_threshold", columnDefinition = "DECIMAL(20,4)")
    @JdbcTypeCode(SqlTypes.NUMERIC)
    public Double criticalThreshold;  // 严重阈值

    @Column(name = "is_anomaly", columnDefinition = "BOOLEAN")
    public Boolean isAnomaly = false;  // 是否为异常值

    @Column(name = "tags", columnDefinition = "TEXT")
    public String tags;  // JSON格式的标签

    @Column(name = "dimensions", columnDefinition = "TEXT")
    public String dimensions;  // JSON格式的维度

    @Column(name = "collected_at", nullable = false)
    public LocalDateTime collectedAt;  // 采集时间

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    // 辅助方法

    public boolean isWarning() {
        return warningThreshold != null && metricValue != null && metricValue >= warningThreshold;
    }

    public boolean isCritical() {
        return criticalThreshold != null && metricValue != null && metricValue >= criticalThreshold;
    }

    public boolean isNormal() {
        return !isWarning() && !isCritical();
    }

    public HealthCheckEntity.HealthStatus getHealthStatus() {
        if (isCritical()) {
            return HealthCheckEntity.HealthStatus.UNHEALTHY;
        } else if (isWarning()) {
            return HealthCheckEntity.HealthStatus.DEGRADED;
        }
        return HealthCheckEntity.HealthStatus.HEALTHY;
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (collectedAt == null) {
            collectedAt = LocalDateTime.now();
        }
    }
}
