package io.oddsmaker.gateway.api;

import io.oddsmaker.common.model.Event;
import io.oddsmaker.gateway.kafka.AvroPublisher;
import io.oddsmaker.gateway.kafka.DlqPublisher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class RevenueFieldsTest {

    @Autowired
    WebTestClient client;

    @MockitoBean
    AvroPublisher avroPublisher;

    @MockitoBean
    DlqPublisher dlqPublisher;

    @Test
    void acceptRevenueFields() {
        String body = "{" +
                "\"event_id\":\"01JREV0001\",\"event_name\":\"iap_order\",\"game_id\":\"game_demo\",\"environment\":\"prod\",\"device_id\":\"d1\",\"ts_client\":1730000000000," +
                "\"revenue_amount\":9.99,\"revenue_currency\":\"USD\",\"props\":{\"order_id\":\"ord_1\",\"product_id\":\"gem_pack_s\"}}";

        ArgumentCaptor<Event> cap = ArgumentCaptor.forClass(Event.class);

        client.post().uri("/v1/batch")
                .contentType(MediaType.valueOf("application/x-ndjson"))
                .header("x-api-key", "pk_test_example")
                .bodyValue(body)
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody()
                .jsonPath("$.accepted[0]").isEqualTo("01JREV0001");

        verify(avroPublisher, atLeastOnce()).publish(cap.capture());
        Event sent = cap.getValue();
        assertEquals("game_demo", sent.gameId);
        assertEquals("prod", sent.environment);
        assertNotNull(sent.revenueAmount);
        assertEquals(9.99, sent.revenueAmount);
        assertEquals("USD", sent.revenueCurrency);
        assertNotNull(sent.props);
        assertEquals("ord_1", sent.props.get("order_id"));
    }

    @Test
    void mapsLegacyProjectAndEnvironmentFields() {
        String body = "{" +
                "\"event_id\":\"01JREVLEGACY\",\"event_name\":\"iap_order\",\"project_id\":\"legacy_game\",\"environment_id\":\"production\",\"device_id\":\"d1\",\"ts_client\":1730000000000," +
                "\"revenue_amount\":4.99,\"revenue_currency\":\"USD\",\"props\":{\"order_id\":\"ord_legacy\"}}";

        ArgumentCaptor<Event> cap = ArgumentCaptor.forClass(Event.class);

        client.post().uri("/v1/batch")
                .contentType(MediaType.valueOf("application/x-ndjson"))
                .header("x-api-key", "pk_test_example")
                .bodyValue(body)
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody()
                .jsonPath("$.accepted[0]").isEqualTo("01JREVLEGACY");

        verify(avroPublisher, atLeastOnce()).publish(cap.capture());
        Event sent = cap.getValue();
        assertEquals("legacy_game", sent.gameId);
        assertEquals("prod", sent.environment);
    }

    @Test
    void mapsLegacyAppIdField() {
        String body = "{" +
                "\"event_id\":\"01JREVAPPID\",\"event_name\":\"iap_order\",\"app_id\":\"legacy_game__staging\",\"device_id\":\"d1\",\"ts_client\":1730000000000," +
                "\"revenue_amount\":4.99,\"revenue_currency\":\"USD\",\"props\":{\"order_id\":\"ord_app\"}}";

        ArgumentCaptor<Event> cap = ArgumentCaptor.forClass(Event.class);

        client.post().uri("/v1/batch")
                .contentType(MediaType.valueOf("application/x-ndjson"))
                .header("x-api-key", "pk_test_example")
                .bodyValue(body)
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody()
                .jsonPath("$.accepted[0]").isEqualTo("01JREVAPPID");

        verify(avroPublisher, atLeastOnce()).publish(cap.capture());
        Event sent = cap.getValue();
        assertEquals("legacy_game", sent.gameId);
        assertEquals("staging", sent.environment);
    }
}
