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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Gateway 推送端：事件契约、NDJSON 分块、x-api-key 头、非 2xx 抛错。 */
class GatewaySinkTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    HttpServer server;
    final ConcurrentLinkedQueue<String> bodies = new ConcurrentLinkedQueue<>();
    final ConcurrentLinkedQueue<String> apiKeys = new ConcurrentLinkedQueue<>();
    final AtomicInteger status = new AtomicInteger(200);

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/batch", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            apiKeys.add(exchange.getRequestHeaders().getFirst("x-api-key"));
            byte[] resp = Integer.toString(status.get()).getBytes(StandardCharsets.UTF_8);
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

    private AgentConfig cfg(int batchSize) {
        AgentConfig cfg = new AgentConfig();
        cfg.gatewayEndpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        cfg.gatewayApiKey = "secret-key";
        cfg.gameId = "g1";
        cfg.environment = "prod";
        cfg.batchSize = batchSize;
        return cfg;
    }

    private DimensionChange change(String id) {
        return new DimensionChange("item", "upsert", id, 1735689605000L).attr("name", "n-" + id);
    }

    @Test
    @DisplayName("toEvent：事件契约字段齐备（Gateway 必填五元组 + props.attributes 内嵌）")
    void toEventContract() {
        Map<String, Object> e = new GatewaySink(cfg(500)).toEvent(change("sword_01"));
        assertNotNull(e.get("event_id"));
        assertEquals("dimension_define", e.get("event_name"));
        assertEquals("dimension", e.get("event_type"));
        assertEquals("g1", e.get("game_id"));
        assertEquals("prod", e.get("environment"));
        assertEquals("oddsmaker-agent", e.get("device_id"));
        assertTrue((Long) e.get("ts_server") > 0);

        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) e.get("props");
        assertEquals("item", props.get("dim_type"));
        assertEquals("sword_01", props.get("resource_id"));
        assertEquals("upsert", props.get("op"));
        assertEquals(1735689605000L, props.get("version_ts"));
        @SuppressWarnings("unchecked")
        Map<String, Object> attrs = (Map<String, Object>) props.get("attributes");
        assertEquals("n-sword_01", attrs.get("name"));
    }

    @Test
    @DisplayName("toNdjson：每变更一行合法 JSON")
    void ndjsonOneLinePerChange() throws Exception {
        String nd = new GatewaySink(cfg(500)).toNdjson(List.of(change("a"), change("b")));
        String[] lines = nd.split("\n");
        assertEquals(2, lines.length);
        for (String line : lines) {
            assertTrue(JSON.readValue(line, Map.class).containsKey("event_id"));
        }
    }

    @Test
    @DisplayName("push：按 batchSize 分块（3 条 × 批 2 → 2 次请求），携带 x-api-key")
    void pushChunksByBatchSize() throws Exception {
        long pushed = new GatewaySink(cfg(2))
                .push(List.of(change("a"), change("b"), change("c")));
        assertEquals(3, pushed);
        assertEquals(2, bodies.size());
        List<String> all = new ArrayList<>(bodies);
        assertEquals(2, all.get(0).split("\n").length);
        assertEquals(1, all.get(1).split("\n").length);
        assertTrue(apiKeys.stream().allMatch("secret-key"::equals));
    }

    @Test
    @DisplayName("非 2xx 抛异常且信息含状态码")
    void non2xxThrows() {
        status.set(500);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new GatewaySink(cfg(500)).push(List.of(change("a"))));
        assertTrue(ex.getMessage().contains("500"));
    }

    @Test
    @DisplayName("空列表零请求")
    void emptyPushIsNoop() throws Exception {
        assertEquals(0, new GatewaySink(cfg(500)).push(List.of()));
        assertEquals(0, bodies.size());
    }
}
