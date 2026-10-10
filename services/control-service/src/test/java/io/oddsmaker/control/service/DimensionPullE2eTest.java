package io.oddsmaker.control.service;

import com.sun.net.httpserver.HttpServer;
import io.oddsmaker.control.jpa.ApiKeyEntity;
import io.oddsmaker.control.jpa.ApiKeyRepo;
import io.oddsmaker.control.jpa.DimensionPullConfigEntity;
import io.oddsmaker.control.jpa.DimensionPullConfigRepo;
import io.oddsmaker.control.jpa.DimensionSyncStatusEntity;
import io.oddsmaker.control.jpa.DimensionSyncStatusRepo;
import io.oddsmaker.control.jpa.GameEnvironmentEntity;
import io.oddsmaker.control.jpa.GameEnvironmentRepo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.client.RestTemplate;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * HTTP Pull 链路端到端（mock 游戏 API + mock Gateway，真 HTTP 回环）：两页拉取全程验证——
 * 首拉无 updated_after、Bearer 头、next_cursor 断点续传、NDJSON 推 /v1/batch（x-api-key +
 * source_type=pull 契约）、cursor 缺省回落本页最大 version_ts、pushedCount/状态行落库。
 * 失败臂：游戏 API 5xx → errorCount 记账 cursor 不前进、缺作用域 key 拒推。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("HTTP Pull 端到端（mock 游戏 API）")
class DimensionPullE2eTest {

    @Mock
    private DimensionPullConfigRepo configRepo;

    @Mock
    private DimensionSyncStatusRepo statusRepo;

    @Mock
    private io.oddsmaker.control.jpa.GameRepo gameRepo;

    @Mock
    private GameEnvironmentRepo envRepo;

    @Mock
    private ApiKeyRepo keyRepo;

    private final DimensionCredentialCipher cipher = new DimensionCredentialCipher("e2e-key");

    private final RestTemplate restTemplate = new RestTemplate();

    private HttpServer server;
    private int port;

