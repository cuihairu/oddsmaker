package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.WebhookConfigEntity;
import io.oddsmaker.control.jpa.WebhookLogEntity;
import io.oddsmaker.control.service.WebhookService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Webhook配置API控制器
 * 提供Webhook管理的API接口
 */
@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {

    @Autowired
    private WebhookService webhookService;

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
}
