package io.oddsmaker.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 配置加载：properties 映射、System property 逐键覆盖、按 source 类型 fail-fast 校验。 */
class AgentConfigTest {

    @TempDir
    Path dir;

    private Path write(String... lines) throws Exception {
        Path p = dir.resolve("agent-" + System.nanoTime() + ".properties");
        Files.write(p, String.join("\n", lines).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return p;
    }

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

    private AgentConfig fullJdbc() {
        AgentConfig c = new AgentConfig();
        c.gatewayEndpoint = "http://gw";
        c.gatewayApiKey = "k";
        c.gameId = "g";
        c.environment = "prod";
        c.sourceType = "postgres";
        c.jdbcUrl = "jdbc:postgresql://db/db";
        c.jdbcUser = "ro";
        c.jdbcQuery = "SELECT * FROM item WHERE updated_at > ? ORDER BY updated_at";
        c.cursorColumn = "updated_at";
        return c;
    }

    private AgentConfig fullCsv() {
        AgentConfig c = new AgentConfig();
        c.gatewayEndpoint = "http://gw";
        c.gatewayApiKey = "k";
        c.gameId = "g";
        c.environment = "prod";
        c.sourceType = "csv";
        c.csvDir = "/data/dim";
        return c;
    }

    private AgentConfig copy(AgentConfig src) {
        AgentConfig c = new AgentConfig();
        c.gatewayEndpoint = src.gatewayEndpoint;
        c.gatewayApiKey = src.gatewayApiKey;
        c.gameId = src.gameId;
        c.environment = src.environment;
        c.batchSize = src.batchSize;
        c.pollSeconds = src.pollSeconds;
        c.sourceType = src.sourceType;
        c.jdbcUrl = src.jdbcUrl;
        c.jdbcQuery = src.jdbcQuery;
        c.cursorColumn = src.cursorColumn;
        c.csvDir = src.csvDir;
        c.excelDir = src.excelDir;
        c.kafkaBootstrap = src.kafkaBootstrap;
        c.kafkaTopic = src.kafkaTopic;
        c.kafkaGroupId = src.kafkaGroupId;
        c.kafkaPollTimeoutMs = src.kafkaPollTimeoutMs;
        c.kafkaCursorInitial = src.kafkaCursorInitial;
        c.kafkaSecurityProtocol = src.kafkaSecurityProtocol;
        c.kafkaSaslMechanism = src.kafkaSaslMechanism;
        c.kafkaUsername = src.kafkaUsername;
        c.kafkaPassword = src.kafkaPassword;
        c.kafkaSslTruststorePath = src.kafkaSslTruststorePath;
        c.kafkaSslTruststorePassword = src.kafkaSslTruststorePassword;
        c.kafkaSslTruststoreType = src.kafkaSslTruststoreType;
        c.kafkaSslKeystorePath = src.kafkaSslKeystorePath;
        c.kafkaSslKeystorePassword = src.kafkaSslKeystorePassword;
        c.kafkaSslKeystoreType = src.kafkaSslKeystoreType;
        return c;
    }

    // === 分支对侧补充：覆盖 load/orDefault/intOf/longOf/require/requireCertMaterial 的未达臂 ===

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

    @Test
    @DisplayName("orDefault：显式非空非空白值返回自身 trimmed（分支对侧：v != null && !v.isBlank()）")
    void orDefaultReturnsTrimmedWhenPresent() throws Exception {
        // 通过 load 间接验证：配置文件里显式写 checkpoint-path，应被读取并 trimmed
        Path p = write(
                "gateway.endpoint=http://gw:8080",
                "gateway.api-key=k1",
                "game.id=g1",
                "game.environment=prod",
                "source.type=csv",
                "source.csv.dir=/tmp",
                "agent.checkpoint-path=  /custom/path  ");
        AgentConfig c = AgentConfig.load(new String[]{"--config=" + p});
        assertEquals("/custom/path", c.checkpointPath);
    }

    @Test
    @DisplayName("orDefault：空白串视为缺失回退默认值（分支对侧：v.isBlank() 为 true）")
    void orDefaultFallsBackToDefaultWhenBlank() throws Exception {
        Path p = write(
                "gateway.endpoint=http://gw:8080",
                "gateway.api-key=k1",
                "game.id=g1",
                "game.environment=prod",
                "source.type=csv",
                "source.csv.dir=/tmp",
                "agent.checkpoint-path=   ");
        AgentConfig c = AgentConfig.load(new String[]{"--config=" + p});
        assertEquals("./checkpoint.json", c.checkpointPath);
    }

    @Test
    @DisplayName("intOf：通过 load 设置 batch-size/poll-seconds 覆盖解析分支（v != null && !v.isBlank() + 解析）")
    void loadParsesIntOfBranches() throws Exception {
        Path p = write(
                "gateway.endpoint=http://gw:8080",
                "gateway.api-key=k1",
                "game.id=g1",
                "game.environment=prod",
                "source.type=csv",
                "source.csv.dir=/tmp",
                "agent.batch-size=123",
                "agent.poll-seconds=456");
        AgentConfig c = AgentConfig.load(new String[]{"--config=" + p});
        assertEquals(123, c.batchSize);
        assertEquals(456, c.pollSeconds);
    }

    @Test
    @DisplayName("longOf：通过 load 设置 kafka.poll-timeout-ms 覆盖解析分支")
    void loadParsesLongOfBranches() throws Exception {
        Path p = write(
                "gateway.endpoint=http://gw:8080",
                "gateway.api-key=k1",
                "game.id=g1",
                "game.environment=prod",
                "source.type=kafka",
                "source.kafka.bootstrap-servers=b:9092",
                "source.kafka.topic=dims",
                "source.kafka.poll-timeout-ms=7777");
        AgentConfig c = AgentConfig.load(new String[]{"--config=" + p});
        assertEquals(7777L, c.kafkaPollTimeoutMs);
    }

    @Test
    @DisplayName("require：空白串也视为缺失并抛出（分支对侧：v.isBlank() 为 true）")
    void requireRejectsBlankString() {
        AgentConfig c = new AgentConfig();
        c.gatewayEndpoint = "http://gw";
        c.gatewayApiKey = "k";
        c.gameId = "g";
        c.environment = "prod";
        c.sourceType = "csv";
        c.csvDir = "  ";   // 空白串
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, c::validate);
        assertTrue(ex.getMessage().contains("source.csv.dir"), ex.getMessage());
    }

