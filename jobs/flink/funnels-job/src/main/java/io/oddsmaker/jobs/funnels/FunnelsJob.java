package io.oddsmaker.jobs.funnels;

import io.oddsmaker.jobs.enrich.ApicurioAvroFlinkDeserializer;
import io.oddsmaker.jobs.enrich.RawEvent;
import org.apache.flink.api.common.eventtime.SerializableTimestampAssigner;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.state.MapState;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.api.common.state.StateTtlConfig;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.connector.jdbc.JdbcConnectionOptions;
import org.apache.flink.connector.jdbc.JdbcExecutionOptions;
import org.apache.flink.connector.jdbc.JdbcSink;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

import java.time.Duration;
import java.time.LocalDate;

public class FunnelsJob {
    static final String JOB_NAME = "oddsmaker-funnels-2step";

    /** 入口：读配置 → 搭管道 → 触发执行（execute 才真正连接 source/sink）。 */
    public static void main(String[] args) throws Exception {
        buildPipeline(StreamExecutionEnvironment.getExecutionEnvironment(), config()).getExecutionEnvironment().execute(JOB_NAME);
    }

    /** 配置读取（System properties，单测可 setProperty 后直测）。顺序见 buildPipeline。 */
    static String[] config() {
        return new String[]{
                System.getProperty("kafka.bootstrap", "localhost:9092"),
                System.getProperty("registry.url", "http://localhost:8081/apis/registry/v2"),
                System.getProperty("kafka.topic", "oddsmaker.events_raw"),
                System.getProperty("clickhouse.url", "jdbc:clickhouse://localhost:8123/default"),
                System.getProperty("clickhouse.user", "default"),
                System.getProperty("clickhouse.pass", ""),
                System.getProperty("funnel.step1", "level_start"),
                System.getProperty("funnel.step2", "level_complete"),
                Long.toString(Long.getLong("funnel.timeout.ms", 24L * 3600_000L)),
        };
    }

    /** 有界乱序水位线（10 分钟），时间戳取 ts_server → ts_client → now。 */
    static WatermarkStrategy<RawEvent> watermarks() {
        return WatermarkStrategy.<RawEvent>forBoundedOutOfOrderness(Duration.ofMinutes(10))
                .withTimestampAssigner((SerializableTimestampAssigner<RawEvent>) (element, recordTimestamp) -> {
                    Long tsServer = element.ts_server;
                    Long tsClient = element.ts_client;
                    long micros = tsServer != null ? tsServer : (tsClient != null ? tsClient : System.currentTimeMillis() * 1000L);
                    return micros / 1000L;
                });
    }

    /**
     * 搭建两步漏斗管道（惰性：source/sink 均到 execute 才连接，单测可用本地环境直跑）。
     */
    static DataStream<FunnelRow> buildPipeline(StreamExecutionEnvironment env, String[] cfg) {
        String bootstrap = cfg[0], registry = cfg[1], topic = cfg[2], chUrl = cfg[3], chUser = cfg[4], chPass = cfg[5];
        String step1 = cfg[6], step2 = cfg[7];
        long timeoutMs = Long.parseLong(cfg[8]);

        // local executor 默认并行度=CPU 核数，而 events_raw 只有 1 个分区：
        // 多余的空 source subtask 会把全局 watermark 卡死。
        // 显式置 1；集群模式提交时用 flink run -p 覆盖
        env.setParallelism(1);

        KafkaSource<RawEvent> source = KafkaSource.<RawEvent>builder()
                .setBootstrapServers(bootstrap)
                .setTopics(topic)
                .setGroupId("oddsmaker-funnels")
                .setStartingOffsets(OffsetsInitializer.latest())
                .setDeserializer(new ApicurioAvroFlinkDeserializer(registry))
                .build();

        DataStream<RawEvent> stream = env.fromSource(source, watermarks(), "events-raw");

        org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator<FunnelRow> rows = stream
                .filter(stepFilter(step1, step2))
                .keyBy(FunnelsJob::funnelKey)
                .process(new FunnelProcess(step1, step2, timeoutMs));

        rows.addSink(JdbcSink.sink(
                "INSERT INTO funnels_2step (game_id, environment, event_date, step1, step2, started, completed) VALUES (?,?,?,?,?,?,?)",
                rowBinder(step1, step2),
                JdbcExecutionOptions.builder().withBatchIntervalMs(1000).withBatchSize(2000).withMaxRetries(3).build(),
                new JdbcConnectionOptions.JdbcConnectionOptionsBuilder()
                        .withUrl(chUrl).withDriverName("com.clickhouse.jdbc.ClickHouseDriver")
                        .withUsername(chUser).withPassword(chPass).build()));
        return rows;
    }

