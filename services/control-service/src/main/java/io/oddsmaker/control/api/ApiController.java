package io.oddsmaker.control.api;

import io.oddsmaker.control.dto.EnvironmentDTO;
import io.oddsmaker.control.dto.ExperimentConfigDTO;
import io.oddsmaker.control.dto.ExperimentDTO;
import io.oddsmaker.control.dto.GameDTO;
import io.oddsmaker.control.dto.StorageProfileDTO;
import io.oddsmaker.control.jpa.GameEntity;
import io.oddsmaker.control.jpa.GameRepo;
import io.oddsmaker.control.service.ExperimentService;
import io.oddsmaker.control.service.GameService;
import io.oddsmaker.control.service.StorageProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * API Controller - 单公司多游戏模型
 * 对外仅暴露 Game → Environment → ApiKey。
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Core API", description = "核心API接口 - 游戏、环境、API Key管理")
public class ApiController {
    private final ControlService svc;
    private final GameService gameService;
    private final ExperimentService experimentService;
    private final StorageProfileService storageProfileService;
    private final GameRepo gameRepo;
    public ApiController(ControlService svc, GameService gameService, ExperimentService experimentService,
                        StorageProfileService storageProfileService, GameRepo gameRepo) {
        this.svc = svc;
        this.gameService = gameService;
        this.experimentService = experimentService;
        this.storageProfileService = storageProfileService;
        this.gameRepo = gameRepo;
    }

    @PostMapping("/keys")
    public Models.ApiKeyResp createKey(@RequestBody Models.CreateKeyReq req) {
        return svc.createKey(req.gameId, req.environmentId, req.name, req.keyRole);
    }

    @PostMapping("/api-keys")
    public Models.ApiKeyResp createApiKey(@RequestBody Models.CreateKeyReq req) {
        return createKey(req);
    }

