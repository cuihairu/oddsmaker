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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class HmacSignatureWindowTest {

    @Autowired
    WebTestClient client;

    @MockitoBean
    AvroPublisher avroPublisher;

    @MockitoBean
    DlqPublisher dlqPublisher;

    @MockitoBean
    AuthService authService;

    @Test
    void validSignatureWithinWindowAccepted() {
        when(authService.getContext("pk_hmac")).thenReturn(newContext("sek"));
        String body = "{" +
                "\"event_id\":\"01JHMACOK\",\"event_name\":\"level_start\",\"game_id\":\"game_demo\",\"environment\":\"prod\",\"device_id\":\"d1\",\"ts_client\":1730000000000}";
        long t = Instant.now().getEpochSecond();
        String msg = t + "." + body;
        String sig = HmacSigner.hmacSha256Hex("sek", msg);
        client.post().uri("/v1/batch")
                .contentType(MediaType.valueOf("application/x-ndjson"))
                .header("x-api-key", "pk_hmac")
                .header("x-signature", "t="+t+", s="+sig)
                .bodyValue(body)
                .exchange()
                .expectStatus().is2xxSuccessful();
    }

    @Test
    void expiredSignatureRejected() {
        when(authService.getContext("pk_hmac")).thenReturn(newContext("sek"));
        String body = "{" +
                "\"event_id\":\"01JHMACEX\",\"event_name\":\"level_start\",\"game_id\":\"game_demo\",\"environment\":\"prod\",\"device_id\":\"d1\",\"ts_client\":\"1730000000000\"}";
        long t = Instant.now().getEpochSecond() - 400; // > 300s window
        String msg = t + "." + body;
        String sig = HmacSigner.hmacSha256Hex("sek", msg);
        client.post().uri("/v1/batch")
                .contentType(MediaType.valueOf("application/x-ndjson"))
                .header("x-api-key", "pk_hmac")
                .header("x-signature", "t="+t+", s="+sig)
                .bodyValue(body)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void clientKeyWithSignatureRejected() {
        AuthService.ApiKeyContext ctx = newContext(null);
        ctx.keyRole = "client";
        when(authService.getContext("pk_client")).thenReturn(ctx);
        String body = "{" +
                "\"event_id\":\"01JHMACCL\",\"event_name\":\"level_start\",\"game_id\":\"game_demo\",\"environment\":\"prod\",\"device_id\":\"d1\",\"ts_client\":1730000000000}";
        long t = Instant.now().getEpochSecond();
        String sig = HmacSigner.hmacSha256Hex("whatever", t + "." + body);
        client.post().uri("/v1/batch")
                .contentType(MediaType.valueOf("application/x-ndjson"))
                .header("x-api-key", "pk_client")
                .header("x-signature", "t="+t+", s="+sig)
                .bodyValue(body)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.error").isEqualTo("signature_not_supported");
    }

    @Test
    void serverKeyWithoutSignatureRejected() {
        AuthService.ApiKeyContext ctx = newContext("sek");
        ctx.keyRole = "server";
        ctx.requireHmac = true;
        when(authService.getContext("pk_server")).thenReturn(ctx);
        String body = "{" +
                "\"event_id\":\"JHMACSVR01\",\"event_name\":\"level_start\",\"game_id\":\"game_demo\",\"environment\":\"prod\",\"device_id\":\"d1\",\"ts_client\":1730000000000}";
        client.post().uri("/v1/batch")
                .contentType(MediaType.valueOf("application/x-ndjson"))
                .header("x-api-key", "pk_server")
                .bodyValue(body)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.error").isEqualTo("missing_signature");
    }

    private static AuthService.ApiKeyContext newContext(String secret) {
        AuthService.ApiKeyContext ctx = new AuthService.ApiKeyContext();
        ctx.apiKey = "pk_hmac";
        ctx.secret = secret;
        ctx.canWrite = true;
        return ctx;
    }
}
