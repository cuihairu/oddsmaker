package io.oddsmaker.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 远程客户端真实 HTTP 路径：AuthService 远端 key 拉取与 60s 缓存、
 * BlockListClient 批量查询解析/缓存/降级（JDK 内置 HttpServer 模拟 control-service）。
 */
@DisplayName("远程客户端 HTTP 路径测试")
class GatewayRemoteClientsTest {

    private HttpServer server;
    private int port;
    private final AtomicInteger keyLookups = new AtomicInteger();
    private final AtomicInteger blockChecks = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/internal/api-keys/", ex -> {
            keyLookups.incrementAndGet();
            String path = ex.getRequestURI().getPath();
            byte[] body;
            if (path.endsWith("/k_remote")) {
                body = ("{\"apiKey\":\"k_remote\",\"secret\":\"sk\",\"gameId\":\"game_x\",\"environment\":\"prod\","
                        + "\"canWrite\":true,\"envStatus\":\"active\"}").getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
            } else {
                ex.sendResponseHeaders(404, -1);
            }
            ex.close();
        });
        server.createContext("/internal/block-lists/batch-check", ex -> {
            blockChecks.incrementAndGet();
            // 排空请求体：JdkHttpServer 不读 body 就响应会让客户端发送中断、响应被判失败
            ex.getRequestBody().readAllBytes();
            byte[] body = ("{\"results\":["
                    + "{\"targetType\":\"device_id\",\"targetValue\":\"d_block\",\"blocked\":true},"
                    + "{\"targetType\":\"device_id\",\"targetValue\":\"d_free\",\"blocked\":false},"
                    + "{\"targetType\":\"player_id\",\"targetValue\":\"u_block\",\"blocked\":true}"
                    + "]}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private MockEnvironment envWithControl() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("oddsmaker.control.url", "http://127.0.0.1:" + port);
        env.setProperty("oddsmaker.control.internal-token", "tok");
        env.setProperty("oddsmaker.blocklist.internal-token", "tok");
        // 默认 200ms 是生产降级阈值：冷启动首次请求（建连+DNS）在 CI 上偶发超限
        // 触发 onErrorResume 降级为全 false。测试放宽到 3s，只验证正常路径。
        env.setProperty("oddsmaker.blocklist.timeout-ms", "3000");
        return env;
    }

    // ===== AuthService =====

    @Test
    @DisplayName("Auth：远端 200 拉取上下文并写 60s 缓存（二次调用不再请求）")
    void authServiceFetchesAndCachesRemoteContext() {
        AuthService service = new AuthService(envWithControl(), new SimpleMeterRegistry());
        AuthService.ApiKeyContext ctx = service.getContext("k_remote");
        assertNotNull(ctx);
        assertEquals("game_x", ctx.gameId);
        assertEquals("prod", ctx.environment);
        assertTrue(ctx.allowsWrite());
        assertTrue(ctx.envWritable());
        assertTrue(ctx.isScoped());
        assertEquals(1, keyLookups.get());

        // 缓存命中：不再发请求
        AuthService.ApiKeyContext cached = service.getContext("k_remote");
        assertEquals("game_x", cached.gameId);
        assertEquals(1, keyLookups.get());
    }

    @Test
    @DisplayName("Auth：远端 404 回退本地密钥表；本地也无则 null")
    void authServiceFallsBackOnHttpError() {
        MockEnvironment env = envWithControl();
        env.setProperty("oddsmaker.auth.keys.k_local", "sk_local");
        AuthService service = new AuthService(env, new SimpleMeterRegistry());

        assertNull(service.getContext("k_unknown"));
        AuthService.ApiKeyContext local = service.getContext("k_local");
        assertNotNull(local);
        assertEquals("sk_local", local.secret);
        assertEquals(2, keyLookups.get()); // k_unknown + k_local 各查一次远端
    }

    // ===== BlockListClient =====

    @Test
    @DisplayName("封禁：批量查询解析 results、命中缓存后不再请求")
    void blockListParsesResultsAndCaches() {
        BlockListClient client = new BlockListClient(envWithControl());
        List<BlockListClient.BatchTarget> targets = List.of(
            new BlockListClient.BatchTarget("device_id", "d_block"),
            new BlockListClient.BatchTarget("device_id", "d_free"),
            new BlockListClient.BatchTarget("player_id", "u_block"));
        Map<String, Boolean> result = client.batchCheck("game_x", targets).block();
        assertEquals(Boolean.TRUE, result.get("device_id:d_block"));
        assertEquals(Boolean.FALSE, result.get("device_id:d_free"));
        assertEquals(Boolean.TRUE, result.get("player_id:u_block"));
        assertEquals(1, blockChecks.get());

        // TTL 内全部命中缓存，远端零请求
        Map<String, Boolean> again = client.batchCheck("game_x", targets).block();
        assertEquals(Boolean.TRUE, again.get("device_id:d_block"));
        assertEquals(1, blockChecks.get());
    }

    @Test
    @DisplayName("封禁：未启用直接返回空；空 targets 返回空")
    void blockListDisabledOrEmptyTargets() {
        MockEnvironment env = envWithControl();
        env.setProperty("oddsmaker.blocklist.enabled", "false");
        BlockListClient client = new BlockListClient(env);
        Map<String, Boolean> result = client.batchCheck("game_x",
            List.of(new BlockListClient.BatchTarget("device_id", "d1"))).block();
        assertTrue(result.isEmpty());
        assertEquals(0, blockChecks.get());

        BlockListClient enabled = new BlockListClient(envWithControl());
        assertTrue(enabled.batchCheck("game_x", List.of()).block().isEmpty());
        assertTrue(enabled.batchCheck("game_x", null).block().isEmpty());
    }

    @Test
    @DisplayName("封禁：远端不可达降级为全部放行")
    void blockListDegradesWhenControlDown() {
        MockEnvironment env = new MockEnvironment();
        // 指向一个必然无服务的端口
        env.setProperty("oddsmaker.control.url", "http://127.0.0.1:1");
        env.setProperty("oddsmaker.blocklist.internal-token", "tok");
        env.setProperty("oddsmaker.blocklist.timeout-ms", "200");
        BlockListClient client = new BlockListClient(env);
        Map<String, Boolean> result = client.batchCheck("game_x",
            List.of(new BlockListClient.BatchTarget("device_id", "d_block"))).block();
        assertEquals(Boolean.FALSE, result.get("device_id:d_block"));
    }

    @Test
    @DisplayName("封禁：BatchTarget 无参构造与字段赋值（反序列化路径）")
    void batchTargetDefaultCtor() throws Exception {
        BlockListClient.BatchTarget t = new BlockListClient.BatchTarget();
        assertNotNull(t);
        t.targetType = "device_id";
        t.targetValue = "d1";
        assertEquals("device_id", t.targetType);
        // Jackson 反序列化等价路径
        BlockListClient.BatchTarget viaJson =
            new ObjectMapper().readValue("{\"targetType\":\"player_id\",\"targetValue\":\"u1\"}",
                BlockListClient.BatchTarget.class);
        assertEquals("player_id", viaJson.targetType);
        assertEquals("u1", viaJson.targetValue);
    }
}
