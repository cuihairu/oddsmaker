package io.oddsmaker.control.api;

import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.PerformanceMonitorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 性能监控API控制器
 * 提供系统性能指标的查询接口；鉴权走 AccessGuard 行内风格（metrics:read / metrics:infra 两档分层）。
 * 历史形态为 @PreAuthorize hasRole('ADMIN') or hasRole('MANAGER') 布尔式，而全仓只签发 ROLE_* authority
 * 且 ROLE_MANAGER 从未签发，方法安全开启后这些注解仅 ADMIN 可用——故换成权限种子（V0.9.8）+ AccessGuard 解析。
 */
@RestController
@RequestMapping("/api/monitoring")
public class PerformanceMonitoringController {

    @Autowired
    private PerformanceMonitorService performanceMonitorService;

    @Autowired
    private AccessGuard accessGuard;

    /**
     * 获取系统性能概览
     */
    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> getSystemOverview() {
        accessGuard.requirePermission("metrics:read");
        // 这里可以扩展为从数据库或缓存中获取更详细的统计数据
        Map<String, Object> overview = Map.of(
            "status", "healthy",
            "timestamp", System.currentTimeMillis(),
            "metrics", Map.of(
                "apiRequests", "See /actuator/prometheus for detailed metrics",
                "eventsProcessed", "See /actuator/prometheus for detailed metrics",
                "riskEventsDetected", "See /actuator/prometheus for detailed metrics"
            )
        );
        return ResponseEntity.ok(overview);
    }

    /**
     * 获取API性能指标
     */
    @GetMapping("/api")
    public ResponseEntity<Map<String, Object>> getApiMetrics() {
        accessGuard.requirePermission("metrics:read");
        Map<String, Object> metrics = Map.of(
            "description", "API performance metrics",
            "endpoints", Map.of(
                "totalRequests", "/actuator/prometheus#oddsmaker_api_requests_total",
                "responseTime", "/actuator/prometheus#oddsmaker_api_response_time_seconds",
                "errors", "/actuator/prometheus#oddsmaker_api_errors_total"
            ),
            "note", "Use Prometheus query language to fetch specific metrics"
        );
        return ResponseEntity.ok(metrics);
    }

    /**
     * 获取事件处理指标
     */
    @GetMapping("/events")
    public ResponseEntity<Map<String, Object>> getEventMetrics() {
        accessGuard.requirePermission("metrics:read");
        Map<String, Object> metrics = Map.of(
            "description", "Event processing metrics",
            "endpoints", Map.of(
                "ingested", "/actuator/prometheus#oddsmaker_events_ingested_total",
                "processed", "/actuator/prometheus#oddsmaker_events_processed_total",
                "processingTime", "/actuator/prometheus#oddsmaker_events_processing_time_seconds"
            ),
            "note", "Use Prometheus query language to fetch specific metrics"
        );
        return ResponseEntity.ok(metrics);
    }

    /**
     * 获取风控指标
     */
    @GetMapping("/risk")
    public ResponseEntity<Map<String, Object>> getRiskMetrics() {
        accessGuard.requirePermission("metrics:read");
        Map<String, Object> metrics = Map.of(
            "description", "Risk control metrics",
            "endpoints", Map.of(
                "detected", "/actuator/prometheus#oddsmaker_risk_events_detected_total",
                "actions", "/actuator/prometheus#oddsmaker_risk_actions_executed_total"
            ),
            "note", "Use Prometheus query language to fetch specific metrics"
        );
        return ResponseEntity.ok(metrics);
    }

    /**
     * 获取数据库指标
     */
    @GetMapping("/database")
    public ResponseEntity<Map<String, Object>> getDatabaseMetrics() {
        accessGuard.requirePermission("metrics:infra");
        Map<String, Object> metrics = Map.of(
            "description", "Database performance metrics",
            "endpoints", Map.of(
                "queryTime", "/actuator/prometheus#oddsmaker_database_query_time_seconds",
                "connectionPool", "/actuator/prometheus#oddsmaker_database_pool_*"
            ),
            "note", "Use Prometheus query language to fetch specific metrics"
        );
        return ResponseEntity.ok(metrics);
    }

    /**
     * 获取Kafka指标
     */
    @GetMapping("/kafka")
    public ResponseEntity<Map<String, Object>> getKafkaMetrics() {
        accessGuard.requirePermission("metrics:infra");
        Map<String, Object> metrics = Map.of(
            "description", "Kafka performance metrics",
            "endpoints", Map.of(
                "publishTime", "/actuator/prometheus#oddsmaker_kafka_publish_time_seconds",
                "consumerLag", "/actuator/prometheus#oddsmaker_kafka_consumer_lag"
            ),
            "note", "Use Prometheus query language to fetch specific metrics"
        );
        return ResponseEntity.ok(metrics);
    }

    /**
     * 获取系统资源指标
     */
    @GetMapping("/system")
    public ResponseEntity<Map<String, Object>> getSystemMetrics() {
        accessGuard.requirePermission("metrics:infra");
        Map<String, Object> metrics = Map.of(
            "description", "System resource metrics",
            "endpoints", Map.of(
                "activeUsers", "/actuator/prometheus#oddsmaker_users_active",
                "queueSize", "/actuator/prometheus#oddsmaker_queue_size",
                "memoryUsage", "/actuator/prometheus#oddsmaker_memory_usage_bytes",
                "cpuUsage", "/actuator/prometheus#oddsmaker_cpu_usage_percent"
            ),
            "note", "Use Prometheus query language to fetch specific metrics"
        );
        return ResponseEntity.ok(metrics);
    }

    /**
     * 获取业务指标
     */
    @GetMapping("/business")
    public ResponseEntity<Map<String, Object>> getBusinessMetrics() {
        accessGuard.requirePermission("metrics:read");
        Map<String, Object> metrics = Map.of(
            "description", "Business metrics",
            "endpoints", Map.of(
                "userLogins", "/actuator/prometheus#oddsmaker_users_login_total",
                "experimentExposures", "/actuator/prometheus#oddsmaker_experiments_exposure_total",
                "revenue", "/actuator/prometheus#oddsmaker_revenue_*",
                "funnelConversion", "/actuator/prometheus#oddsmaker_funnel_conversion"
            ),
            "note", "Use Prometheus query language to fetch specific metrics"
        );
        return ResponseEntity.ok(metrics);
    }

    /**
     * 获取健康检查指标
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> getHealthMetrics() {
        Map<String, Object> metrics = Map.of(
            "description", "Health check metrics",
            "endpoints", Map.of(
                "healthStatus", "/actuator/prometheus#oddsmaker_health",
                "availability", "/actuator/prometheus#oddsmaker_availability"
            ),
            "note", "Use Prometheus query language to fetch specific metrics"
        );
        return ResponseEntity.ok(metrics);
    }

    /**
     * 获取Prometheus指标端点信息
     */
    @GetMapping("/prometheus")
    public ResponseEntity<Map<String, Object>> getPrometheusEndpoint() {
        Map<String, Object> info = Map.of(
            "endpoint", "/actuator/prometheus",
            "format", "OpenMetrics/Prometheus exposition format",
            "description", "Use this endpoint with Prometheus server to scrape metrics",
            "grafanaDashboard", "Import the provided Grafana dashboard JSON for visualization"
        );
        return ResponseEntity.ok(info);
    }
}
