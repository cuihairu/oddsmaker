package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 健康监控服务
 * 管理系统健康检查和性能指标收集
 */
@Service
@Transactional
public class HealthMonitorService {

    private static final Logger logger = LoggerFactory.getLogger(HealthMonitorService.class);

    @Autowired
    private HealthCheckRepo healthCheckRepo;

    @Autowired
    private HealthMetricRepo healthMetricRepo;

    @Autowired
    private SystemAlertRepo systemAlertRepo;

    @Autowired
    private AuditLogService auditLogService;

    // 模拟数据开关：@EnableScheduling 激活前这些 @Scheduled 从未执行，激活后默认关闭
    // 避免 Math.random 假指标/假告警污染真实监控数据
    @Value("${oddsmaker.health.simulated-checks-enabled:false}")
    private boolean simulatedChecksEnabled;

    @Value("${oddsmaker.health.simulated-metrics-enabled:false}")
    private boolean simulatedMetricsEnabled;

    /**
     * 执行健康检查
     */
    public HealthCheckEntity performHealthCheck(String checkName) {
        HealthCheckEntity check = healthCheckRepo.findByName(checkName)
            .orElseThrow(() -> new IllegalArgumentException("Health check not found: " + checkName));

        long startTime = System.currentTimeMillis();

        try {
            // 模拟健康检查
            boolean isHealthy = simulateHealthCheck(check.checkType);
            long responseTime = System.currentTimeMillis() - startTime;

            check.responseTimeMs = responseTime;
            check.lastCheckedAt = LocalDateTime.now();
            check.incrementChecks();

            if (isHealthy) {
                check.markAsHealthy("Service is responding normally");
            } else {
                check.markAsUnhealthy("Service is not responding");
                check.incrementFailures();
            }

        } catch (Exception e) {
            check.responseTimeMs = System.currentTimeMillis() - startTime;
            check.markAsUnhealthy("Health check failed: " + e.getMessage());
            check.incrementFailures();
            logger.error("Health check failed for {}: {}", checkName, e.getMessage(), e);
        }

        return healthCheckRepo.save(check);
    }

    /**
     * 模拟健康检查
     */
    private boolean simulateHealthCheck(HealthCheckEntity.CheckType type) {
        // 模拟健康检查逻辑
        return Math.random() > 0.1;  // 90%成功率
    }

    /**
     * 收集系统指标
     */
    public HealthMetricEntity collectMetric(HealthMetricEntity.MetricType metricType, String source, Double value) {
        HealthMetricEntity metric = new HealthMetricEntity();
        metric.id = "hm_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        metric.metricType = metricType;
        metric.metricName = metricType.name().toLowerCase();
        metric.source = source;
        metric.metricValue = value;
        metric.collectedAt = LocalDateTime.now();

        // 设置单位和阈值
        setMetricDefaults(metric);

        // 检查是否为异常值
        checkForAnomalies(metric);

        metric = healthMetricRepo.save(metric);

        // 如果指标异常，创建告警
        if (metric.isCritical()) {
            createAlertFromMetric(metric);
        }

        return metric;
    }

    /**
     * 设置指标默认值
     */
    private void setMetricDefaults(HealthMetricEntity metric) {
        switch (metric.metricType) {
            case CPU_USAGE:
                metric.unit = "percent";
                metric.warningThreshold = 70.0;
                metric.criticalThreshold = 90.0;
                break;
            case MEMORY_USAGE:
                metric.unit = "percent";
                metric.warningThreshold = 80.0;
                metric.criticalThreshold = 95.0;
                break;
            case DISK_USAGE:
                metric.unit = "percent";
                metric.warningThreshold = 80.0;
                metric.criticalThreshold = 90.0;
                break;
            case LATENCY_P95:
                metric.unit = "ms";
                metric.warningThreshold = 500.0;
                metric.criticalThreshold = 1000.0;
                break;
            case ERROR_RATE:
                metric.unit = "percent";
                metric.warningThreshold = 1.0;
                metric.criticalThreshold = 5.0;
                break;
            default:
                metric.unit = "count";
                break;
        }
    }

    /**
     * 检查异常值
     */
    private void checkForAnomalies(HealthMetricEntity metric) {
        if (metric.criticalThreshold != null && metric.metricValue >= metric.criticalThreshold) {
            metric.isAnomaly = true;
        }
    }

