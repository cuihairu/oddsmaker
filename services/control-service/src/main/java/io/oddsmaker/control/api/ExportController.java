package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.ExportJobEntity;
import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.ExportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 数据导出API控制器
 * 提供数据导出管理的API接口；鉴权走 AccessGuard 行内风格（export:execute，全部 game 级）。
 * 历史形态为 @PreAuthorize hasAuthority('EXPORT_DATA:'+gameId) 拼接式，
 * 而全仓只签发 ROLE_* authority，方法安全开启后这些注解恒 403——故换成权限种子（V0.9.8）+ AccessGuard 解析。
 */
@RestController
@RequestMapping("/api/exports")
public class ExportController {

    @Autowired
    private ExportService exportService;

    @Autowired
    private AccessGuard accessGuard;

    /**
     * 创建导出任务
     */
    @PostMapping
    public ResponseEntity<ExportJobEntity> createExportJob(@RequestBody ExportRequest request) {
        accessGuard.requireGamePermission(request.gameId, "export:execute");
        ExportJobEntity job = exportService.createExportJob(
            request.gameId,
            request.environmentId,
            request.userId,
            request.exportType,
            request.startTime,
            request.endTime,
            request.exportFormat,
            request.filters,
            request.dataSource,
            request.columns,
            request.compression,
            request.notifyOnComplete,
            request.notificationEmail
        );
        return ResponseEntity.ok(job);
    }

    /**
     * 获取导出任务详情
     */
    @GetMapping("/{exportJobId}")
    public ResponseEntity<ExportJobEntity> getExportJob(
            @PathVariable String exportJobId,
            @RequestParam String gameId) {
        accessGuard.requireGamePermission(gameId, "export:execute");
        ExportJobEntity job = exportService.getExportJob(exportJobId);
        return ResponseEntity.ok(job);
    }

    /**
     * 获取用户的导出任务列表
     */
    @GetMapping("/user/{userId}")
    public ResponseEntity<List<ExportJobEntity>> getUserExports(
            @PathVariable String userId,
            @RequestParam String gameId) {
        accessGuard.requireGamePermission(gameId, "export:execute");
        List<ExportJobEntity> jobs = exportService.getUserExports(userId);
        return ResponseEntity.ok(jobs);
    }

    /**
     * 获取游戏的导出任务列表
     */
    @GetMapping("/game/{gameId}")
    public ResponseEntity<List<ExportJobEntity>> getGameExports(@PathVariable String gameId) {
        accessGuard.requireGamePermission(gameId, "export:execute");
        List<ExportJobEntity> jobs = exportService.getGameExports(gameId);
        return ResponseEntity.ok(jobs);
    }

    /**
     * 处理导出任务
     */
    @PostMapping("/{exportJobId}/process")
    public ResponseEntity<ExportJobEntity> processExportJob(
            @PathVariable String exportJobId,
            @RequestParam String gameId) {
        accessGuard.requireGamePermission(gameId, "export:execute");
        ExportJobEntity job = exportService.processExportJob(exportJobId);
        return ResponseEntity.ok(job);
    }

    /**
     * 取消导出任务
     */
    @PostMapping("/{exportJobId}/cancel")
    public ResponseEntity<ExportJobEntity> cancelExportJob(
            @PathVariable String exportJobId,
            @RequestParam String gameId,
            @RequestBody CancelRequest request) {
        accessGuard.requireGamePermission(gameId, "export:execute");
        ExportJobEntity job = exportService.cancelExportJob(exportJobId, request.reason);
        return ResponseEntity.ok(job);
    }

    /**
     * 获取导出统计
     */
    @GetMapping("/stats/{gameId}")
    public ResponseEntity<Map<String, Object>> getExportStats(@PathVariable String gameId) {
        accessGuard.requireGamePermission(gameId, "export:execute");
        Map<String, Object> stats = exportService.getExportStats(gameId);
        return ResponseEntity.ok(stats);
    }

    /**
     * 获取用户导出统计
     */
    @GetMapping("/user-stats/{userId}")
    public ResponseEntity<Map<String, Object>> getUserExportStats(
            @PathVariable String userId,
            @RequestParam String gameId) {
        accessGuard.requireGamePermission(gameId, "export:execute");
        Map<String, Object> stats = exportService.getUserExportStats(userId);
        return ResponseEntity.ok(stats);
    }

    // Request DTOs

    public static class ExportRequest {
        public String gameId;
        public String environmentId;
        public String userId;
        public String exportType;
        public LocalDateTime startTime;
        public LocalDateTime endTime;
        public String exportFormat;
        public Map<String, Object> filters;
        public Map<String, Object> dataSource;
        public List<String> columns;
        public String compression;
        public Boolean notifyOnComplete;
        public String notificationEmail;
    }

    public static class CancelRequest {
        public String reason;
    }
}
