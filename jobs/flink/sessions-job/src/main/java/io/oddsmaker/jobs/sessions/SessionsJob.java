package io.oddsmaker.jobs.sessions;

import io.oddsmaker.jobs.enrich.ApicurioAvroFlinkDeserializer; // reuse deserializer
import io.oddsmaker.jobs.enrich.RawEvent;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.api.common.restartstrategy.RestartStrategies;
import org.apache.flink.api.common.eventtime.SerializableTimestampAssigner;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.tuple.Tuple3;
import org.apache.flink.connector.jdbc.JdbcConnectionOptions;
import org.apache.flink.connector.jdbc.JdbcExecutionOptions;
import org.apache.flink.connector.jdbc.JdbcSink;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.windowing.assigners.EventTimeSessionWindows;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.util.Collector;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.HexFormat;

public class SessionsJob {
    static final String JOB_NAME = "oddsmaker-sessions";

    /** 入口：读配置 → 搭管道 → 触发执行（execute 才真正连接 source/sink）。 */
    public static void main(String[] args) throws Exception {
        buildPipeline(StreamExecutionEnvironment.getExecutionEnvironment(), config()).getExecutionEnvironment().execute(JOB_NAME);
    }

    /** 配置读取（System properties，单测可 setProperty 后逐项直测）。 */
    static String[] config() {
        return new String[]{
                System.getProperty("kafka.bootstrap", "localhost:9092"),
                System.getProperty("registry.url", "http://localhost:8081/apis/registry/v2"),
                // 可切换到 events_enriched
                System.getProperty("kafka.topic", "oddsmaker.events_raw"),
                System.getProperty("clickhouse.url", "jdbc:clickhouse://localhost:8123/default"),
                System.getProperty("clickhouse.user", "default"),
                System.getProperty("clickhouse.pass", ""),
                // event-time session 窗口只在 watermark 越过 窗口末+gap 后 fire；
                // 容差越小，无后续事件时落库等待越短。e2e 用 0，生产默认 10 分钟乱序容忍
                Long.toString(Long.getLong("session.gap.minutes", 30L)),
                Long.toString(Long.getLong("watermark.ooo.minutes", 10L)),
        };
    }

    /** session 窗口水位线（事件时间 = EventLite.eventTimeMs）。 */
    static WatermarkStrategy<EventLite> watermarks(long oooMinutes) {
        return WatermarkStrategy.<EventLite>forBoundedOutOfOrderness(Duration.ofMinutes(oooMinutes))
                .withTimestampAssigner((SerializableTimestampAssigner<EventLite>) (element, recordTimestamp) -> element.eventTimeMs);
    }

    /**
     * 搭建 session 聚合管道（惰性：source/sink 均到 execute 才连接，单测可用本地环境直跑）。
     */
    static org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator<SessionRow> buildPipeline(StreamExecutionEnvironment env, String[] cfg) {
        // 容错语义闭环:无 checkpoint 时 KafkaSource 从不提交 offset,重启后按回退策略重新初始化,
        // 停机窗口事件全部丢失且 keyed state 全空。启用 checkpoint 后 offset 随 checkpoint 提交、
        // state 持久恢复、重启从上次 checkpoint 续读(残余风险收敛为 JdbcSink at-least-once 的
        // 检查点间隔窗口内重复,处理层重复由去重/聚合 state 恢复挡住);
        // 回退 LATEST 保持既有首启动语义,避免已部署环境全量重放+state 空造成全量重复
        env.enableCheckpointing(30_000L, CheckpointingMode.AT_LEAST_ONCE);
        env.setRestartStrategy(RestartStrategies.fixedDelayRestart(3,
                org.apache.flink.api.common.time.Time.seconds(10)));
        String bootstrap = cfg[0], registry = cfg[1], topic = cfg[2], chUrl = cfg[3], chUser = cfg[4], chPass = cfg[5];
        long gapMinutes = Long.parseLong(cfg[6]);
        long oooMinutes = Long.parseLong(cfg[7]);

        // local executor 默认并行度=CPU 核数，而 events_raw 只有 1 个分区：
        // 多余的空 source subtask 会把全局 watermark 卡死，session 窗口永不 fire。
        // 显式置 1；集群模式提交时用 flink run -p 覆盖
        env.setParallelism(1);

        KafkaSource<RawEvent> source = KafkaSource.<RawEvent>builder()
                .setBootstrapServers(bootstrap)
                .setTopics(topic)
                .setGroupId("oddsmaker-sessions")
                .setStartingOffsets(OffsetsInitializer.committedOffsets(OffsetResetStrategy.LATEST))
                .setDeserializer(new ApicurioAvroFlinkDeserializer(registry))
                .build();

        DataStream<EventLite> events = env.fromSource(source, WatermarkStrategy.noWatermarks(), "events-raw")
                .map((MapFunction<RawEvent, EventLite>) SessionsJob::toLite)
                .assignTimestampsAndWatermarks(watermarks(oooMinutes));

        org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator<SessionRow> sessions = events
                // Tuple3 泛型在 lambda 中被擦除，需显式提供 key 类型信息
                .keyBy(SessionsJob::sessionKey,
                        org.apache.flink.api.common.typeinfo.Types.TUPLE(
                                org.apache.flink.api.common.typeinfo.Types.STRING,
                                org.apache.flink.api.common.typeinfo.Types.STRING,
                                org.apache.flink.api.common.typeinfo.Types.STRING))
                .window(EventTimeSessionWindows.withGap(Time.minutes(gapMinutes)))
                .process(new BuildSession());

        sessions.addSink(JdbcSink.sink(
                "INSERT INTO sessions (game_id, environment, session_id, user_id, device_id, session_start, session_end, duration, country, events) VALUES (?,?,?,?,?,?,?,?,?,?)",
                SessionsJob::bindSession,
                JdbcExecutionOptions.builder().withBatchIntervalMs(500).withBatchSize(1000).withMaxRetries(3).build(),
                new JdbcConnectionOptions.JdbcConnectionOptionsBuilder()
                        .withUrl(chUrl)
                        .withDriverName("com.clickhouse.jdbc.ClickHouseDriver")
                        .withUsername(chUser)
                        .withPassword(chPass)
                        .build()));
        return sessions;
    }

