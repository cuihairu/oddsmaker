package io.oddsmaker.gateway.api;

import io.oddsmaker.common.model.Event;
import io.oddsmaker.gateway.config.PolicyService;
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

import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class PiiIpv6CoarseTest {

    @Autowired
    WebTestClient client;

    @MockBean
    AvroPublisher avroPublisher;

    @MockBean
    DlqPublisher dlqPublisher;

    @MockBean
    PolicyService policyService;

    @Test
    void ipv6CoarseMaskingApplied() {
        PolicyService.Policy p = new PolicyService.Policy();
        p.piiIp = "coarse";
        when(policyService.getPolicy("pk_ipv6")).thenReturn(p);

        String ipv6 = "2001:0db8:85a3:0000:0000:8a2e:0370:7334";
        String body = "{" +
                "\"event_id\":\"01JIPV6001\",\"event_name\":\"level_start\",\"game_id\":\"game_demo\",\"environment\":\"prod\",\"device_id\":\"d1\",\"ts_client\":1730000000000}";

        ArgumentCaptor<Event> cap = ArgumentCaptor.forClass(Event.class);

        client.post().uri("/v1/batch")
                .contentType(MediaType.valueOf("application/x-ndjson"))
                .header("x-api-key", "pk_ipv6")
                .header("x-forwarded-for", ipv6)
                .bodyValue(body)
                .exchange()
                .expectStatus().is2xxSuccessful();

        verify(avroPublisher, atLeastOnce()).publish(cap.capture());
        Event sent = cap.getValue();
        assert sent.clientIp != null;
        assert !sent.clientIp.equals(ipv6);
        assert sent.clientIp.contains(":");
    }
}
