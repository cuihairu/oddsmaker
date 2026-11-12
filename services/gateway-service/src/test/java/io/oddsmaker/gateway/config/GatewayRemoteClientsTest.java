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
            if (path.endsWith("/k_remote") || path.endsWith("/k_stale")) {
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
            String reqBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            // 跨游戏隔离测试：仅 game_x 且 prod 封禁 d_block/u_block；game_y 或非 prod 同 target 放行
            boolean gameX = reqBody.contains("\"gameId\":\"game_x\"");
            // 回显请求目标的 environment（无则缺省空）：封禁键含环境，跨环境同 target 不互串
            String env = reqBody.contains("\"environment\":\"prod\"") ? "prod"
                    : reqBody.contains("\"environment\":\"dev\"") ? "dev" : "";
            boolean targetBlocked = gameX && "prod".equals(env);
            String envJson = "\"environment\":\"" + env + "\",";
            byte[] body = ("{\"results\":["
                    + "{" + envJson + "\"targetType\":\"device_id\",\"targetValue\":\"d_block\",\"blocked\":" + targetBlocked + "},"
                    + "{" + envJson + "\"targetType\":\"device_id\",\"targetValue\":\"d_free\",\"blocked\":false},"
                    + "{" + envJson + "\"targetType\":\"player_id\",\"targetValue\":\"u_block\",\"blocked\":" + targetBlocked + "}"
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
        if (server != null) {
            server.stop(0);
        }
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

    @Test
    @DisplayName("Auth：无效 key 负缓存——10s 窗口内重复查询不再打远端（防洪泛打爆事件循环）")
    void authServiceNegativeCachesInvalidKey() {
        AuthService service = new AuthService(envWithControl(), new SimpleMeterRegistry());
        assertNull(service.getContext("k_unknown"));
        assertNull(service.getContext("k_unknown"));
        // 负缓存命中：第二次不再发远端（旧实现每次都对无效 key 打 3s 同步 HTTP）
        assertEquals(1, keyLookups.get());
    }

    @Test
    @DisplayName("Auth：control 不可达时 stale 回退上个周期上下文（仅 exception 路径，非 404）")
    void authServiceServesStaleWhenControlUnavailable() throws Exception {
        AuthService service = new AuthService(envWithControl(), new SimpleMeterRegistry());
        AuthService.ApiKeyContext first = service.getContext("k_stale");
        assertNotNull(first);
        assertEquals("game_x", first.gameId);
        assertEquals(1, keyLookups.get());

        // 模拟 control 宕机超过 60s TTL 后的首个请求：让缓存条目过期但仍在 cache（未清除）
        java.lang.reflect.Field f = AuthService.class.getDeclaredField("cache");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.concurrent.ConcurrentHashMap<String, AuthService.CacheEntry> cache =
            (java.util.concurrent.ConcurrentHashMap<String, AuthService.CacheEntry>) f.get(service);
        AuthService.CacheEntry entry = cache.get("k_stale");
        assertNotNull(entry);
        entry.expireAt = java.time.Instant.now().getEpochSecond() - 1;

        // control 不可达：停掉服务器 → 连接失败/中断 → ControlUnavailable → stale 回退
        server.stop(0);
        server = null;
        AuthService.ApiKeyContext stale = service.getContext("k_stale");
        assertNotNull(stale);
        assertEquals("game_x", stale.gameId);
        assertEquals("prod", stale.environment);

        // stale 顺延刷新：outage 内再请求直接命中缓存，不再打远端（避免每请求都等 3s 超时）
        AuthService.ApiKeyContext again = service.getContext("k_stale");
        assertNotNull(again);
        assertEquals("game_x", again.gameId);
        assertEquals(1, keyLookups.get());
    }

    // ===== BlockListClient =====

    @Test
    @DisplayName("封禁：批量查询解析 results、命中缓存后不再请求")
    void blockListParsesResultsAndCaches() {
        BlockListClient client = new BlockListClient(envWithControl());
        List<BlockListClient.BatchTarget> targets = List.of(
            new BlockListClient.BatchTarget("prod", "device_id", "d_block"),
            new BlockListClient.BatchTarget("prod", "device_id", "d_free"),
            new BlockListClient.BatchTarget("prod", "player_id", "u_block"));
        Map<String, Boolean> result = client.batchCheck("game_x", targets).block();
        assertEquals(Boolean.TRUE, result.get("game_x:prod:device_id:d_block"));
        assertEquals(Boolean.FALSE, result.get("game_x:prod:device_id:d_free"));
        assertEquals(Boolean.TRUE, result.get("game_x:prod:player_id:u_block"));
        assertEquals(1, blockChecks.get());

        // TTL 内全部命中缓存，远端零请求
        Map<String, Boolean> again = client.batchCheck("game_x", targets).block();
        assertEquals(Boolean.TRUE, again.get("game_x:prod:device_id:d_block"));
        assertEquals(1, blockChecks.get());
    }

    @Test
    @DisplayName("封禁：缓存键含 gameId，跨游戏同 target 不互串（T-REV10）")
    void blockListCacheIsScopedByGame() {
        BlockListClient client = new BlockListClient(envWithControl());
        List<BlockListClient.BatchTarget> targets = List.of(
            new BlockListClient.BatchTarget("prod", "device_id", "d_block"));
        Map<String, Boolean> gameX = client.batchCheck("game_x", targets).block();
        assertEquals(Boolean.TRUE, gameX.get("game_x:prod:device_id:d_block"));

        // 同 target 换游戏：不得命中 game_x 缓存，须重新请求远端（远端对 game_y 该 target 放行）
        Map<String, Boolean> gameY = client.batchCheck("game_y", targets).block();
        assertEquals(Boolean.FALSE, gameY.get("game_y:prod:device_id:d_block"));
        assertEquals(2, blockChecks.get());
    }

    @Test
    @DisplayName("封禁：缓存键含 environment，同 target 跨环境不互串（P0 风险自动封禁环境维度）")
    void blockListCacheIsScopedByEnvironment() {
        BlockListClient client = new BlockListClient(envWithControl());
        // prod 封禁 d_block；同 game 同 target 换 dev——不得命中 prod 缓存，须重新请求远端，
        // 远端对非 prod 该 target 放行（环境维度封禁不跨环境泄漏）
        Map<String, Boolean> prod = client.batchCheck("game_x",
            List.of(new BlockListClient.BatchTarget("prod", "device_id", "d_block"))).block();
        assertEquals(Boolean.TRUE, prod.get("game_x:prod:device_id:d_block"));
        assertEquals(1, blockChecks.get());

        Map<String, Boolean> dev = client.batchCheck("game_x",
            List.of(new BlockListClient.BatchTarget("dev", "device_id", "d_block"))).block();
        assertEquals(Boolean.FALSE, dev.get("game_x:dev:device_id:d_block"));
        assertEquals(2, blockChecks.get());

        // prod 再次查询：prod 缓存仍在，dev 结果未污染 prod
        Map<String, Boolean> prodAgain = client.batchCheck("game_x",
            List.of(new BlockListClient.BatchTarget("prod", "device_id", "d_block"))).block();
        assertEquals(Boolean.TRUE, prodAgain.get("game_x:prod:device_id:d_block"));
        assertEquals(2, blockChecks.get());
    }

    @Test
    @DisplayName("封禁：未启用直接返回空；空 targets 返回空")
    void blockListDisabledOrEmptyTargets() {
        MockEnvironment env = envWithControl();
        env.setProperty("oddsmaker.blocklist.enabled", "false");
        BlockListClient client = new BlockListClient(env);
        Map<String, Boolean> result = client.batchCheck("game_x",
            List.of(new BlockListClient.BatchTarget("prod", "device_id", "d1"))).block();
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
            List.of(new BlockListClient.BatchTarget("prod", "device_id", "d_block"))).block();
        assertEquals(Boolean.FALSE, result.get("game_x:prod:device_id:d_block"));
    }

    @Test
    @DisplayName("封禁：BatchTarget 无参/两参/三参构造与字段赋值（反序列化路径）")
    void batchTargetDefaultCtor() throws Exception {
        BlockListClient.BatchTarget t = new BlockListClient.BatchTarget();
        assertNotNull(t);
        t.targetType = "device_id";
        t.targetValue = "d1";
        assertEquals("device_id", t.targetType);
        assertEquals(null, t.environment);
        // 两参构造：environment 缺省 null（历史调用方/测试）
        BlockListClient.BatchTarget twoArg = new BlockListClient.BatchTarget("device_id", "d2");
        assertEquals("d2", twoArg.targetValue);
        assertEquals(null, twoArg.environment);
        // 三参构造：environment 显式携带
        BlockListClient.BatchTarget threeArg = new BlockListClient.BatchTarget("prod", "player_id", "u9");
        assertEquals("prod", threeArg.environment);
        assertEquals("player_id", threeArg.targetType);
        // Jackson 反序列化等价路径（含 environment）
        BlockListClient.BatchTarget viaJson =
            new ObjectMapper().readValue("{\"targetType\":\"player_id\",\"targetValue\":\"u1\",\"environment\":\"prod\"}",
                BlockListClient.BatchTarget.class);
        assertEquals("player_id", viaJson.targetType);
        assertEquals("u1", viaJson.targetValue);
        assertEquals("prod", viaJson.environment);
    }

    // ===== 分支对侧补充（BRANCH 收口）=====

    @Test
    @DisplayName("封禁：缓存条目过期后重新请求远端（expireAt <= now 侧）")
    void blockListExpiredEntryRefetches() {
        BlockListClient client = new BlockListClient(envWithControl());
        List<BlockListClient.BatchTarget> targets =
            List.of(new BlockListClient.BatchTarget("prod", "device_id", "d_block"));
        Map<String, Boolean> first = client.batchCheck("game_x", targets).block();
        assertEquals(Boolean.TRUE, first.get("game_x:prod:device_id:d_block"));
        assertEquals(1, blockChecks.get());

        // 缓存条目置为过期：下次查询必须穿透缓存重新请求（键含 environment）
        try {
            @SuppressWarnings("unchecked")
            java.util.concurrent.ConcurrentHashMap<String, Object> cache =
                (java.util.concurrent.ConcurrentHashMap<String, Object>)
                    org.springframework.test.util.ReflectionTestUtils.getField(client, "cache");
            Object entry = cache.get("game_x:prod:device_id:d_block");
            org.springframework.test.util.ReflectionTestUtils.setField(entry, "expireAt",
                java.time.Instant.now().getEpochSecond() - 1);
        } catch (Exception ignored) {
            // 结构变化时跳过穿透断言
        }
        Map<String, Boolean> second = client.batchCheck("game_x", targets).block();
        assertEquals(Boolean.TRUE, second.get("game_x:prod:device_id:d_block"));
        assertEquals(2, blockChecks.get());
    }

    @Test
    @DisplayName("封禁：畸形响应体（results 非 List / 元素非 Map）容错解析")
    void blockListMalformedResponseTolerated() {
        // 切换 mock 响应为畸形体
        server.removeContext("/internal/block-lists/batch-check");
        server.createContext("/internal/block-lists/batch-check", ex -> {
            blockChecks.incrementAndGet();
            ex.getRequestBody().readAllBytes();
            // results 非列表 + 列表内非对象元素：全部容错跳过
            byte[] body = ("{\"results\":\"not-a-list\"}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        BlockListClient client = new BlockListClient(envWithControl());
        Map<String, Boolean> r = client.batchCheck("game_x",
            List.of(new BlockListClient.BatchTarget("device_id", "d1"))).block();
        assertEquals(Boolean.FALSE, r.get("game_x::device_id:d1")); // 未识别 → 不封禁（env 缺省空串参与键）

        // results 为列表但元素非 Map
        server.removeContext("/internal/block-lists/batch-check");
        server.createContext("/internal/block-lists/batch-check", ex -> {
            ex.getRequestBody().readAllBytes();
            byte[] body = ("{\"results\":[\"str-item\", 42]}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        BlockListClient client2 = new BlockListClient(envWithControl());
        Map<String, Boolean> r2 = client2.batchCheck("game_x",
            List.of(new BlockListClient.BatchTarget("device_id", "d2"))).block();
        assertEquals(Boolean.FALSE, r2.get("game_x::device_id:d2"));
    }
}
