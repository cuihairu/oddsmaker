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
    @DisplayName("DEFAULTS：六类规则全覆盖 + 关键默认值 + 未知类型 null")
    void defaultsCoverAllSixTypes() {
        for (String t : List.of("THRESHOLD", "FREQUENCY", "VELOCITY", "RATIO", "DUPLICATE_RECEIPT", "AD_REWARD")) {
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
