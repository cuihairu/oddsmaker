package io.oddsmaker.control.api;

import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.FunnelResultsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 可配置漏斗结果 API（ClickHouse 数据源：funnels_configurable）。
 * 与 FunnelController（配置 CRUD，hasRole 风格）同前缀、无路由冲突；
 * 鉴权走 AccessGuard 行内风格（照 PaymentFunnelController 先例）。
 */
@RestController
@RequestMapping("/api/funnels")
public class FunnelResultsController {

    private final FunnelResultsService funnelResultsService;
    private final AccessGuard accessGuard;

    public FunnelResultsController(FunnelResultsService funnelResultsService, AccessGuard accessGuard) {
        this.funnelResultsService = funnelResultsService;
        this.accessGuard = accessGuard;
    }

    @GetMapping("/{funnelId}/results")
    public ResponseEntity<Map<String, Object>> results(
            @PathVariable String funnelId,
            @RequestParam String gameId,
            @RequestParam(value = "environment", required = false) String environment,
            @RequestParam(value = "days", required = false) Integer days) {
        accessGuard.requireGamePermission(gameId, "game:read");
        return ResponseEntity.ok(funnelResultsService.results(funnelId, gameId, environment, days));
    }
}
