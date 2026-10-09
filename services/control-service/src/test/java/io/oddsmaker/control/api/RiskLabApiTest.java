package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.RiskCaseEntity;
import io.oddsmaker.control.jpa.RiskCaseRepo;
import io.oddsmaker.control.jpa.RiskRuleEntity;
import io.oddsmaker.control.jpa.RiskRuleRepo;
import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.RiskLabReplayService;
import io.oddsmaker.control.service.RiskLabService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 策略实验室测试：Controller 鉴权透传 + ruleStats 聚合口径
 * （分桶/误杀率分母=已复盘、平均复盘时长、零案例规则、孤儿规则行、排序稳定性）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("策略实验室复盘聚合测试")
class RiskLabApiTest {

    private static final String GAME = "game_demo";

    @Mock
    private AccessGuard accessGuard;

    @Mock
    private RiskRuleRepo riskRuleRepo;

    @Mock
    private RiskCaseRepo riskCaseRepo;

    @Mock
    private RiskLabService riskLabService;

    @Mock
    private RiskLabReplayService riskLabReplayService;

    private RiskLabController controller;
    private RiskLabService service;

    @BeforeEach
    void setUp() {
        service = new RiskLabService(riskRuleRepo, riskCaseRepo);
        controller = new RiskLabController(riskLabService, riskLabReplayService, accessGuard);
    }

    // ===== Controller =====

    @Test
    @DisplayName("rule-stats：game:read 鉴权并返回聚合体")
    void controllerGuardsAndDelegates() {
        when(riskLabService.ruleStats(GAME)).thenReturn(Map.of("gameId", GAME));

        Map<String, Object> body = controller.ruleStats(GAME);

        assertEquals(GAME, body.get("gameId"));
        verify(accessGuard).requireGamePermission(GAME, "game:read");
    }

    @Test
    @DisplayName("replay：game:read 鉴权并透传 samples/ruleIds 给试算服务")
    void replayGuardsAndDelegates() {
        List<Map<String, Object>> samples = List.of(Map.of("eventId", "s1", "amount", 100));
        List<String> ruleIds = List.of("rr_1");
        when(riskLabReplayService.dryRun(GAME, samples, ruleIds)).thenReturn(Map.of("gameId", GAME));

        RiskLabController.ReplayRequest req = new RiskLabController.ReplayRequest();
        req.samples = samples;
        req.ruleIds = ruleIds;
        Map<String, Object> body = controller.replay(GAME, req);

        assertEquals(GAME, body.get("gameId"));
        verify(accessGuard).requireGamePermission(GAME, "game:read");
        verify(riskLabReplayService).dryRun(GAME, samples, ruleIds);
    }

    // ===== Service：聚合口径 =====

    @Test
    @DisplayName("聚合：分桶计数正确，误杀率分母=已复盘，平均复盘时长按时长均值取一位小数")
    void aggregationBucketsAndRates() {
        RiskRuleEntity rr1 = rule("rr_1", "大额充值", 85);
        rr1.displayName = "大额充值（显示名）";   // 名称优先 displayName，空才回退 name
        stubRules(rr1);
        LocalDateTime base = LocalDateTime.of(2026, 10, 9, 10, 0);
        // rr_1：误杀 1（复盘 2h）+ 确认违规 2（复盘 4h、6h）+ 证据不足 1 + 未复盘 1
        when(riskCaseRepo.findByGameIdOrderByCreatedAtDesc(eq(GAME), eq(PageRequest.of(0, 5000))))
            .thenReturn(List.of(
                caseOf("rc_b", "rr_1", "confirmed_benign", base, base.plusHours(2)),
                caseOf("rc_f1", "rr_1", "confirmed_fraud", base, base.plusHours(4)),
                caseOf("rc_f2", "rr_1", "confirmed_fraud", base, base.plusHours(6)),
                caseOf("rc_i", "rr_1", "inconclusive", base, null),
                caseOf("rc_n", "rr_1", null, base, null)));

        Map<String, Object> result = service.ruleStats(GAME);

        Map<String, Object> row = ruleRow(result, "rr_1");
        assertEquals(5L, row.get("caseCount"));
        assertEquals(1L, row.get("falsePositiveCount"));
        assertEquals(2L, row.get("confirmedFraudCount"));
        assertEquals(1L, row.get("inconclusiveCount"));
        assertEquals(1L, row.get("unreviewedCount"));
        assertEquals(3L, row.get("reviewedCount"));
        // 误杀率 = 1/4 已处置 = 25.0（未复盘不进分母）；平均复盘时长 = (2+4+6)/3（有 reviewedAt 的 3 条）= 4.0
        assertEquals(25.0, row.get("misKillRate"));
        assertEquals(4.0, row.get("avgReviewHours"));
        assertEquals("大额充值（显示名）", row.get("ruleName"));
        assertEquals(85, row.get("riskScore"));
        assertEquals("ACTIVE", row.get("ruleStatus"));

        Map<String, Object> totals = (Map<String, Object>) result.get("totals");
        assertEquals(5L, totals.get("totalCases"));
        assertEquals(1L, totals.get("totalFalsePositives"));
        assertEquals(1L, totals.get("rulesWithCases"));
        assertEquals(5, result.get("scannedCases"));
    }

