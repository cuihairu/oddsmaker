package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.IntegrationEntity;
import io.oddsmaker.control.jpa.IntegrationLogEntity;
import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.IntegrationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 集成API控制器
 * 提供外部系统集成管理的API接口；鉴权走 AccessGuard 行内风格（integration:read / manage / trigger，全部 game 级）。
 * 历史形态为 @PreAuthorize hasAuthority('VIEW_INTEGRATIONS:'+gameId) 拼接式，
 * 而全仓只签发 ROLE_* authority，方法安全开启后这些注解恒 403——故换成权限种子（V0.9.8）+ AccessGuard 解析。
 */
@RestController
@RequestMapping("/api/integrations")
public class IntegrationController {

    @Autowired
    private IntegrationService integrationService;

    @Autowired
    private AccessGuard accessGuard;

    /**
     * 创建集成
     */
    @PostMapping
    public ResponseEntity<IntegrationEntity> createIntegration(@RequestBody IntegrationRequest request) {
        accessGuard.requireGamePermission(request.gameId, "integration:manage");
        IntegrationEntity integration = integrationService.createIntegration(
            request.gameId,
            request.name,
            request.description,
            request.integrationType,
            request.authType,
            request.endpointUrl,
            request.apiKey,
            request.apiSecret,
            request.config,
            request.timeoutSeconds,
            request.createdBy
        );
        return ResponseEntity.ok(integration);
    }

    /**
     * 获取集成详情
     */
    @GetMapping("/{integrationId}")
    public ResponseEntity<IntegrationEntity> getIntegration(
            @PathVariable String integrationId,
            @RequestParam String gameId) {
        accessGuard.requireGamePermission(gameId, "integration:read");
        IntegrationEntity integration = integrationService.getIntegration(integrationId);
        return ResponseEntity.ok(integration);
    }

    /**
     * 获取游戏的集成列表
     */
    @GetMapping("/game/{gameId}")
    public ResponseEntity<List<IntegrationEntity>> getIntegrations(@PathVariable String gameId) {
        accessGuard.requireGamePermission(gameId, "integration:read");
        List<IntegrationEntity> integrations = integrationService.getIntegrations(gameId);
        return ResponseEntity.ok(integrations);
    }

    /**
     * 根据类型获取集成列表
     */
    @GetMapping("/game/{gameId}/type/{type}")
    public ResponseEntity<List<IntegrationEntity>> getIntegrationsByType(
            @PathVariable String gameId,
            @PathVariable IntegrationEntity.IntegrationType type) {
        accessGuard.requireGamePermission(gameId, "integration:read");
        List<IntegrationEntity> integrations = integrationService.getIntegrations(gameId).stream()
            .filter(i -> i.integrationType == type)
            .toList();
        return ResponseEntity.ok(integrations);
    }

    /**
     * 更新集成
     */
    @PutMapping("/{integrationId}")
    public ResponseEntity<IntegrationEntity> updateIntegration(
            @PathVariable String integrationId,
            @RequestParam String gameId,
            @RequestBody UpdateRequest request) {
        accessGuard.requireGamePermission(gameId, "integration:manage");
        IntegrationEntity integration = integrationService.updateIntegration(
            integrationId,
            request.name,
            request.description,
            request.endpointUrl,
            request.apiKey,
            request.apiSecret,
            request.config
        );
        return ResponseEntity.ok(integration);
    }

    /**
     * 验证集成连接
     */
    @PostMapping("/{integrationId}/verify")
    public ResponseEntity<IntegrationEntity> verifyIntegration(
            @PathVariable String integrationId,
            @RequestParam String gameId) {
        accessGuard.requireGamePermission(gameId, "integration:manage");
        IntegrationEntity integration = integrationService.verifyIntegration(integrationId);
        return ResponseEntity.ok(integration);
    }

    /**
     * 启用集成
     */
    @PostMapping("/{integrationId}/enable")
    public ResponseEntity<IntegrationEntity> enableIntegration(
            @PathVariable String integrationId,
            @RequestParam String gameId) {
        accessGuard.requireGamePermission(gameId, "integration:manage");
        IntegrationEntity integration = integrationService.enableIntegration(integrationId);
        return ResponseEntity.ok(integration);
    }