    /** 游戏 API 收到的请求：query 参数 + Authorization 头 */
    private final List<Map<String, String>> gameRequests = new ArrayList<>();
    /** Gateway /v1/batch 收到的推送：x-api-key + NDJSON 体 */
    private final List<Map<String, String>> batchCalls = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        // ===== mock 游戏 API：两页（第一页带 next_cursor，第二页到头） =====
        server.createContext("/v1/items", exchange -> {
            Map<String, String> params = parseQuery(exchange.getRequestURI().getRawQuery());
            params.put("authorization", exchange.getRequestHeaders().getFirst("Authorization"));
            gameRequests.add(params);
            boolean firstPage = params.get("updated_after") == null;
            String body = firstPage
                    ? "{\"items\":["
                      + "{\"item_code\":\"sword_001\",\"item_name\":\"铁剑\",\"rarity\":\"common\",\"type\":\"weapon\",\"updated_at\":1760000000000},"
                      + "{\"item_code\":\"sword_002\",\"item_name\":\"钢剑\",\"rarity\":\"rare\",\"type\":\"weapon\",\"updated_at\":1760000000001}"
                      + "],\"next_cursor\":\"1760000000001\"}"
                    : "{\"items\":["
                      + "{\"item_code\":\"shield_001\",\"item_name\":\"木盾\",\"rarity\":\"common\",\"type\":\"armor\",\"updated_at\":1760000100000}"
                      + "]}";
            respond(exchange, 200, body);
        });
        // ===== mock Gateway /v1/batch：记录 x-api-key + NDJSON =====
        server.createContext("/v1/batch", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> call = new LinkedHashMap<>();
            call.put("key", exchange.getRequestHeaders().getFirst("x-api-key"));
            call.put("body", body);
            batchCalls.add(call);
            respond(exchange, 200, "{\"accepted\":1}");
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private DimensionPullService service(DimensionPullConfigEntity config) {
        DimensionPullService svc = new DimensionPullService(configRepo, statusRepo, gameRepo, envRepo, keyRepo,
                cipher, restTemplate, "http://127.0.0.1:" + port);
        // 配置与仓储行为
        config.credentialEncrypted = cipher.encrypt("token-xyz");
        when(configRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(statusRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(statusRepo.findByGameIdAndEnvironmentAndSourceKey(config.gameId, config.environment, config.sourceKey))
                .thenReturn(Optional.empty());
        // 作用域 key：server key 绑定 (game_x, prod)
        ApiKeyEntity key = new ApiKeyEntity();
        key.apiKey = "pk_game_x_server";
        key.keyType = ApiKeyEntity.ApiKeyType.SERVER;
        key.status = ApiKeyEntity.ApiKeyStatus.ACTIVE;
        key.environmentId = "env_game_x_prod";
        when(keyRepo.findByGameIdAndStatus("game_x", ApiKeyEntity.ApiKeyStatus.ACTIVE)).thenReturn(List.of(key));
        GameEnvironmentEntity env = new GameEnvironmentEntity();
        env.id = "env_game_x_prod";
        env.gameId = "game_x";
        env.name = "prod";
        when(envRepo.findById("env_game_x_prod")).thenReturn(Optional.of(env));
        return svc;
    }

    private DimensionPullConfigEntity config() {
        DimensionPullConfigEntity c = new DimensionPullConfigEntity();
        c.id = "dpc_e2e";
        c.gameId = "game_x";
        c.environment = "prod";
        c.sourceKey = "items";
        c.dimType = "item";
        c.endpoint = "http://127.0.0.1:" + port + "/v1/items";
        c.enabled = true;
        c.intervalSeconds = 300;
        c.pageLimit = 1000;
        c.pushedCount = 0L;
        c.errorCount = 0L;
        return c;
    }

    @Test
    @DisplayName("两页端到端：首拉无 updated_after → next_cursor 续传 → 第二页到头回落 max version_ts")
    void twoPagePull() {
        DimensionPullConfigEntity config = config();
        DimensionPullService svc = service(config);

        // ===== 第一轮：拉第一页（2 行）并推 Gateway =====
        DimensionPullService.PullRunResult r1 = svc.runPull(config);
        assertTrue(r1.success());
        assertEquals(2, r1.pushed());
        assertEquals("1760000000001", r1.cursor());

        // 游戏 API 请求：无 updated_after（首拉）、limit、Bearer 头
        Map<String, String> req1 = gameRequests.get(0);
        assertNull(req1.get("updated_after"));
        assertEquals("1000", req1.get("limit"));
        assertEquals("Bearer token-xyz", req1.get("authorization"));

        // Gateway 推送：2 行 NDJSON，x-api-key，事件契约 source_type=pull
        assertEquals(1, batchCalls.size());
        assertEquals("pk_game_x_server", batchCalls.get(0).get("key"));
        String[] lines1 = batchCalls.get(0).get("body").split("\n");
        assertEquals(2, lines1.length);
        assertTrue(lines1[0].contains("\"event_name\":\"dimension_define\""));
        assertTrue(lines1[0].contains("\"event_type\":\"dimension\""));
        assertTrue(lines1[0].contains("\"game_id\":\"game_x\""));
        assertTrue(lines1[0].contains("\"environment\":\"prod\""));
        assertTrue(lines1[0].contains("\"source_type\":\"pull\""));
        assertTrue(lines1[0].contains("\"resource_id\":\"sword_001\""));

        // ===== 第二轮：以 next_cursor 续传第二页（1 行），到头回落 max version_ts =====
        DimensionPullService.PullRunResult r2 = svc.runPull(config);
        assertTrue(r2.success());
        assertEquals(1, r2.pushed());
        assertEquals("1760000100000", r2.cursor());

        Map<String, String> req2 = gameRequests.get(1);
        assertEquals("1760000000001", req2.get("updated_after"));
        assertEquals("Bearer token-xyz", req2.get("authorization"));

        String[] lines2 = batchCalls.get(1).get("body").split("\n");
        assertEquals(1, lines2.length);
        assertTrue(lines2[0].contains("\"resource_id\":\"shield_001\""));

        // ===== 进度记账：pushedCount 累计、cursor 前进、无错误 =====
        assertEquals(3L, config.pushedCount);
        assertEquals(0L, config.errorCount);
        assertNull(config.lastError);
        assertNotNull(config.lastEventTs);

        // ===== 状态行：source_type=pull，与 Agent 上报同表同口径 =====
        org.mockito.ArgumentCaptor<DimensionSyncStatusEntity> captor =
                org.mockito.ArgumentCaptor.forClass(DimensionSyncStatusEntity.class);
        org.mockito.Mockito.verify(statusRepo, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        DimensionSyncStatusEntity status = captor.getValue();
        assertEquals("pull", status.sourceType);
        assertEquals("1760000100000", status.cursor);
        assertEquals(3L, status.pushedCount);
        assertNotNull(status.lastPushAt);
        assertEquals("game_x", status.gameId);
        assertEquals("prod", status.environment);
        assertEquals("items", status.sourceKey);
    }

    @Test
    @DisplayName("失败臂：游戏 API 5xx → 不推网关、errorCount 记账、cursor 停留旧断点")
    void gameApiFailure() throws Exception {
        server.createContext("/v1/broken", exchange -> respond(exchange, 503, "unavailable"));
        DimensionPullConfigEntity config = config();
        config.endpoint = "http://127.0.0.1:" + port + "/v1/broken";
        config.lastCursor = "keep-me";
        DimensionPullService svc = service(config);

        DimensionPullService.PullRunResult r = svc.runPull(config);
        assertFalse(r.success());
        assertTrue(r.error().contains("503"));
        assertTrue(batchCalls.isEmpty());
        assertEquals(1L, config.errorCount);
        assertEquals("keep-me", config.lastCursor);
        assertNotNull(config.lastError);
        assertNotNull(config.lastPullAt);
    }

    @Test
    @DisplayName("失败臂：无作用域 server/admin key → 拉到数据但拒推并记账")
    void missingScopedKey() {
        DimensionPullConfigEntity config = config();
        DimensionPullService svc = service(config);
        when(keyRepo.findByGameIdAndStatus("game_x", ApiKeyEntity.ApiKeyStatus.ACTIVE))
                .thenReturn(List.of()); // 无可用 key

        DimensionPullService.PullRunResult r = svc.runPull(config);
        assertFalse(r.success());
        assertTrue(r.error().contains("API Key"));
        assertTrue(batchCalls.isEmpty());
        assertEquals(1L, config.errorCount);
        // 拉 API 成功但推送失败：cursor 不前进（下一轮重放同窗口，下游幂等）
        assertNull(config.lastCursor);
    }

    @Test
    @DisplayName("调度入口：仅到期配置被拉取，未到间隔与停用配置跳过")
    void schedulerIntervalGate() throws Exception {
        DimensionPullConfigEntity due = config();
        DimensionPullConfigEntity notDue = config();
        notDue.sourceKey = "levels";
        notDue.lastPullAt = java.time.LocalDateTime.now().minusSeconds(30); // interval 300s 未到
        DimensionPullConfigEntity disabled = config();
        disabled.sourceKey = "skills";
        disabled.enabled = false;

        when(configRepo.findAll()).thenReturn(List.of(due, notDue, disabled));
        DimensionPullService svc = service(due);

        svc.runDuePulls();

        assertEquals(1, gameRequests.size(), "实际请求: " + gameRequests);
        assertEquals(2L, due.pushedCount); // 首页 2 行全推
        assertEquals(0L, notDue.pushedCount);
        assertEquals(0L, disabled.pushedCount);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> params = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return params;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            params.put(urlDecode(key), urlDecode(value));
        }
        return params;
    }

    private static String urlDecode(String s) {
        return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}
