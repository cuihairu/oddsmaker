package io.oddsmaker.jobs.risk;

import io.oddsmaker.jobs.enrich.ApicurioAvroFlinkDeserializer;
import io.oddsmaker.jobs.enrich.RawEvent;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.api.common.restartstrategy.RestartStrategies;
import org.apache.flink.api.common.eventtime.SerializableTimestampAssigner;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.common.state.StateTtlConfig;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.connector.jdbc.JdbcConnectionOptions;
import org.apache.flink.connector.jdbc.JdbcExecutionOptions;
import org.apache.flink.connector.jdbc.JdbcSink;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.WindowedStream;
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
import java.util.List;
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
                // B5 特征分支：risk_features 落 PostgreSQL（control 库）
                System.getProperty("control.db.url", "jdbc:postgresql://localhost:5432/oddsmaker"),
                System.getProperty("control.db.user", "oddsmaker"),
                System.getProperty("control.db.pass", "oddsmaker"),
                // 乱序容忍秒数：e2e 复现时置 0 可让窗口按事件时间即刻闭合
                Integer.parseInt(System.getProperty("risk.watermark.delay-seconds", "120")),
                // 特征分支总开关（关掉则既不产出 risk_features 也不评估 FEATURE 规则）
                Boolean.parseBoolean(System.getProperty("risk.features.enabled", "true")),
                // PATTERN 窗口不在此处：DEFAULTS/spec.windowSeconds 直接读 risk.pattern.window-minutes
        };
    }

    /** 有界乱序水位线（缺省 2 分钟），时间戳取 ts_server → ts_client → now。 */
    static WatermarkStrategy<RawEvent> watermarks() {
        return watermarks(120);
    }

    static WatermarkStrategy<RawEvent> watermarks(int delaySeconds) {
        return WatermarkStrategy.<RawEvent>forBoundedOutOfOrderness(Duration.ofSeconds(delaySeconds))
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
     * 搭建七类风控规则管道（惰性：source/sink 均到 execute 才连接，单测可用本地环境直跑）。
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

        DataStream<RawEvent> raw = env.fromSource(source, watermarks(watermarkDelaySeconds), "events-raw");

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

        // PATTERN：序列匹配无需开窗，keyed 状态机增量推进（见 PatternFunction）
        DataStream<RiskHit> patternHits = inputs
                .filter(RiskJob::inPatternSequence)
                .keyBy(RiskJob::subjectWindowKey)
                .process(new PatternFunction(rules))
                .returns(Types.POJO(RiskHit.class));

        DataStream<RiskHit> allHits;
        if (featuresEnabled) {
            // B5 特征分支（计划书 §5.1）：事件→特征→规则三段解耦。
            // 特征行按 6 特征滑动窗口聚合 → upsert PostgreSQL risk_features；
            // 同一批特征行广播回评估算子，FEATURE 规则按主体最新特征快照命中。
            DataStream<FeatureRow> featureRows = buildFeatureBranch(inputs);

            featureRows.addSink(featureJdbcSink(pgUrl, pgUser, pgPass)).name("postgres-risk-features");

            // B10：feature_store 摘要双写（control 库，scope×窗口一行）。
            // FeatureRow 是长格式（每特征一行），依赖 SQL 层 jsonb 合并聚成单行；
            // 同名特征迟到重算时 || 取右值，与 risk_features 的 upsert 语义一致
            featureRows.addSink(featureStoreJdbcSink(pgUrl, pgUser, pgPass)).name("postgres-feature-store");

            DataStream<RiskHit> featureRuleHits = inputs
                    .keyBy(RiskJob::subjectWindowKey)
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
        allHits = allHits.map(RiskJob::applyTrustGate).returns(Types.POJO(RiskHit.class));

        KafkaSink<String> kafkaSink = KafkaSink.<String>builder()
                .setBootstrapServers(bootstrap)
                .setRecordSerializer(KafkaRecordSerializationSchema.builder()
                        .setTopic(riskTopic)
                        .setValueSerializationSchema(new org.apache.flink.api.common.serialization.SimpleStringSchema())
                        .build())
                .build();

        allHits.map(RiskJob::toJson).returns(Types.STRING).sinkTo(kafkaSink).name("kafka-risk-events");

        allHits.addSink(riskEventsJdbcSink(chUrl, chUser, chPass)).name("clickhouse-risk-events");

        // B6 §5.2：评估产出 risk_scores（主体累计分 + 规则触发明细 JSON），与 risk_events 同源分叉
        DataStream<RiskScoreRow> scores = allHits
                .keyBy(RiskJob::scoreKey)
                .process(new RiskScoreFunction())
                .returns(Types.POJO(RiskScoreRow.class));

        scores.addSink(riskScoresJdbcSink(chUrl, chUser, chPass)).name("clickhouse-risk-scores");

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

    /**
     * B5 特征作业分支：首批 6 特征的滑动窗口聚合（清单以计划书 §5.1 为准）。
     * 窗口步长：1h 特征 5 分钟一算（规则读值新鲜度），24h 半小时，10 分钟特征 1 分钟。
     * 分口径 keyBy：gold/device/win/event_count 走主体键，account_count_per_ip 走 IP 键。
     */
    static DataStream<FeatureRow> buildFeatureBranch(DataStream<RiskInput> inputs) {
        DataStream<FeatureRow> gold1h = inputs
                .filter(FeatureCalc::isGoldSource)
                .keyBy(RiskJob::subjectWindowKey)
                .window(SlidingEventTimeWindows.of(Time.hours(1), Time.minutes(5)))
                .process(new FeatureCalc.GoldGainFeature("gold_gain_1h"))
                .returns(Types.POJO(FeatureRow.class));

        DataStream<FeatureRow> gold24h = inputs
                .filter(FeatureCalc::isGoldSource)
                .keyBy(RiskJob::subjectWindowKey)
                .window(SlidingEventTimeWindows.of(Time.hours(24), Time.minutes(30)))
                .process(new FeatureCalc.GoldGainFeature("gold_gain_24h"))
                .returns(Types.POJO(FeatureRow.class));

        DataStream<FeatureRow> deviceCount = inputs
                .filter(RiskJob::isPlayerSubject)
                .keyBy(RiskJob::subjectWindowKey)
                .window(SlidingEventTimeWindows.of(Time.hours(1), Time.minutes(5)))
                .process(new FeatureCalc.DeviceCountFeature())
                .returns(Types.POJO(FeatureRow.class));

        DataStream<FeatureRow> accountPerIp = inputs
                .filter(RiskJob::hasIpAccount)
                .keyBy(RiskJob::ipWindowKey)
                .window(SlidingEventTimeWindows.of(Time.hours(1), Time.minutes(5)))
                .process(new FeatureCalc.AccountPerIpFeature())
                .returns(Types.POJO(FeatureRow.class));

        DataStream<FeatureRow> winRate = inputs
                .filter(FeatureCalc::isMatchResult)
                .keyBy(RiskJob::subjectWindowKey)
                .window(SlidingEventTimeWindows.of(Time.hours(1), Time.minutes(5)))
                .process(new FeatureCalc.WinRateFeature())
                .returns(Types.POJO(FeatureRow.class));

        DataStream<FeatureRow> eventCount = inputs
                .keyBy(RiskJob::subjectWindowKey)
                .window(SlidingEventTimeWindows.of(Time.minutes(10), Time.minutes(1)))
                .process(new FeatureCalc.EventCountFeature())
                .returns(Types.POJO(FeatureRow.class));

        return gold1h.union(gold24h).union(deviceCount).union(accountPerIp).union(winRate).union(eventCount);
    }

    /** risk_features upsert：同窗口重算（迟到事件修正）按唯一键覆盖 value/as_of，幂等可重放 */
    static final String FEATURE_UPSERT_SQL =
            "INSERT INTO risk_features (game_id, environment, scope_key, feature_name, window_start, window_end, value, as_of) "
            + "VALUES (?,?,?,?,?,?,?,?) "
            + "ON CONFLICT (game_id, environment, scope_key, feature_name, window_start, window_end) "
            + "DO UPDATE SET value = EXCLUDED.value, as_of = EXCLUDED.as_of";

    /** risk_features 写入 sink（PostgreSQL control 库） */
    static org.apache.flink.streaming.api.functions.sink.SinkFunction<FeatureRow> featureJdbcSink(String pgUrl, String pgUser, String pgPass) {
        return JdbcSink.sink(
                FEATURE_UPSERT_SQL,
                RiskJob::bindFeatureRow,
                JdbcExecutionOptions.builder().withBatchIntervalMs(500).withBatchSize(500).withMaxRetries(3).build(),
                new JdbcConnectionOptions.JdbcConnectionOptionsBuilder()
                        .withUrl(pgUrl)
                        .withDriverName("org.postgresql.Driver")
                        .withUsername(pgUser)
                        .withPassword(pgPass)
                        .build());
    }

    /**
     * feature_store 摘要 upsert（B10 双写，同连接参数同批次语义）。
     * 长格式行各自携带单特征 JSON 片段写入，冲突时 jsonb 合并——一个 scope×窗口
     * 的全部特征最终聚在同一行的 features 串里；同名键迟到重算取右值覆盖。
     */
    static final String FEATURE_STORE_UPSERT_SQL =
            "INSERT INTO feature_store (game_id, environment, scope_key, window_start, window_end, features, as_of, created_at) "
            + "VALUES (?,?,?,?,?,?,?,now()) "
            + "ON CONFLICT (game_id, environment, scope_key, window_start, window_end) "
            + "DO UPDATE SET features = (feature_store.features::jsonb || EXCLUDED.features::jsonb)::text, "
            + "as_of = EXCLUDED.as_of";

    /** feature_store 写入 sink（PostgreSQL control 库） */
    static org.apache.flink.streaming.api.functions.sink.SinkFunction<FeatureRow> featureStoreJdbcSink(String pgUrl, String pgUser, String pgPass) {
        return JdbcSink.sink(
                FEATURE_STORE_UPSERT_SQL,
                RiskJob::bindFeatureStoreRow,
                JdbcExecutionOptions.builder().withBatchIntervalMs(500).withBatchSize(500).withMaxRetries(3).build(),
                new JdbcConnectionOptions.JdbcConnectionOptionsBuilder()
                        .withUrl(pgUrl)
                        .withDriverName("org.postgresql.Driver")
                        .withUsername(pgUser)
                        .withPassword(pgPass)
                        .build());
    }

    /** 单特征 JSON 片段（feature_store.features 合并单元）。featureName 恒为代码内特征常量，无注入面。 */
    static String featureStoreFragment(FeatureRow r) {
        return "{\"" + r.featureName + "\":" + r.value + "}";
    }

    /** feature_store 表写入绑定（7 参 + now()）。 */
    static void bindFeatureStoreRow(java.sql.PreparedStatement ps, FeatureRow r) throws java.sql.SQLException {
        ps.setString(1, r.gameId);
        ps.setString(2, r.environment);
        ps.setString(3, r.scopeKey);
        ps.setTimestamp(4, new java.sql.Timestamp(r.windowStartMs));
        ps.setTimestamp(5, new java.sql.Timestamp(r.windowEndMs));
        ps.setString(6, featureStoreFragment(r));
        ps.setTimestamp(7, new java.sql.Timestamp(r.asOfMs));
    }

    /** risk_events 写入 sink（ClickHouse 风控事件流，14 列）——buildPipeline 与 §5.4 端到端共用 */
    static org.apache.flink.streaming.api.functions.sink.SinkFunction<RiskHit> riskEventsJdbcSink(String chUrl, String chUser, String chPass) {
        return JdbcSink.sink(
                "INSERT INTO risk_events (game_id, environment, ts, risk_event_id, source_event_id, rule_id, risk_type, severity, subject_type, subject_id, score, action, reason, evidence) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                RiskJob::bindRiskHit,
                JdbcExecutionOptions.builder().withBatchIntervalMs(500).withBatchSize(500).withMaxRetries(3).build(),
                chJdbcOptions(chUrl, chUser, chPass));
    }

    /** risk_scores 写入 sink（ClickHouse 主体累计分评估产出，7 列）——buildPipeline 与 §5.4 端到端共用 */
    static org.apache.flink.streaming.api.functions.sink.SinkFunction<RiskScoreRow> riskScoresJdbcSink(String chUrl, String chUser, String chPass) {
        return JdbcSink.sink(
                "INSERT INTO risk_scores (game_id, environment, subject_type, subject_id, score, updated_at, reasons) VALUES (?,?,?,?,?,?,?)",
                RiskJob::bindRiskScore,
                JdbcExecutionOptions.builder().withBatchIntervalMs(500).withBatchSize(500).withMaxRetries(3).build(),
                chJdbcOptions(chUrl, chUser, chPass));
    }

    /** ClickHouse JDBC 连接参数（risk_events / risk_scores 两个出口共用） */
    private static JdbcConnectionOptions chJdbcOptions(String chUrl, String chUser, String chPass) {
        return new JdbcConnectionOptions.JdbcConnectionOptionsBuilder()
                .withUrl(chUrl)
                .withDriverName("com.clickhouse.jdbc.ClickHouseDriver")
                .withUsername(chUser)
                .withPassword(chPass)
                .build();
    }

    /** risk_features 表写入绑定（8 列）。 */
    static void bindFeatureRow(java.sql.PreparedStatement ps, FeatureRow r) throws java.sql.SQLException {
        ps.setString(1, r.gameId);
        ps.setString(2, r.environment);
        ps.setString(3, r.scopeKey);
        ps.setString(4, r.featureName);
        ps.setTimestamp(5, new java.sql.Timestamp(r.windowStartMs));
        ps.setTimestamp(6, new java.sql.Timestamp(r.windowEndMs));
        ps.setDouble(7, r.value);
        ps.setTimestamp(8, new java.sql.Timestamp(r.asOfMs));
    }

    /** device_count/win_rate/event_count 的主体口径前提：带 user_id 的 PLAYER 主体 */
    static boolean isPlayerSubject(RiskInput i) {
        return i.userId != null && !i.userId.isEmpty();
    }

    /** account_count_per_ip 的入窗口前提：带账号且带 IP */
    static boolean hasIpAccount(RiskInput i) {
        return isPlayerSubject(i) && i.clientIp != null && !i.clientIp.isEmpty();
    }

    /** account_count_per_ip 的分键：game|env|IP:<ip> */
    static String ipWindowKey(RiskInput i) {
        return i.gameId + "|" + i.environment + "|" + FeatureCalc.ipScopeKey(i);
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
        in.resourceId = nz(r.resource_id);
        in.propsJson = r.props_json;
        in.receiptKey = firstNonBlank(r.receipt_hash, r.order_id);
        in.revenueAmount = parseAmount(r.revenue_amount);
        // B6 §5.2：信任档位为网关权威值（B4 契约 v2），null 归一为空串——
        // 与 CH 事件表迁移同款语义：'' 天然不满足 HIGH 精确匹配，安全侧收敛（fail-closed）
        in.trustLevel = nz(str(r.trust_level));
        String adFormat = nz(r.ad_format);
        // schema 无 game_event_type 字段：gameEventType 是空串字面量，
        // "ad_reward".equalsIgnoreCase("") 恒 false，死枝等价删除
        in.adReward = "rewarded".equalsIgnoreCase(adFormat)
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
                ev, i.trustLevel);
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
            boolean allHigh = true;
            for (RiskInput e : events) {
                count++;
                last = e;
                if (!"HIGH".equals(e.trustLevel)) allHigh = false;
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
                        ev, trustScope(allHigh)));
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
            boolean allHigh = true;
            for (RiskInput e : events) {
                sum = sum.add(e.amount);
                count++;
                last = e;
                if (!"HIGH".equals(e.trustLevel)) allHigh = false;
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
                        ev, trustScope(allHigh)));
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
            boolean allHigh = true;
            for (RiskInput e : events) {
                if ("source".equals(e.flowType)) sourceSum = sourceSum.add(e.amount);
                else if ("sink".equals(e.flowType)) sinkSum = sinkSum.add(e.amount);
                last = e;
                if (!"HIGH".equals(e.trustLevel)) allHigh = false;
            }
            // sinkSum > 0 蕴含窗口循环至少执行过一次（同一循环里给 last 赋值），
            // last != null 恒真，等价删枝
            if (sinkSum.compareTo(BigDecimal.ZERO) > 0) {
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
                            ev, trustScope(allHigh)));
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
            boolean allHigh = true;
            for (RiskInput e : events) {
                count++;
                last = e;
                if (!"HIGH".equals(e.trustLevel)) allHigh = false;
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
                        ev, trustScope(allHigh)));
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
            boolean allHigh = true;
            for (RiskInput e : events) {
                count++;
                if (e.revenueAmount != null) revenueSum = revenueSum.add(e.revenueAmount);
                last = e;
                if (!"HIGH".equals(e.trustLevel)) allHigh = false;
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
                        ev, trustScope(allHigh)));
            }
        }
    }

    /** PATTERN 口径：事件名属于当前序列规则的事件集合。 */
    static boolean inPatternSequence(RiskInput i) {
        RuleConfig.RuleSpec spec = RuleConfig.byType("PATTERN");
        return i.eventName != null && spec.sequence.contains(i.eventName);
    }

    /**
     * PATTERN：keyed 有序序列状态机。与窗口类算子不同，序列匹配是逐事件增量推进，
     * 用 KeyedProcessFunction + ValueState 维护「下一步期望索引 + 第一步时间戳」，
     * 窗口自第一步起算、惰性过期；命中后重置，可再次从头匹配。
     * 状态机核心在纯函数 {@link #advance}，不依赖 Flink 运行时，单测直测。
     */
    static class PatternFunction extends KeyedProcessFunction<String, RiskInput, RiskHit> {
        private final RuleSource rules;
        private transient ValueState<Integer> progress;
        private transient ValueState<Long> startedAt;

        PatternFunction(RuleSource rules) {
            this.rules = rules;
        }

        @Override
        public void open(org.apache.flink.configuration.Configuration parameters) {
            RuleFetcher.startOnce(rules.controlUrl, rules.gameId, rules.token, rules.refreshMs);
            progress = keyedState("pattern-progress", Types.INT);
            startedAt = keyedState("pattern-started-at", Types.LONG);
        }

        /** 带 TTL 的 keyed 状态：惰性过期之外，主体再无后续事件的僵尸 key 由 TTL 兜底回收。 */
        private <T> ValueState<T> keyedState(String name, org.apache.flink.api.common.typeinfo.TypeInformation<T> type) {
            ValueStateDescriptor<T> desc = new ValueStateDescriptor<>(name, type);
            desc.enableTimeToLive(StateTtlConfig.newBuilder(org.apache.flink.api.common.time.Time.days(1)).build());
            return getRuntimeContext().getState(desc);
        }

        @Override
        public void processElement(RiskInput i, Context ctx, Collector<RiskHit> out) throws Exception {
            RuleConfig.RuleSpec spec = RuleConfig.byType("PATTERN");
            SeqState st = new SeqState();
            st.nextIdx = progress.value();
            st.startTs = startedAt.value();
            RiskHit hit = advance(spec, i, st);
            if (hit != null) out.collect(hit);
            if (st.nextIdx == null) progress.clear();
            else progress.update(st.nextIdx);
            if (st.startTs == null) startedAt.clear();
            else startedAt.update(st.startTs);
        }
    }

    /** PATTERN 状态机进行中的状态（nextIdx=null 表示空闲）。 */
    static final class SeqState {
        /** 下一个期望步的索引；null=空闲 */
        Integer nextIdx;
        /** 第一步命中时间戳（毫秒）；空闲为 null */
        Long startTs;
    }

    /**
     * PATTERN 单步转移（纯函数）：按当前状态推进序列匹配，原地更新 st；
     * 序列完成时返回命中事件（st 已重置为空闲），否则返回 null。
     * 语义：窗口自第一步起算，超窗惰性作废；非期望步事件若是第一步则重新起序，否则忽略。
     */
    static RiskHit advance(RuleConfig.RuleSpec spec, RiskInput i, SeqState st) {
        List<String> seq = spec.sequence;
        if (i.eventName == null || seq.size() < 2) return null;
        long ts = i.ts.getTime();

        // 惰性过期：进行中的序列超出窗口则作废
        if (st.nextIdx != null && st.startTs != null && ts - st.startTs > spec.windowSeconds * 1000L) {
            st.nextIdx = null;
            st.startTs = null;
        }

        if (st.nextIdx == null) {
            // 空闲：只有第一步能起序
            if (seq.get(0).equals(i.eventName)) {
                st.nextIdx = 1;
                st.startTs = ts;
            }
            return null;
        }

        if (seq.get(st.nextIdx).equals(i.eventName)) {
            if (st.nextIdx == seq.size() - 1) {
                long start = st.startTs;
                st.nextIdx = null;
                st.startTs = null;
                return patternHit(spec, i, seq, start);
            }
            st.nextIdx++;
            return null;
        }

        // 非期望步：第一步重新起序（含第一步重复出现 → 以最新为起点），其余忽略
        if (seq.get(0).equals(i.eventName)) {
            st.nextIdx = 1;
            st.startTs = ts;
        }
        return null;
    }

    /** PATTERN 命中事件构造。 */
    static RiskHit patternHit(RuleConfig.RuleSpec spec, RiskInput last, List<String> seq, long startTs) {
        int windowMin = Math.max(1, spec.windowSeconds / 60);
        String seqText = String.join(" -> ", seq);
        Map<String, String> ev = new HashMap<>();
        ev.put("sequence", seqText);
        ev.put("window_minutes", String.valueOf(windowMin));
        ev.put("first_ts", String.valueOf(startTs));
        ev.put("subject", subjectKey(last));
        return new RiskHit(
                last.gameId, last.environment, last.ts, UUID.randomUUID().toString(), last.eventId,
                spec.ruleId != null ? spec.ruleId : "risk-pattern-sequence", "PATTERN", spec.riskLevel,
                subjectType(last), subjectId(last),
                spec.riskScore, spec.actionType,
                "sequence matched in " + windowMin + "min: " + seqText,
                ev, last.trustLevel);
    }

    /**
     * B6 §5.2 RiskScore 独立：按主体键累计各规则对主体的最大贡献分——Rule 静态 riskScore
     * 降级为「该规则最大贡献分」，同规则重复命中取高不叠加；每次评估触发产出一行 risk_scores
     * （subject、累计分、规则触发明细 JSON），ReplacingMergeTree 按主体保留最新累计快照。
     */
    static class RiskScoreFunction extends KeyedProcessFunction<String, RiskHit, RiskScoreRow> {
        private transient ValueState<Map<String, Float>> contributions;

        @Override
        public void open(org.apache.flink.configuration.Configuration parameters) {
            ValueStateDescriptor<Map<String, Float>> desc =
                    new ValueStateDescriptor<>("risk-score-contributions", Types.MAP(Types.STRING, Types.FLOAT));
            // 状态兜底回收（同 PatternFunction 惯例）：主体 7 天无命中则累计清零重新起算
            desc.enableTimeToLive(StateTtlConfig.newBuilder(org.apache.flink.api.common.time.Time.days(7)).build());
            contributions = getRuntimeContext().getState(desc);
        }

        @Override
        public void processElement(RiskHit h, Context ctx, Collector<RiskScoreRow> out) throws Exception {
            Map<String, Float> byRule = contributions.value();
            if (byRule == null) byRule = new HashMap<>();
            Float prev = byRule.get(h.ruleId);
            if (prev == null || h.score > prev) byRule.put(h.ruleId, h.score);
            contributions.update(byRule);

            float total = 0f;
            List<String> reasons = new java.util.ArrayList<>(byRule.size());
            for (Map.Entry<String, Float> e : byRule.entrySet()) {
                total += e.getValue();
                reasons.add("{\"rule_id\":\"" + jsonEscape(e.getKey())
                        + "\",\"contribution\":" + e.getValue() + "}");
            }
            reasons.sort(null); // 明细顺序确定，便于断言与回溯
            out.collect(new RiskScoreRow(h.gameId, h.environment, h.subjectType, h.subjectId,
                    total, h.ts, reasons));
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

    /** risk_scores 的主体分键：game|env|subjectType:subjectId（与表 ORDER BY 主体维度对齐）。 */
    static String scoreKey(RiskHit h) {
        return h.gameId + "|" + h.environment + "|" + h.subjectType + ":" + h.subjectId;
    }

    /** risk_scores 表写入绑定（7 列；reasons 为规则触发明细 JSON 数组）。 */
    static void bindRiskScore(java.sql.PreparedStatement ps, RiskScoreRow r) throws java.sql.SQLException {
        ps.setString(1, r.gameId);
        ps.setString(2, r.environment);
        ps.setString(3, r.subjectType);
        ps.setString(4, r.subjectId);
        ps.setFloat(5, r.score);
        ps.setTimestamp(6, r.updatedTs);
        ps.setArray(7, ps.getConnection().createArrayOf("String", r.reasons.toArray(new String[0])));
    }

    /** JSON 字符串字面量转义（ruleId 等标识符防御性转义）。 */
    static String jsonEscape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
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

    /** 窗口口径信任档位：窗口内事件全为 HIGH 才收敛为 "HIGH"，否则 ""（混杂窗口 fail-closed）。 */
    static String trustScope(boolean allHigh) {
        return allHigh ? "HIGH" : "";
    }

    /**
     * BLOCK 级动作高信任门槛（计划书 §5.2/§4.3）：BLOCK 只允许 trust_level=HIGH 的输入驱动；
     * ''/null/LOW/COMPUTED 或窗口混杂一律降级 REVIEW——安全侧收敛，宁转人审不放行封禁。
     * 其余动作不受门槛影响，原样返回。
     */
    static String gateAction(String action, String trustLevel) {
        return "BLOCK".equalsIgnoreCase(action) && !"HIGH".equals(trustLevel) ? "REVIEW" : action;
    }

    /** 命中流统一门槛（union 后、进 sink 前）：BLOCK 降级时 reason 打 trust_gate 标记便于回溯。 */
    static RiskHit applyTrustGate(RiskHit h) {
        String gated = gateAction(h.action, h.trustLevel);
        if (!gated.equals(h.action)) {
            h.action = gated;
            h.reason = "[trust_gate] " + h.reason;
        }
        return h;
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
        sb.append(",\"trust_level\":\"").append(h.trustLevel == null ? "" : h.trustLevel).append("\"");
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
}
