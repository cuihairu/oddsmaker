package io.oddsmaker.control.api;

import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.RiskLabReplayService;
import io.oddsmaker.control.service.RiskLabService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 策略实验室 API（调研「样本管理 + 策略实验室」落点）：
 * V0.2 规则维度复盘统计；样本下钻走案例回看列表（risk-cases 的 ruleId/disposition 过滤）；
 * V0.3 试算回放 dry-run（样本批量命中打分，即传即算不落库）。
 * 鉴权与案例回看一致（game:read）。
 */
@RestController
public class RiskLabController {

    private final RiskLabService riskLabService;
    private final RiskLabReplayService riskLabReplayService;
    private final AccessGuard accessGuard;

    public RiskLabController(RiskLabService riskLabService, RiskLabReplayService riskLabReplayService,
                             AccessGuard accessGuard) {
        this.riskLabService = riskLabService;
        this.riskLabReplayService = riskLabReplayService;
        this.accessGuard = accessGuard;
    }

    @GetMapping("/api/games/{gameId}/risk-lab/rule-stats")
    public Map<String, Object> ruleStats(@PathVariable String gameId) {
        accessGuard.requireGamePermission(gameId, "game:read");
        return riskLabService.ruleStats(gameId);
    }

    @PostMapping("/api/games/{gameId}/risk-lab/replay")
    public Map<String, Object> replay(@PathVariable String gameId, @RequestBody ReplayRequest request) {
        accessGuard.requireGamePermission(gameId, "game:read");
        return riskLabReplayService.dryRun(gameId, request.samples, request.ruleIds);
    }

    /** 试算请求体：samples 样本事件列表（必填 1~500 条），ruleIds 可选规则过滤 */
    public static class ReplayRequest {
        public List<Map<String, Object>> samples;
        public List<String> ruleIds;
    }
}
