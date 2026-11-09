package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.HealthCheckRepo;
import io.oddsmaker.control.jpa.HealthMetricEntity;
import io.oddsmaker.control.jpa.HealthMetricRepo;
import io.oddsmaker.control.jpa.SystemAlertEntity;
import io.oddsmaker.control.jpa.SystemAlertRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Service 层覆盖率收尾测试（LAY-006 拆分）：内存与错误率指标告警、健康检查告警升级、升级负载严重级别空侧。与 HealthMonitorServiceTest / HealthMonitorDeepTest 等互补。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("健康监控 Service 深度测试（内存/错误率/告警升级）")
class ServicesFinalSweepHealthTest {

    @Mock
    private HealthCheckRepo healthCheckRepo;

    @Mock
    private HealthMetricRepo healthMetricRepo;

    @Mock
    private SystemAlertRepo systemAlertRepo;

    @Mock
    private AuditLogService auditLog;

    @Mock
    private WebhookService webhookService;

    @InjectMocks
    private HealthMonitorService healthMonitorService;

    @Test
    @DisplayName("健康监控：MEMORY/ERROR_RATE 超阈值创建对应 CRITICAL 告警")
    void healthCollectMetricMemoryAndErrorRateAlerts() {
        lenient().when(healthMetricRepo.save(any(HealthMetricEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        HealthMetricEntity memory = healthMonitorService.collectMetric(
            HealthMetricEntity.MetricType.MEMORY_USAGE, "node-1", 96.0);
        assertEquals("percent", memory.unit);
        assertEquals(80.0, memory.warningThreshold);
        assertEquals(95.0, memory.criticalThreshold);
        assertTrue(memory.isCritical());

        ArgumentCaptor<SystemAlertEntity> captor = ArgumentCaptor.forClass(SystemAlertEntity.class);
        verify(systemAlertRepo).save(captor.capture());
        assertEquals(SystemAlertEntity.AlertType.HIGH_MEMORY, captor.getValue().alertType);
        assertEquals(SystemAlertEntity.Severity.CRITICAL, captor.getValue().severity);
        assertEquals("node-1", captor.getValue().source);

        HealthMetricEntity errorRate = healthMonitorService.collectMetric(
            HealthMetricEntity.MetricType.ERROR_RATE, "api", 6.0);
        assertEquals("percent", errorRate.unit);
        assertEquals(5.0, errorRate.criticalThreshold);
        assertTrue(errorRate.isCritical());

        ArgumentCaptor<SystemAlertEntity> second = ArgumentCaptor.forClass(SystemAlertEntity.class);
        verify(systemAlertRepo, times(2)).save(second.capture());
        assertEquals(SystemAlertEntity.AlertType.HIGH_ERROR_RATE, second.getAllValues().get(1).alertType);
    }

    @Test
    @DisplayName("健康监控：checkAlertEscalations 升级到期告警并保存")
    void healthCheckAlertEscalations() {
        SystemAlertEntity alert = new SystemAlertEntity();
        alert.id = "alert_1";
        alert.alertStatus = SystemAlertEntity.AlertStatus.OPEN;
        alert.escalationLevel = 0;
        lenient().when(systemAlertRepo.findNeedingEscalation()).thenReturn(List.of(alert));
        lenient().when(systemAlertRepo.save(any(SystemAlertEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        assertDoesNotThrow(() -> healthMonitorService.checkAlertEscalations());

        assertEquals(1, alert.escalationLevel);
        assertNotNull(alert.escalatedAt);
        verify(systemAlertRepo).save(alert);

        lenient().when(systemAlertRepo.findNeedingEscalation()).thenThrow(new RuntimeException("boom"));
        assertDoesNotThrow(() -> healthMonitorService.checkAlertEscalations());
    }

    @Test
    @DisplayName("健康监控：升级派发 payload 的 severity null 侧（显式覆盖 WARNING 初始化器）")
    void healthEscalationPayloadSeverityNullSide() {
        // severity 有 WARNING 初始化器：HTTP/创建链路恒非 null，null 侧须显式置 null 直达
        SystemAlertEntity platformAlert = new SystemAlertEntity();
        platformAlert.id = "alert_nosev";
        platformAlert.alertStatus = SystemAlertEntity.AlertStatus.OPEN;
        platformAlert.escalationLevel = 0;
        platformAlert.gameId = "g1";   // 带 gameId 才会进 payload 派发分支
        platformAlert.severity = null;
        lenient().when(systemAlertRepo.findNeedingEscalation()).thenReturn(List.of(platformAlert));
        lenient().when(systemAlertRepo.save(any(SystemAlertEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        assertDoesNotThrow(() -> healthMonitorService.checkAlertEscalations());
        assertEquals(1, platformAlert.escalationLevel);
    }

}
