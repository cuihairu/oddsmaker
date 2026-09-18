package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.RetentionEnforcementEntity;
import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.RetentionEnforcementService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据保留策略 API：ClickHouse TTL 与 dataRetentionDays 配置的对账状态查看/手动触发。
 * 全局维度（CH 表全库共享，无 game 作用域），鉴权走全局权限（AccessGuard.requirePermission）。
 */
@RestController
public class RetentionController {

    private final RetentionEnforcementService retentionService;
    private final AccessGuard accessGuard;

    public RetentionController(RetentionEnforcementService retentionService, AccessGuard accessGuard) {
        this.retentionService = retentionService;
        this.accessGuard = accessGuard;
    }

    /** 对账状态：{configured, enabled, expectedDays, items}（expectedDays 为 null 表示无有效配置） */
    @GetMapping("/api/retention/enforcements")
    public ResponseEntity<?> status() {
        accessGuard.requirePermission("retention:read");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("configured", retentionService.isConfigured());
        out.put("enabled", retentionService.isEnabled());
        out.put("expectedDays", retentionService.resolveExpectedDays());
        out.put("items", retentionService.listStates());
        return ResponseEntity.ok(out);
    }

    /** 手动对账：不受 enabled 调度门限制（管理员显式意图）；CH 未配置时空转返回。 */
    @PostMapping("/api/retention/enforcements/run")
    public ResponseEntity<?> run() {
        accessGuard.requirePermission("retention:manage");
        List<RetentionEnforcementEntity> items = retentionService.enforceNow();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("configured", retentionService.isConfigured());
        out.put("expectedDays", retentionService.resolveExpectedDays());
        out.put("ranAt", LocalDateTime.now().toString());
        out.put("items", items);
        return ResponseEntity.ok(out);
    }
}
