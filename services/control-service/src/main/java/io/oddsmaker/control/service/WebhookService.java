package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.jpa.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * Webhook服务
 * 管理风险告警和通知的Webhook发送
 */
@Service
@Transactional
public class WebhookService {

    private static final Logger logger = LoggerFactory.getLogger(WebhookService.class);

    @Autowired
    private WebhookConfigRepo webhookConfigRepo;

    @Autowired
    private WebhookLogRepo webhookLogRepo;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired(required = false)
    private Executor asyncExecutor;

    /**
     * 发送风险案例Webhook
     */
    public void sendRiskCaseWebhook(String gameId, String environmentId, RiskCaseEntity riskCase) {
        List<WebhookConfigEntity> configs = webhookConfigRepo.findActiveByGameId(gameId);

        for (WebhookConfigEntity config : configs) {
            // 检查环境匹配和事件类型匹配
            if (shouldSendForConfig(config, environmentId, "risk_case", riskCase.riskLevel.name())) {
                sendWebhookAsync(config, buildRiskCasePayload(riskCase), "risk_case", riskCase.id);
            }
        }
    }

    /**
     * 发送自定义Webhook
     */
    public void sendCustomWebhook(String gameId, String eventType, Map<String, Object> payload) {
        List<WebhookConfigEntity> configs = webhookConfigRepo.findActiveByGameId(gameId);

        for (WebhookConfigEntity config : configs) {
            if (shouldSendForConfig(config, null, eventType, null)) {
                sendWebhookAsync(config, payload, eventType, null);
            }
        }
    }

    /**
     * 处理待重试的Webhook
     */
    @Scheduled(fixedDelay = 60000)  // 每分钟执行一次
    public void processPendingRetries() {
        try {
            List<WebhookLogEntity> pendingRetries = webhookLogRepo.findPendingRetries(LocalDateTime.now());

            for (WebhookLogEntity log : pendingRetries) {
                WebhookConfigEntity config = webhookConfigRepo.findById(log.webhookConfigId).orElse(null);
                if (config != null && config.isActive()) {
                    retryWebhook(config, log);
                }
            }

            if (!pendingRetries.isEmpty()) {
                logger.info("Processed {} pending webhook retries", pendingRetries.size());
            }
        } catch (Exception e) {
            logger.error("Failed to process pending webhook retries", e);
        }
    }

    /**
     * 清理过期日志
     */
    @Scheduled(cron = "0 0 2 * * ?")  // 每天凌晨2点执行
    public void cleanupExpiredLogs() {
        try {
            LocalDateTime expireAt = LocalDateTime.now().minusDays(30);  // 保留30天
            int deleted = webhookLogRepo.deleteExpiredLogs(expireAt);
            if (deleted > 0) {
                logger.info("Cleaned up {} expired webhook logs", deleted);
            }
        } catch (Exception e) {
            logger.error("Failed to cleanup expired webhook logs", e);
        }
    }

    /**
     * 获取Webhook配置统计
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getWebhookStats(String gameId) {
        List<WebhookConfigEntity> configs = webhookConfigRepo.findByGameId(gameId);
        List<WebhookLogEntity> logs = webhookLogRepo.findByGameId(gameId);

        long totalSent = logs.stream()
            .filter(l -> l.deliveryStatus == WebhookLogEntity.DeliveryStatus.SUCCESS)
            .count();

        long totalFailed = logs.stream()
            .filter(l -> l.isFailed())
            .count();

        Map<String, Long> byStatus = logs.stream()
            .collect(Collectors.groupingBy(l -> l.deliveryStatus.name(), Collectors.counting()));

        return Map.of(
            "totalConfigs", configs.size(),
            "activeConfigs", configs.stream().filter(WebhookConfigEntity::isActive).count(),
            "totalSent", totalSent,
            "totalFailed", totalFailed,
            "byStatus", byStatus
        );
    }

    /**
     * 获取Webhook配置
     */
    @Transactional(readOnly = true)
    public WebhookConfigEntity getConfig(String configId) {
        return webhookConfigRepo.findById(configId)
            .orElseThrow(() -> new IllegalArgumentException("Webhook config not found: " + configId));
    }

    /**
     * 获取游戏的Webhook配置列表
     */
    @Transactional(readOnly = true)
    public List<WebhookConfigEntity> getGameConfigs(String gameId) {
        return webhookConfigRepo.findByGameId(gameId);
    }

    /**
     * 获取Webhook日志
     */
    @Transactional(readOnly = true)
    public List<WebhookLogEntity> getWebhookLogs(String configId) {
        return webhookLogRepo.findByWebhookConfigId(configId);
    }

    // 私有辅助方法

    private boolean shouldSendForConfig(WebhookConfigEntity config, String environmentId, String eventType, String riskLevel) {
        // 检查环境匹配
        if (environmentId != null && config.environmentId != null &&
            !config.environmentId.equals(environmentId) &&
            !config.environmentId.isEmpty()) {
            return false;
        }

        // 检查事件类型和风险等级
        return config.shouldSendForEvent(eventType, riskLevel);
    }

    private void sendWebhookAsync(WebhookConfigEntity config, Map<String, Object> payload, String eventType, String eventId) {
        if (asyncExecutor != null) {
            CompletableFuture.runAsync(() -> sendWebhook(config, payload, eventType, eventId), asyncExecutor);
        } else {
            sendWebhook(config, payload, eventType, eventId);
        }
    }

