package io.oddsmaker.jobs.funnels;

import io.oddsmaker.jobs.enrich.ApicurioAvroFlinkDeserializer;
import org.apache.avro.generic.GenericRecord;
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
    public static void main(String[] args) throws Exception {
        String bootstrap = System.getProperty("kafka.bootstrap", "localhost:9092");
        String registry = System.getProperty("registry.url", "http://localhost:8081/apis/registry/v2");
        String topic = System.getProperty("kafka.topic", "oddsmaker.events_raw");
        String chUrl = System.getProperty("clickhouse.url", "jdbc:clickhouse://localhost:8123/default");
        String chUser = System.getProperty("clickhouse.user", "default");
        String chPass = System.getProperty("clickhouse.pass", "");
        String step1 = System.getProperty("funnel.step1", "level_start");
        String step2 = System.getProperty("funnel.step2", "level_complete");
        long timeoutMs = Long.getLong("funnel.timeout.ms", 24L*3600_000L);

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        KafkaSource<GenericRecord> source = KafkaSource.<GenericRecord>builder()
                .setBootstrapServers(bootstrap)
                .setTopics(topic)
                .setGroupId("oddsmaker-funnels")
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
                .filter(r -> {
                    Object n = r.get("event_name");
                    if (n == null) return false;
                    String ev = n.toString();
                    return ev.equals(step1) || ev.equals(step2);
                })
                .keyBy(r -> (r.get("game_id")+"|"+r.get("environment")+"|"+ uidOf(r)))
                .process(new FunnelProcess(step1, step2, timeoutMs))
                .addSink(JdbcSink.sink(
                        "INSERT INTO funnels_2step (game_id, environment, event_date, step1, step2, started, completed) VALUES (?,?,?,?,?,?,?)",
                        (ps, row) -> {
                            ps.setString(1, row.gameId);
                            ps.setString(2, row.environment);
                            ps.setDate(3, new java.sql.Date(row.eventDateEpochDay*24*3600*1000));
                            ps.setString(4, step1);
                            ps.setString(5, step2);
                            ps.setLong(6, row.started);
                            ps.setLong(7, row.completed);
                        },
                        JdbcExecutionOptions.builder().withBatchIntervalMs(1000).withBatchSize(2000).withMaxRetries(3).build(),
                        new JdbcConnectionOptions.JdbcConnectionOptionsBuilder()
                                .withUrl(chUrl).withDriverName("com.clickhouse.jdbc.ClickHouseDriver")
                                .withUsername(chUser).withPassword(chPass).build()
                ));

        env.execute("oddsmaker-funnels-2step");
    }

    static String uidOf(GenericRecord r) {
        Object u = r.get("user_id");
        if (u != null && !u.toString().isEmpty()) return u.toString();
        return String.valueOf(r.get("device_id"));
    }

    static class FunnelRow { String gameId; String environment; long eventDateEpochDay; long started; long completed; }

    static class FunnelProcess extends KeyedProcessFunction<String, GenericRecord, FunnelRow> {
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
        public void processElement(GenericRecord value, Context ctx, Collector<FunnelRow> out) throws Exception {
            String gameId = value.get("game_id").toString();
            String environment = value.get("environment").toString();
            String ev = value.get("event_name").toString();
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

        private long tsMs(GenericRecord r) {
            Long tsServer = (Long) r.get("ts_server");
            Long tsClient = (Long) r.get("ts_client");
            long micros = tsServer != null ? tsServer : (tsClient != null ? tsClient : System.currentTimeMillis() * 1000L);
            return micros / 1000L;
        }
    }
}
