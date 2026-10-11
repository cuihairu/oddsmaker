package io.oddsmaker.control.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.Properties;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent 属性面键规范（纯函数）：props/secrets 拆分规则、注入键拒绝、必填与值形状校验、
 * 下发文本 Properties.load 往返（含转义值）。
 */
@DisplayName("Agent 属性面键规范")
class AgentConfigSpecTest {

    private static final String VALID_PROPS = """
            gateway.endpoint=https://ingest.oddsmaker.local
            dimension.type=item
            source.type=mysql
            source.jdbc.url=jdbc:mysql://replica.internal:3306/game
            source.jdbc.user=oddsmaker_ro
            source.jdbc.query=SELECT item_code FROM items WHERE updated_at > ? ORDER BY updated_at LIMIT 1000
            source.jdbc.cursor-column=updated_at
            agent.batch-size=500
            agent.poll-seconds=300
            status.url=https://control.local/api/dimensions/sync-status
            """;

    private static TreeMap<String, String> validSecrets() {
        TreeMap<String, String> s = new TreeMap<>();
        s.put("gateway.api-key", "pk_game_x");
        s.put("source.jdbc.password", "s3cret");
        return s;
    }

    @Test
    void parseAndValidateAcceptsFullConfig() {
        TreeMap<String, String> props = AgentConfigSpec.parseProps(VALID_PROPS);
        // TreeMap 字典序：agent.* 最先、status.url 最后
        assertEquals("agent.batch-size", props.firstKey());
        assertEquals("status.url", props.lastKey());
        AgentConfigSpec.validate(props, validSecrets());
    }

    @Test
    void parsePropsRejectsBlankAndCommentOnly() {
        IllegalArgumentException e1 = assertThrows(IllegalArgumentException.class,
                () -> AgentConfigSpec.parseProps("   "));
        assertEquals("props 不能为空", e1.getMessage());
        IllegalArgumentException e2 = assertThrows(IllegalArgumentException.class,
                () -> AgentConfigSpec.parseProps("# 只有一行注释\n"));
        assertEquals("props 里没有任何配置键", e2.getMessage());
    }

