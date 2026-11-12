package io.oddsmaker.control.api;

import io.oddsmaker.control.service.BlockListService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 内部 API 控制器 —— 供 Gateway 等内部服务调用，靠 AdminTokenFilter + x-admin-token 鉴权
 */
@RestController
@RequestMapping("/internal")
public class InternalBlockListController {

    @Autowired
    private BlockListService blockListService;

    /**
     * 批量检查封禁状态。
     * Gateway 在 BatchController 逐条事件解析后调用，判断 device_id / user_id 是否被封禁。
     */
    @PostMapping("/block-lists/batch-check")
    public ResponseEntity<Map<String, Object>> batchCheck(@RequestBody BatchCheckRequest request) {
        if (request.gameId == null || request.targets == null || request.targets.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "gameId and targets required"));
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (BatchCheckTarget t : request.targets) {
            // 4 参版本带 environment：网关事件携带环境（风险自动封禁一律按 event.environment 落库，
            // environmentId 恒非 null），仅查全局封禁会漏掉环境维度封禁→风控自动封禁在接入层失效。
            boolean blocked = blockListService.isBlocked(request.gameId, t.environment, t.targetType, t.targetValue);
            Map<String, Object> item = new HashMap<>();
            item.put("targetType", t.targetType);
            item.put("targetValue", t.targetValue);
            item.put("environment", t.environment);
            item.put("blocked", blocked);
            results.add(item);
        }

        return ResponseEntity.ok(Map.of("results", results));
    }

    public static class BatchCheckRequest {
        public String gameId;
        public List<BatchCheckTarget> targets;
    }

    public static class BatchCheckTarget {
        public String targetType;  // device_id, player_id, user_id
        public String targetValue;
        public String environment;  // 网关事件环境；null 时仅命中全局封禁
    }
}
