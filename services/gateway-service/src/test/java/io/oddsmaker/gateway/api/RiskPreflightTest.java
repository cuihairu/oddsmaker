package io.oddsmaker.gateway.api;

import io.oddsmaker.common.auth.HmacSigner;
import io.oddsmaker.gateway.config.AuthService;
import io.oddsmaker.gateway.kafka.AvroPublisher;
import io.oddsmaker.gateway.kafka.DlqPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Instant;

import static org.mockito.Mockito.*;

/**
 * Gateway 风控前置：签名重放拒绝、event_id 幂等吸收、事件时间戳信差检查。
 * 本类使用严格的 ±24h 时间窗（覆盖测试资源中的宽松配置）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "oddsmaker.risk.max-event-ts-drift-ms=86400000")
@AutoConfigureWebTestClient
class RiskPreflightTest {

    @Autowired
    WebTestClient client;

    @MockitoBean
    AvroPublisher avroPublisher;

    @MockitoBean
    DlqPublisher dlqPublisher;

    @MockitoBean
    AuthService authService;

    private static AuthService.ApiKeyContext serverKey(String secret) {
        AuthService.ApiKeyContext ctx = new AuthService.ApiKeyContext();
        ctx.apiKey = "pk_svr";
        ctx.secret = secret;
        ctx.keyRole = "server";
        ctx.canWrite = true;
        ctx.requireHmac = true;
        return ctx;
    }

    private static String event(String eventId, long tsClient) {
        return "{\"event_id\":\"" + eventId + "\",\"event_name\":\"level_start\","
            + "\"game_id\":\"game_demo\",\"environment\":\"prod\","
            + "\"device_id\":\"d1\",\"ts_client\":" + tsClient + "}";
    }

    @Test
    void signatureReplayRejected() {
        when(authService.getContext("pk_svr")).thenReturn(serverKey("sek"));
        String body = event("01JRISKREPL1", Instant.now().toEpochMilli());
        long t = Instant.now().getEpochSecond();
        String sig = HmacSigner.hmacSha256Hex("sek", t + "." + body);

        // 首次：验签通过
        client.post().uri("/v1/batch")
            .contentType(MediaType.valueOf("application/x-ndjson"))
            .header("x-api-key", "pk_svr")
            .header("x-signature", "t=" + t + ", s=" + sig)
            .bodyValue(body)
            .exchange()
            .expectStatus().is2xxSuccessful();

        // 重放同一签名：拒绝
        client.post().uri("/v1/batch")
            .contentType(MediaType.valueOf("application/x-ndjson"))
            .header("x-api-key", "pk_svr")
            .header("x-signature", "t=" + t + ", s=" + sig)
            .bodyValue(body)
            .exchange()
            .expectStatus().isUnauthorized()
            .expectBody().jsonPath("$.error").isEqualTo("replay_detected");
    }

    @Test
    void duplicateEventIdAbsorbedIdempotently() {
        when(authService.getContext("pk_dup")).thenAnswer(inv -> {
            AuthService.ApiKeyContext ctx = new AuthService.ApiKeyContext();
            ctx.apiKey = "pk_dup";
            ctx.canWrite = true;
            return ctx;
        });
        String body = event("01JRISKDUP01", Instant.now().toEpochMilli());

        for (int i = 0; i < 2; i++) {
            client.post().uri("/v1/batch")
                .contentType(MediaType.valueOf("application/x-ndjson"))
                .header("x-api-key", "pk_dup")
                .bodyValue(body)
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody()
                .jsonPath("$.accepted.length()").isEqualTo(1);
        }

        // 第二次响应应标记 duplicates=1，且 Kafka 只发布一次
        client.post().uri("/v1/batch")
            .contentType(MediaType.valueOf("application/x-ndjson"))
            .header("x-api-key", "pk_dup")
            .bodyValue(body)
            .exchange()
            .expectStatus().is2xxSuccessful()
            .expectBody()
            .jsonPath("$.duplicates").isEqualTo(1)
            .jsonPath("$.accepted.length()").isEqualTo(1);

        verify(avroPublisher, times(1)).publish(any());
    }

    @Test
    void staleClientTimestampRejected() {
        when(authService.getContext("pk_ts")).thenAnswer(inv -> {
            AuthService.ApiKeyContext ctx = new AuthService.ApiKeyContext();
            ctx.apiKey = "pk_ts";
            ctx.canWrite = true;
            return ctx;
        });
        // 3 天前的事件时间戳（超过默认 ±24h 窗口）
        long stale = Instant.now().minusSeconds(3 * 86400).toEpochMilli();

        client.post().uri("/v1/batch")
            .contentType(MediaType.valueOf("application/x-ndjson"))
            .header("x-api-key", "pk_ts")
            .bodyValue(event("01JRISKOLDTS1", stale))
            .exchange()
            .expectStatus().is2xxSuccessful()
            .expectBody()
            .jsonPath("$.rejected[0].reason").isEqualTo("invalid_timestamp")
            .jsonPath("$.accepted.length()").isEqualTo(0);
    }

    @Test
    void recentClientTimestampAccepted() {
        when(authService.getContext("pk_ts2")).thenAnswer(inv -> {
            AuthService.ApiKeyContext ctx = new AuthService.ApiKeyContext();
            ctx.apiKey = "pk_ts2";
            ctx.canWrite = true;
            return ctx;
        });
        long recent = Instant.now().minusSeconds(3600).toEpochMilli();

        client.post().uri("/v1/batch")
            .contentType(MediaType.valueOf("application/x-ndjson"))
            .header("x-api-key", "pk_ts2")
            .bodyValue(event("01JRISKTSSOK1", recent))
            .exchange()
            .expectStatus().is2xxSuccessful()
            .expectBody()
            .jsonPath("$.accepted.length()").isEqualTo(1);
    }
}
