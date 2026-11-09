package io.oddsmaker.jobs.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static io.oddsmaker.jobs.risk.RiskJobTestSupport.*;

/**
 * RiskJob 测试（LAY-011 拆分）：PATTERN 序列状态机——三步按序命中、乱序重启、窗口惰性过期、advance 防御侧、inPattern 过滤、processElement 完成即发。
 */
class RiskJobPatternStateMachineTest {

    @Test
    @DisplayName("advance：三步序列按序完成命中并重置；evidence 带序列/窗口/首步时间；重置后可从头再匹配")
    void patternAdvancesAndHitsInOrder() {
        try (RuleOverride o = RuleOverride.set(Map.of("PATTERN", patternSpec("rp-1", SEQ3)))) {
            RiskJob.SeqState st = new RiskJob.SeqState();
            long t0 = 1_000_000L;
            RiskJob.RiskInput a = input("e1", "u1");
            a.eventName = "login";
            a.ts = new Timestamp(t0);
            RiskJob.RiskInput b = input("e2", "u1");
            b.eventName = "purchase";
            b.ts = new Timestamp(t0 + 60_000);
            RiskJob.RiskInput c = input("e3", "u1");
            c.eventName = "refund";
            c.ts = new Timestamp(t0 + 120_000);

            assertNull(RiskJob.advance(RuleConfig.byType("PATTERN"), a, st));
            assertEquals(1, st.nextIdx);
            assertEquals(t0, st.startTs);
            assertNull(RiskJob.advance(RuleConfig.byType("PATTERN"), b, st));
            assertEquals(2, st.nextIdx);

            RiskJob.RiskHit h = RiskJob.advance(RuleConfig.byType("PATTERN"), c, st);
            assertNotNull(h);
            assertNull(st.nextIdx);   // 命中后重置
            assertNull(st.startTs);
            assertEquals("rp-1", h.ruleId);
            assertEquals("PATTERN", h.riskType);
            assertEquals("REVIEW", h.action);
            assertEquals(85f, h.score, 0f);
            assertEquals("PLAYER", h.subjectType);
            assertEquals("u1", h.subjectId);
            assertEquals("login -> purchase -> refund", h.evidence.get("sequence"));
            assertEquals("5", h.evidence.get("window_minutes"));
            assertEquals(String.valueOf(t0), h.evidence.get("first_ts"));
            assertEquals("PLAYER:u1", h.evidence.get("subject"));
            assertTrue(h.reason.contains("login -> purchase -> refund"));

            RiskJob.RiskInput a2 = input("e4", "u1");
            a2.eventName = "login";
            a2.ts = new Timestamp(t0 + 1_000_000);
            assertNull(RiskJob.advance(RuleConfig.byType("PATTERN"), a2, st));   // 重置后从头再匹配
            assertEquals(1, st.nextIdx);
            assertEquals(t0 + 1_000_000, st.startTs);
        }
    }

    @Test
    @DisplayName("advance：ruleId null 走兜底名；乱序事件忽略不打断；第一步重复以最新起点重启")
    void patternIgnoresOutOfOrderAndRestartsOnFirstStep() {
        try (RuleOverride o = RuleOverride.set(Map.of("PATTERN", patternSpec(null, SEQ3)))) {
            RiskJob.SeqState st = new RiskJob.SeqState();
            long t0 = 10_000L;
            RiskJob.RiskInput a = input("e1", "u1");
            a.eventName = "login";
            a.ts = new Timestamp(t0);
            RiskJob.RiskInput noise = input("e2", "u1");
            noise.eventName = "level_up";
            noise.ts = new Timestamp(t0 + 1_000);
            RiskJob.RiskInput b = input("e3", "u1");
            b.eventName = "purchase";
            b.ts = new Timestamp(t0 + 2_000);
            RiskJob.RiskInput c = input("e4", "u1");
            c.eventName = "refund";
            c.ts = new Timestamp(t0 + 3_000);

            assertNull(RiskJob.advance(RuleConfig.byType("PATTERN"), a, st));
            assertNull(RiskJob.advance(RuleConfig.byType("PATTERN"), noise, st));   // 非期望步非第一步：忽略
            assertEquals(1, st.nextIdx);
            assertEquals(t0, st.startTs);

            RiskJob.RiskInput a2 = input("e5", "u1");
            a2.eventName = "login";
            a2.ts = new Timestamp(t0 + 10_000);
            assertNull(RiskJob.advance(RuleConfig.byType("PATTERN"), a2, st));   // 第一步重复：以最新起点重启
            assertEquals(1, st.nextIdx);
            assertEquals(t0 + 10_000, st.startTs);

            assertNull(RiskJob.advance(RuleConfig.byType("PATTERN"), b, st));
            RiskJob.RiskHit h = RiskJob.advance(RuleConfig.byType("PATTERN"), c, st);
            assertNotNull(h);
            assertEquals("risk-pattern-sequence", h.ruleId);   // ruleId null → 兜底名
        }
    }