    /**
     * 禁用集成
     */
    @PostMapping("/{integrationId}/disable")
    public ResponseEntity<IntegrationEntity> disableIntegration(
            @PathVariable String integrationId,
            @RequestParam String gameId) {
        accessGuard.requireGamePermission(gameId, "integration:manage");
        IntegrationEntity integration = integrationService.disableIntegration(integrationId);
        return ResponseEntity.ok(integration);
    }

    /**
     * 删除集成
     */
    @DeleteMapping("/{integrationId}")
    public ResponseEntity<Void> deleteIntegration(
            @PathVariable String integrationId,
            @RequestParam String gameId) {
        accessGuard.requireGamePermission(gameId, "integration:manage");
        integrationService.deleteIntegration(integrationId);
        return ResponseEntity.ok().build();
    }

    /**
     * 获取集成日志
     */
    @GetMapping("/{integrationId}/logs")
    public ResponseEntity<List<IntegrationLogEntity>> getIntegrationLogs(
            @PathVariable String integrationId,
            @RequestParam String gameId) {
        accessGuard.requireGamePermission(gameId, "integration:read");
        List<IntegrationLogEntity> logs = integrationService.getIntegrationLogs(integrationId);
        return ResponseEntity.ok(logs);
    }

    /**
     * 获取集成统计
     */
    @GetMapping("/stats/{gameId}")
    public ResponseEntity<Map<String, Object>> getIntegrationStats(@PathVariable String gameId) {
        accessGuard.requireGamePermission(gameId, "integration:read");
        Map<String, Object> stats = integrationService.getIntegrationStats(gameId);
        return ResponseEntity.ok(stats);
    }

    /**
     * 获取集成调用统计
     */
    @GetMapping("/{integrationId}/call-stats")
    public ResponseEntity<Map<String, Object>> getCallStats(
            @PathVariable String integrationId,
            @RequestParam String gameId,
            @RequestParam(required = false) String since) {
        accessGuard.requireGamePermission(gameId, "integration:read");
        LocalDateTime sinceDate = since != null ? LocalDateTime.parse(since) : LocalDateTime.now().minusDays(7);
        Map<String, Object> stats = integrationService.getCallStats(integrationId, sinceDate);
        return ResponseEntity.ok(stats);
    }

    /**
     * 触发集成调用
     */
    @PostMapping("/{integrationId}/trigger")
    public ResponseEntity<IntegrationLogEntity> triggerIntegration(
            @PathVariable String integrationId,
            @RequestParam String gameId,
            @RequestBody TriggerRequest request) {
        accessGuard.requireGamePermission(gameId, "integration:trigger");
        IntegrationLogEntity log = integrationService.callIntegration(
            integrationId,
            request.eventType,
            request.payload,
            request.correlationId
        );
        return ResponseEntity.ok(log);
    }

    /**
     * 批量触发集成
     */
    @PostMapping("/game/{gameId}/trigger-batch")
    public ResponseEntity<List<IntegrationLogEntity>> triggerIntegrations(
            @PathVariable String gameId,
            @RequestParam IntegrationEntity.IntegrationType type,
            @RequestBody TriggerRequest request) {
        accessGuard.requireGamePermission(gameId, "integration:trigger");
        List<IntegrationLogEntity> logs = integrationService.callIntegrations(
            gameId,
            type,
            request.eventType,
            request.payload,
            request.correlationId
        );
        return ResponseEntity.ok(logs);
    }

    // Request DTOs

    public static class IntegrationRequest {
        public String gameId;
        public String name;
        public String description;
        public IntegrationEntity.IntegrationType integrationType;
        public IntegrationEntity.AuthType authType;
        public String endpointUrl;
        public String apiKey;
        public String apiSecret;
        public Map<String, Object> config;
        public Integer timeoutSeconds;
        public String createdBy;
    }

    public static class UpdateRequest {
        public String name;
        public String description;
        public String endpointUrl;
        public String apiKey;
        public String apiSecret;
        public Map<String, Object> config;
    }

    public static class TriggerRequest {
        public String eventType;
        public Map<String, Object> payload;
        public String correlationId;
    }
}
