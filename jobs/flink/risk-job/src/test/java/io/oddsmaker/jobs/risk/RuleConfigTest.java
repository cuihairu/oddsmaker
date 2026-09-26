package io.oddsmaker.jobs.risk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("风控规则快照")
class RuleConfigTest {

    @Test
    @DisplayName("DEFAULTS：七类规则全覆盖 + 关键默认值 + 未知类型 null")
    void defaultsCoverAllSevenTypes() {
        for (String t : List.of("THRESHOLD", "FREQUENCY", "VELOCITY", "RATIO", "DUPLICATE_RECEIPT", "AD_REWARD", "PATTERN")) {
            assertNotNull(RuleConfig.byType(t), t);
        }
        assertNull(RuleConfig.byType("NOPE"));

        RuleConfig.RuleSpec th = RuleConfig.byType("THRESHOLD");
        assertNull(th.ruleId);
        assertEquals(100_000, th.triggerThreshold);
        assertEquals("ALERT", th.actionType);
        assertEquals(80, th.riskScore);
        assertEquals("HIGH", th.riskLevel);

        assertEquals(1_000, RuleConfig.byType("FREQUENCY").triggerThreshold);
        assertEquals(60, RuleConfig.byType("FREQUENCY").riskScore);
        assertEquals("MEDIUM", RuleConfig.byType("FREQUENCY").riskLevel);
        assertEquals(10, RuleConfig.byType("RATIO").triggerThreshold);
        assertEquals("REVIEW", RuleConfig.byType("DUPLICATE_RECEIPT").actionType);
        assertEquals(90, RuleConfig.byType("DUPLICATE_RECEIPT").riskScore);
        assertEquals(70, RuleConfig.byType("AD_REWARD").riskScore);

        RuleConfig.RuleSpec pat = RuleConfig.byType("PATTERN");
        assertNull(pat.ruleId);
        assertEquals(300, pat.triggerThreshold);   // 借用为窗口秒数（默认 5min）
        assertEquals(300, pat.windowSeconds);
        assertEquals("REVIEW", pat.actionType);
        assertEquals(85, pat.riskScore);
        assertEquals("HIGH", pat.riskLevel);
        assertEquals(List.of("login", "purchase", "refund"), pat.sequence);
    }

    @Test
    @DisplayName("RuleSpec：6/7/8 参构造——sequence 防御拷贝、null 归一空表")
    void ruleSpecConstructorVariants() {
        java.util.ArrayList<String> src = new java.util.ArrayList<>(List.of("login", "purchase", "refund"));
        RuleConfig.RuleSpec full = new RuleConfig.RuleSpec("r1", "PATTERN", 300, "REVIEW", 85, "HIGH", src, 300);
        assertEquals(List.of("login", "purchase", "refund"), full.sequence);
        assertEquals(300, full.windowSeconds);
        src.set(0, "hacked");   // 外部改不透
        assertEquals("login", full.sequence.get(0));

        RuleConfig.RuleSpec seqOnly = new RuleConfig.RuleSpec("r2", "PATTERN", 0, "REVIEW", 85, "HIGH", List.of("a", "b"));
        assertEquals(List.of("a", "b"), seqOnly.sequence);
        assertEquals(0, seqOnly.windowSeconds);   // 7 参侧窗口缺省 0

        RuleConfig.RuleSpec plain = new RuleConfig.RuleSpec("r3", "THRESHOLD", 100, "ALERT", 80, "HIGH");
        assertTrue(plain.sequence.isEmpty());
        assertEquals(0, plain.windowSeconds);

        RuleConfig.RuleSpec nullSeq = new RuleConfig.RuleSpec(null, "PATTERN", 0, "REVIEW", 85, "HIGH", null);
        assertTrue(nullSeq.sequence.isEmpty());   // null 序列归一空表
    }

    @Test
    @DisplayName("构造：null 映射空快照；入参防御性拷贝；快照不可变")
    void constructorDefendsInput() {
        assertTrue(new RuleConfig(null).byType.isEmpty());

        Map<String, RuleConfig.RuleSpec> src = new LinkedHashMap<>();
        src.put("THRESHOLD", new RuleConfig.RuleSpec("x", "THRESHOLD", 1, "ALERT", 1, "LOW"));
        RuleConfig rc = new RuleConfig(src);
        src.put("THRESHOLD", new RuleConfig.RuleSpec("y", "THRESHOLD", 2, "ALERT", 2, "LOW"));   // 外部改不透
        assertEquals("x", rc.byType.get("THRESHOLD").ruleId);
        assertThrows(UnsupportedOperationException.class, () -> rc.byType.put("Z", null));
    }

    @Test
    @DisplayName("update：忽略 null；current 反映最新快照")
    void updateIgnoresNullAndCurrentReflectsLatest() {
        try {
            RuleConfig a = new RuleConfig(Map.of("THRESHOLD",
                    new RuleConfig.RuleSpec("a", "THRESHOLD", 1, "ALERT", 1, "LOW")));
            RuleConfig.update(a);
            assertEquals(a, RuleConfig.current());

            RuleConfig.update(null);   // 忽略
            assertEquals(a, RuleConfig.current());

            RuleConfig b = new RuleConfig(Map.of("THRESHOLD",
                    new RuleConfig.RuleSpec("b", "THRESHOLD", 2, "ALERT", 2, "LOW")));
            RuleConfig.update(b);
            assertEquals(b, RuleConfig.current());
        } finally {
            RuleConfig.update(new RuleConfig(Map.of()));
        }
    }

    @Test
    @DisplayName("byType：优先当前快照，未含类型回落 DEFAULTS")
    void byTypePrefersSnapshotThenFallsBack() {
        try {
            RuleConfig.update(new RuleConfig(Map.of("THRESHOLD",
                    new RuleConfig.RuleSpec("live", "THRESHOLD", 42, "BLOCK", 99, "CRITICAL"))));
            assertEquals("live", RuleConfig.byType("THRESHOLD").ruleId);
            assertEquals(42, RuleConfig.byType("THRESHOLD").triggerThreshold);
            assertEquals(1_000_000, RuleConfig.byType("VELOCITY").triggerThreshold);   // 快照外 → 默认
        } finally {
            RuleConfig.update(new RuleConfig(Map.of()));
        }
        assertNull(RuleConfig.byType("THRESHOLD").ruleId);   // 空快照 → DEFAULTS（ruleId null）
    }

    @Test
    @DisplayName("byType：current 快照为 null（未初始化防御侧）时回落 DEFAULTS")
    void byTypeFallsBackWhenCurrentNull() throws Exception {
        java.lang.reflect.Field f = RuleConfig.class.getDeclaredField("current");
        f.setAccessible(true);
        Object saved = f.get(null);
        f.set(null, null);
        try {
            RuleConfig.RuleSpec spec = RuleConfig.byType("THRESHOLD");
            assertNotNull(spec);
            assertNull(spec.ruleId);
            assertEquals(100_000, spec.triggerThreshold);
            assertEquals("ALERT", spec.actionType);
        } finally {
            f.set(null, saved);
        }
    }
}
