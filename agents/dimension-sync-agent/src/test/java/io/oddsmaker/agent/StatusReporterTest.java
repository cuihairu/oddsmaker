package io.oddsmaker.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 状态上报：心跳语义、camelCase 契约（对齐 Control StatusUpsert）、失败仅记日志不抛。 */
class StatusReporterTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    HttpServer server;
    final ConcurrentLinkedQueue<String> bodies = new ConcurrentLinkedQueue<>();
    final ConcurrentLinkedQueue<String> tokens = new ConcurrentLinkedQueue<>();
    final AtomicInteger status = new AtomicInteger(200);

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/dimensions/sync-status", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            tokens.add(exchange.getRequestHeaders().getFirst("x-admin-token"));
            byte[] resp = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private AgentConfig cfg() {
        AgentConfig cfg = new AgentConfig();
        cfg.gameId = "g1";
        cfg.environment = "prod";
        cfg.sourceType = "mysql";
        cfg.statusToken = "admin-token";
        cfg.statusUrl = "http://127.0.0.1:" + server.getAddress().getPort()
                + "/api/dimensions/sync-status";
        return cfg;
    }

    private Checkpoint checkpoint() {
        Checkpoint cp = new Checkpoint();
        cp.cursor = "n:123";
        cp.lastEventTs = 1735689605000L;
        cp.pushedCount = 42;
        cp.errorCount = 1;
        cp.lastError = "earlier boom";
        return cp;
    }

    @Test
    @DisplayName("report：camelCase 载荷 + x-admin-token 头，200 返回 true")
    void reportsCamelCasePayload() throws Exception {
        assertTrue(new StatusReporter(cfg()).report(checkpoint()));
        assertEquals(1, bodies.size());
        Map<?, ?> payload = JSON.readValue(bodies.poll(), Map.class);
        assertEquals("g1", payload.get("gameId"));
        assertEquals("prod", payload.get("environment"));
        assertEquals("mysql", payload.get("sourceType"));
        assertEquals("n:123", payload.get("cursor"));
        assertEquals(1735689605000L, payload.get("lastEventTs"));
        assertEquals(42, payload.get("pushedCount"));
        assertEquals(1, payload.get("errorCount"));
        assertEquals("earlier boom", payload.get("lastError"));
        assertEquals("admin-token", tokens.poll());
    }

    @Test
    @DisplayName("sourceKey 未配置时缺省 agent-<sourceType>")
    void defaultSourceKey() throws Exception {
        new StatusReporter(cfg()).report(new Checkpoint());
        assertEquals("agent-mysql", JSON.readValue(bodies.poll(), Map.class).get("sourceKey"));
    }

    @Test
    @DisplayName("url 未配置静默跳过")
    void disabledWhenUrlBlank() {
        AgentConfig cfg = cfg();
        cfg.statusUrl = " ";
        assertFalse(new StatusReporter(cfg).enabled());
        assertFalse(new StatusReporter(cfg).report(checkpoint()));
        assertEquals(0, bodies.size());
    }

    @Test
    @DisplayName("非 2xx / 连接失败仅返回 false，不抛异常（不影响同步主链路）")
    void failuresAreSwallowed() {
        status.set(503);
        assertFalse(new StatusReporter(cfg()).report(checkpoint()));

        AgentConfig unreachable = cfg();
        unreachable.statusUrl = "http://127.0.0.1:1/api/dimensions/sync-status";
        assertFalse(new StatusReporter(unreachable).report(checkpoint()));
        assertEquals(1, bodies.size());
    }
}
