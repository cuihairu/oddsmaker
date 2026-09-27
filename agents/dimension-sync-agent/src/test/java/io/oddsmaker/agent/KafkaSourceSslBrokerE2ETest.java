package io.oddsmaker.agent;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.SaslAuthenticationException;
import org.apache.kafka.common.errors.SslAuthenticationException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLHandshakeException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * kafka source TLS(SSL) 证书链真实 broker 端到端。
 * 环境：apache/kafka:3.7.0 单节点 KRaft，自签 CA（CN=oddsmaker-kafka-e2e-ca）签发
 * broker 证书（SAN=DNS:localhost,IP:127.0.0.1——kafka-clients 默认开启主机名校验，
 * 证书必须覆盖 advertised 主机名）。两个证书 listener：SSL（宿主 29107，单向证书链）+
 * SASL_SSL（宿主 29108，SCRAM-SHA-256 用户 dim-e2e）。
 * <b>SSL broker 或 truststore 文件不可达时整类 SKIP，不误报绿。</b>
 *
 * <p>核对点：①SSL 连通 + 消费回路 + 断点续传位点正确（checkpoint 自管位点跨轮推进）；
 * ②不可信信任链（客户端不配 truststore → JVM 默认 cacerts 不含自签 CA）表现为
 * SslAuthenticationException 同步上抛（cause 链 SSLHandshakeException → PKIX path
 * building failed），非静默降级；③SASL_SSL 与既有 SASL_PLAINTEXT 用例的差异仅在
 * 协议层叠加证书链（SCRAM 断言不重复造轮子，只验证组合可达 + 回路）。
 *
 * <p>环境编排（一次性，测试资源不含任何私钥材料）：
 * <pre>
 * # 1) 证书（目录任选，默认 build/e2e-tls-7c4x/（模块 build/ 下，gitignore 覆盖，可经 truststore sysprop 覆盖））
 * openssl req -new -x509 -keyout ca.key -out ca.crt -days 30 -subj "/CN=oddsmaker-kafka-e2e-ca" -passout pass:ca-secret
 * openssl genrsa -out broker.key 2048
 * openssl req -new -key broker.key -out broker.csr -subj "/CN=localhost"
 * printf "subjectAltName=DNS:localhost,IP:127.0.0.1\nbasicConstraints=CA:FALSE\n" > san.ext
 * openssl x509 -req -in broker.csr -CA ca.crt -CAkey ca.key -CAcreateserial -out broker.crt -days 30 -extfile san.ext -passin pass:ca-secret
 * openssl pkcs12 -export -in broker.crt -inkey broker.key -certfile ca.crt -name broker -out broker.p12 -passout pass:broker-secret
 * keytool -importcert -file ca.crt -alias ca -keystore client.p12 -storetype PKCS12 -storepass client-secret -noprompt
 * mkdir -p secrets &amp;&amp; cp broker.p12 client.p12 secrets/ \
 *   &amp;&amp; printf broker-secret &gt; secrets/key_creds &amp;&amp; printf broker-secret &gt; secrets/keystore_creds
 * # jaas.conf 放 KafkaServer ScramLoginModule 占位（inter-broker 走 PLAINTEXT，不实际参与）
 *
 * # 2) broker（listener 名必须叫 SSL / SASL_SSL：镜像 configure 脚本按字面量 "SSL://" 匹配 advertised 才注入 keystore）
 * docker run -d --name oddsmaker-kafka-ssl-e2e --add-host kafka:127.0.0.1 -p 29107:29107 -p 29108:29108 \
 *   -v &lt;证书目录&gt;/secrets:/etc/kafka/secrets \
 *   -e KAFKA_NODE_ID=1 -e KAFKA_PROCESS_ROLES=broker,controller \
 *   -e KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093,SSL://:29107,SASL_SSL://:29108 \
 *   -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://kafka:9092,SSL://localhost:29107,SASL_SSL://localhost:29108 \
 *   -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@kafka:9093 \
 *   -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT,SSL:SSL,SASL_SSL:SASL_SSL \
 *   -e KAFKA_INTER_BROKER_LISTENER_NAME=PLAINTEXT \
 *   -e KAFKA_SSL_KEYSTORE_FILENAME=broker.p12 -e KAFKA_SSL_KEY_CREDENTIALS=key_creds \
 *   -e KAFKA_SSL_KEYSTORE_CREDENTIALS=keystore_creds \
 *   -e KAFKA_OPTS=-Djava.security.auth.login.config=/etc/kafka/secrets/jaas.conf \
 *   -e KAFKA_SASL_ENABLED_MECHANISMS=SCRAM-SHA-256 \
 *   -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1 \
 *   -e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1 -e KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS=0 \
 *   apache/kafka:3.7.0
 * # 3) SASL_SSL 臂的 SCRAM 用户（走容器内 PLAINTEXT）
 * docker exec oddsmaker-kafka-ssl-e2e /opt/kafka/bin/kafka-configs.sh --bootstrap-server kafka:9092 \
 *   --alter --add-config 'SCRAM-SHA-256=[password=dim-e2e-secret]' --entity-type users --entity-name dim-e2e
 * </pre>
 */
