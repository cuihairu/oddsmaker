package io.oddsmaker.jobs.dimension;

import io.oddsmaker.jobs.enrich.ApicurioAvroFlinkDeserializer;
import io.oddsmaker.jobs.enrich.RawEvent;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.connector.jdbc.JdbcConnectionOptions;
import org.apache.flink.connector.jdbc.JdbcExecutionOptions;
import org.apache.flink.connector.jdbc.JdbcSink;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;

import java.sql.Timestamp;
import java.time.Duration;

public class DimensionSyncJob {

    static final String JOB_NAME = "oddsmaker-dimension-sync";

    /** 入口：读配置 → 搭管道 → 触发执行（execute 才真正连接 source/sink）。 */
    public static void main(String[] args) throws Exception {
        buildPipeline(StreamExecutionEnvironment.getExecutionEnvironment(), config()).getExecutionEnvironment().execute(JOB_NAME);
    }

    /** 配置读取（System properties，单测可 setProperty 后逐项直测）。 */
    static String[] config() {
        return new String[]{kafkaBootstrap(), registryUrl(), kafkaTopic(), chUrl(), chUser(), chPass()};
    }

    static String kafkaBootstrap() {
        return System.getProperty("kafka.bootstrap", "localhost:9092");
    }

    static String registryUrl() {
        return System.getProperty("registry.url", "http://localhost:8081/apis/registry/v2");
    }

    static String kafkaTopic() {
        return System.getProperty("kafka.topic", "oddsmaker.events_raw");
    }

    static String chUrl() {
        return System.getProperty("clickhouse.url", "jdbc:clickhouse://localhost:8123/default");
    }

    static String chUser() {
        return System.getProperty("clickhouse.user", "default");
    }

    static String chPass() {
        return System.getProperty("clickhouse.pass", "");
    }

    /** 有界乱序水位线（5 分钟），时间戳取 ts_server → ts_client → now。 */
    static WatermarkStrategy<RawEvent> watermarks() {
        return WatermarkStrategy.<RawEvent>forBoundedOutOfOrderness(Duration.ofMinutes(5))
                .withTimestampAssigner((r, ts) -> eventMillis(r));
    }

    /** item 维度分流谓词。 */
    static boolean isItem(DimRecord r) {
        return "item".equals(r.dimType);
    }

    /** level 维度分流谓词。 */
    static boolean isLevel(DimRecord r) {
        return "level".equals(r.dimType);
    }

    /**
     * 搭建维度同步管道（惰性：source/sink 均到 execute 才连接，单测可用本地环境直跑）。
     */
    static SingleOutputStreamOperator<DimRecord> buildPipeline(StreamExecutionEnvironment env, String[] cfg) {
        String bootstrap = cfg[0], registry = cfg[1], topic = cfg[2], chUrl = cfg[3], chUser = cfg[4], chPass = cfg[5];
        KafkaSource<RawEvent> source = KafkaSource.<RawEvent>builder()
                .setBootstrapServers(bootstrap)
                .setTopics(topic)
                .setGroupId("oddsmaker-dimension-sync")
                .setStartingOffsets(OffsetsInitializer.latest())
                .setDeserializer(new ApicurioAvroFlinkDeserializer(registry))
                .build();

        DataStream<RawEvent> raw = env.fromSource(source, watermarks(), "events-raw");

        SingleOutputStreamOperator<DimRecord> allDims = raw
                .flatMap((FlatMapFunction<RawEvent, DimRecord>) DimensionSyncJob::dimRecords)
                .returns(Types.POJO(DimRecord.class))
                .name("dimension-parse");

        DataStream<DimRecord> items = allDims.filter(DimensionSyncJob::isItem).returns(Types.POJO(DimRecord.class));
        DataStream<DimRecord> levels = allDims.filter(DimensionSyncJob::isLevel).returns(Types.POJO(DimRecord.class));

        var itemSink = JdbcSink.<DimRecord>sink(
                "INSERT INTO item_dim (game_id, environment, resource_id, name, type, rarity, category, description, version_ts, is_current) VALUES (?,?,?,?,?,?,?,?,?,1)",
                DimensionSyncJob::bindItemDim,
                JdbcExecutionOptions.builder().withBatchIntervalMs(500).withBatchSize(500).withMaxRetries(3).build(),
                new JdbcConnectionOptions.JdbcConnectionOptionsBuilder()
                        .withUrl(chUrl).withDriverName("com.clickhouse.jdbc.ClickHouseDriver")
                        .withUsername(chUser).withPassword(chPass).build()
        );

        var levelSink = JdbcSink.<DimRecord>sink(
                "INSERT INTO level_dim (game_id, environment, level_id, name, difficulty, chapter, version_ts, is_current) VALUES (?,?,?,?,?,?,?,1)",
                DimensionSyncJob::bindLevelDim,
                JdbcExecutionOptions.builder().withBatchIntervalMs(500).withBatchSize(500).withMaxRetries(3).build(),
                new JdbcConnectionOptions.JdbcConnectionOptionsBuilder()
                        .withUrl(chUrl).withDriverName("com.clickhouse.jdbc.ClickHouseDriver")
                        .withUsername(chUser).withPassword(chPass).build()
        );

        items.addSink(itemSink).name("clickhouse-item-dim");
        levels.addSink(levelSink).name("clickhouse-level-dim");
        return allDims;
    }

