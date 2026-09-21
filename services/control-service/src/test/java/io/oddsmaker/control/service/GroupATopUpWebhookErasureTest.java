package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.jpa.BlockListRepo;
import io.oddsmaker.control.jpa.GameEntity;
import io.oddsmaker.control.jpa.GameRepo;
import io.oddsmaker.control.jpa.IdentityEntity;
import io.oddsmaker.control.jpa.IdentityLinkEntity;
import io.oddsmaker.control.jpa.IdentityLinkRepo;
import io.oddsmaker.control.jpa.IdentityRepo;
import io.oddsmaker.control.jpa.MailClaimRepo;
import io.oddsmaker.control.jpa.MailEntity;
import io.oddsmaker.control.jpa.MailRepo;
import io.oddsmaker.control.jpa.PlayerErasureRequestEntity;
import io.oddsmaker.control.jpa.PlayerErasureRequestRepo;
import io.oddsmaker.control.jpa.PlayerExportJobRepo;
import io.oddsmaker.control.jpa.PlayerLoginLogRepo;
import io.oddsmaker.control.jpa.PlayerPaymentRepo;
import io.oddsmaker.control.jpa.RedeemRecordRepo;
import io.oddsmaker.control.jpa.ReviewQueueEntity;
import io.oddsmaker.control.jpa.ReviewQueueRepo;
import io.oddsmaker.control.jpa.RiskCaseEntity;
import io.oddsmaker.control.jpa.RiskCaseRepo;
import io.oddsmaker.control.jpa.WebhookConfigEntity;
import io.oddsmaker.control.jpa.WebhookConfigRepo;
import io.oddsmaker.control.jpa.WebhookLogEntity;
import io.oddsmaker.control.jpa.WebhookLogRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.Arrays;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BRANCH 收口·片A补充：WebhookService 与 PlayerErasureService 的未走分支侧。
 * （对侧输入构造为主；恒真防御已在 main 侧等价改写并注释论证）
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("片A分支补充：Webhook 与玩家删除")
class GroupATopUpWebhookErasureTest {