class KafkaSourceSslBrokerE2ETest {

    private static final String BOOTSTRAP =
            System.getProperty("oddsmaker.kafka.e2e.ssl.bootstrap", "localhost:29107");
    private static final String SASL_BOOTSTRAP =
            System.getProperty("oddsmaker.kafka.e2e.saslssl.bootstrap", "localhost:29108");
    private static final String USER = System.getProperty("oddsmaker.kafka.e2e.saslssl.user", "dim-e2e");
    private static final String PASSWORD = "dim-e2e-secret";
    /** 客户端信任库（PKCS12，仅含自签 CA 公钥）——与 broker keystore 同目录生成，测试不落仓库 */
    private static final String TRUSTSTORE = System.getProperty("oddsmaker.kafka.e2e.ssl.truststore",
            "build/e2e-tls-7c4x/client.p12");
    private static final String TRUSTSTORE_PASSWORD = System.getProperty(
            "oddsmaker.kafka.e2e.ssl.truststore.password", "client-secret");

    private static final List<String> topics = new ArrayList<>();

    @BeforeAll
    static void requireSslBroker() {
        String skipMsg = "SSL kafka broker 或 truststore 不可达（" + BOOTSTRAP + " / " + TRUSTSTORE
                + "），TLS 用例跳过——按类 javadoc 起 SSL 容器（证书有效期 30 天，过期重新生成）后重跑";
        if (!Files.isRegularFile(Path.of(TRUSTSTORE))) {
            Assumptions.assumeTrue(false, skipMsg);
        }
        Assumptions.assumeTrue(sslBrokerReachable(), skipMsg);
    }

    /** 客户端 SSL props（与 KafkaConsumerAdapter.applySecurityProps 同构，测试侧独立拼装） */
    private static Map<String, Object> sslProps(String bootstrap) {
        Map<String, Object> props = new HashMap<>();
        props.put("security.protocol", "SSL");
        props.put("ssl.truststore.location", TRUSTSTORE);
        props.put("ssl.truststore.password", TRUSTSTORE_PASSWORD);
        props.put("ssl.truststore.type", "PKCS12");
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        return props;
    }

    private static Map<String, Object> saslSslProps(String bootstrap, String user, String password) {
        Map<String, Object> props = sslProps(bootstrap);
        props.put("security.protocol", "SASL_SSL");
        props.put("sasl.mechanism", "SCRAM-SHA-256");
        props.put("sasl.jaas.config",
                "org.apache.kafka.common.security.scram.ScramLoginModule required "
                        + "username=\"" + user + "\" password=\"" + password + "\";");
        return props;
    }

