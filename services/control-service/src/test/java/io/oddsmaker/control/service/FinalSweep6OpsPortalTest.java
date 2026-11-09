package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.jpa.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖率最终冲刺 6（LAY-007 拆分）：HealthMonitorService 检查异常分支，WebhookService 连接拒绝与自身 catch 重试分支，AnnouncementService 环境过滤分支。与 HealthMonitorServiceTest / WebhookServiceTest / AnnouncementServiceTest 互补。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("覆盖率冲刺 6：健康检查/webhook 重试/公告")
class FinalSweep6OpsPortalTest {

    @Mock
    private AuditLogService auditLog;

    @Mock
    private HealthCheckRepo healthCheckRepo;

    @Mock
    private GameRepo gameRepo;

    @Mock
    private GameEnvironmentRepo gameEnvironmentRepo;

    @Mock
    private WebhookConfigRepo webhookConfigRepo;

    @Mock
    private WebhookLogRepo webhookLogRepo;

    @Mock
    private AnnouncementRepo announcementRepo;

    private WebhookService newWebhookService() {
        WebhookService service = new WebhookService();
        ReflectionTestUtils.setField(service, "webhookConfigRepo", webhookConfigRepo);
        ReflectionTestUtils.setField(service, "webhookLogRepo", webhookLogRepo);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        // 按配置超时改造后字段为 provider；此处注入真实 provider 包真实 RestTemplate（连接拒绝语义不变）
        ReflectionTestUtils.setField(service, "restTemplateProvider",
            new WebhookRestTemplateProvider(new RestTemplate(), 3000));
        return service;
    }

    private WebhookConfigEntity activeConfig() {
        WebhookConfigEntity config = new WebhookConfigEntity();
        config.id = "wc_1";
        config.gameId = "g";
        config.name = "hook";
        config.webhookUrl = "http://127.0.0.1:1/hook";
        config.httpMethod = "POST";
        config.status = WebhookConfigEntity.WebhookStatus.ACTIVE;
        return config;
    }

