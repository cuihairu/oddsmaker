package io.oddsmaker.agent;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Control 托管配置拉取：GET resolved 端点——URL 查询参数编码、x-admin-token 头、
 * 非 2xx / 空体抛 IOException（由 AgentConfig.load 兜底回落本地文件）。
 */
@DisplayName("Control 托管配置拉取")
class ControlConfigFetcherTest {

    HttpServer server;
    final ConcurrentLinkedQueue<String> queries = new ConcurrentLinkedQueue<>();
    final ConcurrentLinkedQueue<String> tokens = new ConcurrentLinkedQueue<>();
    final AtomicInteger status = new AtomicInteger(200);
    String responseBody = "game.id=g1\n";

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/dimensions/agent-configs/resolved", exchange -> {
            queries.add(exchange.getRequestURI().getRawQuery());
            tokens.add(exchange.getRequestHeaders().getFirst("x-admin-token"));
            byte[] resp = responseBody.getBytes(StandardCharsets.UTF_8);
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

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort()
                + "/api/dimensions/agent-configs/resolved";
    }

    @Test
    @DisplayName("fetch：查询参数带身份、x-admin-token 头、200 返回响应体")
    void fetchSendsIdentityAndToken() throws Exception {
        String text = new ControlConfigFetcher().fetch(url(), "g1", "prod", "items-mysql", "tok-1");
        assertEquals("game.id=g1\n", text);
        assertEquals("gameId=g1&environment=prod&agentKey=items-mysql", queries.poll());
        assertEquals("tok-1", tokens.poll());
    }

    @Test
    @DisplayName("fetch：查询值 URL 编码（空格/中文）")
    void fetchEncodesQueryValues() throws Exception {
        new ControlConfigFetcher().fetch(url(), "g 1", "prod", "项 目", null);
        assertEquals("gameId=g+1&environment=prod&agentKey=" + java.net.URLEncoder.encode("项 目", StandardCharsets.UTF_8),
                queries.poll());
        assertEquals("", tokens.poll());
    }

    @Test
    @DisplayName("fetch：非 2xx 抛 IOException（HTTP 404）")
    void fetchRejectsNon2xx() {
        status.set(404);
        IOException e = assertThrows(IOException.class,
                () -> new ControlConfigFetcher().fetch(url(), "g1", "prod", "missing", "t"));
        assertEquals("HTTP 404", e.getMessage());
    }

    @Test
    @DisplayName("fetch：空响应体抛 IOException")
    void fetchRejectsEmptyBody() {
        responseBody = "";
        IOException e = assertThrows(IOException.class,
                () -> new ControlConfigFetcher().fetch(url(), "g1", "prod", "k", "t"));
        assertEquals("响应体为空", e.getMessage());
    }

    @Test
    @DisplayName("fetch：缺查询身份抛 IOException（不发请求）")
    void fetchRequiresIdentity() {
        IOException e = assertThrows(IOException.class,
                () -> new ControlConfigFetcher().fetch(url(), null, "prod", "k", "t"));
        assertTrue(e.getMessage().contains("查询身份"));
        assertTrue(queries.isEmpty());
    }
}
