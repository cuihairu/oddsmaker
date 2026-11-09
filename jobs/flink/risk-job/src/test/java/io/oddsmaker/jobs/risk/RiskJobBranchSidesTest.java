package io.oddsmaker.jobs.risk;

import io.oddsmaker.jobs.enrich.RawEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static io.oddsmaker.jobs.risk.RiskJobTestSupport.*;

/**
 * RiskJob 测试（LAY-011 拆分）：分支对侧补充收口——parseArgs null 元素、adReward 空/无名事件、零金额过滤、窗口算子负阈值与空窗口、ruleId 兜底两侧、RATIO 忽略异流、toJson 多 evidence。
 */
class RiskJobBranchSidesTest {

    @Test
    @DisplayName("parseArgs：数组内 null 元素安全跳过")
    void parseArgsSkipsNullElements() {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                RiskJob.parseArgs(new String[]{null, "--x.y=1"}));
        System.clearProperty("x.y");
    }

    @Test
    @DisplayName("toRiskInput：event_name 为 null 时 ad_reward 判定不抛（两形态）")
    void adRewardWithNullEventName() {
        RawEvent noNameNoFormat = fullEvent();
        noNameNoFormat.event_name = null;
        noNameNoFormat.ad_format = null;
        assertFalse(RiskJob.toRiskInput(noNameNoFormat).adReward);

        RawEvent noNameRewarded = fullEvent();
        noNameRewarded.event_name = null;
        noNameRewarded.ad_format = "rewarded";
        assertTrue(RiskJob.toRiskInput(noNameRewarded).adReward);   // 仅靠 ad_format 命中
    }

    @Test
    @DisplayName("isFlowAmount：amount 为 0 视同无金额（正数侧已盖）")
    void isFlowAmountRejectsZeroAmount() {
        RiskJob.RiskInput zero = new RiskJob.RiskInput();
        zero.amount = BigDecimal.ZERO;
        zero.flowType = "source";
        assertFalse(RiskJob.isFlowAmount(zero));
    }

    @Test
    @DisplayName("五个窗口算子：负阈值 + 空窗口（count 超限但 last==null 的防御侧）均不输出")
    void windowFunctionsNegativeThresholdEmptyWindow() throws Exception {
        Map<String, RuleConfig.RuleSpec> neg = Map.of(
                "FREQUENCY", new RuleConfig.RuleSpec(null, "FREQUENCY", -1, "ALERT", 55, "MEDIUM"),
                "VELOCITY", new RuleConfig.RuleSpec(null, "VELOCITY", -1, "ALERT", 55, "MEDIUM"),
                "RATIO", new RuleConfig.RuleSpec(null, "RATIO", -1, "ALERT", 55, "MEDIUM"),
                "DUPLICATE_RECEIPT", new RuleConfig.RuleSpec(null, "DUPLICATE_RECEIPT", -1, "ALERT", 55, "MEDIUM"),
                "AD_REWARD", new RuleConfig.RuleSpec(null, "AD_REWARD", -1, "ALERT", 55, "MEDIUM"));
        try (RuleOverride o = RuleOverride.set(neg)) {
            List<RiskJob.RiskHit> out = new ArrayList<>();
            new RiskJob.FrequencyFunction(10, deadRules()).process("k", null, List.of(), sinkTo(out));
            new RiskJob.VelocityFunction(10, deadRules()).process("k", null, List.of(), sinkTo(out));
            new RiskJob.RatioFunction(10, deadRules()).process("k", null, List.of(), sinkTo(out));
            new RiskJob.DuplicateReceiptFunction(10, deadRules()).process("k", null, List.of(), sinkTo(out));
            new RiskJob.AdRewardFunction(10, deadRules()).process("k", null, List.of(), sinkTo(out));
            assertTrue(out.isEmpty());
        }
    }

    @Test
    @DisplayName("窗口算子 ruleId 三元对侧：null 走兜底名、非 null 自定义胜出")
    void windowFunctionsRuleIdFallbackSides() {
        try (RuleOverride o = RuleOverride.set(rules("FREQUENCY", 2, null))) {   // null → 兜底名
            RiskJob.FrequencyFunction fn = new RiskJob.FrequencyFunction(10, deadRules());
            List<RiskJob.RiskHit> out = new ArrayList<>();
            fn.process("k", null, List.of(input("e1", "u1"), input("e2", "u1"), input("e3", "u1")), sinkTo(out));
            assertEquals(1, out.size());
            assertEquals("risk-frequency-burst", out.get(0).ruleId);
        }
        try (RuleOverride o = RuleOverride.set(rules("RATIO", 2, null))) {
            RiskJob.RatioFunction fn = new RiskJob.RatioFunction(10, deadRules());
            List<RiskJob.RiskHit> out = new ArrayList<>();
            fn.process("k", null, List.of(flowInput("source", "300"), flowInput("sink", "100")), sinkTo(out));
            assertEquals(1, out.size());
            assertEquals("risk-ratio-source-sink", out.get(0).ruleId);
        }
        try (RuleOverride o = RuleOverride.set(rules("DUPLICATE_RECEIPT", 2, null))) {
            RiskJob.DuplicateReceiptFunction fn = new RiskJob.DuplicateReceiptFunction(10, deadRules());
            List<RiskJob.RiskHit> out = new ArrayList<>();
            RiskJob.RiskInput a = input("e1", "u1");
            a.receiptKey = "rc";
            RiskJob.RiskInput b = input("e2", "u1");
            b.receiptKey = "rc";
            fn.process("k", null, List.of(a, b), sinkTo(out));
            assertEquals(1, out.size());
            assertEquals("risk-duplicate-receipt", out.get(0).ruleId);
        }
        try (RuleOverride o = RuleOverride.set(rules("VELOCITY", 100, "rv-9"))) {   // 非 null → 自定义（null 侧已盖）
            RiskJob.VelocityFunction fn = new RiskJob.VelocityFunction(10, deadRules());
            List<RiskJob.RiskHit> out = new ArrayList<>();
            List<RiskJob.RiskInput> events = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                RiskJob.RiskInput e = input("e" + i, "u1");
                e.amount = new BigDecimal("50");
                events.add(e);
            }
            fn.process("k", null, events, sinkTo(out));
            assertEquals(1, out.size());
            assertEquals("rv-9", out.get(0).ruleId);
        }
        try (RuleOverride o = RuleOverride.set(rules("AD_REWARD", 2, "rw-1"))) {
            RiskJob.AdRewardFunction fn = new RiskJob.AdRewardFunction(10, deadRules());
            List<RiskJob.RiskHit> out = new ArrayList<>();
            List<RiskJob.RiskInput> events = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                RiskJob.RiskInput e = input("e" + i, "u1");
                e.adReward = true;
                events.add(e);
            }
            fn.process("k", null, events, sinkTo(out));
            assertEquals(1, out.size());
            assertEquals("rw-1", out.get(0).ruleId);
        }
    }

    @Test
    @DisplayName("RatioFunction：非 source/sink 的 flow 事件不入两侧合计")
    void ratioWindowIgnoresOtherFlows() {
        try (RuleOverride o = RuleOverride.set(rules("RATIO", 2, "rr"))) {
            RiskJob.RatioFunction fn = new RiskJob.RatioFunction(10, deadRules());
            List<RiskJob.RiskHit> out = new ArrayList<>();
            fn.process("k", null,
                    List.of(flowInput("source", "300"), flowInput("other", "100"), flowInput("sink", "100")),
                    sinkTo(out));
            assertEquals(1, out.size());
            assertEquals("300", out.get(0).evidence.get("source_sum"));   // other 不计入
            assertEquals("100", out.get(0).evidence.get("sink_sum"));
        }
    }

    @Test
    @DisplayName("toJson：evidence 两条目间以逗号分隔（单条目侧已盖）")
    void toJsonMultipleEvidenceEntries() {
        Map<String, String> ev = new LinkedHashMap<>();
        ev.put("a", "1");
        ev.put("b", "2");
        RiskJob.RiskHit h = new RiskJob.RiskHit(
                "g", "prod", new Timestamp(1_000L), "rid", "sid",
                "rule-1", "THRESHOLD", "HIGH", "PLAYER", "u1", 85f, "ALERT", "r", ev, "");
        assertTrue(RiskJob.toJson(h).contains("\"evidence\":{\"a\":\"1\",\"b\":\"2\"}"));
    }

}
