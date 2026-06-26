package io.oddsmaker.jobs.enrich;

import org.apache.avro.generic.GenericRecord;
import org.apache.flink.api.common.eventtime.SerializableTimestampAssigner;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.state.StateTtlConfig;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.connector.jdbc.JdbcConnectionOptions;
import org.apache.flink.connector.jdbc.JdbcExecutionOptions;
import org.apache.flink.connector.jdbc.JdbcSink;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;

// Optional GeoIP & UA enrichment
import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.exception.GeoIp2Exception;
import com.maxmind.geoip2.model.CityResponse;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import nl.basjes.parse.useragent.UserAgent;
import nl.basjes.parse.useragent.UserAgentAnalyzer;

public class EventsEnrichJob {
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
                .setGroupId("oddsmaker-events-enrich")
                .setStartingOffsets(OffsetsInitializer.latest())
                .setDeserializer(new ApicurioAvroFlinkDeserializer(registry))
                .build();

        var wm = WatermarkStrategy.<GenericRecord>forBoundedOutOfOrderness(Duration.ofMinutes(5))
                .withTimestampAssigner((SerializableTimestampAssigner<GenericRecord>) (element, recordTimestamp) -> {
                    Long tsServer = longOrNull(field(element, "ts_server"));
                    Long tsClient = longOrNull(field(element, "ts_client"));
                    long micros = tsServer != null ? tsServer : (tsClient != null ? tsClient : System.currentTimeMillis() * 1000L);
                    return micros / 1000L; // to ms
                });

        DataStream<GenericRecord> stream = env.fromSource(source, wm, "events-raw");

        // DLQ 侧输出
        final OutputTag<String> DLQ = new OutputTag<>("dlq", Types.STRING);

        // 基础校验（必填字段）+ 填充 ts_server
        SingleOutputStreamOperator<GenericRecord> validated = stream.process(new org.apache.flink.streaming.api.functions.ProcessFunction<GenericRecord, GenericRecord>() {
            @Override
            public void processElement(GenericRecord r, Context ctx, Collector<GenericRecord> out) throws Exception {
                if (field(r, "event_id") == null || field(r, "event_name") == null || field(r, "game_id") == null || field(r, "environment") == null || field(r, "device_id") == null) {
                    ctx.output(DLQ, toDlqJson(r, "invalid_schema"));
                    return;
                }
                if (field(r, "ts_server") == null) {
                    r.put("ts_server", System.currentTimeMillis() * 1000L); // micros
                }
                out.collect(r);
            }
        });

        // 去重（按 event_id，7 天 TTL）
        DataStream<GenericRecord> deduped = validated
                .keyBy(r -> String.valueOf(field(r, "event_id")))
                .process(new DedupFunction(DLQ));

        // Optional enrichers
        String mmdbPath = System.getProperty("geoip.mmdb", "");
        final Enrichers enrichers = Enrichers.create(mmdbPath);

