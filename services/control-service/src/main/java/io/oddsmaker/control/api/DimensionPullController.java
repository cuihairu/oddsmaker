package io.oddsmaker.control.api;

import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.DimensionPullService;
import io.oddsmaker.control.service.DimensionPullService.PullConfigUpsert;
import io.oddsmaker.control.service.DimensionPullService.PullRunResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 维度同步 HTTP Pull 配置 API（dimension-sync.md HTTP Pull 链路）。
 * GET    /api/games/{gameId}/dimension-pulls          列出该游戏全部 Pull 配置（dimension:read）
 * POST   /api/games/{gameId}/dimension-pulls          建档（environment/sourceKey/endpoint/credential 必填，
 *                                                     credential AES-GCM 加密托管，响应不回显）（dimension:manage）
 * PUT    /api/games/{gameId}/dimension-pulls/{id}     更新（credential 缺省=保留既有凭证）（dimension:manage）
 * DELETE /api/games/{gameId}/dimension-pulls/{id}     删除（dimension:manage）
 * POST   /api/games/{gameId}/dimension-pulls/{id}/run 手动触发一轮拉取（dimension:manage）
 * 拉取进度同步落 dimension_sync_status（source_type=pull），经既有 /api/dimensions/sync-status 可观测。
 */
@RestController
@RequestMapping("/api/games/{gameId}/dimension-pulls")
public class DimensionPullController {

    private final DimensionPullService pullService;
    private final AccessGuard accessGuard;

    public DimensionPullController(DimensionPullService pullService, AccessGuard accessGuard) {
        this.pullService = pullService;
        this.accessGuard = accessGuard;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(@PathVariable String gameId) {
        accessGuard.requireGamePermission(gameId, "dimension:read");
        return ResponseEntity.ok(pullService.list(gameId));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@PathVariable String gameId,
                                                      @RequestBody PullConfigUpsert req) {
        accessGuard.requireGamePermission(gameId, "dimension:manage");
        return ResponseEntity.ok(pullService.create(gameId, req));
    }

    @PutMapping("/{pullId}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable String gameId,
                                                      @PathVariable String pullId,
                                                      @RequestBody PullConfigUpsert req) {
        accessGuard.requireGamePermission(gameId, "dimension:manage");
        return ResponseEntity.ok(pullService.update(gameId, pullId, req));
    }

    @DeleteMapping("/{pullId}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String gameId,
                                                      @PathVariable String pullId) {
        accessGuard.requireGamePermission(gameId, "dimension:manage");
        pullService.delete(gameId, pullId);
        return ResponseEntity.ok(Map.of("deleted", true));
    }

    @PostMapping("/{pullId}/run")
    public ResponseEntity<PullRunResult> run(@PathVariable String gameId,
                                             @PathVariable String pullId) {
        accessGuard.requireGamePermission(gameId, "dimension:manage");
        return ResponseEntity.ok(pullService.runNow(gameId, pullId));
    }
}
