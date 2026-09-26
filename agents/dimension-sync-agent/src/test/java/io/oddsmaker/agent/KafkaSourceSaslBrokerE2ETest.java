package io.oddsmaker.agent;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * kafka source SASL/SCRAM 鉴权真实 broker 端到端。
 * 环境：apache/kafka:3.7.0 单节点 KRaft，SASL_PLAINTEXT listener（宿主 29095）+
 * SCRAM-SHA-256 用户 dim-e2e（kafka-configs 动态建），另置不鉴权的内部 PLAINTEXT
 * listener 供管理操作建 topic/建用户。<b>鉴权 broker 不可达时整类 SKIP，不误报绿</b>。
 *
 * <p>核对点：SCRAM 凭证经 AgentConfig → KafkaConsumerAdapter JAAS 注入后真实握手
 * 通过并完整消费；错误凭证表现为 metadata 获取异常（KafkaException，非静默空轮询）。
 */
class KafkaSourceSaslBrokerE2ETest {

    private static final String BOOTSTRAP =
            System.getProperty("oddsmaker.kafka.e2e.sasl.bootstrap", "localhost:29095");
    private static final String USER = System.getProperty("oddsmaker.kafka.e2e.sasl.user", "dim-e2e");
    private static final String PASSWORD = "dim-e2e-secret";

    private static final List<String> topics = new ArrayList<>();

    @BeforeAll
    static void requireSaslBroker() {
        Assumptions.assumeTrue(saslBrokerReachable(),
                "SASL kafka broker 不可达（" + BOOTSTRAP + "），SASL 用例跳过——"
                        + "起 SASL_PLAINTEXT+SCRAM 容器后重跑（见类 javadoc）");
    }

    /** 客户端 SCRAM props（与 KafkaConsumerAdapter.applySecurityProps 同构，测试侧独立拼装） */
    private static Map<String, Object> saslProps(String user, String password) {
        Map<String, Object> props = new HashMap<>();
        props.put("security.protocol", "SASL_PLAINTEXT");
        props.put("sasl.mechanism", "SCRAM-SHA-256");
        props.put("sasl.jaas.config",
                "org.apache.kafka.common.security.scram.ScramLoginModule required "
                        + "username=\"" + user + "\" password=\"" + password + "\";");
        return props;
    }

    private static boolean saslBrokerReachable() {
        for (int attempt = 1; attempt <= 2; attempt++) {
            Map<String, Object> props = saslProps(USER, PASSWORD);
            props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
            props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "5000");
            props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "10000");
            try (AdminClient admin = AdminClient.create(props)) {
                // describeCluster 走 SASL 握手：凭证对不对这一步就见分晓
                admin.describeCluster().nodes().get(10, TimeUnit.SECONDS);
                return true;
            } catch (Exception e) {
                System.err.println("[e2e] SASL broker 可达性检查未通过（尝试 " + attempt + "/2）: " + e);
            }
        }
        return false;
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (topics.isEmpty()) {
            return;
        }
        Map<String, Object> props = saslProps(USER, PASSWORD);
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        try (AdminClient admin = AdminClient.create(props)) {
            admin.deleteTopics(topics).all().get(10, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // 清理失败不影响结论（topic 名带 run 唯一后缀，broker 为一次性环境）
        }
    }

    // ── 工具 ──────────────────────────────────────────────────────

    private static String newTopic() throws Exception {
        String name = "e2e-dimsync-sasl-" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> props = saslProps(USER, PASSWORD);
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        try (AdminClient admin = AdminClient.create(props)) {
            admin.createTopics(List.of(new NewTopic(name, 1, (short) 1))).all()
                    .get(10, TimeUnit.SECONDS);
        }
        topics.add(name);
        return name;
    }

    private static void produce(String topic, String... jsons) throws Exception {
        Map<String, Object> props = saslProps(USER, PASSWORD);
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        try (KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>(props)) {
            for (String json : jsons) {
                producer.send(new ProducerRecord<>(topic, json.getBytes(StandardCharsets.UTF_8)))
                        .get(10, TimeUnit.SECONDS);
            }
        }
    }

    private static AgentConfig saslCfg(String topic) {
        AgentConfig cfg = new AgentConfig();
        cfg.gatewayEndpoint = "http://gw";
        cfg.gatewayApiKey = "k";
        cfg.gameId = "g";
        cfg.environment = "prod";
        cfg.sourceType = "kafka";
        cfg.dimType = "item";
        cfg.kafkaBootstrap = BOOTSTRAP;
        cfg.kafkaTopic = topic;
        cfg.kafkaPollTimeoutMs = 3000;
        cfg.kafkaSecurityProtocol = "SASL_PLAINTEXT";
        cfg.kafkaSaslMechanism = "SCRAM-SHA-256";
        cfg.kafkaUsername = USER;
        cfg.kafkaPassword = PASSWORD;
        cfg.validate();
        return cfg;
    }

    private static String json(String resourceId) {
        return "{\"resource_id\":\"" + resourceId + "\",\"version_ts\":1735689605000}";
    }

    // ── 用例 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("SCRAM 鉴权回路：AgentConfig 凭证经 adapter 注入 → SASL 握手 → 全量消费 + 位点推进")
    void saslScramRoundtrip() throws Exception {
        String topic = newTopic();
        produce(topic, json("s0"), json("s1"), json("s2"));

        AgentConfig cfg = saslCfg(topic);
        KafkaSource s = new KafkaSource(cfg, new KafkaConsumerAdapter(
                cfg.kafkaBootstrap, "e2e-sasl-grp-" + topic, cfg.kafkaTopic,
                cfg.kafkaSecurityProtocol, cfg.kafkaSaslMechanism,
                cfg.kafkaUsername, cfg.kafkaPassword));
        DimensionSource.PollResult r = s.poll(new Checkpoint());

        assertEquals(3, r.changes().size());
        assertEquals("s0", r.changes().get(0).resourceId);
        assertEquals("s2", r.changes().get(2).resourceId);
        assertEquals("k:0=3", r.next().cursor);
    }

    @Test
    @DisplayName("错误凭证：SASL 握手失败表现为 metadata 获取异常（可观测），非静默空轮询")
    void wrongPasswordFailsLoudly() throws Exception {
        // 鉴权失败 broker 直接断链，客户端重试到超时——partitionsFor 抛 KafkaException。
        // 缩短 request/default api timeout 把该路径压到秒级；若走 KafkaSource 整链路，
        // 该异常会在 poll 上抛出并计入 agent 的 errorCount/lastError（fail-visible）
        String topic = newTopic();
        Map<String, Object> props = saslProps(USER, "wrong-password");
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, "5000");
        props.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "8000");
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(props)) {
            long start = System.currentTimeMillis();
            // 实测：SaslAuthenticationException（invalid credentials）经 metadata 请求同步上抛
            org.apache.kafka.common.errors.SaslAuthenticationException ex = assertThrows(
                    org.apache.kafka.common.errors.SaslAuthenticationException.class,
                    () -> consumer.partitionsFor(topic));
            long elapsed = System.currentTimeMillis() - start;
            assertTrue(elapsed < 30_000, "鉴权失败应在 api timeout 内可见（实测 " + elapsed + "ms）");
            assertTrue(String.valueOf(ex.getMessage()).contains("invalid credentials"));
        }
    }
}
