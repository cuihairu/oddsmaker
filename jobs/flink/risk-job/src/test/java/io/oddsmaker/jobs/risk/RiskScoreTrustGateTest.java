package io.oddsmaker.jobs.risk;

import io.oddsmaker.jobs.enrich.RawEvent;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.SinkFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B6 验收钉子（计划书 §5.2）：
 * - BLOCK 级动作高信任门槛：trust_level=HIGH 输入才放行 BLOCK，否则降级 REVIEW（fail-closed）；
 * - RiskScore 独立：按主体累计各规则最大贡献分，每次评估产出 risk_scores 行（累计分 + 规则触发明细 JSON）。
 */
@DisplayName("B6 RiskScore 独立：BLOCK 高信任门槛、主体累计分、risk_scores 落行")
class RiskScoreTrustGateTest {

    // ===== 构造 helper =====

    private static RiskJob.RiskHit hit(String ruleId, float score, String action, String subjectId, String trust) {
        Map<String, String> ev = new LinkedHashMap<>();
        ev.put("k", "v");
        return new RiskJob.RiskHit(
                "g1", "prod", new Timestamp(1_000L), "re_" + ruleId + "_" + subjectId, "sid",
                ruleId, "THRESHOLD", "HIGH", "PLAYER", subjectId,
                score, action, "reason", ev, trust);
    }

    /** RiskInput 便捷构造（信任档位可指定）。 */
    private static RiskJob.RiskInput input(String trustLevel, String receiptKey) {
        RiskJob.RiskInput i = new RiskJob.RiskInput();
        i.gameId = "g1";
        i.environment = "prod";
        i.eventId = "e1";
        i.eventName = "purchase";
        i.userId = "u1";
        i.deviceId = "d1";
        i.ts = new Timestamp(1_000L);
        i.flowType = "source";
        i.receiptKey = receiptKey;
        i.trustLevel = trustLevel;
        return i;
    }

    // ===== BLOCK 高信任门槛 =====

    @Test
    @DisplayName("门槛：BLOCK 仅 HIGH 放行，''/null/LOW/COMPUTED 降级 REVIEW；非 BLOCK 动作不受影响")
    void gateActionRequiresHighTrust() {
        assertEquals("BLOCK", RiskJob.gateAction("BLOCK", "HIGH"));
        assertEquals("REVIEW", RiskJob.gateAction("BLOCK", ""));
        assertEquals("REVIEW", RiskJob.gateAction("BLOCK", null));
        assertEquals("REVIEW", RiskJob.gateAction("BLOCK", "LOW"));
        assertEquals("REVIEW", RiskJob.gateAction("BLOCK", "COMPUTED"));
        assertEquals("REVIEW", RiskJob.gateAction("block", "low")); // 大小写不敏感

        for (String action : List.of("ALERT", "REVIEW", "THROTTLE", "MARK", "WEBHOOK")) {
            assertEquals(action, RiskJob.gateAction(action, ""), "非 BLOCK 动作不受门槛影响");
        }
    }

    @Test
    @DisplayName("门槛接线：降级打 [trust_gate] reason 标记，HIGH 原样不动")
    void applyTrustGateMarksDowngrade() {
        RiskJob.RiskHit low = hit("rr_b", 90f, "BLOCK", "d1", "LOW");
        RiskJob.RiskHit gated = RiskJob.applyTrustGate(low);
        assertEquals("REVIEW", gated.action);
        assertTrue(gated.reason.startsWith("[trust_gate] "), gated.reason);

        RiskJob.RiskHit high = hit("rr_b", 90f, "BLOCK", "d1", "HIGH");
        assertEquals("BLOCK", RiskJob.applyTrustGate(high).action);
        assertEquals("reason", high.reason);
    }

    @Test
    @DisplayName("窗口口径：全 HIGH 收敛为 HIGH，混杂即归空（fail-closed）")
    void trustScopeCollapsesOnMixedWindow() {
        assertEquals("HIGH", RiskJob.trustScope(true));
        assertEquals("", RiskJob.trustScope(false));
    }

    @Test
    @DisplayName("toRiskInput：trust_level 贯通，null 归一为 ''（fail-closed）")
    void toRiskInputCarriesTrustLevel() {
        RawEvent high = new RawEvent();
        high.game_id = "g";
        high.environment = "prod";
        high.event_id = "e1";
        high.device_id = "d1";
        high.trust_level = "HIGH";
        assertEquals("HIGH", RiskJob.toRiskInput(high).trustLevel);

        RawEvent missing = new RawEvent();
        missing.game_id = "g";
        missing.environment = "prod";
        missing.event_id = "e1";
        missing.device_id = "d1";
        missing.trust_level = null;
        assertEquals("", RiskJob.toRiskInput(missing).trustLevel);
    }