    @Test
    @DisplayName("performHealthCheck：检查过程抛异常 → catch 分支标记不健康")
    void healthCheckExceptionBranch() {
        HealthMonitorService service = new HealthMonitorService();
        ReflectionTestUtils.setField(service, "healthCheckRepo", healthCheckRepo);
        ReflectionTestUtils.setField(service, "healthMetricRepo", mock(HealthMetricRepo.class));
        ReflectionTestUtils.setField(service, "systemAlertRepo", mock(SystemAlertRepo.class));
        ReflectionTestUtils.setField(service, "auditLogService", auditLog);

        HealthCheckEntity check = new HealthCheckEntity();
        check.checkName = "db";
        check.checkType = HealthCheckEntity.CheckType.DATABASE;
        check.totalChecks = null;   // incrementChecks 触发 NPE → catch 分支
        check.failedChecks = 0;
        when(healthCheckRepo.findByName("db")).thenReturn(Optional.of(check));
        when(healthCheckRepo.save(any(HealthCheckEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        HealthCheckEntity result = service.performHealthCheck("db");

        assertTrue(result.statusMessage != null && result.statusMessage.startsWith("Health check failed"));
        assertEquals(HealthCheckEntity.HealthStatus.UNHEALTHY, result.healthStatus);
    }

    @Test
    @DisplayName("retryWebhook：真实 RestTemplate 连接拒绝 → 失败记录且不排重试（maxRetries=0）")
    void webhookRetryConnectionRefused() {
        WebhookService service = newWebhookService();

        WebhookConfigEntity config = activeConfig();
        config.maxRetries = 0;
        WebhookLogEntity log = new WebhookLogEntity();
        log.id = "wl_1";
        log.webhookConfigId = "wc_1";
        log.eventType = "risk_action";
        log.requestBody = "{\"k\":\"v\"}";
        when(webhookLogRepo.findPendingRetries(any(LocalDateTime.class))).thenReturn(List.of(log));
        when(webhookConfigRepo.findById("wc_1")).thenReturn(Optional.of(config));
        when(webhookLogRepo.save(any(WebhookLogEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(webhookConfigRepo.save(any(WebhookConfigEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        service.processPendingRetries();

        ArgumentCaptor<WebhookLogEntity> captor = ArgumentCaptor.forClass(WebhookLogEntity.class);
        verify(webhookLogRepo, times(3)).save(captor.capture());
        List<WebhookLogEntity> saved = captor.getAllValues();
        WebhookLogEntity failedLog = saved.get(saved.size() - 1);
        assertEquals(WebhookLogEntity.DeliveryStatus.FAILED, failedLog.deliveryStatus);
        assertNull(failedLog.nextRetryAt);  // maxRetries=0 → 不排重试
        verify(webhookConfigRepo).save(any(WebhookConfigEntity.class));
    }

    @Test
    @DisplayName("retryWebhook：发送中保存抛异常 → 自身 catch 标记失败")
    void webhookRetryOwnCatchBranch() {
        WebhookService service = newWebhookService();

        WebhookConfigEntity config = activeConfig();
        WebhookLogEntity log = new WebhookLogEntity();
        log.id = "wl_1";
        log.webhookConfigId = "wc_1";
        log.eventType = "risk_action";
        log.requestBody = "{\"k\":\"v\"}";
        when(webhookLogRepo.findPendingRetries(any(LocalDateTime.class))).thenReturn(List.of(log));
        when(webhookConfigRepo.findById("wc_1")).thenReturn(Optional.of(config));
        when(webhookLogRepo.save(any(WebhookLogEntity.class)))
            .thenReturn(log)
            .thenThrow(new RuntimeException("log save failed"))
            .thenReturn(log);

        service.processPendingRetries();

        assertEquals(WebhookLogEntity.DeliveryStatus.FAILED, log.deliveryStatus);
    }

    @Test
    @DisplayName("create：有效环境归一 + 已删环境 filter 拒绝分支")
    void announcementCreateEnvironmentFilterBranches() {
        AnnouncementService service = new AnnouncementService();
        ReflectionTestUtils.setField(service, "announcementRepo", announcementRepo);
        ReflectionTestUtils.setField(service, "gameRepo", gameRepo);
        ReflectionTestUtils.setField(service, "environmentRepo", gameEnvironmentRepo);
        ReflectionTestUtils.setField(service, "auditLog", auditLog);

        GameEntity game = new GameEntity();
        game.id = "g";
        when(gameRepo.findById("g")).thenReturn(Optional.of(game));

        GameEnvironmentEntity env = new GameEnvironmentEntity();
        env.id = "env_1";
        env.gameId = "g";
        env.name = "prod";
        when(gameEnvironmentRepo.findById("env_1")).thenReturn(Optional.of(env));
        when(announcementRepo.save(any(AnnouncementEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        AnnouncementEntity scheduled = new AnnouncementEntity();
        scheduled.gameId = "g";
        scheduled.title = "t";
        scheduled.content = "c";
        scheduled.environmentId = "env_1";
        scheduled.scheduledAt = LocalDateTime.now().plusHours(2);
        AnnouncementEntity created = service.create(scheduled, "op");
        assertEquals(AnnouncementEntity.Status.SCHEDULED, created.status);
        assertEquals("env_1", created.environmentId);

        GameEnvironmentEntity deleted = new GameEnvironmentEntity();
        deleted.id = "env_2";
        deleted.gameId = "g";
        deleted.deletedAt = LocalDateTime.now();
        when(gameEnvironmentRepo.findById("env_2")).thenReturn(Optional.of(deleted));
        AnnouncementEntity bad = new AnnouncementEntity();
        bad.gameId = "g";
        bad.title = "t";
        bad.content = "c";
        bad.environmentId = "env_2";
        assertThrows(IllegalArgumentException.class, () -> service.create(bad, "op"));
    }

}