    private static boolean sslBrokerReachable() {
        for (int attempt = 1; attempt <= 2; attempt++) {
            Map<String, Object> props = sslProps(BOOTSTRAP);
            props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "5000");
            props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "10000");
            try (AdminClient admin = AdminClient.create(props)) {
                // describeCluster 走 TLS 握手 + 证书链校验：信任链对不对这一步就见分晓
                admin.describeCluster().nodes().get(10, TimeUnit.SECONDS);
                return true;
            } catch (Exception e) {
                System.err.println("[e2e] SSL broker 可达性检查未通过（尝试 " + attempt + "/2）: " + e);
            }
        }
        return false;
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (topics.isEmpty()) {
            return;
        }
        try (AdminClient admin = AdminClient.create(sslProps(BOOTSTRAP))) {
            admin.deleteTopics(topics).all().get(10, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // 清理失败不影响结论（topic 名带 run 唯一后缀，broker 为一次性环境）
        }
    }

    // ── 工具 ──────────────────────────────────────────────────────

    private static String newTopic() throws Exception {
        String name = "e2e-dimsync-ssl-" + UUID.randomUUID().toString().substring(0, 8);
        try (AdminClient admin = AdminClient.create(sslProps(BOOTSTRAP))) {
            admin.createTopics(List.of(new NewTopic(name, 1, (short) 1))).all()
                    .get(10, TimeUnit.SECONDS);
        }
        topics.add(name);
        return name;
    }

    private static void produce(Map<String, Object> props, String topic, String... jsons) throws Exception {
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        try (KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>(props)) {
            for (String json : jsons) {
                producer.send(new ProducerRecord<>(topic, json.getBytes(StandardCharsets.UTF_8)))
                        .get(10, TimeUnit.SECONDS);
            }
        }
    }

    /** SSL 模式完整 AgentConfig（信任库三键走真实配置链路，validate() 全程真校验） */
    private static AgentConfig sslCfg(String topic) {
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
        cfg.kafkaSecurityProtocol = "SSL";
        cfg.kafkaSslTruststorePath = TRUSTSTORE;
        cfg.kafkaSslTruststorePassword = TRUSTSTORE_PASSWORD;
        cfg.kafkaSslTruststoreType = "PKCS12";
        cfg.validate();
        return cfg;
    }

    private static String json(String resourceId) {
        return "{\"resource_id\":\"" + resourceId + "\",\"version_ts\":1735689605000}";
    }

    // ── 用例 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("SSL 回路：truststore 经 AgentConfig → adapter 注入 → TLS 握手 → 消费 + 断点续传位点正确")
    void sslRoundtripAndResume() throws Exception {
        String topic = newTopic();
        produce(sslProps(BOOTSTRAP), topic, json("t0"), json("t1"), json("t2"));

        AgentConfig cfg = sslCfg(topic);
        KafkaSource s = new KafkaSource(cfg, new KafkaConsumerAdapter(
                cfg.kafkaBootstrap, "e2e-ssl-grp-" + topic, cfg.kafkaTopic,
                cfg.kafkaSecurityProtocol, cfg.kafkaSaslMechanism,
                cfg.kafkaUsername, cfg.kafkaPassword,
                cfg.kafkaSslTruststorePath, cfg.kafkaSslTruststorePassword,
                cfg.kafkaSslTruststoreType));
        DimensionSource.PollResult r1 = s.poll(new Checkpoint());
        assertEquals(3, r1.changes().size());
        assertEquals("t0", r1.changes().get(0).resourceId);
        assertEquals("t2", r1.changes().get(2).resourceId);
        assertEquals("k:0=3", r1.next().cursor);

        // 断点续传：以首轮位点为起点二次 poll，只消费新增，位点精确推进
        produce(sslProps(BOOTSTRAP), topic, json("t3"), json("t4"));
        DimensionSource.PollResult r2 = s.poll(r1.next());
        assertEquals(2, r2.changes().size());
        assertEquals("t3", r2.changes().get(0).resourceId);
        assertEquals("t4", r2.changes().get(1).resourceId);
        assertEquals("k:0=5", r2.next().cursor);
    }

    @Test
    @DisplayName("不可信信任链：客户端不配 truststore（JVM 默认 cacerts 不含自签 CA）→ SslAuthenticationException 同步可见，非静默降级")
    void untrustedChainFailsLoudly() throws Exception {
        // 实测：TLS 握手失败 broker 拒链（alert 40），客户端重试到 api timeout 后
        // partitionsFor 同步上抛 SslAuthenticationException，cause 链为
        // SSLHandshakeException → ValidatorException → SunCertPathBuilderException
        // （PKIX path building failed）。若走 KafkaSource 整链路，该异常在 poll 上抛出
        // 并计入 agent 的 errorCount/lastError（fail-visible，不会静默空轮询）
        String topic = newTopic();
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, "5000");
        props.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "8000");
        props.put("security.protocol", "SSL");  // 不配 ssl.truststore.*：走 JVM 默认信任库
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(props)) {
            long start = System.currentTimeMillis();
            SslAuthenticationException ex = assertThrows(SslAuthenticationException.class,
                    () -> consumer.partitionsFor(topic));
            long elapsed = System.currentTimeMillis() - start;
            assertTrue(elapsed < 30_000, "信任链失败应在 api timeout 内可见（实测 " + elapsed + "ms）");
            assertTrue(String.valueOf(ex.getMessage()).contains("SSL handshake failed"));
            // cause 链必须钉到 TLS 证书校验根因（PKIX），不能只有笼统的握手失败
            boolean pkix = false;
            for (Throwable t = ex.getCause(); t != null; t = t.getCause()) {
                if (t instanceof SSLHandshakeException && String.valueOf(t.getMessage()).contains("PKIX")) {
                    pkix = true;
                    break;
                }
            }
            assertTrue(pkix, "cause 链应含 PKIX path building failed（证书不被信任的确定性根因）: " + ex.getCause());
        }
    }

    @Test
    @DisplayName("SASL_SSL 组合：SCRAM 凭证 + 证书链同链路握手 → 回路可达（SASL 断言见 SASL_PLAINTEXT 用例，不重复造轮子）")
    void saslSslRoundtrip() throws Exception {
        // SASL_SSL 臂环境（29108 listener + SCRAM 用户）单独探活：部分环境只起 SSL 臂
        boolean saslSslReachable;
        Map<String, Object> probe = saslSslProps(SASL_BOOTSTRAP, USER, PASSWORD);
        probe.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "5000");
        probe.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "10000");
        try (AdminClient admin = AdminClient.create(probe)) {
            admin.describeCluster().nodes().get(10, TimeUnit.SECONDS);
            saslSslReachable = true;
        } catch (Exception e) {
            System.err.println("[e2e] SASL_SSL broker 不可达（" + SASL_BOOTSTRAP + "）: " + e);
            saslSslReachable = false;
        }
        Assumptions.assumeTrue(saslSslReachable,
                "SASL_SSL listener 不可达（" + SASL_BOOTSTRAP + "），组合臂跳过——SCRAM 行为已由 "
                        + "SASL_PLAINTEXT 用例覆盖，本臂只验协议层叠加证书链");

        String topic = newTopic();
        produce(saslSslProps(SASL_BOOTSTRAP, USER, PASSWORD), topic, json("x0"), json("x1"));

        AgentConfig cfg = sslCfg(topic);
        cfg.kafkaBootstrap = SASL_BOOTSTRAP;
        cfg.kafkaSecurityProtocol = "SASL_SSL";
        cfg.kafkaSaslMechanism = "SCRAM-SHA-256";
        cfg.kafkaUsername = USER;
        cfg.kafkaPassword = PASSWORD;
        cfg.validate();
        KafkaSource s = new KafkaSource(cfg, new KafkaConsumerAdapter(
                cfg.kafkaBootstrap, "e2e-saslssl-grp-" + topic, cfg.kafkaTopic,
                cfg.kafkaSecurityProtocol, cfg.kafkaSaslMechanism,
                cfg.kafkaUsername, cfg.kafkaPassword,
                cfg.kafkaSslTruststorePath, cfg.kafkaSslTruststorePassword,
                cfg.kafkaSslTruststoreType));
        DimensionSource.PollResult r = s.poll(new Checkpoint());
        assertEquals(2, r.changes().size());
        assertEquals("x0", r.changes().get(0).resourceId);
        assertEquals("x1", r.changes().get(1).resourceId);
        assertEquals("k:0=2", r.next().cursor);

        // 错误凭证在 SASL_SSL 下同样确定性可见（协议叠加不吞鉴权失败）
        Map<String, Object> wrong = saslSslProps(SASL_BOOTSTRAP, USER, "wrong-password");
        wrong.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        wrong.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        wrong.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, "5000");
        wrong.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "8000");
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(wrong)) {
            assertThrows(SaslAuthenticationException.class, () -> consumer.partitionsFor(topic));
        }
    }
}
