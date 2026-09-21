package io.oddsmaker.jobs.identity;

import io.oddsmaker.jobs.enrich.RawEvent;
import org.apache.flink.api.common.eventtime.TimestampAssigner;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.streaming.api.TimerService;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("身份合并 job")
class IdentityMergeJobTest {

    // ===== 管道搭建（惰性，本地环境不 execute） =====

    @Test
    @DisplayName("buildPipeline：本地环境完成全管道搭建（source→filter→keyBy→process→双写）不抛异常")
    void buildPipelineWiresWholeGraphLazily() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.createLocalEnvironment(1);
        DataStream<IdentityMergeJob.IdentityRecord> tail = IdentityMergeJob.buildPipeline(env, IdentityMergeJob.config());
        assertNotNull(tail);
        assertTrue(env.getTransformations().size() >= 5, "expect >= 5 transformations, got " + env.getTransformations().size());
    }

    @Test
    @DisplayName("config：读 System properties，缺省回退默认值")
    void configReadsPropertiesWithDefaults() {
        String[] cfg = IdentityMergeJob.config();
        assertEquals("localhost:9092", cfg[0]);
        assertEquals("http://localhost:8081/apis/registry/v2", cfg[1]);
        assertEquals("oddsmaker.events_raw", cfg[2]);
        assertEquals("oddsmaker.identity_events", cfg[3]);
        assertEquals("jdbc:clickhouse://localhost:8123/default", cfg[4]);
        assertEquals("default", cfg[5]);
        assertEquals("", cfg[6]);
        assertEquals("oddsmaker-identity-merge", IdentityMergeJob.JOB_NAME);

        System.setProperty("identity.topic", "topic-x");
        System.setProperty("clickhouse.user", "ch");
        try {
            String[] c = IdentityMergeJob.config();
            assertEquals("topic-x", c[3]);
            assertEquals("ch", c[5]);
        } finally {
            System.clearProperty("identity.topic");
            System.clearProperty("clickhouse.user");
        }
    }

    // ===== 纯函数 =====

    @Test
    @DisplayName("watermarks/isIdentityEvent/identityKey/str/nz：构件直测")
    void helpers() throws Exception {
        WatermarkStrategy<RawEvent> wm = IdentityMergeJob.watermarks();
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

        RawEvent byType = new RawEvent();
        byType.event_type = "identity";
        RawEvent byName = new RawEvent();
        byName.event_name = "$identify";
        RawEvent neither = new RawEvent();
        neither.event_name = "level_start";
        assertTrue(IdentityMergeJob.isIdentityEvent(byType));
        assertTrue(IdentityMergeJob.isIdentityEvent(byName));
        assertFalse(IdentityMergeJob.isIdentityEvent(neither));
        assertFalse(IdentityMergeJob.isIdentityEvent(new RawEvent()));

        RawEvent keyed = new RawEvent();
        keyed.game_id = "g";
        keyed.environment = "prod";
        keyed.user_id = "u1";
        assertEquals("g|prod|u1", IdentityMergeJob.identityKey(keyed));
        assertEquals("||", IdentityMergeJob.identityKey(new RawEvent()));   // 缺省全空

        assertNull(IdentityMergeJob.str(null));
        assertEquals("7", IdentityMergeJob.str(7));
        assertEquals("", IdentityMergeJob.nz(null));
        assertEquals("v", IdentityMergeJob.nz("v"));

        assertNotNull(new IdentityMergeJob());   // 覆盖隐式构造器
        assertNotNull(new IdentityMergeJob.IdentityRecord());   // 集合字段默认初始化
        assertNotNull(new IdentityMergeJob.IdentityState());
    }

    @Test
    @DisplayName("extractPlayerId：顶层字段 → props_json 提取 → 无")
    void extractPlayerIdChain() {
        RawEvent top = new RawEvent();
        top.player_id = "p1";
        assertEquals("p1", IdentityMergeJob.extractPlayerId(top));

        RawEvent fromJson = new RawEvent();
        fromJson.props_json = "{\"player_id\":\"p2\",\"level\":3}";
        assertEquals("p2", IdentityMergeJob.extractPlayerId(fromJson));

        assertNull(IdentityMergeJob.extractPlayerId(new RawEvent()));
        RawEvent emptyTop = new RawEvent();
        emptyTop.player_id = "";
        assertNull(IdentityMergeJob.extractPlayerId(emptyTop));
    }

    @Test
    @DisplayName("extractTs：ts_server → ts_client → now 三级回退")
    void extractTsFallbacks() {
        RawEvent server = new RawEvent();
        server.ts_server = 2_000_000L;
        assertEquals(new Timestamp(2_000L), IdentityMergeJob.extractTs(server));

        RawEvent client = new RawEvent();
        client.ts_client = 3_000_000L;
        assertEquals(new Timestamp(3_000L), IdentityMergeJob.extractTs(client));

        long before = System.currentTimeMillis();
        Timestamp now = IdentityMergeJob.extractTs(new RawEvent());
        assertTrue(now.getTime() >= before && now.getTime() <= System.currentTimeMillis());
    }

    // ===== JSON 序列化 =====

    @Test
    @DisplayName("toJson/jsonArray/esc/joinList：转义与拼装")
    void jsonHelpers() {
        IdentityMergeJob.IdentityRecord r = new IdentityMergeJob.IdentityRecord();
        r.gameId = "g\"1";
        r.environment = "prod";
        r.identityId = "idt_x";
        r.userId = "u1";
        r.playerId = "p1";
        r.deviceIds = new LinkedHashSet<>(List.of("d1", "d2"));
        r.playerIds = new LinkedHashSet<>(List.of("p1"));
        r.characterIds = new LinkedHashSet<>();
        r.firstSeen = new Timestamp(1_000L);
        r.lastSeen = new Timestamp(62_000L);
        String json = IdentityMergeJob.toJson(r);
        assertTrue(json.contains("\"game_id\":\"g\\\"1\""));   // 引号转义
        assertTrue(json.contains("\"device_ids\":[\"d1\",\"d2\"]"));
        assertTrue(json.contains("\"player_ids\":[\"p1\"]"));
        assertTrue(json.contains("\"character_ids\":[]"));       // 空集合 → []
        assertTrue(json.contains("\"first_seen\":1000"));
        assertTrue(json.contains("\"last_seen\":62000"));

        assertEquals("[]", IdentityMergeJob.jsonArray(null));
        assertEquals("[]", IdentityMergeJob.jsonArray(new LinkedHashSet<>()));
        assertEquals("[\"a\\\\b\",\"c\\\"d\"]", IdentityMergeJob.jsonArray(new LinkedHashSet<>(List.of("a\\b", "c\"d"))));

        assertEquals("", IdentityMergeJob.esc(null));
        assertEquals("a\\\\b", IdentityMergeJob.esc("a\\b"));

        assertEquals("", IdentityMergeJob.joinList(null));
        assertEquals("", IdentityMergeJob.joinList(new LinkedHashSet<>()));
        assertEquals("a||b", IdentityMergeJob.joinList(new LinkedHashSet<>(List.of("a", "b"))));
    }

    // ===== JdbcSink binder =====

    @Test
    @DisplayName("bindIdentity：9 个参数按序绑定（集合 joinList 后写入）")
    void bindIdentitySetsAllParameters() throws Exception {
        IdentityMergeJob.IdentityRecord r = new IdentityMergeJob.IdentityRecord();
        r.gameId = "g";
        r.environment = "prod";
        r.identityId = "idt_abc";
        r.userId = "u1";
        r.playerId = "p1";
        r.characterIds = new LinkedHashSet<>(List.of("c1", "c2"));
        r.deviceIds = new LinkedHashSet<>(List.of("d1"));
        r.firstSeen = new Timestamp(1_000L);
        r.lastSeen = new Timestamp(2_000L);

        Map<String, Object> calls = new LinkedHashMap<>();
        PreparedStatement ps = (PreparedStatement) Proxy.newProxyInstance(
                IdentityMergeJobTest.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
                (Object p, Method m, Object[] a) -> {
                    calls.put(m.getName() + ":" + a[0], a[1]);
                    return null;
                });
        IdentityMergeJob.bindIdentity(ps, r);

        assertEquals("g", calls.get("setString:1"));
        assertEquals("prod", calls.get("setString:2"));
        assertEquals("idt_abc", calls.get("setString:3"));
        assertEquals("u1", calls.get("setString:4"));
        assertEquals("p1", calls.get("setString:5"));
        assertEquals("c1||c2", calls.get("setString:6"));
        assertEquals("d1", calls.get("setString:7"));
        assertEquals(new Timestamp(1_000L), calls.get("setTimestamp:8"));
        assertEquals(new Timestamp(2_000L), calls.get("setTimestamp:9"));
        assertEquals(9, calls.size());
    }

    // ===== IdentityMergeFunction 直测（内存 ValueState） =====

    static class TestValueState implements ValueState<IdentityMergeJob.IdentityState> {
        IdentityMergeJob.IdentityState v;

        @Override
        public IdentityMergeJob.IdentityState value() {
            return v;
        }

        @Override
        public void update(IdentityMergeJob.IdentityState value) {
            v = value;
        }

        @Override
        public void clear() {
            v = null;
        }
    }

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

    private static KeyedProcessFunction<String, RawEvent, IdentityMergeJob.IdentityRecord>.Context keyedContext(
            KeyedProcessFunction<String, RawEvent, IdentityMergeJob.IdentityRecord> fn) {
        return fn.new Context() {
            @Override
            public Long timestamp() {
                return null;
            }

            @Override
            public TimerService timerService() {
                return null;
            }

            @Override
            public <X> void output(OutputTag<X> outputTag, X value) {
            }

            @Override
            public String getCurrentKey() {
                return "k";
            }
        };
    }

    private static IdentityMergeJob.IdentityMergeFunction functionWithState(TestValueState state) throws Exception {
        IdentityMergeJob.IdentityMergeFunction fn = new IdentityMergeJob.IdentityMergeFunction();
        Field f = IdentityMergeJob.IdentityMergeFunction.class.getDeclaredField("state");
        f.setAccessible(true);
        f.set(fn, state);
        return fn;
    }

    private static RawEvent identifyEvent(String deviceId, String playerId, String characterId, long tsMs) {
        RawEvent r = new RawEvent();
        r.game_id = "g";
        r.environment = "prod";
        r.user_id = "u1";
        r.device_id = deviceId;
        r.player_id = playerId;
        r.character_id = characterId;
        r.event_name = "$identify";
        r.ts_server = tsMs * 1000L;
        return r;
    }

    @Test
    @DisplayName("IdentityMergeFunction：首事件建档、后续合并集合与时间、identityId 稳定")
    void mergesIdentityAcrossEvents() throws Exception {
        TestValueState state = new TestValueState();
        IdentityMergeJob.IdentityMergeFunction fn = functionWithState(state);
        List<IdentityMergeJob.IdentityRecord> out = new ArrayList<>();

        fn.processElement(identifyEvent("d1", "p1", "c1", 1_000L), keyedContext(fn), sinkTo(out));
        assertEquals(1, out.size());
        IdentityMergeJob.IdentityRecord first = out.get(0);
        assertEquals("g", first.gameId);
        assertEquals("u1", first.userId);
        assertEquals("p1", first.playerId);
        assertTrue(first.identityId.startsWith("idt_"));
        assertEquals(32, first.identityId.length());
        assertEquals(new Timestamp(1_000L), first.firstSeen);
        assertEquals(new Timestamp(1_000L), first.lastSeen);
        assertEquals(1, first.deviceIds.size());

        fn.processElement(identifyEvent("d2", "p2", null, 62_000L), keyedContext(fn), sinkTo(out));
        assertEquals(2, out.size());
        IdentityMergeJob.IdentityRecord second = out.get(1);
        assertEquals(first.identityId, second.identityId);   // 同键稳定
        assertEquals(2, second.deviceIds.size());
        assertEquals(2, second.playerIds.size());
        assertEquals(1, second.characterIds.size());         // null character 不加
        assertEquals(new Timestamp(1_000L), second.firstSeen);
        assertEquals(new Timestamp(62_000L), second.lastSeen);

        // 乱序更早事件：firstSeen 取更早值；lastSeen 现有实现为最近事件时间（无条件覆盖）
        fn.processElement(identifyEvent("d3", null, "c9", 500L), keyedContext(fn), sinkTo(out));
        IdentityMergeJob.IdentityRecord third = out.get(2);
        assertEquals(new Timestamp(500L), third.firstSeen);
        assertEquals(new Timestamp(500L), third.lastSeen);
        assertEquals(2, third.characterIds.size());
        assertEquals(3, third.deviceIds.size());
    }

    @Test
    @DisplayName("IdentityMergeFunction：缺 game/environment/user/device 的事件直接忽略")
    void skipsInvalidEvents() throws Exception {
        IdentityMergeJob.IdentityMergeFunction fn = functionWithState(new TestValueState());
        List<IdentityMergeJob.IdentityRecord> out = new ArrayList<>();

        fn.processElement(new RawEvent(), keyedContext(fn), sinkTo(out));   // 全空
        RawEvent noDevice = identifyEvent(null, "p1", null, 1L);
        fn.processElement(noDevice, keyedContext(fn), sinkTo(out));
        RawEvent noUser = identifyEvent("d1", null, null, 1L);
        noUser.user_id = null;
        fn.processElement(noUser, keyedContext(fn), sinkTo(out));
        RawEvent noGame = identifyEvent("d1", null, null, 1L);
        noGame.game_id = null;
        fn.processElement(noGame, keyedContext(fn), sinkTo(out));
        assertTrue(out.isEmpty());
    }

    @Test
    @DisplayName("IdentityMergeFunction：playerId 从 props_json 兜底提取并建档")
    void playerIdFallsBackToPropsJson() throws Exception {
        TestValueState state = new TestValueState();
        IdentityMergeJob.IdentityMergeFunction fn = functionWithState(state);
        List<IdentityMergeJob.IdentityRecord> out = new ArrayList<>();

        RawEvent r = identifyEvent("d1", null, null, 1_000L);
        r.props_json = "{\"player_id\":\"pJson\"}";
        fn.processElement(r, keyedContext(fn), sinkTo(out));
        assertEquals(1, out.size());
        assertEquals("pJson", out.get(0).playerId);
        assertTrue(state.v.playerIds.contains("pJson"));

        // 全程无 playerId：emit 的 playerId 为空串
        IdentityMergeJob.IdentityMergeFunction fnNoPlayer = functionWithState(new TestValueState());
        List<IdentityMergeJob.IdentityRecord> outNoPlayer = new ArrayList<>();
        fnNoPlayer.processElement(identifyEvent("d9", null, null, 1L), keyedContext(fnNoPlayer), sinkTo(outNoPlayer));
        assertEquals(1, outNoPlayer.size());
        assertEquals("", outNoPlayer.get(0).playerId);
    }

    /** Proxy RuntimeContext：getState 返回注入状态，其余按返回类型给默认值。 */
    private static org.apache.flink.api.common.functions.RuntimeContext runtimeContextWith(TestValueState state) {
        return (org.apache.flink.api.common.functions.RuntimeContext) Proxy.newProxyInstance(
                IdentityMergeJobTest.class.getClassLoader(),
                new Class<?>[]{org.apache.flink.api.common.functions.RuntimeContext.class},
                (Object p, Method m, Object[] a) -> {
                    if ("getState".equals(m.getName())) return state;
                    Class<?> rt = m.getReturnType();
                    if (rt == boolean.class) return Boolean.FALSE;
                    if (rt == int.class) return 0;
                    if (rt == long.class) return 0L;
                    if (rt == double.class) return 0d;
                    if (rt == float.class) return 0f;
                    if (rt == short.class) return (short) 0;
                    if (rt == byte.class) return (byte) 0;
                    if (rt == char.class) return (char) 0;
                    return null;
                });
    }

    @Test
    @DisplayName("open：经 RuntimeContext 声明并接线 ValueState")
    void openWiresStateViaRuntimeContext() throws Exception {
        IdentityMergeJob.IdentityMergeFunction fn = new IdentityMergeJob.IdentityMergeFunction();
        TestValueState state = new TestValueState();
        fn.setRuntimeContext(runtimeContextWith(state));
        fn.open(new org.apache.flink.configuration.Configuration());
        List<IdentityMergeJob.IdentityRecord> out = new ArrayList<>();
        fn.processElement(identifyEvent("d1", "p1", "c1", 1_000L), keyedContext(fn), sinkTo(out));
        assertEquals(1, out.size());
        assertNotNull(state.v);   // open 接线的状态可写
    }
    @Test
    @DisplayName("main：替身执行环境下完成入口（不触达真实集群）")
    void mainCompletesWithMockEnv() {
        try (org.mockito.MockedStatic<StreamExecutionEnvironment> mocked =
                 org.mockito.Mockito.mockStatic(StreamExecutionEnvironment.class)) {
            StreamExecutionEnvironment env = org.mockito.Mockito.mock(
                StreamExecutionEnvironment.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
            mocked.when(StreamExecutionEnvironment::getExecutionEnvironment).thenReturn(env);
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> IdentityMergeJob.main(new String[0]));
        }
    }

    // ===== 分支对侧补充（BRANCH 收口） =====

    @Test
    @DisplayName("isIdentityEvent：event_type 非空但非 identity 且无 $identify 名 → false")
    void isIdentityEventRejectsNonIdentityType() {
        RawEvent business = new RawEvent();
        business.event_type = "business";
        assertFalse(IdentityMergeJob.isIdentityEvent(business));
    }

    @Test
    @DisplayName("IdentityMergeFunction：environment 缺失的事件直接忽略")
    void mergeSkipsNullEnvironment() throws Exception {
        IdentityMergeJob.IdentityMergeFunction fn = functionWithState(new TestValueState());
        List<IdentityMergeJob.IdentityRecord> out = new ArrayList<>();
        RawEvent noEnv = identifyEvent("d1", "p1", "c1", 1L);
        noEnv.environment = null;
        fn.processElement(noEnv, keyedContext(fn), sinkTo(out));
        assertTrue(out.isEmpty());
    }

    @Test
    @DisplayName("首建分支：props 提取出空串 playerId 与空串 characterId 均不入集合")
    void firstBuildSkipsEmptyPlayerAndCharacterStrings() throws Exception {
        TestValueState state = new TestValueState();
        IdentityMergeJob.IdentityMergeFunction fn = functionWithState(state);
        List<IdentityMergeJob.IdentityRecord> out = new ArrayList<>();
        RawEvent r = identifyEvent("d1", null, "", 1_000L);   // character_id 空串
        r.props_json = "{\"player_id\":\"\"}";                 // 提取结果空串
        fn.processElement(r, keyedContext(fn), sinkTo(out));
        assertEquals(1, out.size());
        assertEquals("", out.get(0).playerId);
        assertTrue(state.v.playerIds.isEmpty());
        assertTrue(state.v.characterIds.isEmpty());
    }

    @Test
    @DisplayName("合并分支：后续事件携带空串 playerId/characterId 均不入集合")
    void elseBranchSkipsEmptyStrings() throws Exception {
        TestValueState state = new TestValueState();
        IdentityMergeJob.IdentityMergeFunction fn = functionWithState(state);
        List<IdentityMergeJob.IdentityRecord> out = new ArrayList<>();
        fn.processElement(identifyEvent("d1", "p1", "c1", 1_000L), keyedContext(fn), sinkTo(out));

        RawEvent second = identifyEvent("d2", null, "", 62_000L);   // characterId 空串
        second.props_json = "{\"player_id\":\"\"}";                 // playerId 提取空串
        fn.processElement(second, keyedContext(fn), sinkTo(out));
        assertEquals(2, out.size());
        assertEquals(1, out.get(1).playerIds.size());   // 仍只有 p1
        assertEquals(1, out.get(1).characterIds.size());   // 仍只有 c1
    }

    @Test
    @DisplayName("合并分支：既有状态 firstSeen 为 null（防御侧）时直接采用当前 ts")
    void nullFirstSeenBackfilledOnMerge() throws Exception {
        TestValueState state = new TestValueState();
        IdentityMergeJob.IdentityState s = new IdentityMergeJob.IdentityState();
        s.identityId = "idt_pre";
        s.firstSeen = null;   // 正常流程首建必置，此为防御侧
        s.lastSeen = new Timestamp(0L);
        state.v = s;
        IdentityMergeJob.IdentityMergeFunction fn = functionWithState(state);
        List<IdentityMergeJob.IdentityRecord> out = new ArrayList<>();
        fn.processElement(identifyEvent("d1", "p1", "c1", 1_000L), keyedContext(fn), sinkTo(out));
        assertEquals(1, out.size());
        assertEquals(new Timestamp(1_000L), out.get(0).firstSeen);   // null → 采用当前
        assertEquals(new Timestamp(1_000L), out.get(0).lastSeen);
    }

    @Test
    @DisplayName("extractPlayerId：props 无键 / 值无引号 / 值无闭合引号 均返回 null")
    void extractPlayerIdMalformedJson() {
        RawEvent noKey = new RawEvent();
        noKey.props_json = "{\"other\":\"v\"}";
        assertNull(IdentityMergeJob.extractPlayerId(noKey));

        RawEvent numeric = new RawEvent();
        numeric.props_json = "{\"player_id\":123}";   // 值非字符串：冒号后无引号
        assertNull(IdentityMergeJob.extractPlayerId(numeric));

        RawEvent truncated = new RawEvent();
        truncated.props_json = "{\"player_id\":\"x";   // 无闭合引号
        assertNull(IdentityMergeJob.extractPlayerId(truncated));
    }
}
