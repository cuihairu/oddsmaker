package io.oddsmaker.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                "source.kafka.cursor-initial=0=42");
        AgentConfig kc = AgentConfig.load(new String[]{"--config=" + k});
        assertEquals("b:9092", kc.kafkaBootstrap);
        assertEquals("dims", kc.kafkaTopic);
        assertEquals("oddsmaker-dimension-sync", kc.kafkaGroupId);
        assertEquals(3000L, kc.kafkaPollTimeoutMs);
        assertEquals("0=42", kc.kafkaCursorInitial);
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
        return c;
    }
}