    @Test
    @DisplayName("聚合：零案例规则也出行（比率/时长 null）；孤儿规则行不丢计数；按案例数降序稳定排序")
    void zeroCaseRulesAndOrphans() {
        stubRules(rule("rr_zero", "零案例规则", 30), rule("rr_1", "有案例规则", 50));
        LocalDateTime base = LocalDateTime.of(2026, 10, 9, 10, 0);
        // rr_1 两条 + 已删规则 rr_gone 一条
        when(riskCaseRepo.findByGameIdOrderByCreatedAtDesc(eq(GAME), eq(PageRequest.of(0, 5000))))
            .thenReturn(List.of(
                caseOf("rc_g", "rr_gone", "confirmed_benign", base, base.plusHours(1)),
                caseOf("rc_1", "rr_1", null, base, null),
                caseOf("rc_2", "rr_1", null, base, null)));

        Map<String, Object> result = service.ruleStats(GAME);

        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("rules");
        assertEquals(3, rows.size());
        // 排序：rr_1(2) → rr_gone(1) → rr_zero(0)
        assertEquals(List.of("rr_1", "rr_gone", "rr_zero"),
            rows.stream().map(r -> r.get("ruleId")).toList());

        Map<String, Object> zero = rows.get(2);
        assertEquals(0L, zero.get("caseCount"));
        assertNull(zero.get("misKillRate"));
        assertNull(zero.get("avgReviewHours"));
        assertEquals("零案例规则", zero.get("ruleName"));

        Map<String, Object> orphan = rows.get(1);
        assertEquals(1L, orphan.get("caseCount"));
        assertNull(orphan.get("ruleName"));      // 规则已删：名称/状态/分空
        assertEquals(100.0, orphan.get("misKillRate"));

        Map<String, Object> totals = (Map<String, Object>) result.get("totals");
        assertEquals(2L, totals.get("rulesWithCases"));   // 零案例规则不计
    }

    @Test
    @DisplayName("聚合：无规则无案例的空游戏出空表，不触碰案例以外的语义")
    void emptyGame() {
        stubRules();
        when(riskCaseRepo.findByGameIdOrderByCreatedAtDesc(eq(GAME), eq(PageRequest.of(0, 5000))))
            .thenReturn(List.of());

        Map<String, Object> result = service.ruleStats(GAME);

        assertTrue(((List<?>) result.get("rules")).isEmpty());
        Map<String, Object> totals = (Map<String, Object>) result.get("totals");
        assertEquals(0L, totals.get("totalCases"));
        assertEquals(0L, totals.get("totalFalsePositives"));
        assertEquals(0L, totals.get("rulesWithCases"));
    }

    // ===== 辅助 =====

    private void stubRules(RiskRuleEntity... rules) {
        when(riskRuleRepo.findByGameId(GAME)).thenReturn(List.of(rules));
    }

    private static RiskRuleEntity rule(String id, String name, int score) {
        RiskRuleEntity r = new RiskRuleEntity();
        r.id = id;
        r.gameId = GAME;
        r.name = name;
        r.riskScore = score;
        r.status = RiskRuleEntity.RuleStatus.ACTIVE;
        return r;
    }

    private static RiskCaseEntity caseOf(String id, String ruleId, String disposition,
                                         LocalDateTime createdAt, LocalDateTime reviewedAt) {
        RiskCaseEntity rc = new RiskCaseEntity();
        rc.id = id;
        rc.gameId = GAME;
        rc.caseNumber = "CASE_" + id.toUpperCase();
        rc.riskRuleId = ruleId;
        rc.targetType = "player_id";
        rc.targetId = "p_" + id;
        rc.riskLevel = RiskCaseEntity.RiskLevel.HIGH;
        rc.status = RiskCaseEntity.DecisionStatus.RESOLVED;
        rc.actionTaken = RiskCaseEntity.ActionType.ALERT;
        rc.executionStatus = RiskCaseEntity.ExecutionStatus.EXECUTED;
        rc.disposition = disposition;
        rc.createdAt = createdAt;
        rc.reviewedAt = reviewedAt;
        return rc;
    }

    private static Map<String, Object> ruleRow(Map<String, Object> result, String ruleId) {
        return ((List<Map<String, Object>>) result.get("rules")).stream()
            .filter(r -> ruleId.equals(r.get("ruleId")))
            .findFirst().orElseThrow();
    }
}
