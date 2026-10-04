package io.oddsmaker.jobs.risk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.oddsmaker.jobs.enrich.RawEvent;
import org.apache.flink.api.common.eventtime.SerializableTimestampAssigner;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.state.ReadOnlyBroadcastState;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.SinkFunction;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B5 验收钉子（计划书 §5.4 三口径）：
 * 特征计算（口径纯函数）、窗口滑动（真窗口执行：有界源末尾 MAX_WATERMARK 全窗闭合）、
 * 规则引用特征（条件解析 + 广播快照评估 + 命中构造）。
 */
@DisplayName("B5 风控 Feature 层：特征计算、窗口滑动、规则引用特征")
class RiskFeatureTest {

    // ===== 事件构造 =====

    private static RiskJob.RiskInput ev(long tsMs, String userId, String deviceId, String ip,
                                        String eventName, String flowType, String resourceId, Double amount,
                                        String propsJson) {
        RiskJob.RiskInput i = new RiskJob.RiskInput();
        i.gameId = "g1";
        i.environment = "prod";
        i.eventId = "e" + tsMs + (userId == null ? deviceId : userId);
        i.eventName = eventName;
        i.userId = userId == null ? "" : userId;
        i.deviceId = deviceId;
        i.clientIp = ip;
        i.ts = new Timestamp(tsMs);
        i.amount = amount == null ? null : java.math.BigDecimal.valueOf(amount);
        i.flowType = flowType == null ? "" : flowType;
        i.resourceId = resourceId == null ? "" : resourceId;
        i.propsJson = propsJson;
        return i;
    }

    private static RiskJob.RiskInput gold(long tsMs, String userId, String deviceId, double amount) {
        return ev(tsMs, userId, deviceId, "10.0.0.1", "resource:gold:gain", "source", "gold", amount, null);
    }

    // ===== 特征计算（口径纯函数） =====

    @Test
    @DisplayName("特征计算：gold_gain 只计 flow=source 且 resource=gold，求和含 null 容忍")
    void goldGainCalc() {
        assertTrue(FeatureCalc.isGoldSource(gold(1, "u1", "d1", 100)));
        assertFalse(FeatureCalc.isGoldSource(ev(1, "u1", "d1", null, "r", "sink", "gold", 100.0, null)));
        assertFalse(FeatureCalc.isGoldSource(ev(1, "u1", "d1", null, "r", "source", "gem", 100.0, null)));

        List<RiskJob.RiskInput> events = List.of(
                gold(1, "u1", "d1", 100), gold(2, "u1", "d1", 50.5),
                ev(3, "u1", "d1", null, "x", "source", "gold", null, null),  // 无金额不计
                ev(4, "u1", "d1", null, "x", "sink", "gold", 999.0, null));  // 流出不计
        assertEquals(150.5, FeatureCalc.goldGain(events), 1e-9);
    }

    @Test
    @DisplayName("特征计算：win_rate=match 结果事件胜占比；props 无 win/解析失败按未胜计")
    void winRateCalc() {
        assertTrue(FeatureCalc.isMatchResult(ev(1, "u", "d", null, "match:complete", null, null, null, null)));
        assertTrue(FeatureCalc.isMatchResult(ev(1, "u", "d", null, "match:end", null, null, null, null)));
        assertFalse(FeatureCalc.isMatchResult(ev(1, "u", "d", null, "match:start", null, null, null, null)));
        assertFalse(FeatureCalc.isMatchResult(ev(1, "u", "d", null, "level:complete", null, null, null, null)));

        assertTrue(FeatureCalc.extractWin("{\"win\":true,\"rank\":1}"));
        assertFalse(FeatureCalc.extractWin("{\"win\":false}"));
        assertFalse(FeatureCalc.extractWin("{}"));
        assertFalse(FeatureCalc.extractWin("not-json"));

        List<RiskJob.RiskInput> events = List.of(
                ev(1, "u", "d", null, "match:complete", null, null, null, "{\"win\":true}"),
                ev(2, "u", "d", null, "match:complete", null, null, null, "{\"win\":false}"),
                ev(3, "u", "d", null, "match:end", null, null, null, null),
                ev(4, "u", "d", null, "resource:gold:gain", null, null, null, "{\"win\":true}"));
        assertEquals(1.0 / 3.0, FeatureCalc.winRate(events), 1e-9);
        assertEquals(-1, FeatureCalc.winRate(List.of()), 1e-9);  // 无结果事件 → 调用方跳过产出
    }

