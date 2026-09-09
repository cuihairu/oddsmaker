package io.oddsmaker.control.api;

import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.PaymentFunnelService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 付费漏斗分析 API（ClickHouse 数据源：events + v_user_first_seen）。
 * - /{gameId}/funnel 窗口期新增用户 注册→首充→二充→月留存 转化漏斗（总体 + 按 cohort 趋势）。
 */
@RestController
@RequestMapping("/api/payment-metrics")
public class PaymentFunnelController {

    private final PaymentFunnelService paymentFunnelService;
    private final AccessGuard accessGuard;

    public PaymentFunnelController(PaymentFunnelService paymentFunnelService, AccessGuard accessGuard) {
        this.paymentFunnelService = paymentFunnelService;
        this.accessGuard = accessGuard;
    }

    @GetMapping("/{gameId}/funnel")
    public ResponseEntity<Map<String, Object>> funnel(
            @PathVariable String gameId,
            @RequestParam(value = "environment", required = false) String environment,
            @RequestParam(value = "days", required = false) Integer days) {
        accessGuard.requireGamePermission(gameId, "game:read");
        return ResponseEntity.ok(paymentFunnelService.funnel(gameId, environment, days));
    }
}
