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

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class PropsClampTest {

    @Autowired
    WebTestClient client;

    @MockBean
    AvroPublisher avroPublisher;

    @MockBean
    DlqPublisher dlqPublisher;

    @MockBean
    PolicyService policyService;

    @Test
    void clampDepthAndArray() {
        PolicyService.Policy p = new PolicyService.Policy();
        p.propsAllowlist = java.util.List.of("bag", "arr");
        when(policyService.getPolicy("pk_clamp")).thenReturn(p);

        String deep = "{\"a\":{\"b\":{\"c\":{\"d\":1}}}}"; // depth 4 -> d should be dropped
        StringBuilder arr = new StringBuilder("[");
        for (int i=0;i<100;i++){ if(i>0)arr.append(','); arr.append(i); } arr.append(']');
        String body = "{" +
                "\"event_id\":\"01JCLAMP\",\"event_name\":\"lvl\",\"game_id\":\"game_demo\",\"environment\":\"prod\",\"device_id\":\"d1\",\"ts_client\":1730000000000,\"props\":{\"bag\":"+deep+",\"arr\":"+arr+"}}";

        ArgumentCaptor<Event> cap = ArgumentCaptor.forClass(Event.class);
        client.post().uri("/v1/batch")
                .contentType(MediaType.valueOf("application/x-ndjson"))
                .header("x-api-key", "pk_clamp")
                .bodyValue(body)
                .exchange()
                .expectStatus().is2xxSuccessful();

        verify(avroPublisher, atLeastOnce()).publish(cap.capture());
        Event e = cap.getValue();
        Map props = e.props;
        assert props.containsKey("bag");
        Object bag = props.get("bag");
        assert bag instanceof Map;
        // bag.a.b.c exists but its child d should be dropped at depth limit -> c is an empty map or without d
        Map c = (Map)((Map)((Map)bag).get("a")).get("b");
        // c may be {} or {"c":{}} depending on clamping; check not containing 'd'
        // Navigate to 'c'
        Object cnode = c.get("c");
        if (cnode instanceof Map) {
            assert !((Map)cnode).containsKey("d");
        }
        List arrOut = (List) props.get("arr");
        assert arrOut.size() <= 50;
    }
}
