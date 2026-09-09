package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.PlayerExportJobEntity;
import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.PlayerExportService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 玩家数据导出 API（客服/运营，敏感操作全程审计）。
 * 创建/列表：/api/player-exports；详情与下载：/api/player-exports/{jobId}[/download]。
 * 文件生成由后台 sweep 异步完成，COMPLETED 后可下载，到期自动清理。
 */
@RestController
public class PlayerExportController {

    private final PlayerExportService playerExportService;
    private final AccessGuard accessGuard;

    public PlayerExportController(PlayerExportService playerExportService, AccessGuard accessGuard) {
        this.playerExportService = playerExportService;
        this.accessGuard = accessGuard;
    }

    /** 创建导出任务：{gameId, playerId, environmentId?, format?(json/csv), sections?(分区子集)} */
    @PostMapping("/api/player-exports")
    public ResponseEntity<?> create(@RequestBody CreateRequest request) {
        try {
            accessGuard.requireGamePermission(request.gameId, "game:read");
            PlayerExportJobEntity job = playerExportService.create(
                request.gameId, request.playerId, request.environmentId,
                request.format, request.sections, currentOperator());
            return ResponseEntity.ok(job);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /** 导出任务列表：playerId 缺省返回整个游戏 */
    @GetMapping("/api/player-exports")
    public ResponseEntity<List<PlayerExportJobEntity>> list(@RequestParam String gameId,
                                                            @RequestParam(value = "playerId", required = false) String playerId) {
        accessGuard.requireGamePermission(gameId, "game:read");
        return ResponseEntity.ok(playerExportService.list(gameId, playerId));
    }

    @GetMapping("/api/player-exports/{jobId}")
    public ResponseEntity<PlayerExportJobEntity> get(@PathVariable String jobId) {
        try {
            PlayerExportJobEntity job = playerExportService.get(jobId);
            accessGuard.requireGamePermission(job.gameId, "game:read");
            return ResponseEntity.ok(job);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /** 下载导出文件：json 单文件 / csv 分区 zip；未完成或已过期返回 409 */
    @GetMapping("/api/player-exports/{jobId}/download")
    public ResponseEntity<byte[]> download(@PathVariable String jobId) {
        PlayerExportJobEntity job;
        try {
            job = playerExportService.get(jobId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
        accessGuard.requireGamePermission(job.gameId, "game:read");
        try {
            PlayerExportService.ExportedFile file = playerExportService.download(jobId);
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.fileName() + "\"")
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(e.getMessage().getBytes(StandardCharsets.UTF_8));
        }
    }

    private String currentOperator() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
            .getContext().getAuthentication();
        return auth != null ? auth.getName() : "api";
    }

    // Request DTO

    public static class CreateRequest {
        public String gameId;
        public String playerId;
        public String environmentId;
        public String format;
        public List<String> sections;
    }
}
