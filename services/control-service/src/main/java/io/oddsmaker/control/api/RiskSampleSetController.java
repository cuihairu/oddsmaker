package io.oddsmaker.control.api;

import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.RiskSampleSetService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 策略实验室样本集 API（V0.4）：命名留档样本批次，供试算回放反复载入对比。
 * 查询与案例回看/试算一致（game:read）；留档写入与删除走 risk:manage。
 */
@RestController
public class RiskSampleSetController {

    private final RiskSampleSetService riskSampleSetService;
    private final AccessGuard accessGuard;

    public RiskSampleSetController(RiskSampleSetService riskSampleSetService, AccessGuard accessGuard) {
        this.riskSampleSetService = riskSampleSetService;
        this.accessGuard = accessGuard;
    }

    @GetMapping("/api/games/{gameId}/risk-lab/sample-sets")
    public List<Map<String, Object>> list(@PathVariable String gameId) {
        accessGuard.requireGamePermission(gameId, "game:read");
        return riskSampleSetService.list(gameId);
    }

    @PostMapping("/api/games/{gameId}/risk-lab/sample-sets")
    public Map<String, Object> create(@PathVariable String gameId, @RequestBody CreateRequest request) {
        accessGuard.requireGamePermission(gameId, "risk:manage");
        return riskSampleSetService.create(gameId, request.name, request.description,
                request.samples, currentOperator());
    }

    @GetMapping("/api/games/{gameId}/risk-lab/sample-sets/{id}")
    public Map<String, Object> get(@PathVariable String gameId, @PathVariable String id) {
        accessGuard.requireGamePermission(gameId, "game:read");
        return riskSampleSetService.get(gameId, id);
    }

    @DeleteMapping("/api/games/{gameId}/risk-lab/sample-sets/{id}")
    public Map<String, Object> delete(@PathVariable String gameId, @PathVariable String id) {
        accessGuard.requireGamePermission(gameId, "risk:manage");
        boolean deleted = riskSampleSetService.delete(gameId, id, currentOperator());
        return Map.of("deleted", deleted);
    }

    /** 新建请求体：name 必填（同游戏唯一）、samples 必填 1~500 条、description 可选 */
    public static class CreateRequest {
        public String name;
        public String description;
        public List<Map<String, Object>> samples;
    }

    private String currentOperator() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
            .getContext().getAuthentication();
        return auth != null ? auth.getName() : "api";
    }
}
