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
 * 环境：apache/kafka:3.7.0 单节点 KRaft，自签 CA 签发 broker 证书（SAN=DNS:localhost,
 * IP:127.0.0.1——kafka-clients 默认 endpoint identification=https 主机名校验，证书必须
 * 覆盖 advertised 主机名）。三个证书 listener：SSL（宿主 29097，单向证书链）+
 * SASL_SSL（宿主 29098，SCRAM-SHA-256 用户 dim-e2e）+ MTLSHOST（宿主 29099，listener 级
 * {@code ssl.client.auth=required}，broker 侧信任库校验客户端证书）。
 * <b>SSL broker 或 truststore 文件不可达时整类 SKIP，不误报绿；mTLS 臂与 SASL_SSL 臂
 * 各自单独探活——只起部分 listener 的环境跳过对应臂，不把「环境没起」报成「功能坏了」。</b>
 *
 * <p>核对点：①SSL 连通 + 消费回路 + 断点续传位点正确（checkpoint 自管位点跨轮推进）；
 * ②不可信信任链（客户端不配 truststore → JVM 默认 cacerts 不含自签 CA）表现为
 * SslAuthenticationException 同步上抛（cause 链 SSLHandshakeException → PKIX path
 * building failed），非静默降级；③SASL_SSL 与既有 SASL_PLAINTEXT 用例的差异仅在
 * 协议层叠加证书链（SCRAM 断言不重复造轮子，只验证组合可达 + 回路）；
 * ④mTLS 完整回路：{@link KafkaConsumerAdapter.SslSettings} 双材料（truststore + keystore）
 * 经 AgentConfig 真实校验链路注入，建 topic / 生产 / 消费全程走 required-client-auth
 * listener，位点跨轮精确推进；⑤mTLS 负臂：信任链正常但不出示客户端证书——实测（TLS1.3）
 * 拒绝发生在握手末段之后，顶层 {@code SslAuthenticationException("Failed to process
 * post-handshake messages")}，cause {@code SSLHandshakeException("(bad_certificate) Received
 * fatal alert: bad_certificate")}；形态与臂 ② 的 PKIX 明确可辨，且本臂已配 truststore 故
 * 断言必不含 PKIX —— 反证该 listener 真在要求客户端证书（否则负臂会以另一种形式通过）。
 *
 * <p>环境编排见 {@code src/test/ssl/}（生成配方 + docker 编排，私钥只落 OUT_DIR 不进仓库）：
 * <pre>
 * bash src/test/ssl/gen-pki.sh      # PKI → /tmp/oddsmaker-kafka-ssl-e2e（CA/broker SAN 证书/
 *                                   #   secrets/broker.p12 + broker-truststore.p12/客户端
 *                                   #   truststore/客户端 keystore（EKU=clientAuth），30 天有效）
 * bash src/test/ssl/run-broker.sh   # 起 oddsmaker-kafka-ssl-e2e（29097/29098/29099）+ 建 SCRAM 用户
 *                                   #   + 握手自检：单向 TLS、mTLS 正向（带客户端证书），
 *                                   #   mTLS 负向以 broker 日志「Failed authentication … SSL
 *                                   #   handshake failed」为判据（TLS1.3 下 openssl 侧看不出）
 * </pre>
 * 下述默认值与脚本产出一致；端口/路径/密码不同时经 System property 覆盖
 * （oddsmaker.kafka.e2e.ssl.bootstrap / saslssl.bootstrap / mtls.bootstrap / ssl.truststore /
 * ssl.truststore.password / mtls.keystore / mtls.keystore.password）。
 * 用完清理：{@code docker rm -f oddsmaker-kafka-ssl-e2e}。
 */
class KafkaSourceSslBrokerE2ETest {

    private static final String BOOTSTRAP =
            System.getProperty("oddsmaker.kafka.e2e.ssl.bootstrap", "localhost:29097");
    private static final String SASL_BOOTSTRAP =
            System.getProperty("oddsmaker.kafka.e2e.saslssl.bootstrap", "localhost:29098");
    private static final String USER = System.getProperty("oddsmaker.kafka.e2e.saslssl.user", "dim-e2e");
    private static final String PASSWORD = "dim-e2e-secret";
    /** 客户端信任库（PKCS12，仅含自签 CA 公钥）——gen-pki.sh 产出，与 broker keystore 同目录不落仓库 */
    private static final String TRUSTSTORE = System.getProperty("oddsmaker.kafka.e2e.ssl.truststore",
            "/tmp/oddsmaker-kafka-ssl-e2e/client-truststore.p12");
    private static final String TRUSTSTORE_PASSWORD = System.getProperty(
            "oddsmaker.kafka.e2e.ssl.truststore.password", "trust-secret");
    /** mTLS listener（broker 侧 listener.name.mtlshost.ssl.client.auth=required） */
    private static final String MTLS_BOOTSTRAP = System.getProperty(
            "oddsmaker.kafka.e2e.mtls.bootstrap", "localhost:29099");
    /** 客户端证书库（PKCS12：叶证书 + CA + 私钥，EKU=clientAuth）——gen-pki.sh 产出，私钥不落仓库 */
    private static final String KEYSTORE = System.getProperty(
            "oddsmaker.kafka.e2e.mtls.keystore", "/tmp/oddsmaker-kafka-ssl-e2e/client-keystore.p12");
    private static final String KEYSTORE_PASSWORD = System.getProperty(
            "oddsmaker.kafka.e2e.mtls.keystore.password", "client-secret");

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

    /** mTLS 完整证书材料：信任库（验服务端链）+ 证书库（出示客户端证书） */
    private static Map<String, Object> mtlsProps(String bootstrap) {
        Map<String, Object> props = sslProps(bootstrap);
        props.put("ssl.keystore.location", KEYSTORE);
        props.put("ssl.keystore.password", KEYSTORE_PASSWORD);
        props.put("ssl.key.password", KEYSTORE_PASSWORD);
        props.put("ssl.keystore.type", "PKCS12");
        return props;
    }

    /**
     * mTLS 臂单独探活（同 SASL_SSL 臂约定）：只起单向 TLS 两 listener 的环境里整组 mTLS
     * 用例 SKIP，不误报红。探活本身即一次完整双向握手——信任库/证书库/ listener 配置
     * 任一处缺失都会在这里暴露。
     */
    private static void requireMtlsArm() {
        Assumptions.assumeTrue(Files.isRegularFile(Path.of(KEYSTORE)),
                "客户端证书库不可达（" + KEYSTORE + "），mTLS 臂跳过——先跑 src/test/ssl/gen-pki.sh");
        Map<String, Object> probe = mtlsProps(MTLS_BOOTSTRAP);
        probe.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "5000");
        probe.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "10000");
        try (AdminClient admin = AdminClient.create(probe)) {
            admin.describeCluster().nodes().get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "mTLS listener 不可达（" + MTLS_BOOTSTRAP + "），"
                    + "mTLS 臂跳过——按 src/test/ssl/run-broker.sh 起 MTLSHOST listener 后重跑: " + e);
        }
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
        return newTopic(sslProps(BOOTSTRAP));
    }

    /** 建 topic 走哪条 listener 由传入的 admin props 决定（mTLS 臂即「管理面也走双向认证」） */
    private static String newTopic(Map<String, Object> adminProps) throws Exception {
        String name = "e2e-dimsync-ssl-" + UUID.randomUUID().toString().substring(0, 8);
        try (AdminClient admin = AdminClient.create(adminProps)) {
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

    /** mTLS 完整配置（单向 TLS 的 sslCfg 再加证书库三键），validate() 全程真校验 */
    private static AgentConfig mtlsCfg(String topic) {
        AgentConfig cfg = sslCfg(topic);
        cfg.kafkaBootstrap = MTLS_BOOTSTRAP;
        cfg.kafkaSslKeystorePath = KEYSTORE;
        cfg.kafkaSslKeystorePassword = KEYSTORE_PASSWORD;
        cfg.kafkaSslKeystoreType = "PKCS12";
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

    @Test
    @DisplayName("mTLS 回路：truststore + keystore 双材料经 AgentConfig → SslSettings 注入 → 双向握手 → 消费 + 断点续传位点精确推进")
    void mtlsRoundtripAndResume() throws Exception {
        requireMtlsArm();
        // 建 topic 与生产都走 mTLS listener：管理面/数据面全程双向认证，不经任何明文 listener
        String topic = newTopic(mtlsProps(MTLS_BOOTSTRAP));
        produce(mtlsProps(MTLS_BOOTSTRAP), topic, json("m0"), json("m1"), json("m2"));

        AgentConfig cfg = mtlsCfg(topic);
        KafkaSource s = new KafkaSource(cfg, new KafkaConsumerAdapter(
                cfg.kafkaBootstrap, "e2e-mtls-grp-" + topic, cfg.kafkaTopic,
                cfg.kafkaSecurityProtocol, cfg.kafkaSaslMechanism,
                cfg.kafkaUsername, cfg.kafkaPassword,
                new KafkaConsumerAdapter.SslSettings(
                        cfg.kafkaSslTruststorePath, cfg.kafkaSslTruststorePassword,
                        cfg.kafkaSslTruststoreType,
                        cfg.kafkaSslKeystorePath, cfg.kafkaSslKeystorePassword,
                        cfg.kafkaSslKeystoreType)));
        DimensionSource.PollResult r1 = s.poll(new Checkpoint());
        assertEquals(3, r1.changes().size());
        assertEquals("m0", r1.changes().get(0).resourceId);
        assertEquals("m2", r1.changes().get(2).resourceId);
        assertEquals("k:0=3", r1.next().cursor);

        // 断点续传：同一 mTLS 连接以首轮位点续拉，只消费新增，位点精确推进
        produce(mtlsProps(MTLS_BOOTSTRAP), topic, json("m3"), json("m4"));
        DimensionSource.PollResult r2 = s.poll(r1.next());
        assertEquals(2, r2.changes().size());
        assertEquals("m3", r2.changes().get(0).resourceId);
        assertEquals("m4", r2.changes().get(1).resourceId);
        assertEquals("k:0=5", r2.next().cursor);
    }

    @Test
    @DisplayName("mTLS 负臂：信任链正常但不出示客户端证书 → 握手被拒、同步上抛，根因是证书缺失而非 PKIX 不信任")
    void missingClientCertFailsLoudly() throws Exception {
        requireMtlsArm();
        // topic 经单向 TLS 管理面建（29097）：确保「消费侧连不上」不是「topic 不存在」
        String topic = newTopic();
        produce(sslProps(BOOTSTRAP), topic, json("n0"));

        Map<String, Object> props = sslProps(MTLS_BOOTSTRAP);  // 只配 truststore，不配 keystore
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, "5000");
        props.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "8000");
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(props)) {
            long start = System.currentTimeMillis();
            SslAuthenticationException ex = assertThrows(SslAuthenticationException.class,
                    () -> consumer.partitionsFor(topic));
            long elapsed = System.currentTimeMillis() - start;
            assertTrue(elapsed < 30_000, "缺客户端证书应在 api timeout 内可见（实测 " + elapsed + "ms）");
            StringBuilder chain = new StringBuilder();
            for (Throwable t = ex; t != null; t = t.getCause()) {
                chain.append(t.getClass().getName()).append(": ").append(t.getMessage()).append(" | ");
            }
            System.err.println("[e2e] mTLS 负臂异常链: " + chain);
            // 实测（TLS1.3）：缺客户端证书的拒绝发生在**握手之后**（CertificateRequest 是
            // 握手末段），故顶层不是臂 ② 那种 "SSL handshake failed"，而是
            // SslAuthenticationException("Failed to process post-handshake messages")，
            // cause = SSLHandshakeException("(bad_certificate) Received fatal alert: bad_certificate")
            // ——broker 以 alert 42 拒绝未出证的客户端（openssl s_client 侧看不到，见 run-broker.sh 注释）
            assertTrue(String.valueOf(ex.getMessage()).contains("post-handshake"),
                    "TLS1.3 下缺客户端证书应在 post-handshake 阶段可见，实测形态已变: " + chain);
            boolean rejectedByAlert = false;
            for (Throwable t = ex; t != null; t = t.getCause()) {
                if (t instanceof SSLHandshakeException
                        && String.valueOf(t.getMessage()).contains("bad_certificate")) {
                    rejectedByAlert = true;
                    break;
                }
            }
            assertTrue(rejectedByAlert, "cause 链应含 broker 侧 fatal alert bad_certificate（缺客户端证书的确定性根因）: " + chain);
            // 与臂 ② 区分开：本臂信任库配了（链是可信的），失败必须来自客户端证书缺失，
            // 根因不能是 PKIX——否则该 listener 其实没在要求客户端证书（配置漂移）
            assertTrue(!chain.toString().contains("PKIX"),
                    "本臂已配 truststore，根因不应是证书链不受信（PKIX），否则 client auth 未生效: " + chain);
        }
    }
}
