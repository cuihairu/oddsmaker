package io.oddsmaker.jobs.risk;

import io.oddsmaker.jobs.enrich.RawEvent;
import org.apache.flink.api.common.eventtime.TimestampAssigner;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.util.Collector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("实时风控 job")
class RiskJobTest {

    // ===== 管道搭建（惰性，本地环境不 execute） =====

    @Test
    @DisplayName("buildPipeline：本地环境完成全管道搭建（source→map→六类规则→union→双 sink）不抛异常")
    void buildPipelineWiresWholeGraphLazily() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.createLocalEnvironment(1);
        DataStream<RiskJob.RiskHit> tail = RiskJob.buildPipeline(env, RiskJob.config());
        assertNotNull(tail);
        assertTrue(env.getTransformations().size() >= 10, "expect >= 10 transformations, got " + env.getTransformations().size());
    }

    @Test
    @DisplayName("config：读 System properties，缺省回退默认值")
    void configReadsPropertiesWithDefaults() {
        Object[] cfg = RiskJob.config();
        assertEquals("localhost:9092", cfg[0]);
        assertEquals("http://localhost:8081/apis/registry/v2", cfg[1]);
        assertEquals("oddsmaker.events_raw", cfg[2]);
        assertEquals("oddsmaker.risk_events", cfg[3]);
        assertEquals("jdbc:clickhouse://localhost:8123/default", cfg[4]);
        assertEquals("default", cfg[5]);
        assertEquals("", cfg[6]);
        assertEquals(10, cfg[7]);
        assertEquals("http://localhost:8085", cfg[8]);
        assertEquals("default", cfg[9]);
        assertEquals("", cfg[10]);
        assertEquals(60000L, cfg[11]);
        assertEquals("oddsmaker-risk-job", RiskJob.JOB_NAME);

        System.setProperty("risk.frequency.window-minutes", "5");
        System.setProperty("control.token", "tk-1");
        System.setProperty("rule.refresh-ms", "123");
        try {
            Object[] c = RiskJob.config();
            assertEquals(5, c[7]);
            assertEquals("tk-1", c[10]);
            assertEquals(123L, c[11]);
        } finally {
            System.clearProperty("risk.frequency.window-minutes");
            System.clearProperty("control.token");
            System.clearProperty("rule.refresh-ms");
        }
    }

    @Test
    @DisplayName("parseArgs：--key=value 落系统属性（覆盖优先）、非法参数忽略、null 安全")
    void parseArgsAppliesSystemProperties() {
        try {
            // 基本落位（Flink REST programArgs 途径）
            RiskJob.parseArgs(new String[]{"--control.url=http://control:8085", "--control.gameId=g1"});
            assertEquals("http://control:8085", System.getProperty("control.url"));
            assertEquals("g1", System.getProperty("control.gameId"));

            // 程序参数覆盖 -D 已有系统属性（setProperty 后到者胜）
            System.setProperty("kafka.bootstrap", "from-D:9092");
            RiskJob.parseArgs(new String[]{"--kafka.bootstrap=from-args:9092"});
            assertEquals("from-args:9092", System.getProperty("kafka.bootstrap"));

            // 非 -- 前缀 / 缺 = / "--" 裸前缀均忽略，合法参数在混排中仍生效
            RiskJob.parseArgs(new String[]{"positional", "--noeq", "--", "--testarg.onlyvalid=1"});
            assertEquals("1", System.getProperty("testarg.onlyvalid"));

            RiskJob.parseArgs(null);
        } finally {
            System.clearProperty("control.url");
            System.clearProperty("control.gameId");
            System.clearProperty("kafka.bootstrap");
            System.clearProperty("testarg.onlyvalid");
        }
    }

    @Test
    @DisplayName("watermarks/slideMinutes/subject*/str/nz/口径过滤：构件直测")
    void helpers() {
        WatermarkStrategy<RawEvent> wm = RiskJob.watermarks();
        assertNotNull(wm.createWatermarkGenerator(null));
        TimestampAssigner<RawEvent> assigner = wm.createTimestampAssigner(null);
        RawEvent e = new RawEvent();
        e.ts_server = 9_000_000L;
        assertEquals(9_000L, assigner.extractTimestamp(e, 0L));
        RawEvent clientOnly = new RawEvent();
        clientOnly.ts_client = 7_000_000L;
        assertEquals(7_000L, assigner.extractTimestamp(clientOnly, 0L));
        long before = System.currentTimeMillis();
        long nowMs = assigner.extractTimestamp(new RawEvent(), 0L);
        assertTrue(nowMs >= before && nowMs <= System.currentTimeMillis());

        assertEquals(5, RiskJob.slideMinutes(10));
        assertEquals(1, RiskJob.slideMinutes(1));
        assertEquals(1, RiskJob.slideMinutes(2));
        assertEquals(1, RiskJob.slideMinutes(3));   // 3/2 整除为 1
        assertEquals(2, RiskJob.slideMinutes(4));
        assertEquals(2, RiskJob.slideMinutes(5));

        RiskJob.RiskInput player = new RiskJob.RiskInput();
        player.userId = "u1";
        player.deviceId = "d1";
        assertEquals("PLAYER", RiskJob.subjectType(player));
        assertEquals("u1", RiskJob.subjectId(player));
        assertEquals("PLAYER:u1", RiskJob.subjectKey(player));

        RiskJob.RiskInput device = new RiskJob.RiskInput();
        device.userId = "";
        device.deviceId = "d1";
        assertEquals("DEVICE", RiskJob.subjectType(device));
        assertEquals("d1", RiskJob.subjectId(device));

        RiskJob.RiskInput keyed = new RiskJob.RiskInput();
        keyed.gameId = "g";
        keyed.environment = "prod";
        keyed.userId = "u1";
        keyed.deviceId = "d1";
        keyed.receiptKey = "rc_1";
        assertEquals("g|prod|PLAYER:u1", RiskJob.subjectWindowKey(keyed));
        assertEquals("g|prod|PLAYER:u1|rc_1", RiskJob.receiptWindowKey(keyed));

        assertNull(RiskJob.str(null));
        assertEquals("7", RiskJob.str(7));
        assertEquals("", RiskJob.nz(null));
        assertEquals("v", RiskJob.nz("v"));

        RiskJob.RiskInput amt = new RiskJob.RiskInput();
        amt.amount = new BigDecimal("50");
        assertTrue(RiskJob.hasAmount(amt));
        amt.amount = null;
        assertFalse(RiskJob.hasAmount(amt));
        amt.amount = BigDecimal.ZERO;
        assertFalse(RiskJob.hasAmount(amt));
        amt.amount = new BigDecimal("-1");
        assertFalse(RiskJob.hasAmount(amt));

        RiskJob.RiskInput flow = new RiskJob.RiskInput();
        flow.amount = new BigDecimal("10");
        flow.flowType = "source";
        assertTrue(RiskJob.isFlowAmount(flow));
        flow.flowType = "sink";
        assertTrue(RiskJob.isFlowAmount(flow));
        flow.flowType = "other";
        assertFalse(RiskJob.isFlowAmount(flow));
        flow.amount = null;
        assertFalse(RiskJob.isFlowAmount(flow));

        RiskJob.RiskInput receipt = new RiskJob.RiskInput();
        assertFalse(RiskJob.hasReceipt(receipt));
        receipt.receiptKey = "";
        assertFalse(RiskJob.hasReceipt(receipt));
        receipt.receiptKey = "rc";
        assertTrue(RiskJob.hasReceipt(receipt));
    }

    // ===== RawEvent → RiskInput =====

    @Test
    @DisplayName("toRiskInput：全字段映射 + ts_server 优先 + receipt_hash 优先 + ad_reward 判定")
    void toRiskInputMapsAllFields() {
        RawEvent full = fullEvent();
        RiskJob.RiskInput in = RiskJob.toRiskInput(full);
        assertEquals("g", in.gameId);
        assertEquals("prod", in.environment);
        assertEquals("e1", in.eventId);
        assertEquals("ad_reward_claim", in.eventName);
        assertEquals("economy", in.eventType);
        assertEquals("u1", in.userId);
        assertEquals("d1", in.deviceId);
        assertEquals("1.2.3.4", in.clientIp);
        assertEquals(new Timestamp(2_000L), in.ts);
        assertEquals(new BigDecimal("3.5"), in.amount);
        assertEquals("sink", in.flowType);
        assertEquals("rh1", in.receiptKey);
        assertEquals(new BigDecimal("9.99"), in.revenueAmount);
        assertTrue(in.adReward);
    }

    @Test
    @DisplayName("toRiskInput：必填字段缺失返回 null（四种各验）")
    void toRiskInputDropsOnMissingRequired() {
        assertNull(RiskJob.toRiskInput(new RawEvent()));

        RawEvent noGame = fullEvent();
        noGame.game_id = null;
        assertNull(RiskJob.toRiskInput(noGame));

        RawEvent noEnv = fullEvent();
        noEnv.environment = null;
        assertNull(RiskJob.toRiskInput(noEnv));

        RawEvent noEventId = fullEvent();
        noEventId.event_id = null;
        assertNull(RiskJob.toRiskInput(noEventId));

        RawEvent noDevice = fullEvent();
        noDevice.device_id = null;
        assertNull(RiskJob.toRiskInput(noDevice));
    }

    @Test
    @DisplayName("toRiskInput：ts 缺省回退 now；receiptKey 回退 order_id；ad_reward 按事件名")
    void toRiskInputFallbacks() {
        RawEvent tsMissing = fullEvent();
        tsMissing.ts_server = null;
        long before = System.currentTimeMillis();
        RiskJob.RiskInput noTs = RiskJob.toRiskInput(tsMissing);
        assertTrue(noTs.ts.getTime() >= before && noTs.ts.getTime() <= System.currentTimeMillis());

        RawEvent receiptFallback = fullEvent();
        receiptFallback.receipt_hash = null;
        receiptFallback.order_id = "o9";
        assertEquals("o9", RiskJob.toRiskInput(receiptFallback).receiptKey);
        RawEvent noReceipt = fullEvent();
        noReceipt.receipt_hash = null;
        assertNull(RiskJob.toRiskInput(noReceipt).receiptKey);

        RawEvent byFormatCase = fullEvent();
        byFormatCase.ad_format = "REWARDED";   // 大小写不敏感
        byFormatCase.event_name = "n1";
        assertTrue(RiskJob.toRiskInput(byFormatCase).adReward);

        RawEvent byName = fullEvent();
        byName.ad_format = null;
        byName.event_name = "watch_ad_reward_video";
        assertTrue(RiskJob.toRiskInput(byName).adReward);

        RawEvent neither = fullEvent();
        neither.ad_format = null;
        neither.event_name = "level_up";
        assertFalse(RiskJob.toRiskInput(neither).adReward);
    }

    @Test
    @DisplayName("inputMapper：合法事件收集，非法事件丢弃")
    void inputMapperCollectsAndDrops() throws Exception {
        List<RiskJob.RiskInput> out = new ArrayList<>();
        RiskJob.inputMapper().flatMap(fullEvent(), sinkTo(out));
        assertEquals(1, out.size());
        RiskJob.inputMapper().flatMap(new RawEvent(), sinkTo(out));   // 必填缺失丢弃
        assertEquals(1, out.size());
    }

    private static RawEvent fullEvent() {
        RawEvent r = new RawEvent();
        r.game_id = "g";
        r.environment = "prod";
        r.event_id = "e1";
        r.event_name = "ad_reward_claim";
        r.event_type = "economy";
        r.user_id = "u1";
        r.device_id = "d1";
        r.client_ip = "1.2.3.4";
        r.ts_server = 2_000_000L;
        r.resource_amount = 3.5;
        r.flow_type = "sink";
        r.receipt_hash = "rh1";
        r.revenue_amount = 9.99;
        r.ad_format = "rewarded";
        return r;
    }

    // ===== THRESHOLD =====

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

    // ===== 五个窗口算子直测 =====

    /** Flink 1.19 Collector.close() 非默认实现，需匿名类补齐。 */
    private static <T> Collector<T> sinkTo(List<T> out) {
        return new Collector<>() {
            @Override
            public void collect(T record) {
                out.add(record);
            }

            @Override
            public void close() {
            }
        };
    }

    private static RiskJob.RiskInput input(String eventId, String userId) {
        RiskJob.RiskInput in = new RiskJob.RiskInput();
        in.gameId = "g";
        in.environment = "prod";
        in.eventId = eventId;
        in.eventName = "n_" + eventId;
        in.userId = userId;
        in.deviceId = "d1";
        in.ts = new Timestamp(1_000L);
        return in;
    }

    private static RiskJob.RiskInput flowInput(String flowType, String amount) {
        RiskJob.RiskInput in = input(flowType, "u1");
        in.eventId = "e_" + flowType;
        in.eventName = "n_" + flowType;
        in.flowType = flowType;
        in.amount = new BigDecimal(amount);
        return in;
    }

    private static Map<String, RuleConfig.RuleSpec> rules(String type, int threshold, String ruleId) {
        return Map.of(type, new RuleConfig.RuleSpec(ruleId, type, threshold, "ALERT", 55, "MEDIUM"));
    }

    /** 临时覆盖全局规则快照，关闭时恢复为空（回落 DEFAULTS）。 */
    static final class RuleOverride implements AutoCloseable {
        private RuleOverride() {
        }

        static RuleOverride set(Map<String, RuleConfig.RuleSpec> m) {
            RuleConfig.update(new RuleConfig(m));
            return new RuleOverride();
        }

        @Override
        public void close() {
            RuleConfig.update(new RuleConfig(Map.of()));
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

    private static RiskJob.RuleSource deadRules() {
        return new RiskJob.RuleSource("http://127.0.0.1:1", "g", "", 999_999_999L);
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

    static Object ruleFetcherInstance() throws Exception {
        Field f = RuleFetcher.class.getDeclaredField("instance");
        f.setAccessible(true);
        return ((java.util.concurrent.atomic.AtomicReference<?>) f.get(null)).get();
    }

    /** 停掉并清空 RuleFetcher 单例（若存在）。 */
    static void stopRuleFetcher() throws Exception {
        Object inst = ruleFetcherInstance();
        if (inst instanceof RuleFetcher rf) rf.stop();
        Field f = RuleFetcher.class.getDeclaredField("instance");
        f.setAccessible(true);
        ((java.util.concurrent.atomic.AtomicReference<?>) f.get(null)).set(null);
    }

    static void resetRuleFetcher() throws Exception {
        stopRuleFetcher();
    }

    // ===== 序列化与写库绑定 =====

    @Test
    @DisplayName("toJson：全字段拼接 + reason/evidence 引号转义")
    void toJsonEscapes() {
        Map<String, String> ev = new LinkedHashMap<>();
        ev.put("k\"1", "v\"2");
        RiskJob.RiskHit h = new RiskJob.RiskHit(
                "g", "prod", new Timestamp(1_000L), "rid", "sid",
                "rule-1", "THRESHOLD", "HIGH", "PLAYER", "u1", 85f, "ALERT",
                "says \"hi\"", ev);
        String json = RiskJob.toJson(h);
        assertTrue(json.startsWith("{\"game_id\":\"g\",\"environment\":\"prod\""), json);
        assertTrue(json.contains("\"ts\":1000"));
        assertTrue(json.contains("\"risk_event_id\":\"rid\""));
        assertTrue(json.contains("\"source_event_id\":\"sid\""));
        assertTrue(json.contains("\"rule_id\":\"rule-1\""));
        assertTrue(json.contains("\"risk_type\":\"THRESHOLD\""));
        assertTrue(json.contains("\"severity\":\"HIGH\""));
        assertTrue(json.contains("\"subject_type\":\"PLAYER\""));
        assertTrue(json.contains("\"subject_id\":\"u1\""));
        assertTrue(json.contains("\"score\":85.0"));
        assertTrue(json.contains("\"action\":\"ALERT\""));
        assertTrue(json.contains("\"reason\":\"says \\\"hi\\\"\""));
        assertTrue(json.contains("\"evidence\":{\"k\"1\":\"v\\\"2\"}"));   // key 原样、value 转义（现状）
        assertTrue(json.endsWith("}}"));
    }

    @Test
    @DisplayName("bindRiskHit：14 个参数按序绑定")
    void bindRiskHitSetsAllParameters() throws Exception {
        Map<String, String> ev = Map.of("k", "v");
        RiskJob.RiskHit h = new RiskJob.RiskHit(
                "g", "prod", new Timestamp(1_000L), "rid", "sid",
                "rule-1", "THRESHOLD", "HIGH", "PLAYER", "u1", 85.5f, "ALERT",
                "reason", ev);

        Map<String, Object> calls = new LinkedHashMap<>();
        PreparedStatement ps = (PreparedStatement) Proxy.newProxyInstance(
                RiskJobTest.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
                (Object p, Method m, Object[] a) -> {
                    calls.put(m.getName() + ":" + a[0], a[1]);
                    return null;
                });
        RiskJob.bindRiskHit(ps, h);

        assertEquals("g", calls.get("setString:1"));
        assertEquals("prod", calls.get("setString:2"));
        assertEquals(new Timestamp(1_000L), calls.get("setTimestamp:3"));
        assertEquals("rid", calls.get("setString:4"));
        assertEquals("sid", calls.get("setString:5"));
        assertEquals("rule-1", calls.get("setString:6"));
        assertEquals("THRESHOLD", calls.get("setString:7"));
        assertEquals("HIGH", calls.get("setString:8"));
        assertEquals("PLAYER", calls.get("setString:9"));
        assertEquals("u1", calls.get("setString:10"));
        assertEquals(85.5f, calls.get("setFloat:11"));
        assertEquals("ALERT", calls.get("setString:12"));
        assertEquals("reason", calls.get("setString:13"));
        assertEquals(ev, calls.get("setObject:14"));
        assertEquals(14, calls.size());
    }

    // ===== 纯函数与实例化 =====

    @Test
    void strNzParseAmount() {
        assertNull(RiskJob.str(null));
        assertEquals("test", RiskJob.str("test"));
        assertEquals("", RiskJob.nz(null));
        assertEquals("test", RiskJob.nz("test"));

        assertNull(RiskJob.parseAmount(null));
        assertEquals(new BigDecimal("123.45"), RiskJob.parseAmount(123.45));
        assertEquals(new BigDecimal("123.45"), RiskJob.parseAmount("123.45"));
        assertNull(RiskJob.parseAmount("not_a_number"));
    }

    @Test
    void firstNonBlankPicksFirstPresent() {
        assertEquals("rh", RiskJob.firstNonBlank(null, " ", "rh", "order1"));
        assertEquals("order1", RiskJob.firstNonBlank(null, "", "order1"));
        assertNull(RiskJob.firstNonBlank(null, "", "  "));
    }

    @Test
    void adRewardFlagIdentified() {
        RiskJob.RiskInput rewarded = new RiskJob.RiskInput();
        rewarded.adReward = true;
        assertTrue(RiskJob.isAdReward(rewarded));

        RiskJob.RiskInput normal = new RiskJob.RiskInput();
        assertFalse(RiskJob.isAdReward(normal));
    }

    @Test
    void riskInputPojo() {
        RiskJob.RiskInput input = new RiskJob.RiskInput();
        input.gameId = "game_demo";
        input.environment = "prod";
        input.eventId = "event_123";
        input.eventName = "purchase";
        input.userId = "user_456";
        input.deviceId = "device_789";
        input.amount = new BigDecimal("99.99");

        assertEquals("game_demo", input.gameId);
        assertEquals("prod", input.environment);
        assertEquals("event_123", input.eventId);
        assertEquals("purchase", input.eventName);
        assertEquals("user_456", input.userId);
        assertEquals("device_789", input.deviceId);
        assertEquals(new BigDecimal("99.99"), input.amount);
    }

    @Test
    void riskHitPojo() {
        RiskJob.RiskHit hit = new RiskJob.RiskHit();
        hit.gameId = "game_demo";
        hit.environment = "prod";
        hit.subjectId = "user_456";
        hit.riskType = "amount_threshold";
        hit.score = 0.95f;
        hit.reason = "Amount exceeds threshold";

        assertEquals("game_demo", hit.gameId);
        assertEquals("prod", hit.environment);
        assertEquals("user_456", hit.subjectId);
        assertEquals("amount_threshold", hit.riskType);
        assertEquals(0.95f, hit.score, 0.0f);
        assertEquals("Amount exceeds threshold", hit.reason);
    }

    @Test
    void instances() {
        assertNotNull(new RiskJob());
        assertNotNull(new RiskJob.RiskInput());
        assertNotNull(new RiskJob.RiskHit());
        assertNotNull(deadRules());   // RuleSource 构造器
    }

    // ========== 规则快照（原用例保留） ==========

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
}
