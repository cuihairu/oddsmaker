package io.oddsmaker.control.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.jpa.AuditLogEntity;
import io.oddsmaker.control.jpa.WebhookConfigEntity;
import io.oddsmaker.control.jpa.WebhookConfigRepo;
import io.oddsmaker.control.jpa.WebhookLogEntity;
import io.oddsmaker.control.jpa.WebhookLogRepo;
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
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WebhookService 单元测试：sendTestWebhook（同步直发/绕过过滤/不进重试/不污染统计/归属校验）
 * 与配置 CRUD（校验矩阵/secret 留空保留/软删置 INACTIVE/审计）
 */
@ExtendWith(MockitoExtension.class)
class WebhookServiceTest {

    @Mock
    private WebhookConfigRepo webhookConfigRepo;

    @Mock
    private WebhookLogRepo webhookLogRepo;

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private AuditLogService auditLogService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private WebhookService webhookService;

    private WebhookConfigEntity config(String gameId) {
        WebhookConfigEntity config = new WebhookConfigEntity();
        config.id = "wc_test";
        config.name = "slack-alerts";
        config.gameId = gameId;
        config.webhookUrl = "https://hooks.example.com/t1";
        config.httpMethod = "POST";
        return config;
    }

    @Test
    @DisplayName("sendTestWebhook 成功：返回 success + 投递细节，log 落 SENDING→SUCCESS，不写配置统计")
    void sendTestWebhookSuccess() {
        WebhookConfigEntity config = config("g1");
        when(webhookConfigRepo.findById("wc_test")).thenReturn(Optional.of(config));
        when(restTemplate.exchange(eq("https://hooks.example.com/t1"), eq(HttpMethod.POST),
                any(HttpEntity.class), eq(String.class)))
            .thenReturn(ResponseEntity.ok("pong"));

        Map<String, Object> result = webhookService.sendTestWebhook("wc_test", "g1");

        assertEquals("wc_test", result.get("configId"));
        assertEquals("slack-alerts", result.get("name"));
        assertEquals("https://hooks.example.com/t1", result.get("url"));
        assertEquals("success", result.get("status"));
        assertEquals(200, result.get("httpStatus"));
        assertEquals("pong", result.get("responseBody"));
        assertEquals("SUCCESS", result.get("deliveryStatus"));
        assertNotNull(result.get("responseTimeMs"));
        assertNotNull(result.get("logId"));

        // log 两次落库（SENDING → SUCCESS；captor 只存引用，首落状态已被 markAsSuccess 覆盖，
        // 以最终状态断言为准），最终成功且不进重试队列
        ArgumentCaptor<WebhookLogEntity> logCaptor = ArgumentCaptor.forClass(WebhookLogEntity.class);
        verify(webhookLogRepo, times(2)).save(logCaptor.capture());
        WebhookLogEntity finalLog = logCaptor.getAllValues().get(1);
        assertEquals(WebhookLogEntity.DeliveryStatus.SUCCESS, finalLog.deliveryStatus);
        assertNull(finalLog.nextRetryAt);
        assertEquals("webhook_test", finalLog.eventType);
        assertTrue(finalLog.requestBody.contains("webhook_test"));

        // 不污染配置统计（recordSuccess/recordFailure 不触发 → config 不落库）
        verify(webhookConfigRepo, never()).save(any(WebhookConfigEntity.class));
    }

    @Test
    @DisplayName("sendTestWebhook HTTP 失败：返回 failed + 错误信息，log 置 FAILED 不排重试")
    void sendTestWebhookHttpFailure() {
        WebhookConfigEntity config = config("g1");
        when(webhookConfigRepo.findById("wc_test")).thenReturn(Optional.of(config));
        when(restTemplate.exchange(eq("https://hooks.example.com/t1"), eq(HttpMethod.POST),
                any(HttpEntity.class), eq(String.class)))
            .thenThrow(new ResourceAccessException("Connection refused"));

        Map<String, Object> result = webhookService.sendTestWebhook("wc_test", "g1");

        assertEquals("failed", result.get("status"));
        assertEquals("Connection refused", result.get("error"));
        assertEquals("ResourceAccessException", result.get("errorType"));
        assertEquals("FAILED", result.get("deliveryStatus"));

        ArgumentCaptor<WebhookLogEntity> logCaptor = ArgumentCaptor.forClass(WebhookLogEntity.class);
        verify(webhookLogRepo, times(2)).save(logCaptor.capture());
        WebhookLogEntity finalLog = logCaptor.getAllValues().get(1);
        assertEquals(WebhookLogEntity.DeliveryStatus.FAILED, finalLog.deliveryStatus);
        assertNull(finalLog.nextRetryAt);
        verify(webhookConfigRepo, never()).save(any(WebhookConfigEntity.class));
    }