    @Test
    @DisplayName("单事件规则构造：THRESHOLD 命中带输入事件信任档位")
    void thresholdHitCarriesInputTrust() {
        RiskJob.RiskInput i = input("HIGH", null);
        i.amount = java.math.BigDecimal.valueOf(500_000);
        assertEquals("HIGH", RiskJob.thresholdHit(i).trustLevel);

        i.trustLevel = "";
        assertEquals("", RiskJob.thresholdHit(i).trustLevel);
    }

    @Test
    @DisplayName("窗口规则构造：DuplicateReceipt 混杂窗口命中信任归空、全 HIGH 收敛 HIGH")
    void windowHitCollapsesMixedTrust() {
        // 窗口算子直测选 DuplicateReceipt：默认阈值 2，触发成本最低
        RiskJob.DuplicateReceiptFunction fn = new RiskJob.DuplicateReceiptFunction(
                10, new RiskJob.RuleSource("http://localhost:1", "g1", "", 60_000L));

        // 全 HIGH 窗口 → 命中带 HIGH
        List<RiskJob.RiskInput> allHigh = List.of(input("HIGH", "rh1"), input("HIGH", "rh1"));
        RiskJob.RiskHit pure = collectWindowHit(fn, allHigh);
        assertEquals("HIGH", pure.trustLevel);

        // 混杂窗口 → 命中信任归空
        List<RiskJob.RiskInput> mixed = List.of(input("HIGH", "rh2"), input("", "rh2"));
        RiskJob.RiskHit dirty = collectWindowHit(fn, mixed);
        assertEquals("", dirty.trustLevel);
    }

    /** 直调窗口算子 process（不依赖 Flink 运行时；ctx 未被使用传 null）。 */
    private static RiskJob.RiskHit collectWindowHit(RiskJob.DuplicateReceiptFunction fn,
                                                    List<RiskJob.RiskInput> events) {
        List<RiskJob.RiskHit> out = new ArrayList<>();
        org.apache.flink.util.Collector<RiskJob.RiskHit> collector =
                new org.apache.flink.util.Collector<>() {
                    @Override
                    public void collect(RiskJob.RiskHit record) {
                        out.add(record);
                    }

                    @Override
                    public void close() {
                    }
                };
        fn.process("key", null, events, collector);
        assertEquals(1, out.size(), "窗口应产出命中");
        return out.get(0);
    }

    // ===== risk_scores 主体累计分 =====

    private static final class ScoreCollector implements SinkFunction<RiskJob.RiskScoreRow> {
        static final ConcurrentLinkedQueue<RiskJob.RiskScoreRow> ROWS = new ConcurrentLinkedQueue<>();

        @Override
        public void invoke(RiskJob.RiskScoreRow value, Context context) {
            ROWS.add(value);
        }
    }

