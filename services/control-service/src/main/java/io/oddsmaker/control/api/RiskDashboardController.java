package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.RiskRuleEntity;
import io.oddsmaker.control.jpa.RiskRuleRepo;
import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.RiskDashboardService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 风控仪表盘API控制器
 * 提供风控数据可视化的API接口；鉴权走 AccessGuard 行内风格（game:read，全部 game 级）。
 * 历史形态为 @PreAuthorize hasAuthority('READ_GAME:'+gameId) 拼接式（getActiveRules 原本无注解，现补齐 game:read），
 * 而全仓只签发 ROLE_* authority，方法安全开启后这些注解恒 403——故换成权限种子（V0.9.8）+ AccessGuard 解析。
 */
@RestController
@RequestMapping("/api/risk-dashboard")
public class RiskDashboardController {

    @Autowired
    private RiskDashboardService riskDashboardService;

    @Autowired
    private RiskRuleRepo riskRuleRepo;

    @Autowired
    private AccessGuard accessGuard;

    @GetMapping("/rules/{gameId}")
    public ResponseEntity<List<Map<String, Object>>> getActiveRules(@PathVariable String gameId) {
        accessGuard.requireGamePermission(gameId, "game:read");
        List<Map<String, Object>> rules = riskRuleRepo.findActiveByGameId(gameId).stream()
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", r.id);
                    m.put("name", r.name);
                    m.put("ruleType", r.ruleType != null ? r.ruleType.name() : null);
                    m.put("ruleConditions", r.ruleConditions);
                    m.put("triggerThreshold", r.triggerThreshold);
                    m.put("timeWindowMinutes", r.timeWindowMinutes);
                    m.put("riskScore", r.riskScore);
                    m.put("riskLevel", r.riskLevel != null ? r.riskLevel.name() : null);
                    m.put("actionType", r.actionType != null ? r.actionType.name() : null);
                    return m;
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(rules);
    }

    /**
     * 获取游戏的风控概览
     */
    @GetMapping("/overview/{gameId}")
    public ResponseEntity<Map<String, Object>> getOverview(
            @PathVariable String gameId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime since) {
        accessGuard.requireGamePermission(gameId, "game:read");
        Map<String, Object> overview = riskDashboardService.getOverview(gameId, since);
        return ResponseEntity.ok(overview);
    }

    /**
     * 获取风险趋势
     */
    @GetMapping("/trends/{gameId}")
    public ResponseEntity<List<Map<String, Object>>> getTrends(
            @PathVariable String gameId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime since,
            @RequestParam(defaultValue = "24") int intervalHours) {
        accessGuard.requireGamePermission(gameId, "game:read");
        List<Map<String, Object>> trends = riskDashboardService.getRiskTrends(gameId, since, intervalHours);
        return ResponseEntity.ok(trends);
    }

    /**
     * 获取高风险目标
     */
    @GetMapping("/high-risk-targets/{gameId}")
    public ResponseEntity<List<Map<String, Object>>> getHighRiskTargets(
            @PathVariable String gameId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime since,
            @RequestParam(defaultValue = "10") int limit) {
        accessGuard.requireGamePermission(gameId, "game:read");
        List<Map<String, Object>> targets = riskDashboardService.getHighRiskTargets(gameId, since, limit);
        return ResponseEntity.ok(targets);
    }

    /**
     * 获取规则性能统计
     */
    @GetMapping("/rule-performance/{gameId}")
    public ResponseEntity<List<Map<String, Object>>> getRulePerformance(@PathVariable String gameId) {
        accessGuard.requireGamePermission(gameId, "game:read");
        List<Map<String, Object>> performance = riskDashboardService.getRulePerformance(gameId);
        return ResponseEntity.ok(performance);
    }

    /**
     * 获取封禁统计
     */
    @GetMapping("/block-stats/{gameId}")
    public ResponseEntity<Map<String, Object>> getBlockStats(@PathVariable String gameId) {
        accessGuard.requireGamePermission(gameId, "game:read");
        Map<String, Object> stats = riskDashboardService.getBlockStats(gameId);
        return ResponseEntity.ok(stats);
    }

    /**
     * 获取作业统计
     */
    @GetMapping("/job-stats/{gameId}")
    public ResponseEntity<Map<String, Object>> getJobStats(@PathVariable String gameId) {
        accessGuard.requireGamePermission(gameId, "game:read");
        Map<String, Object> stats = riskDashboardService.getJobStats(gameId);
        return ResponseEntity.ok(stats);
    }

    /**
     * 获取完整仪表盘数据
     */
    @GetMapping("/dashboard/{gameId}")
    public ResponseEntity<Map<String, Object>> getDashboard(
            @PathVariable String gameId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime since) {
        accessGuard.requireGamePermission(gameId, "game:read");
        Map<String, Object> dashboard = riskDashboardService.getDashboard(gameId, since);
        return ResponseEntity.ok(dashboard);
    }

    /**
     * 获取最近的风险案例
     */
    @GetMapping("/recent-cases/{gameId}")
    public ResponseEntity<List<Map<String, Object>>> getRecentCases(
            @PathVariable String gameId,
            @RequestParam(defaultValue = "20") int limit) {
        accessGuard.requireGamePermission(gameId, "game:read");
        List<Map<String, Object> > cases = riskDashboardService.getRecentCases(gameId, limit);
        return ResponseEntity.ok(cases);
    }

    /**
     * 获取审核队列统计
     */
    @GetMapping("/review-queue-stats/{gameId}")
    public ResponseEntity<Map<String, Object>> getReviewQueueStats(@PathVariable String gameId) {
        accessGuard.requireGamePermission(gameId, "game:read");
        Map<String, Object> stats = riskDashboardService.getReviewQueueStats(gameId);
        return ResponseEntity.ok(stats);
    }
}