    @Test
    void propsRejectsSecretKeys() {
        TreeMap<String, String> props = AgentConfigSpec.parseProps(
                "gateway.api-key=pk_x\n" + VALID_PROPS);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AgentConfigSpec.validateProps(props));
        assertEquals("props 存在密钥键 gateway.api-key，请写进 secrets（props 里不落密钥）", e.getMessage());
    }

    @Test
    void propsRejectsInjectedKeys() {
        TreeMap<String, String> props = AgentConfigSpec.parseProps(
                "game.id=game_x\n" + VALID_PROPS);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AgentConfigSpec.validateProps(props));
        assertTrue(e.getMessage().startsWith("props 存在键 game.id"), e.getMessage());
        assertTrue(e.getMessage().contains("自动注入"), e.getMessage());

        TreeMap<String, String> props2 = AgentConfigSpec.parseProps(
                "agent.checkpoint-path=/var/lib/agent/checkpoint.json\n" + VALID_PROPS);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> AgentConfigSpec.validateProps(props2)).getMessage().contains("本地路径"));
    }

    @Test
    void propsRejectsUnknownKeysWithAllowedList() {
        TreeMap<String, String> props = AgentConfigSpec.parseProps(
                "source.jdbc.pwd=oops\n" + VALID_PROPS);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AgentConfigSpec.validateProps(props));
        assertTrue(e.getMessage().startsWith("props 存在未知键 source.jdbc.pwd"), e.getMessage());
        assertTrue(e.getMessage().contains("source.jdbc.url"), e.getMessage());
    }

    @Test
    void secretsRejectNonSecretKeys() {
        TreeMap<String, String> props = AgentConfigSpec.parseProps(VALID_PROPS);
        TreeMap<String, String> secrets = new TreeMap<>();
        secrets.put("source.jdbc.url", "not-a-secret-place");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AgentConfigSpec.validate(props, secrets));
        assertTrue(e.getMessage().startsWith("secrets 存在未知键 source.jdbc.url"), e.getMessage());
        assertTrue(e.getMessage().contains("非密钥键请写进 props"), e.getMessage());
    }

    @Test
    void secretsRequireGatewayApiKey() {
        TreeMap<String, String> props = AgentConfigSpec.parseProps(VALID_PROPS);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AgentConfigSpec.validate(props, new TreeMap<>()));
        assertEquals("缺少必填配置: gateway.api-key", e.getMessage());
    }

    @Test
    void propsRequireSourceTypeAndEndpoint() {
        TreeMap<String, String> props = AgentConfigSpec.parseProps(VALID_PROPS);
        props.remove("source.type");
        assertEquals("缺少必填配置: source.type",
                assertThrows(IllegalArgumentException.class,
                        () -> AgentConfigSpec.validateProps(props)).getMessage());
        props.put("source.type", "mysql");
        props.remove("gateway.endpoint");
        assertEquals("缺少必填配置: gateway.endpoint",
                assertThrows(IllegalArgumentException.class,
                        () -> AgentConfigSpec.validateProps(props)).getMessage());
    }

    @Test
    void rejectBadEnumAndIntShapes() {
        TreeMap<String, String> props = AgentConfigSpec.parseProps(VALID_PROPS);
        props.put("source.type", "oracle");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> AgentConfigSpec.validateProps(props)).getMessage().contains("mysql/postgres/csv/excel/kafka"));

        TreeMap<String, String> props2 = AgentConfigSpec.parseProps(VALID_PROPS);
        props2.put("gateway.endpoint", "ftp://ingest.oddsmaker.local");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> AgentConfigSpec.validateProps(props2)).getMessage().contains("http:// 或 https://"));

        TreeMap<String, String> props3 = AgentConfigSpec.parseProps(VALID_PROPS);
        props3.put("source.kafka.security-protocol", "PLAIN");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> AgentConfigSpec.validateProps(props3)).getMessage().contains("SASL_SSL"));

        TreeMap<String, String> props4 = AgentConfigSpec.parseProps(VALID_PROPS);
        props4.put("agent.poll-seconds", "abc");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> AgentConfigSpec.validateProps(props4)).getMessage().contains("正整数"));

        TreeMap<String, String> props5 = AgentConfigSpec.parseProps(VALID_PROPS);
        props5.put("agent.poll-seconds", "-5");
        assertThrows(IllegalArgumentException.class, () -> AgentConfigSpec.validateProps(props5));
    }

    @Test
    void renderResolvedIsLoadableAndComplete() throws Exception {
        TreeMap<String, String> props = AgentConfigSpec.parseProps(VALID_PROPS);
        String text = AgentConfigSpec.renderResolved("game_x", "prod", "items-mysql", props, validSecrets());

        Properties loaded = new Properties();
        loaded.load(new StringReader(text));
        // 注入三键
        assertEquals("game_x", loaded.getProperty("game.id"));
        assertEquals("prod", loaded.getProperty("game.environment"));
        assertEquals("items-mysql", loaded.getProperty("status.source-key"));
        // props 与解密后的 secrets 全在位
        assertEquals("https://ingest.oddsmaker.local", loaded.getProperty("gateway.endpoint"));
        assertEquals("pk_game_x", loaded.getProperty("gateway.api-key"));
        assertEquals("s3cret", loaded.getProperty("source.jdbc.password"));
        assertEquals("updated_at", loaded.getProperty("source.jdbc.cursor-column"));
        // 下发文本不含任何注释头（Agent 直接 Properties.load）
        assertTrue(text.lines().allMatch(line -> !line.trim().startsWith("#")), text);
    }

    @Test
    void renderValueEscapingRoundTrips() throws Exception {
        TreeMap<String, String> props = new TreeMap<>();
        props.put("source.jdbc.query", "SELECT a\\b FROM t WHERE c='x'\n  ORDER BY 1");
        String text = AgentConfigSpec.renderResolved("g", "prod", "k", props, validSecrets());
        Properties loaded = new Properties();
        loaded.load(new StringReader(text));
        assertEquals("SELECT a\\b FROM t WHERE c='x'\n  ORDER BY 1", loaded.getProperty("source.jdbc.query"));
    }
}
