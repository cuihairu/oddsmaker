package io.oddsmaker.gateway.api;

import io.oddsmaker.common.model.Event;
import io.oddsmaker.gateway.kafka.AvroPublisher;
import io.oddsmaker.gateway.kafka.DlqPublisher;
import io.oddsmaker.gateway.config.PolicyService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class RateLimitAndPolicyTest {

    @Autowired
    WebTestClient client;

    @MockBean
    AvroPublisher avroPublisher;

    @MockBean
    DlqPublisher dlqPublisher;

    @MockBean
    PolicyService policyService;

    @Test
    void rateLimitedPerApiKey() {
        // per-minute rpm=1, second request should get 429
        PolicyService.Policy p = new PolicyService.Policy();
        p.rpm = 1; p.ipRpm = 9999; // disable ip limit for test
        when(policyService.getPolicy("pk_test_rl")) .thenReturn(p);

        String body = "{" +
                "\"event_id\":\"01JRL0001\",\"event_name\":\"level_start\",\"game_id\":\"game_demo\",\"environment\":\"prod\",\"device_id\":\"d1\",\"ts_client\":1730000000000}";
        client.post().uri("/v1/batch")
                .contentType(MediaType.valueOf("application/x-ndjson"))
                .header("x-api-key", "pk_test_rl")
                .bodyValue(body)
                .exchange()
                .expectStatus().is2xxSuccessful();

        client.post().uri("/v1/batch")
                .contentType(MediaType.valueOf("application/x-ndjson"))
                .header("x-api-key", "pk_test_rl")
                .bodyValue(body)
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().exists("x-request-id")
                .expectBody().jsonPath("$.code").isEqualTo("too_many_requests");
    }

    @Test
    void allowlistAndPiiApplied() {
        // allow only 'level', mask email key; ip coarse expected
        PolicyService.Policy p = new PolicyService.Policy();
        p.propsAllowlist = java.util.List.of("level","email");
        p.piiEmail = "mask"; p.piiPhone = null; p.piiIp = "coarse";
        when(policyService.getPolicy("pk_test_pii")).thenReturn(p);

        String body = "{" +
                "\"event_id\":\"01JPIIACPT\",\"event_name\":\"level_start\",\"game_id\":\"game_demo\",\"environment\":\"prod\",\"device_id\":\"d1\",\"ts_client\":1730000000000,\"props\":{\"level\":1,\"email\":\"u@ex.com\",\"foo\":\"bar\"}}";
        ArgumentCaptor<Event> cap = ArgumentCaptor.forClass(Event.class);

        client.post().uri("/v1/batch")
                .contentType(MediaType.valueOf("application/x-ndjson"))
                .header("x-api-key", "pk_test_pii")
                .header("x-forwarded-for", "1.2.3.4")
                .bodyValue(body)
                .exchange()
                .expectStatus().is2xxSuccessful();

        verify(avroPublisher, atLeastOnce()).publish(cap.capture());
        Event sent = cap.getValue();
        // props only contains allowlisted keys
        assert sent.props != null;
        assert sent.props.containsKey("level");
        assert sent.props.containsKey("email");
        assert !sent.props.containsKey("foo");
        // email should be masked (***@domain)
        Object em = sent.props.get("email");
        assert em instanceof String;
        assert ((String) em).startsWith("***@");
        // ip coarse /24
        assert sent.clientIp != null && sent.clientIp.equals("1.2.3.0");
    }
}