    /** 漏斗步骤事件过滤：event_name 命中 step1/step2 之一。 */
    static boolean isStepEvent(RawEvent r, String step1, String step2) {
        Object n = r.event_name;
        if (n == null) return false;
        String ev = n.toString();
        return ev.equals(step1) || ev.equals(step2);
    }

    /** 过滤算子工厂（lambda 体可经返回值直调覆盖）。 */
    static org.apache.flink.api.common.functions.FilterFunction<RawEvent> stepFilter(String step1, String step2) {
        return r -> isStepEvent(r, step1, step2);
    }

    /** 漏斗分组键：game|environment|uid。 */
    static String funnelKey(RawEvent r) {
        return r.game_id + "|" + r.environment + "|" + uidOf(r);
    }

    /** funnels_2step 写入绑定。 */
    static void bindFunnelRow(java.sql.PreparedStatement ps, FunnelRow row, String step1, String step2) throws java.sql.SQLException {
        ps.setString(1, row.gameId);
        ps.setString(2, row.environment);
        ps.setDate(3, new java.sql.Date(row.eventDateEpochDay * 24 * 3600 * 1000));
        ps.setString(4, step1);
        ps.setString(5, step2);
        ps.setLong(6, row.started);
        ps.setLong(7, row.completed);
    }

    /** JdbcSink 写入绑定工厂（捕获步骤事件名，lambda 体可经返回值直调覆盖）。 */
    static org.apache.flink.connector.jdbc.JdbcStatementBuilder<FunnelRow> rowBinder(String step1, String step2) {
        return (ps, row) -> bindFunnelRow(ps, row, step1, step2);
    }

    static String uidOf(RawEvent r) {
        Object u = r.user_id;
        if (u != null && !u.toString().isEmpty()) return u.toString();
        return String.valueOf(r.device_id);
    }

    /** public class + public fields：包私有类会被 Flink 退化成 Kryo 泛型序列化 */
    public static class FunnelRow { public String gameId; public String environment; public long eventDateEpochDay; public long started; public long completed; }

    static class FunnelProcess extends KeyedProcessFunction<String, RawEvent, FunnelRow> {
        private final String step1; private final String step2; private final long timeoutMs;
        private transient MapState<String, Long> state; // keys: last_step1_ts, started_day_<day>, completed_day_<day>

        public FunnelProcess(String step1, String step2, long timeoutMs) { this.step1 = step1; this.step2 = step2; this.timeoutMs = timeoutMs; }

        @Override
        public void open(org.apache.flink.configuration.Configuration parameters) {
            StateTtlConfig ttl = StateTtlConfig.newBuilder(Time.days(40)).build();
            MapStateDescriptor<String, Long> desc = new MapStateDescriptor<>("funnel_state", TypeInformation.of(String.class), TypeInformation.of(Long.class));
            desc.enableTimeToLive(ttl);
            state = getRuntimeContext().getMapState(desc);
        }

        @Override
        public void processElement(RawEvent value, Context ctx, Collector<FunnelRow> out) throws Exception {
            String gameId = value.game_id.toString();
            String environment = value.environment.toString();
            String ev = value.event_name.toString();
            long ts = tsMs(value);
            long day = ts / 86_400_000L;
            if (ev.equals(step1)) {
                Long seen = state.get("started_day_"+day);
                if (seen == null) {
                    state.put("started_day_"+day, 1L);
                    FunnelRow r = new FunnelRow(); r.gameId = gameId; r.environment = environment; r.eventDateEpochDay = day; r.started = 1; r.completed = 0; out.collect(r);
                }
                state.put("last_step1_ts", ts);
            } else if (ev.equals(step2)) {
                Long last1 = state.get("last_step1_ts");
                if (last1 != null && ts - last1 <= timeoutMs) {
                    Long seen = state.get("completed_day_"+day);
                    if (seen == null) {
                        state.put("completed_day_"+day, 1L);
                        FunnelRow r = new FunnelRow(); r.gameId = gameId; r.environment = environment; r.eventDateEpochDay = day; r.started = 0; r.completed = 1; out.collect(r);
                    }
                }
            }
        }

        private long tsMs(RawEvent r) {
            Long tsServer = r.ts_server;
            Long tsClient = r.ts_client;
            long micros = tsServer != null ? tsServer : (tsClient != null ? tsClient : System.currentTimeMillis() * 1000L);
            return micros / 1000L;
        }
    }
}
