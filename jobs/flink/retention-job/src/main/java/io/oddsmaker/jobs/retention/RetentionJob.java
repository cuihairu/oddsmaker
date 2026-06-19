package io.oddsmaker.jobs.retention;

import io.oddsmaker.jobs.enrich.ApicurioAvroFlinkDeserializer;
import org.apache.avro.generic.GenericRecord;
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

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        KafkaSource<GenericRecord> source = KafkaSource.<GenericRecord>builder()
                .setBootstrapServers(bootstrap)
                .setTopics(topic)
                .setGroupId("oddsmaker-retention")
                .setStartingOffsets(OffsetsInitializer.latest())
                .setDeserializer(new ApicurioAvroFlinkDeserializer(registry))
                .build();

        var wm = WatermarkStrategy.<GenericRecord>forBoundedOutOfOrderness(Duration.ofMinutes(10))
                .withTimestampAssigner((SerializableTimestampAssigner<GenericRecord>) (element, recordTimestamp) -> {
                    Long tsServer = (Long) element.get("ts_server");
                    Long tsClient = (Long) element.get("ts_client");
                    long micros = tsServer != null ? tsServer : (tsClient != null ? tsClient : System.currentTimeMillis() * 1000L);
                    return micros / 1000L;
                });

        DataStream<GenericRecord> stream = env.fromSource(source, wm, "events-raw");

        stream
                .keyBy(r -> (r.get("game_id")+"|"+r.get("environment")+"|"+ uidOf(r)))
                .process(new RetentionProcess())
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
                ));

        env.execute("oddsmaker-retention");
    }

    static String uidOf(GenericRecord r) {
        Object u = r.get("user_id");
        if (u != null && !u.toString().isEmpty()) return u.toString();
        return String.valueOf(r.get("device_id"));
    }

    static class RetentionEmit {
        String gameId; String environment; LocalDate cohortDate; int d;
    }

    static class RetentionProcess extends KeyedProcessFunction<String, GenericRecord, RetentionEmit> {
        private transient MapState<String, Long> state; // keys: firstDay, seen_d_<d>

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
        public void processElement(GenericRecord value, Context ctx, Collector<RetentionEmit> out) throws Exception {
            String gameId = value.get("game_id").toString();
            String environment = value.get("environment").toString();
            long ms = tsMs(value);
            LocalDate day = LocalDate.ofEpochDay(ms / 86_400_000L);

            Long first = state.get("first");
            if (first == null) {
                long epochDay = day.toEpochDay();
                state.put("first", epochDay);
                RetentionEmit r0 = new RetentionEmit(); r0.gameId = gameId; r0.environment = environment; r0.cohortDate = day; r0.d = 0; out.collect(r0);
                return;
            }
            int d = (int)(day.toEpochDay() - first);
            if (d == 1 || d == 7 || d == 30) {
                String key = "seen_d_"+d;
                if (state.get(key) == null) {
                    state.put(key, 1L);
                    RetentionEmit r = new RetentionEmit(); r.gameId = gameId; r.environment = environment; r.cohortDate = LocalDate.ofEpochDay(first); r.d = d; out.collect(r);
                }
            }
        }

        private long tsMs(GenericRecord r) {
            Long tsServer = (Long) r.get("ts_server");
            Long tsClient = (Long) r.get("ts_client");
            long micros = tsServer != null ? tsServer : (tsClient != null ? tsClient : System.currentTimeMillis() * 1000L);
            return micros / 1000L;
        }
    }
}
