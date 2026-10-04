package io.oddsmaker.gateway.api;

import io.oddsmaker.common.model.Event;
import io.oddsmaker.gateway.config.AuthService;
import io.oddsmaker.gateway.kafka.AvroPublisher;
import io.oddsmaker.gateway.kafka.DlqPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 事件契约 v2 增量（06 计划书 §4.2 / todo B4）：
 * v1 事件不携带 source 仍 200（向后兼容）；source=client 时 trust_level 恒 LOW；
 * 自抬（CLIENT key 声明 server 档、trust_level 高于推导档）整事件拒绝。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class EventContractV2Test {
    @Autowired
    WebTestClient client;

    @MockBean
    AvroPublisher avroPublisher;

    @MockBean
    DlqPublisher dlqPublisher;

    @MockBean
    AuthService authService;

    @BeforeEach
    void stubKeys() {
        when(authService.getContext("pk_v2_client")).thenReturn(keyContext("client"));
        when(authService.getContext("pk_v2_server")).thenReturn(keyContext("server"));
    }

    private static AuthService.ApiKeyContext keyContext(String role) {
        AuthService.ApiKeyContext ctx = new AuthService.ApiKeyContext();
        ctx.canWrite = true;
        ctx.keyRole = role;
        return ctx;
    }

    private String body(String eventId, String extraFields) {
        return "{\"event_id\":\"" + eventId + "\",\"event_name\":\"server.purchase.confirmed\"," +
                "\"game_id\":\"game_demo\",\"environment\":\"prod\",\"device_id\":\"d1\"," +
                "\"ts_client\":1730000000000" + extraFields + "}";
    }

    @Test
    @DisplayName("向后兼容：v1 事件不收 source 仍 200，网关回填 client/LOW/1/gateway")
    void v1EventWithoutSourceStillAccepted() {
        client.post().uri("/v1/batch")
                .contentType(MediaType.valueOf("application/x-ndjson"))
                .header("x-api-key", "pk_v2_client")
                .bodyValue(body("01V2BACKWD1", ""))
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody()
                .jsonPath("$.accepted[0]").isEqualTo("01V2BACKWD1");

        ArgumentCaptor<Event> cap = ArgumentCaptor.forClass(Event.class);
        verify(avroPublisher, atLeastOnce()).publish(cap.capture());
        Event sent = cap.getValue();
        assertEquals("client", sent.source);
        assertEquals("LOW", sent.trustLevel);
        assertEquals(Integer.valueOf(1), sent.eventVersion);
        assertEquals("gateway", sent.eventOrigin);
    }

    @Test
    @DisplayName("自抬拒绝：CLIENT key 声明 source=server → trust_escalation，不发布")
    void clientKeyClaimingServerSourceRejected() {
        client.post().uri("/v1/batch")
                .contentType(MediaType.valueOf("application/x-ndjson"))
                .header("x-api-key", "pk_v2_client")
                .bodyValue(body("01V2ESCSRC01", ",\"source\":\"server\""))
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody()
                .jsonPath("$.rejected[0].reason").isEqualTo("trust_escalation")
                .jsonPath("$.accepted.length()").isEqualTo(0);

        verify(avroPublisher, never()).publish(any(Event.class));
    }

    @Test
    @DisplayName("自抬拒绝：source=client 但声明 trust_level=HIGH → trust_escalation")
    void clientKeyRaisingTrustLevelRejected() {
        client.post().uri("/v1/batch")
                .contentType(MediaType.valueOf("application/x-ndjson"))
                .header("x-api-key", "pk_v2_client")
                .bodyValue(body("01V2ESCTRS01", ",\"source\":\"client\",\"trust_level\":\"HIGH\""))
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody()
                .jsonPath("$.rejected[0].reason").isEqualTo("trust_escalation");

        verify(avroPublisher, never()).publish(any(Event.class));
    }

    @Test
    @DisplayName("恒 LOW：source=client + trust_level=LOW 声明一致，接受且保持 LOW")
    void clientLowClaimAcceptedAndStaysLow() {
        client.post().uri("/v1/batch")
                .contentType(MediaType.valueOf("application/x-ndjson"))
                .header("x-api-key", "pk_v2_client")
                .bodyValue(body("01V2CLILOW01", ",\"source\":\"client\",\"trust_level\":\"LOW\""))
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody()
                .jsonPath("$.accepted[0]").isEqualTo("01V2CLILOW01");

        ArgumentCaptor<Event> cap = ArgumentCaptor.forClass(Event.class);
        verify(avroPublisher, atLeastOnce()).publish(cap.capture());
        assertEquals("LOW", cap.getValue().trustLevel);
    }

    @Test
    @DisplayName("SERVER key：缺省回填 source=server/HIGH；保留 SDK 声明的 event_origin")
    void serverKeyDefaultsToServerHigh() {
        client.post().uri("/v1/batch")
                .contentType(MediaType.valueOf("application/x-ndjson"))
                .header("x-api-key", "pk_v2_server")
                .bodyValue(body("01V2SRVDEF01", ",\"event_version\":1,\"event_origin\":\"server-java/0.1.0\""))
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody()
                .jsonPath("$.accepted[0]").isEqualTo("01V2SRVDEF01");

        ArgumentCaptor<Event> cap = ArgumentCaptor.forClass(Event.class);
        verify(avroPublisher, atLeastOnce()).publish(cap.capture());
        Event sent = cap.getValue();
        assertEquals("server", sent.source);
        assertEquals("HIGH", sent.trustLevel);
        assertEquals("server-java/0.1.0", sent.eventOrigin);
    }

    @Test
    @DisplayName("保留档拒绝：SERVER key 声明 source=system → trust_escalation")
    void serverKeyClaimingReservedSourceRejected() {
        client.post().uri("/v1/batch")
                .contentType(MediaType.valueOf("application/x-ndjson"))
                .header("x-api-key", "pk_v2_server")
                .bodyValue(body("01V2SRVSYS01", ",\"source\":\"system\""))
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody()
                .jsonPath("$.rejected[0].reason").isEqualTo("trust_escalation");

        verify(avroPublisher, never()).publish(any(Event.class));
    }

    @Test
    @DisplayName("schema 前置：event_version=0 违反 minimum → invalid_schema（先于信任校验）")
    void eventVersionBelowMinimumRejectedAsInvalidSchema() {
        client.post().uri("/v1/batch")
                .contentType(MediaType.valueOf("application/x-ndjson"))
                .header("x-api-key", "pk_v2_client")
                .bodyValue(body("01V2VER0001", ",\"event_version\":0"))
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody()
                .jsonPath("$.rejected[0].reason").isEqualTo("invalid_schema");

        verify(avroPublisher, never()).publish(any(Event.class));
    }
}