    @Test
    @DisplayName("特征计算：device_count/account_count 去重计数与 scope_key 编码")
    void countAndScope() {
        List<RiskJob.RiskInput> events = List.of(
                ev(1, "u1", "d1", null, "e", null, null, null, null),
                ev(2, "u1", "d2", null, "e", null, null, null, null),
                ev(3, "u1", "d1", null, "e", null, null, null, null));
        assertEquals(2, FeatureCalc.deviceCount(events));
        assertEquals(1, FeatureCalc.accountCount(events));

        RiskJob.RiskInput withIp = ev(1, "u1", "d1", "10.1.1.7", "e", null, null, null, null);
        assertEquals("IP:10.1.1.7", FeatureCalc.ipScopeKey(withIp));
        assertEquals("PLAYER:u1", FeatureCalc.subjectScopeKey(withIp));
        assertNull(FeatureCalc.ipScopeKey(ev(1, "u1", "d1", null, "e", null, null, null, null)));
    }

    @Test
    @DisplayName("特征计算：条件比较 5 算子全量（未知算子恒 false）")
    void conditionOps() {
        assertTrue(FeatureCalc.matches(">", 100.5, 100));
        assertFalse(FeatureCalc.matches(">", 100, 100));
        assertTrue(FeatureCalc.matches(">=", 100, 100));
        assertTrue(FeatureCalc.matches("<", 99.9, 100));
        assertTrue(FeatureCalc.matches("<=", 100, 100));
        assertTrue(FeatureCalc.matches("==", 100, 100));
        assertFalse(FeatureCalc.matches("==", 100.1, 100));
        assertFalse(FeatureCalc.matches("!=", 1, 2));
        assertFalse(FeatureCalc.matches("", 1, 2));
    }

    @Test
    @DisplayName("窗口边界：TimeWindow 排他 end 转闭区间落表值（maxTimestamp+1）")
    void windowEndExclusive() {
        TimeWindow w = new TimeWindow(0, 60_000L);
        assertEquals(60_000L, FeatureCalc.windowEndOf(w));
        assertEquals(w.maxTimestamp() + 1, FeatureCalc.windowEndOf(w));
    }

    // ===== 窗口滑动（真窗口执行：有界源末尾 MAX_WATERMARK 全窗闭合） =====

    /** RiskInput 流接事件时间戳（窗口按 i.ts）；有界 source 结束自动推进 MAX_WATERMARK。 */
    private static DataStream<RiskJob.RiskInput> timed(StreamExecutionEnvironment env, List<RiskJob.RiskInput> events) {
        return env.fromCollection(events)
                .assignTimestampsAndWatermarks(WatermarkStrategy
                        .<RiskJob.RiskInput>forMonotonousTimestamps()
                        .withTimestampAssigner((SerializableTimestampAssigner<RiskJob.RiskInput>) (i, ts) -> i.ts.getTime()));
    }

    private static final class RowCollector implements SinkFunction<FeatureRow> {
        static final ConcurrentLinkedQueue<FeatureRow> ROWS = new ConcurrentLinkedQueue<>();
        @Override public void invoke(FeatureRow value, Context context) { ROWS.add(value); }
    }

