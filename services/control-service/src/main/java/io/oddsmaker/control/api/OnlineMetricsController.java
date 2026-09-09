package io.oddsmaker.control.api;

import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.OnlineMetricsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 实时在线监控 API（ClickHouse 数据源：events 近窗聚合）。
 * - /{gameId} 在线总数 + 按平台/版本/渠道分组 + 分钟趋势（近 N 分钟有事件的独立主体数）。
 */
@RestController
@RequestMapping("/api/online-metrics")
public class OnlineMetricsController {

    private final OnlineMetricsService onlineMetricsService;
    private final AccessGuard accessGuard;

    public OnlineMetricsController(OnlineMetricsService onlineMetricsService, AccessGuard accessGuard) {
        this.onlineMetricsService = onlineMetricsService;
        this.accessGuard = accessGuard;
    }

    @GetMapping("/{gameId}")
    public ResponseEntity<Map<String, Object>> overview(
            @PathVariable String gameId,
            @RequestParam(value = "environment", required = false) String environment,
            @RequestParam(value = "minutes", required = false) Integer minutes) {
        accessGuard.requireGamePermission(gameId, "game:read");
        return ResponseEntity.ok(onlineMetricsService.overview(gameId, environment, minutes));
    }
}
