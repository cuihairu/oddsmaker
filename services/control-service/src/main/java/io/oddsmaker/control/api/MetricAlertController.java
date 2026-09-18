package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.MetricAlertRuleEntity;
import io.oddsmaker.control.jpa.SystemAlertEntity;
import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.MetricAlertService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 业务指标告警 API：按游戏的告警规则 CRUD、手动试算、告警历史与确认/解决。
 * 鉴权走 AccessGuard 行内风格（alert:read / alert:manage）。
 */
@RestController
public class MetricAlertController {

    private final MetricAlertService metricAlertService;
    private final AccessGuard accessGuard;

    public MetricAlertController(MetricAlertService metricAlertService, AccessGuard accessGuard) {
        this.metricAlertService = metricAlertService;
        this.accessGuard = accessGuard;
    }

    @GetMapping("/api/games/{gameId}/alert-rules")
    public ResponseEntity<List<MetricAlertRuleEntity>> list(@PathVariable String gameId) {
        accessGuard.requireGamePermission(gameId, "alert:read");
        return ResponseEntity.ok(metricAlertService.list(gameId));
    }

    @GetMapping("/api/games/{gameId}/alert-rules/{ruleId}")
    public ResponseEntity<MetricAlertRuleEntity> get(@PathVariable String gameId,
                                                     @PathVariable String ruleId) {
        accessGuard.requireGamePermission(gameId, "alert:read");
        MetricAlertRuleEntity rule = metricAlertService.get(gameId, ruleId);
        return rule == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(rule);
    }

    @PostMapping("/api/games/{gameId}/alert-rules")
    public ResponseEntity<MetricAlertRuleEntity> create(@PathVariable String gameId,
                                                        @RequestBody MetricAlertRuleEntity req) {
        accessGuard.requireGamePermission(gameId, "alert:manage");
        return ResponseEntity.ok(metricAlertService.create(gameId, req, currentOperator()));
    }

    @PutMapping("/api/games/{gameId}/alert-rules/{ruleId}")
    public ResponseEntity<MetricAlertRuleEntity> update(@PathVariable String gameId,
                                                        @PathVariable String ruleId,
                                                        @RequestBody MetricAlertRuleEntity req) {
        accessGuard.requireGamePermission(gameId, "alert:manage");
        return ResponseEntity.ok(metricAlertService.update(gameId, ruleId, req, currentOperator()));
    }

    @DeleteMapping("/api/games/{gameId}/alert-rules/{ruleId}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String gameId,
                                                      @PathVariable String ruleId) {
        accessGuard.requireGamePermission(gameId, "alert:manage");
        boolean deleted = metricAlertService.delete(gameId, ruleId, currentOperator());
        return deleted
            ? ResponseEntity.ok(Map.of("deleted", true, "id", ruleId))
            : ResponseEntity.notFound().build();
    }

    /** 手动试算：返回当前值/基线/偏差/是否触发，不改任何状态 */
    @PostMapping("/api/games/{gameId}/alert-rules/{ruleId}/evaluate")
    public ResponseEntity<Map<String, Object>> evaluate(@PathVariable String gameId,
                                                        @PathVariable String ruleId) {
        accessGuard.requireGamePermission(gameId, "alert:manage");
        return ResponseEntity.ok(metricAlertService.evaluateNow(gameId, ruleId));
    }

    @GetMapping("/api/games/{gameId}/alerts")
    public ResponseEntity<List<SystemAlertEntity>> alerts(@PathVariable String gameId,
                                                          @RequestParam(defaultValue = "50") int limit) {
        accessGuard.requireGamePermission(gameId, "alert:read");
        return ResponseEntity.ok(metricAlertService.alertHistory(gameId, limit));
    }

    @PostMapping("/api/games/{gameId}/alerts/{alertId}/acknowledge")
    public ResponseEntity<SystemAlertEntity> acknowledge(@PathVariable String gameId,
                                                         @PathVariable String alertId,
                                                         @RequestBody(required = false) AlertActionReq req) {
        accessGuard.requireGamePermission(gameId, "alert:manage");
        String by = req != null && req.by != null && !req.by.isBlank() ? req.by : currentOperator();
        String comment = req != null ? req.comment : null;
        return ResponseEntity.ok(metricAlertService.acknowledge(gameId, alertId, by, comment));
    }

    @PostMapping("/api/games/{gameId}/alerts/{alertId}/resolve")
    public ResponseEntity<SystemAlertEntity> resolve(@PathVariable String gameId,
                                                     @PathVariable String alertId,
                                                     @RequestBody(required = false) AlertActionReq req) {
        accessGuard.requireGamePermission(gameId, "alert:manage");
        String by = req != null && req.by != null && !req.by.isBlank() ? req.by : currentOperator();
        String comment = req != null ? req.comment : null;
        return ResponseEntity.ok(metricAlertService.resolve(gameId, alertId, by, comment));
    }

    public static class AlertActionReq {
        public String by;       // 操作人（缺省当前登录用户）
        public String comment;
    }

    private String currentOperator() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
            .getContext().getAuthentication();
        return auth != null ? auth.getName() : "api";
    }
}
