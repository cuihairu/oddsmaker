package io.oddsmaker.gateway.api;

import io.oddsmaker.gateway.kafka.AvroPublisher;
import io.oddsmaker.gateway.kafka.DlqPublisher;
import io.oddsmaker.gateway.metrics.DataQualityCounters;
import io.oddsmaker.gateway.metrics.DataQualityCounters.Counter;
import io.oddsmaker.gateway.metrics.DataQualityCounters.WindowSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * B10 埋点端到端：POST /v1/batch 真路径 → DataQualityCounters 计数断言。
 * 作用域走事件自带字段（pk_test_example 为非 scoped 静态 key）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class BatchControllerCountersTest {

    @Autowired
    WebTestClient client;

    @Autowired
    DataQualityCounters counters;

    @MockBean
    AvroPublisher avroPublisher;

    @MockBean
    DlqPublisher dlqPublisher;

    @BeforeEach
    void reset() {
        counters.clear();
    }

    private WindowSnapshot snap() {
        return counters.snapshotAndEvict().stream()
            .filter(s -> "game_demo".equals(s.gameId) && "prod".equals(s.environment))
            .findFirst().orElse(null);
    }

    @Test
    @DisplayName("valid+invalid 混批：received/accepted/rejectedSchema 计数与响应语义一致")
    void mixedBatchCounted() {
        String body = "{\"event_id\":\"01JDQVALID01\",\"event_name\":\"level_start\",\"game_id\":\"game_demo\",\"environment\":\"prod\",\"device_id\":\"d1\",\"ts_client\":1730000000000}\n"
            + "{\"event_id\":\"01JDQBAD0001\",\"event_name\":\"level_start\",\"game_id\":\"game_demo\",\"environment\":\"prod\",\"ts_client\":1730000000000}";
        client.post().uri("/v1/batch")
            .contentType(MediaType.valueOf("application/x-ndjson"))
            .header("x-api-key", "pk_test_example")
            .bodyValue(body)
            .exchange()
            .expectStatus().is2xxSuccessful();

        WindowSnapshot s = snap();
        assertNotNull(s);
        assertEquals(2, s.count(Counter.RECEIVED));
        assertEquals(1, s.count(Counter.ACCEPTED));
        assertEquals(1, s.count(Counter.REJECTED_SCHEMA));
        assertEquals(0, s.count(Counter.DUPLICATES));
    }

    @Test
    @DisplayName("重复 event_id：DUPLICATES 计数且占 accepted 位（恒等式 received=accepted+Σrejected+sampled）")
    void duplicatesCountedAndOccupyAccepted() {
        String event = "{\"event_id\":\"01JDQDUPE001\",\"event_name\":\"level_start\",\"game_id\":\"game_demo\",\"environment\":\"prod\",\"device_id\":\"d1\",\"ts_client\":1730000000000}";
        for (int i = 0; i < 2; i++) {
            client.post().uri("/v1/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .header("x-api-key", "pk_test_example")
                .bodyValue("[" + event + "]")
                .exchange()
                .expectStatus().is2xxSuccessful();
        }

        WindowSnapshot s = snap();
        assertNotNull(s);
        assertEquals(2, s.count(Counter.RECEIVED));
        assertEquals(2, s.count(Counter.ACCEPTED));
        assertEquals(1, s.count(Counter.DUPLICATES));
        // 恒等式在网关边缘即守恒
        assertEquals(0, s.count(Counter.RECEIVED)
            - (s.count(Counter.ACCEPTED)
               + s.count(Counter.REJECTED_SCHEMA) + s.count(Counter.REJECTED_UNKNOWN_EVENT)
               + s.count(Counter.REJECTED_INVALID_TIMESTAMP) + s.count(Counter.REJECTED_PII_BLOCKED)
               + s.count(Counter.REJECTED_PAYLOAD_TOO_LARGE) + s.count(Counter.REJECTED_TRUST_ESCALATION)
               + s.count(Counter.REJECTED_BLOCKED) + s.count(Counter.REJECTED_SCOPE_MISMATCH)
               + s.count(Counter.REJECTED_KAFKA_ERROR)
               + s.count(Counter.SAMPLED_OUT)));
    }

    @Test
    @DisplayName("迟到事件：ts_server 缺失由网关盖章，与 ts_client 差 >5min 计 LATE")
    void lateEventCounted() {
        long staleTsClient = System.currentTimeMillis() - 400_000L;
        String body = "{\"event_id\":\"01JDQLATE001\",\"event_name\":\"level_start\",\"game_id\":\"game_demo\",\"environment\":\"prod\",\"device_id\":\"d1\",\"ts_client\":" + staleTsClient + "}";
        client.post().uri("/v1/batch")
            .contentType(MediaType.APPLICATION_JSON)
            .header("x-api-key", "pk_test_example")
            .bodyValue(body)
            .exchange()
            .expectStatus().is2xxSuccessful();

        WindowSnapshot s = snap();
        assertNotNull(s);
        assertEquals(1, s.count(Counter.ACCEPTED));
        assertEquals(1, s.count(Counter.LATE));
    }
}