    @Test
    @DisplayName("advance：窗口自第一步起算——边界值不超窗；超窗惰性作废；作废事件为第一步时重新起序")
    void patternExpiresLazilyBeyondWindow() {
        try (RuleOverride o = RuleOverride.set(Map.of("PATTERN", patternSpec(null, SEQ3)))) {
            RiskJob.SeqState st = new RiskJob.SeqState();
            RiskJob.RiskInput a = input("e1", "u1");
            a.eventName = "login";
            a.ts = new Timestamp(0L);
            RiskJob.RiskInput boundary = input("e2", "u1");
            boundary.eventName = "purchase";
            boundary.ts = new Timestamp(300_000L);   // 恰好 300s：不严格大于 → 仍有效
            assertNull(RiskJob.advance(RuleConfig.byType("PATTERN"), a, st));
            assertNull(RiskJob.advance(RuleConfig.byType("PATTERN"), boundary, st));
            assertEquals(2, st.nextIdx);

            RiskJob.SeqState st2 = new RiskJob.SeqState();
            RiskJob.RiskInput a2 = input("e3", "u1");
            a2.eventName = "login";
            a2.ts = new Timestamp(1_000_000L);
            RiskJob.RiskInput expired = input("e4", "u1");
            expired.eventName = "purchase";
            expired.ts = new Timestamp(1_300_001L);   // 300.001s > 300s：作废且非第一步不起序
            assertNull(RiskJob.advance(RuleConfig.byType("PATTERN"), a2, st2));
            assertNull(RiskJob.advance(RuleConfig.byType("PATTERN"), expired, st2));
            assertNull(st2.nextIdx);
            assertNull(st2.startTs);

            RiskJob.SeqState st3 = new RiskJob.SeqState();
            RiskJob.RiskInput a3 = input("e5", "u1");
            a3.eventName = "login";
            a3.ts = new Timestamp(2_000_000L);
            RiskJob.RiskInput lateLogin = input("e6", "u1");
            lateLogin.eventName = "login";
            lateLogin.ts = new Timestamp(2_300_001L);   // 超窗作废，但自身是第一步 → 重新起序
            assertNull(RiskJob.advance(RuleConfig.byType("PATTERN"), a3, st3));
            assertNull(RiskJob.advance(RuleConfig.byType("PATTERN"), lateLogin, st3));
            assertEquals(1, st3.nextIdx);
            assertEquals(2_300_001L, st3.startTs);
        }
    }

    @Test
    @DisplayName("advance 防御侧：null 事件名与 <2 步序列不命中不推进")
    void advanceDefensiveSides() {
        RiskJob.RiskInput nameless = input("e1", "u1");
        nameless.eventName = null;
        assertNull(RiskJob.advance(patternSpec("rp", SEQ3), nameless, new RiskJob.SeqState()));

        RuleConfig.RuleSpec shortSeq = patternSpec("rp", List.of("login"));
        RiskJob.SeqState st = new RiskJob.SeqState();
        RiskJob.RiskInput e = input("e2", "u1");
        e.eventName = "login";
        assertNull(RiskJob.advance(shortSeq, e, st));
        assertNull(st.nextIdx);
        assertNull(st.startTs);
    }

