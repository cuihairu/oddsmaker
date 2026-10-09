package io.oddsmaker.control.api;

import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.RiskCaseService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 风控案例回看 API：按游戏的案例列表/详情与人工解除封禁。
 * 判定状态机的正向流转由 RiskEventConsumer 按事件驱动，此处不提供人工改判入口；
 * 审核工作流（领取/完成/处置结论）走 ReviewQueueController。
 * 鉴权走 AccessGuard 行内风格（game:read / risk:manage，全部 game 级）。
 */
@RestController
public class RiskCaseController {

    private final RiskCaseService riskCaseService;
    private final AccessGuard accessGuard;

    public RiskCaseController(RiskCaseService riskCaseService, AccessGuard accessGuard) {
        this.riskCaseService = riskCaseService;
        this.accessGuard = accessGuard;
    }

    @GetMapping("/api/games/{gameId}/risk-cases")
    public ResponseEntity<List<Map<String, Object>>> list(@PathVariable String gameId,
                                                          @RequestParam(required = false) String status,
                                                          @RequestParam(required = false) String riskLevel,
                                                          @RequestParam(required = false) String ruleId,
                                                          @RequestParam(required = false) String disposition,
                                                          @RequestParam(defaultValue = "100") int limit) {
        accessGuard.requireGamePermission(gameId, "game:read");
        return ResponseEntity.ok(riskCaseService.list(gameId, status, riskLevel, ruleId, disposition, limit));
    }

    @GetMapping("/api/games/{gameId}/risk-cases/{caseId}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable String gameId,
                                                      @PathVariable String caseId) {
        accessGuard.requireGamePermission(gameId, "game:read");
        Map<String, Object> detail = riskCaseService.detail(gameId, caseId);
        return detail == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(detail);
    }

    /** 误杀处置：解除封禁（联动释放封禁名单记录），仅 BLOCK 已执行且未解除的案例可调 */
    @PostMapping("/api/games/{gameId}/risk-cases/{caseId}/unblock")
    public ResponseEntity<Map<String, Object>> unblock(@PathVariable String gameId,
                                                       @PathVariable String caseId,
                                                       @RequestBody(required = false) UnblockReq req) {
        accessGuard.requireGamePermission(gameId, "risk:manage");
        String reason = req != null && req.reason != null && !req.reason.isBlank() ? req.reason.trim() : null;
        if (reason == null) {
            throw new IllegalArgumentException("解除封禁须填写原因");
        }
        String by = req != null && req.by != null && !req.by.isBlank() ? req.by : currentOperator();
        riskCaseService.unblock(gameId, caseId, by, reason);
        return ResponseEntity.ok(Map.of("unblocked", true, "caseId", caseId));
    }

    public static class UnblockReq {
        public String reason;   // 必填：解除原因
        public String by;       // 操作人（缺省当前登录用户）
    }

    private String currentOperator() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
            .getContext().getAuthentication();
        return auth != null ? auth.getName() : "api";
    }
}
