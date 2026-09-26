package io.oddsmaker.jobs.retention;

import io.oddsmaker.jobs.enrich.ApicurioAvroFlinkDeserializer;
import io.oddsmaker.jobs.enrich.RawEvent;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.api.common.restartstrategy.RestartStrategies;
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
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

import java.time.Duration;
import java.time.LocalDate;

public class RetentionJob {
    static final String JOB_NAME = "oddsmaker-retention";

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
                // 可配置留存口径：N-Day（恰好第 N 天活跃）与 Rolling（第 N 天及以后任意活跃）
                System.getProperty("retention.ndays", "1,7,30"),
                System.getProperty("retention.rolling.ndays", "1,3,7,14,30"),
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
     * 搭建留存管道（惰性：source/sink 均到 execute 才连接，单测可用本地环境直跑）。
     */
    static DataStream<RetentionEmit> buildPipeline(StreamExecutionEnvironment env, String[] cfg) {
        // 容错语义闭环:无 checkpoint 时 KafkaSource 从不提交 offset,重启后按回退策略重新初始化,
        // 停机窗口事件全部丢失且 keyed state 全空。启用 checkpoint 后 offset 随 checkpoint 提交、
        // state 持久恢复、重启从上次 checkpoint 续读(残余风险收敛为 JdbcSink at-least-once 的
        // 检查点间隔窗口内重复,处理层重复由去重/聚合 state 恢复挡住);
        // 回退 LATEST 保持既有首启动语义,避免已部署环境全量重放+state 空造成全量重复
        env.enableCheckpointing(30_000L, CheckpointingMode.AT_LEAST_ONCE);
        env.setRestartStrategy(RestartStrategies.fixedDelayRestart(3, Time.seconds(10)));
        String bootstrap = cfg[0], registry = cfg[1], topic = cfg[2], chUrl = cfg[3], chUser = cfg[4], chPass = cfg[5];
        RetentionPolicy policy = new RetentionPolicy(
                RetentionPolicy.parseDays(cfg[6]), RetentionPolicy.parseDays(cfg[7]));

        // local executor 默认并行度=CPU 核数，而 events_raw 只有 1 个分区：
        // 多余的空 source subtask 会把全局 watermark 卡死。
        // 显式置 1；集群模式提交时用 flink run -p 覆盖
        env.setParallelism(1);

        KafkaSource<RawEvent> source = KafkaSource.<RawEvent>builder()
                .setBootstrapServers(bootstrap)
                .setTopics(topic)
                .setGroupId("oddsmaker-retention")
                .setStartingOffsets(OffsetsInitializer.committedOffsets(OffsetResetStrategy.LATEST))
                .setDeserializer(new ApicurioAvroFlinkDeserializer(registry))
                .build();

        DataStream<RawEvent> stream = env.fromSource(source, watermarks(), "events-raw");

        SingleOutputStreamOperator<RetentionEmit> emissions = stream
                .keyBy(RetentionJob::retentionKey)
                .process(new RetentionProcess(policy));

        // N-Day 留存：恰好第 N 天活跃（带 subject_id 主体维度，供分群过滤走预聚合）
        emissions.filter(RetentionJob::isNDay)
                .addSink(JdbcSink.sink(
                        "INSERT INTO retention_daily (game_id, environment, subject_id, cohort_date, d, users) VALUES (?,?,?,?,?,?)",
                        RetentionJob::bindRetentionDaily,
                        JdbcExecutionOptions.builder().withBatchIntervalMs(1000).withBatchSize(2000).withMaxRetries(3).build(),
                        new JdbcConnectionOptions.JdbcConnectionOptionsBuilder()
                                .withUrl(chUrl).withDriverName("com.clickhouse.jdbc.ClickHouseDriver")
                                .withUsername(chUser).withPassword(chPass).build()
                )).name("clickhouse-retention-nday");

        // Rolling 留存：第 N 天及以后任意一天活跃
        emissions.filter(RetentionJob::isRolling)
                .addSink(JdbcSink.sink(
                        "INSERT INTO retention_rolling (game_id, environment, cohort_date, n, users) VALUES (?,?,?,?,?)",
                        RetentionJob::bindRetention,
                        JdbcExecutionOptions.builder().withBatchIntervalMs(1000).withBatchSize(2000).withMaxRetries(3).build(),
                        new JdbcConnectionOptions.JdbcConnectionOptionsBuilder()
                                .withUrl(chUrl).withDriverName("com.clickhouse.jdbc.ClickHouseDriver")
                                .withUsername(chUser).withPassword(chPass).build()
                )).name("clickhouse-retention-rolling");

        return emissions;
    }

    /** 留存分组键：game|environment|subject。 */
    static String retentionKey(RawEvent r) {
        return r.game_id + "|" + r.environment + "|" + subjectOf(r);
    }

    /** N-Day 输出行（rolling == 0）。 */
    static boolean isNDay(RetentionEmit e) {
        return e.rolling == 0;
    }

