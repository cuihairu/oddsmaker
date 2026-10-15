package io.oddsmaker.control.jpa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 实体读时类型化（重构契约 §2.4「收敛 ruleConditions」）：rule_conditions 列维持 JSON 文本
 * （不改写存量行、不落新列），conditions() 为容忍档类型化视图——存量自由文本可解析即兼容、
 * 不可解析不抛（跳过语义兜底在 job 侧）；严格档（写路径显式报错）在 RiskRuleService。
 */
@DisplayName("RiskRuleEntity.conditions() 读时类型化视图")
class RiskRuleEntityConditionsTest {

    @Test
    @DisplayName("已知形态：FEATURE 条件解析为类型化列表")
    void featureShapeTyped() {
        RiskRuleEntity r = new RiskRuleEntity();
        r.ruleConditions = "{\"features\":[{\"scope\":\"IP\",\"feature\":\"account_count_per_ip\",\"op\":\">=\",\"value\":3}]}";

        var rc = r.conditions();
        assertTrue(rc.parsed);
        assertTrue(rc.hasFeatures());
        assertEquals("IP", rc.features.get(0).scope());
        assertEquals("account_count_per_ip", rc.features.get(0).feature());
        assertEquals(">=", rc.features.get(0).op());
        assertEquals(3.0, rc.features.get(0).value());
    }

    @Test
    @DisplayName("存量兼容：自由文本 JSON 对象可解析即兼容（空列表 + raw 保留）；坏文本不抛 parsed=false")
    void legacyRowsCompatible() {
        RiskRuleEntity legacy = new RiskRuleEntity();
        legacy.ruleConditions = "{\"event_type\":\"purchase\",\"amount\":\">1000\"}";
        assertTrue(legacy.conditions().parsed);
        assertTrue(legacy.conditions().sequence.isEmpty() && legacy.conditions().features.isEmpty());
        assertEquals("purchase", legacy.conditions().raw.path("event_type").asText());

        RiskRuleEntity junk = new RiskRuleEntity();
        junk.ruleConditions = "legacy-free-text";
        assertFalse(junk.conditions().parsed);
        assertTrue(junk.conditions().features.isEmpty());

        RiskRuleEntity empty = new RiskRuleEntity();
        assertTrue(empty.conditions().parsed);
        assertTrue(empty.conditions().features.isEmpty());
    }
}
