package io.oddsmaker.jobs.risk;

import io.oddsmaker.jobs.enrich.ApicurioAvroFlinkDeserializer;
import io.oddsmaker.jobs.enrich.RawEvent;
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
import java.math.RoundingMode;
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

        int freqWindowMin = Integer.parseInt(System.getProperty("risk.frequency.window-minutes", "10"));
        String controlUrl = System.getProperty("control.url", "http://localhost:8085");
        String controlGameId = System.getProperty("control.gameId", "default");
        String controlToken = System.getProperty("control.token", "");
        long ruleRefreshMs = Long.parseLong(System.getProperty("rule.refresh-ms", "60000"));

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        KafkaSource<RawEvent> source = KafkaSource.<RawEvent>builder()
                .setBootstrapServers(bootstrap)
                .setTopics(sourceTopic)
                .setGroupId("oddsmaker-risk-job")
                .setStartingOffsets(OffsetsInitializer.latest())
                .setDeserializer(new ApicurioAvroFlinkDeserializer(registry))
                .build();

        WatermarkStrategy<RawEvent> wm = WatermarkStrategy.<RawEvent>forBoundedOutOfOrderness(Duration.ofMinutes(2))
                .withTimestampAssigner((r, ts) -> {
                    Long s = r.ts_server;
                    Long c = r.ts_client;
                    long micros = s != null ? s : (c != null ? c : System.currentTimeMillis() * 1000L);
                    return micros / 1000L;
                });

        DataStream<RawEvent> raw = env.fromSource(source, wm, "events-raw");

        DataStream<RiskInput> inputs = raw.flatMap((FlatMapFunction<RawEvent, RiskInput>) (r, out) -> {
            String gameId = str(r.game_id);
            String environmentName = str(r.environment);
            String eventId = str(r.event_id);
            String eventName = str(r.event_name);
            String userId = nz(str(r.user_id));
            String deviceId = str(r.device_id);
            if (gameId == null || environmentName == null || eventId == null || deviceId == null) return;
            String eventType = nz(str(r.event_type));
            BigDecimal amount = parseAmount(r.resource_amount);
            RiskInput in = new RiskInput();
            in.gameId = gameId;
            in.environment = environmentName;
            in.eventId = eventId;
            in.eventName = eventName;
            in.eventType = eventType;
            in.userId = userId;
            in.deviceId = deviceId;
            in.clientIp = nz(str(r.client_ip));
            in.ts = new Timestamp(System.currentTimeMillis());
            Long tsServer = r.ts_server;
            if (tsServer != null) in.ts = new Timestamp(tsServer / 1000L);
            in.amount = amount;
            in.flowType = nz(r.flow_type);
            in.receiptKey = firstNonBlank(r.receipt_hash, r.order_id);
            in.revenueAmount = parseAmount(r.revenue_amount);
            String adFormat = nz(r.ad_format);
            // schema 无 game_event_type 字段（原 GenericRecord 取值恒 null）
            String gameEventType = "";
            in.adReward = "rewarded".equalsIgnoreCase(adFormat)
                    || "ad_reward".equalsIgnoreCase(gameEventType)
                    || (eventName != null && eventName.contains("ad_reward"));
            out.collect(in);
        }).returns(Types.POJO(RiskInput.class));

        DataStream<RiskHit> thresholdHits = inputs
                .filter(i -> {
                    RuleConfig.RuleSpec spec = RuleConfig.byType("THRESHOLD");
                    return i.amount != null && i.amount.compareTo(BigDecimal.valueOf(spec.triggerThreshold)) > 0;
                })
                .map(i -> {
                    RuleConfig.RuleSpec spec = RuleConfig.byType("THRESHOLD");
                    Map<String, String> ev = new HashMap<>();
                    ev.put("resource_amount", i.amount.toPlainString());
                    ev.put("event_name", i.eventName);
                    return new RiskHit(
                            i.gameId, i.environment, i.ts, UUID.randomUUID().toString(), i.eventId,
                            spec.ruleId != null ? spec.ruleId : "risk-threshold-amount", "THRESHOLD", spec.riskLevel,
                            subjectType(i), subjectId(i),
                            spec.riskScore, spec.actionType,
                            "resource amount " + i.amount.toPlainString() + " exceeds threshold " + spec.triggerThreshold,
                            ev);
                }).returns(Types.POJO(RiskHit.class));

        DataStream<RiskHit> frequencyHits = inputs
                .keyBy(i -> i.gameId + "|" + i.environment + "|" + subjectKey(i))
                .window(SlidingEventTimeWindows.of(Time.minutes(freqWindowMin), Time.minutes(freqWindowMin / 2 > 0 ? freqWindowMin / 2 : 1)))
                .process(new ProcessWindowFunction<RiskInput, RiskHit, String, TimeWindow>() {
                    @Override
                    public void open(org.apache.flink.configuration.Configuration parameters) {
                        RuleFetcher.startOnce(controlUrl, controlGameId, controlToken, ruleRefreshMs);
                    }
                    @Override
                    public void process(String key, Context ctx, Iterable<RiskInput> events, Collector<RiskHit> out) {
                        RuleConfig.RuleSpec spec = RuleConfig.byType("FREQUENCY");
                        long count = 0;
                        RiskInput last = null;
                        for (RiskInput e : events) {
                            count++;
                            last = e;
                        }
                        int limit = spec.triggerThreshold;
                        if (count > limit && last != null) {
                            Map<String, String> ev = new HashMap<>();
                            ev.put("window_events", String.valueOf(count));
                            ev.put("window_minutes", String.valueOf(freqWindowMin));
                            ev.put("subject", subjectKey(last));
                            out.collect(new RiskHit(
                                    last.gameId, last.environment, last.ts, UUID.randomUUID().toString(), last.eventId,
                                    spec.ruleId != null ? spec.ruleId : "risk-frequency-burst", "FREQUENCY", spec.riskLevel,
                                    subjectType(last), subjectId(last),
                                    spec.riskScore, spec.actionType,
                                    "event burst " + count + " in " + freqWindowMin + "min (limit " + limit + ")",
                                    ev));
                        }
                    }
                }).returns(Types.POJO(RiskHit.class));

        DataStream<RiskHit> velocityHits = inputs
                .filter(i -> i.amount != null && i.amount.compareTo(BigDecimal.ZERO) > 0)
                .keyBy(i -> i.gameId + "|" + i.environment + "|" + subjectKey(i))
                .window(SlidingEventTimeWindows.of(Time.minutes(freqWindowMin), Time.minutes(freqWindowMin / 2 > 0 ? freqWindowMin / 2 : 1)))
                .process(new ProcessWindowFunction<RiskInput, RiskHit, String, TimeWindow>() {
                    @Override
                    public void open(org.apache.flink.configuration.Configuration parameters) {
                        RuleFetcher.startOnce(controlUrl, controlGameId, controlToken, ruleRefreshMs);
                    }
                    @Override
                    public void process(String key, Context ctx, Iterable<RiskInput> events, Collector<RiskHit> out) {
                        RuleConfig.RuleSpec spec = RuleConfig.byType("VELOCITY");
                        BigDecimal sum = BigDecimal.ZERO;
                        long count = 0;
                        RiskInput last = null;
                        for (RiskInput e : events) {
                            sum = sum.add(e.amount);
                            count++;
                            last = e;
                        }
                        BigDecimal limit = BigDecimal.valueOf(spec.triggerThreshold);
                        if (sum.compareTo(limit) > 0 && last != null) {
                            Map<String, String> ev = new HashMap<>();
                            ev.put("window_sum", sum.toPlainString());
                            ev.put("window_events", String.valueOf(count));
                            ev.put("window_minutes", String.valueOf(freqWindowMin));
                            ev.put("subject", subjectKey(last));
                            out.collect(new RiskHit(
                                    last.gameId, last.environment, last.ts, UUID.randomUUID().toString(), last.eventId,
                                    spec.ruleId != null ? spec.ruleId : "risk-velocity-amount", "VELOCITY", spec.riskLevel,
                                    subjectType(last), subjectId(last),
                                    spec.riskScore, spec.actionType,
                                    "resource velocity " + sum.toPlainString() + " in " + freqWindowMin + "min exceeds " + limit.toPlainString(),
                                    ev));
                        }
                    }
                }).returns(Types.POJO(RiskHit.class));

        DataStream<RiskHit> ratioHits = inputs
                .filter(i -> i.amount != null && i.amount.compareTo(BigDecimal.ZERO) > 0
                        && ("source".equals(i.flowType) || "sink".equals(i.flowType)))
                .keyBy(i -> i.gameId + "|" + i.environment + "|" + subjectKey(i))
                .window(SlidingEventTimeWindows.of(Time.minutes(freqWindowMin), Time.minutes(freqWindowMin / 2 > 0 ? freqWindowMin / 2 : 1)))
                .process(new ProcessWindowFunction<RiskInput, RiskHit, String, TimeWindow>() {
                    @Override
                    public void open(org.apache.flink.configuration.Configuration parameters) {
                        RuleFetcher.startOnce(controlUrl, controlGameId, controlToken, ruleRefreshMs);
                    }
                    @Override
                    public void process(String key, Context ctx, Iterable<RiskInput> events, Collector<RiskHit> out) {
                        RuleConfig.RuleSpec spec = RuleConfig.byType("RATIO");
                        BigDecimal sourceSum = BigDecimal.ZERO;
                        BigDecimal sinkSum = BigDecimal.ZERO;
                        RiskInput last = null;
                        for (RiskInput e : events) {
                            if ("source".equals(e.flowType)) sourceSum = sourceSum.add(e.amount);
                            else if ("sink".equals(e.flowType)) sinkSum = sinkSum.add(e.amount);
                            last = e;
                        }
                        if (sinkSum.compareTo(BigDecimal.ZERO) > 0 && last != null) {
                            BigDecimal ratio = sourceSum.divide(sinkSum, 2, RoundingMode.HALF_UP);
                            BigDecimal limit = BigDecimal.valueOf(spec.triggerThreshold);
                            if (ratio.compareTo(limit) > 0) {
                                Map<String, String> ev = new HashMap<>();
                                ev.put("source_sum", sourceSum.toPlainString());
                                ev.put("sink_sum", sinkSum.toPlainString());
                                ev.put("ratio", ratio.toPlainString());
                                ev.put("window_minutes", String.valueOf(freqWindowMin));
                                ev.put("subject", subjectKey(last));
                                out.collect(new RiskHit(
                                        last.gameId, last.environment, last.ts, UUID.randomUUID().toString(), last.eventId,
                                        spec.ruleId != null ? spec.ruleId : "risk-ratio-source-sink", "RATIO", spec.riskLevel,
                                        subjectType(last), subjectId(last),
                                        spec.riskScore, spec.actionType,
                                        "source/sink ratio " + ratio.toPlainString() + " in " + freqWindowMin + "min exceeds " + limit.toPlainString(),
                                        ev));
                            }
                        }
                    }
                }).returns(Types.POJO(RiskHit.class));

        DataStream<RiskHit> duplicateReceiptHits = inputs
                .filter(i -> i.receiptKey != null && !i.receiptKey.isEmpty())
                .keyBy(i -> i.gameId + "|" + i.environment + "|" + subjectKey(i) + "|" + i.receiptKey)
                .window(SlidingEventTimeWindows.of(Time.minutes(freqWindowMin), Time.minutes(freqWindowMin / 2 > 0 ? freqWindowMin / 2 : 1)))
                .process(new ProcessWindowFunction<RiskInput, RiskHit, String, TimeWindow>() {
                    @Override
                    public void open(org.apache.flink.configuration.Configuration parameters) {
                        RuleFetcher.startOnce(controlUrl, controlGameId, controlToken, ruleRefreshMs);
                    }
                    @Override
                    public void process(String key, Context ctx, Iterable<RiskInput> events, Collector<RiskHit> out) {
                        RuleConfig.RuleSpec spec = RuleConfig.byType("DUPLICATE_RECEIPT");
                        long count = 0;
                        RiskInput last = null;
                        for (RiskInput e : events) {
                            count++;
                            last = e;
                        }
                        if (count >= spec.triggerThreshold && last != null) {
                            Map<String, String> ev = new HashMap<>();
                            ev.put("receipt_key", last.receiptKey);
                            ev.put("occurrences", String.valueOf(count));
                            ev.put("window_minutes", String.valueOf(freqWindowMin));
                            ev.put("subject", subjectKey(last));
                            out.collect(new RiskHit(
                                    last.gameId, last.environment, last.ts, UUID.randomUUID().toString(), last.eventId,
                                    spec.ruleId != null ? spec.ruleId : "risk-duplicate-receipt", "DUPLICATE_RECEIPT", spec.riskLevel,
                                    subjectType(last), subjectId(last),
                                    spec.riskScore, spec.actionType,
                                    "receipt " + last.receiptKey + " submitted " + count + " times in " + freqWindowMin + "min",
                                    ev));
                        }
                    }
                }).returns(Types.POJO(RiskHit.class));

        DataStream<RiskHit> adRewardHits = inputs
                .filter(RiskJob::isAdReward)
                .keyBy(i -> i.gameId + "|" + i.environment + "|" + subjectKey(i))
                .window(SlidingEventTimeWindows.of(Time.minutes(freqWindowMin), Time.minutes(freqWindowMin / 2 > 0 ? freqWindowMin / 2 : 1)))
                .process(new ProcessWindowFunction<RiskInput, RiskHit, String, TimeWindow>() {
                    @Override
                    public void open(org.apache.flink.configuration.Configuration parameters) {
                        RuleFetcher.startOnce(controlUrl, controlGameId, controlToken, ruleRefreshMs);
                    }
                    @Override
                    public void process(String key, Context ctx, Iterable<RiskInput> events, Collector<RiskHit> out) {
                        RuleConfig.RuleSpec spec = RuleConfig.byType("AD_REWARD");
                        long count = 0;
                        BigDecimal revenueSum = BigDecimal.ZERO;
                        RiskInput last = null;
                        for (RiskInput e : events) {
                            count++;
                            if (e.revenueAmount != null) revenueSum = revenueSum.add(e.revenueAmount);
                            last = e;
                        }
                        if (count > spec.triggerThreshold && last != null) {
                            Map<String, String> ev = new HashMap<>();
                            ev.put("ad_reward_count", String.valueOf(count));
                            ev.put("revenue_sum", revenueSum.toPlainString());
                            ev.put("window_minutes", String.valueOf(freqWindowMin));
                            ev.put("subject", subjectKey(last));
                            out.collect(new RiskHit(
                                    last.gameId, last.environment, last.ts, UUID.randomUUID().toString(), last.eventId,
                                    spec.ruleId != null ? spec.ruleId : "risk-ad-reward-abuse", "AD_REWARD", spec.riskLevel,
                                    subjectType(last), subjectId(last),
                                    spec.riskScore, spec.actionType,
                                    "ad reward burst " + count + " in " + freqWindowMin + "min (limit " + spec.triggerThreshold + ")",
                                    ev));
                        }
                    }
                }).returns(Types.POJO(RiskHit.class));

        DataStream<RiskHit> allHits = thresholdHits.union(frequencyHits).union(velocityHits).union(ratioHits)
                .union(duplicateReceiptHits).union(adRewardHits);

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

    static String str(Object v) { return v == null ? null : v.toString(); }

    static String nz(String s) { return s == null ? "" : s; }

    static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    static boolean isAdReward(RiskInput i) { return i.adReward; }

    static BigDecimal parseAmount(Object v) {
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
        public String flowType;
        /** 收据键：receipt_hash 优先，退化 order_id；null 表示非收据事件 */
        public String receiptKey;
        public BigDecimal revenueAmount;
        /** 是否为激励广告 reward 事件 */
        public boolean adReward;
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
