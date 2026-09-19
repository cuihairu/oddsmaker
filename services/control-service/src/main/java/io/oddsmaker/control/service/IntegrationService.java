package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.jpa.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 集成服务
 * 管理外部系统集成和调用
 */
@Service
@Transactional
public class IntegrationService {

    private static final Logger logger = LoggerFactory.getLogger(IntegrationService.class);

    @Autowired
    private IntegrationRepo integrationRepo;

    @Autowired
    private IntegrationLogRepo integrationLogRepo;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private ObjectMapper objectMapper;

    /** 通用调用模板（默认 30s 超时——裸 new RestTemplate() 是无限等待，慢 endpoint 会挂死调用方）。 */
    private final RestTemplate restTemplate = defaultRestTemplate();

    private static RestTemplate defaultRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(30));
        factory.setReadTimeout(Duration.ofSeconds(30));
        return new RestTemplate(factory);
    }

    /**
     * 创建集成
     */
    public IntegrationEntity createIntegration(String gameId, String name, String description,
                                               IntegrationEntity.IntegrationType type,
                                               IntegrationEntity.AuthType authType,
                                               String endpointUrl, String apiKey, String apiSecret,
                                               Map<String, Object> config, Integer timeoutSeconds,
                                               String createdBy) {

        validateBasics(endpointUrl, authType, timeoutSeconds);

        IntegrationEntity integration = new IntegrationEntity();
        integration.id = "int_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        integration.gameId = gameId;
        integration.name = name;
        integration.description = description;
        integration.integrationType = type;
        integration.authType = authType != null ? authType : IntegrationEntity.AuthType.NONE;
        integration.endpointUrl = endpointUrl.trim();
        integration.apiKey = apiKey;
        integration.apiSecret = apiSecret;
        integration.timeoutSeconds = timeoutSeconds != null ? timeoutSeconds : 30;
        integration.createdBy = createdBy;
        integration.integrationStatus = IntegrationEntity.IntegrationStatus.INACTIVE;

        try {
            if (config != null) {
                integration.config = objectMapper.writeValueAsString(config);
            }
        } catch (Exception e) {
            logger.error("Failed to serialize integration config", e);
        }

        integration = integrationRepo.save(integration);

        // 记录审计日志
        auditLogService.logIntegrationCreate(integration.id, name, type.name(), createdBy, gameId);

        logger.info("Created integration: {} for game: {}", integration.id, gameId);
        return integration;
    }

    /**
     * 验证集成连接
     */
    public IntegrationEntity verifyIntegration(String integrationId) {
        IntegrationEntity integration = integrationRepo.findById(integrationId)
            .orElseThrow(() -> new IllegalArgumentException("Integration not found: " + integrationId));

        if (integration.deletedAt != null) {
            throw new IllegalArgumentException("Integration is deleted: " + integrationId);
        }

        integration.markAsVerifying();
        integrationRepo.save(integration);

        try {
            // 模拟验证
            IntegrationLogEntity log = executeHealthCheck(integration);

            if (log.isSuccess()) {
                integration.markAsActive();
                logger.info("Integration verified successfully: {}", integrationId);
            } else {
                integration.markAsFailed(log.errorMessage);
                logger.warn("Integration verification failed: {} - {}", integrationId, log.errorMessage);
            }

        } catch (Exception e) {
            integration.markAsFailed(e.getMessage());
            logger.error("Failed to verify integration: {} - {}", integrationId, e.getMessage(), e);
        }

        return integrationRepo.save(integration);
    }

    /**
     * 真可达性探测：GET endpointUrl，收到任何 HTTP 响应（含 4xx/5xx）= 可达 = SUCCESS——
     * webhook 类 endpoint 多为 POST-only，405/404 同样证明 URL 可达，不判死；
     * 连接层异常（UnknownHost/连接拒绝/超时/IO）= FAILED。不校验业务语义/凭证有效性。
     */
    private IntegrationLogEntity executeHealthCheck(IntegrationEntity integration) {
        IntegrationLogEntity log = new IntegrationLogEntity();
        log.id = "ilog_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        log.integrationId = integration.id;
        log.gameId = integration.gameId;
        log.integrationType = integration.integrationType.name();
        log.eventType = "health_check";
        log.httpMethod = "GET";
        log.requestUrl = integration.endpointUrl;
        log.createdAt = LocalDateTime.now();

        long startTime = System.currentTimeMillis();
        try {
            ResponseEntity<String> response = restTemplateFor(integration).exchange(
                integration.endpointUrl, HttpMethod.GET, new HttpEntity<>(null, null), String.class);
            log.responseStatus = response.getStatusCode().value();
            log.markAsSuccess();
        } catch (HttpStatusCodeException e) {
            // 4xx/5xx 说明对端有 HTTP 服务响应——可达性成立，仍判成功但留痕
            log.responseStatus = e.getStatusCode().value();
            log.markAsSuccess();
            log.errorMessage = "reachable, HTTP " + e.getStatusCode().value();
        } catch (ResourceAccessException e) {
            log.markAsFailed(e.getMessage());
        } catch (Exception e) {
            log.markAsFailed(e.getMessage());
        }
        log.durationMs = System.currentTimeMillis() - startTime;
        return integrationLogRepo.save(log);
    }

    /** 按集成超时配置现建模板（工厂级超时，Boot 3.3.3 是 setConnectTimeout/setReadTimeout(Duration) 形态）。包级可见供测试 stub。 */
    RestTemplate restTemplateFor(IntegrationEntity integration) {
        int timeout = (integration.timeoutSeconds != null ? integration.timeoutSeconds : 30) * 1000;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeout));
        factory.setReadTimeout(Duration.ofMillis(timeout));
        return new RestTemplate(factory);
    }

    /**
     * 创建校验：authType 限 {NONE, API_KEY}——BEARER_TOKEN/BASIC_AUTH/OAUTH2/HMAC/MUTUAL_TLS
     * 在 API 层无凭证写入通道（IntegrationRequest/UpdateRequest 不接收 bearer_token/username/password），
     * 配置成功但鉴权头静默不发，故拒绝（DB 直写凭证仍可工作，buildHeaders 分支保留）；
     * endpointUrl 限 http(s)；timeoutSeconds 钳制 1-300（WebhookService 同款）。
     */
    private void validateBasics(String endpointUrl, IntegrationEntity.AuthType authType, Integer timeoutSeconds) {
        if (authType != null && authType != IntegrationEntity.AuthType.NONE
                && authType != IntegrationEntity.AuthType.API_KEY) {
            throw new IllegalArgumentException("authType " + authType
                + " is not supported: the API has no credential channel for it (request would silently go out unauthenticated). Use NONE or API_KEY");
        }
        String url = endpointUrl == null ? null : endpointUrl.trim();
        if (url == null || url.isEmpty()
                || !(url.startsWith("http://") || url.startsWith("https://"))) {
            throw new IllegalArgumentException("endpointUrl must be a non-blank http(s) URL");
        }
        if (timeoutSeconds != null && (timeoutSeconds < 1 || timeoutSeconds > 300)) {
            throw new IllegalArgumentException("timeoutSeconds must be between 1 and 300");
        }
    }

    /**
     * 调用集成
     */
    public IntegrationLogEntity callIntegration(String integrationId, String eventType,
                                                 Map<String, Object> payload, String correlationId) {
        IntegrationEntity integration = integrationRepo.findById(integrationId)
            .orElseThrow(() -> new IllegalArgumentException("Integration not found: " + integrationId));

        if (!integration.isActive()) {
            throw new IllegalStateException("Integration is not active: " + integrationId);
        }

        IntegrationLogEntity log = new IntegrationLogEntity();
        log.id = "ilog_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        log.integrationId = integrationId;
        log.gameId = integration.gameId;
        log.integrationType = integration.integrationType.name();
        log.eventType = eventType;
        log.correlationId = correlationId;
        log.createdAt = LocalDateTime.now();

        long startTime = System.currentTimeMillis();

        try {
            // 构建HTTP请求
            HttpHeaders headers = buildHeaders(integration);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);

            log.httpMethod = "POST";
            log.requestUrl = integration.endpointUrl;

            try {
                log.requestBody = objectMapper.writeValueAsString(payload);
            } catch (Exception e) {
                logger.error("Failed to serialize payload", e);
            }

            // 执行请求
            ResponseEntity<String> response = restTemplate.exchange(
                integration.endpointUrl,
                HttpMethod.POST,
                request,
                String.class
            );

            log.responseStatus = response.getStatusCode().value();
            log.responseBody = response.getBody();
            log.markAsSuccess();

        } catch (Exception e) {
            log.markAsFailed(e.getMessage());
            integration.incrementRetry();

            if (integration.hasExceededMaxRetries()) {
                integration.markAsFailed(e.getMessage());
                integrationRepo.save(integration);
            } else {
                integrationRepo.save(integration);
            }

            logger.error("Failed to call integration: {} - {}", integrationId, e.getMessage(), e);
        }

        log.durationMs = System.currentTimeMillis() - startTime;
        log = integrationLogRepo.save(log);

        // 记录审计日志
        auditLogService.logIntegrationCall(integrationId, eventType, log.isSuccess() ? "SUCCESS" : "FAILED", integration.gameId);

        return log;
    }

    /**
     * 批量调用集成
     */
    public List<IntegrationLogEntity> callIntegrations(String gameId, IntegrationEntity.IntegrationType type,
                                                        String eventType, Map<String, Object> payload,
                                                        String correlationId) {
        List<IntegrationEntity> integrations = integrationRepo.findByGameIdAndType(gameId, type);

        return integrations.stream()
            .filter(IntegrationEntity::isActive)
            .map(i -> callIntegration(i.id, eventType, payload, correlationId))
            .collect(Collectors.toList());
    }

    /**
     * 获取集成详情
     */
    @Transactional(readOnly = true)
    public IntegrationEntity getIntegration(String integrationId) {
        return integrationRepo.findById(integrationId)
            .orElseThrow(() -> new IllegalArgumentException("Integration not found: " + integrationId));
    }

    /**
     * 获取游戏的集成列表
     */
    @Transactional(readOnly = true)
    public List<IntegrationEntity> getIntegrations(String gameId) {
        return integrationRepo.findByGameId(gameId);
    }

    /**
     * 获取集成日志
     */
    @Transactional(readOnly = true)
    public List<IntegrationLogEntity> getIntegrationLogs(String integrationId) {
        return integrationLogRepo.findByIntegrationId(integrationId);
    }

    /**
     * 更新集成
     */
    public IntegrationEntity updateIntegration(String integrationId, String name, String description,
                                              String endpointUrl, String apiKey, String apiSecret,
                                              Map<String, Object> config) {
        IntegrationEntity integration = integrationRepo.findById(integrationId)
            .orElseThrow(() -> new IllegalArgumentException("Integration not found: " + integrationId));

        if (name != null) {
            integration.name = name;
        }
        if (description != null) {
            integration.description = description;
        }
        if (endpointUrl != null) {
            integration.endpointUrl = endpointUrl;
        }
        if (apiKey != null) {
            integration.apiKey = apiKey;
        }
        if (apiSecret != null) {
            integration.apiSecret = apiSecret;
        }
        if (config != null) {
            try {
                integration.config = objectMapper.writeValueAsString(config);
            } catch (Exception e) {
                logger.error("Failed to serialize integration config", e);
            }
        }

        // 重置验证状态
        integration.integrationStatus = IntegrationEntity.IntegrationStatus.INACTIVE;

        return integrationRepo.save(integration);
    }

    /**
     * 启用集成
     */
    public IntegrationEntity enableIntegration(String integrationId) {
        IntegrationEntity integration = integrationRepo.findById(integrationId)
            .orElseThrow(() -> new IllegalArgumentException("Integration not found: " + integrationId));

        integration.enabled = true;
        return integrationRepo.save(integration);
    }

    /**
     * 禁用集成
     */
    public IntegrationEntity disableIntegration(String integrationId) {
        IntegrationEntity integration = integrationRepo.findById(integrationId)
            .orElseThrow(() -> new IllegalArgumentException("Integration not found: " + integrationId));

        integration.enabled = false;
        integration.integrationStatus = IntegrationEntity.IntegrationStatus.DISABLED;

        // 记录审计日志
        auditLogService.logIntegrationDisable(integrationId, integration.name, integration.gameId);

        return integrationRepo.save(integration);
    }

    /**
     * 删除集成
     */
    public void deleteIntegration(String integrationId) {
        IntegrationEntity integration = integrationRepo.findById(integrationId)
            .orElseThrow(() -> new IllegalArgumentException("Integration not found: " + integrationId));

        integration.deletedAt = LocalDateTime.now();
        integration.enabled = false;
        integrationRepo.save(integration);

        // 记录审计日志
        auditLogService.logIntegrationDelete(integrationId, integration.name, integration.gameId);

        logger.info("Deleted integration: {}", integrationId);
    }

    /**
     * 重试失败的集成
     */
    @Scheduled(fixedDelay = 300000)  // 每5分钟执行一次
    public void retryFailedIntegrations() {
        try {
            List<IntegrationEntity> retryable = integrationRepo.findRetryable();

            for (IntegrationEntity integration : retryable) {
                try {
                    verifyIntegration(integration.id);
                } catch (Exception e) {
                    logger.error("Failed to retry integration: {} - {}", integration.id, e.getMessage());
                }
            }

            if (!retryable.isEmpty()) {
                logger.info("Retried {} failed integrations", retryable.size());
            }
        } catch (Exception e) {
            logger.error("Failed to retry failed integrations", e);
        }
    }

    /**
     * 定期清理过期日志
     */
    @Scheduled(cron = "0 0 3 * * ?")  // 每天凌晨3点执行
    public void cleanupExpiredLogs() {
        try {
            int deleted = integrationLogRepo.deleteExpired(LocalDateTime.now());
            if (deleted > 0) {
                logger.info("Cleaned up {} expired integration logs", deleted);
            }
        } catch (Exception e) {
            logger.error("Failed to cleanup expired logs", e);
        }
    }

    /**
     * 获取集成统计
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getIntegrationStats(String gameId) {
        List<IntegrationEntity> integrations = integrationRepo.findByGameId(gameId);

        long total = integrations.size();
        long active = integrations.stream().filter(IntegrationEntity::isActive).count();
        long failed = integrations.stream().filter(IntegrationEntity::hasFailed).count();

        Map<String, Long> byType = integrations.stream()
            .filter(i -> i.integrationType != null)
            .collect(Collectors.groupingBy(i -> i.integrationType.name(), Collectors.counting()));

        return Map.of(
            "total", total,
            "active", active,
            "failed", failed,
            "inactive", total - active - failed,
            "byType", byType
        );
    }

    /**
     * 获取集成调用统计
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getCallStats(String integrationId, LocalDateTime since) {
        long totalCalls = integrationLogRepo.countCallsSince(integrationId, since);
        long successCalls = integrationLogRepo.countSuccessCallsSince(integrationId, since);
        long failedCalls = integrationLogRepo.countFailedCallsSince(integrationId, since);
        Long avgDuration = integrationLogRepo.averageDurationSince(integrationId, since);

        return Map.of(
            "totalCalls", totalCalls,
            "successCalls", successCalls,
            "failedCalls", failedCalls,
            "successRate", totalCalls > 0 ? (double) successCalls / totalCalls : 0.0,
            "averageDurationMs", avgDuration != null ? avgDuration : 0L
        );
    }

    // 私有辅助方法

    private HttpHeaders buildHeaders(IntegrationEntity integration) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        switch (integration.authType) {
            case API_KEY:
                if (integration.apiKey != null) {
                    headers.set("X-API-Key", integration.apiKey);
                }
                break;
            case BEARER_TOKEN:
                if (integration.bearerToken != null) {
                    headers.setBearerAuth(integration.bearerToken);
                }
                break;
            case BASIC_AUTH:
                if (integration.username != null && integration.password != null) {
                    headers.setBasicAuth(integration.username, integration.password);
                }
                break;
            case NONE:
            default:
                // 无需认证
                break;
        }

        return headers;
    }
}