    /**
     * 从指标创建告警
     */
    private void createAlertFromMetric(HealthMetricEntity metric) {
        SystemAlertEntity alert = new SystemAlertEntity();
        alert.id = "alert_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        alert.alertType = getAlertTypeFromMetric(metric.metricType);
        alert.severity = SystemAlertEntity.Severity.CRITICAL;
        alert.alertStatus = SystemAlertEntity.AlertStatus.OPEN;
        alert.title = String.format("%s threshold exceeded for %s", metric.metricType, metric.source);
        alert.description = String.format("Current value: %.2f %s, Critical threshold: %.2f %s",
            metric.metricValue, metric.unit, metric.criticalThreshold, metric.unit);
        alert.source = metric.source;
        alert.affectedResource = metric.source;
        alert.metricValue = metric.metricValue;
        alert.thresholdValue = metric.criticalThreshold;
        alert.condition = "gt";

        systemAlertRepo.save(alert);

        logger.warn("Created alert from metric: type={}, source={}, value={}",
            metric.metricType, metric.source, metric.metricValue);
    }

    /**
     * 从指标类型获取告警类型
     */
    private SystemAlertEntity.AlertType getAlertTypeFromMetric(HealthMetricEntity.MetricType metricType) {
        return switch (metricType) {
            case CPU_USAGE -> SystemAlertEntity.AlertType.HIGH_CPU;
            case MEMORY_USAGE -> SystemAlertEntity.AlertType.HIGH_MEMORY;
            case DISK_USAGE -> SystemAlertEntity.AlertType.HIGH_DISK;
            case LATENCY_P95, LATENCY_P99 -> SystemAlertEntity.AlertType.SLOW_RESPONSE;
            case ERROR_RATE -> SystemAlertEntity.AlertType.HIGH_ERROR_RATE;
            default -> SystemAlertEntity.AlertType.ANOMALY_DETECTED;
        };
    }

    /**
     * 获取健康检查
     */
    @Transactional(readOnly = true)
    public HealthCheckEntity getHealthCheck(String checkName) {
        return healthCheckRepo.findByName(checkName)
            .orElseThrow(() -> new IllegalArgumentException("Health check not found: " + checkName));
    }

    /**
     * 获取所有健康检查
     */
    @Transactional(readOnly = true)
    public List<HealthCheckEntity> getHealthChecks() {
        return healthCheckRepo.findAll();
    }

    /**
     * 获取系统健康状况
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getSystemHealth() {
        List<HealthCheckEntity> allChecks = healthCheckRepo.findEnabled();
        List<HealthCheckEntity> unhealthy = healthCheckRepo.findUnhealthy();
        List<HealthCheckEntity> slow = healthCheckRepo.findSlowResponses();

        long total = allChecks.size();
        long healthy = allChecks.stream().filter(HealthCheckEntity::isHealthy).count();
        long degraded = allChecks.stream().filter(HealthCheckEntity::isDegraded).count();
        long unhealthyCount = unhealthy.size();

        HealthCheckEntity.HealthStatus overallStatus;
        if (unhealthyCount > 0) {
            overallStatus = HealthCheckEntity.HealthStatus.UNHEALTHY;
        } else if (degraded > 0 || !slow.isEmpty()) {
            overallStatus = HealthCheckEntity.HealthStatus.DEGRADED;
        } else {
            overallStatus = HealthCheckEntity.HealthStatus.HEALTHY;
        }

        return Map.of(
            "overallStatus", overallStatus,
            "totalChecks", total,
            "healthy", healthy,
            "degraded", degraded,
            "unhealthy", unhealthyCount,
            "slowResponses", slow.size(),
            "healthPercent", total > 0 ? (healthy * 100.0 / total) : 0.0,
            "unhealthyServices", unhealthy.stream().map(hc -> hc.checkName).collect(Collectors.toList())
        );
    }

    /**
     * 获取最近的指标
     */
    @Transactional(readOnly = true)
    public List<HealthMetricEntity> getRecentMetrics(HealthMetricEntity.MetricType type, LocalDateTime since) {
        return healthMetricRepo.findRecentByType(type, since != null ? since : LocalDateTime.now().minusHours(1));
    }

    /**
     * 获取活跃告警
     */
    @Transactional(readOnly = true)
    public List<SystemAlertEntity> getActiveAlerts() {
        return systemAlertRepo.findActive();
    }

    /**
     * 确认告警
     */
    public SystemAlertEntity acknowledgeAlert(String alertId, String acknowledgedBy, String comment) {
        SystemAlertEntity alert = systemAlertRepo.findById(alertId)
            .orElseThrow(() -> new IllegalArgumentException("Alert not found: " + alertId));

        alert.acknowledge(acknowledgedBy, comment);
        return systemAlertRepo.save(alert);
    }

    /**
     * 解决告警
     */
    public SystemAlertEntity resolveAlert(String alertId, String resolvedBy, String comment) {
        SystemAlertEntity alert = systemAlertRepo.findById(alertId)
            .orElseThrow(() -> new IllegalArgumentException("Alert not found: " + alertId));

        alert.resolve(resolvedBy, comment);
        return systemAlertRepo.save(alert);
    }

