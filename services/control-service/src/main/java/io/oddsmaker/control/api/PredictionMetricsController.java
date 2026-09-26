package io.oddsmaker.control.api;

import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.PredictionMetricsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 智能化预测 API（ClickHouse 数据源：特征视图 + predictions 归档）。
 * - POST /{gameId}/churn/refresh        重算流失分并归档（ChurnScorer 启发式，可替换 ML）
 * - GET  /{gameId}/churn                高流失风险用户榜
 * - POST /{gameId}/risk-score/refresh   重算主体模型风险分（risk_events 严重度加权）
 * - GET  /{gameId}/risk-score           高模型风险分主体榜
 * - POST /{gameId}/propensity/refresh   付费倾向批量预测（PropensityScorer 启发式，可替换 ML）
 * - GET  /{gameId}/propensity           高付费倾向用户榜
 */
@RestController
@RequestMapping("/api/prediction-metrics")
public class PredictionMetricsController {

    private final PredictionMetricsService predictionMetricsService;
    private final AccessGuard accessGuard;

    public PredictionMetricsController(PredictionMetricsService predictionMetricsService,
                                       AccessGuard accessGuard) {
        this.predictionMetricsService = predictionMetricsService;
        this.accessGuard = accessGuard;
    }

    @PostMapping("/{gameId}/churn/refresh")
    public ResponseEntity<Map<String, Object>> refreshChurn(
            @PathVariable String gameId,
            @RequestParam(value = "environment", required = false) String environment) {
        accessGuard.requireGamePermission(gameId, "game:update");
        return ResponseEntity.ok(predictionMetricsService.refreshChurn(gameId, environment));
    }

    @GetMapping("/{gameId}/churn")
    public ResponseEntity<Map<String, Object>> topChurn(
            @PathVariable String gameId,
            @RequestParam(value = "environment", required = false) String environment,
            @RequestParam(value = "limit", required = false) Integer limit) {
        accessGuard.requireGamePermission(gameId, "game:read");
        return ResponseEntity.ok(predictionMetricsService.topChurn(gameId, environment, limit));
    }

    @PostMapping("/{gameId}/risk-score/refresh")
    public ResponseEntity<Map<String, Object>> refreshRiskScore(
            @PathVariable String gameId,
            @RequestParam(value = "environment", required = false) String environment) {
        accessGuard.requireGamePermission(gameId, "risk_rule:update");
        return ResponseEntity.ok(predictionMetricsService.refreshRiskScore(gameId, environment));
    }

    @GetMapping("/{gameId}/risk-score")
    public ResponseEntity<Map<String, Object>> topRiskScore(
            @PathVariable String gameId,
            @RequestParam(value = "environment", required = false) String environment,
            @RequestParam(value = "limit", required = false) Integer limit) {
        accessGuard.requireGamePermission(gameId, "risk_rule:read");
        return ResponseEntity.ok(predictionMetricsService.topRiskScore(gameId, environment, limit));
    }

    /** pLTV 批量预测（未成熟用户 D7 收入 × D7→D30 乘数，产物优先 / 启发式回落） */
    @PostMapping("/{gameId}/pltv/refresh")
    public ResponseEntity<Map<String, Object>> refreshPltv(
            @PathVariable String gameId,
            @RequestParam(value = "environment", required = false) String environment) {
        accessGuard.requireGamePermission(gameId, "game:update");
        return ResponseEntity.ok(predictionMetricsService.refreshPltv(gameId, environment));
    }

    @GetMapping("/{gameId}/pltv")
    public ResponseEntity<Map<String, Object>> topPltv(
            @PathVariable String gameId,
            @RequestParam(value = "environment", required = false) String environment,
            @RequestParam(value = "limit", required = false) Integer limit) {
        accessGuard.requireGamePermission(gameId, "game:read");
        return ResponseEntity.ok(predictionMetricsService.topPltv(gameId, environment, limit));
    }

    /** 付费倾向批量预测（30 天特征，产物优先 / PropensityScorer 回落） */
    @PostMapping("/{gameId}/propensity/refresh")
    public ResponseEntity<Map<String, Object>> refreshPropensity(
            @PathVariable String gameId,
            @RequestParam(value = "environment", required = false) String environment) {
        accessGuard.requireGamePermission(gameId, "game:update");
        return ResponseEntity.ok(predictionMetricsService.refreshPropensity(gameId, environment));
    }

    @GetMapping("/{gameId}/propensity")
    public ResponseEntity<Map<String, Object>> topPropensity(
            @PathVariable String gameId,
            @RequestParam(value = "environment", required = false) String environment,
            @RequestParam(value = "limit", required = false) Integer limit) {
        accessGuard.requireGamePermission(gameId, "game:read");
        return ResponseEntity.ok(predictionMetricsService.topPropensity(gameId, environment, limit));
    }
}
