package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 限流服务
 * 管理API限流和资源配额
 */
@Service
@Transactional
public class RateLimitService {

    private static final Logger logger = LoggerFactory.getLogger(RateLimitService.class);

    @Autowired
    private RateLimitRepo rateLimitRepo;

    @Autowired
    private RateLimitUsageRepo rateLimitUsageRepo;

    @Autowired
    private QuotaRepo quotaRepo;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private WebhookService webhookService;

    // 本地缓存，用于快速检查
    private final Map<String, RateLimitUsageEntity> usageCache = new ConcurrentHashMap<>();

    /**
     * 检查请求是否允许
     */
    public RateLimitCheckResult checkRequest(String gameId, String apiKeyId, String endpoint, String userId) {
        // 查找适用的限流规则
        List<RateLimitEntity> rules = findApplicableRules(gameId, apiKeyId, endpoint, userId);

        for (RateLimitEntity rule : rules) {
            RateLimitCheckResult result = checkRule(rule, gameId, apiKeyId, endpoint, userId);
            if (!result.allowed) {
                // 记录被限制的请求
                logger.warn("Request rate limited: rule={}, game={}, endpoint={}", rule.id, gameId, endpoint);
                return result;
            }
        }

        // 更新使用量
        incrementUsage(rules, gameId, apiKeyId, endpoint, userId);

        return new RateLimitCheckResult(true, null, -1);
    }

    /**
     * 查找适用的限流规则
     */
    private List<RateLimitEntity> findApplicableRules(String gameId, String apiKeyId, String endpoint, String userId) {
        List<RateLimitEntity> rules = new ArrayList<>();

        // 添加全局规则
        rules.addAll(rateLimitRepo.findGlobal());

        // 添加游戏级别规则
        if (gameId != null) {
            rules.addAll(rateLimitRepo.findByGameId(gameId));
        }

        // 添加API密钥级别规则
        if (apiKeyId != null) {
            rules.addAll(rateLimitRepo.findByApiKeyId(apiKeyId));
        }

        // 添加端点级别规则
        if (endpoint != null) {
            rules.addAll(rateLimitRepo.findForEndpoint(endpoint));
        }

        // 添加用户级别规则
        if (userId != null) {
            rules.addAll(rateLimitRepo.findForUser(userId));
        }

        // 按优先级排序
        rules.sort((a, b) -> b.priority.compareTo(a.priority));

        return rules;
    }

    /**
     * 检查单个规则
     */
    private RateLimitCheckResult checkRule(RateLimitEntity rule, String gameId, String apiKeyId,
                                           String endpoint, String userId) {
        if (!rule.isEnabled()) {
            return new RateLimitCheckResult(true, null, -1);
        }

        LocalDateTime now = LocalDateTime.now();
        RateLimitUsageEntity usage = getOrCreateUsage(rule, gameId, apiKeyId, endpoint, userId, now);

        if (usage.requestCount >= rule.limit) {
            long retryAfter = calculateRetryAfter(usage, rule);
            return new RateLimitCheckResult(false, rule.id, retryAfter);
        }

        return new RateLimitCheckResult(true, null, -1);
    }

    /**
     * 获取或创建使用记录
     */
    private RateLimitUsageEntity getOrCreateUsage(RateLimitEntity rule, String gameId, String apiKeyId,
                                                  String endpoint, String userId, LocalDateTime now) {
        String cacheKey = buildCacheKey(rule.id, gameId, apiKeyId, endpoint, userId);

        // 检查缓存
        RateLimitUsageEntity cached = usageCache.get(cacheKey);
        if (cached != null && cached.windowEnd.isAfter(now)) {
            return cached;
        }

        // 从数据库查找
        Optional<RateLimitUsageEntity> existing = rateLimitUsageRepo.findActiveWindow(rule.id, now);
        if (existing.isPresent()) {
            RateLimitUsageEntity usage = existing.get();
            usageCache.put(cacheKey, usage);
            return usage;
        }

        // 创建新的使用窗口
        RateLimitUsageEntity usage = new RateLimitUsageEntity();
        usage.id = "rlu_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        usage.rateLimitId = rule.id;
        usage.gameId = gameId;
        usage.apiKeyId = apiKeyId;
        usage.endpoint = endpoint;
        usage.userId = userId;
        usage.windowStart = calculateWindowStart(rule, now);
        usage.windowEnd = usage.windowStart.plusSeconds(rule.getWindowDurationMs() / 1000);
        usage.requestCount = 0;
        usage.blockedCount = 0;
        usage.createdAt = now;

        usage = rateLimitUsageRepo.save(usage);
        usageCache.put(cacheKey, usage);

        return usage;
    }