    /** 水位线时间戳：ts_server 优先，回退 ts_client，均缺省用当前时间（微秒 → 毫秒）。 */
    static long eventMillis(RawEvent r) {
        Long s = r.ts_server;
        Long c = r.ts_client;
        long micros = s != null ? s : (c != null ? c : System.currentTimeMillis() * 1000L);
        return micros / 1000L;
    }

    /** flatMap 体：dimension 事件解析为维度记录，无效负载静默丢弃。 */
    static void dimRecords(RawEvent r, Collector<DimRecord> out) {
        if (!"dimension".equals(r.event_type)) return;
        String gameId = r.game_id;
        String environment = r.environment;
        if (gameId == null || environment == null) return;
        String propsJson = r.props_json;
        if (propsJson == null || propsJson.isEmpty()) return;
        DimRecord rec = parseProps(gameId, environment, propsJson);
        if (rec != null) out.collect(rec);
    }

    /** item_dim 写入绑定。 */
    static void bindItemDim(java.sql.PreparedStatement ps, DimRecord r) throws java.sql.SQLException {
        ps.setString(1, r.gameId);
        ps.setString(2, r.environment);
        ps.setString(3, r.id);
        ps.setString(4, r.attributes.getOrDefault("name", ""));
        ps.setString(5, r.attributes.getOrDefault("type", ""));
        ps.setString(6, r.attributes.getOrDefault("rarity", ""));
        ps.setString(7, r.attributes.getOrDefault("category", ""));
        ps.setString(8, r.attributes.getOrDefault("description", ""));
        ps.setTimestamp(9, r.versionTs);
    }

    /** level_dim 写入绑定。 */
    static void bindLevelDim(java.sql.PreparedStatement ps, DimRecord r) throws java.sql.SQLException {
        ps.setString(1, r.gameId);
        ps.setString(2, r.environment);
        ps.setString(3, r.id);
        ps.setString(4, r.attributes.getOrDefault("name", ""));
        ps.setString(5, r.attributes.getOrDefault("difficulty", ""));
        ps.setString(6, r.attributes.getOrDefault("chapter", ""));
        ps.setTimestamp(7, r.versionTs);
    }

    public static DimRecord parseProps(String gameId, String environment, String json) {
        try {
            com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            String dimType = node.path("dim_type").asText(node.path("dimension_type").asText("item"));
            String id = firstNonEmpty(node, "resource_id", "item_code", "level_id", "id");
            if (id == null || id.isEmpty()) return null;
            long versionTs = node.path("version_ts").asLong(0);
            Timestamp ts = versionTs > 0 ? new Timestamp(versionTs) : new Timestamp(System.currentTimeMillis());
            DimRecord rec = new DimRecord();
            rec.gameId = gameId;
            rec.environment = normalizeEnv(environment);
            rec.dimType = normalizeDimType(dimType);
            rec.id = id;
            rec.versionTs = ts;
            com.fasterxml.jackson.databind.JsonNode attrs = node.path("attributes");
            if (attrs.isObject() && attrs.size() > 0) {
                attrs.fields().forEachRemaining(e -> putAttribute(rec.attributes, e.getKey(), e.getValue().asText("")));
            } else {
                node.fields().forEachRemaining(e -> {
                    String k = e.getKey();
                    if (!java.util.Set.of("dim_type", "dimension_type", "resource_id", "item_code", "level_id", "id", "version_ts", "op", "$identify").contains(k)) {
                        putAttribute(rec.attributes, k, e.getValue().asText(""));
                    }
                });
            }
            return rec;
        } catch (Exception e) {
            return null;
        }
    }

    /** 环境名归一化：production → prod，其余原样。 */
    static String normalizeEnv(String env) {
        return "production".equals(env) ? "prod" : env;
    }

    /** 维度类型/表名归一化：resource/items → item，levels → level，其余原样。 */
    static String normalizeDimType(String t) {
        if (t == null) return "item";
        return switch (t) {
            case "resource", "items" -> "item";
            case "levels" -> "level";
            default -> t;
        };
    }

    /** 依次取第一个非空文本字段。 */
    static String firstNonEmpty(com.fasterxml.jackson.databind.JsonNode node, String... keys) {
        for (String k : keys) {
            String v = node.path(k).asText("");
            if (!v.isEmpty()) return v;
        }
        return null;
    }

    /** 属性键归一化：display_name/level_name → name，quality → rarity，level_difficulty → difficulty。 */
    static void putAttribute(java.util.Map<String, String> attrs, String key, String value) {
        switch (key) {
            case "display_name", "level_name" -> attrs.put("name", value);
            case "quality" -> attrs.put("rarity", value);
            case "level_difficulty" -> attrs.put("difficulty", value);
            default -> attrs.put(key, value);
        }
    }

    public static final class DimRecord {
        public String gameId;
        public String environment;
        public String dimType = "item";
        public String id;
        public Timestamp versionTs;
        public boolean isCurrent = true;
        public java.util.Map<String, String> attributes = new java.util.HashMap<>();
    }
}