    @Test
    @DisplayName("sendTestWebhook configId 不存在：抛 IllegalArgumentException")
    void sendTestWebhookConfigNotFound() {
        when(webhookConfigRepo.findById("missing")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
            () -> webhookService.sendTestWebhook("missing", "g1"));
        verify(webhookLogRepo, never()).save(any(WebhookLogEntity.class));
    }

    @Test
    @DisplayName("sendTestWebhook gameId 与配置归属不符：抛 IllegalArgumentException")
    void sendTestWebhookGameMismatch() {
        when(webhookConfigRepo.findById("wc_test")).thenReturn(Optional.of(config("g1")));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> webhookService.sendTestWebhook("wc_test", "g2"));
        assertTrue(ex.getMessage().contains("does not belong to game"));
        verify(webhookLogRepo, never()).save(any(WebhookLogEntity.class));
    }

    // ===== 配置 CRUD =====

    private WebhookConfigEntity validCreateReq() {
        WebhookConfigEntity req = new WebhookConfigEntity();
        req.name = "  slack  ";
        req.webhookUrl = " https://hooks.example.com/x ";
        req.eventTypes = " block , risk_case ,, ";
        req.riskLevels = "HIGH, CRITICAL";
        return req;
    }

    @Test
    @DisplayName("createWebhookConfig 成功：id 前缀/默认值/Csv 规范化/审计落 CREATE")
    void createWebhookConfigSuccess() {
        when(webhookConfigRepo.findByGameIdAndName("g1", "slack")).thenReturn(Optional.empty());
        when(webhookConfigRepo.save(any(WebhookConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        WebhookConfigEntity saved = webhookService.createWebhookConfig("g1", validCreateReq(), "ops");

        assertTrue(saved.id.startsWith("wc_"));
        assertEquals("g1", saved.gameId);
        assertEquals("slack", saved.name);
        assertEquals("https://hooks.example.com/x", saved.webhookUrl);
        assertEquals("block,risk_case", saved.eventTypes);
        assertEquals("HIGH,CRITICAL", saved.riskLevels);
        assertEquals("POST", saved.httpMethod);
        assertEquals("none", saved.authType);
        assertEquals(Integer.valueOf(30), saved.timeoutSeconds);
        assertEquals(Integer.valueOf(3), saved.maxRetries);
        assertEquals(Integer.valueOf(1000), saved.retryBackoffMs);
        assertEquals("ops", saved.createdBy);
        verify(auditLogService).log(eq(AuditLogEntity.AuditAction.CREATE), eq("webhook_config"),
            eq(saved.id), eq("slack"), any(), any(), eq("ops"), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("createWebhookConfig 显式 null 字段：服务侧兜底默认值仍生效（防 API 显式 null 穿透实体初始化器）")
    void createWebhookConfigExplicitNullDefaults() {
        when(webhookConfigRepo.findByGameIdAndName("g1", "slack")).thenReturn(Optional.empty());
        when(webhookConfigRepo.save(any(WebhookConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        WebhookConfigEntity req = validCreateReq();
        req.httpMethod = null;
        req.authType = null;
        req.timeoutSeconds = null;
        req.maxRetries = null;
        req.retryBackoffMs = null;
        req.status = null;

        WebhookConfigEntity saved = webhookService.createWebhookConfig("g1", req, "ops");

        assertEquals("POST", saved.httpMethod);
        assertEquals("none", saved.authType);
        assertEquals(Integer.valueOf(30), saved.timeoutSeconds);
        assertEquals(Integer.valueOf(3), saved.maxRetries);
        assertEquals(Integer.valueOf(1000), saved.retryBackoffMs);
        assertEquals(WebhookConfigEntity.WebhookStatus.ACTIVE, saved.status);
    }

    @Test
    @DisplayName("createWebhookConfig 校验矩阵：url/方法/authType/authConfig/范围/重名")
    void createWebhookConfigValidation() {
        WebhookConfigEntity req = validCreateReq();

        req.webhookUrl = "ftp://hooks.example.com";
        assertThrows(IllegalArgumentException.class, () -> webhookService.createWebhookConfig("g1", req, "ops"));
        req.webhookUrl = null;
        assertThrows(IllegalArgumentException.class, () -> webhookService.createWebhookConfig("g1", req, "ops"));
        req.webhookUrl = "https://hooks.example.com/x";
        req.name = null;
        assertThrows(IllegalArgumentException.class, () -> webhookService.createWebhookConfig("g1", req, "ops"));
        req.name = "x";

        req.name = "x".repeat(101);
        assertThrows(IllegalArgumentException.class, () -> webhookService.createWebhookConfig("g1", req, "ops"));
        req.name = "x";
        req.webhookUrl = "https://hooks.example.com/" + "a".repeat(500);
        assertThrows(IllegalArgumentException.class, () -> webhookService.createWebhookConfig("g1", req, "ops"));
        req.webhookUrl = "https://hooks.example.com/x";

        req.httpMethod = "DELETE";
        assertThrows(IllegalArgumentException.class, () -> webhookService.createWebhookConfig("g1", req, "ops"));
        req.httpMethod = "POST";

        req.authType = "hmac";
        assertThrows(IllegalArgumentException.class, () -> webhookService.createWebhookConfig("g1", req, "ops"));
        req.authType = "BEARER";
        assertThrows(IllegalArgumentException.class, () -> webhookService.createWebhookConfig("g1", req, "ops"));
        req.authType = "bearer";

        req.authConfig = null;
        assertThrows(IllegalArgumentException.class, () -> webhookService.createWebhookConfig("g1", req, "ops"));
        req.authConfig = "not-json";
        assertThrows(IllegalArgumentException.class, () -> webhookService.createWebhookConfig("g1", req, "ops"));
        req.authConfig = "{}";
        assertThrows(IllegalArgumentException.class, () -> webhookService.createWebhookConfig("g1", req, "ops"));
        req.authConfig = "{\"token\":\"t\"}";

        req.timeoutSeconds = 0;
        assertThrows(IllegalArgumentException.class, () -> webhookService.createWebhookConfig("g1", req, "ops"));
        req.timeoutSeconds = 301;
        assertThrows(IllegalArgumentException.class, () -> webhookService.createWebhookConfig("g1", req, "ops"));
        req.timeoutSeconds = 30;
        req.maxRetries = 11;
        assertThrows(IllegalArgumentException.class, () -> webhookService.createWebhookConfig("g1", req, "ops"));
        req.maxRetries = 3;
        req.retryBackoffMs = 50;
        assertThrows(IllegalArgumentException.class, () -> webhookService.createWebhookConfig("g1", req, "ops"));
        req.retryBackoffMs = 1000;

        // 全部合法后：authType 大写规范化 + (gameId, name) 重名拒绝
        // （此分支 req.name 已在上方改为 "x"，重名查询按 trim 后实际入参 stub）
        req.authType = "API_KEY";
        req.authConfig = "{\"key\":\"k\"}";
        when(webhookConfigRepo.findByGameIdAndName("g1", "x")).thenReturn(Optional.of(config("g1")));
        IllegalArgumentException dup = assertThrows(IllegalArgumentException.class,
            () -> webhookService.createWebhookConfig("g1", req, "ops"));
        assertTrue(dup.getMessage().contains("already exists"));
        verify(webhookConfigRepo, never()).save(any(WebhookConfigEntity.class));
    }

    @Test
    @DisplayName("updateWebhookConfig：字段覆盖/secret 留空保留/归属不符或已删返回 null")
    void updateWebhookConfigSemantics() {
        WebhookConfigEntity existing = config("g1");
        existing.authType = "bearer";
        existing.authConfig = "{\"token\":\"old\"}";
        when(webhookConfigRepo.findById("wc_test")).thenReturn(Optional.of(existing));
        when(webhookConfigRepo.findByGameIdAndName("g1", "new-name")).thenReturn(Optional.empty());
        when(webhookConfigRepo.save(any(WebhookConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        WebhookConfigEntity patch = new WebhookConfigEntity();
        patch.name = "  new-name  ";
        patch.webhookUrl = "https://new.example.com/hook";
        patch.displayName = "New Hook";
        patch.authType = "API_KEY";
        patch.authConfig = null; // 留空 = 保留原 secret
        patch.timeoutSeconds = 60;
        patch.status = WebhookConfigEntity.WebhookStatus.PAUSED;

        WebhookConfigEntity updated = webhookService.updateWebhookConfig("g1", "wc_test", patch, "ops");

        assertEquals("new-name", updated.name);
        assertEquals("https://new.example.com/hook", updated.webhookUrl);
        assertEquals("New Hook", updated.displayName);
        assertEquals("api_key", updated.authType);
        assertEquals("{\"token\":\"old\"}", updated.authConfig);
        assertEquals(Integer.valueOf(60), updated.timeoutSeconds);
        assertEquals(WebhookConfigEntity.WebhookStatus.PAUSED, updated.status);
        assertEquals("ops", updated.updatedBy);

        // patch 携带新 secret：直接覆盖原值（与留空保留相对的另一半语义）
        WebhookConfigEntity patch2 = new WebhookConfigEntity();
        patch2.name = "new-name";
        patch2.webhookUrl = "https://new.example.com/hook";
        patch2.authConfig = "{\"token\":\"new\"}";
        WebhookConfigEntity updated2 = webhookService.updateWebhookConfig("g1", "wc_test", patch2, "ops");
        assertEquals("{\"token\":\"new\"}", updated2.authConfig);

        // 归属不符 / 已软删 → null（Controller 404）
        assertNull(webhookService.updateWebhookConfig("g2", "wc_test", patch, "ops"));
        existing.deletedAt = LocalDateTime.now();
        assertNull(webhookService.updateWebhookConfig("g1", "wc_test", patch, "ops"));
    }

    @Test
    @DisplayName("updateWebhookConfig 重名拒绝：同名其他配置存在时抛 IAE 不落库")
    void updateWebhookConfigNameConflict() {
        WebhookConfigEntity existing = config("g1");
        WebhookConfigEntity other = config("g1");
        other.id = "wc_other";
        when(webhookConfigRepo.findById("wc_test")).thenReturn(Optional.of(existing));
        when(webhookConfigRepo.findByGameIdAndName("g1", "slack")).thenReturn(Optional.of(other));

        WebhookConfigEntity patch = new WebhookConfigEntity();
        patch.name = "slack"; // 与 wc_other 重名
        patch.webhookUrl = "https://x.example.com/h";

        assertThrows(IllegalArgumentException.class,
            () -> webhookService.updateWebhookConfig("g1", "wc_test", patch, "ops"));
        verify(webhookConfigRepo, never()).save(any(WebhookConfigEntity.class));
    }

    @Test
    @DisplayName("deleteWebhookConfig：软删+置 INACTIVE+审计 DELETE；缺失/已删/归属不符 false")
    void deleteWebhookConfigSemantics() {
        WebhookConfigEntity existing = config("g1");
        when(webhookConfigRepo.findById("wc_test")).thenReturn(Optional.of(existing));
        when(webhookConfigRepo.save(any(WebhookConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        assertTrue(webhookService.deleteWebhookConfig("g1", "wc_test", "ops"));
        assertNotNull(existing.deletedAt);
        assertEquals(WebhookConfigEntity.WebhookStatus.INACTIVE, existing.status);
        verify(auditLogService).log(eq(AuditLogEntity.AuditAction.DELETE), eq("webhook_config"),
            eq("wc_test"), eq("slack-alerts"), any(), any(), eq("ops"), any(), any(), any(), any(), any());

        // 已软删不可再删
        assertFalse(webhookService.deleteWebhookConfig("g1", "wc_test", "ops"));

        WebhookConfigEntity other = config("g9");
        other.id = "wc_other";
        when(webhookConfigRepo.findById("wc_other")).thenReturn(Optional.of(other));
        assertFalse(webhookService.deleteWebhookConfig("g1", "wc_other", "ops")); // 归属不符
        when(webhookConfigRepo.findById("none")).thenReturn(Optional.empty());
        assertFalse(webhookService.deleteWebhookConfig("g1", "none", "ops")); // 缺失
    }

    @Test
    @DisplayName("审计序列化失败：json() 吞 JsonProcessingException 返回 null，创建流程不受阻")
    void createWebhookConfigAuditJsonFailure() throws JsonProcessingException {
        when(webhookConfigRepo.findByGameIdAndName("g1", "slack")).thenReturn(Optional.empty());
        when(webhookConfigRepo.save(any(WebhookConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new JsonProcessingException("boom") {}).when(objectMapper).writeValueAsString(any());

        WebhookConfigEntity saved = webhookService.createWebhookConfig("g1", validCreateReq(), "ops");

        assertNotNull(saved.id);
        // before/after 快照均为 null，但审计与落库照常完成
        verify(auditLogService).log(eq(AuditLogEntity.AuditAction.CREATE), eq("webhook_config"),
            eq(saved.id), eq("slack"), any(), any(), eq("ops"), isNull(), isNull(), isNull(), isNull(), any());
    }

    @Test
    @DisplayName("sendTestWebhook api_key 配置含非字符串 key：解析失败仅告警不阻断发送")
    void sendTestWebhookApiKeyParseFailure() {
        WebhookConfigEntity config = config("g1");
        config.authType = "api_key";
        config.authConfig = "{\"key\":123,\"value\":\"v\"}"; // key 非字符串 → 反序列化后 CCE → buildHeaders 吞掉
        when(webhookConfigRepo.findById("wc_test")).thenReturn(Optional.of(config));
        when(restTemplate.exchange(eq("https://hooks.example.com/t1"), eq(HttpMethod.POST),
                any(HttpEntity.class), eq(String.class)))
            .thenReturn(ResponseEntity.ok("pong"));

        Map<String, Object> result = webhookService.sendTestWebhook("wc_test", "g1");

        assertEquals("success", result.get("status"));
        assertEquals(200, result.get("httpStatus"));
    }
}
