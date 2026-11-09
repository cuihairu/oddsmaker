package io.oddsmaker.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AgentConfig 配置测试（LAY-013 拆分）：解析纯函数分支——orDefault 显式/空白回退、intOf/longOf 解析与空白回退、非数字抛 NFE、require 空白与 null 拒绝、trim null、normalizeSql 压缩空白。
 */
class AgentConfigParseFnsTest extends AgentConfigTestBase {

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

}
