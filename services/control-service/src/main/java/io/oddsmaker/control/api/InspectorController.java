package io.oddsmaker.control.api;

import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.InspectorProxyService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 实时事件检视 API（Live Inspector，竞品差距 P7-1）。
 *
 * 控制台侧代理端点：转发到 Gateway /v1/inspector/recent 并按 key 作用域收敛。
 * 数据在 Gateway 内存中（TTL 10 分钟），仅元数据 + 结局/原因，无 props/PII。
 */
@RestController
@RequestMapping("/api/inspector")
public class InspectorController {

    private final InspectorProxyService inspectorProxyService;
    private final AccessGuard accessGuard;

    public InspectorController(InspectorProxyService inspectorProxyService, AccessGuard accessGuard) {
        this.inspectorProxyService = inspectorProxyService;
        this.accessGuard = accessGuard;
    }

    @GetMapping("/{gameId}/recent")
    public ResponseEntity<Map<String, Object>> recent(
            @PathVariable String gameId,
            @RequestParam("environment") String environment,
            @RequestParam(value = "outcome", required = false) String outcome,
            @RequestParam(value = "limit", required = false) Integer limit) {
        accessGuard.requireGamePermission(gameId, "game:read");
        return ResponseEntity.ok(inspectorProxyService.recent(gameId, environment, outcome, limit));
    }
}
