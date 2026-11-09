package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.jpa.IntegrationEntity;
import io.oddsmaker.control.jpa.IntegrationLogEntity;
import io.oddsmaker.control.jpa.IntegrationLogRepo;
import io.oddsmaker.control.jpa.IntegrationRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import java.lang.reflect.Field;
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
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 基础设施 Service 深度单元测试（LAY-008 拆分）：集成创建与更新、序列化失败容忍、校验/启用/停用/删除、调用与批量调用。与 IntegrationServiceTest / IntegrationServiceDeepTest / InfraServicesTest 互补。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("集成 Service 深度测试（创建/调用/重试）")
class InfraIntegrationDeepTest {

    @Mock
    private IntegrationRepo integrationRepo;

    @Mock
    private IntegrationLogRepo integrationLogRepo;

    @Mock
    private AuditLogService auditLogService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private RestTemplate restTemplate;

    @InjectMocks
    private IntegrationService integrationService;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(integrationRepo.save(any(IntegrationEntity.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(integrationLogRepo.save(any(IntegrationLogEntity.class))).thenAnswer(i -> i.getArgument(0));
        Field restTemplateField = IntegrationService.class.getDeclaredField("restTemplate");
        restTemplateField.setAccessible(true);
        restTemplateField.set(integrationService, restTemplate);
    }

    private IntegrationEntity integration(String id, IntegrationEntity.IntegrationStatus status, boolean enabled) {
        IntegrationEntity e = new IntegrationEntity();
        e.id = id;
        e.gameId = "g1";
        e.name = "slack-hook";
        e.integrationType = IntegrationEntity.IntegrationType.SLACK;
        e.authType = IntegrationEntity.AuthType.API_KEY;
        e.endpointUrl = "http://unit.test/hook";
        e.apiKey = "key-1";
        e.integrationStatus = status;
        e.enabled = enabled;
        e.retryCount = 0;
        e.maxRetries = 3;
        return e;
    }

    @Test
    @DisplayName("集成：创建与更新（字段覆盖、null 跳过、状态重置）")
    void integrationCreateAndUpdate() {
        // 创建（全参数）
        IntegrationEntity created = integrationService.createIntegration("g1", "slack-hook", "desc",
            IntegrationEntity.IntegrationType.SLACK, IntegrationEntity.AuthType.API_KEY,
            "http://unit.test/hook", "key", "secret", Map.of("channel", "#alerts"), 60, "admin");
        assertNotNull(created.id);
        assertTrue(created.id.startsWith("int_"));
        assertEquals(IntegrationEntity.IntegrationStatus.INACTIVE, created.integrationStatus);
        assertEquals(60, created.timeoutSeconds);
        assertTrue(created.config.contains("#alerts"));
        verify(auditLogService).logIntegrationCreate(eq(created.id), eq("slack-hook"), eq("SLACK"), eq("admin"), eq("g1"));

        // 创建（默认值）
        IntegrationEntity defaulted = integrationService.createIntegration("g1", "empty", null,
            IntegrationEntity.IntegrationType.WEBHOOK, null, "http://unit.test/hook", null, null, null, null, null);
        assertEquals(IntegrationEntity.AuthType.NONE, defaulted.authType);
        assertEquals(30, defaulted.timeoutSeconds);
        assertNull(defaulted.config);

        // 更新：不存在
        lenient().when(integrationRepo.findById("missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
            () -> integrationService.updateIntegration("missing", "n", "d", "u", "k", "s", null));

        // 更新：全字段覆盖 + 状态重置为 INACTIVE
        IntegrationEntity entity = integration("i1", IntegrationEntity.IntegrationStatus.ACTIVE, true);
        lenient().when(integrationRepo.findById("i1")).thenReturn(Optional.of(entity));
        IntegrationEntity updated = integrationService.updateIntegration("i1", "new-name", "new-desc",
            "http://new.url", "new-key", "new-secret", Map.of("k", "v"));
        assertEquals("new-name", updated.name);
        assertEquals("new-desc", updated.description);
        assertEquals("http://new.url", updated.endpointUrl);
        assertEquals("new-key", updated.apiKey);
        assertEquals("new-secret", updated.apiSecret);
        assertTrue(updated.config.contains("\"k\":\"v\""));
        assertEquals(IntegrationEntity.IntegrationStatus.INACTIVE, updated.integrationStatus);

        // 更新：null 参数跳过
        IntegrationEntity untouched = integrationService.updateIntegration("i1", null, null, null, null, null, null);
        assertEquals("new-name", untouched.name);
        assertTrue(untouched.config.contains("\"k\":\"v\""));
    }

    @Test
    @DisplayName("集成：config/payload 不可序列化时 catch 吞掉，主流程继续")
    void integrationSerializationFailureTolerated() {
        java.util.Map<String, Object> bad = new java.util.HashMap<>();
        bad.put("obj", new Object());  // Jackson 无法序列化

        // 创建：config 序列化失败 → config 为 null，创建仍成功
        IntegrationEntity created = integrationService.createIntegration("g1", "bad-cfg", null,
            IntegrationEntity.IntegrationType.WEBHOOK, IntegrationEntity.AuthType.NONE,
            "http://unit.test/x", null, null, bad, null, "admin");
        assertNotNull(created.id);

        // 更新：同样吞掉
        IntegrationEntity entity = integration("i1", IntegrationEntity.IntegrationStatus.ACTIVE, true);
        lenient().when(integrationRepo.findById("i1")).thenReturn(Optional.of(entity));
        IntegrationEntity updated = integrationService.updateIntegration("i1", null, null, null, null, null, bad);
        assertNotNull(updated);

        // 调用：payload 序列化失败 → requestBody 为 null，调用继续
        entity.enabled = true;
        entity.integrationStatus = IntegrationEntity.IntegrationStatus.ACTIVE;  // update 后已重置为 INACTIVE
        IntegrationLogEntity result = integrationService.callIntegration("i1", "evt", bad, "cid");
        assertNotNull(result);
    }

    @Test
    @DisplayName("集成：验证/启用/禁用/获取/删除")
    void integrationVerifyEnableDisableDelete() {
        // 验证：不存在
        lenient().when(integrationRepo.findById("missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> integrationService.verifyIntegration("missing"));

        // 验证成功（真探测 stub 成功 -> ACTIVE）
        RestTemplate probe = mock(RestTemplate.class);
        when(probe.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenReturn(new ResponseEntity<>("ok", org.springframework.http.HttpStatus.OK));
        IntegrationService spied = spy(integrationService);
        doReturn(probe).when(spied).restTemplateFor(any(IntegrationEntity.class));
        IntegrationEntity entity = integration("i1", IntegrationEntity.IntegrationStatus.INACTIVE, true);
        lenient().when(integrationRepo.findById("i1")).thenReturn(Optional.of(entity));
        IntegrationEntity verified = spied.verifyIntegration("i1");
        assertEquals(IntegrationEntity.IntegrationStatus.ACTIVE, verified.integrationStatus);
        assertNotNull(verified.lastVerifiedAt);
        assertNull(verified.lastError);
        verify(integrationRepo, times(2)).save(entity);

        // 启用
        entity.enabled = false;
        IntegrationEntity enabled = integrationService.enableIntegration("i1");
        assertTrue(enabled.enabled);

        // 禁用：状态 DISABLED + 审计
        IntegrationEntity disabled = integrationService.disableIntegration("i1");
        assertFalse(disabled.enabled);
        assertEquals(IntegrationEntity.IntegrationStatus.DISABLED, disabled.integrationStatus);
        verify(auditLogService).logIntegrationDisable("i1", "slack-hook", "g1");

        // 启用/禁用/获取：不存在
        assertThrows(IllegalArgumentException.class, () -> integrationService.enableIntegration("missing"));
        assertThrows(IllegalArgumentException.class, () -> integrationService.disableIntegration("missing"));
        assertThrows(IllegalArgumentException.class, () -> integrationService.getIntegration("missing"));

        // 删除：软删除
        lenient().when(integrationRepo.findById("i2")).thenReturn(Optional.of(integration("i2",
            IntegrationEntity.IntegrationStatus.ACTIVE, true)));
        integrationService.deleteIntegration("i2");
        verify(auditLogService).logIntegrationDelete("i2", "slack-hook", "g1");
    }

    @Test
    @DisplayName("集成：调用（校验、成功、失败重试、超过最大重试）")
    void integrationCall() {
        // 不存在
        lenient().when(integrationRepo.findById("missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
            () -> integrationService.callIntegration("missing", "risk_case", Map.of(), "c1"));

        // 非活跃
        IntegrationEntity inactive = integration("i-inactive", IntegrationEntity.IntegrationStatus.INACTIVE, true);
        lenient().when(integrationRepo.findById("i-inactive")).thenReturn(Optional.of(inactive));
        assertThrows(IllegalStateException.class,
            () -> integrationService.callIntegration("i-inactive", "risk_case", Map.of(), "c1"));

        // 成功
        IntegrationEntity active = integration("i-ok", IntegrationEntity.IntegrationStatus.ACTIVE, true);
        lenient().when(integrationRepo.findById("i-ok")).thenReturn(Optional.of(active));
        lenient().when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class)))
            .thenReturn(ResponseEntity.ok("pong"));
        IntegrationLogEntity okLog = integrationService.callIntegration("i-ok", "risk_case",
            Map.of("case", "c1"), "corr-1");
        assertTrue(okLog.isSuccess());
        assertEquals(200, okLog.responseStatus);
        assertEquals("pong", okLog.responseBody);
        assertNotNull(okLog.durationMs);
        assertTrue(okLog.requestBody.contains("c1"));
        verify(auditLogService).logIntegrationCall("i-ok", "risk_case", "SUCCESS", "g1");

        // 失败：未超最大重试（retryCount 递增，状态保持）
        IntegrationEntity flaky = integration("i-fail", IntegrationEntity.IntegrationStatus.ACTIVE, true);
        lenient().when(integrationRepo.findById("i-fail")).thenReturn(Optional.of(flaky));
        lenient().when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class)))
            .thenThrow(new RuntimeException("connection refused"));
        IntegrationLogEntity failLog = integrationService.callIntegration("i-fail", "risk_case", Map.of(), null);
        assertTrue(failLog.isFailed());
        assertNotNull(failLog.errorMessage);
        assertEquals(1, flaky.retryCount);
        assertEquals(IntegrationEntity.IntegrationStatus.ACTIVE, flaky.integrationStatus);
        verify(auditLogService).logIntegrationCall("i-fail", "risk_case", "FAILED", "g1");

        // 失败：已达最大重试（标记 FAILED）
        IntegrationEntity exhausted = integration("i-exhausted", IntegrationEntity.IntegrationStatus.ACTIVE, true);
        exhausted.retryCount = 2;
        exhausted.maxRetries = 3;
        lenient().when(integrationRepo.findById("i-exhausted")).thenReturn(Optional.of(exhausted));
        IntegrationLogEntity exhaustedLog = integrationService.callIntegration("i-exhausted", "alert", Map.of(), null);
        assertTrue(exhaustedLog.isFailed());
        assertEquals(IntegrationEntity.IntegrationStatus.FAILED, exhausted.integrationStatus);
        assertNotNull(exhausted.lastError);
    }

    @Test
    @DisplayName("集成：批量调用（过滤非活跃集成）")
    void integrationCallBatch() {
        IntegrationEntity active = integration("b1", IntegrationEntity.IntegrationStatus.ACTIVE, true);
        IntegrationEntity inactive = integration("b2", IntegrationEntity.IntegrationStatus.INACTIVE, true);
        lenient().when(integrationRepo.findByGameIdAndType("g1", IntegrationEntity.IntegrationType.SLACK))
            .thenReturn(List.of(active, inactive));
        lenient().when(integrationRepo.findById("b1")).thenReturn(Optional.of(active));
        lenient().when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class)))
            .thenReturn(ResponseEntity.ok("ok"));

        List<IntegrationLogEntity> logs = integrationService.callIntegrations("g1",
            IntegrationEntity.IntegrationType.SLACK, "risk_case", Map.of("x", 1), "corr");
        assertEquals(1, logs.size());
        assertEquals("b1", logs.get(0).integrationId);
        verify(restTemplate, times(1)).exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class));
    }

}
