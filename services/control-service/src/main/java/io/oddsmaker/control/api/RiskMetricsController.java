package io.oddsmaker.control.api;

import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.RiskMetricsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 风控大屏指标 API（ClickHouse 数据源：risk_events + risk_actions）。
 * - /trend      风险趋势：时间桶 × 严重等级
 * - /rule-hits  规则命中：按 rule_id 聚合命中数/影响主体/均分/最近命中
 * - /severity   严重等级分布（按 severity 与 risk_type 细分）
 * - /actions    处置状态：按 action/state 聚合 + 最近处置明细
 */
@RestController
@RequestMapping("/api/risk-metrics")
public class RiskMetricsController {

    private final RiskMetricsService riskMetricsService;
    private final AccessGuard accessGuard;

    public RiskMetricsController(RiskMetricsService riskMetricsService, AccessGuard accessGuard) {
        this.riskMetricsService = riskMetricsService;
        this.accessGuard = accessGuard;
    }

    @GetMapping("/{gameId}/trend")
    public ResponseEntity<Map<String, Object>> trend(
            @PathVariable String gameId,
            @RequestParam(value = "environment", required = false) String environment,
            @RequestParam(value = "hours", required = false) Integer hours) {
        accessGuard.requireGamePermission(gameId, "risk_rule:read");
        return ResponseEntity.ok(riskMetricsService.trend(gameId, environment, hours));
    }

    @GetMapping("/{gameId}/rule-hits")
    public ResponseEntity<Map<String, Object>> ruleHits(
            @PathVariable String gameId,
            @RequestParam(value = "environment", required = false) String environment,
            @RequestParam(value = "hours", required = false) Integer hours) {
        accessGuard.requireGamePermission(gameId, "risk_rule:read");
        return ResponseEntity.ok(riskMetricsService.ruleHits(gameId, environment, hours));
    }

    @GetMapping("/{gameId}/severity")
    public ResponseEntity<Map<String, Object>> severity(
            @PathVariable String gameId,
            @RequestParam(value = "environment", required = false) String environment,
            @RequestParam(value = "hours", required = false) Integer hours) {
        accessGuard.requireGamePermission(gameId, "risk_rule:read");
        return ResponseEntity.ok(riskMetricsService.severity(gameId, environment, hours));
    }

    @GetMapping("/{gameId}/actions")
    public ResponseEntity<Map<String, Object>> actions(
            @PathVariable String gameId,
            @RequestParam(value = "environment", required = false) String environment,
            @RequestParam(value = "hours", required = false) Integer hours) {
        accessGuard.requireGamePermission(gameId, "risk_rule:read");
        return ResponseEntity.ok(riskMetricsService.actions(gameId, environment, hours));
    }
}
