package io.oddsmaker.jobs.retention;

import io.oddsmaker.jobs.enrich.ApicurioAvroFlinkDeserializer;
import io.oddsmaker.jobs.enrich.RawEvent;
import org.apache.flink.api.common.eventtime.SerializableTimestampAssigner;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.state.MapState;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.api.common.state.StateTtlConfig;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.api.common.typeinfo.TypeHint;
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

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;

public class RetentionJob {
    public static void main(String[] args) throws Exception {
        String bootstrap = System.getProperty("kafka.bootstrap", "localhost:9092");
        String registry = System.getProperty("registry.url", "http://localhost:8081/apis/registry/v2");
        String topic = System.getProperty("kafka.topic", "oddsmaker.events_raw");
        String chUrl = System.getProperty("clickhouse.url", "jdbc:clickhouse://localhost:8123/default");
        String chUser = System.getProperty("clickhouse.user", "default");
        String chPass = System.getProperty("clickhouse.pass", "");

        // 可配置留存口径：N-Day（恰好第 N 天活跃）与 Rolling（第 N 天及以后任意活跃）
        RetentionPolicy policy = new RetentionPolicy(
            RetentionPolicy.parseDays(System.getProperty("retention.ndays", "1,7,30")),
            RetentionPolicy.parseDays(System.getProperty("retention.rolling.ndays", "1,3,7,14,30")));

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        KafkaSource<RawEvent> source = KafkaSource.<RawEvent>builder()
                .setBootstrapServers(bootstrap)
                .setTopics(topic)
                .setGroupId("oddsmaker-retention")
                .setStartingOffsets(OffsetsInitializer.latest())
                .setDeserializer(new ApicurioAvroFlinkDeserializer(registry))
                .build();

        var wm = WatermarkStrategy.<RawEvent>forBoundedOutOfOrderness(Duration.ofMinutes(10))
                .withTimestampAssigner((SerializableTimestampAssigner<RawEvent>) (element, recordTimestamp) -> {
                    Long tsServer = element.ts_server;
                    Long tsClient = element.ts_client;
                    long micros = tsServer != null ? tsServer : (tsClient != null ? tsClient : System.currentTimeMillis() * 1000L);
                    return micros / 1000L;
                });

        DataStream<RawEvent> stream = env.fromSource(source, wm, "events-raw");

        DataStream<RetentionEmit> emissions = stream
                .keyBy(r -> (r.game_id+"|"+r.environment+"|"+ uidOf(r)))
                .process(new RetentionProcess(policy));

        // N-Day 留存：恰好第 N 天活跃
        emissions.filter(e -> e.rolling == 0)
                .addSink(JdbcSink.sink(
                        "INSERT INTO retention_daily (game_id, environment, cohort_date, d, users) VALUES (?,?,?,?,?)",
                        (ps, row) -> {
                            ps.setString(1, row.gameId);
                            ps.setString(2, row.environment);
                            ps.setDate(3, new java.sql.Date(row.cohortDate.toEpochDay()*24*3600*1000));
                            ps.setInt(4, row.d);
                            ps.setLong(5, 1L);
                        },
                        JdbcExecutionOptions.builder().withBatchIntervalMs(1000).withBatchSize(2000).withMaxRetries(3).build(),
                        new JdbcConnectionOptions.JdbcConnectionOptionsBuilder()
                                .withUrl(chUrl).withDriverName("com.clickhouse.jdbc.ClickHouseDriver")
                                .withUsername(chUser).withPassword(chPass).build()
                )).name("clickhouse-retention-nday");

        // Rolling 留存：第 N 天及以后任意一天活跃
        emissions.filter(e -> e.rolling > 0)
                .addSink(JdbcSink.sink(
                        "INSERT INTO retention_rolling (game_id, environment, cohort_date, n, users) VALUES (?,?,?,?,?)",
                        (ps, row) -> {
                            ps.setString(1, row.gameId);
                            ps.setString(2, row.environment);
                            ps.setDate(3, new java.sql.Date(row.cohortDate.toEpochDay()*24*3600*1000));
                            ps.setInt(4, row.d);
                            ps.setLong(5, 1L);
                        },
                        JdbcExecutionOptions.builder().withBatchIntervalMs(1000).withBatchSize(2000).withMaxRetries(3).build(),
                        new JdbcConnectionOptions.JdbcConnectionOptionsBuilder()
                                .withUrl(chUrl).withDriverName("com.clickhouse.jdbc.ClickHouseDriver")
                                .withUsername(chUser).withPassword(chPass).build()
                )).name("clickhouse-retention-rolling");

        env.execute("oddsmaker-retention");
    }

    static String uidOf(RawEvent r) {
        Object u = r.user_id;
        if (u != null && !u.toString().isEmpty()) return u.toString();
        return String.valueOf(r.device_id);
    }

    /** rolling=0 为 N-Day 输出；rolling>0 表示 rolling 留存的 N */
    static class RetentionEmit {
        String gameId; String environment; LocalDate cohortDate; int d; int rolling;
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
            long ms = tsMs(value);
            LocalDate day = LocalDate.ofEpochDay(ms / 86_400_000L);

            Long first = state.get("first");
            if (first == null) {
                long epochDay = day.toEpochDay();
                state.put("first", epochDay);
                state.put("last", epochDay);
                RetentionEmit r0 = new RetentionEmit();
                r0.gameId = gameId; r0.environment = environment; r0.cohortDate = day; r0.d = 0; r0.rolling = 0;
                out.collect(r0);
                return;
            }

            long prevLast = state.get("last") == null ? first : state.get("last");

            // N-Day：恰好命中
            int n = policy.nDayHit(first, day.toEpochDay());
            if (n > 0 && state.get("seen_d_" + n) == null) {
                state.put("seen_d_" + n, 1L);
                RetentionEmit r = new RetentionEmit();
                r.gameId = gameId; r.environment = environment;
                r.cohortDate = LocalDate.ofEpochDay(first); r.d = n; r.rolling = 0;
                out.collect(r);
            }

            // Rolling：最后活跃日推进跨越的阈值补记（乱序回退不处理）
            if (day.toEpochDay() > prevLast) {
                state.put("last", day.toEpochDay());
                for (int rn : policy.rollingCrossed(first, prevLast, day.toEpochDay())) {
                    if (state.get("seen_r_" + rn) == null) {
                        state.put("seen_r_" + rn, 1L);
                        RetentionEmit r = new RetentionEmit();
                        r.gameId = gameId; r.environment = environment;
                        r.cohortDate = LocalDate.ofEpochDay(first); r.d = rn; r.rolling = rn;
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
