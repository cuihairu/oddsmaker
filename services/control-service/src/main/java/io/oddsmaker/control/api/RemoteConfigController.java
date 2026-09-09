package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.RemoteConfigEntity;
import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.RemoteConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Remote Config API（LiveOps 联动）。
 * 运营端：/api/games/{gameId}/remote-configs（列表/创建/更新/删除）；
 * 游戏服/SDK：GET /api/remote-config/{gameId}?environment=（生效配置 + 聚合版本）。
 */
@RestController
public class RemoteConfigController {

    private final RemoteConfigService remoteConfigService;
    private final AccessGuard accessGuard;

    public RemoteConfigController(RemoteConfigService remoteConfigService, AccessGuard accessGuard) {
        this.remoteConfigService = remoteConfigService;
        this.accessGuard = accessGuard;
    }

    @GetMapping("/api/games/{gameId}/remote-configs")
    public ResponseEntity<List<RemoteConfigEntity>> list(@PathVariable String gameId,
                                                         @RequestParam(value = "environment", required = false) String environment) {
        accessGuard.requireGamePermission(gameId, "game:read");
        return ResponseEntity.ok(remoteConfigService.list(gameId, environment));
    }

    @PostMapping("/api/games/{gameId}/remote-configs")
    public ResponseEntity<?> create(@PathVariable String gameId, @RequestBody RemoteConfigEntity body) {
        try {
            accessGuard.requireGamePermission(gameId, "game:update");
            body.gameId = gameId;
            return ResponseEntity.ok(remoteConfigService.create(body, currentOperator()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    public static class UpdateRequest {
        public String configValue;
        public RemoteConfigEntity.Status status;
        public String description;
    }

    @PutMapping("/api/games/{gameId}/remote-configs/{id}")
    public ResponseEntity<?> update(@PathVariable String gameId, @PathVariable String id,
                                    @RequestBody UpdateRequest request) {
        try {
            accessGuard.requireGamePermission(gameId, "game:update");
            RemoteConfigEntity config = remoteConfigService.get(id);
            if (!config.gameId.equals(gameId)) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(remoteConfigService.update(
                id, request.configValue, request.status, request.description, currentOperator()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @DeleteMapping("/api/games/{gameId}/remote-configs/{id}")
    public ResponseEntity<?> delete(@PathVariable String gameId, @PathVariable String id) {
        accessGuard.requireGamePermission(gameId, "game:update");
        RemoteConfigEntity config = remoteConfigService.get(id);
        if (!config.gameId.equals(gameId)) {
            return ResponseEntity.notFound().build();
        }
        remoteConfigService.delete(id);
        return ResponseEntity.ok().build();
    }

    /** SDK/游戏服拉取生效配置：环境特定覆盖全环境，返回聚合版本供增量判断 */
    @GetMapping("/api/remote-config/{gameId}")
    public ResponseEntity<Map<String, Object>> resolve(@PathVariable String gameId,
                                                       @RequestParam(value = "environment", required = false) String environment,
                                                       @RequestParam(value = "ifNoneMatchVersion", required = false) Long ifNoneMatchVersion) {
        accessGuard.requireGamePermission(gameId, "game:read");
        Map<String, Object> resolved = remoteConfigService.resolve(gameId, environment);
        if (ifNoneMatchVersion != null && ifNoneMatchVersion.equals(resolved.get("version"))) {
            return ResponseEntity.status(304).build();
        }
        return ResponseEntity.ok(resolved);
    }

    private String currentOperator() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
            .getContext().getAuthentication();
        return auth != null ? auth.getName() : "api";
    }
}