    private static List<RiskJob.RiskScoreRow> runScores(List<RiskJob.RiskHit> hits) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.createLocalEnvironment(1);
        env.setParallelism(1);
        ScoreCollector.ROWS.clear();
        env.fromCollection(hits)
                .keyBy(RiskJob::scoreKey)
                .process(new RiskJob.RiskScoreFunction())
                .returns(Types.POJO(RiskJob.RiskScoreRow.class))
                .addSink(new ScoreCollector());
        env.execute("risk-score-test");
        return new ArrayList<>(ScoreCollector.ROWS);
    }

    /** 取目标主体的末行（最新累计快照）。 */
    private static RiskJob.RiskScoreRow lastFor(List<RiskJob.RiskScoreRow> rows, String subjectId) {
        RiskJob.RiskScoreRow last = null;
        for (RiskJob.RiskScoreRow r : rows) {
            if (subjectId.equals(r.subjectId)) last = r;
        }
        assertTrue(last != null, "主体 " + subjectId + " 应有 risk_scores 行");
        return last;
    }

    @Test
    @DisplayName("单规则：一次评估落行，score=规则最大贡献分，reasons 为规则明细 JSON")
    void singleRuleLandsRow() throws Exception {
        List<RiskJob.RiskScoreRow> rows = runScores(List.of(hit("rr_a", 80f, "ALERT", "u1", "HIGH")));
        assertEquals(1, rows.size());
        RiskJob.RiskScoreRow row = rows.get(0);
        assertEquals("g1", row.gameId);
        assertEquals("prod", row.environment);
        assertEquals("PLAYER", row.subjectType);
        assertEquals("u1", row.subjectId);
        assertEquals(80f, row.score);
        assertEquals(1, row.reasons.size());
        assertEquals("{\"rule_id\":\"rr_a\",\"contribution\":80.0}", row.reasons.get(0));
    }

    @Test
    @DisplayName("同规则重复命中：不叠加（riskScore=该规则最大贡献分），明细保持一条")
    void sameRuleDoesNotDoubleCount() throws Exception {
        List<RiskJob.RiskScoreRow> rows = runScores(List.of(
                hit("rr_a", 80f, "ALERT", "u1", "HIGH"),
                hit("rr_a", 80f, "ALERT", "u1", "HIGH")));
        assertEquals(2, rows.size(), "每次评估各落一行");
        RiskJob.RiskScoreRow last = lastFor(rows, "u1");
        assertEquals(80f, last.score, "同规则重复命中不叠加");
        assertEquals(1, last.reasons.size());
    }

    @Test
    @DisplayName("多规则：累计分为各规则最大贡献分之和，明细 JSON 按规则名排序")
    void multiRuleCumulativeSum() throws Exception {
        List<RiskJob.RiskScoreRow> rows = runScores(List.of(
                hit("rr_b", 60f, "ALERT", "u1", "HIGH"),
                hit("rr_a", 80f, "ALERT", "u1", "HIGH")));
        RiskJob.RiskScoreRow last = lastFor(rows, "u1");
        assertEquals(140f, last.score);
        assertEquals(2, last.reasons.size());
        assertEquals("{\"rule_id\":\"rr_a\",\"contribution\":80.0}", last.reasons.get(0));
        assertEquals("{\"rule_id\":\"rr_b\",\"contribution\":60.0}", last.reasons.get(1));
    }

    @Test
    @DisplayName("规则刷新：同规则再命中取高（贡献封顶为规则最新 riskScore）")
    void ruleScoreRaiseTakesMax() throws Exception {
        List<RiskJob.RiskScoreRow> rows = runScores(List.of(
                hit("rr_a", 80f, "ALERT", "u1", "HIGH"),
                hit("rr_a", 95f, "ALERT", "u1", "HIGH")));
        assertEquals(95f, lastFor(rows, "u1").score);

        List<RiskJob.RiskScoreRow> down = runScores(List.of(
                hit("rr_a", 95f, "ALERT", "u1", "HIGH"),
                hit("rr_a", 70f, "ALERT", "u1", "HIGH")));
        assertEquals(95f, lastFor(down, "u1").score, "规则降分不回撤已累计的高贡献");
    }

    @Test
    @DisplayName("主体隔离：不同 subject 各自累计，互不串分")
    void subjectsAreIsolated() throws Exception {
        List<RiskJob.RiskScoreRow> rows = runScores(List.of(
                hit("rr_a", 80f, "ALERT", "u1", "HIGH"),
                hit("rr_a", 80f, "ALERT", "u2", "HIGH"),
                hit("rr_b", 60f, "ALERT", "u2", "HIGH")));
        assertEquals(80f, lastFor(rows, "u1").score);
        assertEquals(140f, lastFor(rows, "u2").score);
    }

    @Test
    @DisplayName("bindRiskScore：7 列按位绑定，reasons 落 String 数组")
    void bindRiskScoreSetsAllParameters() throws Exception {
        Map<String, Object> calls = new LinkedHashMap<>();
        java.sql.Array reasonsArr = (java.sql.Array) Proxy.newProxyInstance(
                RiskScoreTrustGateTest.class.getClassLoader(), new Class<?>[]{java.sql.Array.class},
                (Object p, java.lang.reflect.Method m, Object[] a) -> null);
        PreparedStatement ps = (PreparedStatement) Proxy.newProxyInstance(
                RiskScoreTrustGateTest.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
                (Object p, java.lang.reflect.Method m, Object[] a) -> {
                    if ("getConnection".equals(m.getName())) {
                        return Proxy.newProxyInstance(
                                RiskScoreTrustGateTest.class.getClassLoader(), new Class<?>[]{java.sql.Connection.class},
                                (Object p2, java.lang.reflect.Method m2, Object[] a2) ->
                                        "createArrayOf".equals(m2.getName()) ? reasonsArr : null);
                    }
                    calls.put(m.getName() + ":" + a[0], a[1]);
                    return null;
                });

        Timestamp ts = new Timestamp(1_000L);
        List<String> reasons = List.of("{\"rule_id\":\"rr_a\",\"contribution\":80.0}");
        RiskJob.bindRiskScore(ps, new RiskJob.RiskScoreRow(
                "g1", "prod", "PLAYER", "u1", 140f, ts, reasons));

        assertEquals("g1", calls.get("setString:1"));
        assertEquals("prod", calls.get("setString:2"));
        assertEquals("PLAYER", calls.get("setString:3"));
        assertEquals("u1", calls.get("setString:4"));
        assertEquals(140f, calls.get("setFloat:5"));
        assertEquals(ts, calls.get("setTimestamp:6"));
        assertSame(reasonsArr, calls.get("setArray:7"));
    }
}