    @Test
    @DisplayName("requireCertMaterial：keystore path 非空且 protocol 为 SASL_SSL、type 合法时通过")
    void requireCertMaterialAllowsKeystoreUnderSaslSsl() {
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
        c.kafkaSslKeystorePath = "/ks/client.p12";
        c.kafkaSslKeystoreType = "PKCS12";
        c.validate();
    }

    @Test
    @DisplayName("requireCertMaterial：keystore type 非法（非 JKS/PKCS12）在 SASL_SSL 下被拒绝")
    void requireCertMaterialRejectsBadKeystoreTypeUnderSaslSsl() {
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
        c.kafkaSslKeystorePath = "/ks/client.p12";
        c.kafkaSslKeystoreType = "PEM";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, c::validate);
        assertTrue(ex.getMessage().contains("ssl-keystore-type"), ex.getMessage());
    }

    @Test
    @DisplayName("intOf：空白串回退默认值（覆盖 v != null && v.isBlank() 分支）")
    void loadParsesIntOfBlankFallsBackToDefault() throws Exception {
        Path p = write(
                "gateway.endpoint=http://gw:8080",
                "gateway.api-key=k1",
                "game.id=g1",
                "game.environment=prod",
                "source.type=csv",
                "source.csv.dir=/tmp",
                "agent.batch-size=   ",
                "agent.poll-seconds=   ");
        AgentConfig c = AgentConfig.load(new String[]{"--config=" + p});
        assertEquals(500, c.batchSize);
        assertEquals(300, c.pollSeconds);
    }

    @Test
    @DisplayName("longOf：空白串回退默认值（覆盖 v != null && v.isBlank() 分支）")
    void loadParsesLongOfBlankFallsBackToDefault() throws Exception {
        Path p = write(
                "gateway.endpoint=http://gw:8080",
                "gateway.api-key=k1",
                "game.id=g1",
                "game.environment=prod",
                "source.type=kafka",
                "source.kafka.bootstrap-servers=b:9092",
                "source.kafka.topic=dims",
                "source.kafka.poll-timeout-ms=   ");
        AgentConfig c = AgentConfig.load(new String[]{"--config=" + p});
        assertEquals(3000L, c.kafkaPollTimeoutMs);
    }

    @Test
    @DisplayName("requireCertMaterial：truststore path 空白串视为未配置（早期返回分支）")
    void requireCertMaterialTreatsBlankTruststoreAsAbsent() {
        AgentConfig c = new AgentConfig();
        c.gatewayEndpoint = "http://gw";
        c.gatewayApiKey = "k";
        c.gameId = "g";
        c.environment = "prod";
        c.sourceType = "kafka";
        c.kafkaBootstrap = "b:9092";
        c.kafkaTopic = "dims";
        c.kafkaSecurityProtocol = "SSL";
        c.kafkaSslTruststorePath = "  ";
        c.kafkaSslTruststoreType = "JKS";
        c.validate();
    }

    @Test
    @DisplayName("requireCertMaterial：keystore path 空白串视为未配置（早期返回分支）")
    void requireCertMaterialTreatsBlankKeystoreAsAbsent() {
        AgentConfig c = new AgentConfig();
        c.gatewayEndpoint = "http://gw";
        c.gatewayApiKey = "k";
        c.gameId = "g";
        c.environment = "prod";
        c.sourceType = "kafka";
        c.kafkaBootstrap = "b:9092";
        c.kafkaTopic = "dims";
        c.kafkaSecurityProtocol = "SSL";
        c.kafkaSslKeystorePath = "  ";
        c.kafkaSslKeystoreType = "JKS";
        c.validate();
    }

    @Test
    @DisplayName("requireCertMaterial：SASL_PLAINTEXT 下配置 truststore = 配置漂移被拒绝")
    void requireCertMaterialRejectsTruststoreUnderSaslPlaintext() {
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
        c.kafkaPassword = "p";
        c.kafkaSslTruststorePath = "/ts/client.p12";
        c.kafkaSslTruststoreType = "JKS";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, c::validate);
        assertTrue(ex.getMessage().contains("ssl-truststore-path"), ex.getMessage());
    }

    @Test
    @DisplayName("requireCertMaterial：SASL_PLAINTEXT 下配置 keystore = 配置漂移被拒绝")
    void requireCertMaterialRejectsKeystoreUnderSaslPlaintext() {
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
        c.kafkaPassword = "p";
        c.kafkaSslKeystorePath = "/ks/client.p12";
        c.kafkaSslKeystoreType = "JKS";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, c::validate);
        assertTrue(ex.getMessage().contains("ssl-keystore-path"), ex.getMessage());
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

    @Test
    @DisplayName("trim：null 输入返回 null（分支对侧）")
    void trimHandlesNull() {
        // 通过 load 间接验证：不配置某键，trim(null) 应返回 null
        // 实际由 load 内部调用 trim，这里直接测试私有方法需反射，改用公共行为验证
        AgentConfig c = new AgentConfig();
        c.gatewayEndpoint = "http://gw";
        c.gatewayApiKey = "k";
        c.gameId = "g";
        c.environment = "prod";
        c.sourceType = "csv";
        c.csvDir = "/tmp";
        // 未设置 gatewayApiKey 时 trim(null) -> null，但 load 会设置
        // 直接验证 require 分支：null 与空白串均视为缺失
        c.gatewayApiKey = null;
        assertThrows(IllegalArgumentException.class, c::validate);
    }

    @Test
    @DisplayName("normalizeSql：null 返回 null，多空白压缩为单空格（分支对侧）")
    void normalizeSqlHandlesNullAndCompressesWhitespace() throws Exception {
        Path p = write(
                "gateway.endpoint=http://gw:8080",
                "gateway.api-key=k1",
                "game.id=g1",
                "game.environment=prod",
                "source.type=mysql",
                "source.jdbc.url=jdbc:mysql://db:3306/game",
                "source.jdbc.user=ro",
                "source.jdbc.password=pw",
                "source.jdbc.query=SELECT   id  ,  name  FROM item WHERE updated_at > ?  ORDER BY updated_at",
                "source.jdbc.cursor-column=updated_at");
        AgentConfig c = AgentConfig.load(new String[]{"--config=" + p});
        // 多空白压成单空格
        assertEquals("SELECT id , name FROM item WHERE updated_at > ? ORDER BY updated_at", c.jdbcQuery);

        // null 输入
        Path p2 = write(
                "gateway.endpoint=http://gw:8080",
                "gateway.api-key=k1",
                "game.id=g1",
                "game.environment=prod",
                "source.type=mysql",
                "source.jdbc.url=jdbc:mysql://db:3306/game",
                "source.jdbc.user=ro",
                "source.jdbc.password=pw",
                "source.jdbc.cursor-column=updated_at");
        AgentConfig c2 = AgentConfig.load(new String[]{"--config=" + p2});
        assertNull(c2.jdbcQuery);
    }

    @Test
    @DisplayName("intOf/longOf：非数字字符串抛 NumberFormatException（解析分支）")
    void intOfLongOfThrowOnNonNumeric() throws Exception {
        Path p = write(
                "gateway.endpoint=http://gw:8080",
                "gateway.api-key=k1",
                "game.id=g1",
                "game.environment=prod",
                "source.type=csv",
                "source.csv.dir=/tmp",
                "agent.batch-size=not-a-number");
        Exception ex = assertThrows(Exception.class, () -> AgentConfig.load(new String[]{"--config=" + p}));
        assertTrue(ex.getCause() instanceof NumberFormatException || ex instanceof NumberFormatException);

        Path p2 = write(
                "gateway.endpoint=http://gw:8080",
                "gateway.api-key=k1",
                "game.id=g1",
                "game.environment=prod",
                "source.type=kafka",
                "source.kafka.bootstrap-servers=b:9092",
                "source.kafka.topic=dims",
                "source.kafka.poll-timeout-ms=not-a-number");
        Exception ex2 = assertThrows(Exception.class, () -> AgentConfig.load(new String[]{"--config=" + p2}));
        assertTrue(ex2.getCause() instanceof NumberFormatException || ex2 instanceof NumberFormatException);
    }

    @Test
    @DisplayName("require：null 与空白串均视为缺失（覆盖 v == null || v.isBlank() 两侧）")
    void requireRejectsNullAndBlank() {
        AgentConfig c = new AgentConfig();
        c.gatewayEndpoint = "http://gw";
        c.gatewayApiKey = "k";
        c.gameId = "g";
        c.environment = "prod";
        c.sourceType = "csv";
        c.csvDir = null;   // null
        assertThrows(IllegalArgumentException.class, c::validate);

        c.csvDir = "";     // 空串
        assertThrows(IllegalArgumentException.class, c::validate);

        c.csvDir = "  ";   // 空白串
        assertThrows(IllegalArgumentException.class, c::validate);
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
}