    /**
     * 获取告警统计
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getAlertStats() {
        long open = systemAlertRepo.countByStatus(SystemAlertEntity.AlertStatus.OPEN);
        long acknowledged = systemAlertRepo.countByStatus(SystemAlertEntity.AlertStatus.ACKNOWLEDGED);
        long investigating = systemAlertRepo.countByStatus(SystemAlertEntity.AlertStatus.INVESTIGATING);
        long resolved = systemAlertRepo.countByStatus(SystemAlertEntity.AlertStatus.RESOLVED);

        long critical = systemAlertRepo.countActiveBySeverity(SystemAlertEntity.Severity.CRITICAL);
        long emergency = systemAlertRepo.countActiveBySeverity(SystemAlertEntity.Severity.EMERGENCY);

        return Map.of(
            "open", open,
            "acknowledged", acknowledged,
            "investigating", investigating,
            "resolved", resolved,
            "active", open + acknowledged + investigating,
            "critical", critical,
            "emergency", emergency,
            "highPriority", critical + emergency
        );
    }

    /**
     * 定期执行健康检查
     */
    @Scheduled(fixedDelay = 30000)  // 每30秒执行一次
    public void performScheduledHealthChecks() {
        if (!simulatedChecksEnabled) {
            return;
        }
        try {
            LocalDateTime since = LocalDateTime.now().minusMinutes(1);
            List<HealthCheckEntity> dueChecks = healthCheckRepo.findDueChecks(since);

            for (HealthCheckEntity check : dueChecks) {
                try {
                    performHealthCheck(check.checkName);
                } catch (Exception e) {
                    logger.error("Failed to perform health check for {}: {}", check.checkName, e.getMessage());
                }
            }

            if (!dueChecks.isEmpty()) {
                logger.debug("Performed {} health checks", dueChecks.size());
            }
        } catch (Exception e) {
            logger.error("Failed to perform scheduled health checks", e);
        }
    }

    /**
     * 定期收集系统指标
     */
    @Scheduled(fixedDelay = 60000)  // 每分钟执行一次
    public void collectSystemMetrics() {
        if (!simulatedMetricsEnabled) {
            return;
        }
        try {
            // 模拟收集系统指标
            collectMetric(HealthMetricEntity.MetricType.CPU_USAGE, "system", Math.random() * 100);
            collectMetric(HealthMetricEntity.MetricType.MEMORY_USAGE, "system", Math.random() * 100);
            collectMetric(HealthMetricEntity.MetricType.DISK_USAGE, "system", 50 + Math.random() * 40);
            collectMetric(HealthMetricEntity.MetricType.LATENCY_P95, "api", 100 + Math.random() * 400);
            collectMetric(HealthMetricEntity.MetricType.ERROR_RATE, "api", Math.random() * 2);

            logger.debug("Collected system metrics");
        } catch (Exception e) {
            logger.error("Failed to collect system metrics", e);
        }
    }

    /**
     * 定期检查告警升级
     */
    @Scheduled(fixedDelay = 300000)  // 每5分钟执行一次
    public void checkAlertEscalations() {
        try {
            List<SystemAlertEntity> needingEscalation = systemAlertRepo.findNeedingEscalation();

            for (SystemAlertEntity alert : needingEscalation) {
                alert.escalate();
                systemAlertRepo.save(alert);

                logger.warn("Escalated alert: {} - {}", alert.id, alert.title);

                // TODO: 发送升级通知
            }

            if (!needingEscalation.isEmpty()) {
                logger.info("Escalated {} alerts", needingEscalation.size());
            }
        } catch (Exception e) {
            logger.error("Failed to check alert escalations", e);
        }
    }

    /**
     * 定期清理过期指标
     */
    @Scheduled(cron = "0 0 2 * * ?")  // 每天凌晨2点执行
    public void cleanupExpiredMetrics() {
        try {
            LocalDateTime expireBefore = LocalDateTime.now().minusDays(30);
            int deleted = healthMetricRepo.deleteExpired(expireBefore);

            if (deleted > 0) {
                logger.info("Cleaned up {} expired health metrics", deleted);
            }
        } catch (Exception e) {
            logger.error("Failed to cleanup expired metrics", e);
        }
    }

    /**
     * 定期清理已关闭的告警
     */
    @Scheduled(cron = "0 0 3 * * ?")  // 每天凌晨3点执行
    public void cleanupClosedAlerts() {
        try {
            LocalDateTime expireBefore = LocalDateTime.now().minusDays(90);
            int deleted = systemAlertRepo.deleteClosedBefore(expireBefore);

            if (deleted > 0) {
                logger.info("Cleaned up {} closed alerts", deleted);
            }
        } catch (Exception e) {
            logger.error("Failed to cleanup closed alerts", e);
        }
    }
}
