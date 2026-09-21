package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.dto.RiskEventDto;
import io.oddsmaker.control.jpa.FeatureFlagEntity;
import io.oddsmaker.control.jpa.FeatureFlagRepo;
import io.oddsmaker.control.jpa.FunnelConfigEntity;
import io.oddsmaker.control.jpa.FunnelConfigRepo;
import io.oddsmaker.control.jpa.FunnelStepEntity;
import io.oddsmaker.control.jpa.FunnelStepRepo;
import io.oddsmaker.control.jpa.IdentityLinkEntity;
import io.oddsmaker.control.jpa.IdentityLinkRepo;
import io.oddsmaker.control.jpa.MaintenanceWindowEntity;
import io.oddsmaker.control.jpa.MaintenanceWindowRepo;
import io.oddsmaker.control.jpa.MLModelEntity;
import io.oddsmaker.control.jpa.MLModelPredictionEntity;
import io.oddsmaker.control.jpa.MLModelRepo;
import io.oddsmaker.control.jpa.MetricAlertRuleEntity;
import io.oddsmaker.control.jpa.MetricAlertRuleRepo;
import io.oddsmaker.control.jpa.ModelPredictionRepo;
import io.oddsmaker.control.jpa.ModelTrainingEntity;
import io.oddsmaker.control.jpa.ModelTrainingRepo;
import io.oddsmaker.control.jpa.ReportEntity;
import io.oddsmaker.control.jpa.ReportExecutionEntity;
import io.oddsmaker.control.jpa.ReportExecutionRepo;
import io.oddsmaker.control.jpa.ReportRepo;
import io.oddsmaker.control.jpa.RiskCaseRepo;
import io.oddsmaker.control.jpa.SystemAlertEntity;
import io.oddsmaker.control.jpa.SystemAlertRepo;
import io.oddsmaker.control.jpa.SystemConfigEntity;
import io.oddsmaker.control.jpa.SystemConfigRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 片B BRANCH 收口：RiskEventConsumer / ReportService / MetricAlertService /
 * MLModelService / MaintenanceService / FunnelConfigService 的分支对侧补充。
 * 全部为可达侧的输入构造；私有方法（validateAlias/describe/readJsonMap/readJsonList）
 * 经反射直调覆盖防御分支。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("片B服务分支对侧测试")
class BranchTopUpBTest {

    private final ObjectMapper om = new ObjectMapper();

    // ==================== RiskEventConsumer ====================

    @Mock private BlockListService blockListService;
    @Mock private WebhookService webhookService;
    @Mock private AuditLogService auditLogService;
    @Mock private RiskCaseRepo riskCaseRepo;
    @Mock private ReviewQueueService reviewQueueService;
    @Mock private RiskActionRecorder riskActionRecorder;
    @Mock private IdentityLinkRepo identityLinkRepo;

    private RiskEventConsumer consumer() {
        RiskEventConsumer c = new RiskEventConsumer();
        ReflectionTestUtils.setField(c, "objectMapper", om);
        ReflectionTestUtils.setField(c, "blockListService", blockListService);
        ReflectionTestUtils.setField(c, "webhookService", webhookService);
        ReflectionTestUtils.setField(c, "auditLogService", auditLogService);
        ReflectionTestUtils.setField(c, "riskCaseRepo", riskCaseRepo);
        ReflectionTestUtils.setField(c, "reviewQueueService", reviewQueueService);
        ReflectionTestUtils.setField(c, "riskActionRecorder", riskActionRecorder);
        ReflectionTestUtils.setField(c, "identityLinkRepo", identityLinkRepo);
        return c;
    }

