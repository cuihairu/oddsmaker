package io.oddsmaker.gateway.api;

import io.oddsmaker.gateway.kafka.AvroPublisher;
import io.oddsmaker.gateway.kafka.DlqPublisher;
import io.oddsmaker.gateway.config.PolicyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class HmacAndIpLimitTest {

    @Autowired
    WebTestClient client;

    @MockBean
    AvroPublisher avroPublisher;

    @MockBean
    DlqPublisher dlqPublisher;

    @MockBean
    PolicyService policyService;

    @Test
    void invalidSignatureUnauthorized() {
        String body = "{" +
                "\"event_id\":\"01JHMAC001\",\"event_name\":\"level_start\",\"game_id\":\"game_demo\",\"environment\":\"prod\",\"device_id\":\"d1\",\"ts_client\":1730000000000}";
        client.post().uri("/v1/batch")
                .contentType(MediaType.valueOf("application/x-ndjson"))
                .header("x-api-key", "pk_test_example")
                .header("x-signature", "t=1730000000, s=deadbeef")
                .bodyValue(body)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void rateLimitedPerIp() {
        PolicyService.Policy p = new PolicyService.Policy();
        p.rpm = 9999; p.ipRpm = 1; // limit per IP
        when(policyService.getPolicy("pk_iprl")).thenReturn(p);

        String body = "{" +
                "\"event_id\":\"01JIPRL001\",\"event_name\":\"level_start\",\"game_id\":\"game_demo\",\"environment\":\"prod\",\"device_id\":\"d1\",\"ts_client\":1730000000000}";
        client.post().uri("/v1/batch")
                .contentType(MediaType.valueOf("application/x-ndjson"))
                .header("x-api-key", "pk_iprl")
                .header("x-forwarded-for", "9.8.7.6")
                .bodyValue(body)
                .exchange()
                .expectStatus().is2xxSuccessful();

        client.post().uri("/v1/batch")
                .contentType(MediaType.valueOf("application/x-ndjson"))
                .header("x-api-key", "pk_iprl")
                .header("x-forwarded-for", "9.8.7.6")
                .bodyValue(body)
                .exchange()
                .expectStatus().isEqualTo(429);
    }

    @Test
    void dynamicDenyKeysBlock() {
        PolicyService.Policy p = new PolicyService.Policy();
        p.propsAllowlist = java.util.List.of("ssn");
        p.denyKeys = java.util.List.of("ssn");
        when(policyService.getPolicy("pk_deny")).thenReturn(p);

        String body = "{" +
                "\"event_id\":\"01JDENY001\",\"event_name\":\"level_start\",\"game_id\":\"game_demo\",\"environment\":\"prod\",\"device_id\":\"d1\",\"ts_client\":1730000000000,\"props\":{\"ssn\":\"123-45-6789\"}}";
        client.post().uri("/v1/batch")
                .contentType(MediaType.valueOf("application/x-ndjson"))
                .header("x-api-key", "pk_deny")
                .bodyValue(body)
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody().jsonPath("$.rejected[0].reason").isEqualTo("pii_blocked");
    }

    @Test
    void dynamicMaskKeys() {
        PolicyService.Policy p = new PolicyService.Policy();
        p.propsAllowlist = java.util.List.of("email");
        p.maskKeys = java.util.List.of("email");
        when(policyService.getPolicy("pk_mask")).thenReturn(p);

        String body = "{" +
                "\"event_id\":\"01JMASK001\",\"event_name\":\"level_start\",\"game_id\":\"game_demo\",\"environment\":\"prod\",\"device_id\":\"d1\",\"ts_client\":1730000000000,\"props\":{\"email\":\"u@ex.com\"}}";
        client.post().uri("/v1/batch")
                .contentType(MediaType.valueOf("application/x-ndjson"))
                .header("x-api-key", "pk_mask")
                .bodyValue(body)
                .exchange()
                .expectStatus().is2xxSuccessful();
        // We don't capture the event here; behavior validated in allowlistAndPiiApplied test for masking.
    }
}
