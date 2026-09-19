package io.oddsmaker.jobs.risk;

import io.oddsmaker.jobs.enrich.ApicurioAvroFlinkDeserializer;
import io.oddsmaker.jobs.enrich.RawEvent;
import org.apache.flink.api.common.eventtime.SerializableTimestampAssigner;
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
import org.apache.flink.streaming.api.datastream.WindowedStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
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

    static final String JOB_NAME = "oddsmaker-risk-job";

    /** 入口：解析程序参数 → 读配置 → 搭管道 → 触发执行（execute 才真正连接 source/sink）。 */
    public static void main(String[] args) throws Exception {
        parseArgs(args);
        buildPipeline(StreamExecutionEnvironment.getExecutionEnvironment(), config()).getExecutionEnvironment().execute(JOB_NAME);
    }

    /**
     * --key=value 程序参数 → System properties（config() 只读系统属性）。
     * Flink REST /jars/{id}/run 的 programArgs 即走此途径；-D 系统属性途径不受影响，
     * 且程序参数优先级更高（后 setProperty 覆盖）。非 -- 前缀或无 = 的参数忽略。
     */
    static void parseArgs(String[] args) {
        if (args == null) return;
        for (String arg : args) {
            if (arg != null && arg.startsWith("--")) {
                int eq = arg.indexOf('=');
                if (eq > 2) {
                    System.setProperty(arg.substring(2, eq), arg.substring(eq + 1));
                }
            }
        }
    }

    /** 配置读取（System properties，单测可 setProperty 后直测）。顺序见 buildPipeline。 */
    static Object[] config() {
        return new Object[]{
                System.getProperty("kafka.bootstrap", "localhost:9092"),
                System.getProperty("registry.url", "http://localhost:8081/apis/registry/v2"),
                System.getProperty("kafka.topic", "oddsmaker.events_raw"),
                System.getProperty("risk.topic", "oddsmaker.risk_events"),
                System.getProperty("clickhouse.url", "jdbc:clickhouse://localhost:8123/default"),
                System.getProperty("clickhouse.user", "default"),
                System.getProperty("clickhouse.pass", ""),
                Integer.parseInt(System.getProperty("risk.frequency.window-minutes", "10")),
                System.getProperty("control.url", "http://localhost:8085"),
                System.getProperty("control.gameId", "default"),
                System.getProperty("control.token", ""),
                Long.parseLong(System.getProperty("rule.refresh-ms", "60000")),
        };
    }

    /** 有界乱序水位线（2 分钟），时间戳取 ts_server → ts_client → now。 */
    static WatermarkStrategy<RawEvent> watermarks() {
        return WatermarkStrategy.<RawEvent>forBoundedOutOfOrderness(Duration.ofMinutes(2))
                .withTimestampAssigner((SerializableTimestampAssigner<RawEvent>) (r, recordTimestamp) -> {
                    Long s = r.ts_server;
                    Long c = r.ts_client;
                    long micros = s != null ? s : (c != null ? c : System.currentTimeMillis() * 1000L);
                    return micros / 1000L;
                });
    }

    /** 滑动窗口步长：窗口一半，最小 1 分钟。 */
    static int slideMinutes(int windowMin) {
        return windowMin / 2 > 0 ? windowMin / 2 : 1;
    }

    /**
     * 搭建六类风控规则管道（惰性：source/sink 均到 execute 才连接，单测可用本地环境直跑）。
     */
    static DataStream<RiskHit> buildPipeline(StreamExecutionEnvironment env, Object[] cfg) {
        String bootstrap = (String) cfg[0];
        String registry = (String) cfg[1];
        String sourceTopic = (String) cfg[2];
        String riskTopic = (String) cfg[3];
        String chUrl = (String) cfg[4];
        String chUser = (String) cfg[5];
        String chPass = (String) cfg[6];
        int freqWindowMin = (Integer) cfg[7];
        RuleSource rules = new RuleSource((String) cfg[8], (String) cfg[9], (String) cfg[10], (Long) cfg[11]);

        // local executor 默认并行度=CPU 核数，而 events_raw 只有 1 个分区：
        // 多余的空 source subtask 会把全局 watermark 卡死，影响窗口类规则。
        // 显式置 1；集群模式提交时用 flink run -p 覆盖
        env.setParallelism(1);

        KafkaSource<RawEvent> source = KafkaSource.<RawEvent>builder()
                .setBootstrapServers(bootstrap)
                .setTopics(sourceTopic)
                .setGroupId("oddsmaker-risk-job")
                .setStartingOffsets(OffsetsInitializer.latest())
                .setDeserializer(new ApicurioAvroFlinkDeserializer(registry))
                .build();

        DataStream<RawEvent> raw = env.fromSource(source, watermarks(), "events-raw");

        DataStream<RiskInput> inputs = raw.flatMap(inputMapper()).returns(Types.POJO(RiskInput.class));

        DataStream<RiskHit> thresholdHits = inputs
                .filter(RiskJob::overThreshold)
                .map(RiskJob::thresholdHit)
                .returns(Types.POJO(RiskHit.class));

        DataStream<RiskHit> frequencyHits = windowedBySubject(inputs, freqWindowMin)
                .process(new FrequencyFunction(freqWindowMin, rules))
                .returns(Types.POJO(RiskHit.class));

        DataStream<RiskHit> velocityHits = windowedBySubject(inputs.filter(RiskJob::hasAmount), freqWindowMin)
                .process(new VelocityFunction(freqWindowMin, rules))
                .returns(Types.POJO(RiskHit.class));

        DataStream<RiskHit> ratioHits = windowedBySubject(inputs.filter(RiskJob::isFlowAmount), freqWindowMin)
                .process(new RatioFunction(freqWindowMin, rules))
                .returns(Types.POJO(RiskHit.class));

        DataStream<RiskHit> duplicateReceiptHits = windowedByReceipt(inputs.filter(RiskJob::hasReceipt), freqWindowMin)
                .process(new DuplicateReceiptFunction(freqWindowMin, rules))
                .returns(Types.POJO(RiskHit.class));

        DataStream<RiskHit> adRewardHits = windowedBySubject(inputs.filter(RiskJob::isAdReward), freqWindowMin)
                .process(new AdRewardFunction(freqWindowMin, rules))
                .returns(Types.POJO(RiskHit.class));

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
                RiskJob::bindRiskHit,
                JdbcExecutionOptions.builder().withBatchIntervalMs(500).withBatchSize(500).withMaxRetries(3).build(),
                new JdbcConnectionOptions.JdbcConnectionOptionsBuilder()
                        .withUrl(chUrl)
                        .withDriverName("com.clickhouse.jdbc.ClickHouseDriver")
                        .withUsername(chUser)
                        .withPassword(chPass)
                        .build()
        );

        allHits.addSink(jdbcSink).name("clickhouse-risk-events");

        return allHits;
    }

    /** 按主体（玩家/设备）分键的滑动窗口。 */
    private static WindowedStream<RiskInput, String, TimeWindow> windowedBySubject(DataStream<RiskInput> inputs, int windowMin) {
        return inputs
                .keyBy(RiskJob::subjectWindowKey)
                .window(SlidingEventTimeWindows.of(Time.minutes(windowMin), Time.minutes(slideMinutes(windowMin))));
    }

    /** 按主体 + 收据键分键的滑动窗口（重复收据检测）。 */
    private static WindowedStream<RiskInput, String, TimeWindow> windowedByReceipt(DataStream<RiskInput> inputs, int windowMin) {
        return inputs
                .keyBy(RiskJob::receiptWindowKey)
                .window(SlidingEventTimeWindows.of(Time.minutes(windowMin), Time.minutes(slideMinutes(windowMin))));
    }

    /** RawEvent → RiskInput 的算子封装（null 表示丢弃）。 */
    static FlatMapFunction<RawEvent, RiskInput> inputMapper() {
        return (r, out) -> {
            RiskInput in = toRiskInput(r);
            if (in != null) out.collect(in);
        };
    }

    /** RawEvent → 风控输入；必填（game/environment/event/device）缺失返回 null。 */
    static RiskInput toRiskInput(RawEvent r) {
        String gameId = str(r.game_id);
        String environmentName = str(r.environment);
        String eventId = str(r.event_id);
        String eventName = str(r.event_name);
        String userId = nz(str(r.user_id));
        String deviceId = str(r.device_id);
        if (gameId == null || environmentName == null || eventId == null || deviceId == null) return null;
        RiskInput in = new RiskInput();
        in.gameId = gameId;
        in.environment = environmentName;
        in.eventId = eventId;
        in.eventName = eventName;
        in.eventType = nz(str(r.event_type));
        in.userId = userId;
        in.deviceId = deviceId;
        in.clientIp = nz(str(r.client_ip));
        in.ts = new Timestamp(System.currentTimeMillis());
        Long tsServer = r.ts_server;
        if (tsServer != null) in.ts = new Timestamp(tsServer / 1000L);
        in.amount = parseAmount(r.resource_amount);
        in.flowType = nz(r.flow_type);
        in.receiptKey = firstNonBlank(r.receipt_hash, r.order_id);
        in.revenueAmount = parseAmount(r.revenue_amount);
        String adFormat = nz(r.ad_format);
        // schema 无 game_event_type 字段（原 GenericRecord 取值恒 null）
        String gameEventType = "";
        in.adReward = "rewarded".equalsIgnoreCase(adFormat)
                || "ad_reward".equalsIgnoreCase(gameEventType)
                || (eventName != null && eventName.contains("ad_reward"));
        return in;
    }

    /** THRESHOLD 过滤：资源量严格大于阈值。 */
    static boolean overThreshold(RiskInput i) {
        RuleConfig.RuleSpec spec = RuleConfig.byType("THRESHOLD");
        return i.amount != null && i.amount.compareTo(BigDecimal.valueOf(spec.triggerThreshold)) > 0;
    }

    /** THRESHOLD 命中构造。 */
    static RiskHit thresholdHit(RiskInput i) {
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
    }

    /** VELOCITY 口径：携带正数金额。 */
    static boolean hasAmount(RiskInput i) {
        return i.amount != null && i.amount.compareTo(BigDecimal.ZERO) > 0;
    }

    /** RATIO 口径：正数金额且 flow 为 source/sink。 */
    static boolean isFlowAmount(RiskInput i) {
        return i.amount != null && i.amount.compareTo(BigDecimal.ZERO) > 0
                && ("source".equals(i.flowType) || "sink".equals(i.flowType));
    }

    /** DUPLICATE_RECEIPT 口径：携带非空收据键。 */
    static boolean hasReceipt(RiskInput i) {
        return i.receiptKey != null && !i.receiptKey.isEmpty();
    }

    /** FREQUENCY：窗口内事件数超阈值告警。 */
    static class FrequencyFunction extends ProcessWindowFunction<RiskInput, RiskHit, String, TimeWindow> {
        private final int windowMin;
        private final RuleSource rules;

        FrequencyFunction(int windowMin, RuleSource rules) {
            this.windowMin = windowMin;
            this.rules = rules;
        }

        @Override
        public void open(org.apache.flink.configuration.Configuration parameters) {
            RuleFetcher.startOnce(rules.controlUrl, rules.gameId, rules.token, rules.refreshMs);
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
                ev.put("window_minutes", String.valueOf(windowMin));
                ev.put("subject", subjectKey(last));
                out.collect(new RiskHit(
                        last.gameId, last.environment, last.ts, UUID.randomUUID().toString(), last.eventId,
                        spec.ruleId != null ? spec.ruleId : "risk-frequency-burst", "FREQUENCY", spec.riskLevel,
                        subjectType(last), subjectId(last),
                        spec.riskScore, spec.actionType,
                        "event burst " + count + " in " + windowMin + "min (limit " + limit + ")",
                        ev));
            }
        }
    }

    /** VELOCITY：窗口内资源总量超阈值告警。 */
    static class VelocityFunction extends ProcessWindowFunction<RiskInput, RiskHit, String, TimeWindow> {
        private final int windowMin;
        private final RuleSource rules;

        VelocityFunction(int windowMin, RuleSource rules) {
            this.windowMin = windowMin;
            this.rules = rules;
        }

        @Override
        public void open(org.apache.flink.configuration.Configuration parameters) {
            RuleFetcher.startOnce(rules.controlUrl, rules.gameId, rules.token, rules.refreshMs);
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
                ev.put("window_minutes", String.valueOf(windowMin));
                ev.put("subject", subjectKey(last));
                out.collect(new RiskHit(
                        last.gameId, last.environment, last.ts, UUID.randomUUID().toString(), last.eventId,
                        spec.ruleId != null ? spec.ruleId : "risk-velocity-amount", "VELOCITY", spec.riskLevel,
                        subjectType(last), subjectId(last),
                        spec.riskScore, spec.actionType,
                        "resource velocity " + sum.toPlainString() + " in " + windowMin + "min exceeds " + limit.toPlainString(),
                        ev));
            }
        }
    }

    /** RATIO：窗口内 source/sink 金额比超阈值告警。 */
    static class RatioFunction extends ProcessWindowFunction<RiskInput, RiskHit, String, TimeWindow> {
        private final int windowMin;
        private final RuleSource rules;

        RatioFunction(int windowMin, RuleSource rules) {
            this.windowMin = windowMin;
            this.rules = rules;
        }

        @Override
        public void open(org.apache.flink.configuration.Configuration parameters) {
            RuleFetcher.startOnce(rules.controlUrl, rules.gameId, rules.token, rules.refreshMs);
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
                    ev.put("window_minutes", String.valueOf(windowMin));
                    ev.put("subject", subjectKey(last));
                    out.collect(new RiskHit(
                            last.gameId, last.environment, last.ts, UUID.randomUUID().toString(), last.eventId,
                            spec.ruleId != null ? spec.ruleId : "risk-ratio-source-sink", "RATIO", spec.riskLevel,
                            subjectType(last), subjectId(last),
                            spec.riskScore, spec.actionType,
                            "source/sink ratio " + ratio.toPlainString() + " in " + windowMin + "min exceeds " + limit.toPlainString(),
                            ev));
                }
            }
        }
    }

    /** DUPLICATE_RECEIPT：同一收据键在窗口内出现次数达到阈值。 */
    static class DuplicateReceiptFunction extends ProcessWindowFunction<RiskInput, RiskHit, String, TimeWindow> {
        private final int windowMin;
        private final RuleSource rules;

        DuplicateReceiptFunction(int windowMin, RuleSource rules) {
            this.windowMin = windowMin;
            this.rules = rules;
        }

        @Override
        public void open(org.apache.flink.configuration.Configuration parameters) {
            RuleFetcher.startOnce(rules.controlUrl, rules.gameId, rules.token, rules.refreshMs);
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
                ev.put("window_minutes", String.valueOf(windowMin));
                ev.put("subject", subjectKey(last));
                out.collect(new RiskHit(
                        last.gameId, last.environment, last.ts, UUID.randomUUID().toString(), last.eventId,
                        spec.ruleId != null ? spec.ruleId : "risk-duplicate-receipt", "DUPLICATE_RECEIPT", spec.riskLevel,
                        subjectType(last), subjectId(last),
                        spec.riskScore, spec.actionType,
                        "receipt " + last.receiptKey + " submitted " + count + " times in " + windowMin + "min",
                        ev));
            }
        }
    }

    /** AD_REWARD：激励广告 reward 事件窗口内爆发。 */
    static class AdRewardFunction extends ProcessWindowFunction<RiskInput, RiskHit, String, TimeWindow> {
        private final int windowMin;
        private final RuleSource rules;

        AdRewardFunction(int windowMin, RuleSource rules) {
            this.windowMin = windowMin;
            this.rules = rules;
        }

        @Override
        public void open(org.apache.flink.configuration.Configuration parameters) {
            RuleFetcher.startOnce(rules.controlUrl, rules.gameId, rules.token, rules.refreshMs);
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
                ev.put("window_minutes", String.valueOf(windowMin));
                ev.put("subject", subjectKey(last));
                out.collect(new RiskHit(
                        last.gameId, last.environment, last.ts, UUID.randomUUID().toString(), last.eventId,
                        spec.ruleId != null ? spec.ruleId : "risk-ad-reward-abuse", "AD_REWARD", spec.riskLevel,
                        subjectType(last), subjectId(last),
                        spec.riskScore, spec.actionType,
                        "ad reward burst " + count + " in " + windowMin + "min (limit " + spec.triggerThreshold + ")",
                        ev));
            }
        }
    }

    /** 控制面规则拉取参数（窗口算子 open 时启动单例 fetcher）。 */
    static class RuleSource implements java.io.Serializable {
        final String controlUrl;
        final String gameId;
        final String token;
        final long refreshMs;

        RuleSource(String controlUrl, String gameId, String token, long refreshMs) {
            this.controlUrl = controlUrl;
            this.gameId = gameId;
            this.token = token;
            this.refreshMs = refreshMs;
        }
    }

    /** risk_events 表写入绑定（14 列）。 */
    static void bindRiskHit(java.sql.PreparedStatement ps, RiskHit h) throws java.sql.SQLException {
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
    }

    static String subjectType(RiskInput i) {
        return i.userId != null && !i.userId.isEmpty() ? "PLAYER" : "DEVICE";
    }

    static String subjectId(RiskInput i) {
        return i.userId != null && !i.userId.isEmpty() ? i.userId : i.deviceId;
    }

    static String subjectKey(RiskInput i) {
        return subjectType(i) + ":" + subjectId(i);
    }

    /** FREQUENCY/VELOCITY/RATIO/AD_REWARD 的分键：game|env|subject。 */
    static String subjectWindowKey(RiskInput i) {
        return i.gameId + "|" + i.environment + "|" + subjectKey(i);
    }

    /** DUPLICATE_RECEIPT 的分键：主体键 + 收据键。 */
    static String receiptWindowKey(RiskInput i) {
        return subjectWindowKey(i) + "|" + i.receiptKey;
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

    static String toJson(RiskHit h) {
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