    // ===== WebhookService =====

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
    @DisplayName("sendCustomWebhook：事件类型不匹配的配置不发送（shouldSendForConfig false 侧）")
    void customWebhookEventMismatchSkips() {
        WebhookConfigEntity onlyCustom = config("g1");
        onlyCustom.eventTypes = "custom_event";
        when(webhookConfigRepo.findActiveByGameId("g1")).thenReturn(List.of(onlyCustom));

        webhookService.sendCustomWebhook("g1", "other_event", Map.of("k", "v"));

        verify(restTemplate, never()).exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class));
        verify(webhookLogRepo, never()).save(any(WebhookLogEntity.class));
    }

    @Test
    @DisplayName("processPendingRetries：空重试队列静默返回；cleanup deleted=0 不打日志")
    void pendingRetriesEmptyAndCleanupZero() {
        when(webhookLogRepo.findPendingRetries(any(LocalDateTime.class))).thenReturn(List.of());
        webhookService.processPendingRetries();   // 空列表侧

        when(webhookLogRepo.deleteExpiredLogs(any(LocalDateTime.class))).thenReturn(0);
        webhookService.cleanupExpiredLogs();      // deleted == 0 侧
        when(webhookLogRepo.deleteExpiredLogs(any(LocalDateTime.class))).thenReturn(7);
        webhookService.cleanupExpiredLogs();      // deleted > 0 侧
    }

    @Test
    @DisplayName("updateWebhookConfig：配置不存在返回 null（existing==null 侧）")
    void updateMissingConfigReturnsNull() {
        when(webhookConfigRepo.findById("wc_none")).thenReturn(Optional.empty());
        WebhookConfigEntity patch = new WebhookConfigEntity();
        patch.name = "n";
        patch.webhookUrl = "https://x.example.com/h";
        assertNull(webhookService.updateWebhookConfig("g1", "wc_none", patch, "ops"));
    }

    @Test
    @DisplayName("updateWebhookConfig：同名配置是自己时不判重名（filter false 侧）；空 patch 沿用原值")
    void updateSelfNameMatchAndEmptyPatch() {
        WebhookConfigEntity existing = config("g1");
        existing.httpMethod = "PUT";
        existing.authType = "bearer";
        existing.authConfig = "{\"token\":\"t\"}";
        existing.timeoutSeconds = 30;
        existing.maxRetries = 5;
        existing.retryBackoffMs = 500;
        existing.status = WebhookConfigEntity.WebhookStatus.ACTIVE;
        when(webhookConfigRepo.findById("wc_test")).thenReturn(Optional.of(existing));
        // 查到的同名配置就是自己（id 相同）→ filter 不通过 → 不抛
        when(webhookConfigRepo.findByGameIdAndName("g1", "slack-alerts")).thenReturn(Optional.of(existing));
        when(webhookConfigRepo.save(any(WebhookConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        // 最小 patch：只有 name/url。httpMethod/timeoutSeconds/maxRetries/retryBackoffMs
        // 有实体初始化器（POST/30/3/1000），须显式置 null 才走「沿用 existing」侧——
        // HTTP 链路下这些字段恒被 Jackson 落默认值覆盖，null 侧仅直调可达
        WebhookConfigEntity patch = new WebhookConfigEntity();
        patch.name = "slack-alerts";
        patch.webhookUrl = "https://hooks.example.com/t2";
        patch.httpMethod = null;
        patch.timeoutSeconds = null;
        patch.maxRetries = null;
        patch.retryBackoffMs = null;
        WebhookConfigEntity updated = webhookService.updateWebhookConfig("g1", "wc_test", patch, "ops");

        assertEquals("PUT", updated.httpMethod);
        assertEquals("bearer", updated.authType);
        assertEquals("{\"token\":\"t\"}", updated.authConfig);   // patch 未带 secret 保留原值
        assertEquals(Integer.valueOf(30), updated.timeoutSeconds);
        assertEquals(Integer.valueOf(5), updated.maxRetries);
        assertEquals(Integer.valueOf(500), updated.retryBackoffMs);
        assertEquals(WebhookConfigEntity.WebhookStatus.ACTIVE, updated.status);

        // patch.authConfig 为纯空白 = 留空保留（isBlank true 侧）
        WebhookConfigEntity blankSecret = new WebhookConfigEntity();
        blankSecret.name = "slack-alerts";
        blankSecret.webhookUrl = "https://hooks.example.com/t2";
        blankSecret.authConfig = "   ";
        assertEquals("{\"token\":\"t\"}",
            webhookService.updateWebhookConfig("g1", "wc_test", blankSecret, "ops").authConfig);
    }

    @Test
    @DisplayName("validateBasics：空白 name/url、http:// 前缀合法、maxRetries=-1 拒绝")
    void validateBasicsSides() {
        // 独立变量名：req 重赋值后进 assertThrows lambda 会破坏 effectively final
        WebhookConfigEntity blankName = new WebhookConfigEntity();
        blankName.name = "   ";                 // isBlank 侧
        blankName.webhookUrl = "https://x.example.com/h";
        assertThrows(IllegalArgumentException.class,
            () -> webhookService.createWebhookConfig("g1", blankName, "ops"));

        WebhookConfigEntity blankUrl = new WebhookConfigEntity();
        blankUrl.name = "n";
        blankUrl.webhookUrl = "   ";           // url isBlank 侧
        assertThrows(IllegalArgumentException.class,
            () -> webhookService.createWebhookConfig("g1", blankUrl, "ops"));

        // http://（非 https）前缀合法：第一个 startsWith 取反为 true、第二个 false → 整体 false 放行
        WebhookConfigEntity req = new WebhookConfigEntity();
        req.name = "n";
        req.webhookUrl = "http://plain.example.com/h";
        req.maxRetries = -1;              // < 0 侧
        assertThrows(IllegalArgumentException.class,
            () -> webhookService.createWebhookConfig("g1", req, "ops"));
        req.maxRetries = 3;
        when(webhookConfigRepo.findByGameIdAndName(anyString(), anyString())).thenReturn(Optional.empty());
        when(webhookConfigRepo.save(any(WebhookConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        assertNotNull(webhookService.createWebhookConfig("g1", req, "ops"));
    }

    @Test
    @DisplayName("validateAuthConfig：none 放行、bearer 空白 authConfig 拒绝、JSON null 字面量拒绝")
    void validateAuthConfigSides() {
        WebhookConfigEntity none = new WebhookConfigEntity();
        none.name = "n";
        none.webhookUrl = "https://x.example.com/h";
        none.authType = "none";           // authType=="none" 侧
        when(webhookConfigRepo.findByGameIdAndName(anyString(), anyString())).thenReturn(Optional.empty());
        when(webhookConfigRepo.save(any(WebhookConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        assertEquals("none", webhookService.createWebhookConfig("g1", none, "ops").authType);

        WebhookConfigEntity blankCfg = new WebhookConfigEntity();
        blankCfg.name = "n2";
        blankCfg.webhookUrl = "https://x.example.com/h2";
        blankCfg.authType = "bearer";
        blankCfg.authConfig = "   ";      // isBlank 侧
        assertThrows(IllegalArgumentException.class,
            () -> webhookService.createWebhookConfig("g1", blankCfg, "ops"));

        blankCfg.authConfig = "null";     // readValue 得 null Map → authMap==null 侧
        assertThrows(IllegalArgumentException.class,
            () -> webhookService.createWebhookConfig("g1", blankCfg, "ops"));
    }

    @Test
    @DisplayName("createWebhookConfig：空白 CSV 归 null（normalizeCsv isBlank 侧）")
    void createNormalizesBlankCsvToNull() {
        WebhookConfigEntity req = new WebhookConfigEntity();
        req.name = "csv-test";
        req.webhookUrl = "https://x.example.com/h";
        req.eventTypes = "   ";
        req.riskLevels = "  ";
        when(webhookConfigRepo.findByGameIdAndName(anyString(), anyString())).thenReturn(Optional.empty());
        when(webhookConfigRepo.save(any(WebhookConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        WebhookConfigEntity saved = webhookService.createWebhookConfig("g1", req, "ops");
        assertNull(saved.eventTypes);
        assertNull(saved.riskLevels);
    }

    @Test
    @DisplayName("sendTestWebhook：配置无 gameId 归属检查跳过；空响应体与超长响应体截断")
    void sendTestWebhookNullGameIdAndBodySides() {
        WebhookConfigEntity noGame = config(null);
        when(webhookConfigRepo.findById("wc_test")).thenReturn(Optional.of(noGame));
        // body 为 null（responseBody != null 的 false 侧）
        when(restTemplate.exchange(eq("https://hooks.example.com/t1"), eq(HttpMethod.POST),
                any(HttpEntity.class), eq(String.class)))
            .thenReturn(ResponseEntity.ok().build());
        Map<String, Object> result = webhookService.sendTestWebhook("wc_test", "g_any");
        assertEquals("success", result.get("status"));
        assertNull(result.get("responseBody"));

        // body 超 500 字符 → 截断（length > 500 true 侧）
        when(restTemplate.exchange(eq("https://hooks.example.com/t1"), eq(HttpMethod.POST),
                any(HttpEntity.class), eq(String.class)))
            .thenReturn(ResponseEntity.ok("x".repeat(600)));
        Map<String, Object> truncated = webhookService.sendTestWebhook("wc_test", "g_any");
        assertEquals(500, String.valueOf(truncated.get("responseBody")).length());
    }

    @Test
    @DisplayName("sendRiskCaseWebhook：config.environmentId 为空串不参与环境排除（isEmpty 侧）")
    void riskCaseWebhookEmptyConfigEnvironmentMatches() {
        WebhookConfigEntity anyEnv = config("g1");
        anyEnv.environmentId = "";          // 空串 → !isEmpty 为 false → 不排除
        anyEnv.eventTypes = "risk_case";
        when(webhookConfigRepo.findActiveByGameId("g1")).thenReturn(List.of(anyEnv));
        when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class)))
            .thenReturn(ResponseEntity.ok("ok"));

        webhookService.sendRiskCaseWebhook("g1", "env1", riskCase());

        verify(restTemplate, times(1)).exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class));
    }

    private RiskCaseEntity riskCase() {
        RiskCaseEntity rc = new RiskCaseEntity();
        rc.id = "case-1";
        rc.caseNumber = "CASE_1";
        rc.gameId = "g1";
        rc.environmentId = "env1";
        rc.targetType = "user_id";
        rc.targetId = "u1";
        rc.targetName = "Player";
        rc.triggerEventId = "e1";
        rc.triggerEventType = "login";
        rc.triggerEventName = "Login";
        rc.riskLevel = RiskCaseEntity.RiskLevel.HIGH;
        rc.riskScore = 80;
        rc.actionTaken = RiskCaseEntity.ActionType.ALERT;
        rc.actionDescription = "auto";
        rc.createdAt = LocalDateTime.now();
        return rc;
    }

    @Test
    @DisplayName("buildHeaders：basic/none/无 authConfig/无 token/key-value 组合各侧")
    void buildHeadersSides() {
        // basic case（仅 break）+ none 走 default
        WebhookConfigEntity basic = config("g1");
        basic.authType = "basic";
        HttpHeaders basicHeaders = ReflectionTestUtils.invokeMethod(webhookService, "buildHeaders", basic);
        assertNotNull(basicHeaders);

        WebhookConfigEntity noneType = config("g1");
        noneType.authType = "none";
        assertNotNull(ReflectionTestUtils.invokeMethod(webhookService, "buildHeaders", noneType));

        // bearer + authConfig 缺失（null 侧）
        WebhookConfigEntity bearerNoCfg = config("g1");
        bearerNoCfg.authType = "bearer";
        HttpHeaders bearerNoCfgHeaders = ReflectionTestUtils.invokeMethod(webhookService, "buildHeaders", bearerNoCfg);
        assertNull(bearerNoCfgHeaders.getFirst("Authorization"));

        // bearer + authConfig 无 token 字段（token == null 侧）
        WebhookConfigEntity bearerNoToken = config("g1");
        bearerNoToken.authType = "bearer";
        bearerNoToken.authConfig = "{}";
        HttpHeaders bearerNoTokenHeaders = ReflectionTestUtils.invokeMethod(webhookService, "buildHeaders", bearerNoToken);
        assertNull(bearerNoTokenHeaders.getFirst("Authorization"));

        // api_key + authConfig 缺失
        WebhookConfigEntity apiKeyNoCfg = config("g1");
        apiKeyNoCfg.authType = "api_key";
        HttpHeaders apiKeyHeaders = ReflectionTestUtils.invokeMethod(webhookService, "buildHeaders", apiKeyNoCfg);
        assertNull(apiKeyHeaders.get("X-Key"));

        // api_key：只有 key 无 value（value == null 短路侧）
        WebhookConfigEntity noValue = config("g1");
        noValue.authType = "api_key";
        noValue.authConfig = "{\"key\":\"X-Key\"}";
        HttpHeaders noValueHeaders = ReflectionTestUtils.invokeMethod(webhookService, "buildHeaders", noValue);
        assertNull(noValueHeaders.get("X-Key"));

        // api_key：只有 value 无 key（key == null 短路侧）
        WebhookConfigEntity noKey = config("g1");
        noKey.authType = "api_key";
        noKey.authConfig = "{\"value\":\"v\"}";
        HttpHeaders noKeyHeaders = ReflectionTestUtils.invokeMethod(webhookService, "buildHeaders", noKey);
        assertNull(noKeyHeaders.get("X-Key"));

        // api_key：key+value 齐 → 设置成功
        WebhookConfigEntity full = config("g1");
        full.authType = "api_key";
        full.authConfig = "{\"key\":\"X-Key\",\"value\":\"secret\"}";
        HttpHeaders fullHeaders = ReflectionTestUtils.invokeMethod(webhookService, "buildHeaders", full);
        assertEquals("secret", fullHeaders.getFirst("X-Key"));
    }

    // ===== PlayerErasureService =====

    @Mock PlayerErasureRequestRepo requestRepo;
    @Mock GameRepo gameRepo;
    @Mock IdentityRepo identityRepo;
    @Mock IdentityLinkRepo identityLinkRepo;
    @Mock PlayerLoginLogRepo loginLogRepo;
    @Mock PlayerPaymentRepo paymentRepo;
    @Mock RedeemRecordRepo redeemRecordRepo;
    @Mock MailRepo mailRepo;
    @Mock MailClaimRepo mailClaimRepo;
    @Mock PlayerExportJobRepo exportJobRepo;
    @Mock RiskCaseRepo riskCaseRepo;
    @Mock ReviewQueueRepo reviewQueueRepo;
    @Mock BlockListRepo blockListRepo;
    @Mock WebhookService webhookSvc;
    @Mock ClickHouseClient clickHouse;

    @InjectMocks
    PlayerErasureService erasureService;

    @BeforeEach
    void erasureSetUp() {
        ReflectionTestUtils.setField(erasureService, "identityExpansionLimit", 500);
        ReflectionTestUtils.setField(erasureService, "processingTimeoutMinutes", 60);
        ReflectionTestUtils.setField(erasureService, "mutationTimeoutSeconds", 0);
        ReflectionTestUtils.setField(erasureService, "mutationPollIntervalSeconds", 0);
        ReflectionTestUtils.setField(erasureService, "maxChRetries", 8);
        lenient().when(requestRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(gameRepo.findById("g1")).thenReturn(Optional.of(new GameEntity()));
        lenient().when(identityLinkRepo.findByTypeAndIdAnyStatus(anyString(), anyString())).thenReturn(List.of());
        lenient().when(identityLinkRepo.findByIdentityIdAnyStatus(anyString())).thenReturn(List.of());
        lenient().when(identityRepo.findById(anyString())).thenReturn(Optional.empty());
        lenient().when(identityRepo.findByGameIdAndPlayerIdAnyStatus(anyString(), anyString())).thenReturn(List.of());
        lenient().when(identityRepo.findByGameIdAndUserIdAnyStatus(anyString(), anyString())).thenReturn(List.of());
        lenient().when(identityRepo.findByGameIdAndDeviceIdAnyStatus(anyString(), anyString())).thenReturn(List.of());
        lenient().when(mailRepo.findIndividualByGameIdAndRecipientsContaining(anyString(), anyString()))
            .thenReturn(List.of());
        lenient().when(riskCaseRepo.findByGameIdAndEvidenceDataContaining(anyString(), anyString()))
            .thenReturn(List.of());
        lenient().when(riskCaseRepo.findByGameIdAndContextDataContaining(anyString(), anyString()))
            .thenReturn(List.of());
        lenient().when(clickHouse.isAvailable()).thenReturn(false);
    }

    private static IdentityLinkEntity link(String identityId, String type, String linkedId) {
        IdentityLinkEntity l = new IdentityLinkEntity();
        l.identityId = identityId;
        l.linkedIdentityType = type;
        l.linkedId = linkedId;
        return l;
    }

    @Test
    @DisplayName("创建：requestValue null 与空白均拒绝")
    void createRejectsNullAndBlankValue() {
        assertThrows(IllegalArgumentException.class,
            () -> erasureService.create("g1", "PLAYER_ID", null, null, "op"));
        assertThrows(IllegalArgumentException.class,
            () -> erasureService.create("g1", "PLAYER_ID", "   ", null, "op"));
    }

    @Test
    @DisplayName("sweep：scheduledFor 已到期的 PENDING 被处理（isAfter false 侧）")
    void sweepProcessesDuePendingRequest() {
        PlayerErasureRequestEntity req = new PlayerErasureRequestEntity();
        req.id = "per_due";
        req.gameId = "g1";
        req.status = PlayerErasureRequestEntity.Status.PENDING;
        req.requestType = PlayerErasureRequestEntity.RequestType.PLAYER_ID;
        req.requestValue = "p1";
        req.scheduledFor = LocalDateTime.now().minusMinutes(5);   // 已到期 → 不跳过
        req.resolvedIdentities = "{\"inputType\":\"player_id\",\"inputValue\":\"p1\",\"gameId\":\"g1\","
            + "\"identityIds\":[],\"userIds\":[],\"playerIds\":[\"p1\"],"
            + "\"deviceIds\":[],\"characterIds\":[],\"truncated\":false}";
        when(requestRepo.findStaleProcessing(any(LocalDateTime.class))).thenReturn(List.of());
        when(requestRepo.findByStatusOrderByCreatedAtAsc(PlayerErasureRequestEntity.Status.PENDING))
            .thenReturn(List.of(req));
        when(requestRepo.findByStatus(PlayerErasureRequestEntity.Status.PARTIAL)).thenReturn(List.of());
        when(requestRepo.findById("per_due")).thenReturn(Optional.of(req));
        // PG 段各删除 repo
        lenient().when(exportJobRepo.findByGameIdAndPlayerIdIn(anyString(), anyList())).thenReturn(List.of());
        lenient().when(riskCaseRepo.findByGameIdAndTargetIdIn(anyString(), anyList())).thenReturn(List.of());
        lenient().when(reviewQueueRepo.findByGameIdAndTargetIdIn(anyString(), anyList())).thenReturn(List.of());
        lenient().when(blockListRepo.findByGameIdAndTargetValueInAndTargetTypeIn(anyString(), anyList(), anyList()))
            .thenReturn(List.of());

        erasureService.sweep();

        assertEquals(PlayerErasureRequestEntity.Status.COMPLETED, req.status);  // CH 不可用 → SKIPPED → COMPLETED
    }

    @Test
    @DisplayName("展开：character_id 入桶；identity 字段空白不入队")
    void resolveCharacterIdBucketAndBlankField() {
        IdentityEntity idt = new IdentityEntity();
        idt.id = "idt1";
        idt.gameId = "g1";
        idt.characterId = "c1";
        idt.userId = "   ";   // blank → addId 跳过
        when(identityRepo.findById("idt1")).thenReturn(Optional.of(idt));
        when(identityLinkRepo.findByTypeAndIdAnyStatus("player_id", "p1"))
            .thenReturn(List.of(link("idt1", "character_id", "c1")));

        PlayerErasureService.ResolvedIdentities resolved =
            erasureService.resolve("g1", PlayerErasureRequestEntity.RequestType.PLAYER_ID, "p1", 500);

        assertTrue(resolved.characterIds().contains("c1"));
        assertTrue(resolved.userIds().isEmpty());   // 空白 userId 未入队
    }

    @Test
    @DisplayName("展开：多条 link 命中同一 identity 只展开一次（identityIds.add false 侧）")
    void resolveDuplicateIdentityViaLinksExpandsOnce() {
        IdentityEntity idt = new IdentityEntity();
        idt.id = "idt1";
        idt.gameId = "g1";
        when(identityRepo.findById("idt1")).thenReturn(Optional.of(idt));
        when(identityLinkRepo.findByTypeAndIdAnyStatus("player_id", "p1")).thenReturn(List.of(
            link("idt1", "user_id", "u1"),
            link("idt1", "device_id", "d1")));   // 两条 link 同 identityId → 第二次 add false

        erasureService.resolve("g1", PlayerErasureRequestEntity.RequestType.PLAYER_ID, "p1", 500);

        verify(identityRepo, times(1)).findById("idt1");   // 只展开一次
    }

    @Test
    @DisplayName("展开：direct 命中已被 link 发现的 identity 不重复展开（add false 侧）")
    void resolveDirectDuplicateIdentitySkipsExpand() {
        IdentityEntity idt = new IdentityEntity();
        idt.id = "idt1";
        idt.gameId = "g1";
        when(identityRepo.findById("idt1")).thenReturn(Optional.of(idt));
        when(identityLinkRepo.findByTypeAndIdAnyStatus("player_id", "p1"))
            .thenReturn(List.of(link("idt1", "user_id", "u1")));
        when(identityRepo.findByGameIdAndPlayerIdAnyStatus("g1", "p1")).thenReturn(List.of(idt));   // direct 同一 identity

        erasureService.resolve("g1", PlayerErasureRequestEntity.RequestType.PLAYER_ID, "p1", 500);

        verify(identityRepo, times(1)).findById("idt1");
    }

    @Test
    @DisplayName("展开：expandIdentity 遍历的 link 其 linkedId 为 null 与空白均安全跳过不入队")
    void resolveSkipsNullOrBlankLinkedId() {
        // 顶层 link（有效）触发 expandIdentity(idt1)；idt1 名下两条 link 的 linkedId 分别为 null/空白 → 跳过
        when(identityLinkRepo.findByTypeAndIdAnyStatus("player_id", "p1"))
            .thenReturn(List.of(link("idt1", "user_id", "u1")));
        when(identityLinkRepo.findByIdentityIdAnyStatus("idt1")).thenReturn(List.of(
            link("idt1", "user_id", null),
            link("idt2", "user_id", "   ")));

        PlayerErasureService.ResolvedIdentities resolved =
            erasureService.resolve("g1", PlayerErasureRequestEntity.RequestType.PLAYER_ID, "p1", 500);

        assertTrue(resolved.userIds().isEmpty());               // null/blank linkedId 未入队
        assertFalse(resolved.identityIds().contains("idt2"));   // 空白 link 不会引出 idt2
    }

    private PlayerErasureService.ResolvedIdentities ids(List<String> userIds) {
        return new PlayerErasureService.ResolvedIdentities("user_id", "u1", "g1",
            List.of(), userIds, List.of(), List.of(), List.of(), false);
    }

    @Test
    @DisplayName("PG 清洗：LIKE 命中但 token 不在集合 → 摘除结果与原文相同不落库")
    void purgeSkipsUnchangedMail() {
        // playerKeys=[p1]，mail.recipients="p12" → LIKE 'p1' 命中但摘除后不变
        PlayerErasureService.ResolvedIdentities one = new PlayerErasureService.ResolvedIdentities(
            "player_id", "p1", "g1", List.of(), List.of(), List.of("p1"), List.of(), List.of(), false);
        MailEntity mail = new MailEntity();
        mail.recipients = "p12";
        when(mailRepo.findIndividualByGameIdAndRecipientsContaining("g1", "p1")).thenReturn(List.of(mail));
        lenient().when(exportJobRepo.findByGameIdAndPlayerIdIn(anyString(), anyList())).thenReturn(List.of());

        erasureService.purgePostgres("g1", one, "per_x");

        verify(mailRepo, never()).save(any(MailEntity.class));   // 无变化不落库
    }

    @Test
    @DisplayName("PG 清洗：标识超过 50 个时跳过 evidence/context 的 LIKE 扫描")
    void purgeOversizeLikeScanSkipped() {
        List<String> many = new java.util.ArrayList<>();
        for (int i = 0; i < 51; i++) {
            many.add("u" + i);
        }
        lenient().when(exportJobRepo.findByGameIdAndPlayerIdIn(anyString(), anyList())).thenReturn(List.of());
        lenient().when(riskCaseRepo.findByGameIdAndTargetIdIn(anyString(), anyList())).thenReturn(List.of());

        erasureService.purgePostgres("g1", ids(many), "per_y");

        verify(riskCaseRepo, never()).findByGameIdAndEvidenceDataContaining(anyString(), anyString());
        verify(riskCaseRepo, never()).findByGameIdAndContextDataContaining(anyString(), anyString());
    }

    @Test
    @DisplayName("PG 清洗：targetName/evidenceData/contextData 各自单独变化时均落库（|| 链各侧）")
    void anonymizeRiskCasesEachFieldAloneTriggersSave() {
        List<String> all = List.of("u1");
        lenient().when(exportJobRepo.findByGameIdAndPlayerIdIn(anyString(), anyList())).thenReturn(List.of());

        for (int variant = 0; variant < 3; variant++) {
            RiskCaseEntity rc = new RiskCaseEntity();
            rc.id = "rc_" + variant;
            rc.gameId = "g1";
            rc.targetId = "keep";
            rc.targetName = variant == 0 ? "u1" : "keep";
            rc.evidenceData = variant == 1 ? "u1" : "keep";
            rc.contextData = variant == 2 ? "u1" : "keep";
            when(riskCaseRepo.findByGameIdAndTargetIdIn("g1", all)).thenReturn(List.of(rc));
            lenient().when(riskCaseRepo.findByGameIdAndEvidenceDataContaining(anyString(), anyString()))
                .thenReturn(List.of());
            lenient().when(riskCaseRepo.findByGameIdAndContextDataContaining(anyString(), anyString()))
                .thenReturn(List.of());
            lenient().when(reviewQueueRepo.findByGameIdAndTargetIdIn(anyString(), anyList())).thenReturn(List.of());
            lenient().when(blockListRepo.findByGameIdAndTargetValueInAndTargetTypeIn(anyString(), anyList(), anyList()))
                .thenReturn(List.of());

            Map<String, Long> pg = erasureService.purgePostgres("g1", ids(all), "per_z" + variant);

            assertEquals(1L, pg.get("risk_cases_anonymized"));   // 每种单独变化都算一次匿名化
            assertEquals(variant == 0 ? "erased:per_z" + variant : "keep", rc.targetName);
            assertEquals("keep", rc.targetId);                    // "keep" 不在标识集合，永不替换
        }
    }

    @Test
    @DisplayName("PG 清洗：审核队列 targetName 为 null 时仅匿名 targetId（null 侧）")
    void anonymizeReviewQueueNullTargetName() {
        ReviewQueueEntity rq = new ReviewQueueEntity();
        rq.gameId = "g1";
        rq.targetId = "u1";
        rq.targetName = null;
        when(reviewQueueRepo.findByGameIdAndTargetIdIn(anyString(), anyList())).thenReturn(List.of(rq));
        lenient().when(exportJobRepo.findByGameIdAndPlayerIdIn(anyString(), anyList())).thenReturn(List.of());
        lenient().when(riskCaseRepo.findByGameIdAndTargetIdIn(anyString(), anyList())).thenReturn(List.of());

        Map<String, Long> pg = erasureService.purgePostgres("g1", ids(List.of("u1")), "per_r");

        assertEquals(1L, pg.get("review_queues_anonymized"));
        assertEquals("erased:per_r", rq.targetId);
        assertNull(rq.targetName);
    }

    @Test
    @DisplayName("CH 预估/基线计数非数字：按 0 处理不失败（instanceof false 侧）")
    void chNonNumericCountsTolerated() {
        when(clickHouse.isAvailable()).thenReturn(true);
        // 基线查询与预估查询都返回非 Number 的 c
        when(clickHouse.query(anyString())).thenReturn(List.of(Map.of("c", "not-a-number")));
        when(clickHouse.execute(any())).thenReturn(null);

        PlayerErasureService.ResolvedIdentities one = new PlayerErasureService.ResolvedIdentities(
            "player_id", "p1", "g1", List.of(), List.of(), List.of("p1"), List.of(), List.of(), false);
        Map<String, Object> ch = erasureService.purgeClickHouse("g1", one, "per_c");

        assertEquals("EXECUTED", ch.get("status"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> mutations = (List<Map<String, Object>>) ch.get("mutations");
        assertEquals(0L, mutations.get(0).get("estimate"));   // 非 Number → 0
    }

    @Test
    @DisplayName("辅助静态方法对侧：parseSummary null/blank、parseType null/blank、removeRecipient 空 token、replaceAll 空标识、inList null、escape null、longOf 非数字")
    void helperStaticSides() {
        // parseSummary（私有静态，经反射直调）
        Map<String, Object> empty = ReflectionTestUtils.invokeMethod(erasureService, "parseSummary", (Object) null);
        assertTrue(empty.isEmpty());
        Map<String, Object> blank = ReflectionTestUtils.invokeMethod(erasureService, "parseSummary", "   ");
        assertTrue(blank.isEmpty());

        // parseType null / blank → IAE（经公开 create 触发，避免反射包装异常类型）
        assertThrows(IllegalArgumentException.class,
            () -> erasureService.create("g1", null, "v1", null, "op"));
        assertThrows(IllegalArgumentException.class,
            () -> erasureService.create("g1", "   ", "v1", null, "op"));

        // removeRecipient：空 token（",,"）被跳过
        assertEquals("p2", PlayerErasureService.removeRecipient("p1,,p2", java.util.Set.of("p1")));

        // replaceAll：集合中的 null 与空白标识跳过，合法标识替换
        assertEquals("PH", PlayerErasureService.replaceAll("x", Arrays.asList(null, "   ", "x"), "PH"));
        assertEquals(null, PlayerErasureService.replaceAll(null, List.of("x"), "PH"));

        // inList：null 集合 → 空集换占位值（避免 IN () 语法错）
        assertEquals("('')", PlayerErasureService.inList(null));

        // escape(null) → ""（私有静态）
        assertEquals("", ReflectionTestUtils.invokeMethod(erasureService, "escape", (Object) null));

        // longOf 非 Number → 0（私有静态）
        Object longOfResult = ReflectionTestUtils.invokeMethod(erasureService, "longOf", (Object) "str");
        assertEquals(Long.valueOf(0L), longOfResult);
    }
}
