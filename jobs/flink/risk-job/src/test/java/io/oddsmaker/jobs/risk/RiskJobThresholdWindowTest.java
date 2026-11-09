package io.oddsmaker.jobs.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static io.oddsmaker.jobs.risk.RiskJobTestSupport.*;

/**
 * RiskJob 测试（LAY-011 拆分）：THRESHOLD 判定四分支与命中字段映射，FREQUENCY/VELOCITY/RATIO/DUPLICATE_RECEIPT/AD_REWARD 五窗口算子直测，open 经 startOnce 单例 fetcher。
 */
class RiskJobThresholdWindowTest {

    @Test
    @DisplayName("overThreshold：null/不超/等于/超阈值 四分支")
    void overThresholdBranches() {
        try (RuleOverride o = RuleOverride.set(rules("THRESHOLD", 100, "rt"))) {
            RiskJob.RiskInput i = new RiskJob.RiskInput();
            assertFalse(RiskJob.overThreshold(i));   // amount null
            i.amount = new BigDecimal("50");
            assertFalse(RiskJob.overThreshold(i));
            i.amount = new BigDecimal("100");        // 等于不触发（严格大于）
            assertFalse(RiskJob.overThreshold(i));
            i.amount = new BigDecimal("150");
            assertTrue(RiskJob.overThreshold(i));
        }
    }

    @Test
    @DisplayName("thresholdHit：覆盖规则字段 + ruleId 兜底 + DEVICE 主体")
    void thresholdHitMapsFields() {
        try (RuleOverride o = RuleOverride.set(rules("THRESHOLD", 100, "rt-1"))) {
            RiskJob.RiskInput i = input("e1", null);   // 无 userId → DEVICE
            i.amount = new BigDecimal("150");
            RiskJob.RiskHit h = RiskJob.thresholdHit(i);
            assertEquals("rt-1", h.ruleId);
            assertEquals("THRESHOLD", h.riskType);
            assertEquals("MEDIUM", h.severity);
            assertEquals("DEVICE", h.subjectType);
            assertEquals("d1", h.subjectId);
            assertEquals(55f, h.score, 0f);
            assertEquals("ALERT", h.action);
            assertEquals("resource amount 150 exceeds threshold 100", h.reason);
            assertEquals("150", h.evidence.get("resource_amount"));
            assertEquals("n_e1", h.evidence.get("event_name"));
        }
        try (RuleOverride o = RuleOverride.set(Map.of())) {   // DEFAULTS：ruleId null → 兜底名
            RiskJob.RiskInput i = input("e2", "u1");
            i.amount = new BigDecimal("200000");
            RiskJob.RiskHit h = RiskJob.thresholdHit(i);
            assertEquals("risk-threshold-amount", h.ruleId);
            assertEquals("HIGH", h.severity);
            assertEquals(80f, h.score, 0f);
            assertEquals("PLAYER", h.subjectType);
        }
    }

    @Test
    @DisplayName("FrequencyFunction：事件数超阈值命中；未超与空窗口不输出")
    void frequencyFunctionBursts() {
        try (RuleOverride o = RuleOverride.set(rules("FREQUENCY", 2, "rf-1"))) {
            RiskJob.FrequencyFunction fn = new RiskJob.FrequencyFunction(10, deadRules());
            List<RiskJob.RiskHit> out = new ArrayList<>();
            fn.process("g|prod|PLAYER:u1", null, List.of(input("e1", "u1"), input("e2", "u1"), input("e3", "u1")), sinkTo(out));
            assertEquals(1, out.size());
            RiskJob.RiskHit h = out.get(0);
            assertEquals("rf-1", h.ruleId);
            assertEquals("FREQUENCY", h.riskType);
            assertEquals("PLAYER", h.subjectType);
            assertEquals("u1", h.subjectId);
            assertEquals("event burst 3 in 10min (limit 2)", h.reason);
            assertEquals("3", h.evidence.get("window_events"));
            assertEquals("10", h.evidence.get("window_minutes"));
            assertEquals("PLAYER:u1", h.evidence.get("subject"));
            assertEquals(36, h.riskEventId.length());

            fn.process("k", null, List.of(input("e9", "u1")), sinkTo(out));   // 1 <= 2
            fn.process("k", null, List.of(), sinkTo(out));                    // 空窗口
            assertEquals(1, out.size());
        }
    }

