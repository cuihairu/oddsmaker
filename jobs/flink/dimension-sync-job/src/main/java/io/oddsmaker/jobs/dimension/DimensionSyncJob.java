package io.oddsmaker.jobs.dimension;

import io.oddsmaker.jobs.enrich.ApicurioAvroFlinkDeserializer;
import org.apache.avro.generic.GenericRecord;
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
                .setGroupId("oddsmaker-dimension-sync")
                .setStartingOffsets(OffsetsInitializer.latest())
                .setDeserializer(new ApicurioAvroFlinkDeserializer(registry))
                .build();

        WatermarkStrategy<GenericRecord> wm = WatermarkStrategy.<GenericRecord>forBoundedOutOfOrderness(Duration.ofMinutes(5))
                .withTimestampAssigner((r, ts) -> {
                    Long s = (Long) r.get("ts_server");
                    Long c = (Long) r.get("ts_client");
                    long micros = s != null ? s : (c != null ? c : System.currentTimeMillis() * 1000L);
                    return micros / 1000L;
                });

        DataStream<GenericRecord> raw = env.fromSource(source, wm, "events-raw");

        SingleOutputStreamOperator<DimRecord> allDims = raw
                .flatMap((FlatMapFunction<GenericRecord, DimRecord>) (r, out) -> {
                    String eventType = str(r.get("event_type"));
                    if (!"dimension".equals(eventType)) return;
                    String gameId = str(r.get("game_id"));
                    String environment = str(r.get("environment"));
                    if (gameId == null || environment == null) return;
                    String propsJson = str(r.get("props_json"));
                    if (propsJson == null || propsJson.isEmpty()) {
                        Object pm = r.get("props");
                        if (pm instanceof java.util.Map) propsJson = mapToJson((java.util.Map<?, ?>) pm);
                    }
                    if (propsJson == null || propsJson.isEmpty()) return;
                    DimRecord rec = parseProps(gameId, environment, propsJson);
                    if (rec != null) out.collect(rec);
                })
                .returns(Types.POJO(DimRecord.class))
                .name("dimension-parse");

        DataStream<DimRecord> items = allDims.filter(r -> "item".equals(r.dimType)).returns(Types.POJO(DimRecord.class));
        DataStream<DimRecord> levels = allDims.filter(r -> "level".equals(r.dimType)).returns(Types.POJO(DimRecord.class));

        var itemSink = JdbcSink.<DimRecord>sink(
                "INSERT INTO item_dim (game_id, environment, resource_id, name, type, rarity, category, description, version_ts, is_current) VALUES (?,?,?,?,?,?,?,?,?,1)",
                (ps, r) -> {
                    ps.setString(1, r.gameId);
                    ps.setString(2, r.environment);
                    ps.setString(3, r.id);
                    ps.setString(4, r.attributes.getOrDefault("name", ""));
                    ps.setString(5, r.attributes.getOrDefault("type", ""));
                    ps.setString(6, r.attributes.getOrDefault("rarity", ""));
                    ps.setString(7, r.attributes.getOrDefault("category", ""));
                    ps.setString(8, r.attributes.getOrDefault("description", ""));
                    ps.setTimestamp(9, r.versionTs);
                },
                JdbcExecutionOptions.builder().withBatchIntervalMs(500).withBatchSize(500).withMaxRetries(3).build(),
                new JdbcConnectionOptions.JdbcConnectionOptionsBuilder()
                        .withUrl(chUrl).withDriverName("com.clickhouse.jdbc.ClickHouseDriver")
                        .withUsername(chUser).withPassword(chPass).build()
        );

        var levelSink = JdbcSink.<DimRecord>sink(
                "INSERT INTO level_dim (game_id, environment, level_id, name, difficulty, chapter, version_ts, is_current) VALUES (?,?,?,?,?,?,?,1)",
                (ps, r) -> {
                    ps.setString(1, r.gameId);
                    ps.setString(2, r.environment);
                    ps.setString(3, r.id);
                    ps.setString(4, r.attributes.getOrDefault("name", ""));
                    ps.setString(5, r.attributes.getOrDefault("difficulty", ""));
                    ps.setString(6, r.attributes.getOrDefault("chapter", ""));
                    ps.setTimestamp(7, r.versionTs);
                },
                JdbcExecutionOptions.builder().withBatchIntervalMs(500).withBatchSize(500).withMaxRetries(3).build(),
                new JdbcConnectionOptions.JdbcConnectionOptionsBuilder()
                        .withUrl(chUrl).withDriverName("com.clickhouse.jdbc.ClickHouseDriver")
                        .withUsername(chUser).withPassword(chPass).build()
        );

        items.addSink(itemSink).name("clickhouse-item-dim");
        levels.addSink(levelSink).name("clickhouse-level-dim");

        env.execute("oddsmaker-dimension-sync");
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

    /**
     * 解析 Debezium CDC envelope（PostgreSQL items/levels 等维度表变更）。
     * gameId/environment/dimType 传空时回退 payload.source 中的值；op=d 取 before 并标记 isCurrent=false。
     */
    public static DimRecord parseDebezium(String json, String gameId, String environment, String dimType) {
        try {
            com.fasterxml.jackson.databind.JsonNode payload =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).path("payload");
            if (!payload.isObject()) return null;
            String op = payload.path("op").asText("");
            com.fasterxml.jackson.databind.JsonNode data =
                "d".equals(op) ? payload.path("before") : payload.path("after");
            if (!data.isObject()) return null;
            com.fasterxml.jackson.databind.JsonNode source = payload.path("source");
            String gid = gameId != null && !gameId.isEmpty() ? gameId : source.path("game_id").asText("");
            String env = environment != null && !environment.isEmpty() ? environment : source.path("environment").asText("");
            String dt = dimType != null && !dimType.isEmpty() ? dimType : normalizeDimType(source.path("table").asText(""));
            String id = firstNonEmpty(data, "item_code", "resource_id", "level_id", "id");
            if (gid.isEmpty() || env.isEmpty() || dt.isEmpty() || id == null || id.isEmpty()) return null;
            DimRecord rec = new DimRecord();
            rec.gameId = gid;
            rec.environment = normalizeEnv(env);
            rec.dimType = dt;
            rec.id = id;
            rec.versionTs = new Timestamp(payload.path("ts_ms").asLong(System.currentTimeMillis()));
            rec.isCurrent = !"d".equals(op);
            data.fields().forEachRemaining(e -> putAttribute(rec.attributes, e.getKey(), e.getValue().asText("")));
            return rec;
        } catch (Exception e) {
            return null;
        }
    }

    /** 环境名归一化：production → prod，其余原样。 */
    private static String normalizeEnv(String env) {
        return "production".equals(env) ? "prod" : env;
    }

    /** 维度类型/表名归一化：resource/items → item，levels → level，其余原样。 */
    private static String normalizeDimType(String t) {
        if (t == null) return "item";
        return switch (t) {
            case "resource", "items" -> "item";
            case "levels" -> "level";
            default -> t;
        };
    }

    /** 依次取第一个非空文本字段。 */
    private static String firstNonEmpty(com.fasterxml.jackson.databind.JsonNode node, String... keys) {
        for (String k : keys) {
            String v = node.path(k).asText("");
            if (!v.isEmpty()) return v;
        }
        return null;
    }

    /** 属性键归一化：display_name/level_name → name，quality → rarity，level_difficulty → difficulty。 */
    private static void putAttribute(java.util.Map<String, String> attrs, String key, String value) {
        switch (key) {
            case "display_name", "level_name" -> attrs.put("name", value);
            case "quality" -> attrs.put("rarity", value);
            case "level_difficulty" -> attrs.put("difficulty", value);
            default -> attrs.put(key, value);
        }
    }

    private static String str(Object v) { return v == null ? null : v.toString(); }

    private static String mapToJson(java.util.Map<?, ?> m) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(m);
        } catch (Exception e) { return ""; }
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
