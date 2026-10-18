package io.oddsmaker.control.api;

import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.GameReadinessService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 游戏分析就绪度 API（审计 DATA-002）：区分「CH 可达」与「采集→分析链路在产」。
 * - GET /api/games/{gameId}/readiness：available + analysisLastEventAt（ISO-8601 UTC）。
 */
@RestController
@RequestMapping("/api/games/{gameId}/readiness")
public class GameReadinessController {

    private final GameReadinessService gameReadinessService;
    private final AccessGuard accessGuard;

    public GameReadinessController(GameReadinessService gameReadinessService, AccessGuard accessGuard) {
        this.gameReadinessService = gameReadinessService;
        this.accessGuard = accessGuard;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> readiness(
            @PathVariable String gameId,
            @RequestParam(value = "environment", required = false) String environment) {
        accessGuard.requireGamePermission(gameId, "game:read");
        return ResponseEntity.ok(gameReadinessService.readiness(gameId, environment));
    }
}
