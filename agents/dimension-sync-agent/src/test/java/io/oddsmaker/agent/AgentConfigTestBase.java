package io.oddsmaker.agent;

import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * AgentConfig 测试公共基类（LAY-013 自 AgentConfigTest 拆出）：
 * 共享 @TempDir 目录、properties 写入与 AgentConfig 样例构造 helper（write/fullJdbc/fullCsv/copy/concat）。
 */
abstract class AgentConfigTestBase {

    @TempDir
    Path dir;

    protected Path write(String... lines) throws Exception {
        Path p = dir.resolve("agent-" + System.nanoTime() + ".properties");
        Files.write(p, String.join("\n", lines).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return p;
    }

    protected AgentConfig fullJdbc() {
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

    protected AgentConfig fullCsv() {
        AgentConfig c = new AgentConfig();
        c.gatewayEndpoint = "http://gw";
        c.gatewayApiKey = "k";
        c.gameId = "g";
        c.environment = "prod";
        c.sourceType = "csv";
        c.csvDir = "/data/dim";
        return c;
    }

    protected AgentConfig copy(AgentConfig src) {
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

    protected static String[] concat(String[] base, String... extra) {
        String[] all = new String[base.length + extra.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(extra, 0, all, base.length, extra.length);
        return all;
    }

}
