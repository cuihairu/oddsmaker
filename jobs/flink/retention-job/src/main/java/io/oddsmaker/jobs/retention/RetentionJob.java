package io.oddsmaker.jobs.retention;

import io.oddsmaker.jobs.enrich.ApicurioAvroFlinkDeserializer;
import io.oddsmaker.jobs.enrich.KeyCodec;
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
        return KeyCodec.join(r.game_id, r.environment, subjectOf(r));
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
        private transient MapState<String, Long> state; // keys: first, last, obs_bits_<window>, seen_d_<n>, seen_r_<n>

        RetentionProcess(RetentionPolicy policy) {
            this.policy = policy;
        }

        /** TTL 必须刷新在读取上（OnReadAndWrite）：first/last 仅在 cohort 建立/校正时写入，
         *  普通活跃事件只读它们；默认 OnCreateAndWrite 会让活跃用户的 first 在 40 天后过期，
         *  下一普通事件误建新 cohort，随后更早日迟到事件触发校正重推已记过的 N-Day（重复写入）。
         *  OnReadAndWrite 使每次事件读取 first/last 都刷新其 TTL，活跃用户状态不会静默过期。 */
        static StateTtlConfig retentionTtlConfig() {
            return StateTtlConfig.newBuilder(Time.days(40)).updateTtlOnReadAndWrite().build();
        }

        @Override
        public void open(org.apache.flink.configuration.Configuration parameters) {
            MapStateDescriptor<String, Long> desc = new MapStateDescriptor<>(
                    "retention_state",
                    TypeInformation.of(String.class),
                    TypeInformation.of(Long.class)
            );
            desc.enableTimeToLive(retentionTtlConfig());
            state = getRuntimeContext().getMapState(desc);
        }

        @Override
        public void processElement(RawEvent value, Context ctx, Collector<RetentionEmit> out) throws Exception {
            String gameId = value.game_id.toString();
            String environment = value.environment.toString();
            String subjectId = subjectOf(value);
            long ms = tsMs(value);
            LocalDate day = LocalDate.ofEpochDay(ms / 86_400_000L);
            long epochDay = day.toEpochDay();
            // 记录绝对观测日（64 天窗口位图）：任何被观测的日子都保留事实，
            // cohort 前移时中间日期不随 seen 清空丢失，后续校正可据此重推 N-Day。
            compactLegacyObservedDays(state);
            recordObservedDay(state, epochDay);

            Long first = state.get("first");
            if (first == null) {
                state.put("first", epochDay);
                state.put("last", epochDay);
                RetentionEmit r0 = new RetentionEmit();
                r0.gameId = gameId; r0.environment = environment; r0.subjectId = subjectId;
                r0.cohortEpochDay = epochDay; r0.d = 0; r0.rolling = 0;
                out.collect(r0);
                return;
            }

            // 队列校正：更早首日事件（day < first）不应静默丢弃。
            // 该事件把 cohort 向前修正，且旧 first 下所有 N-Day/Rolling 记账
            // 相对偏移全部失效，须清空 seen 标记以便按新 cohort 重算。
            if (epochDay < first) {
                long newFirst = epochDay;
                // cohort 前移后 seen 标记全部失效，但已观测的最后活跃日仍真实存在；
                // 保留 max(prevLast, current day) 供新 cohort 补记 N-Day/Rolling。
                Long previousLast = state.get("last");
                long preservedLast = previousLast == null ? newFirst : Math.max(previousLast, epochDay);
                // 绝对观测日位图跨校正保留：cohort 前移只清空相对偏移的 seen 标记，
                // 观测事实本身不丢。再次校正时据全部绝对观测日重推 N-Day，避免中间日期
                // （某次校正后偏移不命中阈值而被丢弃）在后续校正中永久漏计。
                java.util.List<Long> observedDays = new java.util.ArrayList<>();
                for (java.util.Map.Entry<String, Long> e : state.entries()) {
                    String k = e.getKey();
                    if (k.startsWith("obs_bits_")) {
                        try {
                            long window = Long.parseLong(k.substring("obs_bits_".length()));
                            long bits = e.getValue();
                            for (int bit = 0; bit < Long.SIZE; bit++) {
                                if ((bits & (1L << bit)) != 0) {
                                    observedDays.add(window * Long.SIZE + bit);
                                }
                            }
                        } catch (NumberFormatException ignored) {
                            // 非法后缀不影响校正
                        }
                    }
                }
                state.put("first", newFirst);
                state.put("last", preservedLast);
                // 清空相对偏移的 seen 标记（obs_bits_* 保留，跨校正累积观测事实）
                java.util.List<String> stale = new java.util.ArrayList<>();
                for (java.util.Map.Entry<String, Long> e : state.entries()) {
                    String k = e.getKey();
                    if (k.startsWith("seen_d_") || k.startsWith("seen_r_")) {
                        stale.add(k);
                    }
                }
                for (String k : stale) {
                    state.remove(k);
                }
                RetentionEmit r0 = new RetentionEmit();
                r0.gameId = gameId; r0.environment = environment; r0.subjectId = subjectId;
                r0.cohortEpochDay = newFirst; r0.d = 0; r0.rolling = 0;
                out.collect(r0);

                // 重推 N-Day：所有绝对观测日相对新 cohort 的偏移命中阈值即补记（该日确凿被观测）。
                for (long observedDay : observedDays) {
                    int newN = policy.nDayHit(newFirst, observedDay);
                    if (newN > 0 && state.get("seen_d_" + newN) == null) {
                        state.put("seen_d_" + newN, 1L);
                        RetentionEmit r = new RetentionEmit();
                        r.gameId = gameId; r.environment = environment; r.subjectId = subjectId;
                        r.cohortEpochDay = newFirst; r.d = newN; r.rolling = 0;
                        out.collect(r);
                    }
                }

                // 新 cohort 下已观测的最晚活跃日（preservedLast）补记：
                // - N-Day：preservedLast 恰为某阈值时记一次（该日确凿被观测）；
                // - Rolling：last 活跃日 = preservedLast，凡阈值 ≤ preservedLast-newFirst 均已跨越。
                int preservedN = policy.nDayHit(newFirst, preservedLast);
                if (preservedN > 0 && state.get("seen_d_" + preservedN) == null) {
                    state.put("seen_d_" + preservedN, 1L);
                    RetentionEmit r = new RetentionEmit();
                    r.gameId = gameId; r.environment = environment; r.subjectId = subjectId;
                    r.cohortEpochDay = newFirst; r.d = preservedN; r.rolling = 0;
                    out.collect(r);
                }
                for (int rn : policy.rollingCrossed(newFirst, newFirst, preservedLast)) {
                    if (state.get("seen_r_" + rn) == null) {
                        state.put("seen_r_" + rn, 1L);
                        RetentionEmit r = new RetentionEmit();
                        r.gameId = gameId; r.environment = environment; r.subjectId = subjectId;
                        r.cohortEpochDay = newFirst; r.d = rn; r.rolling = rn;
                        out.collect(r);
                    }
                }

                // 说明：旧 cohort 下已发出的 N-Day/Rolling 行无法在本 sink 内撤销——SummingMergeTree
                // 的 users 为 UInt64，不支持负数；完整撤回需引入带 generation/token 的表结构并用
                // REPLACE/增量列（后续独立改造）。此处保留已观测最晚活跃日并补记新 cohort 下
                // 已跨越的 N-Day/Rolling，避免更早事件重置后永久丢失最晚活跃日的留存记录。
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

        /** 将旧版逐日 obs_<epochDay> 键合并为窗口位图，避免升级后状态继续逐日膨胀。 */
        private static void compactLegacyObservedDays(MapState<String, Long> state) throws Exception {
            java.util.List<String> legacyKeys = new java.util.ArrayList<>();
            java.util.List<Long> legacyDays = new java.util.ArrayList<>();
            for (java.util.Map.Entry<String, Long> e : state.entries()) {
                String k = e.getKey();
                if (!k.startsWith("obs_") || k.startsWith("obs_bits_")) continue;
                try {
                    legacyDays.add(Long.parseLong(k.substring("obs_".length())));
                    legacyKeys.add(k);
                } catch (NumberFormatException ignored) {
                    // 非法旧键不参与压缩
                }
            }
            for (long day : legacyDays) {
                recordObservedDay(state, day);
            }
            for (String k : legacyKeys) {
                state.remove(k);
            }
        }

        private static void recordObservedDay(MapState<String, Long> state, long epochDay) throws Exception {
            long window = Math.floorDiv(epochDay, Long.SIZE);
            int bit = Math.floorMod(epochDay, Long.SIZE);
            String key = "obs_bits_" + window;
            Long existing = state.get(key);
            state.put(key, existing == null ? (1L << bit) : (existing | (1L << bit)));
        }

        private long tsMs(RawEvent r) {
            Long tsServer = r.ts_server;
            Long tsClient = r.ts_client;
            long micros = tsServer != null ? tsServer : (tsClient != null ? tsClient : System.currentTimeMillis() * 1000L);
            return micros / 1000L;
        }
    }
}