    /** Rolling 输出行（rolling > 0）。 */
    static boolean isRolling(RetentionEmit e) {
        return e.rolling > 0;
    }

    /** retention_daily 写入绑定（6 列，含主体维度）。 */
    static void bindRetentionDaily(java.sql.PreparedStatement ps, RetentionEmit row) throws java.sql.SQLException {
        ps.setString(1, row.gameId);
        ps.setString(2, row.environment);
        ps.setString(3, row.subjectId);
        ps.setDate(4, new java.sql.Date(row.cohortEpochDay * 86400000L));
        ps.setInt(5, row.d);
        ps.setLong(6, 1L);
    }

    /** retention_rolling 写入绑定（5 列，无主体维度）。 */
    static void bindRetention(java.sql.PreparedStatement ps, RetentionEmit row) throws java.sql.SQLException {
        ps.setString(1, row.gameId);
        ps.setString(2, row.environment);
        ps.setDate(3, new java.sql.Date(row.cohortEpochDay * 86400000L));
        ps.setInt(4, row.d);
        ps.setLong(5, 1L);
    }

    /** 主体口径（与 SegmentService.SUBJECT_PLAYER 对齐）：player_id → user_id → device_id。 */
    static String subjectOf(RawEvent r) {
        if (r.player_id != null && !r.player_id.isEmpty()) return r.player_id;
        if (r.user_id != null && !r.user_id.isEmpty()) return r.user_id;
        return String.valueOf(r.device_id);
    }

    /**
     * rolling=0 为 N-Day 输出；rolling>0 表示 rolling 留存的 N。
     * public class + public fields：Flink 只把 public 无参构造 + public 字段的类识别为 POJO，
     * 包私有类会退化成 Kryo 泛型序列化。
     * cohort 用 long epochDay 而非 LocalDate：LocalDate 字段走 Kryo 反射需要
     * --add-opens java.base/java.time，裸 JVM 直接炸（InaccessibleObjectException）
     */
    public static class RetentionEmit {
        public String gameId; public String environment; public String subjectId; public long cohortEpochDay; public int d; public int rolling;
    }

    static class RetentionProcess extends KeyedProcessFunction<String, RawEvent, RetentionEmit> {
        private final RetentionPolicy policy;
        private transient MapState<String, Long> state; // keys: first, last, seen_d_<n>, seen_r_<n>

        RetentionProcess(RetentionPolicy policy) {
            this.policy = policy;
        }

        @Override
        public void open(org.apache.flink.configuration.Configuration parameters) {
            StateTtlConfig ttl = StateTtlConfig.newBuilder(Time.days(40)).build();
            MapStateDescriptor<String, Long> desc = new MapStateDescriptor<>(
                    "retention_state",
                    TypeInformation.of(String.class),
                    TypeInformation.of(Long.class)
            );
            desc.enableTimeToLive(ttl);
            state = getRuntimeContext().getMapState(desc);
        }

        @Override
        public void processElement(RawEvent value, Context ctx, Collector<RetentionEmit> out) throws Exception {
            String gameId = value.game_id.toString();
            String environment = value.environment.toString();
            String subjectId = subjectOf(value);
            long ms = tsMs(value);
            LocalDate day = LocalDate.ofEpochDay(ms / 86_400_000L);

            Long first = state.get("first");
            if (first == null) {
                long epochDay = day.toEpochDay();
                state.put("first", epochDay);
                state.put("last", epochDay);
                RetentionEmit r0 = new RetentionEmit();
                r0.gameId = gameId; r0.environment = environment; r0.subjectId = subjectId;
                r0.cohortEpochDay = day.toEpochDay(); r0.d = 0; r0.rolling = 0;
                out.collect(r0);
                return;
            }

            long prevLast = state.get("last") == null ? first : state.get("last");

            // N-Day：恰好命中
            int n = policy.nDayHit(first, day.toEpochDay());
            if (n > 0 && state.get("seen_d_" + n) == null) {
                state.put("seen_d_" + n, 1L);
                RetentionEmit r = new RetentionEmit();
                r.gameId = gameId; r.environment = environment; r.subjectId = subjectId;
                r.cohortEpochDay = first; r.d = n; r.rolling = 0;
                out.collect(r);
            }

            // Rolling：最后活跃日推进跨越的阈值补记（乱序回退不处理）
            if (day.toEpochDay() > prevLast) {
                state.put("last", day.toEpochDay());
                for (int rn : policy.rollingCrossed(first, prevLast, day.toEpochDay())) {
                    if (state.get("seen_r_" + rn) == null) {
                        state.put("seen_r_" + rn, 1L);
                        RetentionEmit r = new RetentionEmit();
                        r.gameId = gameId; r.environment = environment; r.subjectId = subjectId;
                        r.cohortEpochDay = first; r.d = rn; r.rolling = rn;
                        out.collect(r);
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
