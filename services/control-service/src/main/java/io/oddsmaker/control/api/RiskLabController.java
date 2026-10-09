package io.oddsmaker.control.api;

import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.RiskLabService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 策略实验室 API（调研「样本管理 + 策略实验室」落点，V0.2 复盘聚合）：
 * 规则维度复盘统计；样本下钻走案例回看列表（risk-cases 的 ruleId/disposition 过滤）。
 * 只读面，鉴权与案例回看一致（game:read）。
 */
@RestController
public class RiskLabController {

    private final RiskLabService riskLabService;
    private final AccessGuard accessGuard;

    public RiskLabController(RiskLabService riskLabService, AccessGuard accessGuard) {
        this.riskLabService = riskLabService;
        this.accessGuard = accessGuard;
    }

    @GetMapping("/api/games/{gameId}/risk-lab/rule-stats")
    public Map<String, Object> ruleStats(@PathVariable String gameId) {
        accessGuard.requireGamePermission(gameId, "game:read");
        return riskLabService.ruleStats(gameId);
    }
}
