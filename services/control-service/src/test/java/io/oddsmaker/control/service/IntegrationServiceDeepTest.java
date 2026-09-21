package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.IntegrationEntity;
import io.oddsmaker.control.jpa.IntegrationLogEntity;
import io.oddsmaker.control.jpa.IntegrationLogRepo;
import io.oddsmaker.control.jpa.IntegrationRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 集成服务深度测试：verify 真探测三态（200/4xx 可达语义/连接层失败）、创建校验收窄、
 * @Scheduled 重试真化语义、callIntegration 落库与鉴权头。
 * restTemplateFor 是包级方法 + restTemplate 是 private final 字段 → 手动构造 + spy/reflection 注入 stub。
 */
@DisplayName("集成服务：verify 真探测/创建校验/重试洗白修复/调用鉴权头")
class IntegrationServiceDeepTest {

    private IntegrationRepo integrationRepo;
    private IntegrationLogRepo integrationLogRepo;
    private AuditLogService auditLogService;
    private RestTemplate probeTemplate;
    private RestTemplate callTemplate;
    private IntegrationService service;

    @BeforeEach
    void setUp() {
        integrationRepo = mock(IntegrationRepo.class);
        integrationLogRepo = mock(IntegrationLogRepo.class);
        auditLogService = mock(AuditLogService.class);
        probeTemplate = mock(RestTemplate.class);
        callTemplate = mock(RestTemplate.class);

        IntegrationService real = new IntegrationService();
        ReflectionTestUtils.setField(real, "integrationRepo", integrationRepo);
        ReflectionTestUtils.setField(real, "integrationLogRepo", integrationLogRepo);
        ReflectionTestUtils.setField(real, "auditLogService", auditLogService);
        ReflectionTestUtils.setField(real, "restTemplate", callTemplate);
        ReflectionTestUtils.setField(real, "objectMapper", new ObjectMapper());
        service = spy(real);
        // restTemplateFor 按集成现建模板——stub 掉以捕获探测请求
        doReturn(probeTemplate).when(service).restTemplateFor(any(IntegrationEntity.class));

        lenient().when(integrationRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(integrationLogRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private IntegrationEntity activeIntegration() {
        IntegrationEntity e = new IntegrationEntity();
        e.id = "int_1";
        e.gameId = "g1";
        e.name = "slack";
        e.integrationType = IntegrationEntity.IntegrationType.SLACK;
        e.authType = IntegrationEntity.AuthType.NONE;
        e.endpointUrl = "https://hooks.example.com/T1/B2/x3";
        e.integrationStatus = IntegrationEntity.IntegrationStatus.INACTIVE;
        e.enabled = true;
        e.maxRetries = 3;
        e.retryCount = 0;
        return e;
    }

    @Test
    @DisplayName("verify：GET 200 → ACTIVE + lastVerifiedAt + log 成功落库")
    void verifyHttp200Activates() {
        IntegrationEntity e = activeIntegration();
        when(integrationRepo.findById("int_1")).thenReturn(java.util.Optional.of(e));
        when(probeTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenReturn(new ResponseEntity<>("ok", HttpStatus.OK));

        IntegrationEntity result = service.verifyIntegration("int_1");

        assertEquals(IntegrationEntity.IntegrationStatus.ACTIVE, result.integrationStatus);
        assertNotNull(result.lastVerifiedAt);
        assertEquals(0, result.retryCount);
        ArgumentCaptor<IntegrationLogEntity> logCap = ArgumentCaptor.forClass(IntegrationLogEntity.class);
        verify(integrationLogRepo).save(logCap.capture());
        assertEquals(IntegrationLogEntity.CallStatus.SUCCESS, logCap.getValue().callStatus);
        assertEquals(200, logCap.getValue().responseStatus);
        assertEquals("GET", logCap.getValue().httpMethod);
        assertEquals("health_check", logCap.getValue().eventType);
        assertNotNull(logCap.getValue().durationMs);
    }

    @Test
    @DisplayName("verify：404/405 也证明 URL 可达 → 仍 ACTIVE，log 留痕 reachable")
    void verifyHttpErrorStillReachable() {
        IntegrationEntity e = activeIntegration();
        when(integrationRepo.findById("int_1")).thenReturn(java.util.Optional.of(e));
        when(probeTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "nf", new HttpHeaders(), new byte[0], null));

        IntegrationEntity result = service.verifyIntegration("int_1");

        assertEquals(IntegrationEntity.IntegrationStatus.ACTIVE, result.integrationStatus);
        ArgumentCaptor<IntegrationLogEntity> logCap = ArgumentCaptor.forClass(IntegrationLogEntity.class);
        verify(integrationLogRepo).save(logCap.capture());
        assertEquals(IntegrationLogEntity.CallStatus.SUCCESS, logCap.getValue().callStatus);
        assertEquals(404, logCap.getValue().responseStatus);
        assertEquals("reachable, HTTP 404", logCap.getValue().errorMessage);
    }

    @Test
    @DisplayName("verify：连接层失败（超时/DNS/拒绝）→ FAILED + retryCount 递增 + lastError")
    void verifyConnectionFailureMarksFailed() {
        IntegrationEntity e = activeIntegration();
        e.integrationStatus = IntegrationEntity.IntegrationStatus.FAILED;
        when(integrationRepo.findById("int_1")).thenReturn(java.util.Optional.of(e));
        when(probeTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenThrow(new ResourceAccessException("Connect timed out"));

        IntegrationEntity result = service.verifyIntegration("int_1");

        assertEquals(IntegrationEntity.IntegrationStatus.FAILED, result.integrationStatus);
        assertEquals(1, result.retryCount);
        assertEquals("Connect timed out", result.lastError);
        ArgumentCaptor<IntegrationLogEntity> logCap = ArgumentCaptor.forClass(IntegrationLogEntity.class);
        verify(integrationLogRepo).save(logCap.capture());
        assertEquals(IntegrationLogEntity.CallStatus.FAILED, logCap.getValue().callStatus);
        assertEquals("Connect timed out", logCap.getValue().errorMessage);
    }

    @Test
    @DisplayName("verify：意外异常（非 HTTP/非 IO）同样落 FAILED 不上抛")
    void verifyUnexpectedExceptionMarksFailed() {
        IntegrationEntity e = activeIntegration();
        when(integrationRepo.findById("int_1")).thenReturn(java.util.Optional.of(e));
        when(probeTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenThrow(new RuntimeException("boom"));

        IntegrationEntity result = service.verifyIntegration("int_1");

        assertEquals(IntegrationEntity.IntegrationStatus.FAILED, result.integrationStatus);
        assertEquals("boom", result.lastError);
    }

    @Test
    @DisplayName("verify/callIntegration：集成不存在 → IAE")
    void nonexistentIntegrationThrows() {
        when(integrationRepo.findById("missing")).thenReturn(java.util.Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.verifyIntegration("missing"));
        assertThrows(IllegalArgumentException.class,
            () -> service.callIntegration("missing", "evt", java.util.Map.of(), "c"));
        verify(integrationRepo, never()).save(any());
    }

    @Test
    @DisplayName("verify：软删集成拒绝（不可复活为 ACTIVE）")
    void verifyDeletedIntegrationThrows() {
        IntegrationEntity e = activeIntegration();
        e.deletedAt = LocalDateTime.now();
        when(integrationRepo.findById("int_1")).thenReturn(java.util.Optional.of(e));

        assertThrows(IllegalArgumentException.class, () -> service.verifyIntegration("int_1"));
        verify(integrationRepo, never()).save(any());
    }

    @Test
    @DisplayName("create：authType 收窄——OAUTH2/HMAC/MUTUAL_TLS/BEARER_TOKEN/BASIC_AUTH 全拒绝")
    void createRejectsUnsupportedAuthTypes() {
        for (IntegrationEntity.AuthType type : java.util.List.of(
                IntegrationEntity.AuthType.OAUTH2, IntegrationEntity.AuthType.HMAC,
                IntegrationEntity.AuthType.MUTUAL_TLS, IntegrationEntity.AuthType.BEARER_TOKEN,
                IntegrationEntity.AuthType.BASIC_AUTH)) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.createIntegration("g1", "n", null, IntegrationEntity.IntegrationType.SLACK,
                    type, "https://x.example.com", null, null, null, null, "admin"));
            assertTrue(ex.getMessage().contains(type.name()), "消息应含被拒类型: " + ex.getMessage());
        }
        verify(integrationRepo, never()).save(any());
    }

    @Test
    @DisplayName("create：endpointUrl 限非空 http(s)，timeoutSeconds 钳制 1-300")
    void createValidatesUrlAndTimeout() {
        IntegrationEntity.IntegrationType type = IntegrationEntity.IntegrationType.SLACK;
        assertThrows(IllegalArgumentException.class,
            () -> service.createIntegration("g1", "n", null, type, null, "ftp://x", null, null, null, null, "a"));
        assertThrows(IllegalArgumentException.class,
            () -> service.createIntegration("g1", "n", null, type, null, "  ", null, null, null, null, "a"));
        assertThrows(IllegalArgumentException.class,
            () -> service.createIntegration("g1", "n", null, type, null, null, null, null, null, null, "a"));
        assertThrows(IllegalArgumentException.class,
            () -> service.createIntegration("g1", "n", null, type, null, "https://x", null, null, null, 0, "a"));
        assertThrows(IllegalArgumentException.class,
            () -> service.createIntegration("g1", "n", null, type, null, "https://x", null, null, null, 301, "a"));
        verify(integrationRepo, never()).save(any());
    }

    @Test
    @DisplayName("create：合法 NONE/API_KEY 通过，url trim、timeout null 兜底 30、初始 INACTIVE")
    void createAcceptsSupportedTypes() {
        IntegrationEntity e = service.createIntegration("g1", " hook ", null,
            IntegrationEntity.IntegrationType.SLACK, null, " https://hooks.example.com/x ",
            null, null, null, null, "admin");
        assertEquals(IntegrationEntity.AuthType.NONE, e.authType);
        assertEquals("https://hooks.example.com/x", e.endpointUrl);
        assertEquals(30, e.timeoutSeconds);
        assertEquals(IntegrationEntity.IntegrationStatus.INACTIVE, e.integrationStatus);

        IntegrationEntity k = service.createIntegration("g1", "svc", null,
            IntegrationEntity.IntegrationType.WEBHOOK, IntegrationEntity.AuthType.API_KEY,
            "https://svc.example.com", "ak", null, null, 60, "admin");
        assertEquals(IntegrationEntity.AuthType.API_KEY, k.authType);
        assertEquals(60, k.timeoutSeconds);
    }

    @Test
    @DisplayName("@Scheduled 重试：失败集成真探测成功 → ACTIVE 且 retryCount 归零（洗白修复）")
    void retryFailedIntegrationsReactivatesOnRealProbeSuccess() {
        IntegrationEntity e = activeIntegration();
        e.integrationStatus = IntegrationEntity.IntegrationStatus.FAILED;
        e.retryCount = 2;
        when(integrationRepo.findRetryable()).thenReturn(java.util.List.of(e));
        when(integrationRepo.findById("int_1")).thenReturn(java.util.Optional.of(e));
        when(probeTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenReturn(new ResponseEntity<>("ok", HttpStatus.OK));

        service.retryFailedIntegrations();

        assertEquals(IntegrationEntity.IntegrationStatus.ACTIVE, e.integrationStatus);
        assertEquals(0, e.retryCount);
    }

    @Test
    @DisplayName("@Scheduled 重试：探测仍失败 → 保持 FAILED（不再被假成功洗白）")
    void retryFailedIntegrationsStaysFailedOnRealProbeFailure() {
        IntegrationEntity e = activeIntegration();
        e.integrationStatus = IntegrationEntity.IntegrationStatus.FAILED;
        e.retryCount = 1;
        when(integrationRepo.findRetryable()).thenReturn(java.util.List.of(e));
        when(integrationRepo.findById("int_1")).thenReturn(java.util.Optional.of(e));
        when(probeTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenThrow(new ResourceAccessException("no route"));

        service.retryFailedIntegrations();

        assertEquals(IntegrationEntity.IntegrationStatus.FAILED, e.integrationStatus);
        assertEquals(2, e.retryCount);
    }

    @Test
    @DisplayName("callIntegration：非 ACTIVE/禁用拒绝；成功路径真 exchange + log 落库，不动集成实体")
    void callIntegrationSuccessPath() {
        IntegrationEntity e = activeIntegration();
        e.integrationStatus = IntegrationEntity.IntegrationStatus.ACTIVE;
        when(integrationRepo.findById("int_1")).thenReturn(java.util.Optional.of(e));
        when(callTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
            .thenReturn(new ResponseEntity<>("done", HttpStatus.OK));

        e.enabled = false;
        assertThrows(IllegalStateException.class,
            () -> service.callIntegration("int_1", "evt", java.util.Map.of("k", "v"), "corr-1"));

        e.enabled = true;
        IntegrationLogEntity log = service.callIntegration("int_1", "evt", java.util.Map.of("k", "v"), "corr-1");

        assertEquals(IntegrationLogEntity.CallStatus.SUCCESS, log.callStatus);
        assertEquals(200, log.responseStatus);
        assertEquals("POST", log.httpMethod);
        assertEquals("corr-1", log.correlationId);
        assertEquals("{\"k\":\"v\"}", log.requestBody);
        // 成功路径不回写集成（retryCount 不动）——仅失败路径 save
        verify(integrationRepo, never()).save(any());
    }

    @Test
    @DisplayName("callIntegration：exchange 抛异常 → log FAILED + incrementRetry + 集成 save")
    void callIntegrationFailurePath() {
        IntegrationEntity e = activeIntegration();
        e.integrationStatus = IntegrationEntity.IntegrationStatus.ACTIVE;
        when(integrationRepo.findById("int_1")).thenReturn(java.util.Optional.of(e));
        when(callTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
            .thenThrow(new ResourceAccessException("read timeout"));

        IntegrationLogEntity log = service.callIntegration("int_1", "evt", java.util.Map.of(), "c2");

        assertEquals(IntegrationLogEntity.CallStatus.FAILED, log.callStatus);
        assertEquals("read timeout", log.errorMessage);
        assertEquals(1, e.retryCount);
        verify(integrationRepo).save(e);
    }

    @Test
    @DisplayName("buildHeaders 经 callIntegration 生效：API_KEY → X-API-Key，DB 直配 bearerToken/basicAuth → Authorization")
    void buildHeadersWiredThroughCall() {
        IntegrationEntity e = activeIntegration();
        e.integrationStatus = IntegrationEntity.IntegrationStatus.ACTIVE;
        e.authType = IntegrationEntity.AuthType.API_KEY;
        e.apiKey = "ak-123";
        when(integrationRepo.findById("int_1")).thenReturn(java.util.Optional.of(e));
        when(callTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
            .thenReturn(new ResponseEntity<>("ok", HttpStatus.OK));

        service.callIntegration("int_1", "evt", java.util.Map.of(), "c3");

        // DB 直写 bearer_token 的运维路径仍生效（API 收窄不砍发送侧分支）
        e.authType = IntegrationEntity.AuthType.BEARER_TOKEN;
        e.bearerToken = "bt-9";
        service.callIntegration("int_1", "evt", java.util.Map.of(), "c4");

        // BASIC_AUTH：username/password 直配
        e.authType = IntegrationEntity.AuthType.BASIC_AUTH;
        e.username = "u1";
        e.password = "p1";
        service.callIntegration("int_1", "evt", java.util.Map.of(), "c5");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpEntity<Object>> captor =
            ArgumentCaptor.forClass((Class<HttpEntity<Object>>) (Class<?>) HttpEntity.class);
        verify(callTemplate, times(3)).exchange(anyString(), eq(HttpMethod.POST), captor.capture(), eq(String.class));
        List<HttpEntity<Object>> all = captor.getAllValues();
        assertEquals("ak-123", all.get(0).getHeaders().getFirst("X-API-Key"));
        assertEquals("Bearer bt-9", all.get(1).getHeaders().getFirst("Authorization"));
        assertNotNull(all.get(2).getHeaders().getFirst("Authorization"));
        assertTrue(all.get(2).getHeaders().getFirst("Authorization").startsWith("Basic "));
    }

    @Test
    @DisplayName("restTemplateFor：按集成 timeoutSeconds 现建带超时模板，null 兜底 30s")
    void restTemplateForBuildsTimeoutFactory() {
        IntegrationService bare = new IntegrationService();
        IntegrationEntity e = activeIntegration();
        e.timeoutSeconds = 7;
        SimpleClientHttpRequestFactory f = (SimpleClientHttpRequestFactory) bare.restTemplateFor(e).getRequestFactory();
        // Spring 6.1 无 getter，反射读内部字段（毫秒）
        assertEquals(7000, ReflectionTestUtils.getField(f, "connectTimeout"));
        assertEquals(7000, ReflectionTestUtils.getField(f, "readTimeout"));

        e.timeoutSeconds = null;
        SimpleClientHttpRequestFactory f2 = (SimpleClientHttpRequestFactory) bare.restTemplateFor(e).getRequestFactory();
        assertEquals(30000, ReflectionTestUtils.getField(f2, "connectTimeout"));

        // 通用调用模板同样带默认超时
        RestTemplate def = (RestTemplate) ReflectionTestUtils.getField(bare, "restTemplate");
        SimpleClientHttpRequestFactory df = (SimpleClientHttpRequestFactory) def.getRequestFactory();
        assertEquals(30000, ReflectionTestUtils.getField(df, "connectTimeout"));
    }


    @Test
    @DisplayName("分支对侧：鉴权字段缺失跳过头设置；类型 null 剔除出 byType；avgDuration null 兜 0")
    void nullAuthFieldsAndStatSides() {
        IntegrationEntity e = activeIntegration();
        e.integrationStatus = IntegrationEntity.IntegrationStatus.ACTIVE;
        when(integrationRepo.findById("int_1")).thenReturn(java.util.Optional.of(e));
        when(callTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
            .thenReturn(new ResponseEntity<>("ok", HttpStatus.OK));

        // API_KEY 但 apiKey=null → 不设 X-API-Key
        e.authType = IntegrationEntity.AuthType.API_KEY;
        e.apiKey = null;
        service.callIntegration("int_1", "evt", java.util.Map.of(), "c-null-1");
        // BEARER_TOKEN 但 bearerToken=null → 不设 Authorization
        e.authType = IntegrationEntity.AuthType.BEARER_TOKEN;
        e.bearerToken = null;
        service.callIntegration("int_1", "evt", java.util.Map.of(), "c-null-2");
        // BASIC_AUTH 但 username/password=null → 不设 Authorization
        e.authType = IntegrationEntity.AuthType.BASIC_AUTH;
        e.username = null;
        e.password = null;
        service.callIntegration("int_1", "evt", java.util.Map.of(), "c-null-3");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpEntity<Object>> captor =
            ArgumentCaptor.forClass((Class<HttpEntity<Object>>) (Class<?>) HttpEntity.class);
        verify(callTemplate, times(3)).exchange(anyString(), eq(HttpMethod.POST), captor.capture(), eq(String.class));
        for (HttpEntity<Object> sent : captor.getAllValues()) {
            assertNull(sent.getHeaders().getFirst("X-API-Key"));
            assertNull(sent.getHeaders().getFirst("Authorization"));
        }

        // getIntegrationStats：integrationType null 的集成剔除出 byType（filter false 侧）
        e.integrationType = null;
        when(integrationRepo.findByGameId("g1")).thenReturn(java.util.List.of(e));
        Map<String, Object> stats = service.getIntegrationStats("g1");
        assertEquals(1L, stats.get("total"));
        assertTrue(((Map<?, ?>) stats.get("byType")).isEmpty());

        // getCallStats：averageDurationSince 返回 null → 兜 0（三元 null 侧）
        when(integrationLogRepo.countCallsSince(eq("int_1"), any())).thenReturn(0L);
        when(integrationLogRepo.countSuccessCallsSince(eq("int_1"), any())).thenReturn(0L);
        when(integrationLogRepo.countFailedCallsSince(eq("int_1"), any())).thenReturn(0L);
        when(integrationLogRepo.averageDurationSince(eq("int_1"), any())).thenReturn(null);
        assertEquals(0L, service.getCallStats("int_1", LocalDateTime.now()).get("averageDurationMs"));
    }
}
