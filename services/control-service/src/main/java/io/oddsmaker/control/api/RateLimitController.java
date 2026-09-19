package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.QuotaEntity;
import io.oddsmaker.control.jpa.RateLimitEntity;
import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.RateLimitService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 限流和配额API控制器
 * 提供API限流和资源配额管理的接口；鉴权走 AccessGuard 行内风格（ratelimit / quota 各 read/manage，全部 game 级）。
 * 历史形态为 @PreAuthorize hasAuthority('VIEW_RATE_LIMITS:'+gameId) 拼接式，
 * 而全仓只签发 ROLE_* authority，方法安全开启后这些注解恒 403——故换成权限种子（V0.9.8）+ AccessGuard 解析。
 */
@RestController
@RequestMapping("/api/rate-limits")
public class RateLimitController {

    @Autowired
    private RateLimitService rateLimitService;

    @Autowired
    private AccessGuard accessGuard;

    // ============== Rate Limit Endpoints ==============

    /**
     * 创建限流规则
     */
    @PostMapping
    public ResponseEntity<RateLimitEntity> createRateLimit(@RequestBody RateLimitRequest request) {
        accessGuard.requireGamePermission(request.gameId, "ratelimit:manage");
        RateLimitEntity rule = rateLimitService.createRateLimit(
            request.gameId,
            request.apiKeyId,
            request.endpoint,
            request.userId,
            request.scope,
            request.limit,
            request.windowType,
            request.windowSize,
            request.algorithm,
            request.burst,
            request.description,
            request.createdBy
        );
        return ResponseEntity.ok(rule);
    }

    /**
     * 获取限流规则详情
     */
    @GetMapping("/{ruleId}")
    public ResponseEntity<RateLimitEntity> getRateLimit(
            @PathVariable String ruleId,
            @RequestParam String gameId) {
        accessGuard.requireGamePermission(gameId, "ratelimit:read");
        RateLimitEntity rule = rateLimitService.getRateLimit(ruleId);
        return ResponseEntity.ok(rule);
    }

    /**
     * 获取游戏的限流规则
     */
    @GetMapping("/game/{gameId}")
    public ResponseEntity<List<RateLimitEntity>> getRateLimits(@PathVariable String gameId) {
        accessGuard.requireGamePermission(gameId, "ratelimit:read");
        List<RateLimitEntity> rules = rateLimitService.getRateLimits(gameId);
        return ResponseEntity.ok(rules);
    }

    /**
     * 更新限流规则
     */
    @PutMapping("/{ruleId}")
    public ResponseEntity<RateLimitEntity> updateRateLimit(
            @PathVariable String ruleId,
            @RequestParam String gameId,
            @RequestBody UpdateRequest request) {
        accessGuard.requireGamePermission(gameId, "ratelimit:manage");
        RateLimitEntity rule = rateLimitService.updateRateLimit(
            ruleId,
            request.limit,
            request.burst,
            request.enabled
        );
        return ResponseEntity.ok(rule);
    }

    /**
     * 删除限流规则
     */
    @DeleteMapping("/{ruleId}")
    public ResponseEntity<Void> deleteRateLimit(
            @PathVariable String ruleId,
            @RequestParam String gameId) {
        accessGuard.requireGamePermission(gameId, "ratelimit:manage");
        rateLimitService.deleteRateLimit(ruleId);
        return ResponseEntity.ok().build();
    }

    /**
     * 获取限流统计
     */
    @GetMapping("/stats/{gameId}")
    public ResponseEntity<Map<String, Object>> getRateLimitStats(@PathVariable String gameId) {
        accessGuard.requireGamePermission(gameId, "ratelimit:read");
        Map<String, Object> stats = rateLimitService.getRateLimitStats(gameId);
        return ResponseEntity.ok(stats);
    }

    // ============== Quota Endpoints ==============

    /**
     * 创建配额
     */
    @PostMapping("/quotas")
    public ResponseEntity<QuotaEntity> createQuota(@RequestBody QuotaRequest request) {
        accessGuard.requireGamePermission(request.gameId, "quota:manage");
        QuotaEntity quota = rateLimitService.createQuota(
            request.gameId,
            request.environmentId,
            request.resourceType,
            request.limit,
            request.warningThreshold,
            request.alertThreshold,
            request.hardLimit,
            request.createdBy
        );
        return ResponseEntity.ok(quota);
    }

    /**
     * 检查配额
     */
    @GetMapping("/quotas/check")
    public ResponseEntity<RateLimitService.QuotaCheckResult> checkQuota(
            @RequestParam String gameId,
            @RequestParam(required = false) String environmentId,
            @RequestParam QuotaEntity.ResourceType resourceType) {
        accessGuard.requireGamePermission(gameId, "quota:read");
        RateLimitService.QuotaCheckResult result = rateLimitService.checkQuota(gameId, environmentId, resourceType);
        return ResponseEntity.ok(result);
    }

    /**
     * 更新配额使用量
     */
    @PostMapping("/quotas/update-usage")
    public ResponseEntity<Void> updateQuotaUsage(
            @RequestParam String gameId,
            @RequestParam(required = false) String environmentId,
            @RequestParam QuotaEntity.ResourceType resourceType,
            @RequestParam long amount) {
        accessGuard.requireGamePermission(gameId, "quota:manage");
        rateLimitService.updateQuotaUsage(gameId, environmentId, resourceType, amount);
        return ResponseEntity.ok().build();
    }

    /**
     * 获取配额统计
     */
    @GetMapping("/quotas/stats/{gameId}")
    public ResponseEntity<Map<String, Object>> getQuotaStats(@PathVariable String gameId) {
        accessGuard.requireGamePermission(gameId, "quota:read");
        Map<String, Object> stats = rateLimitService.getQuotaStats(gameId);
        return ResponseEntity.ok(stats);
    }

    // Request DTOs

    public static class RateLimitRequest {
        public String gameId;
        public String apiKeyId;
        public String endpoint;
        public String userId;
        public RateLimitEntity.Scope scope;
        public Integer limit;
        public RateLimitEntity.WindowType windowType;
        public Integer windowSize;
        public RateLimitEntity.Algorithm algorithm;
        public Integer burst;
        public String description;
        public String createdBy;
    }

    public static class UpdateRequest {
        public Integer limit;
        public Integer burst;
        public Boolean enabled;
    }

    public static class QuotaRequest {
        public String gameId;
        public String environmentId;
        public QuotaEntity.ResourceType resourceType;
        public Long limit;
        public Double warningThreshold;
        public Double alertThreshold;
        public Boolean hardLimit;
        public String createdBy;
    }
}
