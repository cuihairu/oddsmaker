package io.oddsmaker.jobs.retention;

import io.oddsmaker.jobs.enrich.RawEvent;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.api.common.restartstrategy.RestartStrategies;
import org.apache.flink.api.common.eventtime.TimestampAssigner;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.state.MapState;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("留存聚合 job")
class RetentionJobTest {

    // ===== 管道搭建（惰性，本地环境不 execute） =====

    @Test
    @DisplayName("buildPipeline：本地环境完成全管道搭建（source→keyBy→process→双 JdbcSink）不抛异常")
    void buildPipelineWiresWholeGraphLazily() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.createLocalEnvironment(1);
        DataStream<RetentionJob.RetentionEmit> tail = RetentionJob.buildPipeline(env, RetentionJob.config());
        assertNotNull(tail);
        assertTrue(env.getTransformations().size() >= 4, "expect >= 4 transformations, got " + env.getTransformations().size());
        // 容错三件套生效:无 checkpoint 时 Kafka offset 从不提交,重启丢停机窗口数据(state 全空)
        assertTrue(env.getCheckpointConfig().isCheckpointingEnabled());
        assertEquals(30_000L, env.getCheckpointConfig().getCheckpointInterval());
        assertEquals(CheckpointingMode.AT_LEAST_ONCE, env.getCheckpointConfig().getCheckpointingMode());
        assertTrue(env.getRestartStrategy() instanceof RestartStrategies.FixedDelayRestartStrategyConfiguration,
            "重启策略应为固定延迟自愈");
    }

    @Test
    @DisplayName("config：读 System properties，缺省回退默认值")
    void configReadsPropertiesWithDefaults() {
        String[] cfg = RetentionJob.config();
        assertEquals("localhost:9092", cfg[0]);
        assertEquals("http://localhost:8081/apis/registry/v2", cfg[1]);
        assertEquals("oddsmaker.events_raw", cfg[2]);
        assertEquals("jdbc:clickhouse://localhost:8123/default", cfg[3]);
        assertEquals("default", cfg[4]);
        assertEquals("", cfg[5]);
        assertEquals("1,7,30", cfg[6]);
        assertEquals("1,3,7,14,30", cfg[7]);
        assertEquals("oddsmaker-retention", RetentionJob.JOB_NAME);

        System.setProperty("retention.ndays", "1,3");
        System.setProperty("kafka.bootstrap", "kafka-x:9094");
        try {
            String[] c = RetentionJob.config();
            assertEquals("1,3", c[6]);
            assertEquals("kafka-x:9094", c[0]);
        } finally {
            System.clearProperty("retention.ndays");
            System.clearProperty("kafka.bootstrap");
        }
    }

    // ===== 纯函数 =====

    @Test
    @DisplayName("watermarks/retentionKey/uidOf/isNDay/isRolling：构件直测")
    void helpers() throws Exception {
        WatermarkStrategy<RawEvent> wm = RetentionJob.watermarks();
        assertNotNull(wm.createWatermarkGenerator(null));
        TimestampAssigner<RawEvent> assigner = wm.createTimestampAssigner(null);
        RawEvent e = new RawEvent();
        e.ts_server = 9_000_000L;
        assertEquals(9_000L, assigner.extractTimestamp(e, 0L));
        RawEvent clientOnly = new RawEvent();
        clientOnly.ts_client = 7_000_000L;
        assertEquals(7_000L, assigner.extractTimestamp(clientOnly, 0L));   // ts_client 回退
        long before = System.currentTimeMillis();
        long nowMs = assigner.extractTimestamp(new RawEvent(), 0L);        // 均缺省 → now
        assertTrue(nowMs >= before && nowMs <= System.currentTimeMillis());

        RawEvent keyed = new RawEvent();
        keyed.game_id = "g";
        keyed.environment = "prod";
        keyed.user_id = "u1";
        assertEquals("g|prod|u1", RetentionJob.retentionKey(keyed));
        keyed.user_id = null;
        keyed.device_id = "d1";
        assertEquals("g|prod|d1", RetentionJob.retentionKey(keyed));   // uidOf 回退 device_id
        assertEquals("d1", RetentionJob.uidOf(keyed));

        RetentionJob.RetentionEmit nDay = new RetentionJob.RetentionEmit();
        nDay.rolling = 0;
        RetentionJob.RetentionEmit rolling = new RetentionJob.RetentionEmit();
        rolling.rolling = 3;
        assertTrue(RetentionJob.isNDay(nDay));
        assertFalse(RetentionJob.isNDay(rolling));
        assertTrue(RetentionJob.isRolling(rolling));
        assertFalse(RetentionJob.isRolling(nDay));

        assertNotNull(new RetentionJob());   // 覆盖隐式构造器
    }

    // ===== JdbcSink binder =====

    @Test
    @DisplayName("bindRetention：5 个参数按序绑定（N-Day/Rolling 同构）")
    void bindRetentionSetsAllParameters() throws Exception {
        RetentionJob.RetentionEmit row = new RetentionJob.RetentionEmit();
        row.gameId = "g";
        row.environment = "prod";
        row.cohortEpochDay = 20_000L;
        row.d = 7;
        row.rolling = 0;

        Map<String, Object> calls = new LinkedHashMap<>();
        PreparedStatement ps = (PreparedStatement) Proxy.newProxyInstance(
                RetentionJobTest.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
                (Object p, Method m, Object[] a) -> {
                    calls.put(m.getName() + ":" + a[0], a[1]);
                    return null;
                });
        RetentionJob.bindRetention(ps, row);

        assertEquals("g", calls.get("setString:1"));
        assertEquals("prod", calls.get("setString:2"));
        assertNotNull(calls.get("setDate:3"));
        assertEquals(7, calls.get("setInt:4"));
        assertEquals(1L, calls.get("setLong:5"));
        assertEquals(5, calls.size());
    }

    // ===== RetentionProcess 直测（内存 MapState） =====

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

    private static KeyedProcessFunction<String, RawEvent, RetentionJob.RetentionEmit>.Context keyedContext(
            KeyedProcessFunction<String, RawEvent, RetentionJob.RetentionEmit> fn) {
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

    private static RetentionJob.RetentionProcess processWithState(TestMapState state) throws Exception {
        RetentionJob.RetentionProcess fn = new RetentionJob.RetentionProcess(
                new RetentionPolicy(new int[]{1, 7}, new int[]{1, 3}));
        Field f = RetentionJob.RetentionProcess.class.getDeclaredField("state");
        f.setAccessible(true);
        f.set(fn, state);
        return fn;
    }

    /** day 偏移（相对 epoch）事件：ts_server 微秒级。 */
    private static RawEvent event(long dayOffset) {
        RawEvent r = new RawEvent();
        r.game_id = "g";
        r.environment = "prod";
        r.user_id = "u1";
        r.ts_server = dayOffset * 86_400_000L * 1000L;
        return r;
    }

    @Test
    @DisplayName("RetentionProcess：首日 cohort、N-Day 恰好命中、Rolling 跨阈值补记、去重与乱序回退")
    void retentionLifecycle() throws Exception {
        TestMapState state = new TestMapState();
        RetentionJob.RetentionProcess fn = processWithState(state);
        List<RetentionJob.RetentionEmit> out = new ArrayList<>();

        fn.processElement(event(0L), keyedContext(fn), sinkTo(out));   // 首事件 → cohort day0, d=0
        assertEquals(1, out.size());
        assertEquals(0, out.get(0).d);
        assertEquals(0, out.get(0).rolling);
        assertEquals(0L, out.get(0).cohortEpochDay);

        fn.processElement(event(1L), keyedContext(fn), sinkTo(out));   // day1：N-Day 1 + Rolling 1
        assertEquals(3, out.size());
        assertEquals(1, out.get(1).d);
        assertEquals(0, out.get(1).rolling);
        assertEquals(1, out.get(2).rolling);

        fn.processElement(event(1L), keyedContext(fn), sinkTo(out));   // 同日重复 → 均已 seen，无输出
        assertEquals(3, out.size());

        fn.processElement(event(4L), keyedContext(fn), sinkTo(out));   // day4：跨越 rolling 3 → 补记；N-Day 未命中
        assertEquals(4, out.size());
        assertEquals(3, out.get(3).rolling);
        assertEquals(3, out.get(3).d);

        fn.processElement(event(7L), keyedContext(fn), sinkTo(out));   // day7：N-Day 7；rolling {1,3} 已全部 seen
        assertEquals(5, out.size());
        assertEquals(7, out.get(4).d);
        assertEquals(0, out.get(4).rolling);

        fn.processElement(event(2L), keyedContext(fn), sinkTo(out));   // 乱序回退（2 < last=7）→ 无输出
        assertEquals(5, out.size());
        assertEquals(Long.valueOf(7L), state.m.get("last"));   // last 不回退
    }

    @Test
    @DisplayName("RetentionProcess：事件时间戳回退 ts_client → now")
    void timestampFallsBackToClientThenNow() throws Exception {
        List<RetentionJob.RetentionEmit> out = new ArrayList<>();

        RawEvent clientOnly = event(0L);
        clientOnly.ts_server = null;
        clientOnly.ts_client = 86_400_000L * 1000L;   // day1（微秒）
        RetentionJob.RetentionProcess fn = processWithState(new TestMapState());
        fn.processElement(clientOnly, keyedContext(fn), sinkTo(out));
        assertEquals(1, out.size());
        assertEquals(1L, out.get(0).cohortEpochDay);

        RawEvent noTs = event(0L);
        noTs.ts_server = null;
        noTs.ts_client = null;
        long before = System.currentTimeMillis();
        RetentionJob.RetentionProcess fnNow = processWithState(new TestMapState());
        fnNow.processElement(noTs, keyedContext(fnNow), sinkTo(out));
        assertEquals(2, out.size());
        assertTrue(out.get(1).cohortEpochDay >= before / 86_400_000L);
    }

    /** Proxy RuntimeContext：getMapState 返回注入状态，其余按返回类型给默认值。 */
    private static org.apache.flink.api.common.functions.RuntimeContext runtimeContextWith(TestMapState state) {
        return (org.apache.flink.api.common.functions.RuntimeContext) Proxy.newProxyInstance(
                RetentionJobTest.class.getClassLoader(),
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
    @DisplayName("RetentionProcess.open：经 RuntimeContext 声明并接线 MapState")
    void openWiresStateViaRuntimeContext() throws Exception {
        RetentionJob.RetentionProcess fn = new RetentionJob.RetentionProcess(
            new RetentionPolicy(new int[]{1, 7, 30}, new int[]{1, 3, 7, 14, 30}));
        TestMapState state = new TestMapState();
        fn.setRuntimeContext(runtimeContextWith(state));
        fn.open(new org.apache.flink.configuration.Configuration());
        List<RetentionJob.RetentionEmit> out = new ArrayList<>();
        fn.processElement(event(0L), keyedContext(fn), sinkTo(out));   // open 接线的状态可写
        assertEquals(1, out.size());
        assertEquals(2, state.m.size());   // first + last
        assertNull(state.m.get("seen_d_1"));
    }

    // ===== 覆盖缺口补充 =====

    @Test
    @DisplayName("main：替身执行环境下完成入口（不触达真实集群）")
    void mainCompletesWithMockEnv() {
        try (org.mockito.MockedStatic<org.apache.flink.streaming.api.environment.StreamExecutionEnvironment> mocked =
                 org.mockito.Mockito.mockStatic(org.apache.flink.streaming.api.environment.StreamExecutionEnvironment.class)) {
            org.apache.flink.streaming.api.environment.StreamExecutionEnvironment env = org.mockito.Mockito.mock(
                org.apache.flink.streaming.api.environment.StreamExecutionEnvironment.class,
                org.mockito.Mockito.RETURNS_DEEP_STUBS);
            mocked.when(org.apache.flink.streaming.api.environment.StreamExecutionEnvironment::getExecutionEnvironment)
                .thenReturn(env);
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> RetentionJob.main(new String[0]));
        }
    }

    // ===== 分支对侧补充（BRANCH 收口） =====

    @Test
    @DisplayName("uidOf：user_id 非空空串回退 device_id（null 与非空已盖）")
    void uidOfFallsBackOnEmptyUserId() {
        RawEvent r = new RawEvent();
        r.user_id = "";
        r.device_id = "d1";
        assertEquals("d1", RetentionJob.uidOf(r));
    }

    @Test
    @DisplayName("RetentionProcess：last 缺失（防御侧）时 prevLast 回退 first")
    void nullLastFallsBackToFirst() throws Exception {
        TestMapState state = new TestMapState();
        state.m.put("first", 5L);   // 正常流程首建必同时置 last，此为防御侧
        RetentionJob.RetentionProcess fn = processWithState(state);
        List<RetentionJob.RetentionEmit> out = new ArrayList<>();
        fn.processElement(event(6L), keyedContext(fn), sinkTo(out));   // prevLast=first=5
        assertEquals(2, out.size());
        assertEquals(1, out.get(0).d);          // N-Day 1（6-5=1）
        assertEquals(0, out.get(0).rolling);
        assertEquals(5L, out.get(0).cohortEpochDay);
        assertEquals(1, out.get(1).rolling);    // rolling 1 跨越
        assertEquals(Long.valueOf(6L), state.m.get("last"));
    }

    @Test
    @DisplayName("RetentionProcess：rolling 阈值已 seen 时跨过不再补记")
    void alreadySeenRollingThresholdNotReEmitted() throws Exception {
        TestMapState state = new TestMapState();
        state.m.put("first", 0L);
        state.m.put("last", 0L);
        state.m.put("seen_r_1", 1L);   // rolling 1 已记过
        RetentionJob.RetentionProcess fn = processWithState(state);
        List<RetentionJob.RetentionEmit> out = new ArrayList<>();
        fn.processElement(event(1L), keyedContext(fn), sinkTo(out));   // 跨 rolling 1 但已 seen
        assertEquals(1, out.size());   // 仅 N-Day 1
        assertEquals(1, out.get(0).d);
        assertEquals(0, out.get(0).rolling);
        assertEquals(Long.valueOf(1L), state.m.get("last"));   // last 仍推进
    }
}
