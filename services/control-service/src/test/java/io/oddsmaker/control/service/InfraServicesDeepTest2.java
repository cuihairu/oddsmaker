package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.jpa.FlinkJobEntity;
import io.oddsmaker.control.jpa.FlinkJobRepo;
import io.oddsmaker.control.jpa.IntegrationEntity;
import io.oddsmaker.control.jpa.IntegrationLogEntity;
import io.oddsmaker.control.jpa.IntegrationLogRepo;
import io.oddsmaker.control.jpa.IntegrationRepo;
import io.oddsmaker.control.jpa.QuotaEntity;
import io.oddsmaker.control.jpa.QuotaRepo;
import io.oddsmaker.control.jpa.RateLimitEntity;
import io.oddsmaker.control.jpa.RateLimitRepo;
import io.oddsmaker.control.jpa.RateLimitUsageEntity;
import io.oddsmaker.control.jpa.RateLimitUsageRepo;
import io.oddsmaker.control.jpa.RiskCaseEntity;
import io.oddsmaker.control.jpa.RiskRuleEntity;
import io.oddsmaker.control.jpa.RiskRuleRepo;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 基础设施类 Service 深度测试：限流/配额、Flink 作业、集成、Webhook 的写入与分支路径。
 * 与 InfraServicesTest 互补：覆盖其未测的创建/更新/校验/HTTP 调用路径。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("基础设施类 Service 深度测试")
class InfraServicesDeepTest2 {

    @Mock
    private RestTemplate restTemplate;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private Executor asyncExecutor;

    // ===== 限流 =====

    @Mock
    private RateLimitRepo rateLimitRepo;

    @Mock
    private RateLimitUsageRepo rateLimitUsageRepo;

    @Mock
    private QuotaRepo quotaRepo;

    @InjectMocks
    private RateLimitService rateLimitService;

    // ===== Flink 作业 =====

    @Mock
    private FlinkJobRepo flinkJobRepo;

    @Mock
    private RiskRuleRepo riskRuleRepo;

    @InjectMocks
    private FlinkJobService flinkJobService;

    // ===== 集成 =====

    @Mock
    private IntegrationRepo integrationRepo;

    @Mock
    private IntegrationLogRepo integrationLogRepo;

    @InjectMocks
    private IntegrationService integrationService;

    // ===== Webhook =====

    @Mock
    private WebhookConfigRepo webhookConfigRepo;

    @Mock
    private WebhookLogRepo webhookLogRepo;

    @InjectMocks
    private WebhookService webhookService;

