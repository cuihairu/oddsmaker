package io.oddsmaker.gateway.api;

import io.oddsmaker.gateway.kafka.AvroPublisher;
import io.oddsmaker.gateway.kafka.DlqPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@TestPropertySource(properties = {
        "oddsmaker.request.maxBytes=2048" // for payload too large test
})
class TsClientAndPayloadTest {

    @Autowired
    WebTestClient client;

    @MockBean
    AvroPublisher avroPublisher;

    @MockBean
    DlqPublisher dlqPublisher;

    @Test
    void acceptIsoTsClient() {
        String iso = "2025-10-24T10:00:00Z";
        String body = "{" +
                "\"event_id\":\"01JISO0001\",\"event_name\":\"level_start\",\"game_id\":\"game_demo\",\"environment\":\"prod\",\"device_id\":\"d1\",\"ts_client\":\""+iso+"\"}";
        client.post().uri("/v1/batch")
                .contentType(MediaType.valueOf("application/x-ndjson"))
                .header("x-api-key", "pk_test_example")
                .bodyValue(body)
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody().jsonPath("$.accepted[0]").isEqualTo("01JISO0001");
    }

    @Test
    void payloadTooLarge() {
        // Build large NDJSON > 2KB
        StringBuilder sb = new StringBuilder(4096);
        for (int i=0;i<120;i++) {
            if (i>0) sb.append('\n');
            sb.append('{')
              .append("\"event_id\":\"01JBIG").append(String.format("%04d", i)).append("\",")
              .append("\"event_name\":\"x\",")
              .append("\"game_id\":\"game_demo\",")
              .append("\"environment\":\"prod\",")
              .append("\"device_id\":\"d1\",")
              .append("\"ts_client\":1730000000000,")
              .append("\"props\":{\"p\":\"");
            for (int k=0;k<30;k++) sb.append("0123456789");
            sb.append("\"}}");
        }
        client.post().uri("/v1/batch")
                .contentType(MediaType.valueOf("application/x-ndjson"))
                .header("x-api-key", "pk_test_example")
                .bodyValue(sb.toString())
                .exchange()
                .expectStatus().isEqualTo(413);
    }
}
