package io.pit.jobs.enrich;

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
        String topic = System.getProperty("kafka.topic", "pit.events_raw");
        String chUrl = System.getProperty("clickhouse.url", "jdbc:clickhouse://localhost:8123/default");
        String chUser = System.getProperty("clickhouse.user", "default");
        String chPass = System.getProperty("clickhouse.pass", "");

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        KafkaSource<GenericRecord> source = KafkaSource.<GenericRecord>builder()
                .setBootstrapServers(bootstrap)
                .setTopics(topic)
                .setGroupId("pit-events-enrich")
                .setStartingOffsets(OffsetsInitializer.latest())
                .setDeserializer(new ApicurioAvroFlinkDeserializer(registry))
                .build();

        var wm = WatermarkStrategy.<GenericRecord>forBoundedOutOfOrderness(Duration.ofMinutes(5))
                .withTimestampAssigner((SerializableTimestampAssigner<GenericRecord>) (element, recordTimestamp) -> {
                    Long tsServer = (Long) element.get("ts_server");
                    Long tsClient = (Long) element.get("ts_client");
                    long micros = tsServer != null ? tsServer : (tsClient != null ? tsClient : System.currentTimeMillis() * 1000L);
                    return micros / 1000L; // to ms
                });

        DataStream<GenericRecord> stream = env.fromSource(source, wm, "events-raw");

        // DLQ 侧输出
        final OutputTag<String> DLQ = new OutputTag<>("dlq", Types.STRING);

        // 基础校验（必填字段）+ 填充 ts_server
        DataStream<GenericRecord> validated = stream.process(new org.apache.flink.streaming.api.functions.ProcessFunction<GenericRecord, GenericRecord>() {
            @Override
            public void processElement(GenericRecord r, Context ctx, Collector<GenericRecord> out) throws Exception {
                if (r.get("event_id") == null || r.get("event_name") == null || r.get("tenant_id") == null || r.get("app_id") == null || r.get("device_id") == null) {
                    ctx.output(DLQ, toDlqJson(r, "invalid_schema"));
                    return;
                }
                if (r.get("ts_server") == null) {
                    r.put("ts_server", System.currentTimeMillis() * 1000L); // micros
                }
                out.collect(r);
            }
        });

        // 去重（按 event_id，7 天 TTL）
        DataStream<GenericRecord> deduped = validated
                .keyBy(r -> String.valueOf(r.get("event_id")))
                .process(new DedupFunction(DLQ));

        // Optional enrichers
        String mmdbPath = System.getProperty("geoip.mmdb", "");
        final Enrichers enrichers = Enrichers.create(mmdbPath);

        var mapped = deduped.map((MapFunction<GenericRecord, EventRow>) record -> {
            EventRow row = new EventRow();
            row.tenant_id = str(record.get("tenant_id"));
            row.app_id = str(record.get("app_id"));
            row.ts_server = toTimestamp(record.get("ts_server"));
            if (row.ts_server == null) row.ts_server = new Timestamp(System.currentTimeMillis());
            row.event_id = str(record.get("event_id"));
            row.event_name = str(record.get("event_name"));
            row.user_id = nz(str(record.get("user_id")));
            row.device_id = str(record.get("device_id"));
            row.session_id = nz(str(record.get("session_id")));
            row.platform = nz(str(record.get("platform")));
            row.app_version = nz(str(record.get("app_version")));
            String currentCountry = nz(str(record.get("country")));
            String clientIp = nz(str(record.get("client_ip")));
            String userAgent = nz(str(record.get("user_agent")));
            // Enrich country if empty and IP present
            if ((currentCountry == null || currentCountry.isEmpty()) && !clientIp.isEmpty()) {
                String c = enrichers.countryByIp(clientIp);
                if (c != null) currentCountry = c;
            }
            row.country = nz(currentCountry);
            // Merge UA parsed info into props_json
            Object pj = record.get("props_json");
            String baseProps = pj == null ? "{}" : pj.toString();
            String uaFamily = enrichers.uaFamily(userAgent);
            String osFamily = enrichers.osFamily(userAgent);
            String deviceClass = enrichers.deviceClass(userAgent);
            String merged = baseProps;
            if (uaFamily != null && !uaFamily.isEmpty()) merged = mergeProps(merged, "ua_family", uaFamily);
            if (osFamily != null && !osFamily.isEmpty()) merged = mergeProps(merged, "os_family", osFamily);
            if (deviceClass != null && !deviceClass.isEmpty()) merged = mergeProps(merged, "device_class", deviceClass);
            row.props_json = merged;
            row.revenue_amount = BigDecimal.ZERO; // 表为非 Nullable，使用默认 0
            row.revenue_currency = "USD";
            return row;
        });

        var sink = JdbcSink.sink(
                "INSERT INTO events (tenant_id, app_id, ts_server, event_id, event_name, user_id, device_id, session_id, platform, app_version, country, props_json, revenue_amount, revenue_currency) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (ps, r) -> {
                    ps.setString(1, r.tenant_id);
                    ps.setString(2, r.app_id);
                    ps.setTimestamp(3, r.ts_server);
                    ps.setString(4, r.event_id);
                    ps.setString(5, r.event_name);
                    ps.setString(6, r.user_id);
                    ps.setString(7, r.device_id);
                    ps.setString(8, r.session_id);
                    ps.setString(9, r.platform);
                    ps.setString(10, r.app_version);
                    ps.setString(11, r.country);
                    ps.setString(12, r.props_json);
                    ps.setBigDecimal(13, r.revenue_amount);
                    ps.setString(14, r.revenue_currency);
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
                        .setTopic(System.getProperty("kafka.dlq", "pit.deadletter"))
                        .setValueSerializationSchema(new org.apache.flink.api.common.serialization.SimpleStringSchema())
                        .build())
                .build();

        validated.getSideOutput(DLQ).sinkTo(dlqSink).name("dlq-sink");

        env.execute("pit-events-enrich");
    }

    private static String str(Object v) { return v == null ? null : v.toString(); }
    private static String nz(String s) { return s == null ? "" : s; }
    private static Timestamp toTimestamp(Object micros) {
        if (micros == null) return null;
        if (micros instanceof Long) return new Timestamp(((Long) micros) / 1000L);
        try { return new Timestamp(Long.parseLong(micros.toString()) / 1000L); } catch (Exception e) { return null; }
    }

    public static class EventRow {
        public String tenant_id;
        public String app_id;
        public Timestamp ts_server;
        public String event_id;
        public String event_name;
        public String user_id;
        public String device_id;
        public String session_id;
        public String platform;
        public String app_version;
        public String country;
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
            String id = r != null && r.get("event_id") != null ? r.get("event_id").toString() : "";
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
