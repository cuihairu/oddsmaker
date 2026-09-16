package io.oddsmaker.gateway.api;

import io.oddsmaker.gateway.config.AuthService;
import io.oddsmaker.gateway.config.BlockListClient;
import io.oddsmaker.gateway.kafka.AvroPublisher;
import io.oddsmaker.gateway.kafka.DlqPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BatchController 端到端分支覆盖：key 作用域/环境维护态/环境采样/封禁联动/Kafka 异常/超大事件载荷。
 * key 上下文与封禁查询经 @MockBean 注入（避免真实 HTTP 依赖），走完整 WebFilter + Controller 链。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@DisplayName("批量入口端到端分支测试")
class BatchControllerCoverageTest {

    @Autowired
    WebTestClient client;

    @org.springframework.boot.test.web.server.LocalServerPort
    int port;

    @MockBean
    AvroPublisher avroPublisher;

    @MockBean
    DlqPublisher dlqPublisher;

    @MockBean
    AuthService authService;

    @MockBean
    BlockListClient blockListClient;

    @BeforeEach
    void defaultStubs() {
        // 默认：任意 key 都是可写、active、无采样；封禁查询返回空（未封禁）
        when(authService.getContext(anyString())).thenReturn(unscopedKey());
        when(blockListClient.batchCheck(anyString(), anyList()))
            .thenReturn(Mono.just(Map.of()));
    }

    private AuthService.ApiKeyContext unscopedKey() {
        AuthService.ApiKeyContext ctx = new AuthService.ApiKeyContext();
        ctx.apiKey = "pk_test_example";
        ctx.secret = "sk";
        ctx.canWrite = true;
        ctx.envStatus = "active";
        return ctx;
    }

    private String event(String eventId, String gameId, String deviceId, String userId) {
        return "{\"event_id\":\"" + eventId + "\",\"event_name\":\"level_start\","
            + "\"game_id\":\"" + gameId + "\",\"environment\":\"prod\","
            + (deviceId != null ? "\"device_id\":\"" + deviceId + "\"," : "")
            + (userId != null ? "\"user_id\":\"" + userId + "\"," : "")
            + "\"ts_client\":1730000000000}";
    }

    @Test
    @DisplayName("作用域 key 跨游戏事件被拒 api_key_scope_mismatch")
    void scopeMismatchRejected() {
        AuthService.ApiKeyContext scoped = unscopedKey();
        scoped.gameId = "game_x";
        scoped.environment = "prod";
        when(authService.getContext("pk_scope")).thenReturn(scoped);

        client.post().uri("/v1/batch")
            .contentType(MediaType.APPLICATION_JSON)
            .header("x-api-key", "pk_scope")
            .bodyValue("[" + event("01JSCOPE0001", "game_other", "d1", null) + "]")
            .exchange()
            .expectStatus().is2xxSuccessful()
            .expectBody()
            .jsonPath("$.rejected[0].reason").isEqualTo("api_key_scope_mismatch")
            .jsonPath("$.accepted.length()").isEqualTo(0);
    }

    @Test
    @DisplayName("作用域 key 匹配作用域的事件正常接收")
    void scopeMatchAccepted() {
        AuthService.ApiKeyContext scoped = unscopedKey();
        scoped.gameId = "game_x";
        scoped.environment = "prod";
        when(authService.getContext("pk_scope")).thenReturn(scoped);

        client.post().uri("/v1/batch")
            .contentType(MediaType.APPLICATION_JSON)
            .header("x-api-key", "pk_scope")
            .bodyValue("[" + event("01JSCOPE0002", "game_x", "d1", null) + "]")
            .exchange()
            .expectStatus().is2xxSuccessful()
            .expectBody()
            .jsonPath("$.accepted[0]").isEqualTo("01JSCOPE0002");
    }

