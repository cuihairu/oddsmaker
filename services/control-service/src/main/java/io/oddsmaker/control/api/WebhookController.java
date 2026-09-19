package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.WebhookConfigEntity;
import io.oddsmaker.control.jpa.WebhookLogEntity;
import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.WebhookService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Webhook配置API控制器
 * 提供Webhook管理的API接口；配置 CRUD 鉴权走 AccessGuard 行内风格（webhook:manage），
 * 既有 GET/test 端点维持原 @PreAuthorize 注解不变
 */
@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {

    @Autowired
    private WebhookService webhookService;

    @Autowired
    private AccessGuard accessGuard;

    /**
     * 获取游戏的Webhook配置列表
     */
    @GetMapping("/game/{gameId}")
    @PreAuthorize("hasAuthority('READ_GAME:' + #gameId)")
    public ResponseEntity<List<WebhookConfigEntity>> getGameConfigs(@PathVariable String gameId) {
        List<WebhookConfigEntity> configs = webhookService.getGameConfigs(gameId);
        return ResponseEntity.ok(configs);
    }

    /**
     * 获取Webhook配置详情
     */
    @GetMapping("/configs/{configId}")
    @PreAuthorize("hasAuthority('READ_GAME:' + #gameId)")
    public ResponseEntity<WebhookConfigEntity> getConfig(
            @PathVariable String configId,
            @RequestParam String gameId) {
        WebhookConfigEntity config = webhookService.getConfig(configId);
        return ResponseEntity.ok(config);
    }

    /**
     * 获取Webhook发送日志
     */
    @GetMapping("/logs/{configId}")
    @PreAuthorize("hasAuthority('READ_GAME:' + #gameId)")
    public ResponseEntity<List<WebhookLogEntity>> getWebhookLogs(
            @PathVariable String configId,
            @RequestParam String gameId) {
        List<WebhookLogEntity> logs = webhookService.getWebhookLogs(configId);
        return ResponseEntity.ok(logs);
    }

    /**
     * 获取Webhook统计
     */
    @GetMapping("/stats/{gameId}")
    @PreAuthorize("hasAuthority('READ_GAME:' + #gameId)")
    public ResponseEntity<Map<String, Object>> getWebhookStats(@PathVariable String gameId) {
        Map<String, Object> stats = webhookService.getWebhookStats(gameId);
        return ResponseEntity.ok(stats);
    }

    /**
     * 测试Webhook：真实发送一次测试事件并返回投递结果（成功/失败均 200，按 body.status 区分）
     */
    @PostMapping("/test/{configId}")
    @PreAuthorize("hasAuthority('MANAGE_RISK:' + #gameId)")
    public ResponseEntity<Map<String, Object>> testWebhook(
            @PathVariable String configId,
            @RequestParam String gameId) {
        return ResponseEntity.ok(webhookService.sendTestWebhook(configId, gameId));
    }

    // ============== 配置 CRUD（AccessGuard 行内鉴权 webhook:manage） ==============

    /**
     * 创建Webhook配置（校验失败 IAE→400）
     */
    @PostMapping("/game/{gameId}/configs")
    public ResponseEntity<WebhookConfigEntity> createConfig(
            @PathVariable String gameId,
            @RequestBody WebhookConfigEntity req) {
        accessGuard.requireGamePermission(gameId, "webhook:manage");
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(webhookService.createWebhookConfig(gameId, req, currentOperator()));
    }

    /**
     * 更新Webhook配置（不存在/归属不符 404；authConfig 留空=保留原值）
     */
    @PutMapping("/game/{gameId}/configs/{configId}")
    public ResponseEntity<WebhookConfigEntity> updateConfig(
            @PathVariable String gameId,
            @PathVariable String configId,
            @RequestBody WebhookConfigEntity patch) {
        accessGuard.requireGamePermission(gameId, "webhook:manage");
        WebhookConfigEntity updated = webhookService.updateWebhookConfig(gameId, configId, patch, currentOperator());
        return updated != null ? ResponseEntity.ok(updated) : ResponseEntity.notFound().build();
    }

    /**
     * 删除Webhook配置（软删 + 置 INACTIVE；不存在/归属不符 404）
     */
    @DeleteMapping("/game/{gameId}/configs/{configId}")
    public ResponseEntity<Map<String, Object>> deleteConfig(
            @PathVariable String gameId,
            @PathVariable String configId) {
        accessGuard.requireGamePermission(gameId, "webhook:manage");
        boolean deleted = webhookService.deleteWebhookConfig(gameId, configId, currentOperator());
        return deleted
            ? ResponseEntity.ok(Map.of("deleted", true, "id", configId))
            : ResponseEntity.notFound().build();
    }

    private String currentOperator() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
            .getContext().getAuthentication();
        return auth != null ? auth.getName() : "api";
    }
}
