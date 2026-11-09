package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.QuotaEntity;
import io.oddsmaker.control.jpa.QuotaRepo;
import io.oddsmaker.control.jpa.RateLimitEntity;
import io.oddsmaker.control.jpa.RateLimitRepo;
import io.oddsmaker.control.jpa.WebhookConfigRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 基础设施 Service 深度单元测试（LAY-008 拆分）：限流规则创建/更新/删除/参数校验、配额创建与用量告警、调度任务与计数侧。与 RateLimitServiceTest / InfraServicesTest 互补。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("限流 Service 深度测试（规则/配额/调度）")
class RateLimitServiceDeepTest {

    @Mock
    private RateLimitRepo rateLimitRepo;

    @Mock
    private QuotaRepo quotaRepo;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private RateLimitService rateLimitService;

    @Mock
    private WebhookConfigRepo webhookConfigRepo;

    @InjectMocks
    private WebhookService webhookService;

    @BeforeEach
    void setUp() {
        lenient().when(rateLimitRepo.save(any(RateLimitEntity.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(quotaRepo.save(any(QuotaEntity.class))).thenAnswer(i -> i.getArgument(0));
    }

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
    @DisplayName("配额：使用量更新触发警告/告警（原子自增 + 标志位原子 UPDATE）")
    void quotaUpdateUsageAlerts() {
        // 未配置配额：无操作
        lenient().when(quotaRepo.findByGameEnvironmentAndResourceType("g1", "env1",
            QuotaEntity.ResourceType.EVENTS_PER_DAY)).thenReturn(Optional.empty());
        assertDoesNotThrow(() ->
            rateLimitService.updateQuotaUsage("g1", "env1", QuotaEntity.ResourceType.EVENTS_PER_DAY, 100));
        verify(quotaRepo, never()).incrementUsageAtomic(anyString(), anyLong());

        // 警告区间（85%）：原子自增后重读（85%），发送警告不发送告警
        QuotaEntity warningQuota = quota("qw", 100, 0);
        lenient().when(quotaRepo.findByGameAndResourceType("g1", QuotaEntity.ResourceType.EVENTS_PER_DAY))
            .thenReturn(Optional.of(warningQuota));
        lenient().when(quotaRepo.findById("qw")).thenReturn(Optional.of(quota("qw", 100, 85)));
        rateLimitService.updateQuotaUsage("g1", null, QuotaEntity.ResourceType.EVENTS_PER_DAY, 85);
        verify(quotaRepo).incrementUsageAtomic("qw", 85);
        verify(quotaRepo).markWarningSentAtomic("qw");
        verify(quotaRepo, never()).markAlertSentAtomic("qw");

        // 告警区间（96%）：警告 + 告警
        QuotaEntity alertQuota = quota("qa", 100, 0);
        lenient().when(quotaRepo.findByGameAndResourceType("g1", QuotaEntity.ResourceType.USERS))
            .thenReturn(Optional.of(alertQuota));
        lenient().when(quotaRepo.findById("qa")).thenReturn(Optional.of(quota("qa", 100, 96)));
        rateLimitService.updateQuotaUsage("g1", null, QuotaEntity.ResourceType.USERS, 96);
        verify(quotaRepo).markWarningSentAtomic("qa");
        verify(quotaRepo).markAlertSentAtomic("qa");

        // 已发送过告警：不重复发送
        QuotaEntity sent = quota("qs", 100, 99);
        sent.warningSent = true;
        sent.alertSent = true;
        lenient().when(quotaRepo.findByGameAndResourceType("g1", QuotaEntity.ResourceType.INTEGRATIONS))
            .thenReturn(Optional.of(sent));
        lenient().when(quotaRepo.findById("qs")).thenReturn(Optional.of(sent));
        rateLimitService.updateQuotaUsage("g1", null, QuotaEntity.ResourceType.INTEGRATIONS, 1);
        verify(quotaRepo).incrementUsageAtomic("qs", 1);
        verify(quotaRepo, never()).markWarningSentAtomic("qs");
        verify(quotaRepo, never()).markAlertSentAtomic("qs");
    }

    @Test
    @DisplayName("限流：定时配额重置/配额告警巡检")
    void rateLimitScheduledJobs() {
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
        verify(quotaRepo).markWarningSentAtomic("qp");
        verify(quotaRepo, never()).markWarningSentAtomic("qdel");
    }

    @Test
    @DisplayName("限流：createRateLimit null 兜底侧（规则三元先执行、审计 Map.of 不容 null 值而 NPE）+ 配额 webhook resourceType null + 统计 scope 过滤")
    void rateLimitAndQuotaCounterSides() {
        // 56 行 limit null → 100；随后审计 Map.of("limit", null) 抛 NPE（生产语义如此，三元行已执行）
        assertThrows(NullPointerException.class, () -> rateLimitService.createRateLimit(
            "g1", null, null, null, RateLimitEntity.Scope.GAME, null,
            RateLimitEntity.WindowType.HOUR, 1, null, null, null, null));
        // 57 行 windowType null → MINUTE；审计 Map.of("windowType", null) 抛 NPE
        assertThrows(NullPointerException.class, () -> rateLimitService.createRateLimit(
            "g1", null, null, null, RateLimitEntity.Scope.GAME, 200, null, 1, null, null, null, null));

        // 233 行 quota.resourceType null → payload resource_type null
        QuotaEntity nullType = quota("q-null", 100, 99);
        nullType.resourceType = null;
        // @InjectMocks 实例不会被注入进另一个 @InjectMocks（Mockito 不级联），手动接线，
        // 否则 rateLimitService.webhookService 为 null、NPE 被 dispatchQuotaWebhook 的 catch 吞掉
        org.springframework.test.util.ReflectionTestUtils.setField(rateLimitService, "webhookService", webhookService);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(rateLimitService,
            "dispatchQuotaWebhook", nullType, WebhookService.EVENT_QUOTA_ALERT);
        // webhookService 是 @InjectMocks 真实例（非 mock），验证其首个副作用：查询活跃配置
        verify(webhookConfigRepo).findActiveByGameId("g1");

        // 324 行 scope null 过滤侧：byScope 只统计有 scope 的规则
        RateLimitEntity withScope = rateLimit("rl-s", RateLimitEntity.Scope.GAME,
            RateLimitEntity.Algorithm.SLIDING_WINDOW, 10, true);
        RateLimitEntity noScope = rateLimit("rl-ns", null,
            RateLimitEntity.Algorithm.SLIDING_WINDOW, 10, true);
        when(rateLimitRepo.findByGameId("g1")).thenReturn(List.of(withScope, noScope));
        Map<String, Object> stats = rateLimitService.getRateLimitStats("g1");
        assertEquals(2L, stats.get("total"));
        @SuppressWarnings("unchecked")
        Map<String, Long> byScope = (Map<String, Long>) stats.get("byScope");
        assertEquals(Map.of(RateLimitEntity.Scope.GAME.name(), 1L), byScope);
    }

}
