package io.oddsmaker.gateway.api;

import io.oddsmaker.gateway.kafka.AvroPublisher;
import io.oddsmaker.gateway.kafka.DlqPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class BatchControllerTest {

    @Autowired
    WebTestClient client;

    @MockBean
    AvroPublisher avroPublisher;

    @MockBean
    DlqPublisher dlqPublisher;

    @Test
    void acceptValidNdjson() {
        String body = "{" +
                "\"event_id\":\"01JACCEPT001\",\"event_name\":\"level_start\",\"game_id\":\"game_demo\",\"environment\":\"prod\",\"device_id\":\"d1\",\"ts_client\":1730000000000}";
        client.post().uri("/v1/batch")
                .contentType(MediaType.valueOf("application/x-ndjson"))
                .header("x-api-key", "pk_test_example")
                .bodyValue(body)
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody()
                .jsonPath("$.accepted[0]").isEqualTo("01JACCEPT001");
    }

    @Test
    void rejectInvalidSchema() {
        // missing device_id
        String body = "{" +
                "\"event_id\":\"01JREJECT001\",\"event_name\":\"level_start\",\"game_id\":\"game_demo\",\"environment\":\"prod\",\"ts_client\":1730000000000}";
        client.post().uri("/v1/batch")
                .contentType(MediaType.valueOf("application/x-ndjson"))
                .header("x-api-key", "pk_test_example")
                .bodyValue(body)
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody()
                .jsonPath("$.rejected[0].event_id").isEqualTo("01JREJECT001")
                .jsonPath("$.rejected[0].reason").isEqualTo("invalid_schema");
    }

    @Test
    void rejectPiiBlocked() {
        String body = "{" +
                "\"event_id\":\"01JPII0001\",\"event_name\":\"level_start\",\"game_id\":\"game_demo\",\"environment\":\"prod\",\"device_id\":\"d1\",\"ts_client\":1730000000000,\"props\":{\"password\":\"x\"}}";
        client.post().uri("/v1/batch")
                .contentType(MediaType.valueOf("application/x-ndjson"))
                .header("x-api-key", "pk_test_example")
                .bodyValue(body)
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody()
                .jsonPath("$.rejected[0].event_id").isEqualTo("01JPII0001")
                .jsonPath("$.rejected[0].reason").isEqualTo("pii_blocked");
    }
}
