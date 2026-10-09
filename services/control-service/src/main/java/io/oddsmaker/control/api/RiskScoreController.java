package io.oddsmaker.control.api;

import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.RiskScoreService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 主体累计风险分 API（B6 边界闭合）：读 CH risk_scores 主体最新快照。
 * 与案例回看/策略实验室一致走 game:read。
 */
@RestController
public class RiskScoreController {

    private final RiskScoreService riskScoreService;
    private final AccessGuard accessGuard;

    public RiskScoreController(RiskScoreService riskScoreService, AccessGuard accessGuard) {
        this.riskScoreService = riskScoreService;
        this.accessGuard = accessGuard;
    }

    @GetMapping("/api/games/{gameId}/risk-scores")
    public Map<String, Object> latest(@PathVariable String gameId,
                                      @RequestParam String subjectType,
                                      @RequestParam String subjectId) {
        accessGuard.requireGamePermission(gameId, "game:read");
        return riskScoreService.latest(gameId, subjectType, subjectId);
    }
}