    private void sendWebhook(WebhookConfigEntity config, Map<String, Object> payload, String eventType, String eventId) {
        WebhookLogEntity log = createWebhookLog(config, payload, eventType, eventId);
        webhookLogRepo.save(log);

        try {
            // 构建请求
            HttpHeaders headers = buildHeaders(config);
            HttpEntity<String> entity = new HttpEntity<>(serializePayload(payload), headers);

            // 发送请求
            LocalDateTime startTime = LocalDateTime.now();
            ResponseEntity<String> response = restTemplate.exchange(
                config.webhookUrl,
                HttpMethod.valueOf(config.httpMethod),
                entity,
                String.class
            );

            long responseTime = java.time.temporal.ChronoUnit.MILLIS.between(startTime, LocalDateTime.now());

            // 记录成功
            log.markAsSuccess(response.getStatusCode().value(), response.getBody(), responseTime);
            config.recordSuccess();

            logger.info("Webhook sent successfully: {} -> {}", config.name, config.webhookUrl);

        } catch (Exception e) {
            // 记录失败
            log.markAsFailed(e.getMessage(), e.getClass().getSimpleName());

            // 检查是否需要重试
            if (config.shouldRetry() && log.retryCount < config.maxRetries) {
                long delayMs = config.retryBackoffMs * (long) Math.pow(2, log.retryCount);
                LocalDateTime nextRetry = LocalDateTime.now().plus(java.time.Duration.ofMillis(delayMs));
                log.scheduleRetry(nextRetry);
                logger.info("Scheduling webhook retry: {} at {}", config.name, nextRetry);
            }

            config.recordFailure(e.getMessage());
            logger.error("Webhook failed: {} -> {} - {}", config.name, config.webhookUrl, e.getMessage());
        }

        webhookLogRepo.save(log);
        webhookConfigRepo.save(config);
    }

    private void retryWebhook(WebhookConfigEntity config, WebhookLogEntity log) {
        log.deliveryStatus = WebhookLogEntity.DeliveryStatus.SENDING;
        webhookLogRepo.save(log);

        try {
            // 重新发送
            Map<String, Object> payload = deserializePayload(log.requestBody);
            sendWebhook(config, payload, log.eventType, log.eventId);

        } catch (Exception e) {
            log.markAsFailed(e.getMessage(), e.getClass().getSimpleName());
            webhookLogRepo.save(log);
            logger.error("Webhook retry failed: {} - {}", config.name, e.getMessage());
        }
    }

    private WebhookLogEntity createWebhookLog(WebhookConfigEntity config, Map<String, Object> payload, String eventType, String eventId) {
        WebhookLogEntity log = new WebhookLogEntity();
        log.id = "wl_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        log.webhookConfigId = config.id;
        log.gameId = config.gameId;
        log.eventType = eventType;
        log.eventId = eventId;
        log.requestUrl = config.webhookUrl;
        log.requestMethod = config.httpMethod;
        log.requestBody = serializePayload(payload);
        log.deliveryStatus = WebhookLogEntity.DeliveryStatus.SENDING;
        log.sentAt = LocalDateTime.now();
        return log;
    }

    private HttpHeaders buildHeaders(WebhookConfigEntity config) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // 添加自定义请求头
        if (config.requestHeaders != null) {
            try {
                Map<String, String> customHeaders = objectMapper.readValue(config.requestHeaders, Map.class);
                customHeaders.forEach(headers::add);
            } catch (Exception e) {
                logger.warn("Failed to parse custom headers", e);
            }
        }

        // 添加鉴权头
        if (config.authType != null) {
            switch (config.authType.toLowerCase()) {
                case "basic":
                    // 添加Basic Auth
                    break;
                case "bearer":
                    if (config.authConfig != null) {
                        try {
                            Map<String, String> auth = objectMapper.readValue(config.authConfig, Map.class);
                            String token = auth.get("token");
                            if (token != null) {
                                headers.setBearerAuth(token);
                            }
                        } catch (Exception e) {
                            logger.warn("Failed to parse bearer auth config", e);
                        }
                    }
                    break;
                case "api_key":
                    if (config.authConfig != null) {
                        try {
                            Map<String, String> auth = objectMapper.readValue(config.authConfig, Map.class);
                            String key = auth.get("key");
                            String value = auth.get("value");
                            if (key != null && value != null) {
                                headers.set(key, value);
                            }
                        } catch (Exception e) {
                            logger.warn("Failed to parse API key config", e);
                        }
                    }
                    break;
            }
        }

        return headers;
    }

    private String serializePayload(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            logger.error("Failed to serialize webhook payload", e);
            return "{}";
        }
    }

    private Map<String, Object> deserializePayload(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            logger.error("Failed to deserialize webhook payload", e);
            return Map.of();
        }
    }

    private Map<String, Object> buildRiskCasePayload(RiskCaseEntity riskCase) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("event_type", "risk_case");
        payload.put("case_id", riskCase.id);
        payload.put("case_number", riskCase.caseNumber);
        payload.put("game_id", riskCase.gameId);
        payload.put("environment_id", riskCase.environmentId);

        payload.put("target", Map.of(
            "type", riskCase.targetType,
            "id", riskCase.targetId,
            "name", riskCase.targetName
        ));

        payload.put("risk", Map.of(
            "level", riskCase.riskLevel.name(),
            "score", riskCase.riskScore
        ));

        payload.put("action", Map.of(
            "type", riskCase.actionTaken.name(),
            "description", riskCase.actionDescription
        ));

        payload.put("trigger", Map.of(
            "event_id", riskCase.triggerEventId,
            "event_type", riskCase.triggerEventType,
            "event_name", riskCase.triggerEventName
        ));

        payload.put("timestamp", riskCase.createdAt.toString());

        return payload;
    }
}
