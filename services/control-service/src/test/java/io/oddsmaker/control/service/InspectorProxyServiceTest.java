package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.ApiKeyEntity;
import io.oddsmaker.control.jpa.ApiKeyRepo;
import io.oddsmaker.control.jpa.GameEnvironmentEntity;
import io.oddsmaker.control.jpa.GameEnvironmentRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 实时事件检视代理测试：key 选择（类型/环境匹配/软删过滤）、URI 组装、
 * 网关 200/非 2xx/不可达三态。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("实时事件检视代理测试")
class InspectorProxyServiceTest {

    @Mock
    private ApiKeyRepo keyRepo;

    @Mock
    private GameEnvironmentRepo envRepo;

    @Mock
    private RestTemplate restTemplate;

    private InspectorProxyService service;

    @BeforeEach
    void setUp() {
        service = new InspectorProxyService(keyRepo, envRepo, restTemplate, "http://gateway:8080");
    }

    private ApiKeyEntity key(String apiKey, String envId, ApiKeyEntity.ApiKeyType type) {
        ApiKeyEntity k = new ApiKeyEntity();
        k.apiKey = apiKey;
        k.secret = "sk-" + apiKey;
        k.gameId = "game_a";
        k.environmentId = envId;
        k.keyType = type;
        k.status = ApiKeyEntity.ApiKeyStatus.ACTIVE;
        return k;
    }

    private GameEnvironmentEntity env(String envId, String name, String gameId) {
        GameEnvironmentEntity e = new GameEnvironmentEntity();
        e.id = envId;
        e.name = name;
        e.gameId = gameId;
        return e;
    }

    @Test
    @DisplayName("happy path：server key 命中环境，网关 200 透传并标记 available=true")
    void proxiesToGatewayWithScopedKey() {
        when(keyRepo.findByGameIdAndStatus("game_a", ApiKeyEntity.ApiKeyStatus.ACTIVE))
                .thenReturn(List.of(key("pk_client", "env1", ApiKeyEntity.ApiKeyType.CLIENT),
                                   key("pk_server", "env2", ApiKeyEntity.ApiKeyType.SERVER)));
        when(envRepo.findById("env2")).thenReturn(java.util.Optional.of(env("env2", "prod", "game_a")));

        Map<String, Object> gatewayBody = Map.of("game_id", "game_a", "environment", "prod",
                "count", 1, "events", List.of(Map.of("event_id", "e1", "outcome", "accepted")));
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(gatewayBody));

        Map<String, Object> resp = service.recent("game_a", "prod", "rejected", 25);

        assertTrue((Boolean) resp.get("available"));
        assertEquals("game_a", resp.get("game_id"));
        assertEquals(1, resp.get("count"));

        // client key 被跳过（env1 名为 dev 不匹配 prod 且类型为 CLIENT），server key 用作凭据
        verify(restTemplate).exchange(
                contains("game_id=game_a&environment=prod&outcome=rejected&limit=25"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));
        org.mockito.Mockito.verify(restTemplate, org.mockito.Mockito.times(1))
                .exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));
    }

    @Test
    @DisplayName("无匹配 key：client 类型/环境名不符/环境软删 均不可用 → available=false no_scoped_server_key")
    void noScopedKeyDegrades() {
        when(keyRepo.findByGameIdAndStatus("game_a", ApiKeyEntity.ApiKeyStatus.ACTIVE))
                .thenReturn(List.of(
                        key("pk_client", "env1", ApiKeyEntity.ApiKeyType.CLIENT),
                        key("pk_wrong_env", "env3", ApiKeyEntity.ApiKeyType.SERVER),
                        key("pk_deleted_env", "env4", ApiKeyEntity.ApiKeyType.ADMIN)));
        when(envRepo.findById("env3")).thenReturn(java.util.Optional.of(env("env3", "dev", "game_a")));
        GameEnvironmentEntity deleted = env("env4", "prod", "game_a");
        deleted.deletedAt = java.time.LocalDateTime.now();
        when(envRepo.findById("env4")).thenReturn(java.util.Optional.of(deleted));

        Map<String, Object> resp = service.recent("game_a", "prod", null, null);

        assertFalse((Boolean) resp.get("available"));
        assertEquals("no_scoped_server_key", resp.get("reason"));
        verify(restTemplate, never()).exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(Map.class));
    }

    @Test
    @DisplayName("网关非 2xx：available=false gateway_rejected 透传状态码")
    void gatewayRejectionDegrades() {
        when(keyRepo.findByGameIdAndStatus("game_a", ApiKeyEntity.ApiKeyStatus.ACTIVE))
                .thenReturn(List.of(key("pk_server", "env2", ApiKeyEntity.ApiKeyType.SERVER)));
        when(envRepo.findById("env2")).thenReturn(java.util.Optional.of(env("env2", "prod", "game_a")));
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.status(401).build());

        Map<String, Object> resp = service.recent("game_a", "prod", null, null);

        assertFalse((Boolean) resp.get("available"));
        assertEquals("gateway_rejected", resp.get("reason"));
        assertEquals(401, resp.get("status"));
    }

    @Test
    @DisplayName("网关不可达：available=false gateway_unreachable")
    void gatewayUnreachableDegrades() {
        when(keyRepo.findByGameIdAndStatus("game_a", ApiKeyEntity.ApiKeyStatus.ACTIVE))
                .thenReturn(List.of(key("pk_server", "env2", ApiKeyEntity.ApiKeyType.SERVER)));
        when(envRepo.findById("env2")).thenReturn(java.util.Optional.of(env("env2", "prod", "game_a")));
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new org.springframework.web.client.ResourceAccessException("connection refused"));

        Map<String, Object> resp = service.recent("game_a", "prod", null, null);

        assertFalse((Boolean) resp.get("available"));
        assertEquals("gateway_unreachable", resp.get("reason"));
    }

    @Test
    @DisplayName("outcome/limit 缺省不追加查询参数")
    void omitsOptionalParamsWhenAbsent() {
        when(keyRepo.findByGameIdAndStatus("game_a", ApiKeyEntity.ApiKeyStatus.ACTIVE))
                .thenReturn(List.of(key("pk_server", "env2", ApiKeyEntity.ApiKeyType.SERVER)));
        when(envRepo.findById("env2")).thenReturn(java.util.Optional.of(env("env2", "prod", "game_a")));
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("count", 0, "events", List.of())));

        service.recent("game_a", "prod", null, null);

        verify(restTemplate).exchange(
                eq("http://gateway:8080/v1/inspector/recent?game_id=game_a&environment=prod"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));
    }
}
