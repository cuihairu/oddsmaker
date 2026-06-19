package io.oddsmaker.jobs.sessions;

import io.oddsmaker.jobs.enrich.ApicurioAvroFlinkDeserializer; // reuse deserializer
import org.apache.avro.generic.GenericRecord;
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
    public static void main(String[] args) throws Exception {
        String bootstrap = System.getProperty("kafka.bootstrap", "localhost:9092");
        String registry = System.getProperty("registry.url", "http://localhost:8081/apis/registry/v2");
        String topic = System.getProperty("kafka.topic", "oddsmaker.events_raw"); // 可切换到 events_enriched
        long gapMinutes = Long.getLong("session.gap.minutes", 30L);

        String chUrl = System.getProperty("clickhouse.url", "jdbc:clickhouse://localhost:8123/default");
        String chUser = System.getProperty("clickhouse.user", "default");
        String chPass = System.getProperty("clickhouse.pass", "");

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        KafkaSource<GenericRecord> source = KafkaSource.<GenericRecord>builder()
                .setBootstrapServers(bootstrap)
                .setTopics(topic)
                .setGroupId("oddsmaker-sessions")
                .setStartingOffsets(OffsetsInitializer.latest())
                .setDeserializer(new ApicurioAvroFlinkDeserializer(registry))
                .build();

        var wm = WatermarkStrategy.<EventLite>forBoundedOutOfOrderness(Duration.ofMinutes(10))
                .withTimestampAssigner((SerializableTimestampAssigner<EventLite>) (element, recordTimestamp) -> element.eventTimeMs);

        DataStream<EventLite> events = env.fromSource(source, WatermarkStrategy.noWatermarks(), "events-raw")
                .map((MapFunction<GenericRecord, EventLite>) SessionsJob::toLite)
                .assignTimestampsAndWatermarks(wm);

        events
                .keyBy(e -> Tuple3.of(e.gameId, e.environment, e.userOrDeviceId()))
                .window(EventTimeSessionWindows.withGap(Time.minutes(gapMinutes)))
                .process(new BuildSession())
                .addSink(JdbcSink.sink(
                        "INSERT INTO sessions (game_id, environment, session_id, user_id, device_id, session_start, session_end, duration, country, events) VALUES (?,?,?,?,?,?,?,?,?,?)",
                        (ps, s) -> {
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
                        },
                        JdbcExecutionOptions.builder().withBatchIntervalMs(500).withBatchSize(1000).withMaxRetries(3).build(),
                        new JdbcConnectionOptions.JdbcConnectionOptionsBuilder()
                                .withUrl(chUrl)
                                .withDriverName("com.clickhouse.jdbc.ClickHouseDriver")
                                .withUsername(chUser)
                                .withPassword(chPass)
                                .build()
                ));

        env.execute("oddsmaker-sessions");
    }

    static EventLite toLite(GenericRecord r) {
        EventLite e = new EventLite();
        e.gameId = str(r.get("game_id"));
        e.environment = str(r.get("environment"));
        e.userId = str(r.get("user_id"));
        e.deviceId = str(r.get("device_id"));
        e.country = nz(str(r.get("country")));
        Long tsServerMicros = (Long) r.get("ts_server");
        Long tsClientMicros = (Long) r.get("ts_client");
        long micros = tsServerMicros != null ? tsServerMicros : (tsClientMicros != null ? tsClientMicros : System.currentTimeMillis() * 1000L);
        e.eventTimeMs = micros / 1000L;
        return e;
    }

    static String str(Object v) { return v == null ? null : v.toString(); }
    static String nz(String s) { return s == null ? "" : s; }

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
            SessionRow s = new SessionRow();
            s.game_id = key.f0;
            s.environment = key.f1;
            String userOrDevice = key.f2;
            s.user_id = userOrDevice.equals(elements.iterator().next().userId) ? userOrDevice : (elements.iterator().next().userId == null ? "" : elements.iterator().next().userId);
            s.device_id = elements.iterator().next().deviceId == null ? "" : elements.iterator().next().deviceId;
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