    private String eventJson(String action, String severity, String subjectType, String subjectId) {
        try {
            RiskEventDto dto = new RiskEventDto();
            dto.action = action;
            dto.severity = severity;
            dto.subjectType = subjectType;
            dto.subjectId = subjectId;
            return om.writeValueAsString(dto);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("REVIEW/BLOCK：subjectType 非玩家/设备走 default 小写，null 走 unknown")
    void subjectTypeDefaultAndNullSides() {
        lenient().when(riskCaseRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        RiskEventConsumer c = consumer();

        // default + 非空 → toLowerCase
        c.onRiskEvent(eventJson("REVIEW", "HIGH", "IP", "1.2.3.4"));
        ArgumentCaptor<io.oddsmaker.control.jpa.RiskCaseEntity> caseCap =
            ArgumentCaptor.forClass(io.oddsmaker.control.jpa.RiskCaseEntity.class);
        verify(riskCaseRepo).save(caseCap.capture());
        assertEquals("ip", caseCap.getValue().targetType);

        // subjectType null → ternary 两侧 + default null 侧 → unknown
        org.mockito.Mockito.reset(riskCaseRepo);
        lenient().when(riskCaseRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        c.onRiskEvent(eventJson("REVIEW", null, null, null));
        verify(riskCaseRepo).save(caseCap.capture());
        assertEquals("unknown", caseCap.getValue().targetType);
        assertEquals(io.oddsmaker.control.jpa.RiskCaseEntity.RiskLevel.MEDIUM,
            caseCap.getValue().riskLevel);   // severity null → parseRiskLevel 默认 MEDIUM

        // BLOCK 侧：default 小写与 null unknown
        c.onRiskEvent(eventJson("BLOCK", "HIGH", "IP", "9.9.9.9"));
        verify(blockListService).addBlock(eq(null), eq(null), eq("ip"), eq("9.9.9.9"),
            anyString(), anyString(), any(), anyBoolean(), any(), anyString(), isNull(), isNull());

        c.onRiskEvent(eventJson("BLOCK", "HIGH", null, null));
        verify(blockListService).addBlock(eq(null), eq(null), eq("unknown"), isNull(),
            anyString(), anyString(), any(), anyBoolean(), any(), anyString(), isNull(), isNull());
    }

    @Test
    @DisplayName("BLOCK：severity null → 非永久且无时长（if 侧）")
    void blockSeverityNullSide() {
        consumer().onRiskEvent(eventJson("BLOCK", null, "PLAYER", "u1"));
        verify(blockListService).addBlock(any(), any(), eq("player_id"), eq("u1"),
            anyString(), anyString(), any(), eq(false), isNull(),
            anyString(), isNull(), isNull());
    }

    @Test
    @DisplayName("ALERT 全字段 null：审计载荷 null→空串/unknown 侧全覆盖")
    void alertWithAllNullFields() {
        consumer().onRiskEvent("{\"action\":\"alert\"}");
        verify(auditLogService).log(any(), eq("risk_event"), isNull(), eq("unknown"),
            isNull(), any(), eq("risk-automation"), isNull(), isNull(), isNull(), isNull(),
            eq(Map.of("gameId", "", "environment", "", "action", "alert",
                "subjectType", "", "subjectId", "", "ruleId", "", "score", "null")));
    }

    @Test
    @DisplayName("身份扩散：PLAYER 目标与非 DEVICE 主体直接跳过（短路侧）")
    void identityExtendSkipsNonDevice() {
        RiskEventConsumer c = consumer();
        ReflectionTestUtils.setField(c, "identityExtend", true);

        c.onRiskEvent(eventJson("BLOCK", "HIGH", "PLAYER", "u1"));
        verify(identityLinkRepo, never()).findByTypeAndId(anyString(), anyString());

        // device 目标但 subjectId null → 第三条件短路
        c.onRiskEvent(eventJson("BLOCK", "HIGH", "DEVICE", null));
        verify(identityLinkRepo, never()).findByTypeAndId(anyString(), anyString());
    }

    @Test
    @DisplayName("身份扩散：player/user 双类型纳入、device 忽略、10 上限内外层 break")
    void identityExtendFullWalk() {
        RiskEventConsumer c = consumer();
        ReflectionTestUtils.setField(c, "identityExtend", true);

        IdentityLinkEntity linkA = new IdentityLinkEntity();
        linkA.identityId = "idA";
        IdentityLinkEntity linkB = new IdentityLinkEntity();
        linkB.identityId = "idB";

        List<IdentityLinkEntity> inner = new ArrayList<>();
        for (int i = 0; i < 5; i++) inner.add(linked("player_id", "p" + i));
        for (int i = 0; i < 4; i++) inner.add(linked("user_id", "u" + i));
        inner.add(linked("device_id", "skip_me"));      // 类型不匹配 → 忽略侧
        inner.add(linked("player_id", "p10"));          // 第 10 个合格目标
        inner.add(linked("player_id", "p11_over"));     // 触发内层 break（extended>=10）

        when(identityLinkRepo.findByTypeAndId("device_id", "dev_1")).thenReturn(List.of(linkA, linkB));
        when(identityLinkRepo.findByIdentityId("idA")).thenReturn(inner);
        when(identityLinkRepo.findByIdentityId("idB")).thenReturn(List.of());

        c.onRiskEvent(eventJson("BLOCK", "HIGH", "DEVICE", "dev_1"));

        // 主封禁 1 + 扩散 10（player×6 + user×4），第 11 个被内层 break 拦截
        verify(blockListService, times(11)).addBlock(any(), any(), anyString(), anyString(),
            anyString(), anyString(), any(), anyBoolean(), any(), anyString(), isNull(), isNull());
        // 外层 break（extended>=10）在进入第二个 link 前触发，idB 永不被下钻
        verify(identityLinkRepo, never()).findByIdentityId("idB");
    }

    private IdentityLinkEntity linked(String type, String id) {
        IdentityLinkEntity e = new IdentityLinkEntity();
        e.identityId = "idA";
        e.linkedIdentityType = type;
        e.linkedId = id;
        return e;
    }

    // ==================== ReportService ====================

    @Mock private ReportRepo reportRepo;
    @Mock private ReportExecutionRepo executionRepo;
    @Mock private AuditLogService reportAudit;
    @Mock private ClickHouseClient clickHouse;

    private ReportService reportService() {
        ReportService s = new ReportService();
        ReflectionTestUtils.setField(s, "reportRepo", reportRepo);
        ReflectionTestUtils.setField(s, "executionRepo", executionRepo);
        ReflectionTestUtils.setField(s, "auditLogService", reportAudit);
        ReflectionTestUtils.setField(s, "objectMapper", om);
        ReflectionTestUtils.setField(s, "clickHouse", clickHouse);
        lenient().when(executionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(reportRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(clickHouse.isAvailable()).thenReturn(false);
        return s;
    }

    private ReportEntity report(String id, String granularity, String aggregations,
                                String timeRange, String queryConfig) {
        ReportEntity r = new ReportEntity();
        r.id = id;
        r.gameId = "g";
        r.timeGranularity = granularity;
        r.aggregations = aggregations;
        r.defaultTimeRange = timeRange;
        r.queryConfig = queryConfig;
        return r;
    }

    @Test
    @DisplayName("执行配置矩阵：粒度/聚合/时间窗/过滤的 null、空白、越界与类型不符侧")
    void executeReportConfigMatrix() {
        ReportService s = reportService();

        ReportEntity[] reports = {
            report("rA", null, null, null, null),                                   // 粒度 null/聚合 null/窗 null/配置 null
            report("rB", "   ", "{}", "   ", "{\"environment\":\"   \"}"),           // 粒度空白/聚合空映射/窗空白/环境空白
            report("rC", "day", null, "8761h", null),                                // 小时越界
            report("rD", "day", null, "366d", null),                                 // 天越界
            report("rE", "day", null, "1h", "{\"environment\":null,\"eventTypes\":\"a,b\"}"), // 环境null/类型非List
            report("rF", "day", null, "7d", "{\"eventTypes\":[]}"),                  // 空类型列表
            report("rG", "day", null, "7d", "{\"environment\":\"prod\",\"eventTypes\":[\"purchase\"]}")
        };
        for (ReportEntity r : reports) {
            when(reportRepo.findById(r.id)).thenReturn(Optional.of(r));
            ReportExecutionEntity exec = s.executeReport(r.id, "op", null, null, null);
            // CH 未配置/配置非法 → 诚实 FAILED（配置解析分支在可用性检查前全部走过）
            assertEquals(ReportExecutionEntity.ExecutionStatus.FAILED, exec.executionStatus,
                "report " + r.id + " 应失败收口");
        }
    }

    @Test
    @DisplayName("统计：triggerType null 归 unknown、null 均值/行数回落 0")
    void reportStatsNullSides() {
        ReportService s = reportService();
        when(executionRepo.countByReportId("rs1")).thenReturn(2L);
        when(executionRepo.countSuccessByReportId("rs1")).thenReturn(1L);
        when(executionRepo.averageExecutionTime("rs1")).thenReturn(null);
        when(executionRepo.sumRowCountByReportId("rs1")).thenReturn(null);
        when(executionRepo.findByReportId("rs1")).thenReturn(List.of(new ReportExecutionEntity()));

        Map<String, Object> stats = s.getReportStats("rs1");
        assertEquals(0.5, (double) stats.get("successRate"), 1e-9);   // totalRuns > 0 侧
        assertEquals(0.0, (double) stats.get("avgExecutionTimeMs"), 1e-9);
        assertEquals(0L, stats.get("totalRows"));
        assertEquals(Map.of("unknown", 1L), stats.get("byTriggerType"));   // triggerType null 侧
    }

    @Test
    @DisplayName("概览：reportCategory null 的报表被过滤出 byCategory")
    void reportOverviewNullCategoryFiltered() {
        ReportService s = reportService();
        ReportEntity r = new ReportEntity();
        r.id = "ov1";
        r.gameId = "g";
        r.reportCategory = null;
        when(reportRepo.findByGameId("g")).thenReturn(List.of(r));
        when(reportRepo.findPublishedByGameId("g")).thenReturn(List.of());
        when(reportRepo.findScheduledReports()).thenReturn(List.of());
        when(executionRepo.countByReportId("ov1")).thenReturn(0L);
        when(executionRepo.countSuccessByReportId("ov1")).thenReturn(0L);
        when(executionRepo.averageExecutionTime("ov1")).thenReturn(null);
        when(executionRepo.sumRowCountByReportId("ov1")).thenReturn(null);
        when(executionRepo.findByReportId("ov1")).thenReturn(List.of());

        Map<String, Object> overview = s.getGameReportOverview("g");
        assertEquals(1L, overview.get("draftReports"));
        assertTrue(((Map<?, ?>) overview.get("byCategory")).isEmpty());
    }

    @Test
    @DisplayName("定时任务：超时列表非空逐条标 TIMEOUT；清理删除数 >0 侧")
    void reportScheduledNonEmptySides() {
        ReportService s = reportService();
        ReportExecutionEntity timeout = new ReportExecutionEntity();
        timeout.id = "re_t";
        when(executionRepo.findTimeout(any())).thenReturn(List.of(timeout));
        assertDoesNotThrow(s::checkTimeoutExecutions);
        assertEquals(ReportExecutionEntity.ExecutionStatus.TIMEOUT, timeout.executionStatus);

        when(executionRepo.deleteExpired(any())).thenReturn(1).thenReturn(0);
        assertDoesNotThrow(s::cleanupExpiredExecutions);   // deleted > 0 侧
        assertDoesNotThrow(s::cleanupExpiredExecutions);   // deleted == 0 侧
    }

    @Test
    @DisplayName("反射直调：validateAlias(null) 拒绝、readJsonMap/readJsonList(null) 返回 null")
    void reportPrivateHelpersNullSides() {
        ReportService s = reportService();
        try {
            ReflectionTestUtils.invokeMethod(s, "validateAlias", (Object) null);
            fail("alias 为 null 应被拒绝");
        } catch (Exception e) {
            assertTrue(e instanceof IllegalArgumentException || e.getCause() instanceof IllegalArgumentException,
                "应包装 IllegalArgumentException，实际 " + e);
        }
        assertNull(ReflectionTestUtils.invokeMethod(s, "readJsonMap", (Object) null));
        assertNull(ReflectionTestUtils.invokeMethod(s, "readJsonList", (Object) null));
    }

    // ==================== MetricAlertService ====================

    @Mock private MetricAlertRuleRepo ruleRepo;
    @Mock private SystemAlertRepo alertRepo;
    @Mock private WebhookService alertWebhook;
    @Mock private AuditLogService alertAudit;

    private MetricAlertService alertService() {
        return new MetricAlertService(ruleRepo, alertRepo, clickHouse, alertWebhook,
            alertAudit, om, true);
    }

    private MetricAlertRuleEntity absolute(MetricAlertRuleEntity.Comparison cmp, double threshold) {
        MetricAlertRuleEntity r = new MetricAlertRuleEntity();
        r.id = "alr_1";
        r.gameId = "g";
        r.name = "收入下限";
        r.metricType = MetricAlertRuleEntity.MetricType.REVENUE;
        r.conditionType = MetricAlertRuleEntity.ConditionType.ABSOLUTE;
        r.comparison = cmp;
        r.threshold = threshold;
        return r;
    }

    private MetricAlertRuleEntity baseline(MetricAlertRuleEntity.Comparison cmp, double pct) {
        MetricAlertRuleEntity r = new MetricAlertRuleEntity();
        r.id = "alr_2";
        r.gameId = "g";
        r.name = "DAU 波动";
        r.metricType = MetricAlertRuleEntity.MetricType.DAU;
        r.conditionType = MetricAlertRuleEntity.ConditionType.BASELINE_DEVIATION;
        r.comparison = cmp;
        r.deviationPct = pct;
        return r;
    }

    @Test
    @DisplayName("校验对侧：name null、conditionType/comparison 单缺、deviationPct null")
    void alertValidateNullSides() {
        MetricAlertService s = alertService();

        MetricAlertRuleEntity noName = absolute(MetricAlertRuleEntity.Comparison.LT, 1);
        noName.name = null;
        assertThrows(IllegalArgumentException.class, () -> s.create("g", noName, "op"));

        MetricAlertRuleEntity noCond = absolute(MetricAlertRuleEntity.Comparison.LT, 1);
        noCond.conditionType = null;
        assertThrows(IllegalArgumentException.class, () -> s.create("g", noCond, "op"));

        MetricAlertRuleEntity noCmp = absolute(MetricAlertRuleEntity.Comparison.LT, 1);
        noCmp.comparison = null;
        assertThrows(IllegalArgumentException.class, () -> s.create("g", noCmp, "op"));

        MetricAlertRuleEntity noDev = baseline(MetricAlertRuleEntity.Comparison.BOTH, 30);
        noDev.deviationPct = null;
        assertThrows(IllegalArgumentException.class, () -> s.create("g", noDev, "op"));
    }

    @Test
    @DisplayName("试算：ABSOLUTE 不查基线（三元 null 侧）；current null 与未命中侧")
    void alertEvaluateNowSides() {
        MetricAlertService s = alertService();
        when(clickHouse.isAvailable()).thenReturn(true);
        when(ruleRepo.findById("alr_1")).thenReturn(Optional.of(absolute(
            MetricAlertRuleEntity.Comparison.LT, 1000.0)));

        // current 非空 + 命中 → fired true
        when(clickHouse.query(anyString(), any(Object[].class))).thenReturn(List.of(Map.of("v", 500.0)));
        Map<String, Object> hit = s.evaluateNow("g", "alr_1");
        assertEquals(true, hit.get("fired"));
        assertNull(hit.get("baseline"));   // ABSOLUTE → 基线三元 null 侧

        // current 非空 + 未命中 → fired false
        when(clickHouse.query(anyString(), any(Object[].class))).thenReturn(List.of(Map.of("v", 5000.0)));
        assertEquals(false, s.evaluateNow("g", "alr_1").get("fired"));

        // current null（空行）→ fired false 短路侧
        when(clickHouse.query(anyString(), any(Object[].class))).thenReturn(List.of());
        Map<String, Object> noData = s.evaluateNow("g", "alr_1");
        assertEquals(false, noData.get("fired"));
        assertNull(noData.get("currentValue"));
    }

    @Test
    @DisplayName("告警归属：alert.gameId null 视为不属本游戏拒绝")
    void alertOwnershipNullGameId() {
        MetricAlertService s = alertService();
        SystemAlertEntity alert = new SystemAlertEntity();
        alert.id = "alert_x";
        alert.gameId = null;
        when(alertRepo.findById("alert_x")).thenReturn(Optional.of(alert));
        assertThrows(IllegalArgumentException.class, () -> s.acknowledge("g", "alert_x", "op", null));
        assertThrows(IllegalArgumentException.class, () -> s.resolve("g", "alert_x", "op", null));
    }

    @Test
    @DisplayName("调度评估：规则未触发侧与空规则列表侧")
    void alertEvaluateAllSides() {
        MetricAlertService s = alertService();
        when(clickHouse.isAvailable()).thenReturn(true);

        // 单规则评估为 false（GT 未命中）→ if false 侧
        MetricAlertRuleEntity notFiring = absolute(MetricAlertRuleEntity.Comparison.GT, 1000.0);
        when(ruleRepo.findByEnabledTrueAndDeletedAtIsNull()).thenReturn(List.of(notFiring));
        when(clickHouse.query(anyString(), any(Object[].class))).thenReturn(List.of(Map.of("v", 500.0)));
        assertDoesNotThrow(s::evaluateAll);

        // 空规则列表 → !isEmpty false 侧
        when(ruleRepo.findByEnabledTrueAndDeletedAtIsNull()).thenReturn(List.of());
        assertDoesNotThrow(s::evaluateAll);
    }

    @Test
    @DisplayName("BASELINE GT 未达阈值侧（deviation >= n false）与 occurrenceCount null 侧")
    void alertGtMissAndOccurrenceNull() {
        MetricAlertService s = alertService();
        when(clickHouse.isAvailable()).thenReturn(true);

        // GT + 偏差 +20% < 30% → case GT 命中但 >= 为 false
        MetricAlertRuleEntity gt = baseline(MetricAlertRuleEntity.Comparison.GT, 30);
        when(clickHouse.query(anyString(), any(Object[].class)))
            .thenReturn(List.of(Map.of("v", 120.0)), List.of(Map.of("v", 100.0)));
        assertFalse(s.evaluateRule(gt));

        // 活跃告警 occurrenceCount null → 回落 1 再 +1
        SystemAlertEntity active = new SystemAlertEntity();
        active.id = "alert_a";
        active.gameId = "g";
        active.ruleId = "alr_1";
        active.occurrenceCount = null;
        when(alertRepo.findActiveByRuleId("alr_1")).thenReturn(List.of(active));
        when(ruleRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(clickHouse.query(anyString(), any(Object[].class))).thenReturn(List.of(Map.of("v", 500.0)));
        assertTrue(s.evaluateRule(absolute(MetricAlertRuleEntity.Comparison.LT, 1000.0)));
        assertEquals(2, active.occurrenceCount);
    }

    @Test
    @DisplayName("notifyWebhook=false 不派发；environment null/空白跳过环境过滤")
    void alertNoWebhookAndEnvSides() {
        MetricAlertService s = alertService();
        when(clickHouse.isAvailable()).thenReturn(true);
        when(ruleRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // notifyWebhook 显式 false → 单次 save、零 webhook
        MetricAlertRuleEntity quiet = absolute(MetricAlertRuleEntity.Comparison.LT, 1000.0);
        quiet.notifyWebhook = false;
        when(clickHouse.query(anyString(), any(Object[].class))).thenReturn(List.of(Map.of("v", 500.0)));
        assertTrue(s.evaluateRule(quiet));
        verify(alertWebhook, never()).sendCustomWebhook(anyString(), anyString(), any());
        verify(alertRepo, times(1)).save(any());

        // environment null → SQL 无 AND environment 片段
        when(clickHouse.query(contains("AND environment"), any(Object[].class)))
            .thenReturn(List.of(Map.of("v", 5000.0)));
        MetricAlertRuleEntity nullEnv = absolute(MetricAlertRuleEntity.Comparison.GT, 1000.0);
        nullEnv.environment = null;
        assertFalse(s.evaluateRule(nullEnv));
        MetricAlertRuleEntity blankEnv = absolute(MetricAlertRuleEntity.Comparison.GT, 1000.0);
        blankEnv.environment = "   ";
        assertFalse(s.evaluateRule(blankEnv));
    }

    @Test
    @DisplayName("查询解析：v 缺失返回 null、字符串值走 parseDouble")
    void alertQueryValueSides() {
        MetricAlertService s = alertService();
        when(clickHouse.isAvailable()).thenReturn(true);
        when(ruleRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // 行存在但无 v 键 → null → 本轮跳过
        when(clickHouse.query(anyString(), any(Object[].class))).thenReturn(List.of(Map.of()));
        assertFalse(s.evaluateRule(absolute(MetricAlertRuleEntity.Comparison.LT, 1000.0)));

        // v 为字符串 → parseDouble 路径
        when(clickHouse.query(anyString(), any(Object[].class)))
            .thenReturn(List.of(Map.of("v", "500.0")));
        assertTrue(s.evaluateRule(absolute(MetricAlertRuleEntity.Comparison.LT, 1000.0)));
    }

    @Test
    @DisplayName("describe 防御侧：BASELINE 基线 null 时偏差展示 n/a（经 fired 不可达，反射直调）")
    void alertDescribeNullDeviationSide() {
        MetricAlertService s = alertService();
        MetricAlertRuleEntity rule = baseline(MetricAlertRuleEntity.Comparison.BOTH, 30);
        String text = ReflectionTestUtils.invokeMethod(s, "describe", rule, 100.0, null);
        assertNotNull(text);
        assertTrue(text.contains("n/a"));
    }

    // ==================== MLModelService ====================

    @Mock private MLModelRepo mlModelRepo;
    @Mock private ModelTrainingRepo modelTrainingRepo;
    @Mock private ModelPredictionRepo modelPredictionRepo;
    @Mock private AuditLogService mlAudit;
    @Mock private WebhookService mlWebhook;

    private MLModelService mlService() {
        MLModelService s = new MLModelService();
        ReflectionTestUtils.setField(s, "mlModelRepo", mlModelRepo);
        ReflectionTestUtils.setField(s, "modelTrainingRepo", modelTrainingRepo);
        ReflectionTestUtils.setField(s, "modelPredictionRepo", modelPredictionRepo);
        ReflectionTestUtils.setField(s, "auditLogService", mlAudit);
        ReflectionTestUtils.setField(s, "webhookService", mlWebhook);
        lenient().when(mlModelRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(modelTrainingRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(modelPredictionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        return s;
    }

    private MLModelEntity model(String id, MLModelEntity.ModelStatus status) {
        MLModelEntity m = new MLModelEntity();
        m.id = id;
        m.gameId = "g";
        m.modelName = "m_" + id;
        m.modelStatus = status;
        m.modelArtifactPath = "s3://artifact/" + id;
        return m;
    }

    @Test
    @DisplayName("训练任务：三配置全 null 创建、进度 metrics null、完成 metrics null")
    void mlTrainingNullConfigSides() {
        MLModelService s = mlService();
        when(mlModelRepo.findById("m1")).thenReturn(Optional.of(model("m1", MLModelEntity.ModelStatus.DRAFT)));
        ModelTrainingEntity job = s.createTrainingJob("m1", "job1", null, null, null, "op");
        assertEquals("manual", job.triggerType);

        // 进度更新 metrics null 侧
        job.start();
        when(modelTrainingRepo.findById(job.id)).thenReturn(Optional.of(job));
        ModelTrainingEntity progressed = s.updateTrainingProgress(job.id, 1, 10, 0.5, null);
        assertNull(progressed.trainingMetrics);

        // 完成 finalMetrics null 侧（两处 if）；模型同步走 completeTraining()
        MLModelEntity m1 = model("m1", MLModelEntity.ModelStatus.TRAINING);
        when(mlModelRepo.findById("m1")).thenReturn(Optional.of(m1));
        ModelTrainingEntity done = s.completeTraining(job.id, "s3://out", null);
        assertNull(done.validationMetrics);
        assertEquals(MLModelEntity.ModelStatus.EVALUATING, m1.modelStatus);
    }

    @Test
    @DisplayName("部署：config null 与空 map 跳过序列化/端点键；A/B config null")
    void mlDeployNullConfigSides() {
        MLModelService s = mlService();
        when(mlModelRepo.findById("m_cfg")).thenReturn(Optional.of(model("m_cfg", MLModelEntity.ModelStatus.EVALUATING)));
        MLModelEntity deployed = s.deployModel("m_cfg", null, "op");
        assertEquals(MLModelEntity.ModelStatus.DEPLOYED, deployed.modelStatus);
        assertNull(deployed.deploymentConfig);
        assertNull(deployed.servingEndpoint);

        when(mlModelRepo.findById("m_empty")).thenReturn(Optional.of(model("m_empty", MLModelEntity.ModelStatus.STAGING)));
        MLModelEntity emptyCfg = s.deployModel("m_empty", Map.of(), "op");
        assertNotNull(emptyCfg.deploymentConfig);   // 序列化了空对象
        assertNull(emptyCfg.servingEndpoint);       // containsKey false 侧
        // canaryDeployment 有 false 初始化器，containsKey 不命中时保持 false 而非 null
        assertFalse(emptyCfg.canaryDeployment);

        // 全键配置 → servingEndpoint/canary 回写
        when(mlModelRepo.findById("m_full")).thenReturn(Optional.of(model("m_full", MLModelEntity.ModelStatus.EVALUATING)));
        Map<String, Object> full = new HashMap<>();
        full.put("servingEndpoint", "http://ep");
        full.put("canaryDeployment", true);
        MLModelEntity fullCfg = s.deployModel("m_full", full, "op");
        assertEquals("http://ep", fullCfg.servingEndpoint);
        assertEquals(Boolean.TRUE, fullCfg.canaryDeployment);

        // A/B 配置 null 侧（模型需已部署）
        MLModelEntity ab = s.configureAbTest("m_cfg", "m_base", 50, null, "op");
        assertTrue(ab.isAbTest);
        assertNull(ab.abTestConfig);
    }

    @Test
    @DisplayName("预测：inputData null、output null/空/全键三态")
    void mlPredictionSides() {
        MLModelService s = mlService();
        when(mlModelRepo.findById("m1")).thenReturn(Optional.of(model("m1", MLModelEntity.ModelStatus.DEPLOYED)));

        MLModelPredictionEntity rec = s.recordPrediction("m1", "user", "u1", null, "req", "cli", "api");
        assertNull(rec.inputData);

        MLModelPredictionEntity p = new MLModelPredictionEntity();
        when(modelPredictionRepo.findById("p1")).thenReturn(Optional.of(p));
        MLModelPredictionEntity withNull = s.completePrediction("p1", null, null, null);
        assertNull(withNull.outputPrediction);

        MLModelPredictionEntity p2 = new MLModelPredictionEntity();
        when(modelPredictionRepo.findById("p2")).thenReturn(Optional.of(p2));
        MLModelPredictionEntity withEmpty = s.completePrediction("p2", Map.of(), 0.9, 5);
        assertNull(withEmpty.predictionClass);
        assertNull(withEmpty.predictionProbability);
        assertNull(withEmpty.topPredictions);
        assertNull(withEmpty.featureImportance);

        MLModelPredictionEntity p3 = new MLModelPredictionEntity();
        when(modelPredictionRepo.findById("p3")).thenReturn(Optional.of(p3));
        Map<String, Object> out = new HashMap<>();
        out.put("class", "churn");
        out.put("probability", 0.75);
        out.put("topPredictions", List.of(Map.of("churn", 0.75), Map.of("keep", 0.25)));
        out.put("featureImportance", Map.of("sessions", 0.6));
        out.put("explanation", "feature drift");
        MLModelPredictionEntity full = s.completePrediction("p3", out, 0.75, 12);
        assertEquals("churn", full.predictionClass);
        assertEquals(0.75, full.predictionProbability, 1e-9);
        assertNotNull(full.topPredictions);
        assertNotNull(full.featureImportance);
        assertEquals("feature drift", full.explanation);
    }

    @Test
    @DisplayName("统计：近邻训练 durationMs null 回落 0")
    void mlStatisticsNullDuration() {
        MLModelService s = mlService();
        when(mlModelRepo.findById("m1")).thenReturn(Optional.of(model("m1", MLModelEntity.ModelStatus.DEPLOYED)));
        ModelTrainingEntity noDuration = new ModelTrainingEntity();
        noDuration.durationMs = null;
        ModelTrainingEntity withDuration = new ModelTrainingEntity();
        withDuration.durationMs = 5L;
        when(modelTrainingRepo.countByModelId("m1")).thenReturn(2L);
        when(modelTrainingRepo.calculateAverageDuration("m1")).thenReturn(null);
        when(modelTrainingRepo.findRecentByModelId("m1")).thenReturn(List.of(noDuration, withDuration));
        when(modelPredictionRepo.countByModelId("m1")).thenReturn(0L);
        when(modelPredictionRepo.calculateAverageLatency("m1")).thenReturn(null);
        when(modelPredictionRepo.countByFeedbackType("m1")).thenReturn(List.of());

        Map<String, Object> stats = s.getModelStatistics("m1");
        List<Map<String, Object>> recent = (List<Map<String, Object>>) stats.get("recentTrainings");
        // 三元装箱形态是 Long，按 Number 取值断言避免 Integer/Long equals 失配
        assertEquals(0, ((Number) recent.get(0).get("durationMs")).intValue());
        assertEquals(5, ((Number) recent.get(1).get("durationMs")).intValue());
    }

    @Test
    @DisplayName("超时训练：模型仍处 TRAINING → 一并置 FAILED（isTraining true 侧）")
    void mlTimedOutTrainingFailsModel() {
        MLModelService s = mlService();
        ModelTrainingEntity job = new ModelTrainingEntity();
        job.id = "train_x";
        job.modelId = "m1";
        when(modelTrainingRepo.findTimedOutJobs(any())).thenReturn(List.of(job));

        MLModelEntity training = model("m1", MLModelEntity.ModelStatus.TRAINING);
        when(mlModelRepo.findById("m1")).thenReturn(Optional.of(training));

        s.detectTimedOutTrainings();
        assertEquals(MLModelEntity.ModelStatus.FAILED, training.modelStatus);
        verify(mlModelRepo).save(training);
    }

    @Test
    @DisplayName("超时训练：模型已不处 TRAINING（如 DEPLOYED）→ 不连带置 FAILED（isTraining false 侧）")
    void mlTimedOutTrainingSparesNonTrainingModel() {
        MLModelService s = mlService();
        ModelTrainingEntity job = new ModelTrainingEntity();
        job.id = "train_y";
        job.modelId = "m2";
        when(modelTrainingRepo.findTimedOutJobs(any())).thenReturn(List.of(job));

        MLModelEntity deployed = model("m2", MLModelEntity.ModelStatus.DEPLOYED);
        when(mlModelRepo.findById("m2")).thenReturn(Optional.of(deployed));

        s.detectTimedOutTrainings();
        assertEquals(MLModelEntity.ModelStatus.DEPLOYED, deployed.modelStatus);  // 状态不动
        verify(mlModelRepo, never()).save(deployed);
    }

    // ==================== MaintenanceService ====================

    @Mock private MaintenanceWindowRepo maintenanceWindowRepo;
    @Mock private SystemConfigRepo systemConfigRepo;
    @Mock private FeatureFlagRepo featureFlagRepo;
    @Mock private AuditLogService mwAudit;
    @Mock private WebhookService mwWebhook;

    private MaintenanceService maintenanceService() {
        MaintenanceService s = new MaintenanceService();
        ReflectionTestUtils.setField(s, "maintenanceWindowRepo", maintenanceWindowRepo);
        ReflectionTestUtils.setField(s, "systemConfigRepo", systemConfigRepo);
        ReflectionTestUtils.setField(s, "featureFlagRepo", featureFlagRepo);
        ReflectionTestUtils.setField(s, "auditLogService", mwAudit);
        ReflectionTestUtils.setField(s, "webhookService", mwWebhook);
        lenient().when(maintenanceWindowRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(featureFlagRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        return s;
    }

    private MaintenanceWindowEntity window(String gameId, boolean nullOutFields) {
        MaintenanceWindowEntity w = new MaintenanceWindowEntity();
        w.id = "mw_" + System.nanoTime();
        w.title = "t";
        w.gameId = gameId;
        if (nullOutFields) {
            w.maintenanceType = null;
            w.impactScope = null;
            w.scheduledStart = null;
            w.scheduledEnd = null;
        }
        return w;
    }

    @Test
    @DisplayName("列表过滤：已软删配置与开关被剔除")
    void maintenanceFiltersDeleted() {
        MaintenanceService s = maintenanceService();
        SystemConfigEntity kept = new SystemConfigEntity();
        SystemConfigEntity deleted = new SystemConfigEntity();
        deleted.deletedAt = LocalDateTime.now();
        when(systemConfigRepo.findAll()).thenReturn(List.of(kept, deleted));
        assertEquals(1, s.getAllConfigs().size());

        FeatureFlagEntity flagKept = new FeatureFlagEntity();
        FeatureFlagEntity flagDeleted = new FeatureFlagEntity();
        flagDeleted.deletedAt = LocalDateTime.now();
        when(featureFlagRepo.findAll()).thenReturn(List.of(flagKept, flagDeleted));
        assertEquals(1, s.getAllFeatureFlags().size());
    }

    @Test
    @DisplayName("灰度推进：steps null/空白拒绝，合法步骤回写百分比")
    void maintenanceAdvanceRolloutSides() {
        MaintenanceService s = maintenanceService();
        FeatureFlagEntity nullSteps = new FeatureFlagEntity();
        nullSteps.flagKey = "f_null";
        nullSteps.flagStatus = FeatureFlagEntity.FlagStatus.STAGED_ROLLOUT;
        nullSteps.rolloutSteps = null;
        when(featureFlagRepo.findByKey("f_null")).thenReturn(Optional.of(nullSteps));
        assertThrows(IllegalArgumentException.class, () -> s.advanceFeatureRollout("f_null", "op"));

        FeatureFlagEntity blankSteps = new FeatureFlagEntity();
        blankSteps.flagKey = "f_blank";
        blankSteps.flagStatus = FeatureFlagEntity.FlagStatus.STAGED_ROLLOUT;
        blankSteps.rolloutSteps = "   ";
        when(featureFlagRepo.findByKey("f_blank")).thenReturn(Optional.of(blankSteps));
        assertThrows(IllegalArgumentException.class, () -> s.advanceFeatureRollout("f_blank", "op"));

        FeatureFlagEntity valid = new FeatureFlagEntity();
        valid.flagKey = "f_ok";
        valid.flagStatus = FeatureFlagEntity.FlagStatus.STAGED_ROLLOUT;
        valid.rolloutSteps = "[10,50,100]";
        when(featureFlagRepo.findByKey("f_ok")).thenReturn(Optional.of(valid));
        FeatureFlagEntity advanced = s.advanceFeatureRollout("f_ok", "op");
        assertEquals(10, advanced.percentageValue);
        assertEquals(1, advanced.currentStep);
    }

    @Test
    @DisplayName("待开始巡检：gameId 非空派发 webhook、null/空白跳过；载荷 null 字段侧")
    void maintenancePendingChecksSides() {
        MaintenanceService s = maintenanceService();
        when(maintenanceWindowRepo.findPendingUnnotified(any())).thenReturn(List.of(
            window("g", true), window(null, false), window("   ", false)));

        assertDoesNotThrow(s::checkPendingMaintenances);
        verify(mwWebhook, times(1)).sendCustomWebhook(eq("g"),
            eq(WebhookService.EVENT_MAINTENANCE_UPCOMING), any());
    }

    @Test
    @DisplayName("应结束巡检：同款 gameId 三态与 null 字段载荷")
    void maintenanceEndingChecksSides() {
        MaintenanceService s = maintenanceService();
        when(maintenanceWindowRepo.findShouldEndUnnotified(any())).thenReturn(List.of(
            window("g", true), window(null, false), window("   ", false)));

        assertDoesNotThrow(s::checkEndingMaintenances);
        verify(mwWebhook, times(1)).sendCustomWebhook(eq("g"),
            eq(WebhookService.EVENT_MAINTENANCE_ENDING), any());
    }

    @Test
    @DisplayName("开关巡检三组合：仅启用/仅禁用/仅过期各自触发日志分支")
    void maintenanceScheduledFlagCombinations() {
        MaintenanceService s = maintenanceService();

        FeatureFlagEntity f1 = new FeatureFlagEntity();
        f1.flagKey = "f1";
        when(featureFlagRepo.findScheduledToEnable(any())).thenReturn(List.of(f1));
        when(featureFlagRepo.findScheduledToDisable(any())).thenReturn(List.of());
        when(featureFlagRepo.findExpired(any())).thenReturn(List.of());
        assertDoesNotThrow(s::checkScheduledFeatureFlags);
        assertEquals(FeatureFlagEntity.FlagStatus.ENABLED, f1.flagStatus);

        FeatureFlagEntity f2 = new FeatureFlagEntity();
        f2.flagKey = "f2";
        when(featureFlagRepo.findScheduledToEnable(any())).thenReturn(List.of());
        when(featureFlagRepo.findScheduledToDisable(any())).thenReturn(List.of(f2));
        when(featureFlagRepo.findExpired(any())).thenReturn(List.of());
        assertDoesNotThrow(s::checkScheduledFeatureFlags);
        assertEquals(FeatureFlagEntity.FlagStatus.DISABLED, f2.flagStatus);

        FeatureFlagEntity f3 = new FeatureFlagEntity();
        f3.flagKey = "f3";
        when(featureFlagRepo.findScheduledToEnable(any())).thenReturn(List.of());
        when(featureFlagRepo.findScheduledToDisable(any())).thenReturn(List.of());
        when(featureFlagRepo.findExpired(any())).thenReturn(List.of(f3));
        assertDoesNotThrow(s::checkScheduledFeatureFlags);
        assertEquals(FeatureFlagEntity.FlagStatus.DISABLED, f3.flagStatus);
    }

    // ==================== FunnelConfigService ====================

    @Mock private FunnelConfigRepo funnelConfigRepo;
    @Mock private FunnelStepRepo funnelStepRepo;
    @Mock private AuditLogService funnelAudit;

    private FunnelConfigService funnelService() {
        FunnelConfigService s = new FunnelConfigService();
        ReflectionTestUtils.setField(s, "funnelConfigRepo", funnelConfigRepo);
        ReflectionTestUtils.setField(s, "funnelStepRepo", funnelStepRepo);
        ReflectionTestUtils.setField(s, "auditLog", funnelAudit);
        lenient().when(funnelConfigRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(funnelStepRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(funnelConfigRepo.existsByGameIdAndNameAndDeletedAtIsNull(anyString(), anyString()))
            .thenReturn(false);
        return s;
    }

    @Test
    @DisplayName("创建：id 空白生成、显式 id 保留")
    void funnelCreateIdSides() {
        FunnelConfigService s = funnelService();
        FunnelConfigEntity blank = new FunnelConfigEntity();
        blank.gameId = "g";
        blank.name = "funA";
        blank.id = "   ";
        assertTrue(s.createFunnel(blank).id.startsWith("funnel_"));

        FunnelConfigEntity given = new FunnelConfigEntity();
        given.gameId = "g";
        given.name = "funB";
        given.id = "my_custom_id";
        assertEquals("my_custom_id", s.createFunnel(given).id);
    }

    @Test
    @DisplayName("更新：软删漏斗拒绝；同名免查重；部分字段 null 不覆盖")
    void funnelUpdateSides() {
        FunnelConfigService s = funnelService();
        FunnelConfigEntity existing = new FunnelConfigEntity();
        existing.id = "f1";
        existing.gameId = "g";
        existing.name = "n1";
        lenient().when(funnelConfigRepo.findById("f1")).thenReturn(Optional.of(existing));

        // 同名 → 不触发查重（条件 false 侧）
        FunnelConfigEntity sameName = new FunnelConfigEntity();
        sameName.name = "n1";
        s.updateFunnel("f1", sameName);
        verify(funnelConfigRepo, never()).existsByGameIdAndNameAndDeletedAtIsNull(anyString(), anyString());

        // 部分更新：type/userKey/enabled 显式 null → 不覆盖
        FunnelConfigEntity partial = new FunnelConfigEntity();
        partial.name = "n2";
        partial.type = null;
        partial.userKey = null;
        partial.enabled = null;
        FunnelConfigEntity updated = s.updateFunnel("f1", partial);
        assertEquals("n2", updated.name);
        assertEquals(FunnelConfigEntity.FunnelType.STANDARD, updated.type);
        assertEquals("user_id", updated.userKey);
        assertEquals(Boolean.TRUE, updated.enabled);

        // 软删漏斗：findById 有值但 deletedAt 非空 → 拒绝
        FunnelConfigEntity deleted = new FunnelConfigEntity();
        deleted.id = "fdel";
        deleted.deletedAt = LocalDateTime.now();
        when(funnelConfigRepo.findById("fdel")).thenReturn(Optional.of(deleted));
        assertThrows(IllegalArgumentException.class, () -> s.updateFunnel("fdel", new FunnelConfigEntity()));
    }

    @Test
    @DisplayName("查改入口：软删漏斗 findById/toggle/addStep 均拒绝")
    void funnelDeletedLookupsRejected() {
        FunnelConfigService s = funnelService();
        FunnelConfigEntity deleted = new FunnelConfigEntity();
        deleted.id = "fdel";
        deleted.deletedAt = LocalDateTime.now();
        when(funnelConfigRepo.findById("fdel")).thenReturn(Optional.of(deleted));

        assertThrows(IllegalArgumentException.class, () -> s.findById("fdel"));
        assertThrows(IllegalArgumentException.class, () -> s.toggleFunnel("fdel", true));
        assertThrows(IllegalArgumentException.class, () -> s.addStep("fdel", new FunnelStepEntity()));
    }

    @Test
    @DisplayName("步骤更新：全 null 更新体不覆盖任何字段")
    void funnelStepPartialUpdate() {
        FunnelConfigService s = funnelService();
        FunnelStepEntity step = new FunnelStepEntity();
        step.id = "s1";
        step.name = "step1";
        step.eventName = "ev1";
        step.optional = false;
        when(funnelStepRepo.findById("s1")).thenReturn(Optional.of(step));

        FunnelStepEntity updated = s.updateStep("s1", new FunnelStepEntity());
        assertEquals("step1", updated.name);
        assertEquals("ev1", updated.eventName);
        assertEquals(Boolean.FALSE, updated.optional);
    }
}
