package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.jpa.MetricAlertRuleEntity;
import io.oddsmaker.control.jpa.MetricAlertRuleRepo;
import io.oddsmaker.control.jpa.SystemAlertEntity;
import io.oddsmaker.control.jpa.SystemAlertRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 业务指标告警服务测试：评估守卫、阈值/偏差判定、沿触发抑制、恢复、异常隔离与 CRUD 校验。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("业务指标告警服务测试")
class MetricAlertServiceTest {

    @Mock
    private MetricAlertRuleRepo ruleRepo;

    @Mock
    private SystemAlertRepo alertRepo;

    @Mock
    private ClickHouseClient clickHouse;

    @Mock
    private WebhookService webhookService;

    @Mock
    private AuditLogService auditLog;

    private MetricAlertService service;

    @BeforeEach
    void setUp() {
        service = new MetricAlertService(ruleRepo, alertRepo, clickHouse, webhookService,
                auditLog, new ObjectMapper(), true);
        when(alertRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(ruleRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private MetricAlertRuleEntity absoluteRule(MetricAlertRuleEntity.Comparison cmp, double threshold) {
        MetricAlertRuleEntity rule = new MetricAlertRuleEntity();
        rule.id = "alr_1";
        rule.gameId = "g";
        rule.name = "收入下限";
        rule.metricType = MetricAlertRuleEntity.MetricType.REVENUE;
        rule.conditionType = MetricAlertRuleEntity.ConditionType.ABSOLUTE;
        rule.comparison = cmp;
        rule.threshold = threshold;
        rule.severity = SystemAlertEntity.Severity.CRITICAL;
        return rule;
    }

    private MetricAlertRuleEntity baselineRule(MetricAlertRuleEntity.Comparison cmp, double pct) {
        MetricAlertRuleEntity rule = new MetricAlertRuleEntity();
        rule.id = "alr_2";
        rule.gameId = "g";
        rule.name = "DAU 骤降";
        rule.metricType = MetricAlertRuleEntity.MetricType.DAU;
        rule.conditionType = MetricAlertRuleEntity.ConditionType.BASELINE_DEVIATION;
        rule.comparison = cmp;
        rule.deviationPct = pct;
        return rule;
    }

    private void stubMetric(double value) {
        when(clickHouse.query(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("v", value)));
    }

    /** BASELINE 评估连续查两次：第一次当前值、第二次基线 */
    private void stubCurrentBaseline(double current, double baseline) {
        when(clickHouse.query(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("v", current)), List.of(Map.of("v", baseline)));
    }

    // ===== 评估守卫 =====

    @Test
    @DisplayName("守卫：enabled=false 或 CH 不可用时不评估任何规则")
    void guardsSkipEvaluation() {
        MetricAlertService disabled = new MetricAlertService(ruleRepo, alertRepo, clickHouse,
                webhookService, auditLog, new ObjectMapper(), false);
        disabled.evaluateAll();
        verify(ruleRepo, never()).findByEnabledTrueAndDeletedAtIsNull();

        when(clickHouse.isAvailable()).thenReturn(false);
        service.evaluateAll();
        verify(ruleRepo, never()).findByEnabledTrueAndDeletedAtIsNull();
    }

    // ===== ABSOLUTE 判定 =====

    @Test
    @DisplayName("ABSOLUTE LT 命中：新建告警 + Webhook 派发 + notificationSent")
    void absoluteHitCreatesAlertAndNotifies() {
        when(clickHouse.isAvailable()).thenReturn(true);
        stubMetric(500.0);

        boolean firing = service.evaluateRule(absoluteRule(MetricAlertRuleEntity.Comparison.LT, 1000.0));
        assertTrue(firing);

        // 新建 save + webhook 派发后置 notificationSent 再 save（同一实体两次落库）
        ArgumentCaptor<SystemAlertEntity> captor = ArgumentCaptor.forClass(SystemAlertEntity.class);
        verify(alertRepo, times(2)).save(captor.capture());
        SystemAlertEntity alert = captor.getValue();
        assertEquals("g", alert.gameId);
        assertEquals("alr_1", alert.ruleId);
        assertEquals(SystemAlertEntity.AlertType.ANOMALY_DETECTED, alert.alertType);
        assertEquals(SystemAlertEntity.Severity.CRITICAL, alert.severity);
        assertEquals(SystemAlertEntity.AlertStatus.OPEN, alert.alertStatus);
        assertEquals("lt", alert.condition);
        assertEquals(500.0, alert.metricValue);
        assertEquals(1000.0, alert.thresholdValue);
        assertEquals(Boolean.TRUE, alert.notificationSent);
        assertTrue(alert.context.contains("alr_1"));
        verify(webhookService).sendCustomWebhook(eq("g"), eq("metric_alert"), any());
    }

    @Test
    @DisplayName("ABSOLUTE GT 命中触发；未命中不新建告警")
    void absoluteGtAndMiss() {
        when(clickHouse.isAvailable()).thenReturn(true);
        stubMetric(2000.0);
        assertTrue(service.evaluateRule(absoluteRule(MetricAlertRuleEntity.Comparison.GT, 1000.0)));
        verify(alertRepo, times(2)).save(any());

        // 未命中：不新增告警（上面命中路径共 2 次 save）
        stubMetric(500.0);
        assertFalse(service.evaluateRule(absoluteRule(MetricAlertRuleEntity.Comparison.GT, 1000.0)));
        verify(alertRepo, times(2)).save(any());
    }

    // ===== BASELINE 判定 =====

    @Test
    @DisplayName("BASELINE BOTH：下跌与上涨双向命中，±N 内不触发")
    void baselineBothDirections() {
        when(clickHouse.isAvailable()).thenReturn(true);
        MetricAlertRuleEntity rule = baselineRule(MetricAlertRuleEntity.Comparison.BOTH, 30);

        stubCurrentBaseline(60.0, 100.0);   // -40%
        assertTrue(service.evaluateRule(rule));

        stubCurrentBaseline(140.0, 100.0);  // +40%
        assertTrue(service.evaluateRule(rule));

        stubCurrentBaseline(120.0, 100.0);  // +20%
        assertFalse(service.evaluateRule(rule));
        assertEquals(120.0, rule.lastValue);
        assertEquals(100.0, rule.lastBaseline);
    }

    @Test
    @DisplayName("BASELINE 方向过滤：LT 只报下跌，上涨超限不报")
    void baselineDirectionFilter() {
        when(clickHouse.isAvailable()).thenReturn(true);
        MetricAlertRuleEntity rule = baselineRule(MetricAlertRuleEntity.Comparison.LT, 30);

        stubCurrentBaseline(140.0, 100.0);  // +40% 但 LT 不报上涨
        assertFalse(service.evaluateRule(rule));

        stubCurrentBaseline(60.0, 100.0);   // -40%
        assertTrue(service.evaluateRule(rule));
    }

    @Test
    @DisplayName("BASELINE：基线为 0/null 时跳过不触发")
    void baselineZeroOrNullSkips() {
        when(clickHouse.isAvailable()).thenReturn(true);
        MetricAlertRuleEntity rule = baselineRule(MetricAlertRuleEntity.Comparison.BOTH, 30);

        // 第一次调用=当前值 100，第二次调用=基线 0 → 偏差除零跳过
        when(clickHouse.query(contains("uniqExact"), any(Object[].class)))
                .thenReturn(List.of(Map.of("v", 100L)), List.of(Map.of("v", 0L)));
        assertFalse(service.evaluateRule(rule));

        // 基线查询返回空行 → null 跳过
        when(clickHouse.query(contains("uniqExact"), any(Object[].class)))
                .thenReturn(List.of(Map.of("v", 100L)), List.of());
        assertFalse(service.evaluateRule(rule));
    }

    // ===== 沿触发抑制与恢复 =====

    @Test
    @DisplayName("沿触发：已有活跃告警时累加 occurrenceCount，不新建不重发")
    void firingAccumulatesExistingAlert() {
        when(clickHouse.isAvailable()).thenReturn(true);
        stubMetric(500.0);

        SystemAlertEntity active = new SystemAlertEntity();
        active.id = "alert_x";
        active.gameId = "g";
        active.ruleId = "alr_1";
        active.occurrenceCount = 1;
        when(alertRepo.findActiveByRuleId("alr_1")).thenReturn(List.of(active));

        assertTrue(service.evaluateRule(absoluteRule(MetricAlertRuleEntity.Comparison.LT, 1000.0)));

        assertEquals(2, active.occurrenceCount);
        assertNotNull(active.lastOccurredAt);
        assertEquals(500.0, active.metricValue);
        verify(webhookService, never()).sendCustomWebhook(anyString(), anyString(), any());
        // 只 save 了累积更新的那条告警，没有新建
        verify(alertRepo).save(active);
    }

    @Test
    @DisplayName("恢复：未触发且上次 FIRING 时置 OK，不自动 resolve 告警")
    void recoverySetsOkState() {
        when(clickHouse.isAvailable()).thenReturn(true);
        stubMetric(5000.0);   // 高于阈值 1000，未触发

        MetricAlertRuleEntity rule = absoluteRule(MetricAlertRuleEntity.Comparison.LT, 1000.0);
        rule.lastState = MetricAlertRuleEntity.State.FIRING;

        assertFalse(service.evaluateRule(rule));
        assertEquals(MetricAlertRuleEntity.State.OK, rule.lastState);
        verify(alertRepo, never()).save(any());
    }

    // ===== 异常隔离与跳过 =====

    @Test
    @DisplayName("隔离：单规则评估抛异常（写库失败）不影响后续规则触发")
    void ruleFailureIsolated() {
        when(clickHouse.isAvailable()).thenReturn(true);
        stubMetric(500.0);
        MetricAlertRuleEntity broken = absoluteRule(MetricAlertRuleEntity.Comparison.LT, 1000.0);
        broken.id = "alr_broken";
        MetricAlertRuleEntity good = absoluteRule(MetricAlertRuleEntity.Comparison.LT, 1000.0);
        good.id = "alr_good";
        when(ruleRepo.findByEnabledTrueAndDeletedAtIsNull()).thenReturn(List.of(broken, good));
        // 首次 save（broken 回写评估状态）抛异常，其后正常
        when(ruleRepo.save(any())).thenThrow(new RuntimeException("db down"))
                .thenAnswer(inv -> inv.getArgument(0));

        service.evaluateAll();

        // broken 异常被吞掉（evaluateAll 未传播），good 不受影响正常触发（两条都命中 → webhook 各一次）
        verify(webhookService, times(2)).sendCustomWebhook(eq("g"), eq("metric_alert"), any());
        assertEquals(MetricAlertRuleEntity.State.FIRING, good.lastState);
        verify(ruleRepo).findByEnabledTrueAndDeletedAtIsNull();
    }

    @Test
    @DisplayName("指标查询异常返回 null，本轮跳过且不动 last_state")
    void queryFailureSkipsRound() {
        when(clickHouse.isAvailable()).thenReturn(true);
        when(clickHouse.query(anyString(), any(Object[].class))).thenThrow(new RuntimeException("boom"));

        MetricAlertRuleEntity rule = absoluteRule(MetricAlertRuleEntity.Comparison.LT, 1000.0);
        assertFalse(service.evaluateRule(rule));
        assertNull(rule.lastEvaluatedAt);
        assertEquals(MetricAlertRuleEntity.State.OK, rule.lastState);
    }

    // ===== 手动试算 =====

    @Test
    @DisplayName("试算：返回当前值/基线/偏差/fired，不改任何状态")
    void evaluateNowDoesNotMutate() {
        when(clickHouse.isAvailable()).thenReturn(true);
        when(ruleRepo.findById("alr_2")).thenReturn(Optional.of(baselineRule(MetricAlertRuleEntity.Comparison.BOTH, 30)));
        stubCurrentBaseline(60.0, 100.0);

        Map<String, Object> out = service.evaluateNow("g", "alr_2");
        assertTrue((Boolean) out.get("available"));
        assertEquals(60.0, out.get("currentValue"));
        assertEquals(100.0, out.get("baseline"));
        assertEquals(-40.0, (Double) out.get("deviationPct"), 1e-9);
        assertEquals(true, out.get("fired"));
        verify(alertRepo, never()).save(any());
        verify(webhookService, never()).sendCustomWebhook(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("试算：CH 不可用降级 available=false；规则不存在抛 400")
    void evaluateNowDegrades() {
        when(ruleRepo.findById("alr_2")).thenReturn(Optional.of(baselineRule(MetricAlertRuleEntity.Comparison.BOTH, 30)));
        when(clickHouse.isAvailable()).thenReturn(false);
        Map<String, Object> out = service.evaluateNow("g", "alr_2");
        assertEquals(false, out.get("available"));

        when(ruleRepo.findById("alr_missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.evaluateNow("g", "alr_missing"));
    }

    // ===== CRUD 校验 =====

    @Test
    @DisplayName("CRUD 校验：缺字段/非法组合抛 IllegalArgumentException")
    void crudValidation() {
        MetricAlertRuleEntity noName = absoluteRule(MetricAlertRuleEntity.Comparison.LT, 100);
        noName.name = " ";
        assertThrows(IllegalArgumentException.class, () -> service.create("g", noName, "op"));

        MetricAlertRuleEntity noThreshold = absoluteRule(MetricAlertRuleEntity.Comparison.LT, 100);
        noThreshold.threshold = null;
        assertThrows(IllegalArgumentException.class, () -> service.create("g", noThreshold, "op"));

        MetricAlertRuleEntity bothAbsolute = absoluteRule(MetricAlertRuleEntity.Comparison.BOTH, 100);
        assertThrows(IllegalArgumentException.class, () -> service.create("g", bothAbsolute, "op"));

        MetricAlertRuleEntity badDeviation = baselineRule(MetricAlertRuleEntity.Comparison.BOTH, -5);
        assertThrows(IllegalArgumentException.class, () -> service.create("g", badDeviation, "op"));

        MetricAlertRuleEntity noEnum = absoluteRule(MetricAlertRuleEntity.Comparison.LT, 100);
        noEnum.metricType = null;
        assertThrows(IllegalArgumentException.class, () -> service.create("g", noEnum, "op"));
    }

    @Test
    @DisplayName("CRUD：create 生成 alr_ 前缀 id 与操作人；update/get 校验归属；软删")
    void crudLifecycle() {
        MetricAlertRuleEntity rule = absoluteRule(MetricAlertRuleEntity.Comparison.LT, 100);
        MetricAlertRuleEntity created = service.create("g", rule, "op");
        assertTrue(created.id.startsWith("alr_"));
        assertEquals(28, created.id.length());
        assertEquals("op", created.createdBy);
        verify(auditLog).log(any(), eq("metric_alert_rule"), eq(created.id), eq("收入下限"),
                any(), any(), eq("op"), any(), anyString(), any(), any(), any());

        when(ruleRepo.findById(created.id)).thenReturn(Optional.of(created));
        // gameId 不匹配按不存在
        assertEquals(null, service.get("other", created.id));

        // 软删
        assertTrue(service.delete("g", created.id, "op2"));
        assertNotNull(created.deletedAt);
        assertFalse(service.delete("g", created.id, "op2"));   // 已删后再次删除返回 false
    }

    // ===== 窗口边界与偏差计算 =====

    @Test
    @DisplayName("窗口边界：TODAY/HOUR_1 的当前与昨日基线区间")
    void windowRanges() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 18, 10, 30);

        LocalDateTime[] today = MetricAlertService.currentRange(MetricAlertRuleEntity.Window.TODAY, now);
        assertEquals(LocalDateTime.of(2026, 9, 18, 0, 0), today[0]);
        assertEquals(now, today[1]);

        LocalDateTime[] hour = MetricAlertService.currentRange(MetricAlertRuleEntity.Window.HOUR_1, now);
        assertEquals(LocalDateTime.of(2026, 9, 18, 9, 30), hour[0]);
        assertEquals(now, hour[1]);

        LocalDateTime[] baseToday = MetricAlertService.baselineRange(MetricAlertRuleEntity.Window.TODAY, now);
        assertEquals(LocalDateTime.of(2026, 9, 17, 0, 0), baseToday[0]);
        assertEquals(LocalDateTime.of(2026, 9, 17, 10, 30), baseToday[1]);

        LocalDateTime[] baseHour = MetricAlertService.baselineRange(MetricAlertRuleEntity.Window.HOUR_1, now);
        assertEquals(LocalDateTime.of(2026, 9, 17, 9, 30), baseHour[0]);
        assertEquals(LocalDateTime.of(2026, 9, 17, 10, 30), baseHour[1]);
    }

    @Test
    @DisplayName("偏差计算：null/零基线返回 null，常规值正确")
    void deviationMath() {
        assertNull(MetricAlertService.deviationPct(null, 100.0));
        assertNull(MetricAlertService.deviationPct(100.0, null));
        assertNull(MetricAlertService.deviationPct(100.0, 0.0));
        assertEquals(-40.0, MetricAlertService.deviationPct(60.0, 100.0), 1e-9);
        assertEquals(150.0, MetricAlertService.deviationPct(250.0, 100.0), 1e-9);
    }

    // ===== update / alertHistory / acknowledge / resolve =====

    @Test
    @DisplayName("list：按游戏委托 Repo")
    void listDelegates() {
        when(ruleRepo.findByGameIdAndDeletedAtIsNullOrderByName("g"))
                .thenReturn(List.of(absoluteRule(MetricAlertRuleEntity.Comparison.LT, 100)));
        assertEquals(1, service.list("g").size());
    }

    @Test
    @DisplayName("update：合并字段 + 审计；不存在或跨游戏抛 IllegalArgumentException")
    void updateMergesAndAudits() {
        MetricAlertRuleEntity existing = absoluteRule(MetricAlertRuleEntity.Comparison.LT, 1000.0);
        when(ruleRepo.findById("alr_1")).thenReturn(Optional.of(existing));

        MetricAlertRuleEntity req = baselineRule(MetricAlertRuleEntity.Comparison.BOTH, 25);
        req.name = "DAU 波动";
        req.environment = "prod";
        req.window = MetricAlertRuleEntity.Window.HOUR_1;
        req.enabled = false;
        req.notifyWebhook = false;
        MetricAlertRuleEntity updated = service.update("g", "alr_1", req, "op2");

        assertEquals("DAU 波动", updated.name);
        assertEquals(MetricAlertRuleEntity.ConditionType.BASELINE_DEVIATION, updated.conditionType);
        assertEquals(25.0, updated.deviationPct);
        assertEquals("prod", updated.environment);
        assertEquals(MetricAlertRuleEntity.Window.HOUR_1, updated.window);
        assertEquals(false, updated.enabled);
        assertEquals("op2", updated.updatedBy);
        verify(auditLog).log(any(), eq("metric_alert_rule"), eq("alr_1"), eq("DAU 波动"),
                any(), any(), eq("op2"), any(), anyString(), any(), any(), any());

        // 不存在 / 跨游戏
        when(ruleRepo.findById("alr_x")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.update("g", "alr_x", req, "op"));
        assertThrows(IllegalArgumentException.class, () -> service.update("other", "alr_1", req, "op"));

        // 校验先于合并：非法 req 不落库
        MetricAlertRuleEntity bad = absoluteRule(MetricAlertRuleEntity.Comparison.BOTH, 1);
        assertThrows(IllegalArgumentException.class, () -> service.update("g", "alr_1", bad, "op"));
    }

    @Test
    @DisplayName("update：校验失败（存在性检查后）抛异常不写审计")
    void updateMissingNoAudit() {
        when(ruleRepo.findById("alr_1")).thenReturn(Optional.empty());
        MetricAlertRuleEntity req = absoluteRule(MetricAlertRuleEntity.Comparison.LT, 1);
        assertThrows(IllegalArgumentException.class, () -> service.update("g", "alr_1", req, "op"));
        verify(ruleRepo, never()).save(any());
    }

    @Test
    @DisplayName("告警历史：limit 夹取到 [1,200] 并委托 Repo")
    void alertHistoryClampsAndDelegates() {
        when(alertRepo.findByGameId(eq("g"), any())).thenReturn(List.of());
        service.alertHistory("g", 50);
        service.alertHistory("g", 0);      // → 1
        service.alertHistory("g", 9999);   // → 200

        verify(alertRepo).findByGameId(eq("g"), eq(org.springframework.data.domain.PageRequest.of(0, 50)));
        verify(alertRepo).findByGameId(eq("g"), eq(org.springframework.data.domain.PageRequest.of(0, 1)));
        verify(alertRepo).findByGameId(eq("g"), eq(org.springframework.data.domain.PageRequest.of(0, 200)));
    }

    @Test
    @DisplayName("确认与解决：归属校验通过后落库并审计；已删/跨游戏抛异常")
    void acknowledgeResolveWithOwnership() {
        SystemAlertEntity alert = new SystemAlertEntity();
        alert.id = "alert_x";
        alert.gameId = "g";
        alert.title = "收入下限";
        when(alertRepo.findById("alert_x")).thenReturn(Optional.of(alert));

        SystemAlertEntity acked = service.acknowledge("g", "alert_x", "alice", "收到");
        assertEquals(SystemAlertEntity.AlertStatus.ACKNOWLEDGED, acked.alertStatus);
        assertEquals("alice", acked.acknowledgedBy);
        verify(auditLog).log(any(), eq("system_alert"), eq("alert_x"), eq("收入下限"),
                any(), any(), eq("alice"), any(), eq("acknowledged"), any(), any(), any());

        SystemAlertEntity resolved = service.resolve("g", "alert_x", "bob", "已修复");
        assertEquals(SystemAlertEntity.AlertStatus.RESOLVED, resolved.alertStatus);
        assertEquals("bob", resolved.resolvedBy);
        verify(auditLog).log(any(), eq("system_alert"), eq("alert_x"), eq("收入下限"),
                any(), any(), eq("bob"), any(), eq("resolved"), any(), any(), any());

        // 已删除告警拒绝处理
        alert.deletedAt = LocalDateTime.now();
        assertThrows(IllegalArgumentException.class, () -> service.resolve("g", "alert_x", "bob", "again"));

        // 跨游戏归属拒绝
        alert.deletedAt = null;
        alert.gameId = "other";
        assertThrows(IllegalArgumentException.class, () -> service.acknowledge("g", "alert_x", "alice", "x"));

        // 不存在
        when(alertRepo.findById("alert_missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.resolve("g", "alert_missing", "bob", null));
    }

    // ===== 指标查询分支 =====

    @Test
    @DisplayName("environment 过滤：带环境的规则查询拼 AND environment = ? 且告警照常触发")
    void environmentFilterApplied() {
        when(clickHouse.isAvailable()).thenReturn(true);
        stubMetric(500.0);
        MetricAlertRuleEntity rule = absoluteRule(MetricAlertRuleEntity.Comparison.LT, 1000.0);
        rule.environment = "prod";

        assertTrue(service.evaluateRule(rule));
        org.mockito.ArgumentCaptor<Object[]> args = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(clickHouse).query(contains("AND environment = ?"), args.capture());
        assertEquals(4, args.getValue().length);
        assertEquals("prod", args.getValue()[1]);
    }

    @Test
    @DisplayName("CRASH_RATE 指标：比例低于阈值触发，condition 走 gt/lt 标签")
    void crashRateMetricEvaluates() {
        when(clickHouse.isAvailable()).thenReturn(true);
        stubMetric(0.02);
        MetricAlertRuleEntity rule = absoluteRule(MetricAlertRuleEntity.Comparison.GT, 0.01);
        rule.metricType = MetricAlertRuleEntity.MetricType.CRASH_RATE;
        rule.comparison = MetricAlertRuleEntity.Comparison.GT;

        assertTrue(service.evaluateRule(rule));
        ArgumentCaptor<SystemAlertEntity> captor = ArgumentCaptor.forClass(SystemAlertEntity.class);
        verify(alertRepo, times(2)).save(captor.capture());
        assertEquals("gt", captor.getValue().condition);
        assertEquals(0.02, captor.getValue().metricValue, 1e-9);
    }

    @Test
    @DisplayName("BASELINE 触发告警：condition 标签区分下跌/上涨")
    void baselineConditionLabels() {
        when(clickHouse.isAvailable()).thenReturn(true);
        MetricAlertRuleEntity rule = baselineRule(MetricAlertRuleEntity.Comparison.LT, 30);
        stubCurrentBaseline(60.0, 100.0);
        assertTrue(service.evaluateRule(rule));
        ArgumentCaptor<SystemAlertEntity> down = ArgumentCaptor.forClass(SystemAlertEntity.class);
        verify(alertRepo, times(2)).save(down.capture());
        assertEquals("baseline_down", down.getValue().condition);

        // 上涨方向（GT）另起规则：无活跃告警 → 新建走 baseline_up 标签
        org.mockito.Mockito.reset(alertRepo);
        when(alertRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        MetricAlertRuleEntity up = baselineRule(MetricAlertRuleEntity.Comparison.GT, 30);
        up.id = "alr_3";
        stubCurrentBaseline(140.0, 100.0);
        assertTrue(service.evaluateRule(up));
        ArgumentCaptor<SystemAlertEntity> upCap = ArgumentCaptor.forClass(SystemAlertEntity.class);
        verify(alertRepo, times(2)).save(upCap.capture());
        assertEquals("baseline_up", upCap.getValue().condition);
    }
}