    @Test
    @DisplayName("inPatternSequence：事件名在序列集合内才放行（快照侧 + DEFAULTS 侧）")
    void inPatternSequenceFilters() {
        try (RuleOverride o = RuleOverride.set(Map.of("PATTERN", patternSpec("rp", SEQ3)))) {
            RiskJob.RiskInput in = input("e1", "u1");
            in.eventName = "login";
            assertTrue(RiskJob.inPatternSequence(in));
            in.eventName = "level_up";
            assertFalse(RiskJob.inPatternSequence(in));
            in.eventName = null;
            assertFalse(RiskJob.inPatternSequence(in));
        }
        RiskJob.RiskInput def = input("e2", "u1");   // 空快照 → DEFAULTS 序列兜底
        def.eventName = "purchase";
        assertTrue(RiskJob.inPatternSequence(def));
    }

    @Test
    @DisplayName("PatternFunction：open 建 keyed 状态，processElement 推进并在序列完成时输出命中后重置")
    void patternFunctionProcessElementEmitsOnCompletion() throws Exception {
        resetRuleFetcher();
        try (RuleOverride o = RuleOverride.set(Map.of("PATTERN",
                patternSpec("rp-9", List.of("login", "purchase"))))) {   // 2 步边界：末步即完成步
            org.apache.flink.api.common.state.ValueState<Integer> progress =
                    new org.apache.flink.api.common.state.ValueState<>() {
                        private Integer v;

                        @Override
                        public Integer value() {
                            return v;
                        }

                        @Override
                        public void update(Integer value) {
                            this.v = value;
                        }

                        @Override
                        public void clear() {
                            this.v = null;
                        }
                    };
            org.apache.flink.api.common.state.ValueState<Long> startedAt =
                    new org.apache.flink.api.common.state.ValueState<>() {
                        private Long v;

                        @Override
                        public Long value() {
                            return v;
                        }

                        @Override
                        public void update(Long value) {
                            this.v = value;
                        }

                        @Override
                        public void clear() {
                            this.v = null;
                        }
                    };
            org.apache.flink.api.common.functions.RuntimeContext rc =
                    (org.apache.flink.api.common.functions.RuntimeContext) Proxy.newProxyInstance(
                            RiskJobPatternStateMachineTest.class.getClassLoader(),
                            new Class<?>[]{org.apache.flink.api.common.functions.RuntimeContext.class},
                            (p, m, args) -> {
                                if ("getState".equals(m.getName())) {
                                    org.apache.flink.api.common.state.ValueStateDescriptor<?> d =
                                            (org.apache.flink.api.common.state.ValueStateDescriptor<?>) args[0];
                                    return "pattern-progress".equals(d.getName()) ? progress : startedAt;
                                }
                                return null;
                            });

            RiskJob.PatternFunction fn = new RiskJob.PatternFunction(deadRules());
            fn.setRuntimeContext(rc);
            fn.open(new org.apache.flink.configuration.Configuration());   // 经 startOnce 启动单例 fetcher

            List<RiskJob.RiskHit> out = new ArrayList<>();
            RiskJob.RiskInput a = input("e1", "u1");
            a.eventName = "login";
            a.ts = new Timestamp(1_000L);
            RiskJob.RiskInput b = input("e2", "u1");
            b.eventName = "purchase";
            b.ts = new Timestamp(61_000L);

            fn.processElement(a, null, sinkTo(out));
            assertTrue(out.isEmpty());
            assertEquals(1, progress.value());
            assertEquals(1_000L, startedAt.value());

            fn.processElement(b, null, sinkTo(out));
            assertEquals(1, out.size());
            RiskJob.RiskHit h = out.get(0);
            assertEquals("rp-9", h.ruleId);
            assertEquals("PATTERN", h.riskType);
            assertEquals("1000", h.evidence.get("first_ts"));
            assertNull(progress.value());   // 命中后重置
            assertNull(startedAt.value());
        } finally {
            stopRuleFetcher();
        }
    }

}