    private static List<FeatureRow> runFeatureBranch(List<RiskJob.RiskInput> events) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.createLocalEnvironment(1);
        env.setParallelism(1);
        RowCollector.ROWS.clear();
        RiskJob.buildFeatureBranch(timed(env, events)).addSink(new RowCollector());
        env.execute("feature-branch-test");
        return new ArrayList<>(RowCollector.ROWS);
    }

    private static List<FeatureRow> of(List<FeatureRow> rows, String featureName) {
        List<FeatureRow> out = new ArrayList<>();
        for (FeatureRow r : rows) if (r.featureName.equals(featureName)) out.add(r);
        return out;
    }

    @Test
    @DisplayName("窗口滑动：gold_gain_1h 5 分钟步长滚动出 12 窗，行带 [start,end) 界与聚合值")
    void goldGainSlidingWindows() throws Exception {
        long base = Duration.ofHours(48).toMillis();  // 对齐任意窗界均不贴边
        List<RiskJob.RiskInput> events = List.of(gold(base, "u1", "d1", 400_000), gold(base + 60_000, "u1", "d1", 200_000));
        List<FeatureRow> rows = of(runFeatureBranch(events), "gold_gain_1h");

        // 两条事件同落一个 5 分钟桶：1h/5m 滑动 → 12 个窗口都含两条 → 各行值=600000
        assertEquals(12, rows.size());
        long slideMs = Duration.ofMinutes(5).toMillis();
        List<Long> starts = new ArrayList<>();
        for (FeatureRow r : rows) {
            assertEquals(600_000.0, r.value, 1e-9);
            assertEquals(Duration.ofHours(1).toMillis(), r.windowEndMs - r.windowStartMs);
            assertEquals("PLAYER:u1", r.scopeKey);
            assertEquals("g1", r.gameId);
            assertEquals("prod", r.environment);
            assertTrue(r.windowStartMs <= base && base + 60_000 < r.windowEndMs, "窗口须罩住全部事件");
            starts.add(r.windowStartMs);
        }
        Collections.sort(starts);
        // 步长 5 分钟：相邻窗口起点等差
        for (int i = 1; i < starts.size(); i++) {
            assertEquals(slideMs, starts.get(i) - starts.get(i - 1));
        }
    }

    @Test
    @DisplayName("窗口滑动：gold_gain_1h 与 gold_gain_24h 同源双窗，10m 计数特征 1 分钟步长")
    void multiWindowAndCount() throws Exception {
        long base = Duration.ofHours(48).toMillis();
        List<RiskJob.RiskInput> events = List.of(gold(base, "u1", "d1", 10), gold(base, "u1", "d1", 20));
        List<FeatureRow> rows = runFeatureBranch(events);

        List<FeatureRow> d24 = of(rows, "gold_gain_24h");
        // 24h/30m → 每桶 48 窗；两事件同桶同主体 → 每窗值=30
        assertEquals(48, d24.size());
        for (FeatureRow r : d24) {
            assertEquals(30.0, r.value, 1e-9);
            assertEquals(Duration.ofHours(24).toMillis(), r.windowEndMs - r.windowStartMs);
        }

        // 主体分窗：event_count_10m 10 分钟窗 1 分钟步长 → 同刻两事件落在 10 个滑动窗，均值=2
        List<FeatureRow> counts = of(rows, "event_count_10m");
        assertEquals(10, counts.size());
        for (FeatureRow r : counts) assertEquals(2.0, r.value, 1e-9);
    }

    @Test
    @DisplayName("窗口滑动：事件跨桶时各窗只聚合窗内事件；device_count/account_count_per_ip 去重与 IP scope")
    void crossBucketAndScopes() throws Exception {
        long base = Duration.ofHours(48).toMillis();
        long sixMin = Duration.ofMinutes(6).toMillis();
        List<RiskJob.RiskInput> events = List.of(
                // u1：两台设备、同一 IP；两次 match 一胜一负
                ev(base, "u1", "d1", "10.9.9.9", "e", null, null, null, null),
                ev(base + sixMin, "u1", "d2", "10.9.9.9", "e", null, null, null, null),
                ev(base + sixMin, "u1", "d2", "10.9.9.9", "match:complete", null, null, null, "{\"win\":true}"),
                ev(base + sixMin, "u1", "d2", "10.9.9.9", "match:end", null, null, null, "{\"win\":false}"),
                // u2：同 IP 第二个账号（聚集信号）
                ev(base + sixMin, "u2", "d3", "10.9.9.9", "e", null, null, null, null));
        List<FeatureRow> rows = runFeatureBranch(events);

        // event_count_10m：10 分钟窗、1 分钟步长；u1 的 [base+5m,base+15m) 窗只含 base+6m 的 3 条
        List<FeatureRow> counts = of(rows, "event_count_10m");
        boolean foundSplit = false;
        for (FeatureRow r : counts) {
            if (r.windowStartMs == base + Duration.ofMinutes(5).toMillis()
                    && "PLAYER:u1".equals(r.scopeKey)) {
                foundSplit = true;
                assertEquals(3.0, r.value, 1e-9, "滑动窗口只聚合窗内事件");
            }
        }
        assertTrue(foundSplit, "须存在跨桶切分的窗口行");

        // device_count：u1 在含两设备的 1h 窗口 = 2
        boolean foundDevices = false;
        for (FeatureRow r : of(rows, "device_count")) {
            if (r.scopeKey.equals("PLAYER:u1") && r.value == 2.0) foundDevices = true;
        }
        assertTrue(foundDevices);

        // account_count_per_ip：IP scope，同 IP 两账号
        List<FeatureRow> perIp = of(rows, "account_count_per_ip");
        assertFalse(perIp.isEmpty());
        for (FeatureRow r : perIp) assertEquals("IP:10.9.9.9", r.scopeKey);
        assertTrue(perIp.stream().anyMatch(r -> r.value == 2.0), "同 IP 两账号特征=2");

        // win_rate：两结果事件一胜 → 0.5
        assertTrue(of(rows, "win_rate").stream().anyMatch(r -> Math.abs(r.value - 0.5) < 1e-9));
    }

    // ===== 规则引用特征（解析 → 快照评估 → 命中构造） =====

    /** ReadOnlyBroadcastState 测试桩：内存 map 供快照评估。 */
    private static final class FakeState implements ReadOnlyBroadcastState<String, FeatureRow> {
        private final Map<String, FeatureRow> map = new HashMap<>();

        void put(String k, FeatureRow v) { map.put(k, v); }

        @Override public FeatureRow get(String key) { return map.get(key); }
        @Override public boolean contains(String key) { return map.containsKey(key); }
        @Override public Iterable<Map.Entry<String, FeatureRow>> immutableEntries() { return map.entrySet(); }
        @Override public void clear() { map.clear(); }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static RuleConfig.RuleSpec featureSpec(String conditionsJson) throws Exception {
        JsonNode n = MAPPER.readTree(conditionsJson);
        List<RuleConfig.FeatureCondition> conds = RuleFetcher.parseFeatureConditions(n.path("ruleConditions"));
        if (conds.isEmpty()) return null;
        return RuleConfig.RuleSpec.featureSpec("rule-f1", "FEATURE", "REVIEW", 85, "HIGH", conds);
    }

    private static final String CONDITIONS_JSON = """
            {"ruleConditions":{"features":[
              {"feature":"gold_gain_1h","op":">","value":500000},
              {"scope":"IP","feature":"account_count_per_ip","op":">=","value":2}]}}""";

    @Test
    @DisplayName("规则引用特征：ruleConditions.features 解析（scope 缺省 SUBJECT，非法整表作废）")
    void parseFeatureConditions() throws Exception {
        JsonNode ok = MAPPER.readTree(CONDITIONS_JSON);
        List<RuleConfig.FeatureCondition> conds = RuleFetcher.parseFeatureConditions(ok.path("ruleConditions"));
        assertEquals(2, conds.size());
        assertEquals("SUBJECT", conds.get(0).scope);
        assertEquals("IP", conds.get(1).scope);
        assertEquals(">=", conds.get(1).op);
        assertEquals(2.0, conds.get(1).threshold, 1e-9);

        // 非法输入整表作废：非对象 / 空 features / 非法 scope / 非法 op / 非数字 value / 缺 feature
        for (String bad : new String[]{
                "null", "{}", "{\"features\":[]}",
                "{\"features\":[{\"feature\":\"f\",\"op\":\">\",\"value\":1,\"scope\":\"GAME\"}]}",
                "{\"features\":[{\"feature\":\"f\",\"op\":\"!=\",\"value\":1}]}",
                "{\"features\":[{\"feature\":\"f\",\"op\":\">\",\"value\":\"x\"}]}",
                "{\"features\":[{\"op\":\">\",\"value\":1}]}",
                "{\"sequence\":[\"a\",\"b\"]}"}) {
            JsonNode n = MAPPER.readTree(bad);
            assertTrue(RuleFetcher.parseFeatureConditions(n).isEmpty(), "应作废: " + bad);
        }
    }

    @Test
    @DisplayName("规则引用特征：广播快照评估全 AND——无值不判真、IP 无 IP 不判真、阈值不过不命中")
    void resolveSnapshotSemantics() throws Exception {
        RuleConfig.RuleSpec spec = featureSpec(CONDITIONS_JSON);
        assertNotNull(spec);
        assertEquals("FEATURE", spec.ruleType);
        assertEquals(0, spec.triggerThreshold);

        RiskJob.RiskInput in = ev(1, "u1", "d1", "10.9.9.9", "resource:gold:gain", "source", "gold", 600_000.0, null);
        String subjectKey = FeatureRow.stateKey("PLAYER:u1", "gold_gain_1h");
        String ipKey = FeatureRow.stateKey("IP:10.9.9.9", "account_count_per_ip");

        FakeState state = new FakeState();
        // 无值不判真
        assertNull(FeatureRuleFunction.resolveSnapshot(spec, "PLAYER:u1", "IP:10.9.9.9", state));
        // 只过第一条件不命中
        state.put(subjectKey, new FeatureRow("g1", "prod", "PLAYER:u1", "gold_gain_1h", 0, 3_600_000L, 600_000, 1));
        assertNull(FeatureRuleFunction.resolveSnapshot(spec, "PLAYER:u1", "IP:10.9.9.9", state));
        // IP 条件阈值不过不命中
        state.put(ipKey, new FeatureRow("g1", "prod", "IP:10.9.9.9", "account_count_per_ip", 0, 3_600_000L, 1, 1));
        assertNull(FeatureRuleFunction.resolveSnapshot(spec, "PLAYER:u1", "IP:10.9.9.9", state));
        // 全条件满足 → 快照
        state.put(ipKey, new FeatureRow("g1", "prod", "IP:10.9.9.9", "account_count_per_ip", 0, 3_600_000L, 2, 1));
        Map<String, FeatureRow> snap = FeatureRuleFunction.resolveSnapshot(spec, "PLAYER:u1", "IP:10.9.9.9", state);
        assertNotNull(snap);
        assertEquals(600_000.0, snap.get("gold_gain_1h").value, 1e-9);
        // 主体无 IP（本事件与历史皆无）→ IP 条件无值不判真
        assertNull(FeatureRuleFunction.resolveSnapshot(spec, "PLAYER:u1", null, state));
    }

    @Test
    @DisplayName("规则引用特征：命中构造带特征值/窗口界/scope_key 证据，字段对齐 risk_events 列")
    void featureHitShape() throws Exception {
        RuleConfig.RuleSpec spec = featureSpec(CONDITIONS_JSON);
        RiskJob.RiskInput in = ev(1_000, "u1", "d1", "10.9.9.9", "resource:gold:gain", "source", "gold", 600_000.0, null);
        Map<String, FeatureRow> snap = Map.of(
                "gold_gain_1h", new FeatureRow("g1", "prod", "PLAYER:u1", "gold_gain_1h", 0, 3_600_000L, 600_000, 1),
                "account_count_per_ip", new FeatureRow("g1", "prod", "IP:10.9.9.9", "account_count_per_ip", 0, 3_600_000L, 2, 1));

        RiskJob.RiskHit hit = FeatureRuleFunction.featureHit(spec, in, snap);
        assertEquals("rule-f1", hit.ruleId);
        assertEquals("FEATURE", hit.riskType);
        assertEquals("HIGH", hit.severity);
        assertEquals(85f, hit.score);
        assertEquals("REVIEW", hit.action);
        assertEquals("PLAYER", hit.subjectType);
        assertEquals("u1", hit.subjectId);
        assertEquals("g1", hit.gameId);
        assertEquals("prod", hit.environment);
        assertEquals("PLAYER:u1", hit.evidence.get("scope_key"));
        assertEquals("600000", hit.evidence.get("feature:gold_gain_1h"));
        assertEquals("3600000", hit.evidence.get("feature:gold_gain_1h:window_end"));
        assertEquals("2", hit.evidence.get("feature:account_count_per_ip"));
        assertTrue(hit.reason.contains("gold_gain_1h=600000"));
    }

    @Test
    @DisplayName("规则拉取：FEATURE 规则经 /api/risk-dashboard/rules 解析入快照（无有效条件跳过）")
    void fetcherParsesFeatureRule() throws Exception {
        List<String> seen = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/risk-dashboard/rules/", ex -> {
            seen.add(ex.getRequestURI().getPath());
            byte[] body = """
                    [
                      {"id":"rf1","ruleType":"FEATURE","actionType":"REVIEW","riskScore":85,"riskLevel":"HIGH",
                       "ruleConditions":{"features":[{"feature":"gold_gain_1h","op":">","value":500000}]}},
                      {"id":"rf2","ruleType":"FEATURE","actionType":"ALERT","riskScore":99,"riskLevel":"CRITICAL",
                       "ruleConditions":{"features":[]}},
                      {"id":"rf3","ruleType":"FEATURE","actionType":"ALERT","riskScore":70,"riskLevel":"MEDIUM",
                       "ruleConditions":{"features":[{"feature":"gold_gain_1h","op":">=","value":100000},
                                                      {"scope":"IP","feature":"account_count_per_ip","op":">=","value":2}]}}
                    ]""".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            try (var os = ex.getResponseBody()) { os.write(body); }
        });
        server.start();
        try {
            RuleConfig.update(new RuleConfig(Map.of()));
            RuleFetcher f = new RuleFetcher("http://127.0.0.1:" + server.getAddress().getPort(), "g1", "", 1000);
            Method m = RuleFetcher.class.getDeclaredMethod("fetchAndApply");
            m.setAccessible(true);
            m.invoke(f);

            // 同类型（FEATURE）收敛取 riskScore 最高：rf2 空条件跳过；rf1(85) 胜 rf3(70)
            RuleConfig.RuleSpec spec = RuleConfig.byType("FEATURE");
            assertNotNull(spec, "FEATURE 规则应入快照");
            assertEquals("rf1", spec.ruleId);
            assertEquals(85, spec.riskScore);
            assertEquals(1, spec.features.size());
            assertEquals("gold_gain_1h", spec.features.get(0).feature);
        } finally {
            server.stop(0);
            RuleConfig.update(new RuleConfig(Map.of()));
        }
    }

    // ===== 管道接线与落表绑定 =====

    @Test
    @DisplayName("config：B5 新增槽位缺省值与 property 覆盖（pg 三元组/水位线秒/特征开关）")
    void configNewSlots() {
        Object[] cfg = RiskJob.config();
        assertEquals("jdbc:postgresql://localhost:5432/oddsmaker", cfg[12]);
        assertEquals("oddsmaker", cfg[13]);
        assertEquals("oddsmaker", cfg[14]);
        assertEquals(120, cfg[15]);
        assertTrue((Boolean) cfg[16]);

        System.setProperty("control.db.url", "jdbc:postgresql://pg:5432/odd");
        System.setProperty("control.db.user", "u");
        System.setProperty("control.db.pass", "p");
        System.setProperty("risk.watermark.delay-seconds", "0");
        System.setProperty("risk.features.enabled", "false");
        try {
            Object[] c = RiskJob.config();
            assertEquals("jdbc:postgresql://pg:5432/odd", c[12]);
            assertEquals("u", c[13]);
            assertEquals("p", c[14]);
            assertEquals(0, c[15]);
            assertEquals(false, c[16]);
        } finally {
            System.clearProperty("control.db.url");
            System.clearProperty("control.db.user");
            System.clearProperty("control.db.pass");
            System.clearProperty("risk.watermark.delay-seconds");
            System.clearProperty("risk.features.enabled");
        }
    }

    @Test
    @DisplayName("buildPipeline：特征分支（6 特征窗 + upsert sink + 广播评估）惰性成图；开关关闭则退回七类规则")
    void pipelineWiresFeatureBranch() throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.createLocalEnvironment(1);
        DataStream<RiskJob.RiskHit> tail = RiskJob.buildPipeline(env, RiskJob.config());
        assertNotNull(tail);
        boolean hasFeatureSink = env.getTransformations().stream()
                .anyMatch(t -> "postgres-risk-features".equals(t.getName()));
        assertTrue(hasFeatureSink, "特征 upsert sink 应入图");

        System.setProperty("risk.features.enabled", "false");
        try {
            StreamExecutionEnvironment env2 = StreamExecutionEnvironment.createLocalEnvironment(1);
            RiskJob.buildPipeline(env2, RiskJob.config());
            boolean stillThere = env2.getTransformations().stream()
                    .anyMatch(t -> "postgres-risk-features".equals(t.getName()));
            assertFalse(stillThere, "开关关闭不应有特征分支");
        } finally {
            System.clearProperty("risk.features.enabled");
        }
    }

    @Test
    @DisplayName("落表绑定：bindFeatureRow 8 列顺序对齐 upsert SQL（game/env/scope/feature/ws/we/value/as_of）")
    void bindFeatureRowAlignsSql() throws Exception {
        assertTrue(RiskJob.FEATURE_UPSERT_SQL.contains(
                "ON CONFLICT (game_id, environment, scope_key, feature_name, window_start, window_end)"));
        assertTrue(RiskJob.FEATURE_UPSERT_SQL.contains("DO UPDATE SET value = EXCLUDED.value, as_of = EXCLUDED.as_of"));

        // PreparedStatement 桩记录 set 调用序列
        List<String> calls = new ArrayList<>();
        FeatureRow row = new FeatureRow("g1", "prod", "PLAYER:u1", "gold_gain_1h", 0, 60_000L, 12.5, 61_000L);
        PreparedStatement ps = (PreparedStatement) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class},
                (p, m, a) -> {
                    if (m.getName().startsWith("set"))
                        calls.add(m.getName() + ":" + a[0] + "=" + a[1]);
                    return null;
                });
        RiskJob.bindFeatureRow(ps, row);
        assertEquals(8, calls.size());
        assertEquals("setString:1=g1", calls.get(0));
        assertEquals("setString:2=prod", calls.get(1));
        assertEquals("setString:3=PLAYER:u1", calls.get(2));
        assertEquals("setString:4=gold_gain_1h", calls.get(3));
        assertEquals("setTimestamp:5=" + new Timestamp(0), calls.get(4));
        assertEquals("setTimestamp:6=" + new Timestamp(60_000L), calls.get(5));
        assertEquals("setDouble:7=12.5", calls.get(6));
        assertEquals("setTimestamp:8=" + new Timestamp(61_000L), calls.get(7));
    }

    @Test
    @DisplayName("toRiskInput：resource_id/props_json 透传进 RiskInput（特征口径输入）")
    void riskInputCarriesFeatureFields() {
        RawEvent r = new RawEvent();
        r.game_id = "g1";
        r.environment = "prod";
        r.event_id = "e1";
        r.event_name = "resource:gold:gain";
        r.device_id = "d1";
        r.user_id = "u1";
        r.resource_id = "gold";
        r.resource_amount = 123.0;
        r.flow_type = "source";
        r.props_json = "{\"win\":true}";
        RiskJob.RiskInput in = RiskJob.toRiskInput(r);
        assertEquals("gold", in.resourceId);
        assertEquals("{\"win\":true}", in.propsJson);
        assertTrue(FeatureCalc.isGoldSource(in));
    }
}
