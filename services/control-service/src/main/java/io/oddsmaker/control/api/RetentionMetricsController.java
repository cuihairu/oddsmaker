package io.oddsmaker.control.api;

import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.RetentionMetricsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 留存趋势报表 API（ClickHouse 数据源：retention_daily）。
 * - /{gameId}/trend 按天/周/月 cohort 输出新增用户与次留/7留/30留趋势折线数据。
 */
@RestController
@RequestMapping("/api/retention-metrics")
public class RetentionMetricsController {

    private final RetentionMetricsService retentionMetricsService;
    private final AccessGuard accessGuard;

    public RetentionMetricsController(RetentionMetricsService retentionMetricsService, AccessGuard accessGuard) {
        this.retentionMetricsService = retentionMetricsService;
        this.accessGuard = accessGuard;
    }

    @GetMapping("/{gameId}/trend")
    public ResponseEntity<Map<String, Object>> trend(
            @PathVariable String gameId,
            @RequestParam(value = "environment", required = false) String environment,
            @RequestParam(value = "granularity", defaultValue = "day") String granularity,
            @RequestParam(value = "days", required = false) Integer days) {
        accessGuard.requireGamePermission(gameId, "game:read");
        return ResponseEntity.ok(retentionMetricsService.trend(gameId, environment, granularity, days));
    }
}
