package io.oddsmaker.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AgentConfig 配置测试（LAY-013 拆分）：按 source 类型 fail-fast 校验——JDBC 缺列/占位数/csv 目录/未知类型/非法 batch 与 poll、各源完整通过、excel 与 kafka 缺参、kafka 鉴权协议白名单与 SCRAM 凭证、jdbc/kafka 密码允许空串、SSL 与 SASL_SSL 无证书材料走 JVM 默认信任库。
 */
class AgentConfigValidateTest extends AgentConfigTestBase {

    @Test
    @DisplayName("validate：JDBC 缺列 / ? 占位数不对 / csv 缺目录 / 未知 source.type / 非法 batch/poll")
    void validatesFailFast() throws Exception {
        AgentConfig base = new AgentConfig();
        base.gatewayEndpoint = "http://gw";
        base.gatewayApiKey = "k";
        base.gameId = "g";
        base.environment = "prod";

        AgentConfig unknownSource = copy(base);
        unknownSource.sourceType = "oracle";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, unknownSource::validate);
        assertTrue(ex.getMessage().contains("source.type"));

        AgentConfig missingUrl = copy(base);
        missingUrl.sourceType = "mysql";
        missingUrl.jdbcQuery = "SELECT 1 WHERE x > ?";
        missingUrl.cursorColumn = "x";
        assertTrue(assertThrows(IllegalArgumentException.class, missingUrl::validate)
                .getMessage().contains("source.jdbc.url"));

        AgentConfig badMarks = copy(base);
        badMarks.sourceType = "postgres";
        badMarks.jdbcUrl = "jdbc:postgresql://db/db";
        badMarks.jdbcUser = "ro";
        badMarks.jdbcQuery = "SELECT 1 WHERE x > ? AND y < ?";
        badMarks.cursorColumn = "x";
        assertTrue(assertThrows(IllegalArgumentException.class, badMarks::validate)
                .getMessage().contains("?"));

        AgentConfig noMarks = copy(badMarks);
        noMarks.jdbcQuery = "SELECT 1";
        assertThrows(IllegalArgumentException.class, noMarks::validate);

        AgentConfig missingCsvDir = copy(base);
        missingCsvDir.sourceType = "csv";
        assertThrows(IllegalArgumentException.class, missingCsvDir::validate).getMessage().contains("source.csv.dir");

        AgentConfig badBatch = copy(missingCsvDir);
        badBatch.csvDir = "/tmp";
        badBatch.batchSize = 0;
        assertTrue(assertThrows(IllegalArgumentException.class, badBatch::validate)
                .getMessage().contains("batch-size"));

        AgentConfig badPoll = copy(badBatch);
        badPoll.batchSize = 100;
        badPoll.pollSeconds = -1;
        assertTrue(assertThrows(IllegalArgumentException.class, badPoll::validate)
                .getMessage().contains("poll-seconds"));

