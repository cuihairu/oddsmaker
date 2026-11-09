package io.oddsmaker.jobs.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static io.oddsmaker.jobs.risk.RiskJobTestSupport.*;

/**
 * RiskJob 测试（LAY-011 拆分）：规则快照默认值（DUPLICATE_RECEIPT=2/AD_REWARD=60）、按类型覆盖、main 配 mock 环境跑通。
 */
class RiskJobRuleSnapshotTest {

    @Test
    void duplicateReceiptRuleDefaultIsTwoOccurrences() {
        RuleConfig.RuleSpec spec = RuleConfig.byType("DUPLICATE_RECEIPT");
        assertNotNull(spec);
        assertEquals(2, spec.triggerThreshold);
        assertEquals("REVIEW", spec.actionType);
        assertEquals("CRITICAL", spec.riskLevel);
    }

    @Test
    void adRewardRuleDefaultThreshold() {
        RuleConfig.RuleSpec spec = RuleConfig.byType("AD_REWARD");
        assertNotNull(spec);
        assertEquals(60, spec.triggerThreshold);
        assertEquals("HIGH", spec.riskLevel);
    }

    @Test
    void ruleConfigSnapshotOverriddenByType() {
        java.util.Map<String, RuleConfig.RuleSpec> overrides = new java.util.HashMap<>();
        overrides.put("AD_REWARD", new RuleConfig.RuleSpec(
                "rr_custom", "AD_REWARD", 10, "BLOCK", 85, "CRITICAL"));
        RuleConfig.update(new RuleConfig(overrides));

        try {
            RuleConfig.RuleSpec spec = RuleConfig.byType("AD_REWARD");
            assertEquals("rr_custom", spec.ruleId);
            assertEquals(10, spec.triggerThreshold);
            assertEquals("BLOCK", spec.actionType);
            // 未覆盖类型回落默认
            assertEquals(2, RuleConfig.byType("DUPLICATE_RECEIPT").triggerThreshold);
        } finally {
            RuleConfig.update(new RuleConfig(java.util.Map.of()));
        }
    }

    @Test
    @DisplayName("main：替身执行环境下完成入口（不触达真实集群）")
    void mainCompletesWithMockEnv() {
        try (org.mockito.MockedStatic<StreamExecutionEnvironment> mocked =
                 org.mockito.Mockito.mockStatic(StreamExecutionEnvironment.class)) {
            StreamExecutionEnvironment env = org.mockito.Mockito.mock(
                StreamExecutionEnvironment.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
            mocked.when(StreamExecutionEnvironment::getExecutionEnvironment).thenReturn(env);
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> RiskJob.main(new String[0]));
        }
    }

}
