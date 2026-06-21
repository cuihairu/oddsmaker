package io.oddsmaker.jobs.identity;

import io.oddsmaker.jobs.enrich.ApicurioAvroFlinkDeserializer;
import org.apache.avro.generic.GenericRecord;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.state.StateTtlConfig;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.connector.jdbc.JdbcConnectionOptions;
import org.apache.flink.connector.jdbc.JdbcExecutionOptions;
import org.apache.flink.connector.jdbc.JdbcSink;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

public class IdentityMergeJob {

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
                .setGroupId("oddsmaker-identity-merge")
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

        DataStream<IdentityRecord> identities = raw
                .filter((org.apache.flink.api.common.functions.FilterFunction<GenericRecord>) r -> {
                    Object t = r.get("event_type");
                    Object n = r.get("event_name");
                    return t != null && "identity".equals(t.toString())
                            || (n != null && "$identify".equals(n.toString()));
                })
                .returns(Types.GENERIC(GenericRecord.class))
                .keyBy(r -> nz(str(r.get("game_id"))) + "|" + nz(str(r.get("environment"))) + "|" + nz(str(r.get("user_id"))))
                .process(new IdentityMergeFunction())
                .name("identity-merge");

        var sink = JdbcSink.<IdentityRecord>sink(
                "INSERT INTO identities " +
                        "(game_id, environment, identity_id, user_id, player_id, character_ids, device_ids, first_seen, last_seen, risk_score) " +
                        "VALUES (?, ?, ?, ?, ?, split('||', ?), split('||', ?), ?, ?, 0)",
                (ps, r) -> {
                    ps.setString(1, r.gameId);
                    ps.setString(2, r.environment);
                    ps.setString(3, r.identityId);
                    ps.setString(4, r.userId);
                    ps.setString(5, r.playerId);
                    ps.setString(6, joinList(r.characterIds));
                    ps.setString(7, joinList(r.deviceIds));
                    ps.setTimestamp(8, r.firstSeen);
                    ps.setTimestamp(9, r.lastSeen);
                },
                JdbcExecutionOptions.builder().withBatchIntervalMs(500).withBatchSize(500).withMaxRetries(3).build(),
                new JdbcConnectionOptions.JdbcConnectionOptionsBuilder()
                        .withUrl(chUrl)
                        .withDriverName("com.clickhouse.jdbc.ClickHouseDriver")
                        .withUsername(chUser)
                        .withPassword(chPass)
                        .build()
        );

        identities.addSink(sink).name("clickhouse-identities");

        env.execute("oddsmaker-identity-merge");
    }

    private static String joinList(Set<String> list) {
        if (list == null || list.isEmpty()) return "";
        return String.join("||", list);
    }

    public static final class IdentityState {
        public String identityId;
        public Set<String> deviceIds = new LinkedHashSet<>();
        public Set<String> playerIds = new LinkedHashSet<>();
        public Set<String> characterIds = new LinkedHashSet<>();
        public Timestamp firstSeen;
        public Timestamp lastSeen;
    }

    public static final class IdentityRecord {
        public String gameId;
        public String environment;
        public String identityId;
        public String userId;
        public String playerId;
        public Set<String> characterIds = new LinkedHashSet<>();
        public Set<String> deviceIds = new LinkedHashSet<>();
        public Timestamp firstSeen;
        public Timestamp lastSeen;
    }

    public static class IdentityMergeFunction extends KeyedProcessFunction<String, GenericRecord, IdentityRecord> {
        private transient ValueState<IdentityState> state;

        @Override
        public void open(org.apache.flink.configuration.Configuration parameters) {
            StateTtlConfig ttl = StateTtlConfig.newBuilder(Time.days(90))
                    .setUpdateType(StateTtlConfig.UpdateType.OnCreateAndWrite)
                    .setStateVisibility(StateTtlConfig.StateVisibility.NeverReturnExpired)
                    .build();
            ValueStateDescriptor<IdentityState> desc = new ValueStateDescriptor<>("identity-state", IdentityState.class);
            desc.enableTimeToLive(ttl);
            state = getRuntimeContext().getState(desc);
        }

        @Override
        public void processElement(GenericRecord record, Context ctx, Collector<IdentityRecord> out) throws Exception {
            String gameId = str(record.get("game_id"));
            String environment = str(record.get("environment"));
            String userId = nz(str(record.get("user_id")));
            String deviceId = str(record.get("device_id"));
            if (gameId == null || environment == null || userId.isEmpty() || deviceId == null) return;

            String playerId = extractPlayerId(record);
            Timestamp ts = extractTs(record);

            IdentityState s = state.value();
            if (s == null) {
                s = new IdentityState();
                s.identityId = "idt_" + UUID.randomUUID().toString().replace("-", "");
                s.firstSeen = ts;
                s.lastSeen = ts;
                s.deviceIds.add(deviceId);
                if (playerId != null && !playerId.isEmpty()) s.playerIds.add(playerId);
            } else {
                s.deviceIds.add(deviceId);
                if (playerId != null && !playerId.isEmpty()) s.playerIds.add(playerId);
                if (ts != null) {
                    s.lastSeen = ts;
                    if (s.firstSeen == null || ts.before(s.firstSeen)) s.firstSeen = ts;
                }
            }

            state.update(s);

            IdentityRecord r = new IdentityRecord();
            r.gameId = gameId;
            r.environment = environment;
            r.identityId = s.identityId;
            r.userId = userId;
            r.playerId = s.playerIds.isEmpty() ? "" : s.playerIds.iterator().next();
            r.characterIds = new LinkedHashSet<>(s.characterIds);
            r.deviceIds = new LinkedHashSet<>(s.deviceIds);
            r.firstSeen = s.firstSeen;
            r.lastSeen = s.lastSeen;
            out.collect(r);
        }
    }

    private static String str(Object v) { return v == null ? null : v.toString(); }

    private static String nz(String s) { return s == null ? "" : s; }

    private static Timestamp extractTs(GenericRecord r) {
        Long tsServer = (Long) r.get("ts_server");
        Long tsClient = (Long) r.get("ts_client");
        long micros = tsServer != null ? tsServer : (tsClient != null ? tsClient : System.currentTimeMillis() * 1000L);
        return new Timestamp(micros / 1000L);
    }

    private static String extractPlayerId(GenericRecord r) {
        Object pid = r.get("player_id");
        if (pid != null && !pid.toString().isEmpty()) return pid.toString();
        Object pj = r.get("props_json");
        if (pj != null) {
            String json = pj.toString();
            int idx = json.indexOf("\"player_id\"");
            if (idx >= 0) {
                int colon = json.indexOf(':', idx);
                int q1 = json.indexOf('"', colon + 1);
                int q2 = json.indexOf('"', q1 + 1);
                if (q1 > 0 && q2 > q1) return json.substring(q1 + 1, q2);
            }
        }
        Object pm = r.get("props");
        if (pm instanceof java.util.Map) {
            Object v = ((java.util.Map<?, ?>) pm).get("player_id");
            if (v != null && !v.toString().isEmpty()) return v.toString();
        }
        return null;
    }
}
