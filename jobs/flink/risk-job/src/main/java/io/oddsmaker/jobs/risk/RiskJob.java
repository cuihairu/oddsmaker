package io.oddsmaker.jobs.risk;

import io.oddsmaker.jobs.enrich.ApicurioAvroFlinkDeserializer;
import org.apache.avro.generic.GenericRecord;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.connector.jdbc.JdbcConnectionOptions;
import org.apache.flink.connector.jdbc.JdbcExecutionOptions;
import org.apache.flink.connector.jdbc.JdbcSink;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.assigners.SlidingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class RiskJob {

    public static void main(String[] args) throws Exception {
        String bootstrap = System.getProperty("kafka.bootstrap", "localhost:9092");
        String registry = System.getProperty("registry.url", "http://localhost:8081/apis/registry/v2");
        String sourceTopic = System.getProperty("kafka.topic", "oddsmaker.events_raw");
        String riskTopic = System.getProperty("risk.topic", "oddsmaker.risk_events");
        String chUrl = System.getProperty("clickhouse.url", "jdbc:clickhouse://localhost:8123/default");
        String chUser = System.getProperty("clickhouse.user", "default");
        String chPass = System.getProperty("clickhouse.pass", "");

        BigDecimal amountThreshold = new BigDecimal(System.getProperty("risk.threshold.amount", "100000"));
        int freqWindowMin = Integer.parseInt(System.getProperty("risk.frequency.window-minutes", "10"));
        long freqMaxEvents = Long.parseLong(System.getProperty("risk.frequency.max-events", "1000"));

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        KafkaSource<GenericRecord> source = KafkaSource.<GenericRecord>builder()
                .setBootstrapServers(bootstrap)
                .setTopics(sourceTopic)
                .setGroupId("oddsmaker-risk-job")
                .setStartingOffsets(OffsetsInitializer.latest())
                .setDeserializer(new ApicurioAvroFlinkDeserializer(registry))
                .build();

        WatermarkStrategy<GenericRecord> wm = WatermarkStrategy.<GenericRecord>forBoundedOutOfOrderness(Duration.ofMinutes(2))
                .withTimestampAssigner((r, ts) -> {
                    Long s = (Long) r.get("ts_server");
                    Long c = (Long) r.get("ts_client");
                    long micros = s != null ? s : (c != null ? c : System.currentTimeMillis() * 1000L);
                    return micros / 1000L;
                });

        DataStream<GenericRecord> raw = env.fromSource(source, wm, "events-raw");

        DataStream<RiskInput> inputs = raw.flatMap((FlatMapFunction<GenericRecord, RiskInput>) (r, out) -> {
            String gameId = str(r.get("game_id"));
            String environmentName = str(r.get("environment"));
            String eventId = str(r.get("event_id"));
            String eventName = str(r.get("event_name"));
            String userId = nz(str(r.get("user_id")));
            String deviceId = str(r.get("device_id"));
            if (gameId == null || environmentName == null || eventId == null || deviceId == null) return;
            String eventType = nz(str(r.get("event_type")));
            BigDecimal amount = parseAmount(r.get("resource_amount"));
            RiskInput in = new RiskInput();
            in.gameId = gameId;
            in.environment = environmentName;
            in.eventId = eventId;
            in.eventName = eventName;
            in.eventType = eventType;
            in.userId = userId;
            in.deviceId = deviceId;
            in.clientIp = nz(str(r.get("client_ip")));
            in.ts = new Timestamp(System.currentTimeMillis());
            Long tsServer = (Long) r.get("ts_server");
            if (tsServer != null) in.ts = new Timestamp(tsServer / 1000L);
            in.amount = amount;
            out.collect(in);
        }).returns(Types.POJO(RiskInput.class));

        DataStream<RiskHit> thresholdHits = inputs
                .filter(i -> i.amount != null && i.amount.compareTo(amountThreshold) > 0)
                .map(i -> {
                    Map<String, String> ev = new HashMap<>();
                    ev.put("resource_amount", i.amount.toPlainString());
                    ev.put("event_name", i.eventName);
                    return new RiskHit(
                            i.gameId, i.environment, i.ts, UUID.randomUUID().toString(), i.eventId,
                            "risk-threshold-amount", "THRESHOLD", "HIGH",
                            subjectType(i), subjectId(i),
                            80f, "ALERT",
                            "resource amount " + i.amount.toPlainString() + " exceeds threshold " + amountThreshold.toPlainString(),
                            ev);
                }).returns(Types.POJO(RiskHit.class));

        DataStream<RiskHit> frequencyHits = inputs
                .keyBy(i -> i.gameId + "|" + i.environment + "|" + subjectKey(i))
                .window(SlidingEventTimeWindows.of(Time.minutes(freqWindowMin), Time.minutes(freqWindowMin / 2 > 0 ? freqWindowMin / 2 : 1)))
                .process(new ProcessWindowFunction<RiskInput, RiskHit, String, TimeWindow>() {
                    @Override
                    public void process(String key, Context ctx, Iterable<RiskInput> events, Collector<RiskHit> out) {
                        long count = 0;
                        RiskInput last = null;
                        for (RiskInput e : events) {
                            count++;
                            last = e;
                        }
                        if (count > freqMaxEvents && last != null) {
                            Map<String, String> ev = new HashMap<>();
                            ev.put("window_events", String.valueOf(count));
                            ev.put("window_minutes", String.valueOf(freqWindowMin));
                            ev.put("subject", subjectKey(last));
                            out.collect(new RiskHit(
                                    last.gameId, last.environment, last.ts, UUID.randomUUID().toString(), last.eventId,
                                    "risk-frequency-burst", "FREQUENCY", "MEDIUM",
                                    subjectType(last), subjectId(last),
                                    60f, "ALERT",
                                    "event burst " + count + " in " + freqWindowMin + "min (limit " + freqMaxEvents + ")",
                                    ev));
                        }
                    }
                }).returns(Types.POJO(RiskHit.class));

        DataStream<RiskHit> allHits = thresholdHits.union(frequencyHits);

        KafkaSink<String> kafkaSink = KafkaSink.<String>builder()
                .setBootstrapServers(bootstrap)
                .setRecordSerializer(KafkaRecordSerializationSchema.builder()
                        .setTopic(riskTopic)
                        .setValueSerializationSchema(new org.apache.flink.api.common.serialization.SimpleStringSchema())
                        .build())
                .build();

        allHits.map(RiskJob::toJson).returns(Types.STRING).sinkTo(kafkaSink).name("kafka-risk-events");

        var jdbcSink = JdbcSink.<RiskHit>sink(
                "INSERT INTO risk_events (game_id, environment, ts, risk_event_id, source_event_id, rule_id, risk_type, severity, subject_type, subject_id, score, action, reason, evidence) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (ps, h) -> {
                    ps.setString(1, h.gameId);
                    ps.setString(2, h.environment);
                    ps.setTimestamp(3, h.ts);
                    ps.setString(4, h.riskEventId);
                    ps.setString(5, h.sourceEventId);
                    ps.setString(6, h.ruleId);
                    ps.setString(7, h.riskType);
                    ps.setString(8, h.severity);
                    ps.setString(9, h.subjectType);
                    ps.setString(10, h.subjectId);
                    ps.setFloat(11, h.score);
                    ps.setString(12, h.action);
                    ps.setString(13, h.reason);
                    ps.setObject(14, h.evidence);
                },
                JdbcExecutionOptions.builder().withBatchIntervalMs(500).withBatchSize(500).withMaxRetries(3).build(),
                new JdbcConnectionOptions.JdbcConnectionOptionsBuilder()
                        .withUrl(chUrl)
                        .withDriverName("com.clickhouse.jdbc.ClickHouseDriver")
                        .withUsername(chUser)
                        .withPassword(chPass)
                        .build()
        );

        allHits.addSink(jdbcSink).name("clickhouse-risk-events");

        env.execute("oddsmaker-risk-job");
    }

    private static String subjectType(RiskInput i) {
        return i.userId != null && !i.userId.isEmpty() ? "PLAYER" : "DEVICE";
    }

    private static String subjectId(RiskInput i) {
        return i.userId != null && !i.userId.isEmpty() ? i.userId : i.deviceId;
    }

    private static String subjectKey(RiskInput i) {
        return subjectType(i) + ":" + subjectId(i);
    }

    private static String str(Object v) { return v == null ? null : v.toString(); }

    private static String nz(String s) { return s == null ? "" : s; }

    private static BigDecimal parseAmount(Object v) {
        if (v == null) return null;
        try { return new BigDecimal(v.toString()); } catch (Exception e) { return null; }
    }

    private static String toJson(RiskHit h) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"game_id\":\"").append(h.gameId).append("\"");
        sb.append(",\"environment\":\"").append(h.environment).append("\"");
        sb.append(",\"ts\":").append(h.ts.getTime());
        sb.append(",\"risk_event_id\":\"").append(h.riskEventId).append("\"");
        sb.append(",\"source_event_id\":\"").append(h.sourceEventId).append("\"");
        sb.append(",\"rule_id\":\"").append(h.ruleId).append("\"");
        sb.append(",\"risk_type\":\"").append(h.riskType).append("\"");
        sb.append(",\"severity\":\"").append(h.severity).append("\"");
        sb.append(",\"subject_type\":\"").append(h.subjectType).append("\"");
        sb.append(",\"subject_id\":\"").append(h.subjectId).append("\"");
        sb.append(",\"score\":").append(h.score);
        sb.append(",\"action\":\"").append(h.action).append("\"");
        sb.append(",\"reason\":\"").append(h.reason.replace("\"", "\\\"")).append("\"");
        sb.append(",\"evidence\":{");
        boolean first = true;
        for (Map.Entry<String, String> e : h.evidence.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(e.getKey()).append("\":\"").append(e.getValue().replace("\"", "\\\"")).append("\"");
            first = false;
        }
        sb.append("}}");
        return sb.toString();
    }

    public static final class RiskInput {
        public String gameId;
        public String environment;
        public String eventId;
        public String eventName;
        public String eventType;
        public String userId;
        public String deviceId;
        public String clientIp;
        public Timestamp ts;
        public BigDecimal amount;
    }

    public static final class RiskHit {
        public String gameId;
        public String environment;
        public Timestamp ts;
        public String riskEventId;
        public String sourceEventId;
        public String ruleId;
        public String riskType;
        public String severity;
        public String subjectType;
        public String subjectId;
        public float score;
        public String action;
        public String reason;
        public Map<String, String> evidence;

        public RiskHit() {}

        public RiskHit(String gameId, String environment, Timestamp ts, String riskEventId, String sourceEventId,
                       String ruleId, String riskType, String severity, String subjectType, String subjectId,
                       float score, String action, String reason, Map<String, String> evidence) {
            this.gameId = gameId;
            this.environment = environment;
            this.ts = ts;
            this.riskEventId = riskEventId;
            this.sourceEventId = sourceEventId;
            this.ruleId = ruleId;
            this.riskType = riskType;
            this.severity = severity;
            this.subjectType = subjectType;
            this.subjectId = subjectId;
            this.score = score;
            this.action = action;
            this.reason = reason;
            this.evidence = evidence;
        }
    }
}
