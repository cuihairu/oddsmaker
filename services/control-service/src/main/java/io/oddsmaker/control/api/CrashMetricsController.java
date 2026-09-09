package io.oddsmaker.control.api;

import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.CrashMetricsService;
import io.oddsmaker.control.service.SymbolicationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Crash/Error 监控 API（ClickHouse 数据源：events + v_crash_* 口径）。
 * - /{gameId}/top-groups      Top 崩溃分组（crash_hash 聚合）
 * - /{gameId}/trend           按日崩溃趋势
 * - /{gameId}/rate-by-version 按版本崩溃率
 * - /{gameId}/symbolicate     混淆堆栈符号化（symbol_mappings 规则）
 */
@RestController
@RequestMapping("/api/crash-metrics")
public class CrashMetricsController {

    private final CrashMetricsService crashMetricsService;
    private final SymbolicationService symbolicationService;
    private final AccessGuard accessGuard;

    public CrashMetricsController(CrashMetricsService crashMetricsService,
                                  SymbolicationService symbolicationService,
                                  AccessGuard accessGuard) {
        this.crashMetricsService = crashMetricsService;
        this.symbolicationService = symbolicationService;
        this.accessGuard = accessGuard;
    }

    @GetMapping("/{gameId}/top-groups")
    public ResponseEntity<Map<String, Object>> topGroups(
            @PathVariable String gameId,
            @RequestParam(value = "environment", required = false) String environment,
            @RequestParam(value = "days", required = false) Integer days) {
        accessGuard.requireGamePermission(gameId, "game:read");
        return ResponseEntity.ok(crashMetricsService.topGroups(gameId, environment, days));
    }

    @GetMapping("/{gameId}/trend")
    public ResponseEntity<Map<String, Object>> trend(
            @PathVariable String gameId,
            @RequestParam(value = "environment", required = false) String environment,
            @RequestParam(value = "days", required = false) Integer days) {
        accessGuard.requireGamePermission(gameId, "game:read");
        return ResponseEntity.ok(crashMetricsService.trend(gameId, environment, days));
    }

    @GetMapping("/{gameId}/rate-by-version")
    public ResponseEntity<Map<String, Object>> rateByVersion(
            @PathVariable String gameId,
            @RequestParam(value = "environment", required = false) String environment,
            @RequestParam(value = "days", required = false) Integer days) {
        accessGuard.requireGamePermission(gameId, "game:read");
        return ResponseEntity.ok(crashMetricsService.rateByVersion(gameId, environment, days));
    }

    public static class SymbolicateRequest {
        public String platform;
        public String appVersion;
        public String stackTrace;
    }

    /** 混淆堆栈符号化：按 (platform, appVersion) 匹配 ACTIVE 映射规则 */
    @PostMapping("/{gameId}/symbolicate")
    public ResponseEntity<?> symbolicate(@PathVariable String gameId,
                                         @RequestBody SymbolicateRequest request) {
        accessGuard.requireGamePermission(gameId, "game:read");
        if (request.platform == null || request.platform.isBlank()
                || request.appVersion == null || request.appVersion.isBlank()) {
            return ResponseEntity.badRequest().body("platform and appVersion are required");
        }
        try {
            return ResponseEntity.ok(symbolicationService.symbolicate(
                gameId, request.platform, request.appVersion, request.stackTrace));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}