    /**
     * 增加使用量
     */
    private void incrementUsage(List<RateLimitEntity> rules, String gameId, String apiKeyId,
                              String endpoint, String userId) {
        LocalDateTime now = LocalDateTime.now();

        for (RateLimitEntity rule : rules) {
            if (!rule.isEnabled()) {
                continue;
            }

            RateLimitUsageEntity usage = getOrCreateUsage(rule, gameId, apiKeyId, endpoint, userId, now);
            usage.requestCount++;
            usage.lastRequestAt = now;
            rateLimitUsageRepo.save(usage);
        }
    }

    /**
     * 计算窗口开始时间
     */
    private LocalDateTime calculateWindowStart(RateLimitEntity rule, LocalDateTime now) {
        return switch (rule.algorithm) {
            case FIXED_WINDOW -> {
                // 固定窗口：对齐到窗口边界
                long duration = rule.getWindowDurationMs() / 1000;
                long epochSecond = now.toEpochSecond(java.time.ZoneOffset.UTC);
                long windowStart = (epochSecond / duration) * duration;
                yield LocalDateTime.ofEpochSecond(windowStart, 0, java.time.ZoneOffset.UTC);
            }
            case SLIDING_WINDOW -> {
                // 滑动窗口：从现在开始
                yield now;
            }
            case TOKEN_BUCKET, LEAKY_BUCKET -> now;
        };
    }

    /**
     * 计算重试时间
     */
    private long calculateRetryAfter(RateLimitUsageEntity usage, RateLimitEntity rule) {
        long remainingMs = java.time.Duration.between(LocalDateTime.now(), usage.windowEnd).toMillis();
        return Math.max(0, remainingMs / 1000);
    }

    /**
     * 构建缓存键
     */
    private String buildCacheKey(String ruleId, String gameId, String apiKeyId, String endpoint, String userId) {
        return String.format("%s:%s:%s:%s:%s", ruleId, gameId, apiKeyId, endpoint, userId);
    }

    /**
     * 创建限流规则
     */
    public RateLimitEntity createRateLimit(String gameId, String apiKeyId, String endpoint, String userId,
                                          RateLimitEntity.Scope scope, Integer limit,
                                          RateLimitEntity.WindowType windowType, Integer windowSize,
                                          RateLimitEntity.Algorithm algorithm, Integer burst,
                                          String description, String createdBy) {

        RateLimitEntity rule = new RateLimitEntity();
        rule.id = "rl_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        rule.gameId = gameId;
        rule.apiKeyId = apiKeyId;
        rule.endpoint = endpoint;
        rule.userId = userId;
        rule.scope = scope;
        rule.limit = limit != null ? limit : 100;
        rule.windowType = windowType != null ? windowType : RateLimitEntity.WindowType.MINUTE;
        rule.windowSize = windowSize != null ? windowSize : 1;
        rule.algorithm = algorithm != null ? algorithm : RateLimitEntity.Algorithm.SLIDING_WINDOW;
        rule.burst = burst != null ? burst : 0;
        rule.description = description;
        rule.createdBy = createdBy;
        rule.enabled = true;

        rule = rateLimitRepo.save(rule);

        // 记录审计日志
        auditLogService.logCreate("rate_limit", rule.id, "Rate limit", createdBy, createdBy, null,
            Map.of("scope", scope, "limit", limit, "windowType", windowType));

        logger.info("Created rate limit rule: {} for scope: {}", rule.id, scope);
        return rule;
    }

    /**
     * 获取限流规则
     */
    @Transactional(readOnly = true)
    public RateLimitEntity getRateLimit(String ruleId) {
        return rateLimitRepo.findById(ruleId)
            .orElseThrow(() -> new IllegalArgumentException("Rate limit not found: " + ruleId));
    }

    /**
     * 获取游戏的限流规则
     */
    @Transactional(readOnly = true)
    public List<RateLimitEntity> getRateLimits(String gameId) {
        return rateLimitRepo.findByGameId(gameId);
    }

    /**
     * 更新限流规则
     */
    public RateLimitEntity updateRateLimit(String ruleId, Integer limit, Integer burst, Boolean enabled) {
        RateLimitEntity rule = getRateLimit(ruleId);

        if (limit != null) {
            rule.limit = limit;
        }
        if (burst != null) {
            rule.burst = burst;
        }
        if (enabled != null) {
            rule.enabled = enabled;
        }

        return rateLimitRepo.save(rule);
    }

    /**
     * 删除限流规则
     */
    public void deleteRateLimit(String ruleId) {
        RateLimitEntity rule = getRateLimit(ruleId);
        rule.deletedAt = LocalDateTime.now();
        rule.enabled = false;
        rateLimitRepo.save(rule);

        logger.info("Deleted rate limit rule: {}", ruleId);
    }