    @GetMapping("/keys/{apiKey}")
    public ResponseEntity<Models.KeyDetailResp> getKey(@PathVariable String apiKey) {
        var r = svc.getKey(apiKey);
        if (r == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(r);
    }

    @GetMapping("/api-keys/{apiKey}")
    public ResponseEntity<Models.KeyDetailResp> getApiKey(@PathVariable String apiKey) {
        return getKey(apiKey);
    }

    @GetMapping("/keys")
    public Object listKeys(@RequestParam(value = "q", required = false) String q,
                           @RequestParam(value = "gameId", required = false) String gameId,
                           @RequestParam(value = "environmentId", required = false) String environmentId,
                           @RequestParam(value = "page", required = false) Integer page,
                           @RequestParam(value = "size", required = false) Integer size) {
        if (page == null && size == null && q == null && gameId == null && environmentId == null) {
            return svc.listKeys();
        }
        int p = page == null ? 0 : Math.max(0, page);
        int s = size == null ? 50 : Math.max(1, size);
        return svc.searchKeys(gameId, environmentId, q, p, s);
    }

    @GetMapping("/api-keys")
    public Object listApiKeys(@RequestParam(value = "q", required = false) String q,
                              @RequestParam(value = "gameId", required = false) String gameId,
                              @RequestParam(value = "environmentId", required = false) String environmentId,
                              @RequestParam(value = "page", required = false) Integer page,
                              @RequestParam(value = "size", required = false) Integer size) {
        return listKeys(q, gameId, environmentId, page, size);
    }

    public static class UpdatePolicyReq {
        public Integer rpm; public Integer ipRpm; public List<String> propsAllowlist;
        public String piiEmail; public String piiPhone; public String piiIp;
        public List<String> denyKeys; public List<String> maskKeys;
    }

    public static class BatchDeleteReq { public List<String> apiKeys; }

    @PutMapping("/keys/{apiKey}/policy")
    public ResponseEntity<Models.KeyDetailResp> updatePolicy(@PathVariable String apiKey, @RequestBody UpdatePolicyReq req) {
        var r = new Models.KeyDetailResp();
        r.rpm = req.rpm; r.ipRpm = req.ipRpm; r.propsAllowlist = req.propsAllowlist; r.piiEmail = req.piiEmail; r.piiPhone = req.piiPhone; r.piiIp = req.piiIp; r.denyKeys = req.denyKeys; r.maskKeys = req.maskKeys;
        var out = svc.updatePolicy(apiKey, r);
        if (out == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(out);
    }

    @PutMapping("/api-keys/{apiKey}")
    public ResponseEntity<Models.KeyDetailResp> updateApiKey(@PathVariable String apiKey, @RequestBody UpdatePolicyReq req) {
        return updatePolicy(apiKey, req);
    }

    @DeleteMapping("/keys/{apiKey}")
    public ResponseEntity<Map<String,Object>> deleteKey(@PathVariable String apiKey) {
        boolean ok = svc.deleteKey(apiKey);
        return ok ? ResponseEntity.ok(Map.of("deleted", true)) : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/api-keys/{apiKey}")
    public ResponseEntity<Map<String,Object>> deleteApiKey(@PathVariable String apiKey) {
        return deleteKey(apiKey);
    }

    @PostMapping("/keys/batch-delete")
    public ResponseEntity<Map<String,Object>> deleteKeys(@RequestBody BatchDeleteReq req) {
        long n = svc.deleteKeys(req.apiKeys);
        return ResponseEntity.ok(Map.of("deleted", n));
    }

    @GetMapping("/games")
    @Operation(summary = "获取游戏列表", description = "分页获取游戏列表，支持搜索")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "成功获取游戏列表",
            content = @Content(schema = @Schema(implementation = GameDTO.class))),
        @ApiResponse(responseCode = "401", description = "未授权")
    })
    public Object listGames(@Parameter(description = "搜索关键词") @RequestParam(value = "q", required = false) String q,
                            @RequestParam(value = "page", required = false) Integer page,
                            @RequestParam(value = "size", required = false) Integer size,
                            @RequestParam(value = "sort", required = false) String sort) {
        int p = page == null ? 0 : Math.max(0, page);
        int s = size == null ? 50 : Math.max(1, size);
        String sortField = (sort == null || sort.isBlank()) ? "name" : sort;
        var pageable = PageRequest.of(p, s, Sort.by(sortField).ascending());
        var result = (q == null || q.isBlank())
            ? gameService.getGames(pageable)
            : gameService.searchGames(q, pageable);
        return new ControlService.Paged<>(result.getContent(), result.getTotalElements());
    }

    /**
     * 游戏统计（控制面 Dashboard 卡片：totalGames/liveGames）。
     * 前端此前调用此路径但后端无端点，Dashboard 一直 404 空数据。
     */
    @GetMapping("/games/statistics")
    @Operation(summary = "获取游戏统计", description = "游戏总数与上线数统计")
    public ResponseEntity<Map<String, Object>> getGameStatistics() {
        return ResponseEntity.ok(Map.of(
            "totalGames", gameRepo.countByDeletedAtIsNull(),
            "liveGames", gameRepo.countByStatusAndDeletedAtIsNull(GameEntity.GameStatus.LIVE)
        ));
    }

