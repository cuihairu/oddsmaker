package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.ApiKeyEntity;
import io.oddsmaker.control.jpa.ApiKeyRepo;
import io.oddsmaker.control.jpa.GameEnvironmentEntity;
import io.oddsmaker.control.jpa.GameEnvironmentRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实时事件检视代理（Live Inspector，竞品差距 P7-1）。
 *
 * 控制台读 Gateway 的 /v1/inspector/recent：用该 (game, environment) 下
 * 最先找到的 ACTIVE server/admin key 作服务间凭据（client key 亦可读，
 * 但 server/admin 更贴合控制台语义）。key 作用域与请求作用域一致，
 * Gateway 侧再按 key 作用域强制过滤，形成双重作用域收敛。
 *
 * Gateway 不可达/未配置时降级 available=false，控制台给出原因而不是 500。
 */
@Service
public class InspectorProxyService {

    private static final Logger logger = LoggerFactory.getLogger(InspectorProxyService.class);

    private final ApiKeyRepo keyRepo;
    private final GameEnvironmentRepo envRepo;
    private final RestTemplate restTemplate;
    private final String gatewayUrl;

    public InspectorProxyService(ApiKeyRepo keyRepo,
                                 GameEnvironmentRepo envRepo,
                                 RestTemplate restTemplate,
                                 @Value("${oddsmaker.gateway.url:http://localhost:8080}") String gatewayUrl) {
        this.keyRepo = keyRepo;
        this.envRepo = envRepo;
        this.restTemplate = restTemplate;
        this.gatewayUrl = gatewayUrl;
    }

    public Map<String, Object> recent(String gameId, String environment, String outcome, Integer limit) {
        String apiKey = pickScopedKey(gameId, environment);
        if (apiKey == null) {
            Map<String, Object> degraded = new LinkedHashMap<>();
            degraded.put("available", false);
            degraded.put("reason", "no_scoped_server_key");
            degraded.put("message", "该游戏环境没有可用的 server/admin API Key，请先在 API Key 管理中创建");
            return degraded;
        }

        StringBuilder uri = new StringBuilder(gatewayUrl)
                .append("/v1/inspector/recent?game_id=").append(gameId)
                .append("&environment=").append(environment);
        if (outcome != null && !outcome.isBlank()) {
            uri.append("&outcome=").append(outcome);
        }
        if (limit != null) {
            uri.append("&limit=").append(limit);
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("x-api-key", apiKey);
            ResponseEntity<Map> response = restTemplate.exchange(
                uri.toString(), HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, Object> body = new LinkedHashMap<>(response.getBody());
                body.put("available", true);
                return body;
            }
            // 非 2xx：网关明确拒绝（如 key 失效），透传状态语义
            Map<String, Object> degraded = new LinkedHashMap<>();
            degraded.put("available", false);
            degraded.put("reason", "gateway_rejected");
            degraded.put("status", response.getStatusCode().value());
            return degraded;
        } catch (Exception e) {
            logger.warn("Inspector proxy error for {} {}: {}", gameId, environment, e.toString());
            Map<String, Object> degraded = new LinkedHashMap<>();
            degraded.put("available", false);
            degraded.put("reason", "gateway_unreachable");
            degraded.put("message", "Gateway 不可达，请确认 oddsmaker.gateway.url 配置与 Gateway 状态");
            return degraded;
        }
    }

    /**
     * 选一个绑定到 (gameId, environment 名) 的 ACTIVE server/admin key。
     * environmentId 是环境内部 ID，需经 env 实体换算为环境名（prod/dev/...）。
     */
    private String pickScopedKey(String gameId, String environment) {
        List<ApiKeyEntity> keys = keyRepo.findByGameIdAndStatus(gameId, ApiKeyEntity.ApiKeyStatus.ACTIVE);
        for (ApiKeyEntity key : keys) {
            if (key.keyType != ApiKeyEntity.ApiKeyType.SERVER && key.keyType != ApiKeyEntity.ApiKeyType.ADMIN) {
                continue;
            }
            GameEnvironmentEntity env = envRepo.findById(key.environmentId)
                .map(e -> (GameEnvironmentEntity) org.hibernate.Hibernate.unproxy(e))
                .orElse(null);
            if (env == null || env.deletedAt != null || !gameId.equals(env.gameId)) {
                continue;
            }
            if (environment.equalsIgnoreCase(env.name)) {
                return key.apiKey;
            }
        }
        return null;
    }
}
