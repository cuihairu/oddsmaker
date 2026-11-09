package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.jpa.RiskCaseEntity;
import io.oddsmaker.control.jpa.WebhookConfigEntity;
import io.oddsmaker.control.jpa.WebhookConfigRepo;
import io.oddsmaker.control.jpa.WebhookLogEntity;
import io.oddsmaker.control.jpa.WebhookLogRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 基础设施 Service 深度单元测试（LAY-008 拆分）：Webhook 风险案件推送、自定义事件与鉴权头构建、发送失败/同步分支、待处理重试、查询统计与清理。与 WebhookServiceTest / InfraServicesTest 互补。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Webhook Service 深度测试（发送/鉴权/重试）")
class WebhookServiceDeepTest {

    @Mock
    private WebhookConfigRepo webhookConfigRepo;

    @Mock
    private WebhookLogRepo webhookLogRepo;

    @Mock
    private WebhookRestTemplateProvider restTemplateProvider;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private Executor asyncExecutor;

    @Mock
    private RestTemplate restTemplate;

    @InjectMocks
    private WebhookService webhookService;

    @BeforeEach
    void setUp() {
        lenient().when(webhookConfigRepo.save(any(WebhookConfigEntity.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(webhookLogRepo.save(any(WebhookLogEntity.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(restTemplateProvider.forConfig(any(WebhookConfigEntity.class))).thenReturn(restTemplate);
        lenient().doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(asyncExecutor).execute(any(Runnable.class));
    }

    private WebhookConfigEntity webhookConfig(String id, String eventTypes, String riskLevels) {
        WebhookConfigEntity c = new WebhookConfigEntity();
        c.id = id;
        c.gameId = "g1";
        c.name = "hook-" + id;
        c.webhookUrl = "http://hook.test/" + id;
        c.httpMethod = "POST";
        c.eventTypes = eventTypes;
        c.riskLevels = riskLevels;
        c.maxRetries = 3;
        c.retryBackoffMs = 100;
        c.totalSent = 0L;
        c.totalSuccess = 0L;
        c.totalFailed = 0L;
        return c;
    }

    private RiskCaseEntity riskCase() {
        RiskCaseEntity rc = new RiskCaseEntity();
        rc.id = "case-1";
        rc.caseNumber = "CASE_20260909_001";
        rc.gameId = "g1";
        rc.environmentId = "env1";
        rc.targetType = "user_id";
        rc.targetId = "u1";
        rc.targetName = "Player One";
        rc.triggerEventId = "evt-1";
        rc.triggerEventType = "login";
        rc.triggerEventName = "Player Login";
        rc.riskLevel = RiskCaseEntity.RiskLevel.HIGH;
        rc.riskScore = 88;
        rc.actionTaken = RiskCaseEntity.ActionType.BLOCK;
        rc.actionDescription = "auto block";
        rc.createdAt = LocalDateTime.now();
        return rc;
    }

    private WebhookLogEntity capturedWebhookLog() {
        ArgumentCaptor<WebhookLogEntity> captor = ArgumentCaptor.forClass(WebhookLogEntity.class);
        verify(webhookLogRepo, times(2)).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("Webhook：风险案例推送（事件/环境/风险等级过滤）")
    void webhookSendRiskCase() {
        WebhookConfigEntity match = webhookConfig("wc-match", "risk_case", "HIGH");
        match.environmentId = "env1";
        WebhookConfigEntity wrongEvent = webhookConfig("wc-event", "other_event", "HIGH");
        WebhookConfigEntity wrongEnv = webhookConfig("wc-env", "risk_case", "HIGH");
        wrongEnv.environmentId = "env2";
        WebhookConfigEntity wrongLevel = webhookConfig("wc-level", "risk_case", "LOW");
        lenient().when(webhookConfigRepo.findActiveByGameId("g1"))
            .thenReturn(List.of(match, wrongEvent, wrongEnv, wrongLevel));
        lenient().when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class)))
            .thenReturn(ResponseEntity.ok("ok"));

        webhookService.sendRiskCaseWebhook("g1", "env1", riskCase());

        // 仅匹配配置发送一次
        ArgumentCaptor<HttpEntity<String>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate, times(1)).exchange(anyString(), any(HttpMethod.class), entityCaptor.capture(), eq(String.class));
        assertEquals(1L, match.totalSent);
        assertEquals(1L, match.totalSuccess);
        assertEquals(0L, wrongEvent.totalSent);
        assertEquals(0L, wrongEnv.totalSent);
        assertEquals(0L, wrongLevel.totalSent);

        // 请求体包含风险案例字段
        WebhookLogEntity savedLog = capturedWebhookLog();
        assertNotNull(savedLog);
        assertTrue(savedLog.requestBody.contains("case-1"));
        assertTrue(savedLog.requestBody.contains("\"level\":\"HIGH\""));
        assertEquals("risk_case", savedLog.eventType);
    }

    @Test
    @DisplayName("Webhook：自定义推送与鉴权头构建（bearer/api_key/basic/坏 JSON）")
    @SuppressWarnings("unchecked")
    void webhookSendCustomWithAuthHeaders() {
        WebhookConfigEntity bearer = webhookConfig("wc-bearer", "custom_event", null);
        bearer.authType = "bearer";
        bearer.authConfig = "{\"token\":\"tok-123\"}";
        WebhookConfigEntity apiKey = webhookConfig("wc-apikey", "custom_event", null);
        apiKey.authType = "api_key";
        apiKey.authConfig = "{\"key\":\"X-Custom-Key\",\"value\":\"secret-value\"}";
        WebhookConfigEntity basic = webhookConfig("wc-basic", "custom_event", null);
        basic.authType = "basic";
        WebhookConfigEntity badAuth = webhookConfig("wc-badauth", "custom_event", null);
        badAuth.authType = "bearer";
        badAuth.authConfig = "not-json";
        WebhookConfigEntity customHeaders = webhookConfig("wc-headers", "custom_event", null);
        customHeaders.requestHeaders = "{\"X-Custom\":\"custom-value\"}";
        WebhookConfigEntity badHeaders = webhookConfig("wc-badheaders", "custom_event", null);
        badHeaders.requestHeaders = "not-json";

        lenient().when(webhookConfigRepo.findActiveByGameId("g1"))
            .thenReturn(List.of(bearer, apiKey, basic, badAuth, customHeaders, badHeaders));
        lenient().when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class)))
            .thenReturn(ResponseEntity.ok("ok"));

        webhookService.sendCustomWebhook("g1", "custom_event", Map.of("payload", "data"));

        ArgumentCaptor<HttpEntity<String>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate, times(6)).exchange(anyString(), any(HttpMethod.class), captor.capture(), eq(String.class));