    @PostMapping("/games")
    @Operation(summary = "创建游戏", description = "创建新的游戏产品")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "游戏创建成功",
            content = @Content(schema = @Schema(implementation = GameDTO.class))),
        @ApiResponse(responseCode = "400", description = "请求参数无效"),
        @ApiResponse(responseCode = "401", description = "未授权"),
        @ApiResponse(responseCode = "409", description = "游戏ID已存在")
    })
    public GameDTO createGame(@Valid @RequestBody GameDTO dto) {
        return gameService.createGame(dto);
    }

    @GetMapping("/games/{gameId}")
    @Operation(summary = "获取游戏详情", description = "根据游戏ID获取游戏详细信息")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "成功获取游戏详情",
            content = @Content(schema = @Schema(implementation = GameDTO.class))),
        @ApiResponse(responseCode = "404", description = "游戏不存在")
    })
    public ResponseEntity<GameDTO> getGame(@Parameter(description = "游戏ID") @PathVariable String gameId) {
        return gameService.getGame(gameId)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/games/{gameId}")
    public ResponseEntity<GameDTO> updateGame(@PathVariable String gameId,
                                              @Valid @RequestBody GameDTO dto) {
        return ResponseEntity.ok(gameService.updateGame(gameId, dto));
    }

    @DeleteMapping("/games/{gameId}")
    public ResponseEntity<Map<String, Object>> deleteGame(@PathVariable String gameId) {
        gameService.deleteGame(gameId);
        return ResponseEntity.ok(Map.of("deleted", true, "gameId", gameId));
    }

    @PostMapping("/games/{gameId}/publish")
    public ResponseEntity<GameDTO> publishGame(@PathVariable String gameId) {
        return ResponseEntity.ok(gameService.publishGame(gameId));
    }

    @PostMapping("/games/{gameId}/unpublish")
    public ResponseEntity<GameDTO> unpublishGame(@PathVariable String gameId) {
        return ResponseEntity.ok(gameService.unpublishGame(gameId));
    }

    @GetMapping("/games/{gameId}/environments")
    public List<EnvironmentDTO> listEnvironments(@PathVariable String gameId) {
        return gameService.listEnvironments(gameId);
    }

    @PostMapping("/games/{gameId}/environments")
    public EnvironmentDTO createEnvironment(@PathVariable String gameId,
                                            @Valid @RequestBody EnvironmentDTO dto) {
        return gameService.createEnvironment(gameId, dto);
    }

    @GetMapping("/games/{gameId}/environments/{environmentName}")
    public ResponseEntity<EnvironmentDTO> getEnvironment(@PathVariable String gameId,
                                                         @PathVariable String environmentName) {
        return gameService.getEnvironment(gameId, environmentName)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/games/{gameId}/environments/{environmentName}")
    public ResponseEntity<EnvironmentDTO> updateEnvironment(@PathVariable String gameId,
                                                            @PathVariable String environmentName,
                                                            @RequestBody EnvironmentDTO dto) {
        return ResponseEntity.ok(gameService.updateEnvironment(gameId, environmentName, dto));
    }

    @DeleteMapping("/games/{gameId}/environments/{environmentName}")
    public ResponseEntity<Map<String, Object>> deleteEnvironment(@PathVariable String gameId,
                                                                   @PathVariable String environmentName) {
        try {
            gameService.deleteEnvironment(gameId, environmentName);
            return ResponseEntity.ok(Map.of("deleted", true, "gameId", gameId, "environment", environmentName));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException ex) {
            // Environment is in use - return 409 Conflict
            return ResponseEntity.status(409).body(Map.of(
                "deleted", false,
                "gameId", gameId,
                "environment", environmentName,
                "error", ex.getMessage()
            ));
        }
    }

    @GetMapping("/experiments")
    public ControlService.Paged<ExperimentDTO> listExperiments(@RequestParam(value = "gameId", required = false) String gameId,
                                                               @RequestParam(value = "environmentId", required = false) String environmentId,
                                                               @RequestParam(value = "environment", required = false) String environment,
                                                               @RequestParam(value = "status", required = false) String status,
                                                               @RequestParam(value = "page", required = false) Integer page,
                                                               @RequestParam(value = "size", required = false) Integer size) {
        return experimentService.listExperiments(
            gameId,
            environmentId,
            environment,
            status,
            page == null ? 0 : page,
            size == null ? 50 : size
        );
    }

    @PostMapping("/experiments")
    public ExperimentDTO createExperiment(@Valid @RequestBody ExperimentDTO dto) {
        return experimentService.createExperiment(dto);
    }

    @GetMapping("/experiments/{id}")
    public ResponseEntity<ExperimentDTO> getExperiment(@PathVariable String id) {
        return experimentService.getExperiment(id)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/experiments/{id}")
    public ResponseEntity<ExperimentDTO> updateExperiment(@PathVariable String id,
                                                          @RequestBody ExperimentDTO dto) {
        return ResponseEntity.ok(experimentService.updateExperiment(id, dto));
    }

    @PostMapping("/experiments/{id}/publish")
    public ResponseEntity<ExperimentDTO> publishExperiment(@PathVariable String id) {
        return ResponseEntity.ok(experimentService.publishExperiment(id));
    }

    @PostMapping("/experiments/{id}/pause")
    public ResponseEntity<ExperimentDTO> pauseExperiment(@PathVariable String id) {
        return ResponseEntity.ok(experimentService.pauseExperiment(id));
    }

    @GetMapping("/experiments/{id}/assign")
    @Operation(summary = "服务端分流", description = "为主体分配实验变体（确定性哈希，仅 running 实验分流）")
    public ResponseEntity<Map<String, Object>> assignExperiment(@PathVariable String id,
                                                                @RequestParam String subjectId) {
        String variant = experimentService.assign(id, subjectId);
        if (variant == null) {
            // 非 running 或无有效变体：返回 control 兜底，不抛错，保证游戏主流程不受实验平台影响
            return ResponseEntity.ok(Map.of("experimentId", id, "subjectId", subjectId, "variant", "control", "assigned", false));
        }
        return ResponseEntity.ok(Map.of("experimentId", id, "subjectId", subjectId, "variant", variant, "assigned", true));
    }

    @DeleteMapping("/experiments/{id}")
    public ResponseEntity<Map<String, Object>> deleteExperiment(@PathVariable String id) {
        boolean deleted = experimentService.deleteExperiment(id);
        return deleted ? ResponseEntity.ok(Map.of("deleted", true, "id", id)) : ResponseEntity.notFound().build();
    }

    @GetMapping("/config/{gameId}/{environment}")
    public List<ExperimentConfigDTO> getExperimentConfig(@PathVariable String gameId,
                                                         @PathVariable String environment) {
        return experimentService.getRunningConfig(gameId, environment);
    }

    @GetMapping("/storage-profiles")
    public List<StorageProfileDTO> listStorageProfiles() {
        return storageProfileService.getStorageProfiles();
    }

    @GetMapping("/storage-profiles/{profileId}")
    public ResponseEntity<StorageProfileDTO> getStorageProfile(@PathVariable String profileId) {
        return storageProfileService.getStorageProfile(profileId)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/storage-profiles")
    public StorageProfileDTO createStorageProfile(@Valid @RequestBody StorageProfileDTO dto) {
        return storageProfileService.createStorageProfile(dto);
    }

    @PutMapping("/storage-profiles/{profileId}")
    public ResponseEntity<StorageProfileDTO> updateStorageProfile(@PathVariable String profileId,
                                                                   @RequestBody StorageProfileDTO dto) {
        try {
            return ResponseEntity.ok(storageProfileService.updateStorageProfile(profileId, dto));
        } catch (IllegalArgumentException ex) {
            if (ex.getMessage() != null && ex.getMessage().startsWith("Storage profile not found")) {
                return ResponseEntity.notFound().build();
            }
            throw ex;
        }
    }

    @DeleteMapping("/storage-profiles/{profileId}")
    public ResponseEntity<Map<String, Object>> deleteStorageProfile(@PathVariable String profileId) {
        try {
            storageProfileService.deleteStorageProfile(profileId);
            return ResponseEntity.ok(Map.of("deleted", true, "profileId", profileId));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException ex) {
            // Profile is in use - return 409 Conflict
            return ResponseEntity.status(409).body(Map.of(
                "deleted", false,
                "profileId", profileId,
                "error", ex.getMessage()
            ));
        }
    }
}
