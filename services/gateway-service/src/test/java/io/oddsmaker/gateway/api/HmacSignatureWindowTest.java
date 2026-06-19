package io.oddsmaker.gateway.api;

import io.oddsmaker.common.auth.HmacSigner;
import io.oddsmaker.gateway.config.AuthService;
import io.oddsmaker.gateway.kafka.AvroPublisher;
import io.oddsmaker.gateway.kafka.DlqPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Instant;

import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class HmacSignatureWindowTest {

    @Autowired
    WebTestClient client;

    @MockBean
    AvroPublisher avroPublisher;

    @MockBean
    DlqPublisher dlqPublisher;

    @MockBean
    AuthService authService;

    @Test
    void validSignatureWithinWindowAccepted() {
        when(authService.getSecret("pk_hmac")).thenReturn("sek");
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
        when(authService.getSecret("pk_hmac")).thenReturn("sek");
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
}