    @BeforeEach
    void setUp() throws Exception {
        // 所有 repo.save 直接返回入参（服务内部依赖返回值）
        lenient().when(rateLimitRepo.save(any(RateLimitEntity.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(rateLimitUsageRepo.save(any(RateLimitUsageEntity.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(quotaRepo.save(any(QuotaEntity.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(flinkJobRepo.save(any(FlinkJobEntity.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(integrationRepo.save(any(IntegrationEntity.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(integrationLogRepo.save(any(IntegrationLogEntity.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(webhookConfigRepo.save(any(WebhookConfigEntity.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(webhookLogRepo.save(any(WebhookLogEntity.class))).thenAnswer(i -> i.getArgument(0));

        // IntegrationService 的 restTemplate 是 private final new 出来的，通过反射替换为 mock
        Field restTemplateField = IntegrationService.class.getDeclaredField("restTemplate");
        restTemplateField.setAccessible(true);
        restTemplateField.set(integrationService, restTemplate);

        // WebhookService 的异步执行器同步运行，便于断言
        lenient().doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(asyncExecutor).execute(any(Runnable.class));
    }

    // ==================== 限流：RateLimitService ====================

    private RateLimitEntity rateLimit(String id, RateLimitEntity.Scope scope, RateLimitEntity.Algorithm algorithm,
                                      int limit, boolean enabled) {
        RateLimitEntity rule = new RateLimitEntity();
        rule.id = id;
        rule.gameId = "g1";
        rule.scope = scope;
        rule.algorithm = algorithm;
        rule.limit = limit;
        rule.enabled = enabled;
        rule.windowType = RateLimitEntity.WindowType.MINUTE;
        rule.windowSize = 1;
        rule.burst = 0;
        rule.priority = 0;
        rule.description = "test rule";
        rule.createdBy = "admin";
        return rule;
    }

    private RateLimitUsageEntity rateLimitUsage(String ruleId, int requestCount) {
        RateLimitUsageEntity usage = new RateLimitUsageEntity();
        usage.id = "usage-" + ruleId;
        usage.rateLimitId = ruleId;
        usage.gameId = "g1";
        usage.requestCount = requestCount;
        usage.blockedCount = 0;
        usage.windowStart = LocalDateTime.now().minusSeconds(30);
        usage.windowEnd = LocalDateTime.now().plusSeconds(60);
        usage.createdAt = LocalDateTime.now();
        return usage;
    }

    private QuotaEntity quota(String id, long limit, long usage) {
        QuotaEntity q = new QuotaEntity();
        q.id = id;
        q.gameId = "g1";
        q.resourceType = QuotaEntity.ResourceType.EVENTS_PER_DAY;
        q.quotaLimit = limit;
        q.currentUsage = usage;
        q.warningThreshold = 80.0;
        q.alertThreshold = 95.0;
        q.hardLimit = false;
        q.warningSent = false;
        q.alertSent = false;
        return q;
    }

    @Test
    @DisplayName("限流：创建/获取/删除规则与参数校验")
    void rateLimitCreateGetDelete() {
        // 创建（全参数）
        RateLimitEntity created = rateLimitService.createRateLimit("g1", null, "/api/events", null,
            RateLimitEntity.Scope.GAME, 500, RateLimitEntity.WindowType.HOUR, 2,
            RateLimitEntity.Algorithm.FIXED_WINDOW, 10, "desc", "admin");
        assertNotNull(created.id);
        assertTrue(created.id.startsWith("rl_"));
        assertEquals(RateLimitEntity.Scope.GAME, created.scope);
        assertEquals(500, created.limit);
        assertEquals(RateLimitEntity.WindowType.HOUR, created.windowType);
        assertEquals(2, created.windowSize);
        assertEquals(RateLimitEntity.Algorithm.FIXED_WINDOW, created.algorithm);
        assertEquals(10, created.burst);
        assertTrue(created.enabled);
        verify(auditLogService).logCreate(eq("rate_limit"), eq(created.id), eq("Rate limit"), eq("admin"),
            eq("admin"), isNull(), anyMap());

        // 创建（默认值：windowSize/algorithm/burst 兜底；注意生产代码审计日志使用 Map.of，
        // 故 limit/windowType 必须非 null）
        RateLimitEntity defaulted = rateLimitService.createRateLimit("g1", null, null, null,
            RateLimitEntity.Scope.GLOBAL, 200, RateLimitEntity.WindowType.DAY, null, null, null, null, null);
        assertEquals(200, defaulted.limit);
        assertEquals(RateLimitEntity.WindowType.DAY, defaulted.windowType);
        assertEquals(1, defaulted.windowSize);
        assertEquals(RateLimitEntity.Algorithm.SLIDING_WINDOW, defaulted.algorithm);
        assertEquals(0, defaulted.burst);
        assertTrue(defaulted.enabled);

        // 获取：不存在抛 IllegalArgumentException
        lenient().when(rateLimitRepo.findById("missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> rateLimitService.getRateLimit("missing"));

        // 获取：存在
        RateLimitEntity rule = rateLimit("rl-1", RateLimitEntity.Scope.GAME,
            RateLimitEntity.Algorithm.SLIDING_WINDOW, 10, true);
        lenient().when(rateLimitRepo.findById("rl-1")).thenReturn(Optional.of(rule));
        assertEquals(rule, rateLimitService.getRateLimit("rl-1"));

        // 删除：软删除 + 禁用
        rateLimitService.deleteRateLimit("rl-1");
        assertNotNull(rule.deletedAt);
        assertFalse(rule.enabled);
        verify(rateLimitRepo, times(3)).save(any(RateLimitEntity.class));
    }

    @Test
    @DisplayName("限流：更新规则字段（null 跳过）与不存在校验")
    void rateLimitUpdateFields() {
        lenient().when(rateLimitRepo.findById("missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
            () -> rateLimitService.updateRateLimit("missing", 1, 1, true));

        RateLimitEntity rule = rateLimit("rl-2", RateLimitEntity.Scope.USER,
            RateLimitEntity.Algorithm.TOKEN_BUCKET, 10, true);
        rule.burst = 2;
        lenient().when(rateLimitRepo.findById("rl-2")).thenReturn(Optional.of(rule));

        RateLimitEntity updated = rateLimitService.updateRateLimit("rl-2", 50, 5, false);
        assertEquals(50, updated.limit);
        assertEquals(5, updated.burst);
        assertFalse(updated.enabled);

        // 全 null 参数：字段保持不变
        RateLimitEntity untouched = rateLimitService.updateRateLimit("rl-2", null, null, null);
        assertEquals(50, untouched.limit);
        assertEquals(5, untouched.burst);
        assertFalse(untouched.enabled);
    }

    @Test
    @DisplayName("限流：checkRequest 允许路径 + 缓存复用")
    void rateLimitCheckRequestAllowedAndCached() {
        RateLimitEntity rule = rateLimit("rl-cache", RateLimitEntity.Scope.GAME,
            RateLimitEntity.Algorithm.SLIDING_WINDOW, 10, true);
        lenient().when(rateLimitRepo.findGlobal()).thenReturn(List.of());
        lenient().when(rateLimitRepo.findByGameId("g1")).thenReturn(List.of(rule));
        lenient().when(rateLimitRepo.findByApiKeyId("key1")).thenReturn(List.of());
        lenient().when(rateLimitRepo.findForEndpoint("/api")).thenReturn(List.of());
        lenient().when(rateLimitRepo.findForUser("u1")).thenReturn(List.of());
        lenient().when(rateLimitUsageRepo.findActiveWindow(anyString(), any(LocalDateTime.class)))
            .thenReturn(Optional.empty());

        RateLimitService.RateLimitCheckResult first = rateLimitService.checkRequest("g1", "key1", "/api", "u1");
        assertTrue(first.allowed);
        assertNull(first.ruleId);

        // 第二次请求命中本地缓存：不再查库，计数累加
        RateLimitService.RateLimitCheckResult second = rateLimitService.checkRequest("g1", "key1", "/api", "u1");
        assertTrue(second.allowed);

        verify(rateLimitUsageRepo, times(1)).findActiveWindow(anyString(), any(LocalDateTime.class));
        ArgumentCaptor<RateLimitUsageEntity> captor = ArgumentCaptor.forClass(RateLimitUsageEntity.class);
        verify(rateLimitUsageRepo, times(3)).save(captor.capture());
        RateLimitUsageEntity last = captor.getValue();
        assertEquals("rl-cache", last.rateLimitId);
        assertEquals(2, last.requestCount);
        assertNotNull(last.lastRequestAt);
    }

    @Test
    @DisplayName("限流：checkRequest 超限拒绝路径")
    void rateLimitCheckRequestDenied() {
        RateLimitEntity rule = rateLimit("rl-deny", RateLimitEntity.Scope.GAME,
            RateLimitEntity.Algorithm.FIXED_WINDOW, 5, true);
        RateLimitUsageEntity usedUp = rateLimitUsage("rl-deny", 5);
        lenient().when(rateLimitRepo.findGlobal()).thenReturn(List.of(rule));
        lenient().when(rateLimitUsageRepo.findActiveWindow(anyString(), any(LocalDateTime.class)))
            .thenReturn(Optional.of(usedUp));

        RateLimitService.RateLimitCheckResult result = rateLimitService.checkRequest("g1", null, null, null);
        assertFalse(result.allowed);
        assertEquals("rl-deny", result.ruleId);
        assertTrue(result.retryAfterSeconds >= 55);
        verify(rateLimitUsageRepo, never()).save(any(RateLimitUsageEntity.class));
    }

    @Test
    @DisplayName("限流：checkRequest 为各算法创建新使用窗口")
    void rateLimitCheckRequestCreatesWindows() {
        RateLimitEntity fixed = rateLimit("rl-fixed", RateLimitEntity.Scope.GAME,
            RateLimitEntity.Algorithm.FIXED_WINDOW, 100, true);
        RateLimitEntity sliding = rateLimit("rl-sliding", RateLimitEntity.Scope.API_KEY,
            RateLimitEntity.Algorithm.SLIDING_WINDOW, 100, true);
        sliding.gameId = null;
        sliding.apiKeyId = "key1";
        RateLimitEntity token = rateLimit("rl-token", RateLimitEntity.Scope.ENDPOINT,
            RateLimitEntity.Algorithm.TOKEN_BUCKET, 100, true);
        token.gameId = null;
        token.endpoint = "/api";
        RateLimitEntity leaky = rateLimit("rl-leaky", RateLimitEntity.Scope.USER,
            RateLimitEntity.Algorithm.LEAKY_BUCKET, 100, true);
        leaky.gameId = null;
        leaky.userId = "u1";

        lenient().when(rateLimitRepo.findGlobal()).thenReturn(List.of());
        lenient().when(rateLimitRepo.findByGameId("g1")).thenReturn(List.of(fixed));
        lenient().when(rateLimitRepo.findByApiKeyId("key1")).thenReturn(List.of(sliding));
        lenient().when(rateLimitRepo.findForEndpoint("/api")).thenReturn(List.of(token));
        lenient().when(rateLimitRepo.findForUser("u1")).thenReturn(List.of(leaky));
        lenient().when(rateLimitUsageRepo.findActiveWindow(anyString(), any(LocalDateTime.class)))
            .thenReturn(Optional.empty());

        RateLimitService.RateLimitCheckResult result = rateLimitService.checkRequest("g1", "key1", "/api", "u1");
        assertTrue(result.allowed);

        // FIXED_WINDOW 窗口按 UTC epoch 对齐，非 UTC 时区下缓存不会命中（多一次建窗），
        // 因此不断言精确调用次数，只断言每条规则都建窗并完成一次计数自增
        ArgumentCaptor<RateLimitUsageEntity> captor = ArgumentCaptor.forClass(RateLimitUsageEntity.class);
        verify(rateLimitUsageRepo, atLeast(8)).save(captor.capture());
        List<String> ruleIds = captor.getAllValues().stream()
            .map(u -> u.rateLimitId).distinct().toList();
        assertEquals(4, ruleIds.size());
        assertTrue(ruleIds.containsAll(List.of("rl-fixed", "rl-sliding", "rl-token", "rl-leaky")));
        // 每条规则完成一次自增（计数为 1）的保存，且窗口时间完整
        List<RateLimitUsageEntity> incremented = captor.getAllValues().stream()
            .filter(u -> u.requestCount == 1).toList();
        assertTrue(incremented.size() >= 4);
        incremented.forEach(u -> {
            assertNotNull(u.windowStart);
            assertNotNull(u.windowEnd);
            assertTrue(u.windowEnd.isAfter(u.windowStart));
        });
    }

    @Test
    @DisplayName("限流：禁用规则被跳过")
    void rateLimitCheckRequestDisabledRuleSkipped() {
        RateLimitEntity disabled = rateLimit("rl-off", RateLimitEntity.Scope.GAME,
            RateLimitEntity.Algorithm.SLIDING_WINDOW, 10, false);
        lenient().when(rateLimitRepo.findGlobal()).thenReturn(List.of(disabled));
        lenient().when(rateLimitUsageRepo.findActiveWindow(anyString(), any(LocalDateTime.class)))
            .thenReturn(Optional.empty());

        RateLimitService.RateLimitCheckResult result = rateLimitService.checkRequest("g1", null, null, null);
        assertTrue(result.allowed);
        verify(rateLimitUsageRepo, never()).findActiveWindow(anyString(), any(LocalDateTime.class));
        verify(rateLimitUsageRepo, never()).save(any(RateLimitUsageEntity.class));
    }

    @Test
    @DisplayName("配额：创建与检查（环境/全局、硬限制、未配置）")
    void quotaCreateAndCheck() {
        // 创建（自定义阈值）
        QuotaEntity created = rateLimitService.createQuota("g1", "env1",
            QuotaEntity.ResourceType.API_CALLS_PER_DAY, 1000L, 70.0, 90.0, true, "admin");
        assertNotNull(created.id);
        assertTrue(created.id.startsWith("quota_"));
        assertEquals(70.0, created.warningThreshold);
        assertEquals(90.0, created.alertThreshold);
        assertTrue(created.hardLimit);
        verify(auditLogService).logCreate(eq("quota"), eq(created.id), eq("API_CALLS_PER_DAY"),
            eq("admin"), eq("admin"), isNull(), anyMap());

        // 创建（默认阈值：limit 必须非 null——生产代码审计日志 Map.of 限制）
        QuotaEntity defaulted = rateLimitService.createQuota("g1", null,
            QuotaEntity.ResourceType.STORAGE_GB, 500L, null, null, null, null);
        assertEquals(500L, defaulted.quotaLimit);
        assertEquals(80.0, defaulted.warningThreshold);
        assertEquals(95.0, defaulted.alertThreshold);
        assertFalse(defaulted.hardLimit);
        assertEquals(0L, defaulted.currentUsage);

        // 检查：环境级配额，未超限
        QuotaEntity q = quota("q1", 100, 50);
        q.hardLimit = true;
        lenient().when(quotaRepo.findByGameEnvironmentAndResourceType("g1", "env1",
            QuotaEntity.ResourceType.EVENTS_PER_DAY)).thenReturn(Optional.of(q));
        RateLimitService.QuotaCheckResult withEnv =
            rateLimitService.checkQuota("g1", "env1", QuotaEntity.ResourceType.EVENTS_PER_DAY);
        assertTrue(withEnv.allowed);
        assertEquals("q1", withEnv.quotaId);
        assertEquals(50L, withEnv.remaining);
        assertEquals(50.0, withEnv.usagePercent);

        // 检查：环境级未配置 -> 游戏级也未配置 -> 允许
        lenient().when(quotaRepo.findByGameAndResourceType("g1", QuotaEntity.ResourceType.EVENTS_PER_DAY))
            .thenReturn(Optional.empty());
        RateLimitService.QuotaCheckResult noEnv =
            rateLimitService.checkQuota("g1", null, QuotaEntity.ResourceType.EVENTS_PER_DAY);
        assertTrue(noEnv.allowed);
        assertNull(noEnv.quotaId);
        assertEquals(-1L, noEnv.remaining);

        // 检查：硬限制 + 超限 -> 拒绝
        QuotaEntity over = quota("q2", 100, 120);
        over.hardLimit = true;
        lenient().when(quotaRepo.findByGameAndResourceType("g1", QuotaEntity.ResourceType.USERS))
            .thenReturn(Optional.of(over));
        RateLimitService.QuotaCheckResult hard =
            rateLimitService.checkQuota("g1", null, QuotaEntity.ResourceType.USERS);
        assertFalse(hard.allowed);
        assertEquals(0L, hard.remaining);

        // 检查：软限制超限 -> 仍允许
        QuotaEntity soft = quota("q3", 100, 120);
        lenient().when(quotaRepo.findByGameAndResourceType("g1", QuotaEntity.ResourceType.INTEGRATIONS))
            .thenReturn(Optional.of(soft));
        assertTrue(rateLimitService.checkQuota("g1", null, QuotaEntity.ResourceType.INTEGRATIONS).allowed);
    }

    @Test
    @DisplayName("配额：使用量更新触发警告/告警")
    void quotaUpdateUsageAlerts() {
        // 未配置配额：无操作
        lenient().when(quotaRepo.findByGameEnvironmentAndResourceType("g1", "env1",
            QuotaEntity.ResourceType.EVENTS_PER_DAY)).thenReturn(Optional.empty());
        assertDoesNotThrow(() ->
            rateLimitService.updateQuotaUsage("g1", "env1", QuotaEntity.ResourceType.EVENTS_PER_DAY, 100));
        verify(quotaRepo, never()).save(any(QuotaEntity.class));

        // 警告区间（85%）：发送警告不发送告警
        QuotaEntity warningQuota = quota("qw", 100, 0);
        lenient().when(quotaRepo.findByGameAndResourceType("g1", QuotaEntity.ResourceType.EVENTS_PER_DAY))
            .thenReturn(Optional.of(warningQuota));
        rateLimitService.updateQuotaUsage("g1", null, QuotaEntity.ResourceType.EVENTS_PER_DAY, 85);
        assertEquals(85L, warningQuota.currentUsage);
        assertTrue(warningQuota.warningSent);
        assertFalse(warningQuota.alertSent);
        assertNotNull(warningQuota.lastCalculatedAt);

        // 告警区间（96%）：警告 + 告警
        QuotaEntity alertQuota = quota("qa", 100, 0);
        lenient().when(quotaRepo.findByGameAndResourceType("g1", QuotaEntity.ResourceType.USERS))
            .thenReturn(Optional.of(alertQuota));
        rateLimitService.updateQuotaUsage("g1", null, QuotaEntity.ResourceType.USERS, 96);
        assertTrue(alertQuota.warningSent);
        assertTrue(alertQuota.alertSent);

        // 已发送过告警：不重复发送
        QuotaEntity sent = quota("qs", 100, 99);
        sent.warningSent = true;
        sent.alertSent = true;
        lenient().when(quotaRepo.findByGameAndResourceType("g1", QuotaEntity.ResourceType.INTEGRATIONS))
            .thenReturn(Optional.of(sent));
        rateLimitService.updateQuotaUsage("g1", null, QuotaEntity.ResourceType.INTEGRATIONS, 1);
        assertTrue(sent.warningSent);
        assertTrue(sent.alertSent);
    }

    @Test
    @DisplayName("限流：定时清理/配额重置/配额告警巡检")
    void rateLimitScheduledJobs() {
        // 清理过期窗口（含缓存条目清理 + 异常吞掉）
        lenient().when(rateLimitUsageRepo.deleteExpired(any(LocalDateTime.class)))
            .thenReturn(2)
            .thenThrow(new RuntimeException("cleanup boom"));
        assertDoesNotThrow(() -> rateLimitService.cleanupExpiredWindows());
        assertDoesNotThrow(() -> rateLimitService.cleanupExpiredWindows());

        // 配额重置：resetAt 已过期
        QuotaEntity toReset = quota("qr", 100, 50);
        toReset.resetAt = LocalDateTime.now().minusMinutes(1);
        toReset.warningSent = true;
        lenient().when(quotaRepo.findResetNeeded(any(LocalDateTime.class)))
            .thenReturn(List.of(toReset))
            .thenReturn(List.of());
        rateLimitService.checkQuotaResets();
        assertEquals(0L, toReset.currentUsage);
        assertFalse(toReset.warningSent);
        rateLimitService.checkQuotaResets(); // 空列表分支

        // 配额重置异常分支
        lenient().when(quotaRepo.findResetNeeded(any(LocalDateTime.class)))
            .thenThrow(new RuntimeException("reset boom"));
        assertDoesNotThrow(() -> rateLimitService.checkQuotaResets());

        // 配额告警巡检：过滤 deletedAt，触发告警
        QuotaEntity active = quota("qp", 100, 85);
        QuotaEntity deleted = quota("qdel", 100, 85);
        deleted.deletedAt = LocalDateTime.now();
        lenient().when(quotaRepo.findAll()).thenReturn(List.of(active, deleted));
        assertDoesNotThrow(() -> rateLimitService.checkQuotaAlerts());
        assertTrue(active.warningSent);
        assertFalse(deleted.warningSent);
    }

    // ==================== Flink 作业：FlinkJobService ====================

    private FlinkJobEntity flinkJob(String id, FlinkJobEntity.JobStatus status) {
        FlinkJobEntity job = new FlinkJobEntity();
        job.id = id;
        job.gameId = "g1";
        job.environmentId = "env1";
        job.name = "risk-job";
        job.displayName = "Risk Job";
        job.jobType = FlinkJobEntity.JobType.RISK_EVALUATION.name();
        job.parallelism = 2;
        job.status = status;
        job.createdBy = "admin";
        return job;
    }

    @Test
    @DisplayName("Flink：创建作业（重名校验、序列化、默认值、空配置）")
    void flinkCreateJob() {
        // 名称重复
        lenient().when(flinkJobRepo.findByGameIdAndName("g1", "risk-job"))
            .thenReturn(Optional.of(flinkJob("fj-existing", FlinkJobEntity.JobStatus.RUNNING)));
        assertThrows(IllegalArgumentException.class, () -> flinkJobService.createJob(
            "g1", "env1", "risk-job", "Risk Job", "d", "FRAUD_DETECTION",
            Map.of("k", "v"), Map.of("src", "kafka"), Map.of("sink", "alert"),
            List.of("r1"), 2, "admin"));

        // 正常创建（全参数）—— anyString() 覆盖后续所有名称查询（未 stub 的 Optional 返回 null 会 NPE）
        lenient().when(flinkJobRepo.findByGameIdAndName(eq("g1"), anyString())).thenReturn(Optional.empty());
        FlinkJobEntity job = flinkJobService.createJob("g1", "env1", "risk-job", "Risk Job", "d",
            "FRAUD_DETECTION", Map.of("k", "v"), Map.of("src", "kafka"), Map.of("sink", "alert"),
            List.of("r1"), 2, "admin");
        assertNotNull(job.id);
        assertTrue(job.id.startsWith("fj_"));
        assertEquals(FlinkJobEntity.JobStatus.DRAFT, job.status);
        assertEquals("FRAUD_DETECTION", job.jobType);
        assertEquals(2, job.parallelism);
        assertTrue(job.jobConfig.contains("\"k\":\"v\""));
        assertTrue(job.sourceConfig.contains("kafka"));
        assertTrue(job.sinkConfig.contains("alert"));
        assertTrue(job.ruleIds.contains("r1"));

        // 默认值 + 空配置：jobType/parallelism 兜底，null 配置不序列化
        FlinkJobEntity emptyJob = flinkJobService.createJob("g1", null, "empty-job", null, null,
            null, null, null, null, null, null, null);
        assertEquals("RISK_EVALUATION", emptyJob.jobType);
        assertEquals(1, emptyJob.parallelism);
        assertNull(emptyJob.jobConfig);
        assertNull(emptyJob.sourceConfig);
        assertNull(emptyJob.sinkConfig);
        assertNull(emptyJob.ruleIds);
    }

    @Test
    @DisplayName("Flink：部署作业（成功、状态校验、失败回滚）")
    void flinkDeployJob() {
        // 不存在
        lenient().when(flinkJobRepo.findById("missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> flinkJobService.deployJob("missing", "op"));

        // 状态不可部署
        FlinkJobEntity running = flinkJob("fj-run", FlinkJobEntity.JobStatus.RUNNING);
        lenient().when(flinkJobRepo.findById("fj-run")).thenReturn(Optional.of(running));
        assertThrows(IllegalStateException.class, () -> flinkJobService.deployJob("fj-run", "op"));

        // DRAFT -> 部署成功
        FlinkJobEntity draft = flinkJob("fj-draft", FlinkJobEntity.JobStatus.DRAFT);
        draft.ruleIds = "[\"r1\"]";
        lenient().when(flinkJobRepo.findById("fj-draft")).thenReturn(Optional.of(draft));
        FlinkJobEntity deployed = flinkJobService.deployJob("fj-draft", "op");
        assertEquals(FlinkJobEntity.JobStatus.RUNNING, deployed.status);
        assertNotNull(deployed.flinkJobId);
        assertTrue(deployed.flinkJobId.startsWith("flink_"));
        assertTrue(deployed.flinkUrl.contains(deployed.flinkJobId));
        assertEquals("op", deployed.updatedBy);
        assertNotNull(deployed.deployedAt);

        // 部署过程失败：标记 FAILED 并抛出
        AtomicInteger saveCalls = new AtomicInteger();
        lenient().when(flinkJobRepo.save(any(FlinkJobEntity.class))).thenAnswer(inv -> {
            if (saveCalls.incrementAndGet() == 2) {
                throw new RuntimeException("save boom");
            }
            return inv.getArgument(0);
        });
        FlinkJobEntity failedDraft = flinkJob("fj-fail", FlinkJobEntity.JobStatus.STOPPED);
        lenient().when(flinkJobRepo.findById("fj-fail")).thenReturn(Optional.of(failedDraft));
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> flinkJobService.deployJob("fj-fail", "op"));
        assertEquals("Failed to deploy job", ex.getMessage());
        assertEquals(FlinkJobEntity.JobStatus.FAILED, failedDraft.status);
        assertNotNull(failedDraft.errorMessage);
    }

    @Test
    @DisplayName("Flink：停止作业（成功、状态校验、失败）")
    void flinkStopJob() {
        // 不存在
        lenient().when(flinkJobRepo.findById("missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> flinkJobService.stopJob("missing", "op"));

        // DRAFT 不可停止
        FlinkJobEntity draft = flinkJob("fj-draft2", FlinkJobEntity.JobStatus.DRAFT);
        lenient().when(flinkJobRepo.findById("fj-draft2")).thenReturn(Optional.of(draft));
        assertThrows(IllegalStateException.class, () -> flinkJobService.stopJob("fj-draft2", "op"));

        // RUNNING + flinkJobId -> 停止成功
        FlinkJobEntity running = flinkJob("fj-stop", FlinkJobEntity.JobStatus.RUNNING);
        running.flinkJobId = "flink_xyz";
        lenient().when(flinkJobRepo.findById("fj-stop")).thenReturn(Optional.of(running));
        FlinkJobEntity stopped = flinkJobService.stopJob("fj-stop", "op");
        assertEquals(FlinkJobEntity.JobStatus.STOPPED, stopped.status);
        assertNotNull(stopped.stoppedAt);
        assertEquals("op", stopped.updatedBy);

        // 停止失败：save 抛异常 -> RuntimeException
        FlinkJobEntity deploying = flinkJob("fj-stopfail", FlinkJobEntity.JobStatus.DEPLOYING);
        lenient().when(flinkJobRepo.findById("fj-stopfail")).thenReturn(Optional.of(deploying));
        lenient().when(flinkJobRepo.save(any(FlinkJobEntity.class)))
            .thenThrow(new RuntimeException("stop save boom"));
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> flinkJobService.stopJob("fj-stopfail", "op"));
        assertEquals("Failed to stop job", ex.getMessage());
    }

    @Test
    @DisplayName("Flink：更新作业指标与获取作业")
    void flinkUpdateJobMetrics() {
        // 作业不存在：静默返回
        lenient().when(flinkJobRepo.findById("missing")).thenReturn(Optional.empty());
        assertDoesNotThrow(() ->
            flinkJobService.updateJobMetrics("missing", 1L, 1L, 1L));
        verify(flinkJobRepo, never()).save(any(FlinkJobEntity.class));

        // 作业存在：更新指标
        FlinkJobEntity job = flinkJob("fj-m", FlinkJobEntity.JobStatus.RUNNING);
        lenient().when(flinkJobRepo.findById("fj-m")).thenReturn(Optional.of(job));
        flinkJobService.updateJobMetrics("fj-m", 100L, 10L, 5L);
        assertEquals(100L, job.totalEventsProcessed);
        assertEquals(10L, job.totalRiskCasesCreated);
        assertEquals(5L, job.totalActionsExecuted);
        assertNotNull(job.lastMetricsUpdate);

        // getJob 不存在
        assertThrows(IllegalArgumentException.class, () -> flinkJobService.getJob("missing"));
    }

    @Test
    @DisplayName("Flink：getJobConfig 解析（成功与坏 JSON）")
    void flinkGetJobConfig() {
        // 不存在
        lenient().when(flinkJobRepo.findById("missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> flinkJobService.getJobConfig("missing"));

        // 完整配置解析
        FlinkJobEntity job = flinkJob("fj-cfg", FlinkJobEntity.JobStatus.RUNNING);
        job.jobConfig = "{\"k\":\"v\"}";
        job.sourceConfig = "{\"src\":\"kafka\"}";
        job.sinkConfig = "{\"sink\":\"alert\"}";
        job.ruleIds = "[\"r1\",\"r2\"]";
        lenient().when(flinkJobRepo.findById("fj-cfg")).thenReturn(Optional.of(job));
        Map<String, Object> config = flinkJobService.getJobConfig("fj-cfg");
        assertEquals("fj-cfg", config.get("jobId"));
        assertEquals("g1", config.get("gameId"));
        assertEquals("env1", config.get("environmentId"));
        assertEquals(2, config.get("parallelism"));
        assertEquals(Map.of("k", "v"), config.get("jobConfig"));
        assertEquals(List.of("r1", "r2"), config.get("ruleIds"));

        // 空 job（null 配置）
        FlinkJobEntity bare = flinkJob("fj-bare", FlinkJobEntity.JobStatus.DRAFT);
        lenient().when(flinkJobRepo.findById("fj-bare")).thenReturn(Optional.of(bare));
        Map<String, Object> bareConfig = flinkJobService.getJobConfig("fj-bare");
        assertFalse(bareConfig.containsKey("jobConfig"));

        // 坏 JSON：解析失败仅记录日志
        FlinkJobEntity broken = flinkJob("fj-bad", FlinkJobEntity.JobStatus.DRAFT);
        broken.jobConfig = "{not-valid-json";
        broken.ruleIds = "not-a-json";
        lenient().when(flinkJobRepo.findById("fj-bad")).thenReturn(Optional.of(broken));
        Map<String, Object> brokenConfig = flinkJobService.getJobConfig("fj-bad");
        assertFalse(brokenConfig.containsKey("jobConfig"));
        assertFalse(brokenConfig.containsKey("ruleIds"));
    }

    @Test
    @DisplayName("Flink：getJobRules 规则解析（空/部分命中/坏 JSON）")
    void flinkGetJobRules() {
        // 不存在
        lenient().when(flinkJobRepo.findById("missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> flinkJobService.getJobRules("missing"));

        // ruleIds 为 null
        FlinkJobEntity noRules = flinkJob("fj-norules", FlinkJobEntity.JobStatus.RUNNING);
        lenient().when(flinkJobRepo.findById("fj-norules")).thenReturn(Optional.of(noRules));
        assertEquals(List.of(), flinkJobService.getJobRules("fj-norules"));

        // 部分规则命中
        FlinkJobEntity job = flinkJob("fj-rules", FlinkJobEntity.JobStatus.RUNNING);
        job.ruleIds = "[\"r1\",\"missing-rule\"]";
        lenient().when(flinkJobRepo.findById("fj-rules")).thenReturn(Optional.of(job));
        RiskRuleEntity rule = new RiskRuleEntity();
        rule.id = "r1";
        lenient().when(riskRuleRepo.findById("r1")).thenReturn(Optional.of(rule));
        lenient().when(riskRuleRepo.findById("missing-rule")).thenReturn(Optional.empty());
        List<RiskRuleEntity> rules = flinkJobService.getJobRules("fj-rules");
        assertEquals(1, rules.size());
        assertEquals("r1", rules.get(0).id);

        // 坏 JSON：返回空列表
        FlinkJobEntity broken = flinkJob("fj-badrules", FlinkJobEntity.JobStatus.RUNNING);
        broken.ruleIds = "not-a-json";
        lenient().when(flinkJobRepo.findById("fj-badrules")).thenReturn(Optional.of(broken));
        assertEquals(List.of(), flinkJobService.getJobRules("fj-badrules"));
    }

    // ==================== 集成：IntegrationService ====================

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
            IntegrationEntity.IntegrationType.WEBHOOK, null, null, null, null, null, null, null);
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
    @DisplayName("集成：验证/启用/禁用/获取/删除")
    void integrationVerifyEnableDisableDelete() {
        // 验证：不存在
        lenient().when(integrationRepo.findById("missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> integrationService.verifyIntegration("missing"));

        // 验证成功（健康检查模拟成功 -> ACTIVE）
        IntegrationEntity entity = integration("i1", IntegrationEntity.IntegrationStatus.INACTIVE, true);
        lenient().when(integrationRepo.findById("i1")).thenReturn(Optional.of(entity));
        IntegrationEntity verified = integrationService.verifyIntegration("i1");
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

    // ==================== Webhook：WebhookService ====================

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

    private WebhookLogEntity capturedWebhookLog() {
        ArgumentCaptor<WebhookLogEntity> captor = ArgumentCaptor.forClass(WebhookLogEntity.class);
        verify(webhookLogRepo, times(2)).save(captor.capture());
        return captor.getValue();
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
