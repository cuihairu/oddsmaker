package io.oddsmaker.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AgentConfig 配置测试（LAY-013 拆分）：Control 合并——下发键覆盖本地、拉取失败整段回落、System property 优先级最高、热更新 refresh-seconds 解析与 appliedRemoteText、热更新校验三臂。
 */
class AgentConfigControlMergeTest extends AgentConfigTestBase {

    @Test
    @DisplayName("Control 合并：下发键覆盖本地文件值，注入键（game.id 等）同路径生效")
    void controlMergeOverridesFileValues() throws Exception {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        String body = String.join("\n",
                "game.id=g1",
                "game.environment=prod",
                "status.source-key=items-mysql",
                "gateway.endpoint=http://gw-from-control:8080",
                "source.jdbc.password=from-control",
                "agent.poll-seconds=60");
        server.createContext("/api/dimensions/agent-configs/resolved", exchange -> {
            byte[] resp = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (java.io.OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        try {
            Path p = write(
                    "control.config-url=http://127.0.0.1:" + server.getAddress().getPort()
                            + "/api/dimensions/agent-configs/resolved",
                    "gateway.endpoint=http://gw-local:8080",
                    "gateway.api-key=k1",
                    "game.id=g1",
                    "game.environment=prod",
                    "status.source-key=local-key",
                    "source.type=mysql",
                    "source.jdbc.url=jdbc:mysql://db:3306/game",
                    "source.jdbc.user=ro",
                    "source.jdbc.password=local-pw",
                    "source.jdbc.query=SELECT 1 WHERE x > ? ORDER BY x",
                    "source.jdbc.cursor-column=x",
                    "agent.poll-seconds=300");
            AgentConfig c = AgentConfig.load(new String[]{"--config=" + p});
            // 下发覆盖文件值
            assertEquals("http://gw-from-control:8080", c.gatewayEndpoint);
            assertEquals("from-control", c.jdbcPassword);
            assertEquals(60, c.pollSeconds);
            // 注入键同路径覆盖（下发文本里就是行值）
            assertEquals("items-mysql", c.sourceKey);
            // 本地独有键保留
            assertEquals("k1", c.gatewayApiKey);
            assertEquals("jdbc:mysql://db:3306/game", c.jdbcUrl);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Control 合并：拉取失败整段回落本地文件（不阻断启动）")
    void controlMergeFallsBackOnFailure() throws Exception {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/dimensions/agent-configs/resolved",
                exchange -> exchange.sendResponseHeaders(500, -1));
        server.start();
        try {
            Path p = write(
                    "control.config-url=http://127.0.0.1:" + server.getAddress().getPort()
                            + "/api/dimensions/agent-configs/resolved",
                    "gateway.endpoint=http://gw-local:8080",
                    "gateway.api-key=k1",
                    "game.id=g1",
                    "game.environment=prod",
                    "source.type=mysql",
                    "source.jdbc.url=jdbc:mysql://db:3306/game",
                    "source.jdbc.user=ro",
                    "source.jdbc.password=local-pw",
                    "source.jdbc.query=SELECT 1 WHERE x > ? ORDER BY x",
                    "source.jdbc.cursor-column=x");
            AgentConfig c = AgentConfig.load(new String[]{"--config=" + p});
            assertEquals("http://gw-local:8080", c.gatewayEndpoint);
            assertEquals("local-pw", c.jdbcPassword);
            assertEquals(300, c.pollSeconds);
            c.validate(); // 本地值完整可用
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("配置源优先级：System property > Control 下发 > 本地文件")
    void systemPropertyWinsOverControlMerge() throws Exception {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        String body = "gateway.api-key=from-control\n";
        server.createContext("/api/dimensions/agent-configs/resolved", exchange -> {
            byte[] resp = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (java.io.OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        String previous = System.getProperty("gateway.api-key");
        System.setProperty("gateway.api-key", "from-sysprop");
        try {
            Path p = write(
                    "control.config-url=http://127.0.0.1:" + server.getAddress().getPort()
                            + "/api/dimensions/agent-configs/resolved",
                    "gateway.endpoint=http://gw-local:8080",
                    "gateway.api-key=from-file",
                    "game.id=g1",
                    "game.environment=prod",
                    "source.type=mysql",
                    "source.jdbc.url=jdbc:mysql://db:3306/game",
                    "source.jdbc.user=ro",
                    "source.jdbc.query=SELECT 1 WHERE x > ? ORDER BY x",
                    "source.jdbc.cursor-column=x");
            AgentConfig c = AgentConfig.load(new String[]{"--config=" + p});
            assertEquals("from-sysprop", c.gatewayApiKey);
        } finally {
            if (previous == null) {
                System.clearProperty("gateway.api-key");
            } else {
                System.setProperty("gateway.api-key", previous);
            }
            server.stop(0);
        }
    }

    @Test
    @DisplayName("热更新键：control.config-refresh-seconds 解析（缺省 0）；appliedRemoteText 记录下发原文")
    void parsesRefreshSecondsAndAppliedRemoteText() throws Exception {
        // 未配托管：refresh 0、configUrl null、appliedRemoteText null
        Path p = write(
                "gateway.endpoint=http://gw:8080",
                "gateway.api-key=k1",
                "game.id=g1",
                "game.environment=prod",
                "source.type=mysql",
                "source.jdbc.url=jdbc:mysql://db:3306/game",
                "source.jdbc.user=ro",
                "source.jdbc.query=SELECT 1 WHERE x > ? ORDER BY x",
                "source.jdbc.cursor-column=x");
        AgentConfig c = AgentConfig.load(new String[]{"--config=" + p});
        assertEquals(0, c.configRefreshSeconds);
        assertNull(c.configUrl);
        assertNull(c.appliedRemoteText);
        c.validate();

        // 配了 refresh + url：解析生效；override 直填模拟已拉取文本（不触网）
        Path h = write(
                "control.config-url=http://127.0.0.1:1/resolved",
                "control.config-refresh-seconds=120",
                "gateway.endpoint=http://gw-local:8080",
                "gateway.api-key=k1",
                "game.id=g1",
                "game.environment=prod",
                "source.type=mysql",
                "source.jdbc.url=jdbc:mysql://db:3306/game",
                "source.jdbc.user=ro",
                "source.jdbc.query=SELECT 1 WHERE x > ? ORDER BY x",
                "source.jdbc.cursor-column=x");
        String override = "gateway.endpoint=http://gw-remote:8080\nagent.poll-seconds=5\n";
        AgentConfig hc = AgentConfig.load(new String[]{"--config=" + h},
                new ControlConfigFetcher(), override);
        assertEquals(120, hc.configRefreshSeconds);
        assertEquals("http://127.0.0.1:1/resolved", hc.configUrl);
        assertEquals(override, hc.appliedRemoteText);
        // override 文本覆盖本地同名值（127.0.0.1:1 若真发起请求必然失败走回落，断言即证未拉取）
        assertEquals("http://gw-remote:8080", hc.gatewayEndpoint);
        assertEquals(5, hc.pollSeconds);
        hc.validate();
    }

    @Test
    @DisplayName("热更新校验：负数拒；refresh>0 而 config-url 缺失拒；启用且有 url 通过")
    void refreshSecondsValidation() throws Exception {
        String[] base = {
                "gateway.endpoint=http://gw:8080",
                "gateway.api-key=k1",
                "game.id=g1",
                "game.environment=prod",
                "source.type=mysql",
                "source.jdbc.url=jdbc:mysql://db:3306/game",
                "source.jdbc.user=ro",
                "source.jdbc.query=SELECT 1 WHERE x > ? ORDER BY x",
                "source.jdbc.cursor-column=x"};

        Path neg = write(concat(base, "control.config-refresh-seconds=-1"));
        IllegalArgumentException e1 = assertThrows(IllegalArgumentException.class,
                () -> AgentConfig.load(new String[]{"--config=" + neg}).validate());
        assertTrue(e1.getMessage().contains("不能为负数"));

        Path noUrl = write(concat(base, "control.config-refresh-seconds=60"));
        IllegalArgumentException e2 = assertThrows(IllegalArgumentException.class,
                () -> AgentConfig.load(new String[]{"--config=" + noUrl}).validate());
        assertTrue(e2.getMessage().contains("需配合 control.config-url"));

        Path ok = write(concat(base, "control.config-refresh-seconds=60",
                "control.config-url=http://control:8080/api/dimensions/agent-configs/resolved"));
        AgentConfig.load(new String[]{"--config=" + ok}).validate();
    }

}