        var mapped = deduped.map((MapFunction<GenericRecord, EventRow>) record -> {
            EventRow row = new EventRow();
            row.game_id = str(field(record, "game_id"));
            row.environment = str(field(record, "environment"));
            row.ts_server = toTimestamp(field(record, "ts_server"));
            if (row.ts_server == null) row.ts_server = new Timestamp(System.currentTimeMillis());
            row.ts_client = toTimestamp(field(record, "ts_client"));
            if (row.ts_client == null) row.ts_client = row.ts_server;
            row.event_id = str(field(record, "event_id"));
            row.event_type = nz(str(field(record, "event_type")));
            row.event_name = str(field(record, "event_name"));
            row.user_id = nz(str(field(record, "user_id")));
            row.device_id = str(field(record, "device_id"));
            row.player_id = nz(str(field(record, "player_id")));
            row.character_id = nz(str(field(record, "character_id")));
            row.session_id = nz(str(field(record, "session_id")));
            row.platform = nz(str(field(record, "platform")));
            row.app_version = nz(str(field(record, "app_version")));
            row.sdk_version = nz(str(field(record, "sdk_version")));
            String currentCountry = nz(str(field(record, "country")));
            String clientIp = nz(str(field(record, "client_ip")));
            String userAgent = nz(str(field(record, "user_agent")));
            row.user_agent = userAgent;
            row.server_id = nz(str(field(record, "server_id")));
            row.guild_id = nz(str(field(record, "guild_id")));
            row.match_id = nz(str(field(record, "match_id")));
            row.level_id = nz(str(field(record, "level_id")));
            row.game_mode = nz(str(field(record, "game_mode")));
            row.difficulty = nz(str(field(record, "difficulty")));
            row.progression_path = nz(str(field(record, "progression_path")));
            row.order_id = nz(str(field(record, "order_id")));
            row.product_id = nz(str(field(record, "product_id")));
            row.receipt_hash = nz(str(field(record, "receipt_hash")));
            row.virtual_currency = nz(str(field(record, "virtual_currency")));
            row.virtual_amount = decimalOrZero(field(record, "virtual_amount"));
            row.item_id = nz(str(field(record, "item_id")));
            row.operation_id = nz(str(field(record, "operation_id")));
            row.operation_type = nz(str(field(record, "operation_type")));
            row.resource_id = nz(str(field(record, "resource_id")));
            row.resource_amount = decimalOrZero(field(record, "resource_amount"));
            row.flow_type = nz(str(field(record, "flow_type")));
            row.ad_network = nz(str(field(record, "ad_network")));
            row.ad_placement = nz(str(field(record, "ad_placement")));
            row.ad_format = nz(str(field(record, "ad_format")));
            row.ad_impression_id = nz(str(field(record, "ad_impression_id")));
            // Enrich country if empty and IP present
            if ((currentCountry == null || currentCountry.isEmpty()) && !clientIp.isEmpty()) {
                String c = enrichers.countryByIp(clientIp);
                if (c != null) currentCountry = c;
            }
            row.country = nz(currentCountry);
            // Merge UA parsed info into props_json
            Object pj = field(record, "props_json");
            String baseProps = pj == null ? "{}" : pj.toString();
            String uaFamily = enrichers.uaFamily(userAgent);
            String osFamily = enrichers.osFamily(userAgent);
            String deviceClass = enrichers.deviceClass(userAgent);
            String merged = baseProps;
            if (uaFamily != null && !uaFamily.isEmpty()) merged = mergeProps(merged, "ua_family", uaFamily);
            if (osFamily != null && !osFamily.isEmpty()) merged = mergeProps(merged, "os_family", osFamily);
            if (deviceClass != null && !deviceClass.isEmpty()) merged = mergeProps(merged, "device_class", deviceClass);
            row.props_json = merged;
            row.revenue_amount = decimalOrZero(field(record, "revenue_amount"));
            row.revenue_currency = nz(str(field(record, "revenue_currency")));
            return row;
        });

        var sink = JdbcSink.<EventRow>sink(
                "INSERT INTO events (" +
                        "game_id, environment, ts_server, ts_client, event_id, event_type, event_name, " +
                        "user_id, device_id, player_id, character_id, session_id, " +
                        "platform, app_version, sdk_version, country, user_agent, " +
                        "server_id, guild_id, match_id, level_id, game_mode, difficulty, progression_path, " +
                        "order_id, product_id, revenue_amount, revenue_currency, receipt_hash, " +
                        "virtual_currency, virtual_amount, flow_type, item_id, operation_id, operation_type, " +
                        "resource_id, resource_amount, " +
                        "ad_network, ad_placement, ad_format, ad_impression_id, props_json" +
                        ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (ps, r) -> {
                    ps.setString(1, r.game_id);
                    ps.setString(2, r.environment);
                    ps.setTimestamp(3, r.ts_server);
                    ps.setTimestamp(4, r.ts_client);
                    ps.setString(5, r.event_id);
                    ps.setString(6, r.event_type);
                    ps.setString(7, r.event_name);
                    ps.setString(8, r.user_id);
                    ps.setString(9, r.device_id);
                    ps.setString(10, r.player_id);
                    ps.setString(11, r.character_id);
                    ps.setString(12, r.session_id);
                    ps.setString(13, r.platform);
                    ps.setString(14, r.app_version);
                    ps.setString(15, r.sdk_version);
                    ps.setString(16, r.country);
                    ps.setString(17, r.user_agent);
                    ps.setString(18, r.server_id);
                    ps.setString(19, r.guild_id);
                    ps.setString(20, r.match_id);
                    ps.setString(21, r.level_id);
                    ps.setString(22, r.game_mode);
                    ps.setString(23, r.difficulty);
                    ps.setString(24, r.progression_path);
                    ps.setString(25, r.order_id);
                    ps.setString(26, r.product_id);
                    ps.setBigDecimal(27, r.revenue_amount);
                    ps.setString(28, r.revenue_currency);
                    ps.setString(29, r.receipt_hash);
                    ps.setString(30, r.virtual_currency);
                    ps.setBigDecimal(31, r.virtual_amount);
                    ps.setString(32, r.flow_type);
                    ps.setString(33, r.item_id);
                    ps.setString(34, r.operation_id);
                    ps.setString(35, r.operation_type);
                    ps.setString(36, r.resource_id);
                    ps.setBigDecimal(37, r.resource_amount);
                    ps.setString(38, r.ad_network);
                    ps.setString(39, r.ad_placement);
                    ps.setString(40, r.ad_format);
                    ps.setString(41, r.ad_impression_id);
                    ps.setString(42, r.props_json);
                },
                JdbcExecutionOptions.builder().withBatchIntervalMs(200).withBatchSize(2000).withMaxRetries(3).build(),
                new JdbcConnectionOptions.JdbcConnectionOptionsBuilder()
                        .withUrl(chUrl)
                        .withDriverName("com.clickhouse.jdbc.ClickHouseDriver")
                        .withUsername(chUser)
                        .withPassword(chPass)
                        .build()
        );