    /** sessions 表写入绑定。 */
    static void bindSession(java.sql.PreparedStatement ps, SessionRow s) throws java.sql.SQLException {
        ps.setString(1, s.game_id);
        ps.setString(2, s.environment);
        ps.setString(3, s.session_id);
        ps.setString(4, s.user_id);
        ps.setString(5, s.device_id);
        ps.setTimestamp(6, s.session_start);
        ps.setTimestamp(7, s.session_end);
        ps.setInt(8, s.duration);
        ps.setString(9, s.country);
        ps.setInt(10, s.events);
    }

    static EventLite toLite(RawEvent r) {
        EventLite e = new EventLite();
        e.gameId = str(r.game_id);
        e.environment = str(r.environment);
        e.userId = str(r.user_id);
        e.deviceId = str(r.device_id);
        e.country = nz(str(r.country));
        Long tsServerMicros = r.ts_server;
        Long tsClientMicros = r.ts_client;
        long micros = tsServerMicros != null ? tsServerMicros : (tsClientMicros != null ? tsClientMicros : System.currentTimeMillis() * 1000L);
        e.eventTimeMs = micros / 1000L;
        return e;
    }

    static String str(Object v) { return v == null ? null : v.toString(); }
    static String nz(String s) { return s == null ? "" : s; }

    /** 会话分组键：(gameId, environment, userId 优先回退 deviceId)。 */
    static Tuple3<String, String, String> sessionKey(EventLite e) {
        return Tuple3.of(e.gameId, e.environment, e.userOrDeviceId());
    }

    /** 会话 user_id 归位：key 即 userId 时原样，否则取窗口首事件的 userId（无则空串）。 */
    static String resolveUserId(String userOrDevice, String firstUserId) {
        return userOrDevice.equals(firstUserId) ? userOrDevice : (firstUserId == null ? "" : firstUserId);
    }

    /** 会话 device_id：窗口首事件缺失时空串。 */
    static String resolveDeviceId(String firstDeviceId) {
        return firstDeviceId == null ? "" : firstDeviceId;
    }

    public static class EventLite {
        public String gameId;
        public String environment;
        public String userId;
        public String deviceId;
        public String country;
        public long eventTimeMs;
        public String userOrDeviceId() { return userId != null && !userId.isEmpty() ? userId : deviceId; }
    }

    public static class SessionRow {
        public String game_id;
        public String environment;
        public String session_id;
        public String user_id;
        public String device_id;
        public Timestamp session_start;
        public Timestamp session_end;
        public int duration;
        public String country;
        public int events;
    }

    public static class BuildSession extends ProcessWindowFunction<EventLite, SessionRow, Tuple3<String,String,String>, TimeWindow> {
        @Override
        public void process(Tuple3<String, String, String> key, Context context, Iterable<EventLite> elements, Collector<SessionRow> out) throws Exception {
            long minTs = Long.MAX_VALUE, maxTs = Long.MIN_VALUE; int cnt = 0; String country = "";
            for (EventLite e : elements) {
                if (e.eventTimeMs < minTs) minTs = e.eventTimeMs;
                if (e.eventTimeMs > maxTs) maxTs = e.eventTimeMs;
                if (country.isEmpty() && e.country != null) country = e.country;
                cnt++;
            }
            if (cnt == 0) return;
            EventLite first = elements.iterator().next();
            SessionRow s = new SessionRow();
            s.game_id = key.f0;
            s.environment = key.f1;
            String userOrDevice = key.f2;
            s.user_id = resolveUserId(userOrDevice, first.userId);
            s.device_id = resolveDeviceId(first.deviceId);
            s.session_start = new Timestamp(minTs);
            s.session_end = new Timestamp(maxTs);
            s.duration = (int) Math.max(0, (maxTs - minTs)/1000);
            s.country = country;
            s.events = cnt;
            s.session_id = deterministicId(key.f0 + ":" + key.f1 + ":" + userOrDevice + ":" + minTs);
            out.collect(s);
        }
    }

    static String deterministicId(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().withUpperCase().formatHex(d, 0, 12); // 24 hex chars
        } catch (Exception e) { return s; }
    }
}
