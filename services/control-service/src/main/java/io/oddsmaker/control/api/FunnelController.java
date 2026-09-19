package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.FunnelConfigEntity;
import io.oddsmaker.control.jpa.FunnelStepEntity;
import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.FunnelConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 漏斗配置API控制器
 * 提供漏斗配置的CRUD操作；鉴权走 AccessGuard 行内风格（funnel:read / funnel:manage，平台级）。
 * 历史形态为 @PreAuthorize hasRole('ADMIN') or hasRole('MANAGER') 布尔式，而全仓只签发 ROLE_* authority
 * 且 ROLE_MANAGER/ROLE_ANALYST 从未签发，方法安全开启后这些注解仅 ADMIN 可用——故换成权限种子（V0.9.8）+ AccessGuard 解析。
 */
@RestController
@RequestMapping("/api/funnels")
public class FunnelController {

    @Autowired
    private FunnelConfigService funnelConfigService;

    @Autowired
    private AccessGuard accessGuard;

    /**
     * 创建漏斗配置
     */
    @PostMapping
    public ResponseEntity<FunnelConfigEntity> createFunnel(@RequestBody FunnelConfigEntity funnel) {
        accessGuard.requirePermission("funnel:manage");
        FunnelConfigEntity created = funnelConfigService.createFunnel(funnel);
        return ResponseEntity.ok(created);
    }

    /**
     * 获取漏斗配置列表
     */
    @GetMapping
    public ResponseEntity<Page<FunnelConfigEntity>> listFunnels(
            @RequestParam String gameId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {
        accessGuard.requirePermission("funnel:read");

        Sort sort = sortDir.equalsIgnoreCase("desc") ?
            Sort.by(sortBy).descending() : Sort.by(sortBy).ascending();
        Pageable pageable = PageRequest.of(page, size, sort);

        Page<FunnelConfigEntity> funnels = funnelConfigService.findByGameId(gameId, pageable);
        return ResponseEntity.ok(funnels);
    }

    /**
     * 获取漏斗配置详情
     */
    @GetMapping("/{funnelId}")
    public ResponseEntity<FunnelConfigEntity> getFunnel(@PathVariable String funnelId) {
        accessGuard.requirePermission("funnel:read");
        FunnelConfigEntity funnel = funnelConfigService.findById(funnelId);
        return ResponseEntity.ok(funnel);
    }

    /**
     * 更新漏斗配置
     */
    @PutMapping("/{funnelId}")
    public ResponseEntity<FunnelConfigEntity> updateFunnel(
            @PathVariable String funnelId,
            @RequestBody FunnelConfigEntity updates) {
        accessGuard.requirePermission("funnel:manage");

        FunnelConfigEntity updated = funnelConfigService.updateFunnel(funnelId, updates);
        return ResponseEntity.ok(updated);
    }

    /**
     * 删除漏斗配置
     */
    @DeleteMapping("/{funnelId}")
    public ResponseEntity<Void> deleteFunnel(@PathVariable String funnelId) {
        accessGuard.requirePermission("funnel:manage");
        funnelConfigService.deleteFunnel(funnelId);
        return ResponseEntity.ok().build();
    }

    /**
     * 启用/禁用漏斗
     */
    @PostMapping("/{funnelId}/toggle")
    public ResponseEntity<FunnelConfigEntity> toggleFunnel(
            @PathVariable String funnelId,
            @RequestBody ToggleRequest request) {
        accessGuard.requirePermission("funnel:manage");

        FunnelConfigEntity updated = funnelConfigService.toggleFunnel(funnelId, request.enabled);
        return ResponseEntity.ok(updated);
    }

    /**
     * 搜索漏斗配置
     */
    @GetMapping("/search")
    public ResponseEntity<Page<FunnelConfigEntity>> searchFunnels(
            @RequestParam String gameId,
            @RequestParam String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        accessGuard.requirePermission("funnel:read");

        Pageable pageable = PageRequest.of(page, size);
        Page<FunnelConfigEntity> funnels = funnelConfigService.searchByName(gameId, query, pageable);
        return ResponseEntity.ok(funnels);
    }

    /**
     * 获取启用的漏斗配置
     */
    @GetMapping("/enabled")
    public ResponseEntity<List<FunnelConfigEntity>> getEnabledFunnels(@RequestParam String gameId) {
        accessGuard.requirePermission("funnel:read");
        List<FunnelConfigEntity> funnels = funnelConfigService.findEnabledByGameId(gameId);
        return ResponseEntity.ok(funnels);
    }

    /**
     * 根据类型获取漏斗配置
     */
    @GetMapping("/type/{type}")
    public ResponseEntity<List<FunnelConfigEntity>> getFunnelsByType(
            @RequestParam String gameId,
            @PathVariable FunnelConfigEntity.FunnelType type) {
        accessGuard.requirePermission("funnel:read");

        List<FunnelConfigEntity> funnels = funnelConfigService.findByGameIdAndType(gameId, type);
        return ResponseEntity.ok(funnels);
    }

    /**
     * 添加漏斗步骤
     */
    @PostMapping("/{funnelId}/steps")
    public ResponseEntity<FunnelStepEntity> addStep(
            @PathVariable String funnelId,
            @RequestBody FunnelStepEntity step) {
        accessGuard.requirePermission("funnel:manage");

        FunnelStepEntity created = funnelConfigService.addStep(funnelId, step);
        return ResponseEntity.ok(created);
    }

    /**
     * 更新漏斗步骤
     */
    @PutMapping("/steps/{stepId}")
    public ResponseEntity<FunnelStepEntity> updateStep(
            @PathVariable String stepId,
            @RequestBody FunnelStepEntity updates) {
        accessGuard.requirePermission("funnel:manage");

        FunnelStepEntity updated = funnelConfigService.updateStep(stepId, updates);
        return ResponseEntity.ok(updated);
    }

    /**
     * 删除漏斗步骤
     */
    @DeleteMapping("/steps/{stepId}")
    public ResponseEntity<Void> deleteStep(@PathVariable String stepId) {
        accessGuard.requirePermission("funnel:manage");
        funnelConfigService.deleteStep(stepId);
        return ResponseEntity.ok().build();
    }

    /**
     * 获取漏斗统计信息
     */
    @GetMapping("/statistics")
    public ResponseEntity<FunnelStatistics> getFunnelStatistics(@RequestParam String gameId) {
        accessGuard.requirePermission("funnel:read");
        long totalFunnels = funnelConfigService.getFunnelCount(gameId);
        long enabledFunnels = funnelConfigService.getEnabledFunnelCount(gameId);

        FunnelStatistics stats = new FunnelStatistics();
        stats.totalFunnels = totalFunnels;
        stats.enabledFunnels = enabledFunnels;
        stats.disabledFunnels = totalFunnels - enabledFunnels;

        return ResponseEntity.ok(stats);
    }

    // 请求DTO
    public static class ToggleRequest {
        public boolean enabled;
    }

    // 响应DTO
    public static class FunnelStatistics {
        public long totalFunnels;
        public long enabledFunnels;
        public long disabledFunnels;
    }
}