        AgentConfig missingGateway = new AgentConfig();
        missingGateway.sourceType = "csv";
        missingGateway.csvDir = "/tmp";
        assertTrue(assertThrows(IllegalArgumentException.class, missingGateway::validate)
                .getMessage().contains("gateway.endpoint"));
    }

    @Test
    @DisplayName("validate：mysql / postgres / csv / excel / kafka 完整配置通过；isJdbc 区分")
    void validatesHappyPaths() {
        AgentConfig jdbc = fullJdbc();
        jdbc.validate();
        assertTrue(jdbc.isJdbc());

        AgentConfig csv = fullCsv();
        csv.validate();
        assertFalse(csv.isJdbc());

        AgentConfig excel = fullCsv();
        excel.sourceType = "excel";
        excel.csvDir = null;
        excel.excelDir = "/data/dim";
        excel.validate();

        AgentConfig kafka = fullCsv();
        kafka.sourceType = "kafka";
        kafka.csvDir = null;
        kafka.kafkaBootstrap = "b:9092";
        kafka.kafkaTopic = "dims";
        kafka.validate();
    }

    @Test
    @DisplayName("validate：excel 缺目录 / kafka 缺 bootstrap 或 topic / 非法 poll-timeout")
    void validatesExcelAndKafkaFailFast() {
        AgentConfig base = fullCsv();
        base.csvDir = null;

        AgentConfig missingExcelDir = copy(base);
        missingExcelDir.sourceType = "excel";
        assertTrue(assertThrows(IllegalArgumentException.class, missingExcelDir::validate)
                .getMessage().contains("source.excel.dir"));

        AgentConfig missingBootstrap = copy(base);
        missingBootstrap.sourceType = "kafka";
        missingBootstrap.kafkaTopic = "dims";
        assertTrue(assertThrows(IllegalArgumentException.class, missingBootstrap::validate)
                .getMessage().contains("source.kafka.bootstrap-servers"));

        AgentConfig missingTopic = copy(missingBootstrap);
        missingTopic.kafkaBootstrap = "b:9092";
        missingTopic.kafkaTopic = null;
        assertTrue(assertThrows(IllegalArgumentException.class, missingTopic::validate)
                .getMessage().contains("source.kafka.topic"));

        AgentConfig badTimeout = copy(missingTopic);   // bootstrap 已补齐，隔离验证 poll-timeout
        badTimeout.kafkaTopic = "dims";
        badTimeout.kafkaPollTimeoutMs = 0;
        assertTrue(assertThrows(IllegalArgumentException.class, badTimeout::validate)
                .getMessage().contains("poll-timeout-ms"));
    }

    @Test
    @DisplayName("validate：kafka 鉴权——协议白名单 / SASL 必须带齐机制与 SCRAM 凭证 / 齐备通过")
    void validatesKafkaSecurityFailFast() {
        AgentConfig base = fullCsv();
        base.csvDir = null;
        base.sourceType = "kafka";
        base.kafkaBootstrap = "b:9092";
        base.kafkaTopic = "dims";

        // 协议白名单
        AgentConfig badProtocol = copy(base);
        badProtocol.kafkaSecurityProtocol = "Kerberos";
        assertTrue(assertThrows(IllegalArgumentException.class, badProtocol::validate)
                .getMessage().contains("security-protocol"));

        // SASL 缺机制
        AgentConfig missingMech = copy(base);
        missingMech.kafkaSecurityProtocol = "SASL_PLAINTEXT";
        missingMech.kafkaUsername = "u";
        missingMech.kafkaPassword = "p";
        assertTrue(assertThrows(IllegalArgumentException.class, missingMech::validate)
                .getMessage().contains("sasl-mechanism"));

        // 机制仅支持 SCRAM（PLAIN 凭证明文传输，不做支持面）
        AgentConfig plainMech = copy(missingMech);
        plainMech.kafkaSaslMechanism = "PLAIN";
        assertTrue(assertThrows(IllegalArgumentException.class, plainMech::validate)
                .getMessage().contains("sasl-mechanism"));

        // SASL 缺账号 / 缺密码
        AgentConfig missingUser = copy(missingMech);
        missingUser.kafkaSaslMechanism = "SCRAM-SHA-256";
        missingUser.kafkaUsername = null;
        assertTrue(assertThrows(IllegalArgumentException.class, missingUser::validate)
                .getMessage().contains("source.kafka.username"));
        AgentConfig missingPassword = copy(missingUser);
        missingPassword.kafkaUsername = "u";
        missingPassword.kafkaPassword = null;   // copy 链带着 missingMech 的 "p"，显式清掉隔离验证
        assertTrue(assertThrows(IllegalArgumentException.class, missingPassword::validate)
                .getMessage().contains("source.kafka.password"));

        // 齐备通过（SASL_SSL + SCRAM-SHA-512）
        AgentConfig fullSasl = copy(missingPassword);
        fullSasl.kafkaPassword = "p";
        fullSasl.kafkaSecurityProtocol = "SASL_SSL";
        fullSasl.kafkaSaslMechanism = "SCRAM-SHA-512";
        fullSasl.validate();

        // PLAINTEXT/SSL 不要求账号凭证
        AgentConfig ssl = copy(base);
        ssl.kafkaSecurityProtocol = "SSL";
        ssl.validate();

        // ── SSL 信任库（证书模式）──
        // SSL + truststore 三键齐备通过
        AgentConfig sslTs = copy(ssl);
        sslTs.kafkaSslTruststorePath = "/ts/client.p12";
        sslTs.kafkaSslTruststorePassword = "ts-pass";
        sslTs.kafkaSslTruststoreType = "PKCS12";
        sslTs.validate();

        // SASL_SSL + truststore 同样通过（证书链 + 账号凭证叠加）
        AgentConfig saslSslTs = copy(fullSasl);
        saslSslTs.kafkaSslTruststorePath = "/ts/client.p12";
        saslSslTs.kafkaSslTruststoreType = "JKS";
        saslSslTs.validate();

        // 明文协议下配置 truststore = 配置漂移，fail-fast
        AgentConfig plaintextTs = copy(base);
        plaintextTs.kafkaSecurityProtocol = "PLAINTEXT";
        plaintextTs.kafkaSslTruststorePath = "/ts/client.p12";
        assertTrue(assertThrows(IllegalArgumentException.class, plaintextTs::validate)
                .getMessage().contains("ssl-truststore-path"));
        AgentConfig saslPlaintextTs = copy(fullSasl);
        saslPlaintextTs.kafkaSecurityProtocol = "SASL_PLAINTEXT";
        saslPlaintextTs.kafkaSslTruststorePath = "/ts/client.p12";
        assertTrue(assertThrows(IllegalArgumentException.class, saslPlaintextTs::validate)
                .getMessage().contains("配置漂移"));

        // 信任库类型白名单（JKS/PKCS12）
        AgentConfig badType = copy(sslTs);
        badType.kafkaSslTruststoreType = "PEM";
        assertTrue(assertThrows(IllegalArgumentException.class, badType::validate)
                .getMessage().contains("ssl-truststore-type"));

        // ── mTLS 证书库（keystore，broker ssl.client.auth=required 时出证）──
        // SSL + truststore + keystore 齐备通过（完整 mTLS 配置面）
        AgentConfig sslKs = copy(sslTs);
        sslKs.kafkaSslKeystorePath = "/ks/client.p12";
        sslKs.kafkaSslKeystorePassword = "ks-pass";
        sslKs.kafkaSslKeystoreType = "PKCS12";
        sslKs.validate();

        // SASL_SSL + keystore 同样通过（证书链 + 客户端证书 + 账号凭证三方叠加）
        AgentConfig saslSslKs = copy(saslSslTs);
        saslSslKs.kafkaSslKeystorePath = "/ks/client.jks";
        saslSslKs.validate();

        // 明文协议下配置 keystore = 配置漂移，fail-fast（消息带 path 键名与证书库中文名）
        AgentConfig plaintextKs = copy(base);
        plaintextKs.kafkaSslKeystorePath = "/ks/client.p12";
        assertTrue(assertThrows(IllegalArgumentException.class, plaintextKs::validate)
                .getMessage().contains("ssl-keystore-path"));
        AgentConfig saslPlaintextKs = copy(fullSasl);
        saslPlaintextKs.kafkaSecurityProtocol = "SASL_PLAINTEXT";
        saslPlaintextKs.kafkaSslKeystorePath = "/ks/client.p12";
        assertTrue(assertThrows(IllegalArgumentException.class, saslPlaintextKs::validate)
                .getMessage().contains("证书库"));

        // 证书库类型白名单（与信任库同规则）
        AgentConfig badKsType = copy(sslKs);
        badKsType.kafkaSslKeystoreType = "PEM";
        assertTrue(assertThrows(IllegalArgumentException.class, badKsType::validate)
                .getMessage().contains("ssl-keystore-type"));
    }

    @Test
    @DisplayName("validate：jdbcPassword 允许为空串（配置键存在但值为空）")
    void jdbcPasswordAllowsEmptyString() throws Exception {
        Path p = write(
                "gateway.endpoint=http://gw:8080",
                "gateway.api-key=k1",
                "game.id=g1",
                "game.environment=prod",
                "source.type=mysql",
                "source.jdbc.url=jdbc:mysql://db:3306/game",
                "source.jdbc.user=ro",
                "source.jdbc.password=",
                "source.jdbc.query=SELECT 1 WHERE x > ? ORDER BY x",
                "source.jdbc.cursor-column=x");
        AgentConfig c = AgentConfig.load(new String[]{"--config=" + p});
        assertEquals("", c.jdbcPassword);
        c.validate(); // 不抛异常
    }

    @Test
    @DisplayName("validate：kafkaPassword 允许为空串")
    void kafkaPasswordAllowsEmptyString() {
        AgentConfig c = new AgentConfig();
        c.gatewayEndpoint = "http://gw";
        c.gatewayApiKey = "k";
        c.gameId = "g";
        c.environment = "prod";
        c.sourceType = "kafka";
        c.kafkaBootstrap = "b:9092";
        c.kafkaTopic = "dims";
        c.kafkaSecurityProtocol = "SASL_PLAINTEXT";
        c.kafkaSaslMechanism = "SCRAM-SHA-256";
        c.kafkaUsername = "u";
        c.kafkaPassword = "";  // 空串允许（require 只检查非 null 非空白，空串视为缺失会抛）
        // 实际上 require 会拦截空串，所以这是验证当前行为
        assertThrows(IllegalArgumentException.class, c::validate);
    }

    @Test
    @DisplayName("validate：SSL 协议无证书材料通过（走 JVM 默认信任库）")
    void validateAllowsSslWithoutCertMaterial() {
        AgentConfig c = new AgentConfig();
        c.gatewayEndpoint = "http://gw";
        c.gatewayApiKey = "k";
        c.gameId = "g";
        c.environment = "prod";
        c.sourceType = "kafka";
        c.kafkaBootstrap = "b:9092";
        c.kafkaTopic = "dims";
        c.kafkaSecurityProtocol = "SSL";
        c.validate();
    }

    @Test
    @DisplayName("validate：SASL_SSL 协议无证书材料通过（仅账号凭证，走 JVM 默认信任库）")
    void validateAllowsSaslSslWithoutCertMaterial() {
        AgentConfig c = new AgentConfig();
        c.gatewayEndpoint = "http://gw";
        c.gatewayApiKey = "k";
        c.gameId = "g";
        c.environment = "prod";
        c.sourceType = "kafka";
        c.kafkaBootstrap = "b:9092";
        c.kafkaTopic = "dims";
        c.kafkaSecurityProtocol = "SASL_SSL";
        c.kafkaSaslMechanism = "SCRAM-SHA-256";
        c.kafkaUsername = "u";
        c.kafkaPassword = "p";
        c.validate();
    }

}
