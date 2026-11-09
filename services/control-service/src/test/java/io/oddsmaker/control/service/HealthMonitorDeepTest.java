package io.oddsmaker.control.service;

import io.oddsmaker.control.dto.GameDTO;
import io.oddsmaker.control.jpa.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
/**
 * 安全与游戏域 Service 深度单元测试（LAY-004 拆分）：健康检查、指标采集与告警、系统健康聚合、统计与定期清理。与 GameServiceTest / DashboardServicesTest 互补。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("健康监控域 Service 深度测试（检查/指标/告警）")
class HealthMonitorDeepTest {

// ===== 共享 Mock =====

    @Mock
    private AuditLogService auditLog;

    // ===== HealthMonitorService =====

    @Mock
    private HealthCheckRepo healthCheckRepo;

    @Mock
    private HealthMetricRepo healthMetricRepo;

    @Mock
    private SystemAlertRepo systemAlertRepo;

    @InjectMocks
    private HealthMonitorService healthMonitorService;

    @Test
    @DisplayName("performHealthCheck：检查项不存在抛出异常")
    void performHealthCheckFailsOnMissingCheck() {
        lenient().when(healthCheckRepo.findByName("ghost")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
            () -> healthMonitorService.performHealthCheck("ghost"));
    }

    @Test
    @DisplayName("performHealthCheck：记录响应时间/检查次数与状态")
    void performHealthCheckRecordsResult() {
        HealthCheckEntity check = new HealthCheckEntity();
        check.checkName = "primary-db";
        check.checkType = HealthCheckEntity.CheckType.DATABASE;
        lenient().when(healthCheckRepo.findByName("primary-db")).thenReturn(Optional.of(check));
        lenient().when(healthCheckRepo.save(any(HealthCheckEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        HealthCheckEntity result = healthMonitorService.performHealthCheck("primary-db");

        assertEquals(1, result.totalChecks);
        assertNotNull(result.responseTimeMs);
        assertNotNull(result.lastCheckedAt);
        assertNotNull(result.statusMessage);
        assertTrue(result.healthStatus == HealthCheckEntity.HealthStatus.HEALTHY
            || result.healthStatus == HealthCheckEntity.HealthStatus.UNHEALTHY);
        if (result.isUnhealthy()) {
            assertEquals(1, result.failedChecks.intValue());
            assertEquals(1, result.consecutiveFailures.intValue());
        }
    }

    @Test
    @DisplayName("collectMetric：CPU 正常值设置阈值且不产生告警")
    void collectMetricNormalValueDoesNotAlert() {
        lenient().when(healthMetricRepo.save(any(HealthMetricEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        HealthMetricEntity metric = healthMonitorService.collectMetric(
            HealthMetricEntity.MetricType.CPU_USAGE, "node-1", 50.0);

        assertTrue(metric.id.startsWith("hm_"));
        assertEquals("cpu_usage", metric.metricName);
        assertEquals("percent", metric.unit);
        assertEquals(70.0, metric.warningThreshold);
        assertEquals(90.0, metric.criticalThreshold);
        assertFalse(metric.isAnomaly);
        verify(systemAlertRepo, never()).save(any());
    }

    @Test
    @DisplayName("collectMetric：CPU 超阈值创建 CRITICAL 告警")
    void collectMetricCriticalCpuCreatesAlert() {
        lenient().when(healthMetricRepo.save(any(HealthMetricEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        HealthMetricEntity metric = healthMonitorService.collectMetric(
            HealthMetricEntity.MetricType.CPU_USAGE, "node-1", 95.5);

        assertTrue(metric.isAnomaly);
        assertTrue(metric.isCritical());

        ArgumentCaptor<SystemAlertEntity> captor = ArgumentCaptor.forClass(SystemAlertEntity.class);
        verify(systemAlertRepo).save(captor.capture());
        SystemAlertEntity alert = captor.getValue();
        assertTrue(alert.id.startsWith("alert_"));
        assertEquals(SystemAlertEntity.AlertType.HIGH_CPU, alert.alertType);
        assertEquals(SystemAlertEntity.Severity.CRITICAL, alert.severity);
        assertEquals(SystemAlertEntity.AlertStatus.OPEN, alert.alertStatus);
        assertEquals("node-1", alert.source);
        assertEquals(95.5, alert.metricValue);
        assertEquals(90.0, alert.thresholdValue);
        assertEquals("gt", alert.condition);
        assertTrue(alert.title.contains("CPU_USAGE"));
    }

    @Test
    @DisplayName("collectMetric：P95 延迟超阈值创建 SLOW_RESPONSE 告警")
    void collectMetricCriticalLatencyCreatesAlert() {
        lenient().when(healthMetricRepo.save(any(HealthMetricEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        HealthMetricEntity metric = healthMonitorService.collectMetric(
            HealthMetricEntity.MetricType.LATENCY_P95, "api", 1500.0);

        assertEquals("ms", metric.unit);
        assertTrue(metric.isAnomaly);

        ArgumentCaptor<SystemAlertEntity> captor = ArgumentCaptor.forClass(SystemAlertEntity.class);
        verify(systemAlertRepo).save(captor.capture());
        assertEquals(SystemAlertEntity.AlertType.SLOW_RESPONSE, captor.getValue().alertType);
    }

    @Test
    @DisplayName("collectMetric：无阈值类型走默认分支且不告警")
    void collectMetricDefaultTypeHasNoThreshold() {
        lenient().when(healthMetricRepo.save(any(HealthMetricEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        HealthMetricEntity metric = healthMonitorService.collectMetric(
            HealthMetricEntity.MetricType.REQUEST_RATE, "api", 42.0);

        assertEquals("count", metric.unit);
        assertNull(metric.criticalThreshold);
        assertFalse(metric.isAnomaly);
        verify(systemAlertRepo, never()).save(any());
    }

    @Test
    @DisplayName("acknowledgeAlert：不存在抛异常，存在则置 ACKNOWLEDGED")
    void acknowledgeAlertUpdatesStatus() {
        lenient().when(systemAlertRepo.findById("alert_missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
            () -> healthMonitorService.acknowledgeAlert("alert_missing", "ops", null));

        SystemAlertEntity alert = new SystemAlertEntity();
        alert.alertStatus = SystemAlertEntity.AlertStatus.OPEN;
        lenient().when(systemAlertRepo.findById("alert_1")).thenReturn(Optional.of(alert));
        lenient().when(systemAlertRepo.save(any(SystemAlertEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        SystemAlertEntity result = healthMonitorService.acknowledgeAlert("alert_1", "ops", "checking");

        assertEquals(SystemAlertEntity.AlertStatus.ACKNOWLEDGED, result.alertStatus);
        assertEquals("ops", result.acknowledgedBy);
        assertEquals("checking", result.acknowledgementComment);
        assertNotNull(result.acknowledgedAt);
    }

    @Test
    @DisplayName("resolveAlert：不存在抛异常，存在则置 RESOLVED")
    void resolveAlertUpdatesStatus() {
        lenient().when(systemAlertRepo.findById("alert_missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
            () -> healthMonitorService.resolveAlert("alert_missing", "ops", null));

        SystemAlertEntity alert = new SystemAlertEntity();
        alert.alertStatus = SystemAlertEntity.AlertStatus.ACKNOWLEDGED;
        lenient().when(systemAlertRepo.findById("alert_2")).thenReturn(Optional.of(alert));
        lenient().when(systemAlertRepo.save(any(SystemAlertEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        SystemAlertEntity result = healthMonitorService.resolveAlert("alert_2", "ops", "fixed");

        assertEquals(SystemAlertEntity.AlertStatus.RESOLVED, result.alertStatus);
        assertEquals("ops", result.resolvedBy);
        assertEquals("fixed", result.resolutionComment);
        assertNotNull(result.resolvedAt);
    }

    @Test
    @DisplayName("getSystemHealth：全部健康时整体 HEALTHY")
    void getSystemHealthAllHealthy() {
        HealthCheckEntity db = new HealthCheckEntity();
        db.checkName = "db";
        db.markAsHealthy("ok");
        HealthCheckEntity cache = new HealthCheckEntity();
        cache.checkName = "cache";
        cache.markAsHealthy("ok");
        lenient().when(healthCheckRepo.findEnabled()).thenReturn(List.of(db, cache));
        lenient().when(healthCheckRepo.findUnhealthy()).thenReturn(List.of());
        lenient().when(healthCheckRepo.findSlowResponses()).thenReturn(List.of());

        Map<String, Object> health = healthMonitorService.getSystemHealth();

        assertEquals(HealthCheckEntity.HealthStatus.HEALTHY, health.get("overallStatus"));
        assertEquals(2L, health.get("totalChecks"));
        assertEquals(2L, health.get("healthy"));
        assertEquals(100.0, (double) health.get("healthPercent"), 0.001);
        assertEquals(0, ((List<?>) health.get("unhealthyServices")).size());
    }

    @Test
    @DisplayName("getSystemHealth：存在不健康服务时整体 UNHEALTHY")
    void getSystemHealthUnhealthy() {
        HealthCheckEntity ok = new HealthCheckEntity();
        ok.checkName = "cache";
        ok.markAsHealthy("ok");
        HealthCheckEntity bad = new HealthCheckEntity();
        bad.checkName = "db";
        bad.markAsUnhealthy("down");
        lenient().when(healthCheckRepo.findEnabled()).thenReturn(List.of(ok));
        lenient().when(healthCheckRepo.findUnhealthy()).thenReturn(List.of(bad));
        lenient().when(healthCheckRepo.findSlowResponses()).thenReturn(List.of());

        Map<String, Object> health = healthMonitorService.getSystemHealth();

        assertEquals(HealthCheckEntity.HealthStatus.UNHEALTHY, health.get("overallStatus"));
        assertEquals(1L, health.get("unhealthy"));
        assertEquals(List.of("db"), health.get("unhealthyServices"));
    }

    @Test
    @DisplayName("getSystemHealth：慢响应导致整体 DEGRADED")
    void getSystemHealthDegradedBySlowResponses() {
        HealthCheckEntity ok = new HealthCheckEntity();
        ok.checkName = "db";
        ok.markAsHealthy("ok");
        HealthCheckEntity slow = new HealthCheckEntity();
        slow.checkName = "search";
        slow.markAsHealthy("slow but alive");
        lenient().when(healthCheckRepo.findEnabled()).thenReturn(List.of(ok));
        lenient().when(healthCheckRepo.findUnhealthy()).thenReturn(List.of());
        lenient().when(healthCheckRepo.findSlowResponses()).thenReturn(List.of(slow));

        Map<String, Object> health = healthMonitorService.getSystemHealth();

        assertEquals(HealthCheckEntity.HealthStatus.DEGRADED, health.get("overallStatus"));
        assertEquals(0, ((List<?>) health.get("unhealthyServices")).size());
        assertEquals(1, ((Number) health.get("slowResponses")).intValue());
    }

    @Test
    @DisplayName("getAlertStats：各状态与严重级别计数聚合")
    void getAlertStatsAggregatesCounts() {
        lenient().when(systemAlertRepo.countByStatus(SystemAlertEntity.AlertStatus.OPEN)).thenReturn(1L);
        lenient().when(systemAlertRepo.countByStatus(SystemAlertEntity.AlertStatus.ACKNOWLEDGED)).thenReturn(2L);
        lenient().when(systemAlertRepo.countByStatus(SystemAlertEntity.AlertStatus.INVESTIGATING)).thenReturn(3L);
        lenient().when(systemAlertRepo.countByStatus(SystemAlertEntity.AlertStatus.RESOLVED)).thenReturn(4L);
        lenient().when(systemAlertRepo.countActiveBySeverity(SystemAlertEntity.Severity.CRITICAL)).thenReturn(2L);
        lenient().when(systemAlertRepo.countActiveBySeverity(SystemAlertEntity.Severity.EMERGENCY)).thenReturn(1L);

        Map<String, Object> stats = healthMonitorService.getAlertStats();

        assertEquals(1L, stats.get("open"));
        assertEquals(2L, stats.get("acknowledged"));
        assertEquals(3L, stats.get("investigating"));
        assertEquals(4L, stats.get("resolved"));
        assertEquals(6L, stats.get("active"));
        assertEquals(3L, stats.get("highPriority"));
    }

    @Test
    @DisplayName("performScheduledHealthChecks：到期检查被执行，失败项被吞掉")
    void performScheduledHealthChecksProcessesDueChecks() {
        org.springframework.test.util.ReflectionTestUtils.setField(
            healthMonitorService, "simulatedChecksEnabled", true);
        HealthCheckEntity due = new HealthCheckEntity();
        due.checkName = "db";
        due.checkType = HealthCheckEntity.CheckType.DATABASE;
        HealthCheckEntity ghost = new HealthCheckEntity();
        ghost.checkName = "ghost";
        ghost.checkType = HealthCheckEntity.CheckType.KAFKA;
        lenient().when(healthCheckRepo.findDueChecks(any(LocalDateTime.class)))
            .thenReturn(List.of(due, ghost));
        lenient().when(healthCheckRepo.findByName("db")).thenReturn(Optional.of(due));
        lenient().when(healthCheckRepo.findByName("ghost")).thenReturn(Optional.empty());
        lenient().when(healthCheckRepo.save(any(HealthCheckEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        assertDoesNotThrow(() -> healthMonitorService.performScheduledHealthChecks());

        assertEquals(1, due.totalChecks);
        assertNotNull(due.lastCheckedAt);
        verify(healthCheckRepo, times(1)).save(any(HealthCheckEntity.class));
    }

    @Test
    @DisplayName("collectSystemMetrics：一次采集五类指标")
    void collectSystemMetricsSavesFiveMetrics() {
        org.springframework.test.util.ReflectionTestUtils.setField(
            healthMonitorService, "simulatedMetricsEnabled", true);
        lenient().when(healthMetricRepo.save(any(HealthMetricEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        assertDoesNotThrow(() -> healthMonitorService.collectSystemMetrics());

        verify(healthMetricRepo, times(5)).save(any(HealthMetricEntity.class));
    }

    @Test
    @DisplayName("查询接口：健康检查/最近指标/活跃告警直通 Repo")
    void healthMonitorReadQueries() {
        HealthCheckEntity check = new HealthCheckEntity();
        check.checkName = "db";
        lenient().when(healthCheckRepo.findByName("db")).thenReturn(Optional.of(check));
        lenient().when(healthCheckRepo.findAll()).thenReturn(List.of(check));
        lenient().when(healthMetricRepo.findRecentByType(eq(HealthMetricEntity.MetricType.CPU_USAGE), any(LocalDateTime.class)))
            .thenReturn(List.of());
        SystemAlertEntity alert = new SystemAlertEntity();
        lenient().when(systemAlertRepo.findActive()).thenReturn(List.of(alert));

        assertThrows(IllegalArgumentException.class, () -> healthMonitorService.getHealthCheck("ghost"));
        assertEquals("db", healthMonitorService.getHealthCheck("db").checkName);
        assertEquals(1, healthMonitorService.getHealthChecks().size());
        assertTrue(healthMonitorService.getRecentMetrics(HealthMetricEntity.MetricType.CPU_USAGE, null).isEmpty());
        assertEquals(1, healthMonitorService.getActiveAlerts().size());
    }

    @Test
    @DisplayName("定期清理：过期指标与已关闭告警")
    void healthMonitorScheduledCleanups() {
        lenient().when(healthMetricRepo.deleteExpired(any(LocalDateTime.class))).thenReturn(5);
        lenient().when(systemAlertRepo.deleteClosedBefore(any(LocalDateTime.class))).thenReturn(2);

        assertDoesNotThrow(() -> healthMonitorService.cleanupExpiredMetrics());
        assertDoesNotThrow(() -> healthMonitorService.cleanupClosedAlerts());

        verify(healthMetricRepo).deleteExpired(any(LocalDateTime.class));
        verify(systemAlertRepo).deleteClosedBefore(any(LocalDateTime.class));
    }
}
