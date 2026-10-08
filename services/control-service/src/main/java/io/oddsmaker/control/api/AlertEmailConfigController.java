package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.AlertEmailConfigEntity;
import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.AlertEmailService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 业务告警邮件通道 API：每游戏的收件配置（查询/保存/测试发送）。
 * 配置管理在控制台「业务告警」页；鉴权走 AccessGuard 行内风格（alert:read / alert:manage）。
 */
@RestController
public class AlertEmailConfigController {

    private final AlertEmailService alertEmailService;
    private final AccessGuard accessGuard;

    public AlertEmailConfigController(AlertEmailService alertEmailService, AccessGuard accessGuard) {
        this.alertEmailService = alertEmailService;
        this.accessGuard = accessGuard;
    }

    @GetMapping("/api/games/{gameId}/alert-email-config")
    public ResponseEntity<AlertEmailConfigEntity> get(@PathVariable String gameId) {
        accessGuard.requireGamePermission(gameId, "alert:read");
        return ResponseEntity.ok(alertEmailService.config(gameId));
    }

    @PutMapping("/api/games/{gameId}/alert-email-config")
    public ResponseEntity<AlertEmailConfigEntity> save(@PathVariable String gameId,
                                                       @RequestBody SaveReq req) {
        accessGuard.requireGamePermission(gameId, "alert:manage");
        AlertEmailConfigEntity cfg = alertEmailService.save(
            gameId, req.environment, req.recipients,
            Boolean.TRUE.equals(req.enabled), currentOperator());
        return ResponseEntity.ok(cfg);
    }

    /** 测试邮件：按当前保存的配置发一条测试消息（SMTP 未配置时返回 sent=false 而非报错） */
    @PostMapping("/api/games/{gameId}/alert-email-config/test")
    public ResponseEntity<Map<String, Object>> test(@PathVariable String gameId) {
        accessGuard.requireGamePermission(gameId, "alert:manage");
        AlertEmailConfigEntity cfg = alertEmailService.config(gameId);
        if (cfg == null) {
            throw new IllegalArgumentException("尚未配置邮件通道");
        }
        return ResponseEntity.ok(alertEmailService.sendTestEmail(gameId, cfg, currentOperator()));
    }

    public static class SaveReq {
        public String recipients;    // 必填：逗号/分号/空白分隔
        public String environment;   // 可空 = 全部环境
        public Boolean enabled;      // 缺省 false
    }

    private String currentOperator() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
            .getContext().getAuthentication();
        return auth != null ? auth.getName() : "api";
    }
}
