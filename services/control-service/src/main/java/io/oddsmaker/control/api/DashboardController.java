package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.DashboardEntity;
import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.AuditLogService;
import io.oddsmaker.control.service.DashboardService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 自定义仪表盘 API（P7-3 仪表盘 widget 化）。
 *
 * 布局 CRUD；widget 数据由前端按 source 白名单调用既有报表 API。
 * 读：dashboard:read；写：dashboard:manage。
 */
@RestController
@RequestMapping("/api")
public class DashboardController {

    private final DashboardService dashboardService;
    private final AccessGuard accessGuard;
    private final AuditLogService auditLog;

    public DashboardController(DashboardService dashboardService, AccessGuard accessGuard, AuditLogService auditLog) {
        this.dashboardService = dashboardService;
        this.accessGuard = accessGuard;
        this.auditLog = auditLog;
    }

    public static class CreateDashboardRequest {
        public String name;
        public String description;
        public String layout;
    }

    public static class UpdateDashboardRequest {
        public String description;
        public String status;
        public String layout;
    }

    @PostMapping("/games/{gameId}/dashboards")
    public ResponseEntity<DashboardEntity> create(
            @PathVariable String gameId, @RequestBody CreateDashboardRequest request) {
        accessGuard.requireGamePermission(gameId, "dashboard:manage");
        DashboardEntity created = dashboardService.create(gameId, request.name, request.description, request.layout);
        auditLog.logCreate("dashboard", created.id, created.name, null, null, null,
                Map.of("gameId", gameId, "widgets", widgetCount(created)));
        return ResponseEntity.ok(created);
    }

    @GetMapping("/games/{gameId}/dashboards")
    public ResponseEntity<List<DashboardEntity>> list(@PathVariable String gameId) {
        accessGuard.requireGamePermission(gameId, "dashboard:read");
        return ResponseEntity.ok(dashboardService.listByGame(gameId));
    }

    @GetMapping("/dashboards/{dashboardId}")
    public ResponseEntity<DashboardEntity> get(@PathVariable String dashboardId) {
        DashboardEntity entity = dashboardService.get(dashboardId);
        accessGuard.requireGamePermission(entity.gameId, "dashboard:read");
        return ResponseEntity.ok(entity);
    }

    @PutMapping("/dashboards/{dashboardId}")
    public ResponseEntity<DashboardEntity> update(
            @PathVariable String dashboardId, @RequestBody UpdateDashboardRequest request) {
        DashboardEntity entity = dashboardService.get(dashboardId);
        accessGuard.requireGamePermission(entity.gameId, "dashboard:manage");
        DashboardEntity updated = dashboardService.update(dashboardId, request.description, request.status, request.layout);
        auditLog.logUpdate("dashboard", updated.id, updated.name, null, null, null,
                Map.of("gameId", updated.gameId, "widgets", widgetCount(updated)));
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/dashboards/{dashboardId}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String dashboardId) {
        DashboardEntity entity = dashboardService.get(dashboardId);
        accessGuard.requireGamePermission(entity.gameId, "dashboard:manage");
        boolean deleted = dashboardService.delete(dashboardId);
        auditLog.logDelete("dashboard", dashboardId, entity.name, null, null, null);
        return ResponseEntity.ok(Map.of("deleted", deleted));
    }

    private static int widgetCount(DashboardEntity entity) {
        try {
            return entity.layout == null ? 0 : entity.layout.split("\"type\"", -1).length - 1;
        } catch (Exception e) {
            return 0;
        }
    }
}
