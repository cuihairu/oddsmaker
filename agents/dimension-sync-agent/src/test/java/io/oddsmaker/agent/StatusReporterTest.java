package io.oddsmaker.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

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

    // === 分支对侧补充（BRANCH 收口）===

    @Test
    @DisplayName("statusUrl 为 null（非空白串）：enabled/report 均静默跳过")
    void nullStatusUrlDisables() {
        AgentConfig cfg = cfg();
        cfg.statusUrl = null;
        assertFalse(new StatusReporter(cfg).enabled());
        assertFalse(new StatusReporter(cfg).report(checkpoint()));
        assertEquals(0, bodies.size());
    }

    @Test
    @DisplayName("sourceKey 已配置：载荷用配置值（不走 agent-<sourceType> 缺省）")
    void configuredSourceKeyIsUsed() throws Exception {
        AgentConfig cfg = cfg();
        cfg.sourceKey = "dim-sync-01";
        new StatusReporter(cfg).report(new Checkpoint());
        assertEquals("dim-sync-01", JSON.readValue(bodies.poll(), Map.class).get("sourceKey"));
    }

    @Test
    @DisplayName("statusToken 为 null：x-admin-token 头为空串（不 NPE、不省略头）")
    void nullStatusTokenSendsEmptyHeader() throws Exception {
        AgentConfig cfg = cfg();
        cfg.statusToken = null;
        new StatusReporter(cfg).report(new Checkpoint());
        assertEquals("", tokens.poll());
    }

    @Test
    @DisplayName("1xx 状态码（< 200）：同样判失败返回 false")
    void informationalStatusIsFailure() {
        status.set(199);
        assertFalse(new StatusReporter(cfg()).report(checkpoint()));
    }

    @Test
    @DisplayName("3xx 状态码（>= 300）：重定向也判失败返回 false（覆盖 resp.statusCode() >= 300 分支）")
    void redirectionStatusIsFailure() {
        status.set(302);
        assertFalse(new StatusReporter(cfg()).report(checkpoint()));
    }

    @Test
    @DisplayName("statusCode < 200 假分支：经注入缝投喂 199 合成响应 → 判失败返回 false")
    @SuppressWarnings("unchecked")
    void sub200FinalResponseIsFailure() {
        // java.net.http 把 1xx 当信息性响应继续等最终响应，真实回路给不出 <200 的最终
        // statusCode（真服务端 199 用例走的是异常 catch 路径）——该防御臂经包内注入缝
        // 直击：合成 199 响应 → report 判失败返回 false，不抛。
        HttpClient synthetic199 = new HttpClient() {
            @Override
            public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
                return (HttpResponse<T>) syntheticResponse(199);
            }

            @Override
            public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                    HttpResponse.BodyHandler<T> handler) {
                throw new UnsupportedOperationException("本用例只走同步 send");
            }

            @Override
            public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                    HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
                throw new UnsupportedOperationException("本用例只走同步 send");
            }

            @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
            @Override public Optional<Duration> connectTimeout() { return Optional.empty(); }
            @Override public HttpClient.Redirect followRedirects() { return null; }
            @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
            @Override public SSLContext sslContext() { return null; }
            @Override public SSLParameters sslParameters() { return null; }
            @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
            @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
            @Override public Optional<Executor> executor() { return Optional.empty(); }
        };
        assertFalse(new StatusReporter(cfg(), synthetic199).report(checkpoint()));
    }

    private static HttpResponse<String> syntheticResponse(int statusCode) {
        return new HttpResponse<>() {
            @Override public int statusCode() { return statusCode; }
            @Override public HttpRequest request() { return null; }
            @Override public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
            @Override public HttpHeaders headers() {
                return HttpHeaders.of(Map.of(), (k, v) -> true);
            }
            @Override public String body() { return "{}"; }
            @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
            @Override public URI uri() { return URI.create("http://127.0.0.1/"); }
            @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
    }
}
