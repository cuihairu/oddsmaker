package io.oddsmaker.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 公开构造链的**真实 kafka-clients 构造**（不 assign、不 poll，故不触网）：
 * 离线 props 单测（{@link KafkaConsumerAdapterPropsTest}）只证明键值对拼对了，这里补上
 * 「kafka-clients 自己认不认这份 props」——配置非法在构造期即抛（fail-fast），
 * 合法则 consumer 建得起来且 {@code close()} 干净退出。
 *
 * <p>真实消费回路（assign/seek/poll/位点续传）不在此类范围：见 KafkaSource*BrokerE2ETest。
 */
class KafkaConsumerAdapterCtorTest {

    /** 无人监听的 bootstrap：构造期不建连，close 时后台线程立即退出。 */
    private static final String DEAD_BOOTSTRAP = "127.0.0.1:9999";

    @TempDir
    Path dir;

    @Test
    @DisplayName("3 参构造（明文最简入口）：consumer 建得起来，close 不抛")
    void minimalPlaintextCtorBuildsAndCloses() throws Exception {
        KafkaConsumerAdapter adapter = new KafkaConsumerAdapter(DEAD_BOOTSTRAP, "g-3arg", "dims");
        assertNotNull(adapter);
        adapter.close();
    }

    @Test
    @DisplayName("7 参构造（SASL/SCRAM）：JAAS 行能被 kafka-clients 真实解析（拼错在此暴露，而非运行期静默降级）")
    void saslCtorPropsAcceptedByKafkaClients() throws Exception {
        KafkaConsumerAdapter adapter = new KafkaConsumerAdapter(DEAD_BOOTSTRAP, "g-7arg", "dims",
                "SASL_PLAINTEXT", "SCRAM-SHA-256", "dim_ro", "s3cr3t");
        assertNotNull(adapter);
        adapter.close();
    }

    @Test
    @DisplayName("10 参构造（仅信任库便捷入口）：JKS 信任库路径注入后 consumer 构造成功")
    void truststoreConvenienceCtorBuilds() throws Exception {
        Path truststore = dir.resolve("trust.jks");
        try (OutputStream out = Files.newOutputStream(truststore)) {
            KeyStore ks = KeyStore.getInstance("JKS");
            ks.load(null, null);
            ks.store(out, "changeit".toCharArray());
        }
        KafkaConsumerAdapter adapter = new KafkaConsumerAdapter(DEAD_BOOTSTRAP, "g-10arg", "dims",
                "SSL", null, null, null, truststore.toString(), "changeit", "JKS");
        assertNotNull(adapter);
        adapter.close();
    }

    @Test
    @DisplayName("security.protocol 非法值：kafka-clients 构造期即抛 ConfigException（AgentConfig 白名单之外的第二道防线）")
    void illegalSecurityProtocolFailsFastAtConstruction() {
        Exception ex = assertThrows(Exception.class, () -> new KafkaConsumerAdapter(
                DEAD_BOOTSTRAP, "g-bogus", "dims", "TLS", null, null, null));
        assertEqualsConfigException(ex);
        assertTrue(String.valueOf(ex.getMessage()).toLowerCase().contains("protocol"), ex.getMessage());
    }

    /** kafka-clients 的配置校验异常在构造线程同步抛出（不进入异步失败）。 */
    private static void assertEqualsConfigException(Exception ex) {
        assertTrue(ex instanceof org.apache.kafka.common.config.ConfigException,
                "实际异常类型: " + ex.getClass() + " / " + ex.getMessage());
    }
}
