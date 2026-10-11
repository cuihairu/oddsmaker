package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.DimensionSyncStatusEntity;
import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.DimensionAgentConfigService;
import io.oddsmaker.control.service.DimensionSyncStatusService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 维度同步状态 API（P4 横向）
 * GET  /api/dimensions/sync-status?gameId=&environment=  查询各同步源位点/心跳/同步延迟（dimension:read）
 * POST /api/dimensions/sync-status                       Agent 位点上报（dimension:manage；Agent 用 x-admin-token 认证，
 *        与 risk-job 拉规则同一鉴权面）。
 * GET  /api/dimensions/agent-configs/resolved?gameId=&environment=&agentKey=  Agent 取配置（dimension:read，
 *        同 x-admin-token 鉴权面；返回可 Properties.load 的完整文本，注入 game.id/game.environment/
 *        status.source-key 并解密合并密钥键；任何非 200 Agent 端回落本地 agent.properties）。
 * 上报为高频机器遥测：不写审计日志（避免心跳刷屏；规则/处置类变更仍走审计）。
 */
@RestController
@RequestMapping("/api/dimensions")
public class DimensionSyncController {

    private final DimensionSyncStatusService statusService;
    private final DimensionAgentConfigService agentConfigService;
    private final AccessGuard accessGuard;

    public DimensionSyncController(DimensionSyncStatusService statusService,
                                   DimensionAgentConfigService agentConfigService,
                                   AccessGuard accessGuard) {
        this.statusService = statusService;
        this.agentConfigService = agentConfigService;
        this.accessGuard = accessGuard;
    }

    @GetMapping("/sync-status")
    public ResponseEntity<List<Map<String, Object>>> syncStatus(
            @RequestParam String gameId,
            @RequestParam(required = false) String environment) {
        accessGuard.requireGamePermission(gameId, "dimension:read");
        return ResponseEntity.ok(statusService.list(gameId, environment).stream()
                .map(statusService::toResp)
                .collect(Collectors.toList()));
    }

    @PostMapping("/sync-status")
    public ResponseEntity<Map<String, Object>> report(@RequestBody DimensionSyncStatusService.StatusUpsert req) {
        accessGuard.requireGamePermission(req.gameId, "dimension:manage");
        DimensionSyncStatusEntity saved = statusService.upsert(req);
        return ResponseEntity.ok(statusService.toResp(saved));
    }

    /** Agent 取配置（机器面）：响应体即 properties 文本，Agent 端 load 后与本地文件合并。 */
    @GetMapping(value = "/agent-configs/resolved", produces = "text/plain;charset=UTF-8")
    public ResponseEntity<String> resolved(@RequestParam String gameId,
                                           @RequestParam String environment,
                                           @RequestParam String agentKey) {
        accessGuard.requireGamePermission(gameId, "dimension:read");
        return ResponseEntity.ok(agentConfigService.resolve(gameId, environment, agentKey));
    }
}
