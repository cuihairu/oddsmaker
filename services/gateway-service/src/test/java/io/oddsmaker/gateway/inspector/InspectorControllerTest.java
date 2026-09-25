package io.oddsmaker.gateway.inspector;

import io.oddsmaker.common.model.Event;
import io.oddsmaker.gateway.config.AuthService;
import io.oddsmaker.gateway.kafka.AvroPublisher;
import io.oddsmaker.gateway.kafka.DlqPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.mockito.Mockito.when;

/**
 * /v1/inspector/recent 端点测试：鉴权（401 两侧）、scoped key 作用域强制、
 * 非 scoped key 显式传参（400 侧）、outcome 校验与 limit 钳制。
 * AuthService mock 化以构造 scoped 上下文；EventInspectorBuffer 用真实 bean（默认容量）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@DisplayName("实时事件检视 API")
class InspectorControllerTest {

    @Autowired
    WebTestClient client;

    @MockBean
    AuthService authService;

    // 与其他独立上下文测试类同构：Mock Avro/Dlq 发布器，避免真实 Kafka/Registry 初始化
    @MockBean
    AvroPublisher avroPublisher;

    @MockBean
    DlqPublisher dlqPublisher;

    @Autowired
    EventInspectorBuffer buffer;

    @BeforeEach
    void seed() {
        buffer.clear();
        // scoped key：game_a/prod
        AuthService.ApiKeyContext scoped = new AuthService.ApiKeyContext();
        scoped.apiKey = "pk_scoped";
        scoped.gameId = "game_a";
        scoped.environment = "prod";
        scoped.keyRole = "server";
        scoped.canWrite = true;
        when(authService.getContext("pk_scoped")).thenReturn(scoped);

        // 非 scoped key（等价本地静态 key 上下文形态）
        AuthService.ApiKeyContext unscoped = new AuthService.ApiKeyContext();
        unscoped.apiKey = "pk_unscoped";
        unscoped.canWrite = true;
        when(authService.getContext("pk_unscoped")).thenReturn(unscoped);

        // 无效 key
        when(authService.getContext("pk_unknown")).thenReturn(null);

        buffer.record("game_a", "prod", EventInspectorBuffer.OUTCOME_ACCEPTED, null, null, event("ok1"));
        buffer.record("game_a", "prod", EventInspectorBuffer.OUTCOME_REJECTED, "invalid_schema",
            "props.level: expected number", event("bad1"));
        buffer.record("game_a", "prod", EventInspectorBuffer.OUTCOME_SAMPLED_OUT, null, null, event("s1"));
        buffer.record("game_b", "prod", EventInspectorBuffer.OUTCOME_ACCEPTED, null, null, event("other"));
    }

    private Event event(String id) {
        Event e = new Event();
        e.eventId = id;
        e.eventName = "level_start";
        e.deviceId = "d-" + id;
        return e;
    }

    @Test
    @DisplayName("scoped key：返回自己作用域最近记录，schema 明细可见，其他作用域不可见")
    void scopedKeySeesOwnScopeOnly() {
        client.get().uri("/v1/inspector/recent")
                .header("x-api-key", "pk_scoped")
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody()
                .jsonPath("$.game_id").isEqualTo("game_a")
                .jsonPath("$.environment").isEqualTo("prod")
                .jsonPath("$.count").isEqualTo(3)
                .jsonPath("$.events[0].event_id").isEqualTo("s1")
                .jsonPath("$.events[1].outcome").isEqualTo("rejected")
                .jsonPath("$.events[1].reason").isEqualTo("invalid_schema")
                .jsonPath("$.events[1].detail").isEqualTo("props.level: expected number")
                .jsonPath("$.events[2].event_id").isEqualTo("ok1");
    }

    @Test
    @DisplayName("scoped key：显式传其他 game_id 参数也被强制回 key 作用域（防越权窥视）")
    void scopedKeyForcesOwnScopeOverExplicitParams() {
        client.get().uri(b -> b.path("/v1/inspector/recent")
                        .queryParam("game_id", "game_b")
                        .queryParam("environment", "prod").build())
                .header("x-api-key", "pk_scoped")
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody()
                .jsonPath("$.game_id").isEqualTo("game_a")
                .jsonPath("$.count").isEqualTo(3);
    }

    @Test
    @DisplayName("outcome 过滤与 limit 钳制：limit>200 收敛、limit 小值截断")
    void outcomeFilterAndLimitClamp() {
        client.get().uri(b -> b.path("/v1/inspector/recent")
                        .queryParam("game_id", "game_a")
                        .queryParam("environment", "prod")
                        .queryParam("outcome", "rejected")
                        .queryParam("limit", "999").build())
                .header("x-api-key", "pk_unscoped")
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody()
                .jsonPath("$.count").isEqualTo(1)
                .jsonPath("$.events[0].event_id").isEqualTo("bad1");

        client.get().uri(b -> b.path("/v1/inspector/recent")
                        .queryParam("game_id", "game_a")
                        .queryParam("environment", "prod")
                        .queryParam("limit", "2").build())
                .header("x-api-key", "pk_unscoped")
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody()
                .jsonPath("$.count").isEqualTo(2);
    }

    @Test
    @DisplayName("非 scoped key：缺 game_id/environment 显式传参 → 400 missing_scope")
    void unscopedKeyWithoutParamsRejected() {
        client.get().uri("/v1/inspector/recent")
                .header("x-api-key", "pk_unscoped")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("非法 outcome 参数 → 400 invalid_outcome")
    void invalidOutcomeRejected() {
        client.get().uri(b -> b.path("/v1/inspector/recent")
                        .queryParam("game_id", "game_a")
                        .queryParam("environment", "prod")
                        .queryParam("outcome", "everything").build())
                .header("x-api-key", "pk_unscoped")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("鉴权：缺 key 与无效 key 均 401")
    void authFailures() {
        client.get().uri(b -> b.path("/v1/inspector/recent")
                        .queryParam("game_id", "game_a")
                        .queryParam("environment", "prod").build())
                .exchange()
                .expectStatus().isUnauthorized();

        client.get().uri(b -> b.path("/v1/inspector/recent")
                        .queryParam("game_id", "game_a")
                        .queryParam("environment", "prod").build())
                .header("x-api-key", "pk_unknown")
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
