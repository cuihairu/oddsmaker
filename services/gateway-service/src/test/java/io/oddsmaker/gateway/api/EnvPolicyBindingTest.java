package io.oddsmaker.gateway.api;

import io.oddsmaker.gateway.config.AuthService;
import io.oddsmaker.gateway.kafka.AvroPublisher;
import io.oddsmaker.gateway.kafka.DlqPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;

import static org.mockito.Mockito.*;

/**
 * 环境策略绑定：维护中环境拒绝写入、环境级确定性采样行为一致。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class EnvPolicyBindingTest {

    @Autowired
    WebTestClient client;

    @MockitoBean
    AvroPublisher avroPublisher;

    @MockitoBean
    DlqPublisher dlqPublisher;

    @MockitoBean
    AuthService authService;

    private static AuthService.ApiKeyContext ctx(String envStatus, Boolean sampling, Double rate) {
        AuthService.ApiKeyContext ctx = new AuthService.ApiKeyContext();
        ctx.apiKey = "pk_env";
        ctx.canWrite = true;
        ctx.envStatus = envStatus;
        ctx.envEnableSampling = sampling;
        ctx.envSampleRate = rate;
        return ctx;
    }

    private static String event(String eventId, String deviceId) {
        return "{\"event_id\":\"" + eventId + "\",\"event_name\":\"level_start\","
            + "\"game_id\":\"game_demo\",\"environment\":\"prod\","
            + "\"device_id\":\"" + deviceId + "\",\"ts_client\":1730000000000}";
    }

    @Test
    void maintenanceEnvironmentRejected() {
        when(authService.getContext("pk_env")).thenReturn(ctx("maintenance", null, null));
        client.post().uri("/v1/batch")
            .contentType(MediaType.valueOf("application/x-ndjson"))
            .header("x-api-key", "pk_env")
            .bodyValue(event("01JENVMAINT", "d1"))
            .exchange()
            .expectStatus().isEqualTo(503)
            .expectBody().jsonPath("$.message").isEqualTo("environment_unavailable");
    }

    @Test
    void inactiveEnvironmentRejected() {
        when(authService.getContext("pk_env")).thenReturn(ctx("inactive", null, null));
        client.post().uri("/v1/batch")
            .contentType(MediaType.valueOf("application/x-ndjson"))
            .header("x-api-key", "pk_env")
            .bodyValue(event("01JENVINACT", "d1"))
            .exchange()
            .expectStatus().isEqualTo(503);
    }

    @Test
    void samplingKeepsSameDeviceConsistent() {
        when(authService.getContext("pk_env")).thenReturn(ctx("active", true, 0.5));

        // 20 个设备，每个 2 个事件；event_id 形如 evt_dev03_a / evt_dev03_b
        StringBuilder ndjson = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            String device = String.format("dev%02d", i);
            ndjson.append(event("evt_" + device + "_a", device)).append('\n');
            ndjson.append(event("evt_" + device + "_b", device)).append('\n');
        }

        BatchController.BatchResponse resp = client.post().uri("/v1/batch")
            .contentType(MediaType.valueOf("application/x-ndjson"))
            .header("x-api-key", "pk_env")
            .bodyValue(ndjson.toString())
            .exchange()
            .expectStatus().is2xxSuccessful()
            .expectBody(BatchController.BatchResponse.class)
            .returnResult().getResponseBody();

        org.junit.jupiter.api.Assertions.assertNotNull(resp);
        // 总量守恒
        org.junit.jupiter.api.Assertions.assertEquals(40, resp.accepted.size() + resp.sampled_out);
        org.junit.jupiter.api.Assertions.assertTrue(resp.sampled_out > 0, "应存在被采样丢弃的事件");
        org.junit.jupiter.api.Assertions.assertTrue(resp.accepted.size() > 0, "应存在被保留的事件");

        // 同一设备的两个事件采样结果必须一致：成对出现或成对丢弃
        for (int i = 0; i < 20; i++) {
            String device = String.format("dev%02d", i);
            boolean a = resp.accepted.contains("evt_" + device + "_a");
            boolean b = resp.accepted.contains("evt_" + device + "_b");
            org.junit.jupiter.api.Assertions.assertEquals(a, b,
                "device " + device + " 的事件采样结果不一致，会破坏漏斗/留存口径");
        }
    }

    @Test
    void fullSampleRateKeepsEverything() {
        when(authService.getContext("pk_env")).thenReturn(ctx("active", true, 1.0));
        client.post().uri("/v1/batch")
            .contentType(MediaType.valueOf("application/x-ndjson"))
            .header("x-api-key", "pk_env")
            .bodyValue(event("01JENVFULL", "d1") + "\n" + event("01JENVFUL2", "d2"))
            .exchange()
            .expectStatus().is2xxSuccessful()
            .expectBody()
            .jsonPath("$.sampled_out").isEqualTo(0)
            .jsonPath("$.accepted.length()").isEqualTo(2);
    }
}
