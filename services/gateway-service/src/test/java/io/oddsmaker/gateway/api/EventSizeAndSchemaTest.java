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
class EventSizeAndSchemaTest {

    @Autowired
    WebTestClient client;

    @MockBean
    AvroPublisher avroPublisher;

    @MockBean
    DlqPublisher dlqPublisher;

    @Test
    void rejectLongEventNameBySchema() {
        StringBuilder name = new StringBuilder();
        for (int i=0;i<100;i++) name.append('a'); // >64
        String body = "{" +
                "\"event_id\":\"01JSCHEMA\",\"event_name\":\""+name.toString()+"\",\"game_id\":\"game_demo\",\"environment\":\"prod\",\"device_id\":\"d1\",\"ts_client\":1730000000000}";
        client.post().uri("/v1/batch")
                .contentType(MediaType.valueOf("application/x-ndjson"))
                .header("x-api-key", "pk_test_example")
                .bodyValue(body)
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody().jsonPath("$.rejected[0].event_id").isEqualTo("01JSCHEMA")
                .jsonPath("$.rejected[0].reason").isEqualTo("invalid_schema");
    }
}