    @Test
    @DisplayName("维护态环境拒绝写入：503 environment_unavailable")
    void maintenanceEnvRejected() {
        AuthService.ApiKeyContext maint = unscopedKey();
        maint.envStatus = "maintenance";
        when(authService.getContext("pk_maint")).thenReturn(maint);

        client.post().uri("/v1/batch")
            .contentType(MediaType.APPLICATION_JSON)
            .header("x-api-key", "pk_maint")
            .bodyValue("[" + event("01JMAINT00001", "game_demo", "d1", null) + "]")
            .exchange()
            .expectStatus().isEqualTo(503);
    }

    @Test
    @DisplayName("采样率极小：事件全量 sampled_out，不发布不拒绝")
    void tinySampleRateDropsAll() {
        AuthService.ApiKeyContext tiny = unscopedKey();
        tiny.envEnableSampling = true;
        tiny.envSampleRate = 0.0001;
        when(authService.getContext("pk_sample_tiny")).thenReturn(tiny);

        client.post().uri("/v1/batch")
            .contentType(MediaType.APPLICATION_JSON)
            .header("x-api-key", "pk_sample_tiny")
            .bodyValue("[" + event("01JSAMPLE0001", "game_demo", "d1", null) + "]")
            .exchange()
            .expectStatus().is2xxSuccessful()
            .expectBody()
            .jsonPath("$.sampled_out").isEqualTo(1)
            .jsonPath("$.accepted.length()").isEqualTo(0)
            .jsonPath("$.rejected.length()").isEqualTo(0);
        verify(avroPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("采样率极大：事件保留并发布")
    void keepSampleRatePublishes() {
        AuthService.ApiKeyContext keep = unscopedKey();
        keep.envEnableSampling = true;
        keep.envSampleRate = 0.9999;
        when(authService.getContext("pk_sample_keep")).thenReturn(keep);

        client.post().uri("/v1/batch")
            .contentType(MediaType.APPLICATION_JSON)
            .header("x-api-key", "pk_sample_keep")
            .bodyValue("[" + event("01JSAMPLE0002", "game_demo", "d1", null) + "]")
            .exchange()
            .expectStatus().is2xxSuccessful()
            .expectBody()
            .jsonPath("$.accepted[0]").isEqualTo("01JSAMPLE0002");
        verify(avroPublisher).publish(any());
    }

    @Test
    @DisplayName("设备封禁：blocked 拒绝，未封禁发布")
    void blockedDeviceRejected() {
        when(blockListClient.batchCheck(anyString(), anyList()))
            .thenReturn(Mono.just(Map.of("device_id:d_block", true)));

        client.post().uri("/v1/batch")
            .contentType(MediaType.APPLICATION_JSON)
            .header("x-api-key", "pk_test_example")
            .bodyValue("[" + event("01JBLOCK00001", "game_demo", "d_block", null) + ","
                + event("01JBLOCK00002", "game_demo", "d_free", null) + "]")
            .exchange()
            .expectStatus().is2xxSuccessful()
            .expectBody()
            .jsonPath("$.rejected[0].reason").isEqualTo("blocked")
            .jsonPath("$.accepted[0]").isEqualTo("01JBLOCK00002");
    }

    @Test
    @DisplayName("玩家封禁：user_id 命中 blocked")
    void blockedUserRejected() {
        when(blockListClient.batchCheck(anyString(), anyList()))
            .thenReturn(Mono.just(Map.of("player_id:u_block", true)));

        client.post().uri("/v1/batch")
            .contentType(MediaType.APPLICATION_JSON)
            .header("x-api-key", "pk_test_example")
            .bodyValue("[" + event("01JBLOCK00003", "game_demo", "d_ok", "u_block") + "]")
            .exchange()
            .expectStatus().is2xxSuccessful()
            .expectBody()
            .jsonPath("$.rejected[0].reason").isEqualTo("blocked");
    }

    @Test
    @DisplayName("Kafka 发布异常：事件拒绝 kafka_error")
    void kafkaErrorRejected() {
        doThrow(new RuntimeException("broker down")).when(avroPublisher).publish(any());
        client.post().uri("/v1/batch")
            .contentType(MediaType.APPLICATION_JSON)
            .header("x-api-key", "pk_test_example")
            .bodyValue("[" + event("01JKAFKA00001", "game_demo", "d1", null) + "]")
            .exchange()
            .expectStatus().is2xxSuccessful()
            .expectBody()
            .jsonPath("$.rejected[0].reason").isEqualTo("kafka_error");
    }

    @Test
    @DisplayName("数组 null 与非对象元素被跳过，其余正常处理")
    void nullArrayElementsSkipped() {
        client.post().uri("/v1/batch")
            .contentType(MediaType.APPLICATION_JSON)
            .header("x-api-key", "pk_test_example")
            .bodyValue("[null,42," + event("01JNULLSKP0001", "game_demo", "d1", null) + "]")
            .exchange()
            .expectStatus().is2xxSuccessful()
            .expectBody()
            .jsonPath("$.accepted[0]").isEqualTo("01JNULLSKP0001");
    }

    @Test
    @DisplayName("单事件超限：payload_too_large 拒绝")
    void oversizedEventRejected() {
        String bigProp = "x".repeat(70_000);
        client.post().uri("/v1/batch")
            .contentType(MediaType.APPLICATION_JSON)
            .header("x-api-key", "pk_test_example")
            .bodyValue("[{\"event_id\":\"01JBIGEV00001\",\"event_name\":\"level_start\","
                + "\"game_id\":\"game_demo\",\"environment\":\"prod\",\"device_id\":\"d1\","
                + "\"ts_client\":1730000000000,\"props\":{\"blob\":\"" + bigProp + "\"}}]")
            .exchange()
            .expectStatus().is2xxSuccessful()
            .expectBody()
            .jsonPath("$.rejected[0].reason").isEqualTo("payload_too_large");
    }

    @Test
    @DisplayName("非法 JSON：400 bad_request（全局异常处理器）")
    void invalidJsonReturns400() {
        client.post().uri("/v1/batch")
            .contentType(MediaType.APPLICATION_JSON)
            .header("x-api-key", "pk_test_example")
            .bodyValue("not-json-[")
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.code").isEqualTo("bad_request");
    }

    // ===== 兼容性与策略分支 =====

    private long now() { return System.currentTimeMillis(); }

    private String compatEvent(String eventId, String extra) {
        return "{\"event_id\":\"" + eventId + "\",\"event_name\":\"level_start\","
            + "\"device_id\":\"d_c\",\"ts_client\":" + now() + "," + extra + "}";
    }

    @Test
    @DisplayName("事件时间戳超信差：invalid_timestamp 拒绝")
    void staleTimestampRejected() {
        client.post().uri("/v1/batch")
            .contentType(MediaType.APPLICATION_JSON)
            .header("x-api-key", "pk_test_example")
            .bodyValue("[{\"event_id\":\"01JTSTALE0001\",\"event_name\":\"level_start\","
                + "\"game_id\":\"game_demo\",\"environment\":\"prod\",\"device_id\":\"d1\","
                + "\"ts_client\":1000}]")
            .exchange()
            .expectStatus().is2xxSuccessful()
            .expectBody()
            .jsonPath("$.rejected[0].reason").isEqualTo("invalid_timestamp");
    }

    @Test
    @DisplayName("ndjson：好行接收、坏行与空行跳过")
    void ndjsonLinesParsed() {
        String line1 = compatEvent("01JNDJSON0001", "\"game_id\":\"game_demo\",\"environment\":\"prod\"");
        String line2 = compatEvent("01JNDJSON0002", "\"game_id\":\"game_demo\",\"environment\":\"prod\"");
        String body = line1 + "\n\n{broken-json\n" + line2;
        client.post().uri("/v1/batch")
            .contentType(MediaType.parseMediaType("application/x-ndjson"))
            .header("x-api-key", "pk_test_example")
            .bodyValue(body)
            .exchange()
            .expectStatus().is2xxSuccessful()
            .expectBody()
            .jsonPath("$.accepted.length()").isEqualTo(2)
            .jsonPath("$.rejected.length()").isEqualTo(0);
    }

    @Test
    @DisplayName("单对象（非数组）请求：直接按单事件处理")
    void singleObjectBodyAccepted() {
        client.post().uri("/v1/batch")
            .contentType(MediaType.APPLICATION_JSON)
            .header("x-api-key", "pk_test_example")
            .bodyValue(compatEvent("01JSINGLE0001", "\"game_id\":\"game_demo\",\"environment\":\"prod\""))
            .exchange()
            .expectStatus().is2xxSuccessful()
            .expectBody()
            .jsonPath("$.accepted[0]").isEqualTo("01JSINGLE0001");
    }

    @Test
    @DisplayName("v0 兼容路由字段：project_id/environment_id/app_id 映射与规范化")
    void compatRoutingFieldsMapped() {
        String body = "[" + compatEvent("01JCOMPAT0001",
                "\"project_id\":\"game_p\",\"environment_id\":\"env_production\"") + ","
            + compatEvent("01JCOMPAT0002", "\"app_id\":\"mygame__prod\"") + ","
            + compatEvent("01JCOMPAT0003", "\"app_id\":\"game2_development\"") + ","
            + compatEvent("01JCOMPAT0004", "\"app_id\":\"game3_staging\"") + ","
            + compatEvent("01JCOMPAT0005",
                "\"game_id\":\"game_demo\",\"environment\":\"PRODUCTION\"") + ","
            + compatEvent("01JCOMPAT0006",
                "\"game_id\":\"game_demo\",\"environment\":\"tenant__dev\"") + ","
            + compatEvent("01JCOMPAT0007",
                "\"game_id\":\"game_demo\",\"environment\":\"prod\","
                + "\"ts_client\":\"" + java.time.Instant.now().toString() + "\","
                + "\"ts_server\":\"" + java.time.Instant.now().toString() + "\"") + ","
            + compatEvent("01JCOMPAT0008",
                "\"game_id\":\"game_demo\",\"environment\":\"prod\",\"ts_client\":\"" + now() + "\"") + "]";
        client.post().uri("/v1/batch")
            .contentType(MediaType.APPLICATION_JSON)
            .header("x-api-key", "pk_test_example")
            .bodyValue(body)
            .exchange()
            .expectStatus().is2xxSuccessful()
            .expectBody()
            .jsonPath("$.accepted.length()").isEqualTo(8)
            .jsonPath("$.rejected.length()").isEqualTo(0);
    }

    @Test
    @DisplayName("ts_server 无法解析：整个事件被静默跳过（convertValue 失败走 parseEvents catch）")
    void malformedTsServerSilentlySkipped() {
        // 现状行为：ts_server 非数字非 ISO 时 convertValue 抛异常，数组分支 catch 跳过——
        // 不进 accepted/rejected/DLQ。回归锚定该行为，未来改为显式 reject 时此用例需同步更新
        String body = "[" + compatEvent("01JBADTSV00001",
            "\"game_id\":\"game_demo\",\"environment\":\"prod\",\"ts_server\":\"not-a-time\"") + "]";
        client.post().uri("/v1/batch")
            .contentType(MediaType.APPLICATION_JSON)
            .header("x-api-key", "pk_test_example")
            .bodyValue(body)
            .exchange()
            .expectStatus().is2xxSuccessful()
            .expectBody()
            .jsonPath("$.accepted.length()").isEqualTo(0)
            .jsonPath("$.rejected.length()").isEqualTo(0);
    }

    @Test
    @DisplayName("废弃租户路由字段 tenant_id/org_id 被剔除")
    void deprecatedRoutingFieldsRemoved() {
        String body = compatEvent("01JDEPRECAT0001",
            "\"game_id\":\"game_demo\",\"environment\":\"prod\",\"tenant_id\":\"t1\",\"org_id\":\"o1\"");
        client.post().uri("/v1/batch")
            .contentType(MediaType.APPLICATION_JSON)
            .header("x-api-key", "pk_test_example")
            .bodyValue(body)
            .exchange()
            .expectStatus().is2xxSuccessful()
            .expectBody()
            .jsonPath("$.accepted[0]").isEqualTo("01JDEPRECAT0001");
    }

    @Test
    @DisplayName("event_type 缺省：按事件名关键词推断九类")
    void eventTypeInferredFromName() {
        String[] names = {"risk_flag", "fraud_attempt", "experiment_enroll", "ad_click",
            "level_up", "quest_done", "achievement_unlock", "session_start", "error_stack",
            "crash_report", "resource_gold", "currency_gain", "item_buy", "economy_trade",
            "user_login", "register_user", "signup_done", "auth_token", "design_choice",
            "business_deal", "plain_thing"};
        StringBuilder arr = new StringBuilder("[");
        for (int i = 0; i < names.length; i++) {
            String id = String.format("01JINFER%05d", i);
            if (i > 0) arr.append(',');
            arr.append("{\"event_id\":\"").append(id).append("\",\"event_name\":\"").append(names[i])
                .append("\",\"game_id\":\"game_demo\",\"environment\":\"prod\","
                    + "\"device_id\":\"d1\",\"event_type\":\"\",\"ts_client\":").append(now()).append("}");
        }
        arr.append(']');
        client.post().uri("/v1/batch")
            .contentType(MediaType.APPLICATION_JSON)
            .header("x-api-key", "pk_test_example")
            .bodyValue(arr.toString())
            .exchange()
            .expectStatus().is2xxSuccessful()
            .expectBody()
            .jsonPath("$.accepted.length()").isEqualTo(names.length)
            .jsonPath("$.rejected.length()").isEqualTo(0);
    }

    @Test
    @DisplayName("key 级策略 overrides：deny 拒绝、drop 剔除、mask 脱敏、allow IP 直通")
    void policyOverridesApplied() {
        AuthService.ApiKeyContext ctx = unscopedKey();
        ctx.piiEmail = "drop";
        ctx.piiPhone = "drop";
        ctx.piiIp = "allow";
        ctx.denyKeys = List.of("forbid");
        ctx.maskKeys = List.of("contact");
        when(authService.getContext("pk_policy")).thenReturn(ctx);

        // denyKeys 命中 → pii_blocked
        client.post().uri("/v1/batch")
            .contentType(MediaType.APPLICATION_JSON)
            .header("x-api-key", "pk_policy")
            .header("x-forwarded-for", "1.2.3.4, 5.6.7.8")
            .bodyValue("[" + "{\"event_id\":\"01JPOLICY0001\",\"event_name\":\"level_start\","
                + "\"game_id\":\"game_demo\",\"environment\":\"prod\",\"device_id\":\"d1\","
                + "\"ts_client\":" + now() + ",\"props\":{\"forbid\":\"secret\"}}" + "]")
            .exchange()
            .expectStatus().is2xxSuccessful()
            .expectBody()
            .jsonPath("$.rejected[0].reason").isEqualTo("pii_blocked");

        // email drop / phone drop 剔除、maskKeys 命中脱敏、未知模式回落 MASK
        AuthService.ApiKeyContext ctx2 = unscopedKey();
        ctx2.piiEmail = "bogus-mode"; // 非法模式回落 MASK
        ctx2.maskKeys = List.of("contact");
        when(authService.getContext("pk_policy2")).thenReturn(ctx2);
        client.post().uri("/v1/batch")
            .contentType(MediaType.APPLICATION_JSON)
            .header("x-api-key", "pk_policy2")
            .header("x-forwarded-for", "9.9.9.9")
            .bodyValue("[" + "{\"event_id\":\"01JPOLICY0002\",\"event_name\":\"level_start\","
                + "\"game_id\":\"game_demo\",\"environment\":\"prod\",\"device_id\":\"d1\","
                + "\"ts_client\":" + now() + ",\"props\":{\"mail\":\"a@b.com\",\"contact\":\"c@d.com\"}}" + "]")
            .exchange()
            .expectStatus().is2xxSuccessful()
            .expectBody()
            .jsonPath("$.accepted[0]").isEqualTo("01JPOLICY0002");
        verify(avroPublisher).publish(any());

        // ipMode=drop 的 parseIpMode 分支
        AuthService.ApiKeyContext ctx3 = unscopedKey();
        ctx3.piiIp = "drop";
        when(authService.getContext("pk_policy3")).thenReturn(ctx3);
        client.post().uri("/v1/batch")
            .contentType(MediaType.APPLICATION_JSON)
            .header("x-api-key", "pk_policy3")
            .header("x-forwarded-for", "9.9.9.9")
            .bodyValue("[" + compatEvent("01JPOLICY0003", "\"game_id\":\"game_demo\",\"environment\":\"prod\"") + "]")
            .exchange()
            .expectStatus().is2xxSuccessful()
            .expectBody()
            .jsonPath("$.accepted[0]").isEqualTo("01JPOLICY0003");
    }

    @Test
    @DisplayName("gzip 压缩请求体：解压后正常处理")
    void gzipBodyDecompressed() throws Exception {
        String json = "[" + compatEvent("01JGZIP0000001", "\"game_id\":\"game_demo\",\"environment\":\"prod\"") + "]";
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(bos)) {
            gz.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        client.post().uri("/v1/batch")
            .contentType(MediaType.APPLICATION_JSON)
            .header("content-encoding", "gzip")
            .header("x-api-key", "pk_test_example")
            .bodyValue(bos.toByteArray())
            .exchange()
            .expectStatus().is2xxSuccessful()
            .expectBody()
            .jsonPath("$.accepted[0]").isEqualTo("01JGZIP0000001");
    }

    @Test
    @DisplayName("content-encoding 声明 gzip 但数据非 gzip：解压失败按原文处理")
    void gzipHeaderOnPlainBodyFallsBack() {
        // maybeGunzip 解压异常静默回退原始字节：正常 JSON 照常被解析接收
        String json = "[" + compatEvent("01JGZIPFB00001", "\"game_id\":\"game_demo\",\"environment\":\"prod\"") + "]";
        client.post().uri("/v1/batch")
            .contentType(MediaType.APPLICATION_JSON)
            .header("content-encoding", "gzip")
            .header("x-api-key", "pk_test_example")
            .bodyValue(json)
            .exchange()
            .expectStatus().is2xxSuccessful()
            .expectBody()
            .jsonPath("$.accepted[0]").isEqualTo("01JGZIPFB00001");
    }

    @Test
    @DisplayName("app_id 兼容变体：__production 后缀、无后缀回退、空 app_id 拒绝")
    void appIdSuffixVariants() {
        // 可解析变体：__production 后缀 → prod；无后缀 → gameId 原值 + environment_id 兜底
        String ok = "[" + compatEvent("01JAPPIDV00001", "\"app_id\":\"mygame__production\"") + ","
            + compatEvent("01JAPPIDV00002", "\"app_id\":\"plainapp\",\"environment_id\":\"prod\"") + ","
            + compatEvent("01JAPPIDV00003", "\"game_id\":\"game_demo\",\"environment\":\"development\"") + "]";
        client.post().uri("/v1/batch")
            .contentType(MediaType.APPLICATION_JSON)
            .header("x-api-key", "pk_test_example")
            .bodyValue(ok)
            .exchange()
            .expectStatus().is2xxSuccessful()
            .expectBody()
            .jsonPath("$.accepted.length()").isEqualTo(3)
            .jsonPath("$.rejected.length()").isEqualTo(0);

        // 空 app_id → gameId 仍空 → invalid_schema；空 environment_id → normalizeEnvironment(null) → 同样拒绝
        // 无后缀 app_id 且无 environment 兜底 → environment 为 null → parseEnvironmentFromAppId 无匹配返回 null
        String bad = "[" + compatEvent("01JAPPIDB00001", "\"app_id\":\"\"") + ","
            + compatEvent("01JAPPIDB00002", "\"game_id\":\"game_demo\",\"environment_id\":\" \"") + ","
            + compatEvent("01JAPPIDB00003", "\"app_id\":\"plainapp\"") + "]";
        client.post().uri("/v1/batch")
            .contentType(MediaType.APPLICATION_JSON)
            .header("x-api-key", "pk_test_example")
            .bodyValue(bad)
            .exchange()
            .expectStatus().is2xxSuccessful()
            .expectBody()
            .jsonPath("$.accepted.length()").isEqualTo(0)
            .jsonPath("$.rejected.length()").isEqualTo(3)
            .jsonPath("$.rejected[0].reason").isEqualTo("invalid_schema");
    }

    @Test
    @DisplayName("ts_client 非数字非 ISO：事件被静默跳过（convertValue 失容）")
    void nonParsableTsClientSkipped() {
        String body = "[" + compatEvent("01JTSBOOL00001",
                "\"game_id\":\"game_demo\",\"environment\":\"prod\",\"ts_client\":true") + ","
            + compatEvent("01JTSTEXT00001",
                "\"game_id\":\"game_demo\",\"environment\":\"prod\",\"ts_client\":\"not-a-time\"") + "]";
        client.post().uri("/v1/batch")
            .contentType(MediaType.APPLICATION_JSON)
            .header("x-api-key", "pk_test_example")
            .bodyValue(body)
            .exchange()
            .expectStatus().is2xxSuccessful()
            .expectBody()
            .jsonPath("$.accepted.length()").isEqualTo(0)
            .jsonPath("$.rejected.length()").isEqualTo(0);
    }

    @Test
    @DisplayName("缺 event_id：invalid_schema 拒绝且 DLQ 收到 null 事件 id")
    void missingEventIdRejected() {
        client.post().uri("/v1/batch")
            .contentType(MediaType.APPLICATION_JSON)
            .header("x-api-key", "pk_test_example")
            .bodyValue("[" + "{\"event_name\":\"level_start\",\"game_id\":\"game_demo\","
                + "\"environment\":\"prod\",\"device_id\":\"d1\",\"ts_client\":" + now() + "}" + "]")
            .exchange()
            .expectStatus().is2xxSuccessful()
            .expectBody()
            .jsonPath("$.rejected.length()").isEqualTo(1)
            .jsonPath("$.rejected[0].event_id").isEqualTo("null")
            .jsonPath("$.rejected[0].reason").isEqualTo("invalid_schema");
        verify(dlqPublisher).publish(org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq("invalid_schema"), any());
    }

    @Test
    @DisplayName("无 x-forwarded-for：回退连接远端地址作为 client_ip")
    void clientIpFallsBackToRemoteAddress() {
        // @AutoConfigureWebTestClient 走 mock exchange（remoteAddress 为 null），
        // 用真实端口连接才能让服务器看到非空 RemoteAddress
        WebTestClient realClient = WebTestClient.bindToServer()
            .baseUrl("http://127.0.0.1:" + port)
            .build();
        realClient.post().uri("/v1/batch")
            .contentType(MediaType.APPLICATION_JSON)
            .header("x-api-key", "pk_test_example")
            .bodyValue("[" + compatEvent("01JREMOTE00001", "\"game_id\":\"game_demo\",\"environment\":\"prod\"") + "]")
            .exchange()
            .expectStatus().is2xxSuccessful()
            .expectBody()
            .jsonPath("$.accepted[0]").isEqualTo("01JREMOTE00001");
    }

    @Test
    @DisplayName("key 级策略：email allow 模式直通（parseMode allow 分支）")
    void policyEmailAllowed() {
        AuthService.ApiKeyContext ctx = unscopedKey();
        ctx.piiEmail = "allow";
        ctx.piiIp = "bogus-mode"; // parseIpMode 非法模式回落 COARSE
        when(authService.getContext("pk_policy4")).thenReturn(ctx);
        client.post().uri("/v1/batch")
            .contentType(MediaType.APPLICATION_JSON)
            .header("x-api-key", "pk_policy4")
            .bodyValue("[" + "{\"event_id\":\"01JPOLICY0004\",\"event_name\":\"level_start\","
                + "\"game_id\":\"game_demo\",\"environment\":\"prod\",\"device_id\":\"d1\","
                + "\"ts_client\":" + now() + ",\"props\":{\"mail\":\"a@b.com\"}}" + "]")
            .exchange()
            .expectStatus().is2xxSuccessful()
            .expectBody()
            .jsonPath("$.accepted[0]").isEqualTo("01JPOLICY0004");
        verify(avroPublisher).publish(any());
    }
}