        mapped.addSink(sink).name("clickhouse-sink");

        // DLQ: 输出到 Kafka 文本主题（JSON 字符串）
        KafkaSink<String> dlqSink = KafkaSink.<String>builder()
                .setBootstrapServers(bootstrap)
                .setRecordSerializer(KafkaRecordSerializationSchema.builder()
                        .setTopic(System.getProperty("kafka.dlq", "oddsmaker.deadletter"))
                        .setValueSerializationSchema(new org.apache.flink.api.common.serialization.SimpleStringSchema())
                        .build())
                .build();

        validated.getSideOutput(DLQ).sinkTo(dlqSink).name("dlq-sink");

        env.execute("oddsmaker-events-enrich");
    }

    private static String str(Object v) { return v == null ? null : v.toString(); }
    private static String nz(String s) { return s == null ? "" : s; }
    private static Object field(GenericRecord record, String name) {
        if (record == null || record.getSchema() == null || record.getSchema().getField(name) == null) {
            return null;
        }
        return record.get(name);
    }
    private static Long longOrNull(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.longValue();
        try { return Long.parseLong(value.toString()); } catch (Exception e) { return null; }
    }
    private static BigDecimal decimalOrZero(Object value) {
        if (value == null) return BigDecimal.ZERO;
        if (value instanceof BigDecimal bd) return bd;
        if (value instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try { return new BigDecimal(value.toString()); } catch (Exception e) { return BigDecimal.ZERO; }
    }
    private static Timestamp toTimestamp(Object micros) {
        if (micros == null) return null;
        if (micros instanceof Long) return new Timestamp(((Long) micros) / 1000L);
        try { return new Timestamp(Long.parseLong(micros.toString()) / 1000L); } catch (Exception e) { return null; }
    }

    public static class EventRow {
        public String game_id;
        public String environment;
        public Timestamp ts_server;
        public Timestamp ts_client;
        public String event_id;
        public String event_type;
        public String event_name;
        public String user_id;
        public String device_id;
        public String player_id;
        public String character_id;
        public String session_id;
        public String platform;
        public String app_version;
        public String sdk_version;
        public String country;
        public String user_agent;
        public String server_id;
        public String guild_id;
        public String match_id;
        public String level_id;
        public String game_mode;
        public String difficulty;
        public String progression_path;
        public String order_id;
        public String product_id;
        public String receipt_hash;
        public String virtual_currency;
        public BigDecimal virtual_amount;
        public String item_id;
        public String operation_id;
        public String operation_type;
        public String resource_id;
        public BigDecimal resource_amount;
        public String flow_type;
        public String ad_network;
        public String ad_placement;
        public String ad_format;
        public String ad_impression_id;
        public String props_json;
        public BigDecimal revenue_amount;
        public String revenue_currency;
    }

    // 去重函数：按 event_id 维度，7天 TTL；重复事件输出到 DLQ 侧输出
    public static class DedupFunction extends KeyedProcessFunction<String, GenericRecord, GenericRecord> {
        private final OutputTag<String> dlq;
        private transient ValueState<Long> seenTs;

        public DedupFunction(OutputTag<String> dlq) { this.dlq = dlq; }

        @Override
        public void open(org.apache.flink.configuration.Configuration parameters) {
            StateTtlConfig ttl = StateTtlConfig.newBuilder(Time.days(7))
                    .setUpdateType(StateTtlConfig.UpdateType.OnCreateAndWrite)
                    .setStateVisibility(StateTtlConfig.StateVisibility.ReturnExpiredIfNotCleanedUp)
                    .build();
            ValueStateDescriptor<Long> desc = new ValueStateDescriptor<>("seen_ts", Long.class);
            desc.enableTimeToLive(ttl);
            seenTs = getRuntimeContext().getState(desc);
        }

        @Override
        public void processElement(GenericRecord value, Context ctx, Collector<GenericRecord> out) throws Exception {
            Long seen = seenTs.value();
            if (seen != null) {
                ctx.output(dlq, toDlqJson(value, "duplicate"));
                return;
            }
            seenTs.update(System.currentTimeMillis());
            out.collect(value);
        }
    }

    private static String toDlqJson(GenericRecord r, String reason) {
        try {
            Object eventId = field(r, "event_id");
            String id = eventId == null ? "" : eventId.toString();
            return "{\"event_id\":\"" + id + "\",\"reason\":\"" + reason + "\"}";
        } catch (Exception e) { return "{\"event_id\":\"\",\"reason\":\""+reason+"\"}"; }
    }

    // Enricher holder
    static class Enrichers {
        private final DatabaseReader geoip;
        private final UserAgentAnalyzer uaa;

        private Enrichers(DatabaseReader geoip, UserAgentAnalyzer uaa) {
            this.geoip = geoip; this.uaa = uaa;
        }

        static Enrichers create(String mmdbPath) {
            DatabaseReader dr = null; UserAgentAnalyzer uaa = null;
            try {
                if (mmdbPath != null && !mmdbPath.isBlank() && new File(mmdbPath).exists()) {
                    dr = new DatabaseReader.Builder(new File(mmdbPath)).build();
                }
            } catch (IOException e) { dr = null; }
            try {
                uaa = UserAgentAnalyzer.newBuilder().hideMatcherLoadStats().withCache(10000).build();
            } catch (Exception e) { uaa = null; }
            return new Enrichers(dr, uaa);
        }

        String countryByIp(String ip) {
            if (geoip == null) return null;
            try {
                InetAddress addr = InetAddress.getByName(ip);
                CityResponse resp = geoip.city(addr);
                if (resp.getCountry() != null && resp.getCountry().getIsoCode() != null) {
                    return resp.getCountry().getIsoCode();
                }
                return null;
            } catch (IOException | GeoIp2Exception e) {
                return null;
            }
        }

        String uaFamily(String ua) {
            if (uaa == null || ua == null || ua.isEmpty()) return null;
            try {
                UserAgent parsed = uaa.parse(ua);
                return parsed.getValue("AgentName");
            } catch (Exception e) { return null; }
        }

        String osFamily(String ua) {
            if (uaa == null || ua == null || ua.isEmpty()) return null;
            try {
                UserAgent parsed = uaa.parse(ua);
                return parsed.getValue("OperatingSystemName");
            } catch (Exception e) { return null; }
        }

        String deviceClass(String ua) {
            if (uaa == null || ua == null || ua.isEmpty()) return null;
            try {
                UserAgent parsed = uaa.parse(ua);
                return parsed.getValue("DeviceClass");
            } catch (Exception e) { return null; }
        }
    }

    // Merge a single string key/value into existing JSON object string; fall back to concat if invalid
    private static String mergeProps(String json, String key, String value) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode node = om.readTree(json);
            if (!node.isObject()) return json; // keep original if not object
            com.fasterxml.jackson.databind.node.ObjectNode obj = (com.fasterxml.jackson.databind.node.ObjectNode) node;
            if (!obj.has(key)) obj.put(key, value);
            return om.writeValueAsString(obj);
        } catch (Exception e) {
            // naive fallback
            if (json == null || json.isEmpty() || json.equals("{}")) {
                return "{\""+key+"\":\""+value.replace("\"","\\\"")+"\"}";
            }
            return json;
        }
    }
}