    /**
     * 创建配额
     */
    public QuotaEntity createQuota(String gameId, String environmentId, QuotaEntity.ResourceType resourceType,
                                  Long limit, Double warningThreshold, Double alertThreshold,
                                  Boolean hardLimit, String createdBy) {

        QuotaEntity quota = new QuotaEntity();
        quota.id = "quota_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        quota.gameId = gameId;
        quota.environmentId = environmentId;
        quota.resourceType = resourceType;
        quota.quotaLimit = limit;
        quota.currentUsage = 0L;
        quota.warningThreshold = warningThreshold != null ? warningThreshold : 80.0;
        quota.alertThreshold = alertThreshold != null ? alertThreshold : 95.0;
        quota.hardLimit = hardLimit != null ? hardLimit : false;
        quota.createdBy = createdBy;

        quota = quotaRepo.save(quota);

        // 记录审计日志
        auditLogService.logCreate("quota", quota.id, resourceType.name(), createdBy, createdBy, null,
            Map.of("gameId", gameId, "limit", limit));

        logger.info("Created quota: {} for game: {}, resource: {}", quota.id, gameId, resourceType);
        return quota;
    }

    /**
     * 检查配额
     */
    public QuotaCheckResult checkQuota(String gameId, String environmentId, QuotaEntity.ResourceType resourceType) {
        Optional<QuotaEntity> quotaOpt = environmentId != null
            ? quotaRepo.findByGameEnvironmentAndResourceType(gameId, environmentId, resourceType)
            : quotaRepo.findByGameAndResourceType(gameId, resourceType);

        if (quotaOpt.isEmpty()) {
            return new QuotaCheckResult(true, null, -1, -1);
        }

        QuotaEntity quota = quotaOpt.get();
        boolean allowed = !quota.hardLimit || !quota.isOverLimit();
        long remaining = Math.max(0, quota.quotaLimit - quota.currentUsage);
        double percent = quota.getUsagePercent();

        return new QuotaCheckResult(allowed, quota.id, remaining, percent);
    }

    /**
     * 更新配额使用量
     */
    public void updateQuotaUsage(String gameId, String environmentId, QuotaEntity.ResourceType resourceType, long amount) {
        Optional<QuotaEntity> quotaOpt = environmentId != null
            ? quotaRepo.findByGameEnvironmentAndResourceType(gameId, environmentId, resourceType)
            : quotaRepo.findByGameAndResourceType(gameId, resourceType);

        if (quotaOpt.isPresent()) {
            QuotaEntity quota = quotaOpt.get();
            quota.incrementUsage(amount);
            quota.lastCalculatedAt = LocalDateTime.now();
            quotaRepo.save(quota);

            // 检查是否需要发送警告或告警
            checkAndSendAlerts(quota);
        }
    }

    /**
     * 检查并发送告警
     */
    private void checkAndSendAlerts(QuotaEntity quota) {
        if (quota.shouldSendWarning()) {
            quota.markWarningSent();
            sendQuotaWarning(quota);
        }

        if (quota.shouldSendAlert()) {
            quota.markAlertSent();
            sendQuotaAlert(quota);
        }

        quotaRepo.save(quota);
    }

    /**
     * 发送配额警告
     */
    private void sendQuotaWarning(QuotaEntity quota) {
        logger.warn("Quota warning: game={}, resource={}, usage={}%",
            quota.gameId, quota.resourceType, String.format("%.2f", quota.getUsagePercent()));

        dispatchQuotaWebhook(quota, WebhookService.EVENT_QUOTA_WARNING);
    }

    /**
     * 发送配额告警
     */
    private void sendQuotaAlert(QuotaEntity quota) {
        logger.error("Quota alert: game={}, resource={}, usage={}%",
            quota.gameId, quota.resourceType, String.format("%.2f", quota.getUsagePercent()));

        dispatchQuotaWebhook(quota, WebhookService.EVENT_QUOTA_ALERT);
    }

