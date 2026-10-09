package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.jpa.RiskRuleEntity;
import io.oddsmaker.control.jpa.RiskRuleRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 策略实验室试算回放测试（V0.3 dry-run）：
 * THRESHOLD 严格大于、FEATURE 全 AND + 缺值 fail-closed、非法条件整条作废、
 * 流式类型明示跳过、ruleIds 过滤、同类型按 riskScore 收敛、样本校验。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("策略实验室试算回放测试")
class RiskLabReplayServiceTest {

    private static final String GAME = "game_demo";

    @Mock
    private RiskRuleRepo riskRuleRepo;

    private RiskLabReplayService service;

    @BeforeEach
    void setUp() {
        service = new RiskLabReplayService(riskRuleRepo, new ObjectMapper());
    }

    private RiskRuleEntity rule(String id, RiskRuleEntity.RuleType type, int riskScore) {
        RiskRuleEntity r = new RiskRuleEntity();
        r.id = id;
        r.gameId = GAME;
        r.name = "rule-" + id;
        r.ruleType = type;
        r.riskScore = riskScore;
        r.riskLevel = RiskRuleEntity.RiskLevel.HIGH;
        r.actionType = RiskRuleEntity.ActionType.ALERT;
        return r;
    }

    private Map<String, Object> sample(Object eventId, Object amount, Map<String, Object> features) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (eventId != null) {
            m.put("eventId", eventId);
        }
        if (amount != null) {
            m.put("amount", amount);
        }
        if (features != null) {
            m.put("features", features);
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> ruleRow(Map<String, Object> report, String ruleId) {
        for (Map<String, Object> row : (List<Map<String, Object>>) report.get("ruleResults")) {
            if (ruleId.equals(row.get("ruleId"))) {
                return row;
            }
        }
        throw new AssertionError("rule row not found: " + ruleId);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> sampleResults(Map<String, Object> report) {
        return (List<Map<String, Object>>) report.get("sampleResults");
    }

    @Test
    @DisplayName("THRESHOLD 严格大于阈值：等于不命中、超出命中、缺金额不命中")
    void thresholdStrictlyGreater() {
        RiskRuleEntity r = rule("rr_t", RiskRuleEntity.RuleType.THRESHOLD, 80);
        r.triggerThreshold = 1000;
        when(riskRuleRepo.findActiveByGameId(GAME)).thenReturn(List.of(r));

        Map<String, Object> report = service.dryRun(GAME, List.of(
                sample("s1", 1000, null),          // 等于：不命中
                sample("s2", 1001, null),          // 严格大于：命中
                sample("s3", null, null),          // 缺金额：不命中
                sample(null, "2000", null)         // 数值字符串金额：命中，默认编号 sample-4
        ), null);

        Map<String, Object> summary = (Map<String, Object>) report.get("summary");
        assertEquals(4, summary.get("totalSamples"));   // int（样本数原样返回）
        assertEquals(2L, summary.get("hitSamples"));
        assertEquals(1L, summary.get("hitRules"));
        assertEquals(0L, summary.get("skippedRules"));

        Map<String, Object> row = ruleRow(report, "rr_t");
        assertEquals("evaluable", row.get("status"));
        assertNull(row.get("skipReason"));
        assertEquals(2, row.get("hitSamples"));
        assertEquals(List.of("s2", "sample-4"), row.get("sampleIds"));
    }

    @Test
    @DisplayName("FEATURE 全 AND：缺值 fail-closed、部分条件不满足不命中、全部满足命中")
    void featureAllAndFailClosed() {
        RiskRuleEntity r = rule("rr_f", RiskRuleEntity.RuleType.FEATURE, 70);
        r.ruleConditions = "{\"features\":[{\"feature\":\"gold_gain_1h\",\"op\":\">\",\"value\":100},"
                + "{\"feature\":\"win_rate\",\"op\":\">=\",\"value\":0.8}]}";
        when(riskRuleRepo.findActiveByGameId(GAME)).thenReturn(List.of(r));

        Map<String, Object> report = service.dryRun(GAME, List.of(
                sample("all", null, Map.of("gold_gain_1h", 150, "win_rate", 0.9)),   // 全满足
                sample("missing", null, Map.of("gold_gain_1h", 150)),                // 缺值 fail-closed
                sample("partial", null, Map.of("gold_gain_1h", 50, "win_rate", 0.9)) // 第一条不满足
        ), null);

        Map<String, Object> row = ruleRow(report, "rr_f");
        assertEquals("evaluable", row.get("status"));
        assertEquals(1, row.get("hitSamples"));
        assertEquals(List.of("all"), row.get("sampleIds"));

        List<Map<String, Object>> results = sampleResults(report);
        assertEquals(List.of("rr_f"), results.get(0).get("matchedRuleIds"));
        assertTrue(((List<?>) results.get(1).get("effectiveHits")).isEmpty());  // 缺值 fail-closed
        assertTrue(((List<?>) results.get(2).get("effectiveHits")).isEmpty());
    }

    @Test
    @DisplayName("非法特征条件整条作废：invalid + 原因，不参与评估")
    void invalidFeatureConditions() {
        RiskRuleEntity bad = rule("rr_bad", RiskRuleEntity.RuleType.FEATURE, 70);
        bad.ruleConditions = "{\"features\":[{\"feature\":\"f\",\"op\":\"~\",\"value\":1}]}";
        RiskRuleEntity good = rule("rr_good", RiskRuleEntity.RuleType.THRESHOLD, 60);
        good.triggerThreshold = 10;
        when(riskRuleRepo.findActiveByGameId(GAME)).thenReturn(List.of(bad, good));

        Map<String, Object> report = service.dryRun(GAME,
                List.of(sample("s1", 100, null)), null);

        Map<String, Object> badRow = ruleRow(report, "rr_bad");
        assertEquals("invalid", badRow.get("status"));
        assertEquals("特征条件缺失或非法，线上也不会装载", badRow.get("skipReason"));
        assertEquals(0, badRow.get("hitSamples"));

        assertEquals(1L, ((Map<String, Object>) report.get("summary")).get("skippedRules"));
        Map<String, Object> goodRow = ruleRow(report, "rr_good");
        assertEquals(1, goodRow.get("hitSamples"));
    }

    @Test
    @DisplayName("流式/未覆盖类型明示跳过：needsStreaming / notCovered / THRESHOLD 阈值缺失 invalid")
    void streamingAndNotCoveredSkipped() {
        RiskRuleEntity freq = rule("rr_freq", RiskRuleEntity.RuleType.FREQUENCY, 60);
        RiskRuleEntity pattern = rule("rr_pat", RiskRuleEntity.RuleType.PATTERN, 85);
        RiskRuleEntity anomaly = rule("rr_anom", RiskRuleEntity.RuleType.ANOMALY, 50);
        RiskRuleEntity zeroThreshold = rule("rr_zero", RiskRuleEntity.RuleType.THRESHOLD, 40);
        zeroThreshold.triggerThreshold = 0;
        when(riskRuleRepo.findActiveByGameId(GAME))
                .thenReturn(List.of(freq, pattern, anomaly, zeroThreshold));

        Map<String, Object> report = service.dryRun(GAME,
                List.of(sample("s1", 999999, null)), null);

        assertEquals("needsStreaming", ruleRow(report, "rr_freq").get("status"));
        assertEquals("依赖流式窗口/序列聚合，dry-run 不模拟", ruleRow(report, "rr_freq").get("skipReason"));
        assertEquals("needsStreaming", ruleRow(report, "rr_pat").get("status"));
        assertEquals("notCovered", ruleRow(report, "rr_anom").get("status"));
        assertEquals("生产链路未评估该类型", ruleRow(report, "rr_anom").get("skipReason"));
        assertEquals("invalid", ruleRow(report, "rr_zero").get("status"));

        Map<String, Object> summary = (Map<String, Object>) report.get("summary");
        assertEquals(4L, summary.get("skippedRules"));
        assertEquals(0L, summary.get("hitRules"));
        assertEquals(0L, summary.get("hitSamples"));
    }

    @Test
    @DisplayName("ruleIds 过滤只评估所选规则；过滤后不再出现的规则整行省略")
    void ruleIdsFilter() {
        RiskRuleEntity t = rule("rr_t", RiskRuleEntity.RuleType.THRESHOLD, 80);
        t.triggerThreshold = 100;
        RiskRuleEntity f = rule("rr_f", RiskRuleEntity.RuleType.FREQUENCY, 60);
        when(riskRuleRepo.findActiveByGameId(GAME)).thenReturn(List.of(t, f));

        Map<String, Object> report = service.dryRun(GAME,
                List.of(sample("s1", 500, null)), List.of("rr_t"));

        assertEquals(1, ((List<?>) report.get("ruleResults")).size());
        assertEquals("rr_t", ruleRow(report, "rr_t").get("ruleId"));
        assertEquals(0L, ((Map<String, Object>) report.get("summary")).get("skippedRules"));
    }

    @Test
    @DisplayName("同类型多规则命中：matchedRuleIds 保留原始命中，effectiveHits 收敛为 riskScore 最高者")
    void effectiveConvergesByType() {
        RiskRuleEntity low = rule("rr_a", RiskRuleEntity.RuleType.THRESHOLD, 60);
        low.triggerThreshold = 100;
        RiskRuleEntity high = rule("rr_b", RiskRuleEntity.RuleType.THRESHOLD, 90);
        high.triggerThreshold = 100;
        high.actionType = RiskRuleEntity.ActionType.BLOCK;
        high.riskLevel = RiskRuleEntity.RiskLevel.CRITICAL;
        RiskRuleEntity feat = rule("rr_c", RiskRuleEntity.RuleType.FEATURE, 70);
        feat.ruleConditions = "{\"features\":[{\"feature\":\"gold_gain_1h\",\"op\":\">\",\"value\":100}]}";
        when(riskRuleRepo.findActiveByGameId(GAME)).thenReturn(new ArrayList<>(List.of(low, high, feat)));

        Map<String, Object> report = service.dryRun(GAME, List.of(
                sample("s1", 500, Map.of("gold_gain_1h", 200))
        ), null);

        List<Map<String, Object>> results = sampleResults(report);
        assertEquals(List.of("rr_a", "rr_b", "rr_c"), results.get(0).get("matchedRuleIds"));

        List<Map<String, Object>> effective =
                (List<Map<String, Object>>) results.get(0).get("effectiveHits");
        // 同类型收敛：THRESHOLD 只剩 rr_b(90)；FEATURE rr_c(70) 独立生效；riskScore 降序
        assertEquals(2, effective.size());
        assertEquals("rr_b", effective.get(0).get("ruleId"));
        assertEquals(90, effective.get(0).get("riskScore"));
        assertEquals("BLOCK", effective.get(0).get("actionType"));
        assertEquals("rr_c", effective.get(1).get("ruleId"));
        assertEquals(70, effective.get(1).get("riskScore"));
    }

    @Test
    @DisplayName("样本校验：空列表/超上限/非对象元素/非数值金额/非法 features 均拒绝")
    void sampleValidation() {
        assertThrows(IllegalArgumentException.class, () -> service.dryRun(GAME, List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> service.dryRun(GAME, null, null));

        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 0; i < RiskLabReplayService.MAX_SAMPLES + 1; i++) {
            tooMany.add(sample("s" + i, 1, null));
        }
        IllegalArgumentException over = assertThrows(IllegalArgumentException.class,
                () -> service.dryRun(GAME, tooMany, null));
        assertTrue(over.getMessage().contains("上限"));

        assertThrows(IllegalArgumentException.class,
                () -> service.dryRun(GAME, List.of(sample("s1", "abc", null)), null));
        assertThrows(IllegalArgumentException.class,
                () -> service.dryRun(GAME, List.of(sample("s1", Map.of(), null)), null));

        Map<String, Object> badFeatures = new LinkedHashMap<>();
        badFeatures.put("features", "not-a-map");
        assertThrows(IllegalArgumentException.class,
                () -> service.dryRun(GAME, List.of(badFeatures), null));
        assertThrows(IllegalArgumentException.class,
                () -> service.dryRun(GAME, List.of(sample("s1", 1, Map.of("f", "nope"))), null));
    }
}
