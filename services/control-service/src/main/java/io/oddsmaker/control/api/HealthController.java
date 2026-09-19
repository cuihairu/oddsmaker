package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.HealthCheckEntity;
import io.oddsmaker.control.jpa.HealthMetricEntity;
import io.oddsmaker.control.jpa.SystemAlertEntity;
import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.HealthMonitorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 健康监控API控制器
 * 提供系统健康检查和告警管理的接口；鉴权走 AccessGuard 行内风格（health:read / health:manage，
 * 告警端点复用已种子的 alert:read / alert:manage），探活 overview/live/ready 保持无 guard。
 * 历史形态为 @PreAuthorize hasAuthority('VIEW_HEALTH_CHECKS') 静态式，
 * 而全仓只签发 ROLE_* authority，方法安全开启后这些注解恒 403——故换成权限种子（V0.9.8）+ AccessGuard 解析。
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    @Autowired
    private HealthMonitorService healthMonitorService;

    @Autowired
    private AccessGuard accessGuard;

    // ============== Health Check Endpoints ==============

    /**
     * 获取系统整体健康状况
     */
    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> getSystemHealth() {
        Map<String, Object> health = healthMonitorService.getSystemHealth();
        return ResponseEntity.ok(health);
    }

    /**
     * 获取所有健康检查
     */
    @GetMapping("/checks")
    public ResponseEntity<List<HealthCheckEntity>> getHealthChecks() {
        accessGuard.requirePermission("health:read");
        List<HealthCheckEntity> checks = healthMonitorService.getHealthChecks();
        return ResponseEntity.ok(checks);
    }

    /**
     * 获取健康检查详情
     */
    @GetMapping("/checks/{checkName}")
    public ResponseEntity<HealthCheckEntity> getHealthCheck(@PathVariable String checkName) {
        accessGuard.requirePermission("health:read");
        HealthCheckEntity check = healthMonitorService.getHealthCheck(checkName);
        return ResponseEntity.ok(check);
    }

    /**
     * 执行健康检查
     */
    @PostMapping("/checks/{checkName}/run")
    public ResponseEntity<HealthCheckEntity> runHealthCheck(@PathVariable String checkName) {
        accessGuard.requirePermission("health:manage");
        HealthCheckEntity result = healthMonitorService.performHealthCheck(checkName);
        return ResponseEntity.ok(result);
    }

    /**
     * 公开健康检查端点（用于负载均衡器等）
     */
    @GetMapping("/live")
    public ResponseEntity<Map<String, Object>> liveness() {
        Map<String, Object> status = Map.of(
            "status", "UP",
            "timestamp", LocalDateTime.now()
        );
        return ResponseEntity.ok(status);
    }

    /**
     * 公开就绪检查端点
     */
    @GetMapping("/ready")
    public ResponseEntity<Map<String, Object>> readiness() {
        Map<String, Object> systemHealth = healthMonitorService.getSystemHealth();
        HealthCheckEntity.HealthStatus overallStatus = (HealthCheckEntity.HealthStatus) systemHealth.get("overallStatus");

        boolean isReady = overallStatus == HealthCheckEntity.HealthStatus.HEALTHY;
        Map<String, Object> status = Map.of(
            "status", isReady ? "READY" : "NOT_READY",
            "timestamp", LocalDateTime.now(),
            "details", systemHealth
        );

        return ResponseEntity.ok(status);
    }

    // ============== Metrics Endpoints ==============

    /**
     * 获取最近的指标
     */
    @GetMapping("/metrics/{type}")
    public ResponseEntity<List<HealthMetricEntity>> getRecentMetrics(
            @PathVariable HealthMetricEntity.MetricType type,
            @RequestParam(required = false) String since) {
        accessGuard.requirePermission("health:read");
        LocalDateTime sinceDate = since != null ? LocalDateTime.parse(since) : LocalDateTime.now().minusHours(1);
        List<HealthMetricEntity> metrics = healthMonitorService.getRecentMetrics(type, sinceDate);
        return ResponseEntity.ok(metrics);
    }

    // ============== Alerts Endpoints ==============

    /**
     * 获取活跃告警
     */
    @GetMapping("/alerts/active")
    public ResponseEntity<List<SystemAlertEntity>> getActiveAlerts() {
        accessGuard.requirePermission("alert:read");
        List<SystemAlertEntity> alerts = healthMonitorService.getActiveAlerts();
        return ResponseEntity.ok(alerts);
    }

    /**
     * 获取告警统计
     */
    @GetMapping("/alerts/stats")
    public ResponseEntity<Map<String, Object>> getAlertStats() {
        accessGuard.requirePermission("alert:read");
        Map<String, Object> stats = healthMonitorService.getAlertStats();
        return ResponseEntity.ok(stats);
    }

    /**
     * 确认告警
     */
    @PostMapping("/alerts/{alertId}/acknowledge")
    public ResponseEntity<SystemAlertEntity> acknowledgeAlert(
            @PathVariable String alertId,
            @RequestBody AcknowledgeRequest request) {
        accessGuard.requirePermission("alert:manage");
        SystemAlertEntity alert = healthMonitorService.acknowledgeAlert(
            alertId,
            request.acknowledgedBy,
            request.comment
        );
        return ResponseEntity.ok(alert);
    }

    /**
     * 解决告警
     */
    @PostMapping("/alerts/{alertId}/resolve")
    public ResponseEntity<SystemAlertEntity> resolveAlert(
            @PathVariable String alertId,
            @RequestBody ResolveRequest request) {
        accessGuard.requirePermission("alert:manage");
        SystemAlertEntity alert = healthMonitorService.resolveAlert(
            alertId,
            request.resolvedBy,
            request.comment
        );
        return ResponseEntity.ok(alert);
    }

    // Request DTOs

    public static class AcknowledgeRequest {
        public String acknowledgedBy;
        public String comment;
    }

    public static class ResolveRequest {
        public String resolvedBy;
        public String comment;
    }
}
