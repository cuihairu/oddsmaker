package io.oddsmaker.control.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.jpa.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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

    /** 派发事件类型（webhook_configs.event_types 按逗号精确匹配；新接线统一用常量） */
    public static final String EVENT_WEBHOOK_TEST = "webhook_test";
    public static final String EVENT_BLOCK = "block";
    public static final String EVENT_REVIEW_SLA_BREACH = "review_sla_breach";
    public static final String EVENT_REVIEW_ESCALATION = "review_escalation";
    public static final String EVENT_ALERT_ESCALATION = "alert_escalation";
    public static final String EVENT_QUOTA_WARNING = "quota_warning";
    public static final String EVENT_QUOTA_ALERT = "quota_alert";
    public static final String EVENT_MAINTENANCE_UPCOMING = "maintenance_upcoming";
    public static final String EVENT_MAINTENANCE_ENDING = "maintenance_ending";
    public static final String EVENT_EXPORT_COMPLETE = "export_complete";
    public static final String EVENT_MODEL_DRIFT = "model_drift";
    public static final String EVENT_SDK_VERSION_RETIRING = "sdk_version_retiring";

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
    @Qualifier("applicationTaskExecutor")   // Boot 同时注册 applicationTaskExecutor/taskScheduler，歧义需定点
    private Executor asyncExecutor;

    @Autowired
    private AuditLogService auditLogService;

    /** 发送侧 buildHeaders 仅实现这三种鉴权（hmac/oauth2 无实现，校验拒绝防静默不生效） */
    private static final Set<String> SUPPORTED_HTTP_METHODS = Set.of("POST", "PUT", "PATCH");
    private static final Set<String> SUPPORTED_AUTH_TYPES = Set.of("none", "basic", "bearer", "api_key");

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

    // ============== 配置 CRUD ==============

    /**
     * 创建 Webhook 配置：校验与规范化后落库（(gameId, name) 未删唯一，DB 部分唯一索引兜底）。
     * 校验失败抛 IllegalArgumentException（GlobalExceptionHandler 映射 400）。
     */
    public WebhookConfigEntity createWebhookConfig(String gameId, WebhookConfigEntity req, String operator) {
        validateBasics(req);
        String name = req.name.trim();
        if (webhookConfigRepo.findByGameIdAndName(gameId, name).isPresent()) {
            throw new IllegalArgumentException("Webhook config name already exists in game " + gameId + ": " + name);
        }
        req.id = "wc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        req.gameId = gameId;
        req.name = name;
        req.webhookUrl = req.webhookUrl.trim();
        req.eventTypes = normalizeCsv(req.eventTypes);
        req.riskLevels = normalizeCsv(req.riskLevels);
        if (req.httpMethod == null) {
            req.httpMethod = "POST";
        }
        if (req.authType == null) {
            req.authType = "none";
        }
        validateAuthConfig(req.authType, req.authConfig);
        if (req.timeoutSeconds == null) {
            req.timeoutSeconds = 30;
        }
        if (req.maxRetries == null) {
            req.maxRetries = 3;
        }
        if (req.retryBackoffMs == null) {
            req.retryBackoffMs = 1000;
        }
        if (req.status == null) {
            req.status = WebhookConfigEntity.WebhookStatus.ACTIVE;
        }
        req.createdBy = operator;
        req.updatedBy = operator;
        WebhookConfigEntity saved = webhookConfigRepo.save(req);
        auditLogService.log(AuditLogEntity.AuditAction.CREATE, "webhook_config", saved.id, saved.name,
            null, AuditLogEntity.AuditResult.SUCCESS, operator, null, json(saved), null, null, Map.of("gameId", gameId));
        return saved;
    }

    /**
     * 更新 Webhook 配置：部分更新语义（patch 字段 null=不改）；
     * authConfig 为 null/空白 = 保留原值（secret 经 @JsonIgnore 不回显，前端无法回传）；
     * 不存在/已软删/gameId 归属不符返回 null（Controller 映射 404）。
     */
    public WebhookConfigEntity updateWebhookConfig(String gameId, String configId, WebhookConfigEntity patch, String operator) {
        WebhookConfigEntity existing = webhookConfigRepo.findById(configId).orElse(null);
        if (existing == null || existing.deletedAt != null || !existing.gameId.equals(gameId)) {
            return null;
        }
        // validateBasics 会把 null httpMethod 兜底为 POST（副作用改写 patch），
        // 先保存原值以保留 httpMethod 的部分更新语义（未带时沿用 existing，与 authType 一致）
        String patchHttpMethod = patch.httpMethod;
        validateBasics(patch);
        String name = patch.name.trim();
        webhookConfigRepo.findByGameIdAndName(gameId, name)
            .filter(other -> !other.id.equals(configId))
            .ifPresent(other -> {
                throw new IllegalArgumentException("Webhook config name already exists in game " + gameId + ": " + name);
            });
        // authType/authConfig 部分更新语义：patch 未带时沿用 existing，按最终生效组合校验
        String effectiveAuthType = patch.authType != null ? patch.authType : existing.authType;
        String effectiveAuthConfig = (patch.authConfig != null && !patch.authConfig.isBlank())
            ? patch.authConfig : existing.authConfig;
        validateAuthConfig(effectiveAuthType, effectiveAuthConfig);
        String before = json(existing);
        existing.name = name;
        existing.webhookUrl = patch.webhookUrl.trim();
        existing.displayName = patch.displayName;
        existing.description = patch.description;
        existing.eventTypes = normalizeCsv(patch.eventTypes);
        existing.riskLevels = normalizeCsv(patch.riskLevels);
        if (patchHttpMethod != null) {
            existing.httpMethod = patchHttpMethod;
        }
        if (patch.authType != null) {
            existing.authType = patch.authType;
        }
        if (patch.authConfig != null && !patch.authConfig.isBlank()) {
            existing.authConfig = patch.authConfig;
        }
        if (patch.timeoutSeconds != null) {
            existing.timeoutSeconds = patch.timeoutSeconds;
        }
        if (patch.maxRetries != null) {
            existing.maxRetries = patch.maxRetries;
        }
        if (patch.retryBackoffMs != null) {
            existing.retryBackoffMs = patch.retryBackoffMs;
        }
        if (patch.status != null) {
            existing.status = patch.status;
        }
        existing.updatedBy = operator;
        WebhookConfigEntity saved = webhookConfigRepo.save(existing);
        auditLogService.log(AuditLogEntity.AuditAction.UPDATE, "webhook_config", saved.id, saved.name,
            null, AuditLogEntity.AuditResult.SUCCESS, operator, before, json(saved), null, null, Map.of("gameId", gameId));
        return saved;
    }

    /**
     * 软删 Webhook 配置（webhook_logs 对配置是无级联裸 FK，只能软删；日志由既有 30 天清理任务回收）。
     * 同时置 INACTIVE：重试队列只按 isActive 过滤不查 deletedAt，防止在途日志继续轰炸已删端点。
     * 不存在/已删/gameId 归属不符返回 false（Controller 映射 404）。
     */
    public boolean deleteWebhookConfig(String gameId, String configId, String operator) {
        WebhookConfigEntity existing = webhookConfigRepo.findById(configId).orElse(null);
        if (existing == null || existing.deletedAt != null || !existing.gameId.equals(gameId)) {
            return false;
        }
        String before = json(existing);
        existing.deletedAt = LocalDateTime.now();
        existing.status = WebhookConfigEntity.WebhookStatus.INACTIVE;
        existing.updatedBy = operator;
        webhookConfigRepo.save(existing);
        auditLogService.log(AuditLogEntity.AuditAction.DELETE, "webhook_config", existing.id, existing.name,
            null, AuditLogEntity.AuditResult.SUCCESS, operator, before, null, null, null, Map.of("gameId", gameId));
        return true;
    }

    /**
     * 基础字段校验与规范化（create/update 共用）：name/url 必填、URL 必须 http(s)、
     * httpMethod/authType 白名单（hmac/oauth2 发送侧无实现，拒绝配置防"配置成功但鉴权头静默不发"）、
     * timeout/retries/backoff 钳制范围。就地规范化 name/url trim 与 authType 小写。
     * authConfig 不在此校验——update 部分更新语义下须按"最终生效组合"校验（见 validateAuthConfig）。
     */
    private void validateBasics(WebhookConfigEntity req) {
        if (req.name == null || req.name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (req.name.trim().length() > 100) {
            throw new IllegalArgumentException("name must be at most 100 characters");
        }
        if (req.webhookUrl == null || req.webhookUrl.isBlank()) {
            throw new IllegalArgumentException("webhookUrl is required");
        }
        String url = req.webhookUrl.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new IllegalArgumentException("webhookUrl must start with http:// or https://");
        }
        if (url.length() > 500) {
            throw new IllegalArgumentException("webhookUrl must be at most 500 characters");
        }
        if (req.httpMethod != null && !SUPPORTED_HTTP_METHODS.contains(req.httpMethod)) {
            throw new IllegalArgumentException("httpMethod must be one of POST/PUT/PATCH");
        }
        if (req.authType != null) {
            req.authType = req.authType.trim().toLowerCase();
            if (!SUPPORTED_AUTH_TYPES.contains(req.authType)) {
                throw new IllegalArgumentException("authType must be one of none/basic/bearer/api_key (hmac/oauth2 not supported)");
            }
        }
        if (req.timeoutSeconds != null && (req.timeoutSeconds < 1 || req.timeoutSeconds > 300)) {
            throw new IllegalArgumentException("timeoutSeconds must be between 1 and 300");
        }
        if (req.maxRetries != null && (req.maxRetries < 0 || req.maxRetries > 10)) {
            throw new IllegalArgumentException("maxRetries must be between 0 and 10");
        }
        if (req.retryBackoffMs != null && req.retryBackoffMs < 100) {
            throw new IllegalArgumentException("retryBackoffMs must be at least 100");
        }
    }

    /**
     * authConfig 组合校验：按最终生效的 authType/authConfig 判定，而非仅看入参——
     * update 语义下"authType 改为需鉴权类型 + authConfig 留空（保留 existing 旧值）"应合法，
     * 调用方先算 effective 组合再传入。authType≠none 时 authConfig 必须为非空 JSON object。
     */
    private void validateAuthConfig(String authType, String authConfig) {
        if (authType == null || "none".equals(authType)) {
            return;
        }
        if (authConfig == null || authConfig.isBlank()) {
            throw new IllegalArgumentException("authConfig is required when authType is " + authType);
        }
        try {
            Map<?, ?> authMap = objectMapper.readValue(authConfig, Map.class);
            if (authMap == null || authMap.isEmpty()) {
                throw new IllegalArgumentException("authConfig must be a non-empty JSON object");
            }
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("authConfig must be a valid JSON object");
        }
    }

    /** 逗号分隔规范化：split → trim → 去空 → join；空白入参归 null */
    private String normalizeCsv(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Arrays.stream(value.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.joining(","));
    }

    /** 审计快照：authConfig 带 @JsonIgnore，objectMapper 序列化自动排除，secret 不落审计 */
    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /**
     * 测试Webhook配置：同步直发，绕过事件/风险级过滤（测试即验证 URL 可达与鉴权头），
     * 不进重试队列、不计入配置成功/失败统计；webhook_log 照常落库。
     * 返回携带投递结果（成功/失败都是有效业务结果，由调用方按 status 渲染）。
     */
    public Map<String, Object> sendTestWebhook(String configId, String gameId) {
        WebhookConfigEntity config = webhookConfigRepo.findById(configId)
            .orElseThrow(() -> new IllegalArgumentException("Webhook config not found: " + configId));
        if (config.gameId != null && !config.gameId.equals(gameId)) {
            throw new IllegalArgumentException(
                "Webhook config " + configId + " does not belong to game " + gameId);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event_type", EVENT_WEBHOOK_TEST);
        payload.put("test", true);
        payload.put("config_id", config.id);
        payload.put("config_name", config.name);
        payload.put("game_id", gameId);
        payload.put("message", "Oddsmaker webhook connectivity test");
        payload.put("sent_at", LocalDateTime.now().toString());

        WebhookLogEntity log = createWebhookLog(config, payload, EVENT_WEBHOOK_TEST, null);
        webhookLogRepo.save(log);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configId", config.id);
        result.put("name", config.name);
        result.put("url", config.webhookUrl);
        result.put("logId", log.id);
        result.put("sentAt", log.sentAt.toString());

        try {
            HttpHeaders headers = buildHeaders(config);
            HttpEntity<String> entity = new HttpEntity<>(serializePayload(payload), headers);

            LocalDateTime startTime = LocalDateTime.now();
            ResponseEntity<String> response = restTemplate.exchange(
                config.webhookUrl,
                HttpMethod.valueOf(config.httpMethod),
                entity,
                String.class
            );
            long responseTime = java.time.temporal.ChronoUnit.MILLIS.between(startTime, LocalDateTime.now());

            log.markAsSuccess(response.getStatusCode().value(), response.getBody(), responseTime);
            webhookLogRepo.save(log);

            String responseBody = response.getBody();
            result.put("status", "success");
            result.put("httpStatus", response.getStatusCode().value());
            result.put("responseTimeMs", responseTime);
            result.put("responseBody",
                responseBody != null && responseBody.length() > 500 ? responseBody.substring(0, 500) : responseBody);
            result.put("deliveryStatus", log.deliveryStatus.name());
        } catch (Exception e) {
            log.markAsFailed(e.getMessage(), e.getClass().getSimpleName());
            webhookLogRepo.save(log);

            result.put("status", "failed");
            result.put("error", e.getMessage());
            result.put("errorType", e.getClass().getSimpleName());
            result.put("deliveryStatus", log.deliveryStatus.name());
        }

        return result;
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

            // 检查是否需要重试（shouldRetry 已保证 maxRetries != null && > 0；
            // retryCount 唯一递增点是下方 scheduleRetry，sendWebhook 的 log 由 createWebhookLog
            // 新建且 retryCount 初值 0，0 < maxRetries 恒真，无需再比较）
            if (config.shouldRetry()) {
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
