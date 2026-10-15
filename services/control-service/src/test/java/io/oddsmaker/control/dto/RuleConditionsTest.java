package io.oddsmaker.control.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则条件类型化视图契约（重构契约 §2.4「收敛 ruleConditions 为结构化 JSON schema」）：
 * 严格档 = 写路径校验（不可解析/形态非法显式报错）；容忍档 = 读路径存量兼容
 * （可解析即兼容、不可解析 parsed=false 不抛——replay 跳过语义与 RuleFetcher 一致）。
 * 合法形态两侧对齐：{"sequence":[...]}（PATTERN）与 {"features":[{"scope","feature","op","value"}]}（FEATURE）。
 */
@DisplayName("规则条件类型化视图")
class RuleConditionsTest {

    @Test
    @DisplayName("严格档：两类已知形态解析为类型化列表，scope 缺省 SUBJECT，其余键 raw 原样保留")
    void strictParseTyping() {
        RuleConditions rc = RuleConditions.parse(
            "{\"sequence\":[\"login\",\" purchase \"],\"features\":[{\"feature\":\"gold_gain_1h\",\"op\":\">\",\"value\":100},"
                + "{\"scope\":\"IP\",\"feature\":\"account_count_per_ip\",\"op\":\">=\",\"value\":3}],"
                + "\"event_type\":\"purchase\"}");

        assertEquals(List.of("login", "purchase"), rc.sequence);
        assertTrue(rc.parsed);
        assertEquals(2, rc.features.size());
        assertEquals(new RuleConditions.FeatureCondition("SUBJECT", "gold_gain_1h", ">", 100.0), rc.features.get(0));
        assertEquals(new RuleConditions.FeatureCondition("IP", "account_count_per_ip", ">=", 3.0), rc.features.get(1));
        assertTrue(rc.hasFeatures());
        // 未识别键不丢弃（存量自由文本 JSON 对象可解析即兼容）
        assertNotNull(rc.raw);
        assertEquals("purchase", rc.raw.path("event_type").asText());
    }

    @Test
    @DisplayName("严格档：坏 JSON / 非对象 / 序列或特征数组任一元素非法 → 显式 IllegalArgumentException")
    void strictParseRejects() {
        List<String> bad = List.of(
            "not-json",                                                       // 非 JSON
            "[1,2]",                                                          // JSON 但非对象
            "\"login\"",                                                      // JSON 标量
            "{\"sequence\":\"login\"}",                                       // sequence 非数组
            "{\"sequence\":[\"login\",\"\"]}",                                // 空事件名
            "{\"features\":\"x\"}",                                           // features 非数组
            "{\"features\":[]}",                                              // 空条件数组
            "{\"features\":[{\"feature\":\"f\",\"op\":\"~\",\"value\":1}]}",   // 非法算子
            "{\"features\":[{\"scope\":\"GAME\",\"feature\":\"f\",\"op\":\">\",\"value\":1}]}", // 非法 scope
            "{\"features\":[{\"feature\":\"  \",\"op\":\">\",\"value\":1}]}",  // 空特征名
            "{\"features\":[{\"feature\":\"f\",\"op\":\">\",\"value\":\"100\"}]}" // value 非数字
        );
        for (String conditions : bad) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> RuleConditions.parse(conditions), "应显式报错: " + conditions);
            assertTrue(ex.getMessage().startsWith("ruleConditions"), ex.getMessage());
        }
    }

    @Test
    @DisplayName("容忍档：坏数据不抛、parsed=false 空列表；存量自由文本对象 parsed=true 且 raw 保留")
    void tolerantParseNeverThrows() {
        for (String bad : List.of("not-json", "[1,2]", "null",
                "{\"features\":[{\"feature\":\"f\",\"op\":\"~\",\"value\":1}]}")) {
            RuleConditions rc = RuleConditions.parseTolerant(bad, new ObjectMapper());
            assertFalse(rc.parsed, "坏数据应为 parsed=false: " + bad);
            assertTrue(rc.sequence.isEmpty() && rc.features.isEmpty(), "坏数据应为空列表: " + bad);
        }

        RuleConditions legacy = RuleConditions.parseTolerant(
            "{\"event_type\":\"purchase\",\"amount\":\">1000\",\"frequency\":\">10/hour\"}", new ObjectMapper());
        assertTrue(legacy.parsed);
        assertTrue(legacy.sequence.isEmpty() && legacy.features.isEmpty());
        assertEquals("purchase", legacy.raw.path("event_type").asText());
    }

    @Test
    @DisplayName("空档位：null/空白无条件在两档都合法（PATTERN/FEATURE 的必填约束在服务层）")
    void nullOrBlankIsEmpty() {
        for (String blank : new String[] {null, "", "   "}) {
            RuleConditions strict = RuleConditions.parse(blank);
            RuleConditions tolerant = RuleConditions.parseTolerant(blank, new ObjectMapper());
            assertTrue(strict.parsed && tolerant.parsed);
            assertTrue(strict.sequence.isEmpty() && strict.features.isEmpty());
            assertTrue(tolerant.sequence.isEmpty() && tolerant.features.isEmpty());
        }
    }

    @Test
    @DisplayName("算子集：>, >=, <, <=, == 与两侧解析口径一致（RuleFetcher.OPS 同款）")
    void featureOpSet() {
        assertEquals(java.util.Set.of(">", ">=", "<", "<=", "=="), RuleConditions.FEATURE_OPS);
    }
}