    /**
     * 派发配额 webhook（warningSent/alertSent 标志由 checkAndSendAlerts 保证一次性）
     */
    private void dispatchQuotaWebhook(QuotaEntity quota, String eventType) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event_type", eventType);
        payload.put("game_id", quota.gameId);
        payload.put("environment_id", quota.environmentId);
        payload.put("resource_type", quota.resourceType != null ? quota.resourceType.name() : null);
        payload.put("quota_limit", quota.quotaLimit);
        payload.put("current_usage", quota.currentUsage);
        payload.put("usage_percent", quota.getUsagePercent());
        payload.put("warning_threshold", quota.warningThreshold);
        payload.put("alert_threshold", quota.alertThreshold);
        payload.put("hard_limit", quota.hardLimit);
        payload.put("occurred_at", LocalDateTime.now().toString());
        try {
            webhookService.sendCustomWebhook(quota.gameId, eventType, payload);
        } catch (Exception e) {
            logger.warn("Quota webhook dispatch failed: {}", e.getMessage());
        }
    }

    /**
     * 获取配额统计
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getQuotaStats(String gameId) {
        List<QuotaEntity> quotas = quotaRepo.findByGameId(gameId);

        long total = quotas.size();
        long overLimit = quotas.stream().filter(QuotaEntity::isOverLimit).count();
        long nearLimit = quotas.stream().filter(q -> q.isNearWarningThreshold()).count();

        Map<String, Long> byStatus = quotas.stream()
            .collect(java.util.stream.Collectors.groupingBy(q ->
                q.isOverLimit() ? "over" : (q.isNearWarningThreshold() ? "near" : "normal"),
                java.util.stream.Collectors.counting()));

        return Map.of(
            "total", total,
            "overLimit", overLimit,
            "nearLimit", nearLimit,
            "normal", total - overLimit - nearLimit,
            "byStatus", byStatus
        );
    }

    /**
     * 定期清理过期窗口
     */
    @Scheduled(fixedDelay = 300000)  // 每5分钟执行一次
    public void cleanupExpiredWindows() {
        try {
            LocalDateTime expireBefore = LocalDateTime.now().minusDays(7);
            int deleted = rateLimitUsageRepo.deleteExpired(expireBefore);

            // 清理缓存
            usageCache.entrySet().removeIf(entry ->
                entry.getValue().windowEnd.isBefore(LocalDateTime.now()));

            if (deleted > 0) {
                logger.debug("Cleaned up {} expired rate limit windows", deleted);
            }
        } catch (Exception e) {
            logger.error("Failed to cleanup expired windows", e);
        }
    }

    /**
     * 定期检查配额重置
     */
    @Scheduled(fixedDelay = 60000)  // 每分钟执行一次
    public void checkQuotaResets() {
        try {
            List<QuotaEntity> resetNeeded = quotaRepo.findResetNeeded(LocalDateTime.now());

            for (QuotaEntity quota : resetNeeded) {
                quota.resetUsage();
                quotaRepo.save(quota);
                logger.info("Reset quota: {} for game: {}", quota.id, quota.gameId);
            }

            if (!resetNeeded.isEmpty()) {
                logger.info("Reset {} quotas", resetNeeded.size());
            }
        } catch (Exception e) {
            logger.error("Failed to check quota resets", e);
        }
    }

    /**
     * 定期检查配额告警
     */
    @Scheduled(fixedDelay = 300000)  // 每5分钟执行一次
    public void checkQuotaAlerts() {
        try {
            List<QuotaEntity> allQuotas = quotaRepo.findAll().stream()
                .filter(q -> q.deletedAt == null)
                .toList();

            for (QuotaEntity quota : allQuotas) {
                checkAndSendAlerts(quota);
            }
        } catch (Exception e) {
            logger.error("Failed to check quota alerts", e);
        }
    }

    /**
     * 获取限流统计
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getRateLimitStats(String gameId) {
        List<RateLimitEntity> rules = rateLimitRepo.findByGameId(gameId);

        long total = rules.size();
        long enabled = rules.stream().filter(RateLimitEntity::isEnabled).count();

        Map<String, Long> byScope = rules.stream()
            .filter(r -> r.scope != null)
            .collect(java.util.stream.Collectors.groupingBy(r -> r.scope.name(), java.util.stream.Collectors.counting()));

        return Map.of(
            "total", total,
            "enabled", enabled,
            "disabled", total - enabled,
            "byScope", byScope
        );
    }

    // 结果类

    public static class RateLimitCheckResult {
        public final boolean allowed;
        public final String ruleId;
        public final long retryAfterSeconds;

        public RateLimitCheckResult(boolean allowed, String ruleId, long retryAfterSeconds) {
            this.allowed = allowed;
            this.ruleId = ruleId;
            this.retryAfterSeconds = retryAfterSeconds;
        }
    }

    public static class QuotaCheckResult {
        public final boolean allowed;
        public final String quotaId;
        public final long remaining;
        public final double usagePercent;

        public QuotaCheckResult(boolean allowed, String quotaId, long remaining, double usagePercent) {
            this.allowed = allowed;
            this.quotaId = quotaId;
            this.remaining = remaining;
            this.usagePercent = usagePercent;
        }
    }
}
