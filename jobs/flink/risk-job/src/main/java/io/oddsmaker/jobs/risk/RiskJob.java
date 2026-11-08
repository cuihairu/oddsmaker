package io.oddsmaker.jobs.risk;

import io.oddsmaker.jobs.enrich.ApicurioAvroFlinkDeserializer;
import io.oddsmaker.jobs.enrich.RawEvent;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.api.common.restartstrategy.RestartStrategies;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.WindowedStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.SinkFunction;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * 风控作业（拆分 LAY-001 后为装配薄壳）：
 * <ul>
 *   <li>{@link #main} / {@link #buildPipeline}：入口与管道装配（唯一保留的核心职责）；</li>
 *   <li>配置/水位线 → {@link RiskJobConfig}；输入映射 → {@link RiskInputMapper}；</li>
 *   <li>8 类规则 + 主体/信任口径 → {@link RiskRules}；评估产出 → {@link RiskScore}；</li>
 *   <li>落库出口 → {@link RiskSinks}；序列化 → {@link RiskJson}；窗口/特征分支 → {@link RiskPipeline}。</li>
 * </ul>
 * 模型（{@link RiskInput}/{@link RiskHit}/{@link RiskScoreRow}/{@link RuleSource}/{@link SeqState}）
 * 与上述类的薄委托面保留在本类，既有单测与兄弟类（FeatureCalc/FeatureRuleFunction）经此访问不变。
 */
public class RiskJob {

    static final String JOB_NAME = RiskJobConfig.JOB_NAME;

    /** 入口：解析程序参数 → 读配置 → 搭管道 → 触发执行（execute 才真正连接 source/sink）。 */
    public static void main(String[] args) throws Exception {
        parseArgs(args);
        buildPipeline(StreamExecutionEnvironment.getExecutionEnvironment(), config()).getExecutionEnvironment().execute(JOB_NAME);
    }

    /**
     * 搭建七类风控规则管道（惰性：source/sink 均到 execute 才连接，单测可用本地环境直跑）。
     * 规则/出口/序列化均引用拆分后的专注类（RiskRules/RiskSinks/RiskJson/RiskScore/RiskPipeline），
     * 本类只做配置读取与接线。
     */
    static DataStream<RiskHit> buildPipeline(StreamExecutionEnvironment env, Object[] cfg) {
        // 容错语义闭环:无 checkpoint 时 KafkaSource 从不提交 offset,重启后按回退策略重新初始化,
        // 停机窗口事件全部丢失且 keyed state 全空。启用 checkpoint 后 offset 随 checkpoint 提交、
        // state 持久恢复、重启从上次 checkpoint 续读(残余风险收敛为 JdbcSink at-least-once 的
        // 检查点间隔窗口内重复,处理层重复由去重/聚合 state 恢复挡住);
        // 回退 LATEST 保持既有首启动语义,避免已部署环境全量重放+state 空造成全量重复
        env.enableCheckpointing(30_000L, CheckpointingMode.AT_LEAST_ONCE);
        env.setRestartStrategy(RestartStrategies.fixedDelayRestart(3,
                org.apache.flink.api.common.time.Time.seconds(10)));
        String bootstrap = (String) cfg[0];
        String registry = (String) cfg[1];
        String sourceTopic = (String) cfg[2];
        String riskTopic = (String) cfg[3];
        String chUrl = (String) cfg[4];
        String chUser = (String) cfg[5];
        String chPass = (String) cfg[6];
        int freqWindowMin = (Integer) cfg[7];
        RuleSource rules = new RuleSource((String) cfg[8], (String) cfg[9], (String) cfg[10], (Long) cfg[11]);
        String pgUrl = (String) cfg[12];
        String pgUser = (String) cfg[13];
        String pgPass = (String) cfg[14];
        int watermarkDelaySeconds = (Integer) cfg[15];
        boolean featuresEnabled = (Boolean) cfg[16];

        // local executor 默认并行度=CPU 核数，而 events_raw 只有 1 个分区：
        // 多余的空 source subtask 会把全局 watermark 卡死，影响窗口类规则。
        // 显式置 1；集群模式提交时用 flink run -p 覆盖
        env.setParallelism(1);

        KafkaSource<RawEvent> source = KafkaSource.<RawEvent>builder()
                .setBootstrapServers(bootstrap)
                .setTopics(sourceTopic)
                .setGroupId("oddsmaker-risk-job")
                .setStartingOffsets(OffsetsInitializer.committedOffsets(OffsetResetStrategy.LATEST))
                .setDeserializer(new ApicurioAvroFlinkDeserializer(registry))
                .build();

        DataStream<RawEvent> raw = env.fromSource(source, RiskJobConfig.watermarks(watermarkDelaySeconds), "events-raw");

        DataStream<RiskInput> inputs = raw.flatMap(RiskInputMapper.inputMapper()).returns(Types.POJO(RiskInput.class));

        DataStream<RiskHit> thresholdHits = inputs
                .filter(RiskRules::overThreshold)
                .map(RiskRules::thresholdHit)
                .returns(Types.POJO(RiskHit.class));

        DataStream<RiskHit> frequencyHits = RiskPipeline.windowedBySubject(inputs, freqWindowMin)
                .process(new RiskRules.FrequencyFunction(freqWindowMin, rules))
                .returns(Types.POJO(RiskHit.class));

        DataStream<RiskHit> velocityHits = RiskPipeline.windowedBySubject(inputs.filter(RiskRules::hasAmount), freqWindowMin)
                .process(new RiskRules.VelocityFunction(freqWindowMin, rules))
                .returns(Types.POJO(RiskHit.class));

        DataStream<RiskHit> ratioHits = RiskPipeline.windowedBySubject(inputs.filter(RiskRules::isFlowAmount), freqWindowMin)
                .process(new RiskRules.RatioFunction(freqWindowMin, rules))
                .returns(Types.POJO(RiskHit.class));

        DataStream<RiskHit> duplicateReceiptHits = RiskPipeline.windowedByReceipt(inputs.filter(RiskRules::hasReceipt), freqWindowMin)
                .process(new RiskRules.DuplicateReceiptFunction(freqWindowMin, rules))
                .returns(Types.POJO(RiskHit.class));

        DataStream<RiskHit> adRewardHits = RiskPipeline.windowedBySubject(inputs.filter(RiskRules::isAdReward), freqWindowMin)
                .process(new RiskRules.AdRewardFunction(freqWindowMin, rules))
                .returns(Types.POJO(RiskHit.class));

        // PATTERN：序列匹配无需开窗，keyed 状态机增量推进（见 PatternFunction）
        DataStream<RiskHit> patternHits = inputs
                .filter(RiskRules::inPatternSequence)
                .keyBy(RiskRules::subjectWindowKey)
                .process(new RiskRules.PatternFunction(rules))
                .returns(Types.POJO(RiskHit.class));

        DataStream<RiskHit> allHits;
        if (featuresEnabled) {
            // B5 特征分支（计划书 §5.1）：事件→特征→规则三段解耦。
            // 特征行按 6 特征滑动窗口聚合 → upsert PostgreSQL risk_features；
            // 同一批特征行广播回评估算子，FEATURE 规则按主体最新特征快照命中。
            DataStream<FeatureRow> featureRows = RiskPipeline.buildFeatureBranch(inputs);

            featureRows.addSink(RiskSinks.featureJdbcSink(pgUrl, pgUser, pgPass)).name("postgres-risk-features");

            // B10：feature_store 摘要双写（control 库，scope×窗口一行）。
            // FeatureRow 是长格式（每特征一行），依赖 SQL 层 jsonb 合并聚成单行；
            // 同名特征迟到重算时 || 取右值，与 risk_features 的 upsert 语义一致
            featureRows.addSink(RiskSinks.featureStoreJdbcSink(pgUrl, pgUser, pgPass)).name("postgres-feature-store");

            DataStream<RiskHit> featureRuleHits = inputs
                    .keyBy(RiskRules::subjectWindowKey)
                    .connect(featureRows.broadcast(FeatureRuleFunction.FEATURE_STATE))
                    .process(new FeatureRuleFunction(rules))
                    .returns(Types.POJO(RiskHit.class));

            allHits = thresholdHits.union(frequencyHits).union(velocityHits).union(ratioHits)
                    .union(duplicateReceiptHits).union(adRewardHits).union(patternHits).union(featureRuleHits);
        } else {
            allHits = thresholdHits.union(frequencyHits).union(velocityHits).union(ratioHits)
                    .union(duplicateReceiptHits).union(adRewardHits).union(patternHits);
        }

        // B6 §5.2：BLOCK 高信任门槛在 union 后统一收敛——非 HIGH 输入的 BLOCK 降级 REVIEW，
        // 下游 Kafka / risk_events / risk_scores 三个出口看到同一份已定的动作
        allHits = allHits.map(RiskRules::applyTrustGate).returns(Types.POJO(RiskHit.class));

        KafkaSink<String> kafkaSink = KafkaSink.<String>builder()
                .setBootstrapServers(bootstrap)
                .setRecordSerializer(KafkaRecordSerializationSchema.builder()
                        .setTopic(riskTopic)
                        .setValueSerializationSchema(new org.apache.flink.api.common.serialization.SimpleStringSchema())
                        .build())
                .build();

        allHits.map(RiskJson::toJson).returns(Types.STRING).sinkTo(kafkaSink).name("kafka-risk-events");

        allHits.addSink(RiskSinks.riskEventsJdbcSink(chUrl, chUser, chPass)).name("clickhouse-risk-events");

        // B6 §5.2：评估产出 risk_scores（主体累计分 + 规则触发明细 JSON），与 risk_events 同源分叉
        DataStream<RiskScoreRow> scores = allHits
                .keyBy(RiskScore::scoreKey)
                .process(new RiskScore.RiskScoreFunction())
                .returns(Types.POJO(RiskScoreRow.class));

        scores.addSink(RiskSinks.riskScoresJdbcSink(chUrl, chUser, chPass)).name("clickhouse-risk-scores");

        return allHits;
    }

    // ===== 委托面（拆分后保留原访问面，既有单测/兄弟类零改动） =====

    static void parseArgs(String[] args) { RiskJobConfig.parseArgs(args); }

    static Object[] config() { return RiskJobConfig.config(); }

    static WatermarkStrategy<RawEvent> watermarks() { return RiskJobConfig.watermarks(); }

    static WatermarkStrategy<RawEvent> watermarks(int delaySeconds) { return RiskJobConfig.watermarks(delaySeconds); }

    static int slideMinutes(int windowMin) { return RiskJobConfig.slideMinutes(windowMin); }

    static String jsonEscape(String s) { return RiskJson.jsonEscape(s); }

    static String toJson(RiskHit h) { return RiskJson.toJson(h); }

    static final String FEATURE_UPSERT_SQL = RiskSinks.FEATURE_UPSERT_SQL;

    static final String FEATURE_STORE_UPSERT_SQL = RiskSinks.FEATURE_STORE_UPSERT_SQL;

    static SinkFunction<FeatureRow> featureJdbcSink(String pgUrl, String pgUser, String pgPass) {
        return RiskSinks.featureJdbcSink(pgUrl, pgUser, pgPass);
    }

    static SinkFunction<FeatureRow> featureStoreJdbcSink(String pgUrl, String pgUser, String pgPass) {
        return RiskSinks.featureStoreJdbcSink(pgUrl, pgUser, pgPass);
    }

    static String featureStoreFragment(FeatureRow r) { return RiskSinks.featureStoreFragment(r); }

    static void bindFeatureStoreRow(java.sql.PreparedStatement ps, FeatureRow r) throws java.sql.SQLException {
        RiskSinks.bindFeatureStoreRow(ps, r);
    }

    static SinkFunction<RiskHit> riskEventsJdbcSink(String chUrl, String chUser, String chPass) {
        return RiskSinks.riskEventsJdbcSink(chUrl, chUser, chPass);
    }

    static SinkFunction<RiskScoreRow> riskScoresJdbcSink(String chUrl, String chUser, String chPass) {
        return RiskSinks.riskScoresJdbcSink(chUrl, chUser, chPass);
    }

    static void bindFeatureRow(java.sql.PreparedStatement ps, FeatureRow r) throws java.sql.SQLException {
        RiskSinks.bindFeatureRow(ps, r);
    }

    static void bindRiskHit(java.sql.PreparedStatement ps, RiskHit h) throws java.sql.SQLException {
        RiskSinks.bindRiskHit(ps, h);
    }

    static void bindRiskScore(java.sql.PreparedStatement ps, RiskScoreRow r) throws java.sql.SQLException {
        RiskSinks.bindRiskScore(ps, r);
    }

    static boolean overThreshold(RiskInput i) { return RiskRules.overThreshold(i); }

    static RiskHit thresholdHit(RiskInput i) { return RiskRules.thresholdHit(i); }

    static boolean hasAmount(RiskInput i) { return RiskRules.hasAmount(i); }

    static boolean isFlowAmount(RiskInput i) { return RiskRules.isFlowAmount(i); }

    static boolean hasReceipt(RiskInput i) { return RiskRules.hasReceipt(i); }

    static boolean isAdReward(RiskInput i) { return RiskRules.isAdReward(i); }

    static boolean inPatternSequence(RiskInput i) { return RiskRules.inPatternSequence(i); }

    static boolean isPlayerSubject(RiskInput i) { return RiskRules.isPlayerSubject(i); }

    static boolean hasIpAccount(RiskInput i) { return RiskRules.hasIpAccount(i); }

    static String ipWindowKey(RiskInput i) { return RiskRules.ipWindowKey(i); }

    static RiskHit advance(RuleConfig.RuleSpec spec, RiskInput i, SeqState st) { return RiskRules.advance(spec, i, st); }

    static RiskHit patternHit(RuleConfig.RuleSpec spec, RiskInput last, List<String> seq, long startTs) {
        return RiskRules.patternHit(spec, last, seq, startTs);
    }

    static String subjectType(RiskInput i) { return RiskRules.subjectType(i); }

    static String subjectId(RiskInput i) { return RiskRules.subjectId(i); }

    static String subjectKey(RiskInput i) { return RiskRules.subjectKey(i); }

    static String subjectWindowKey(RiskInput i) { return RiskRules.subjectWindowKey(i); }

    static String receiptWindowKey(RiskInput i) { return RiskRules.receiptWindowKey(i); }

    static String trustScope(boolean allHigh) { return RiskRules.trustScope(allHigh); }

    static String gateAction(String action, String trustLevel) { return RiskRules.gateAction(action, trustLevel); }

    static RiskHit applyTrustGate(RiskHit h) { return RiskRules.applyTrustGate(h); }

    static String scoreKey(RiskHit h) { return RiskScore.scoreKey(h); }

    static FlatMapFunction<RawEvent, RiskInput> inputMapper() { return RiskInputMapper.inputMapper(); }

    static RiskInput toRiskInput(RawEvent r) { return RiskInputMapper.toRiskInput(r); }

    static String str(Object v) { return RiskInputMapper.str(v); }

    static String nz(String s) { return RiskInputMapper.nz(s); }

    static String firstNonBlank(String... values) { return RiskInputMapper.firstNonBlank(values); }

    static BigDecimal parseAmount(Object v) { return RiskInputMapper.parseAmount(v); }

    static WindowedStream<RiskInput, String, TimeWindow> windowedBySubject(DataStream<RiskInput> inputs, int windowMin) {
        return RiskPipeline.windowedBySubject(inputs, windowMin);
    }

    static WindowedStream<RiskInput, String, TimeWindow> windowedByReceipt(DataStream<RiskInput> inputs, int windowMin) {
        return RiskPipeline.windowedByReceipt(inputs, windowMin);
    }

    static DataStream<FeatureRow> buildFeatureBranch(DataStream<RiskInput> inputs) {
        return RiskPipeline.buildFeatureBranch(inputs);
    }

    // ===== 规则函数壳（子类化迁移实现，既有 `new RiskJob.X(...)` 访问面不变） =====

    /** @see RiskRules.FrequencyFunction */
    static class FrequencyFunction extends RiskRules.FrequencyFunction {
        FrequencyFunction(int windowMin, RuleSource rules) { super(windowMin, rules); }
    }

    /** @see RiskRules.VelocityFunction */
    static class VelocityFunction extends RiskRules.VelocityFunction {
        VelocityFunction(int windowMin, RuleSource rules) { super(windowMin, rules); }
    }

    /** @see RiskRules.RatioFunction */
    static class RatioFunction extends RiskRules.RatioFunction {
        RatioFunction(int windowMin, RuleSource rules) { super(windowMin, rules); }
    }

    /** @see RiskRules.DuplicateReceiptFunction */
    static class DuplicateReceiptFunction extends RiskRules.DuplicateReceiptFunction {
        DuplicateReceiptFunction(int windowMin, RuleSource rules) { super(windowMin, rules); }
    }

    /** @see RiskRules.AdRewardFunction */
    static class AdRewardFunction extends RiskRules.AdRewardFunction {
        AdRewardFunction(int windowMin, RuleSource rules) { super(windowMin, rules); }
    }

    /** @see RiskRules.PatternFunction */
    static class PatternFunction extends RiskRules.PatternFunction {
        PatternFunction(RuleSource rules) { super(rules); }
    }

    /** @see RiskScore.RiskScoreFunction */
    static class RiskScoreFunction extends RiskScore.RiskScoreFunction {
    }

    /** @see RiskRules.SeqState */
    static final class SeqState extends RiskRules.SeqState {
    }

    // ===== 模型（风控输入/命中/评估产出/规则拉取参数） =====

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

    /** 风控输入 POJO；随携带它的函数闭包一起序列化（TimedSource/RuleSource 等），实现 Serializable。 */
    public static final class RiskInput implements java.io.Serializable {
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
        /** 资源 ID（金币口径判 gold） */
        public String resourceId;
        /** props 原文 JSON（win_rate 的 win 标记来源） */
        public String propsJson;
        /** 收据键：receipt_hash 优先，退化 order_id；null 表示非收据事件 */
        public String receiptKey;
        public BigDecimal revenueAmount;
        /** 是否为激励广告 reward 事件 */
        public boolean adReward;
        /** 信任档位（LOW/HIGH/COMPUTED；网关权威回填，缺失归一为 ""），BLOCK 级动作门槛输入 */
        public String trustLevel;
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
        /** 判定输入的信任口径（单事件=该事件 trust_level；窗口=窗口内全 HIGH 才 "HIGH"），BLOCK 门槛依据 */
        public String trustLevel;

        public RiskHit() {}

        public RiskHit(String gameId, String environment, Timestamp ts, String riskEventId, String sourceEventId,
                       String ruleId, String riskType, String severity, String subjectType, String subjectId,
                       float score, String action, String reason, Map<String, String> evidence, String trustLevel) {
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
            this.trustLevel = trustLevel;
        }
    }

    /** risk_scores 落行载体（对应 ClickHouse 同名表 7 列）。 */
    public static final class RiskScoreRow implements java.io.Serializable {
        public String gameId;
        public String environment;
        public String subjectType;
        public String subjectId;
        public float score;
        public Timestamp updatedTs;
        public List<String> reasons;

        public RiskScoreRow() {}

        public RiskScoreRow(String gameId, String environment, String subjectType, String subjectId,
                            float score, Timestamp updatedTs, List<String> reasons) {
            this.gameId = gameId;
            this.environment = environment;
            this.subjectType = subjectType;
            this.subjectId = subjectId;
            this.score = score;
            this.updatedTs = updatedTs;
            this.reasons = reasons;
        }
    }
}
