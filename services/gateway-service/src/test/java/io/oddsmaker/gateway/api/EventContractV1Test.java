package io.oddsmaker.gateway.api;

import io.oddsmaker.common.model.Event;
import io.oddsmaker.gateway.kafka.AvroPublisher;
import io.oddsmaker.gateway.kafka.DlqPublisher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.atLeastOnce;

/**
 * 事件契约 v1：game_id + environment 为唯一路由字段。
 * tenant_id/org_id 为废弃字段，仅被忽略，不参与映射。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class EventContractV1Test {
    @Autowired
    WebTestClient client;

    @MockBean
    AvroPublisher avroPublisher;

    @MockBean
    DlqPublisher dlqPublisher;

    @Test
    void ignoresDeprecatedTenantAndOrgFieldsWhenGameIdPresent() {
        String body = "{" +
                "\"event_id\":\"01JV1TENANT\",\"event_name\":\"session_start\",\"game_id\":\"game_demo\",\"environment\":\"prod\"," +
                "\"tenant_id\":\"t_100\",\"org_id\":\"org_200\",\"device_id\":\"d1\",\"ts_client\":1730000000000}";

        ArgumentCaptor<Event> cap = ArgumentCaptor.forClass(Event.class);

        client.post().uri("/v1/batch")
                .contentType(MediaType.valueOf("application/x-ndjson"))
                .header("x-api-key", "pk_test_example")
                .bodyValue(body)
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody()
                .jsonPath("$.accepted[0]").isEqualTo("01JV1TENANT");

        verify(avroPublisher, atLeastOnce()).publish(cap.capture());
        Event sent = cap.getValue();
        assertEquals("game_demo", sent.gameId);
        assertEquals("prod", sent.environment);
    }

    @Test
    void tenantIdAloneDoesNotSatisfyGameId() {
        String body = "{" +
                "\"event_id\":\"01JV1NOTENANT\",\"event_name\":\"session_start\",\"tenant_id\":\"t_100\"," +
                "\"device_id\":\"d1\",\"ts_client\":1730000000000}";

        client.post().uri("/v1/batch")
                .contentType(MediaType.valueOf("application/x-ndjson"))
                .header("x-api-key", "pk_test_example")
                .bodyValue(body)
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody()
                .jsonPath("$.rejected[0].reason").isEqualTo("invalid_schema")
                .jsonPath("$.accepted.length()").isEqualTo(0);

        verify(avroPublisher, never()).publish(any(Event.class));
    }

    @Test
    void ignoresDeprecatedFieldsAlongsideLegacyProjectId() {
        String body = "{" +
                "\"event_id\":\"01JV1LEGACY\",\"event_name\":\"session_start\",\"project_id\":\"legacy_game\"," +
                "\"environment_id\":\"production\",\"tenant_id\":\"t_100\",\"device_id\":\"d1\",\"ts_client\":1730000000000}";

        ArgumentCaptor<Event> cap = ArgumentCaptor.forClass(Event.class);

        client.post().uri("/v1/batch")
                .contentType(MediaType.valueOf("application/x-ndjson"))
                .header("x-api-key", "pk_test_example")
                .bodyValue(body)
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody()
                .jsonPath("$.accepted[0]").isEqualTo("01JV1LEGACY");

        verify(avroPublisher, atLeastOnce()).publish(cap.capture());
        Event sent = cap.getValue();
        assertEquals("legacy_game", sent.gameId);
        assertEquals("prod", sent.environment);
    }
}
