package io.oddsmaker.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AgentConfig 配置测试（LAY-013 拆分）：properties 键映射与缺省值、System property 逐键覆盖文件值（密钥不落盘）、忽略非 --config= 参数。
 */
class AgentConfigLoadDefaultsTest extends AgentConfigTestBase {

    @Test
    @DisplayName("load：键映射与缺省值（batch 500 / poll 300s / dim item）")
    void loadsPropertiesWithDefaults() throws Exception {
        Path p = write(
                "gateway.endpoint=http://gw:8080",
                "gateway.api-key=k1",
                "game.id=g1",
                "game.environment=prod",
                "source.type=mysql",
                "source.jdbc.url=jdbc:mysql://db:3306/game",
                "source.jdbc.user=ro",
                "source.jdbc.password=pw",
                "source.jdbc.query=SELECT id, name FROM item WHERE updated_at > ?  ORDER BY updated_at",
                "source.jdbc.cursor-column=updated_at");
        AgentConfig c = AgentConfig.load(new String[]{"--config=" + p});
        assertEquals("http://gw:8080", c.gatewayEndpoint);
        assertEquals("k1", c.gatewayApiKey);
        assertEquals("g1", c.gameId);
        assertEquals("prod", c.environment);
        assertEquals(500, c.batchSize);
        assertEquals(300, c.pollSeconds);
        assertEquals("./checkpoint.json", c.checkpointPath);
        assertEquals("item", c.dimType);
        // SQL 排版换行/多空白压成单行
        assertEquals("SELECT id, name FROM item WHERE updated_at > ? ORDER BY updated_at", c.jdbcQuery);
        assertTrue(c.isJdbc());

        // kafka 键映射与缺省值
        Path k = write(
                "gateway.endpoint=http://gw:8080",
                "gateway.api-key=k1",
                "game.id=g1",
                "game.environment=prod",
                "source.type=kafka",
                "source.kafka.bootstrap-servers=b:9092",
                "source.kafka.topic=dims",
                "source.kafka.cursor-initial=0=42",
                "source.kafka.security-protocol=SASL_PLAINTEXT",
                "source.kafka.sasl-mechanism=SCRAM-SHA-256",
                "source.kafka.username=dim_ro",
                "source.kafka.password=secret");
        AgentConfig kc = AgentConfig.load(new String[]{"--config=" + k});
        assertEquals("b:9092", kc.kafkaBootstrap);
        assertEquals("dims", kc.kafkaTopic);
        assertEquals("oddsmaker-dimension-sync", kc.kafkaGroupId);
        assertEquals(3000L, kc.kafkaPollTimeoutMs);
        assertEquals("0=42", kc.kafkaCursorInitial);
        assertEquals("SASL_PLAINTEXT", kc.kafkaSecurityProtocol);
        assertEquals("SCRAM-SHA-256", kc.kafkaSaslMechanism);
        assertEquals("dim_ro", kc.kafkaUsername);
        assertEquals("secret", kc.kafkaPassword);

        // SSL 信任库键映射与缺省值（自签/私有 CA 场景）
        Path tls = write(
                "gateway.endpoint=http://gw:8080",
                "gateway.api-key=k1",
                "game.id=g1",
                "game.environment=prod",
                "source.type=kafka",
                "source.kafka.bootstrap-servers=b:9092",
                "source.kafka.topic=dims",
                "source.kafka.security-protocol=SSL",
                "source.kafka.ssl-truststore-path=/ts/client.p12",
                "source.kafka.ssl-truststore-password=ts-pass",
                "source.kafka.ssl-truststore-type=PKCS12",
                "source.kafka.ssl-keystore-path=/ks/client.p12",
                "source.kafka.ssl-keystore-password=ks-pass",
                "source.kafka.ssl-keystore-type=PKCS12");
        AgentConfig tc = AgentConfig.load(new String[]{"--config=" + tls});
        assertEquals("SSL", tc.kafkaSecurityProtocol);
        assertEquals("/ts/client.p12", tc.kafkaSslTruststorePath);
        assertEquals("ts-pass", tc.kafkaSslTruststorePassword);
        assertEquals("PKCS12", tc.kafkaSslTruststoreType);
        assertEquals("/ks/client.p12", tc.kafkaSslKeystorePath);
        assertEquals("ks-pass", tc.kafkaSslKeystorePassword);
        assertEquals("PKCS12", tc.kafkaSslKeystoreType);
        // 未配置鉴权时缺省明文
        Path plain = write(
                "gateway.endpoint=http://gw:8080",
                "gateway.api-key=k1",
                "game.id=g1",
                "game.environment=prod",
                "source.type=kafka",
                "source.kafka.bootstrap-servers=b:9092",
                "source.kafka.topic=dims");
        AgentConfig pc = AgentConfig.load(new String[]{"--config=" + plain});
        assertEquals("PLAINTEXT", pc.kafkaSecurityProtocol);
        assertNull(pc.kafkaSaslMechanism);
        // 未配置时：无信任库/证书库（走 JVM 默认）、空密码、类型缺省 JKS（与 kafka-clients 一致）
        assertNull(pc.kafkaSslTruststorePath);
        assertEquals("", pc.kafkaSslTruststorePassword);
        assertEquals("JKS", pc.kafkaSslTruststoreType);
        assertNull(pc.kafkaSslKeystorePath);
        assertEquals("", pc.kafkaSslKeystorePassword);
        assertEquals("JKS", pc.kafkaSslKeystoreType);
    }

    @Test
    @DisplayName("System property 逐键覆盖文件值（密钥不落盘）")
    void systemPropertyOverridesFile() throws Exception {
        Path p = write("gateway.endpoint=http://gw:8080", "gateway.api-key=file-key",
                "game.id=g1", "game.environment=prod", "source.type=csv", "source.csv.dir=/tmp");
        System.setProperty("gateway.api-key", "cli-key");
        try {
            AgentConfig c = AgentConfig.load(new String[]{"--config=" + p});
            assertEquals("cli-key", c.gatewayApiKey);
            assertEquals("http://gw:8080", c.gatewayEndpoint);
        } finally {
            System.clearProperty("gateway.api-key");
        }
    }

    @Test
    @DisplayName("load：忽略不以 --config= 开头的参数（分支对侧）")
    void loadIgnoresNonConfigArgs() throws Exception {
        Path p = write(
                "gateway.endpoint=http://gw:8080",
                "gateway.api-key=k1",
                "game.id=g1",
                "game.environment=prod",
                "source.type=csv",
                "source.csv.dir=/tmp");
        // 传入不以 --config= 开头的参数，应被忽略，仍加载默认路径 ./agent.properties（不存在会抛 IOException）
        // 这里改用存在的文件路径，验证参数被忽略不影响加载
        AgentConfig c = AgentConfig.load(new String[]{"--config=" + p, "--unknown=foo", "bare-arg"});
        assertEquals("http://gw:8080", c.gatewayEndpoint);
    }

}