        List<HttpEntity<String>> entities = captor.getAllValues();
        // bearer 鉴权头
        assertTrue(entities.get(0).getHeaders().getFirst("Authorization").contains("tok-123"));
        // api_key 鉴权头
        assertEquals("secret-value", entities.get(1).getHeaders().getFirst("X-Custom-Key"));
        // basic 不添加鉴权头
        assertNull(entities.get(2).getHeaders().getFirst("Authorization"));
        // 坏 authConfig 不抛异常
        assertNull(entities.get(3).getHeaders().getFirst("Authorization"));
        // 自定义请求头
        assertEquals("custom-value", entities.get(4).getHeaders().getFirst("X-Custom"));
        // 坏 requestHeaders 不抛异常
        assertNull(entities.get(5).getHeaders().getFirst("X-Custom"));
        // 全部成功
        assertEquals(1L, bearer.totalSuccess);
        assertEquals(1L, apiKey.totalSuccess);
    }

    @Test
    @DisplayName("Webhook：发送失败（安排重试 / 不重试）与同步发送分支")
    void webhookSendFailureAndSync() throws Exception {
        // 失败 + 安排重试：log -> RETRYING
        WebhookConfigEntity retryable = webhookConfig("wc-retry", "custom_event", null);
        lenient().when(webhookConfigRepo.findActiveByGameId("g1")).thenReturn(List.of(retryable));
        lenient().when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class)))
            .thenThrow(new RuntimeException("connection refused"));
        webhookService.sendCustomWebhook("g1", "custom_event", Map.of("k", "v"));
        assertEquals(1L, retryable.totalFailed);
        assertEquals(0L, retryable.totalSuccess);

        // 失败 + 不重试（maxRetries=0）：log -> FAILED
        WebhookConfigEntity noRetry = webhookConfig("wc-noretry", "custom_event", null);
        noRetry.maxRetries = 0;
        lenient().when(webhookConfigRepo.findActiveByGameId("g2")).thenReturn(List.of(noRetry));
        webhookService.sendCustomWebhook("g2", "custom_event", Map.of("k", "v"));
        assertEquals(1L, noRetry.totalFailed);

        // 验证两种终态日志
        ArgumentCaptor<WebhookLogEntity> logCaptor = ArgumentCaptor.forClass(WebhookLogEntity.class);
        verify(webhookLogRepo, times(4)).save(logCaptor.capture());
        List<WebhookLogEntity> logs = logCaptor.getAllValues();
        assertEquals(WebhookLogEntity.DeliveryStatus.RETRYING, logs.get(1).deliveryStatus);
        assertNotNull(logs.get(1).nextRetryAt);
        assertEquals(WebhookLogEntity.DeliveryStatus.FAILED, logs.get(3).deliveryStatus);
        assertNotNull(logs.get(3).errorMessage);

        // 无执行器：同步发送分支
        Field executorField = WebhookService.class.getDeclaredField("asyncExecutor");
        executorField.setAccessible(true);
        executorField.set(webhookService, null);
        WebhookConfigEntity sync = webhookConfig("wc-sync", "custom_event", null);
        lenient().when(webhookConfigRepo.findActiveByGameId("g3")).thenReturn(List.of(sync));
        lenient().when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class)))
            .thenReturn(ResponseEntity.ok("ok"));
        webhookService.sendCustomWebhook("g3", "custom_event", Map.of("k", "v"));
        assertEquals(1L, sync.totalSuccess);
    }

    @Test
    @DisplayName("Webhook：待重试队列处理与异常分支")
    void webhookProcessPendingRetries() {
        WebhookConfigEntity config = webhookConfig("wc-retryable", "risk_case", null);
        WebhookConfigEntity inactiveConfig = webhookConfig("wc-inactive", "risk_case", null);
        inactiveConfig.status = WebhookConfigEntity.WebhookStatus.INACTIVE;

        WebhookLogEntity toRetry = new WebhookLogEntity();
        toRetry.id = "wl-1";
        toRetry.webhookConfigId = "wc-retryable";
        toRetry.eventType = "risk_case";
        toRetry.eventId = "e1";
        toRetry.requestBody = "{\"k\":\"v\"}";
        toRetry.deliveryStatus = WebhookLogEntity.DeliveryStatus.RETRYING;
        WebhookLogEntity missingConfig = new WebhookLogEntity();
        missingConfig.id = "wl-2";
        missingConfig.webhookConfigId = "wc-missing";
        missingConfig.deliveryStatus = WebhookLogEntity.DeliveryStatus.RETRYING;
        WebhookLogEntity inactiveLog = new WebhookLogEntity();
        inactiveLog.id = "wl-3";
        inactiveLog.webhookConfigId = "wc-inactive";
        inactiveLog.deliveryStatus = WebhookLogEntity.DeliveryStatus.RETRYING;

        lenient().when(webhookLogRepo.findPendingRetries(any(LocalDateTime.class)))
            .thenReturn(List.of(toRetry, missingConfig, inactiveLog));
        lenient().when(webhookConfigRepo.findById("wc-retryable")).thenReturn(Optional.of(config));
        lenient().when(webhookConfigRepo.findById("wc-missing")).thenReturn(Optional.empty());
        lenient().when(webhookConfigRepo.findById("wc-inactive")).thenReturn(Optional.of(inactiveConfig));
        lenient().when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class)))
            .thenReturn(ResponseEntity.ok("ok"));

        assertDoesNotThrow(() -> webhookService.processPendingRetries());
        // retryWebhook 将 toRetry 标记为 SENDING，实际重发由 sendWebhook 用新日志对象完成
        assertEquals(WebhookLogEntity.DeliveryStatus.SENDING, toRetry.deliveryStatus);
        assertEquals(1L, config.totalSuccess);
        verify(restTemplate, times(1)).exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class));
        assertEquals(WebhookLogEntity.DeliveryStatus.RETRYING, missingConfig.deliveryStatus);
        assertEquals(WebhookLogEntity.DeliveryStatus.RETRYING, inactiveLog.deliveryStatus);

        // 坏 requestBody：反序列化失败回退空 Map，仍能重发成功
        toRetry.requestBody = "not-json";
        toRetry.deliveryStatus = WebhookLogEntity.DeliveryStatus.RETRYING;
        webhookService.processPendingRetries();
        assertEquals(2L, config.totalSuccess);

        // 队列查询异常：吞掉
        lenient().when(webhookLogRepo.findPendingRetries(any(LocalDateTime.class)))
            .thenThrow(new RuntimeException("db boom"));
        assertDoesNotThrow(() -> webhookService.processPendingRetries());
    }

    @Test
    @DisplayName("Webhook：配置/日志查询、统计与过期清理")
    void webhookQueriesStatsAndCleanup() {
        // getConfig 不存在
        lenient().when(webhookConfigRepo.findById("missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> webhookService.getConfig("missing"));

        // 查询
        WebhookConfigEntity config = webhookConfig("wc-1", null, null);
        lenient().when(webhookConfigRepo.findById("wc-1")).thenReturn(Optional.of(config));
        lenient().when(webhookConfigRepo.findByGameId("g1")).thenReturn(List.of(config));
        WebhookLogEntity log = new WebhookLogEntity();
        log.deliveryStatus = WebhookLogEntity.DeliveryStatus.SUCCESS;
        WebhookLogEntity failed = new WebhookLogEntity();
        failed.deliveryStatus = WebhookLogEntity.DeliveryStatus.FAILED;
        WebhookLogEntity pending = new WebhookLogEntity();
        pending.deliveryStatus = WebhookLogEntity.DeliveryStatus.PENDING;
        lenient().when(webhookLogRepo.findByGameId("g1")).thenReturn(List.of(log, failed, pending));
        lenient().when(webhookLogRepo.findByWebhookConfigId("wc-1")).thenReturn(List.of(log));

        assertEquals(config, webhookService.getConfig("wc-1"));
        assertEquals(List.of(config), webhookService.getGameConfigs("g1"));
        assertEquals(List.of(log), webhookService.getWebhookLogs("wc-1"));

        // 统计（注意 totalConfigs 为 int 装箱 Integer，count() 结果为 Long）
        Map<String, Object> stats = webhookService.getWebhookStats("g1");
        assertEquals(1, stats.get("totalConfigs"));
        assertEquals(1L, stats.get("activeConfigs"));
        assertEquals(1L, stats.get("totalSent"));
        assertEquals(1L, stats.get("totalFailed"));
        Map<?, ?> byStatus = (Map<?, ?>) stats.get("byStatus");
        assertEquals(3, byStatus.size());

        // 清理：正常 + 异常
        lenient().when(webhookLogRepo.deleteExpiredLogs(any(LocalDateTime.class)))
            .thenReturn(5)
            .thenThrow(new RuntimeException("cleanup boom"));
        assertDoesNotThrow(() -> webhookService.cleanupExpiredLogs());
        assertDoesNotThrow(() -> webhookService.cleanupExpiredLogs());
        verify(webhookLogRepo, times(2)).deleteExpiredLogs(any(LocalDateTime.class));
    }

}
