package io.oddsmaker.jobs.funnels;

import io.oddsmaker.jobs.enrich.RawEvent;
import org.apache.flink.api.common.eventtime.TimestampAssigner;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.state.MapState;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("固定两步漏斗 job")
class FunnelsJobTest {

    // ===== 管道搭建（惰性，本地环境不 execute） =====

    @Test
    @DisplayName("buildPipeline：本地环境完成全管道搭建（source→filter→keyBy→process→JdbcSink）不抛异常")
    void buildPipelineWiresWholeGraphLazily() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.createLocalEnvironment(1);
        org.apache.flink.streaming.api.datastream.DataStream<FunnelsJob.FunnelRow> tail = FunnelsJob.buildPipeline(env, FunnelsJob.config());
        assertNotNull(tail);
        assertTrue(env.getTransformations().size() >= 3, "expect >= 3 transformations, got " + env.getTransformations().size());
    }

    @Test
    @DisplayName("config：读 System properties，缺省回退默认值")
    void configReadsPropertiesWithDefaults() {
        String[] cfg = FunnelsJob.config();
        assertEquals("localhost:9092", cfg[0]);
        assertEquals("oddsmaker.events_raw", cfg[2]);
        assertEquals("level_start", cfg[6]);
        assertEquals("level_complete", cfg[7]);
        assertEquals("86400000", cfg[8]);
        assertEquals("oddsmaker-funnels-2step", FunnelsJob.JOB_NAME);

        System.setProperty("funnel.step1", "register");
        System.setProperty("funnel.step2", "first_pay");
        System.setProperty("funnel.timeout.ms", "3600000");
        try {
            String[] c = FunnelsJob.config();
            assertEquals("register", c[6]);
            assertEquals("first_pay", c[7]);
            assertEquals("3600000", c[8]);
        } finally {
            System.clearProperty("funnel.step1");
            System.clearProperty("funnel.step2");
            System.clearProperty("funnel.timeout.ms");
        }
    }

    // ===== 纯函数 =====

    @Test
    @DisplayName("watermarks/isStepEvent/funnelKey/uidOf：水位线直调 + 步骤过滤 + 分组键")
    void helpers() throws Exception {
        WatermarkStrategy<RawEvent> wm = FunnelsJob.watermarks();
        assertNotNull(wm.createWatermarkGenerator(null));
        TimestampAssigner<RawEvent> assigner = wm.createTimestampAssigner(null);
        RawEvent e = new RawEvent();
        e.ts_server = 5_000_000L;
        assertEquals(5_000L, assigner.extractTimestamp(e, 0L));
        RawEvent clientOnly = new RawEvent();
        clientOnly.ts_client = 7_000_000L;
        assertEquals(7_000L, assigner.extractTimestamp(clientOnly, 0L));

        RawEvent hit1 = new RawEvent();
        hit1.event_name = "level_start";
        RawEvent hit2 = new RawEvent();
        hit2.event_name = "level_complete";
        RawEvent miss = new RawEvent();
        miss.event_name = "shop_open";
        assertTrue(FunnelsJob.isStepEvent(hit1, "level_start", "level_complete"));
        assertTrue(FunnelsJob.isStepEvent(hit2, "level_start", "level_complete"));
        assertFalse(FunnelsJob.isStepEvent(miss, "level_start", "level_complete"));
        assertFalse(FunnelsJob.isStepEvent(new RawEvent(), "level_start", "level_complete"));

        // 过滤算子工厂：lambda 体直调
        org.apache.flink.api.common.functions.FilterFunction<RawEvent> f = FunnelsJob.stepFilter("level_start", "level_complete");
        assertTrue(f.filter(hit1));
        assertFalse(f.filter(miss));

        // 时间戳均缺省 → now 回退
        long before = System.currentTimeMillis();
        assertTrue(assigner.extractTimestamp(new RawEvent(), 0L) >= before);
        assertTrue(assigner.extractTimestamp(new RawEvent(), 0L) <= System.currentTimeMillis());

        RawEvent keyed = new RawEvent();
        keyed.game_id = "g";
        keyed.environment = "prod";
        keyed.user_id = "u1";
        assertEquals("g|prod|u1", FunnelsJob.funnelKey(keyed));
        keyed.user_id = null;
        keyed.device_id = "d1";
        assertEquals("g|prod|d1", FunnelsJob.funnelKey(keyed));
        assertEquals("d1", FunnelsJob.uidOf(keyed));
    }

    // ===== JdbcSink binder =====

    @Test
    @DisplayName("bindFunnelRow：7 个参数按序绑定（step 名取管道配置）")
    void bindFunnelRowSetsAllParameters() throws Exception {
        FunnelsJob.FunnelRow row = new FunnelsJob.FunnelRow();
        row.gameId = "g";
        row.environment = "prod";
        row.eventDateEpochDay = 20_000L;
        row.started = 1;
        row.completed = 0;

        Map<String, Object> calls = new LinkedHashMap<>();
        InvocationHandler h = (Object proxy, Method method, Object[] a) -> {
            calls.put(method.getName() + ":" + a[0], a[1]);
            return null;
        };
        PreparedStatement ps = (PreparedStatement) Proxy.newProxyInstance(
                FunnelsJobTest.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, h);
        FunnelsJob.bindFunnelRow(ps, row, "level_start", "level_complete");

        assertEquals("g", calls.get("setString:1"));
        assertEquals("prod", calls.get("setString:2"));
        assertNotNull(calls.get("setDate:3"));
        assertEquals("level_start", calls.get("setString:4"));
        assertEquals("level_complete", calls.get("setString:5"));
        assertEquals(1L, calls.get("setLong:6"));
        assertEquals(0L, calls.get("setLong:7"));
        assertEquals(7, calls.size());

        // binder 工厂：lambda 体直调
        Map<String, Object> viaBinder = new LinkedHashMap<>();
        PreparedStatement binderPs = (PreparedStatement) Proxy.newProxyInstance(
                FunnelsJobTest.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
                (Object p, Method m, Object[] a) -> {
                    viaBinder.put(m.getName() + ":" + a[0], a[1]);
                    return null;
                });
        FunnelsJob.rowBinder("s1", "s2").accept(binderPs, row);
        assertEquals("s1", viaBinder.get("setString:4"));
        assertEquals("s2", viaBinder.get("setString:5"));
        assertEquals(7, viaBinder.size());
    }

    // ===== FunnelProcess 直测（内存 MapState） =====

    /** Flink 1.19 MapState 的内存实现。 */
    static class TestMapState implements MapState<String, Long> {
        final HashMap<String, Long> m = new HashMap<>();

        @Override
        public Long get(String key) {
            return m.get(key);
        }

        @Override
        public void put(String key, Long value) {
            m.put(key, value);
        }

        @Override
        public void putAll(Map<String, Long> map) {
            m.putAll(map);
        }

        @Override
        public void remove(String key) {
            m.remove(key);
        }

        @Override
        public boolean contains(String key) {
            return m.containsKey(key);
        }

        @Override
        public Iterable<Map.Entry<String, Long>> entries() {
            return m.entrySet();
        }

        @Override
        public Iterable<String> keys() {
            return m.keySet();
        }

        @Override
        public Iterable<Long> values() {
            return m.values();
        }

        @Override
        public java.util.Iterator<Map.Entry<String, Long>> iterator() {
            return m.entrySet().iterator();
        }

        @Override
        public boolean isEmpty() {
            return m.isEmpty();
        }

        @Override
        public void clear() {
            m.clear();
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

    private static KeyedProcessFunction<String, RawEvent, FunnelsJob.FunnelRow>.Context keyedContext(
            KeyedProcessFunction<String, RawEvent, FunnelsJob.FunnelRow> fn) {
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

    private static FunnelsJob.FunnelProcess processWithState(TestMapState state) throws Exception {
        FunnelsJob.FunnelProcess fn = new FunnelsJob.FunnelProcess("step1", "step2", 1_000L);
        Field f = FunnelsJob.FunnelProcess.class.getDeclaredField("state");
        f.setAccessible(true);
        f.set(fn, state);
        return fn;
    }

    private static RawEvent event(String name, long tsMs) {
        RawEvent r = new RawEvent();
        r.game_id = "g";
        r.environment = "prod";
        r.event_name = name;
        r.ts_server = tsMs * 1000L;
        return r;
    }

    @Test
    @DisplayName("FunnelProcess：step1 首次计 started、同日重复不计；窗口内 step2 计 completed、重复不计")
    void sequentialFunnelHappyPath() throws Exception {
        TestMapState state = new TestMapState();
        FunnelsJob.FunnelProcess fn = processWithState(state);
        List<FunnelsJob.FunnelRow> out = new ArrayList<>();

        fn.processElement(event("step1", 1_000L), keyedContext(fn), sinkTo(out));
        assertEquals(1, out.size());
        assertEquals(1, out.get(0).started);
        assertEquals(0, out.get(0).completed);

        fn.processElement(event("step1", 1_100L), keyedContext(fn), sinkTo(out));   // 同日重复 step1 不计
        assertEquals(1, out.size());

        fn.processElement(event("step2", 1_500L), keyedContext(fn), sinkTo(out));   // 窗口内(500ms <= 1000ms)
        assertEquals(2, out.size());
        assertEquals(0, out.get(1).started);
        assertEquals(1, out.get(1).completed);

        fn.processElement(event("step2", 1_600L), keyedContext(fn), sinkTo(out));   // 同日重复 completed 不计
        assertEquals(2, out.size());
        assertEquals(3, state.m.size());   // started_day_0 + last_step1_ts + completed_day_0
    }

    @Test
    @DisplayName("FunnelProcess：step2 超窗/无前置 step1 均不计；跨日再次 step1 计新 started")
    void sequentialFunnelMisses() throws Exception {
        TestMapState state = new TestMapState();
        FunnelsJob.FunnelProcess fn = processWithState(state);
        List<FunnelsJob.FunnelRow> out = new ArrayList<>();

        fn.processElement(event("step2", 1_000L), keyedContext(fn), sinkTo(out));   // 无 last_step1_ts
        assertTrue(out.isEmpty());

        fn.processElement(event("step1", 1_000L), keyedContext(fn), sinkTo(out));
        fn.processElement(event("step2", 5_000L), keyedContext(fn), sinkTo(out));   // 超窗(4000 > 1000)
        assertEquals(1, out.size());

        // 跨日 step1 → 新的 started_day key → 计数
        fn.processElement(event("step1", 86_400_000L + 2_000L), keyedContext(fn), sinkTo(out));
        assertEquals(2, out.size());
    }

    @Test
    @DisplayName("FunnelProcess：非步骤事件直接忽略")
    void nonStepEventIgnored() throws Exception {
        FunnelsJob.FunnelProcess fn = processWithState(new TestMapState());
        List<FunnelsJob.FunnelRow> out = new ArrayList<>();
        RawEvent r = event("other", 1L);
        fn.processElement(r, keyedContext(fn), sinkTo(out));
        assertTrue(out.isEmpty());
        assertNotNull(new FunnelsJob());
    }

    /** Proxy RuntimeContext：getMapState 返回注入状态，其余按返回类型给默认值。 */
    private static org.apache.flink.api.common.functions.RuntimeContext runtimeContextWith(TestMapState state) {
        return (org.apache.flink.api.common.functions.RuntimeContext) Proxy.newProxyInstance(
                FunnelsJobTest.class.getClassLoader(),
                new Class<?>[]{org.apache.flink.api.common.functions.RuntimeContext.class},
                (Object p, Method m, Object[] a) -> {
                    if ("getMapState".equals(m.getName())) return state;
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
    @DisplayName("FunnelProcess.open：经 RuntimeContext 声明并接线 MapState")
    void openWiresStateViaRuntimeContext() throws Exception {
        FunnelsJob.FunnelProcess fn = new FunnelsJob.FunnelProcess("step1", "step2", 60_000L);
        TestMapState state = new TestMapState();
        fn.setRuntimeContext(runtimeContextWith(state));
        fn.open(new org.apache.flink.configuration.Configuration());
        List<FunnelsJob.FunnelRow> out = new ArrayList<>();
        fn.processElement(event("step1", 1_000L), keyedContext(fn), sinkTo(out));   // open 接线的状态可写
        assertEquals(1, out.size());
        assertEquals(2, state.m.size());   // started_day_1 + last_step1_ts
    }

    @Test
    @DisplayName("FunnelProcess：事件时间戳回退 ts_client → now")
    void timestampFallsBackToClientThenNow() throws Exception {
        List<FunnelsJob.FunnelRow> out = new ArrayList<>();

        RawEvent clientOnly = event("step1", 0L);
        clientOnly.ts_server = null;
        clientOnly.ts_client = 3_000_000L;   // 3s（毫秒）
        FunnelsJob.FunnelProcess fn = processWithState(new TestMapState());
        fn.processElement(clientOnly, keyedContext(fn), sinkTo(out));
        assertEquals(1, out.size());
        assertEquals(0L, out.get(0).eventDateEpochDay);   // 3000ms → day 0

        RawEvent noTs = event("step1", 0L);
        noTs.ts_server = null;
        noTs.ts_client = null;
        long before = System.currentTimeMillis();
        FunnelsJob.FunnelProcess fnNow = processWithState(new TestMapState());
        fnNow.processElement(noTs, keyedContext(fnNow), sinkTo(out));
        assertEquals(2, out.size());
        assertTrue(out.get(1).eventDateEpochDay >= before / 86_400_000L);   // now 所在日
    }

    // ===== 覆盖缺口补充 =====

    @Test
    @DisplayName("main：替身执行环境下完成入口（不触达真实集群）")
    void mainCompletesWithMockEnv() {
        try (org.mockito.MockedStatic<StreamExecutionEnvironment> mocked =
                 org.mockito.Mockito.mockStatic(StreamExecutionEnvironment.class)) {
            StreamExecutionEnvironment env = org.mockito.Mockito.mock(
                StreamExecutionEnvironment.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
            mocked.when(StreamExecutionEnvironment::getExecutionEnvironment).thenReturn(env);
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> FunnelsJob.main(new String[0]));
        }
    }
}
