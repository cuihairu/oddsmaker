package io.oddsmaker.control.api;

import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.DimensionAgentConfigService;
import io.oddsmaker.control.service.DimensionAgentConfigService.AgentConfigUpsert;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Sync Agent 属性面托管 API（dimension-sync.md「Agent 侧 agent.properties 的 Control 下发迁移」
 * 控制面半边）。每个 (game, environment, agentKey) 一条：props 非密钥文本 + secrets 密钥映射，
 * 密钥 AES-GCM 密文托管。Agent 取配置口在 DimensionSyncController 的
 * /api/dimensions/agent-configs/resolved（与 sync-status 同 Agent 面）。
 *
 * GET    /api/games/{gameId}/agent-configs        列出该游戏全部 Agent 配置档（密钥只回显键名）（dimension:read）
 * POST   /api/games/{gameId}/agent-configs        建档（environment/agentKey/props/secrets 必填，
 *                                                 secrets 含 gateway.api-key）（dimension:manage）
 * PUT    /api/games/{gameId}/agent-configs/{id}   更新（secrets 缺省=保留既有密钥；props 全量替换）（dimension:manage）
 * DELETE /api/games/{gameId}/agent-configs/{id}   删除（dimension:manage）
 */
@RestController
@RequestMapping("/api/games/{gameId}/agent-configs")
public class DimensionAgentConfigController {

    private final DimensionAgentConfigService configService;
    private final AccessGuard accessGuard;

    public DimensionAgentConfigController(DimensionAgentConfigService configService,
                                          AccessGuard accessGuard) {
        this.configService = configService;
        this.accessGuard = accessGuard;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(@PathVariable String gameId) {
        accessGuard.requireGamePermission(gameId, "dimension:read");
        return ResponseEntity.ok(configService.list(gameId));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@PathVariable String gameId,
                                                      @RequestBody AgentConfigUpsert req) {
        accessGuard.requireGamePermission(gameId, "dimension:manage");
        return ResponseEntity.ok(configService.create(gameId, req));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable String gameId,
                                                      @PathVariable String id,
                                                      @RequestBody AgentConfigUpsert req) {
        accessGuard.requireGamePermission(gameId, "dimension:manage");
        return ResponseEntity.ok(configService.update(gameId, id, req));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String gameId,
                                                      @PathVariable String id) {
        accessGuard.requireGamePermission(gameId, "dimension:manage");
        configService.delete(gameId, id);
        return ResponseEntity.ok(Map.of("deleted", true));
    }
}
