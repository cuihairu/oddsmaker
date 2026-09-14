package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.AuditLogEntity;
import io.oddsmaker.control.jpa.AuditLogRepo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 审计日志API控制器
 * 提供审计日志的查询和管理接口
 */
@RestController
@RequestMapping("/api/audit-logs")
public class AuditLogController {

    @Autowired
    private AuditLogRepo auditLogRepo;

    /**
     * 获取审计日志列表
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasRole('MANAGER')")
    public ResponseEntity<Page<AuditLogEntity>> listAuditLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {
        
        Sort sort = sortDir.equalsIgnoreCase("desc") ? 
            Sort.by(sortBy).descending() : Sort.by(sortBy).ascending();
        Pageable pageable = PageRequest.of(page, size, sort);
        
        Page<AuditLogEntity> logs = auditLogRepo.findRecentLogs(pageable);
        return ResponseEntity.ok(logs);
    }

    /**
     * 根据用户ID查找审计日志
     */
    @GetMapping("/user/{userId}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MANAGER')")
    public ResponseEntity<Page<AuditLogEntity>> getLogsByUser(
            @PathVariable String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        
        Pageable pageable = PageRequest.of(page, size);
        Page<AuditLogEntity> logs = auditLogRepo.findByUserIdOrderByCreatedAtDesc(userId, pageable);
        return ResponseEntity.ok(logs);
    }

    /**
     * 根据资源类型和ID查找审计日志
     */
    @GetMapping("/resource/{resourceType}/{resourceId}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MANAGER')")
    public ResponseEntity<Page<AuditLogEntity>> getLogsByResource(
            @PathVariable String resourceType,
            @PathVariable String resourceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        
        Pageable pageable = PageRequest.of(page, size);
        Page<AuditLogEntity> logs = auditLogRepo.findByResourceTypeAndResourceIdOrderByCreatedAtDesc(
            resourceType, resourceId, pageable);
        return ResponseEntity.ok(logs);
    }

    /**
     * 根据操作类型查找审计日志
     */
    @GetMapping("/action/{action}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MANAGER')")
    public ResponseEntity<Page<AuditLogEntity>> getLogsByAction(
            @PathVariable AuditLogEntity.AuditAction action,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        
        Pageable pageable = PageRequest.of(page, size);
        Page<AuditLogEntity> logs = auditLogRepo.findByActionOrderByCreatedAtDesc(action, pageable);
        return ResponseEntity.ok(logs);
    }

    /**
     * 根据状态查找审计日志
     */
    @GetMapping("/status/{status}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MANAGER')")
    public ResponseEntity<Page<AuditLogEntity>> getLogsByStatus(
            @PathVariable AuditLogEntity.AuditStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        
        Pageable pageable = PageRequest.of(page, size);
        Page<AuditLogEntity> logs = auditLogRepo.findByStatusOrderByCreatedAtDesc(status, pageable);
        return ResponseEntity.ok(logs);
    }

    /**
     * 根据时间范围查找审计日志
     */
    @GetMapping("/time-range")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MANAGER')")
    public ResponseEntity<Page<AuditLogEntity>> getLogsByTimeRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        
        Pageable pageable = PageRequest.of(page, size);
        Page<AuditLogEntity> logs = auditLogRepo.findByCreatedAtBetweenOrderByCreatedAtDesc(
            start, end, pageable);
        return ResponseEntity.ok(logs);
    }

    /**
     * 根据游戏ID查找审计日志
     */
    @GetMapping("/game/{gameId}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MANAGER')")
    public ResponseEntity<Page<AuditLogEntity>> getLogsByGame(
            @PathVariable String gameId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        
        Pageable pageable = PageRequest.of(page, size);
        Page<AuditLogEntity> logs = auditLogRepo.findByGameIdOrderByCreatedAtDesc(gameId, pageable);
        return ResponseEntity.ok(logs);
    }

    /**
     * 查找失败的审计日志
     */
    @GetMapping("/failed")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Page<AuditLogEntity>> getFailedLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        
        Pageable pageable = PageRequest.of(page, size);
        Page<AuditLogEntity> logs = auditLogRepo.findFailedActions(pageable);
        return ResponseEntity.ok(logs);
    }

    /**
     * 查找认证相关的审计日志
     */
    @GetMapping("/auth")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Page<AuditLogEntity>> getAuthLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        
        Pageable pageable = PageRequest.of(page, size);
        Page<AuditLogEntity> logs = auditLogRepo.findAuthActions(pageable);
        return ResponseEntity.ok(logs);
    }

    /**
     * 查找敏感操作的审计日志
     */
    @GetMapping("/sensitive")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Page<AuditLogEntity>> getSensitiveLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        
        Pageable pageable = PageRequest.of(page, size);
        Page<AuditLogEntity> logs = auditLogRepo.findSensitiveActions(pageable);
        return ResponseEntity.ok(logs);
    }

    /**
     * 搜索审计日志
     */
    @GetMapping("/search")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MANAGER')")
    public ResponseEntity<Page<AuditLogEntity>> searchLogs(
            @RequestParam String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        
        Pageable pageable = PageRequest.of(page, size);
        Page<AuditLogEntity> logs = auditLogRepo.searchLogs(query, pageable);
        return ResponseEntity.ok(logs);
    }

    /**
     * 获取审计日志统计信息
     */
    @GetMapping("/statistics")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> getAuditStatistics(
            @RequestParam(defaultValue = "7") int days) {
        
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        
        Map<String, Object> stats = Map.of(
            "totalLogs", auditLogRepo.countLogsSince(since),
            "actionsByUser", auditLogRepo.countActionsByUser(since),
            "actionsByResourceType", auditLogRepo.countActionsByResourceType(since),
            "actionsByActionType", auditLogRepo.countActionsByActionType(since),
            "actionsByHour", auditLogRepo.countActionsByHour(since)
        );
        
        return ResponseEntity.ok(stats);
    }

    /**
     * 获取审计日志详情
     */
    @GetMapping("/{logId}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MANAGER')")
    public ResponseEntity<AuditLogEntity> getAuditLog(@PathVariable String logId) {
        return auditLogRepo.findById(logId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 清理旧的审计日志
     */
    @DeleteMapping("/cleanup")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> cleanupOldLogs(
            @RequestParam(defaultValue = "90") int daysToKeep) {
        
        LocalDateTime before = LocalDateTime.now().minusDays(daysToKeep);
        int deleted = auditLogRepo.deleteLogsBefore(before);
        
        Map<String, Object> result = Map.of(
            "deletedCount", deleted,
            "cutoffDate", before
        );
        
        return ResponseEntity.ok(result);
    }
}