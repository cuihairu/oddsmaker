package io.oddsmaker.jobs.sessions;

import io.oddsmaker.jobs.enrich.RawEvent;
import org.apache.flink.api.common.state.KeyedStateStore;
import org.apache.flink.api.common.eventtime.TimestampAssigner;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.java.tuple.Tuple3;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("session 聚合 job")
class SessionsJobTest {

    // ===== 管道搭建（惰性，本地环境不 execute） =====

    @Test
    @DisplayName("buildPipeline：本地环境完成全管道搭建（source→map→session窗口→JdbcSink）不抛异常")
    void buildPipelineWiresWholeGraphLazily() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.createLocalEnvironment(1);
        org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator<SessionsJob.SessionRow> tail = SessionsJob.buildPipeline(env, SessionsJob.config());
        assertNotNull(tail);
        assertTrue(env.getTransformations().size() >= 3, "expect >= 3 transformations, got " + env.getTransformations().size());
    }

    @Test
    @DisplayName("config：读 System properties，缺省回退默认值")
    void configReadsPropertiesWithDefaults() {
        String[] defaults = SessionsJob.config();
        assertEquals("localhost:9092", defaults[0]);
        assertEquals("http://localhost:8081/apis/registry/v2", defaults[1]);
        assertEquals("oddsmaker.events_raw", defaults[2]);
        assertEquals("jdbc:clickhouse://localhost:8123/default", defaults[3]);
        assertEquals("default", defaults[4]);
        assertEquals("", defaults[5]);
        assertEquals("30", defaults[6]);
        assertEquals("10", defaults[7]);
        assertEquals("oddsmaker-sessions", SessionsJob.JOB_NAME);

        System.setProperty("session.gap.minutes", "5");
        System.setProperty("watermark.ooo.minutes", "0");
        System.setProperty("kafka.bootstrap", "kafka-x:9094");
        try {
            String[] cfg = SessionsJob.config();
            assertEquals("5", cfg[6]);
            assertEquals("0", cfg[7]);
            assertEquals("kafka-x:9094", cfg[0]);
        } finally {
            System.clearProperty("session.gap.minutes");
            System.clearProperty("watermark.ooo.minutes");
            System.clearProperty("kafka.bootstrap");
        }
    }

    // ===== 辅助构件 =====

    @Test
    @DisplayName("watermarks/toLite/str/nz：水位线 assigner 直调 + 字段映射与时间回退")
    void helpersAndToLite() {
        WatermarkStrategy<SessionsJob.EventLite> wm = SessionsJob.watermarks(10);
        assertNotNull(wm.createWatermarkGenerator(null));
        TimestampAssigner<SessionsJob.EventLite> assigner = wm.createTimestampAssigner(null);
        SessionsJob.EventLite lite = new SessionsJob.EventLite();
        lite.eventTimeMs = 1_715_000_000_000L;
        assertEquals(1_715_000_000_000L, assigner.extractTimestamp(lite, 0L));

        RawEvent r = new RawEvent();
        r.game_id = "g";
        r.environment = "prod";
        r.user_id = "u1";
        r.device_id = "d1";
        r.country = null;
        r.ts_server = 2_000_000_000L;
        r.ts_client = 1_000_000_000L;
        SessionsJob.EventLite mapped = SessionsJob.toLite(r);
        assertEquals("g", mapped.gameId);
        assertEquals("u1", mapped.userOrDeviceId());
        assertEquals("", mapped.country);
        assertEquals(2_000_000L, mapped.eventTimeMs);   // ts_server 优先

        RawEvent noTs = new RawEvent();
        noTs.ts_client = 3_000_000_000L;
        assertEquals(3_000_000L, SessionsJob.toLite(noTs).eventTimeMs);   // 回退 ts_client

        long before = System.currentTimeMillis();
        long got = SessionsJob.toLite(new RawEvent()).eventTimeMs;        // 均缺省 → now
        assertTrue(got >= before && got <= System.currentTimeMillis());

        assertEquals("x", SessionsJob.str("x"));
        org.junit.jupiter.api.Assertions.assertNull(SessionsJob.str(null));
        assertEquals("", SessionsJob.nz(null));
        assertEquals("v", SessionsJob.nz("v"));
        // user_id 缺失时回退 deviceId
        RawEvent anon = new RawEvent();
        anon.device_id = "dev9";
        assertEquals("dev9", SessionsJob.toLite(anon).userOrDeviceId());
    }

    @Test
    @DisplayName("deterministicId：SHA-1 截断 24 hex 大写、同输入恒定")
    void deterministicIdStable() {
        String id = SessionsJob.deterministicId("g|prod|u1|1715000000000");
        assertEquals(24, id.length());
        assertTrue(id.matches("[0-9A-F]{24}"));
        assertEquals(id, SessionsJob.deterministicId("g|prod|u1|1715000000000"));
        assertFalse(id.equals(SessionsJob.deterministicId("g|prod|u1|1715000000001")));
    }

    @Test
    @DisplayName("sessionKey/resolveUserId/resolveDeviceId：分组键与首事件字段归位全分支")
    void keyAndResolveHelpers() {
        SessionsJob.EventLite e = new SessionsJob.EventLite();
        e.gameId = "g";
        e.environment = "prod";
        e.userId = "u1";
        e.deviceId = "d1";
        Tuple3<String, String, String> key = SessionsJob.sessionKey(e);
        assertEquals("g", key.f0);
        assertEquals("prod", key.f1);
        assertEquals("u1", key.f2);

        assertEquals("u1", SessionsJob.resolveUserId("u1", "u1"));    // key 即 userId
        assertEquals("u9", SessionsJob.resolveUserId("d1", "u9"));    // key 是 device，窗口首事件有 userId
        assertEquals("", SessionsJob.resolveUserId("d1", null));      // 窗口首事件无 userId
        assertEquals("", SessionsJob.resolveDeviceId(null));
        assertEquals("d1", SessionsJob.resolveDeviceId("d1"));
    }

    // ===== 窗口聚合函数直测 =====

    /** ProcessWindowFunction.Context 是非静态内部类，须以 fn.new Context(){} 限定语法创建。 */
    private static ProcessWindowFunction<SessionsJob.EventLite, SessionsJob.SessionRow, Tuple3<String, String, String>, TimeWindow>.Context windowContext(
            ProcessWindowFunction<SessionsJob.EventLite, SessionsJob.SessionRow, Tuple3<String, String, String>, TimeWindow> fn) {
        return fn.new Context() {
            @Override
            public TimeWindow window() {
                return new TimeWindow(0, 1);
            }

            @Override
            public long currentProcessingTime() {
                return 0L;
            }

            @Override
            public long currentWatermark() {
                return Long.MAX_VALUE;
            }

            @Override
            public KeyedStateStore windowState() {
                return null;
            }

            @Override
            public KeyedStateStore globalState() {
                return null;
            }

            @Override
            public <X> void output(OutputTag<X> outputTag, X value) {
            }
        };
    }

    private static SessionsJob.EventLite lite(long tsMs, String userId, String deviceId, String country) {
        SessionsJob.EventLite e = new SessionsJob.EventLite();
        e.gameId = "g";
        e.environment = "prod";
        e.userId = userId;
        e.deviceId = deviceId;
        e.country = country;
        e.eventTimeMs = tsMs;
        return e;
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

    @Test
    @DisplayName("BuildSession：多事件聚合 min/max/计数/首非空 country，session_id 确定性")
    void buildSessionAggregatesWindow() throws Exception {
        SessionsJob.BuildSession fn = new SessionsJob.BuildSession();
        List<SessionsJob.SessionRow> out = new ArrayList<>();
        List<SessionsJob.EventLite> events = List.of(
                lite(1_000_000L, "u1", "d1", ""),
                lite(1_061_000L, "u1", "d1", "CN"),
                lite(1_030_500L, "u1", "d1", "US"));   // 乱序：min/max 仍正确
        fn.process(Tuple3.of("g", "prod", "u1"), windowContext(fn), events, sinkTo(out));

        assertEquals(1, out.size());
        SessionsJob.SessionRow s = out.get(0);
        assertEquals("g", s.game_id);
        assertEquals("prod", s.environment);
        assertEquals(new Timestamp(1_000_000L), s.session_start);
        assertEquals(new Timestamp(1_061_000L), s.session_end);
        assertEquals(61, s.duration);
        assertEquals("CN", s.country);     // 首个非空
        assertEquals(3, s.events);
        assertEquals(24, s.session_id.length());
        assertEquals("u1", s.user_id);
        assertEquals("d1", s.device_id);
    }

    @Test
    @DisplayName("BuildSession：空窗口不输出")
    void buildSessionSkipsEmptyWindow() throws Exception {
        SessionsJob.BuildSession fn = new SessionsJob.BuildSession();
        List<SessionsJob.SessionRow> out = new ArrayList<>();
        fn.process(Tuple3.of("g", "prod", "d1"), windowContext(fn), List.of(), sinkTo(out));
        assertTrue(out.isEmpty());
    }

    @Test
    @DisplayName("BuildSession：device 维度会话（无 user_id）")
    void buildSessionDeviceKeyed() throws Exception {
        SessionsJob.BuildSession fn = new SessionsJob.BuildSession();
        List<SessionsJob.SessionRow> out = new ArrayList<>();
        fn.process(Tuple3.of("g", "prod", "d1"), windowContext(fn),
                List.of(lite(5_000L, null, "d1", "JP")), sinkTo(out));
        assertEquals(1, out.size());
        assertEquals("d1", out.get(0).device_id);
        assertEquals("JP", out.get(0).country);
        assertEquals(0, out.get(0).duration);
        assertEquals(1, out.get(0).events);
    }

    // ===== JdbcSink binder =====

    @Test
    @DisplayName("bindSession：10 个参数按序绑定")
    void bindSessionSetsAllParameters() throws Exception {
        SessionsJob.SessionRow s = new SessionsJob.SessionRow();
        s.game_id = "g";
        s.environment = "prod";
        s.session_id = "ABC";
        s.user_id = "u1";
        s.device_id = "d1";
        s.session_start = new Timestamp(1_000L);
        s.session_end = new Timestamp(62_000L);
        s.duration = 61;
        s.country = "CN";
        s.events = 3;

        Map<String, Object> calls = new LinkedHashMap<>();
        InvocationHandler h = (Object proxy, Method method, Object[] a) -> {
            calls.put(method.getName() + ":" + a[0], a[1]);
            return null;
        };
        PreparedStatement ps = (PreparedStatement) Proxy.newProxyInstance(
                SessionsJobTest.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, h);
        SessionsJob.bindSession(ps, s);

        assertEquals("g", calls.get("setString:1"));
        assertEquals("prod", calls.get("setString:2"));
        assertEquals("ABC", calls.get("setString:3"));
        assertEquals("u1", calls.get("setString:4"));
        assertEquals("d1", calls.get("setString:5"));
        assertEquals(new Timestamp(1_000L), calls.get("setTimestamp:6"));
        assertEquals(new Timestamp(62_000L), calls.get("setTimestamp:7"));
        assertEquals(61, calls.get("setInt:8"));
        assertEquals("CN", calls.get("setString:9"));
        assertEquals(3, calls.get("setInt:10"));
        assertEquals(10, calls.size());
    }

    // 覆盖隐式构造器行
    @Test
    @DisplayName("实例化：覆盖隐式构造器")
    void instances() {
        assertNotNull(new SessionsJob());
        assertNotNull(new SessionsJob.BuildSession());
    }
}