    @Test
    @DisplayName("VelocityFunction：金额合计超阈值命中；未超与空窗口不输出")
    void velocityFunctionSums() {
        try (RuleOverride o = RuleOverride.set(rules("VELOCITY", 100, null))) {
            RiskJob.VelocityFunction fn = new RiskJob.VelocityFunction(10, deadRules());
            List<RiskJob.RiskHit> out = new ArrayList<>();

            List<RiskJob.RiskInput> events = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                RiskJob.RiskInput e = input("e" + i, "u1");
                e.amount = new BigDecimal("50");
                events.add(e);
            }
            fn.process("k", null, events, sinkTo(out));   // sum 150 > 100
            assertEquals(1, out.size());
            RiskJob.RiskHit h = out.get(0);
            assertEquals("risk-velocity-amount", h.ruleId);   // ruleId null → 兜底名
            assertEquals("VELOCITY", h.riskType);
            assertEquals("resource velocity 150 in 10min exceeds 100", h.reason);
            assertEquals("150", h.evidence.get("window_sum"));
            assertEquals("3", h.evidence.get("window_events"));

            fn.process("k", null, events.subList(0, 1), sinkTo(out));   // 50 <= 100
            fn.process("k", null, List.of(), sinkTo(out));
            assertEquals(1, out.size());
        }
    }

    @Test
    @DisplayName("RatioFunction：source/sink 比超阈值命中；无 sink 与空窗口不输出")
    void ratioFunctionComparesFlows() {
        try (RuleOverride o = RuleOverride.set(rules("RATIO", 2, "rr"))) {
            RiskJob.RatioFunction fn = new RiskJob.RatioFunction(10, deadRules());
            List<RiskJob.RiskHit> out = new ArrayList<>();

            fn.process("k", null, List.of(flowInput("source", "300"), flowInput("sink", "100")), sinkTo(out));
            assertEquals(1, out.size());
            RiskJob.RiskHit h = out.get(0);
            assertEquals("rr", h.ruleId);
            assertEquals("RATIO", h.riskType);
            assertEquals("source/sink ratio 3.00 in 10min exceeds 2", h.reason);
            assertEquals("300", h.evidence.get("source_sum"));
            assertEquals("100", h.evidence.get("sink_sum"));
            assertEquals("3.00", h.evidence.get("ratio"));

            fn.process("k", null, List.of(flowInput("source", "999")), sinkTo(out));          // sink 为 0
            fn.process("k", null, List.of(flowInput("source", "50"), flowInput("sink", "100")), sinkTo(out));   // 0.50 <= 2
            fn.process("k", null, List.of(), sinkTo(out));
            assertEquals(1, out.size());
        }
    }

    @Test
    @DisplayName("DuplicateReceiptFunction：次数达阈值命中（>=）；不足与空窗口不输出")
    void duplicateReceiptFunctionDetects() {
        try (RuleOverride o = RuleOverride.set(rules("DUPLICATE_RECEIPT", 2, "rdup"))) {
            RiskJob.DuplicateReceiptFunction fn = new RiskJob.DuplicateReceiptFunction(10, deadRules());
            List<RiskJob.RiskHit> out = new ArrayList<>();

            RiskJob.RiskInput first = input("e1", "u1");
            first.receiptKey = "rc_1";
            RiskJob.RiskInput second = input("e2", "u1");
            second.receiptKey = "rc_1";
            fn.process("k", null, List.of(first, second), sinkTo(out));   // 2 >= 2
            assertEquals(1, out.size());
            RiskJob.RiskHit h = out.get(0);
            assertEquals("rdup", h.ruleId);
            assertEquals("DUPLICATE_RECEIPT", h.riskType);
            assertEquals("receipt rc_1 submitted 2 times in 10min", h.reason);
            assertEquals("rc_1", h.evidence.get("receipt_key"));
            assertEquals("2", h.evidence.get("occurrences"));

            fn.process("k", null, List.of(first), sinkTo(out));   // 1 < 2
            fn.process("k", null, List.of(), sinkTo(out));
            assertEquals(1, out.size());
        }
    }

    @Test
    @DisplayName("AdRewardFunction：reward 事件数超阈值命中且累计收入；未超与空窗口不输出")
    void adRewardFunctionBursts() {
        try (RuleOverride o = RuleOverride.set(rules("AD_REWARD", 2, null))) {
            RiskJob.AdRewardFunction fn = new RiskJob.AdRewardFunction(10, deadRules());
            List<RiskJob.RiskHit> out = new ArrayList<>();

            List<RiskJob.RiskInput> events = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                RiskJob.RiskInput e = input("e" + i, "u1");
                e.adReward = true;
                if (i > 0) e.revenueAmount = new BigDecimal("5");   // 首条无收入
                events.add(e);
            }
            fn.process("k", null, events, sinkTo(out));   // 3 > 2
            assertEquals(1, out.size());
            RiskJob.RiskHit h = out.get(0);
            assertEquals("risk-ad-reward-abuse", h.ruleId);
            assertEquals("AD_REWARD", h.riskType);
            assertEquals("ad reward burst 3 in 10min (limit 2)", h.reason);
            assertEquals("3", h.evidence.get("ad_reward_count"));
            assertEquals("10", h.evidence.get("revenue_sum"));

            fn.process("k", null, events.subList(0, 2), sinkTo(out));   // 2 不严格大于 2
            fn.process("k", null, List.of(), sinkTo(out));
            assertEquals(1, out.size());
        }
    }

    @Test
    @DisplayName("五个窗口算子 open：均经 startOnce 启动（单例只建一个 fetcher）")
    void windowFunctionsOpenStartRuleFetcherOnce() throws Exception {
        resetRuleFetcher();
        try {
            org.apache.flink.configuration.Configuration cfg = new org.apache.flink.configuration.Configuration();
            new RiskJob.FrequencyFunction(10, deadRules()).open(cfg);
            new RiskJob.VelocityFunction(10, deadRules()).open(cfg);
            new RiskJob.RatioFunction(10, deadRules()).open(cfg);
            new RiskJob.DuplicateReceiptFunction(10, deadRules()).open(cfg);
            new RiskJob.AdRewardFunction(10, deadRules()).open(cfg);

            Object inst = ruleFetcherInstance();
            assertNotNull(inst, "startOnce 应已创建单例 fetcher");
        } finally {
            stopRuleFetcher();
        }
    }

}
