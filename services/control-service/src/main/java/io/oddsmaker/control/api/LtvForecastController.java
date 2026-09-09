package io.oddsmaker.control.api;

import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.LtvForecastService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * pLTV 预测 API（ClickHouse 数据源：v_ltv_by_cohort_day + v_user_first_seen）。
 * - /{gameId}/pltv D7→D30 乘数拟合与各 cohort 预测 D30 ARPU。
 */
@RestController
@RequestMapping("/api/ltv-metrics")
public class LtvForecastController {

    private final LtvForecastService ltvForecastService;
    private final AccessGuard accessGuard;

    public LtvForecastController(LtvForecastService ltvForecastService, AccessGuard accessGuard) {
        this.ltvForecastService = ltvForecastService;
        this.accessGuard = accessGuard;
    }

    @GetMapping("/{gameId}/pltv")
    public ResponseEntity<Map<String, Object>> pltv(
            @PathVariable String gameId,
            @RequestParam(value = "environment", required = false) String environment,
            @RequestParam(value = "days", required = false) Integer days) {
        accessGuard.requireGamePermission(gameId, "game:read");
        return ResponseEntity.ok(ltvForecastService.pltv(gameId, environment, days));
    }
}
