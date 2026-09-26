package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.MlArtifactEntity;
import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.MlArtifactRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * ml 训练产物注册 API（P4.4 收尾）。
 * 接收 ml/（oddsmaker-ml）训练管线的版本化 JSON 产物并注册（校验 + 落库 + 审计），
 * 供批量打分优先消费；查询已注册版本。
 * 鉴权：注册 ml:manage，查询 ml:read（V0.9.8 权限种子，AccessGuard 行内风格）。
 */
@RestController
@RequestMapping("/api/ml-artifacts")
public class MlArtifactController {

    private final MlArtifactRegistry registry;
    private final AccessGuard accessGuard;

    public MlArtifactController(MlArtifactRegistry registry, AccessGuard accessGuard) {
        this.registry = registry;
        this.accessGuard = accessGuard;
    }

    /** 注册产物：body 为 {gameId, createdBy, artifact:{...ml 产物 JSON 全文...}} */
    @PostMapping
    public ResponseEntity<MlArtifactEntity> register(@RequestBody RegisterRequest request) {
        accessGuard.requireGamePermission(request.gameId, "ml:manage");
        MlArtifactEntity entity = registry.register(request.gameId, request.artifact, request.createdBy);
        return ResponseEntity.ok(entity);
    }

    /** 已注册版本列表（modelType 可选；空则返回该游戏全部类型） */
    @GetMapping("/game/{gameId}")
    public ResponseEntity<List<MlArtifactEntity>> listVersions(
            @PathVariable String gameId,
            @RequestParam(value = "modelType", required = false) String modelType) {
        accessGuard.requireGamePermission(gameId, "ml:read");
        return ResponseEntity.ok(registry.listVersions(gameId, modelType));
    }

    /** 当前生效产物（该类型最新注册版本）；未注册返回 404 */
    @GetMapping("/game/{gameId}/active")
    public ResponseEntity<MlArtifactEntity> resolveActive(
            @PathVariable String gameId,
            @RequestParam("modelType") String modelType) {
        accessGuard.requireGamePermission(gameId, "ml:read");
        return registry.resolveActive(gameId, modelType)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public static class RegisterRequest {
        public String gameId;
        public Map<String, Object> artifact;
        public String createdBy;
    }
}
