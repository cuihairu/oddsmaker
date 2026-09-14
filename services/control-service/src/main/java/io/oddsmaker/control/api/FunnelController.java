package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.FunnelConfigEntity;
import io.oddsmaker.control.jpa.FunnelStepEntity;
import io.oddsmaker.control.service.FunnelConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 漏斗配置API控制器
 * 提供漏斗配置的CRUD操作
 */
@RestController
@RequestMapping("/api/funnels")
public class FunnelController {

    @Autowired
    private FunnelConfigService funnelConfigService;

    /**
     * 创建漏斗配置
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN') or hasRole('MANAGER')")
    public ResponseEntity<FunnelConfigEntity> createFunnel(@RequestBody FunnelConfigEntity funnel) {
        FunnelConfigEntity created = funnelConfigService.createFunnel(funnel);
        return ResponseEntity.ok(created);
    }

    /**
     * 获取漏斗配置列表
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasRole('MANAGER') or hasRole('ANALYST')")
    public ResponseEntity<Page<FunnelConfigEntity>> listFunnels(
            @RequestParam String gameId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {
        
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
    @PreAuthorize("hasRole('ADMIN') or hasRole('MANAGER') or hasRole('ANALYST')")
    public ResponseEntity<FunnelConfigEntity> getFunnel(@PathVariable String funnelId) {
        FunnelConfigEntity funnel = funnelConfigService.findById(funnelId);
        return ResponseEntity.ok(funnel);
    }

    /**
     * 更新漏斗配置
     */
    @PutMapping("/{funnelId}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MANAGER')")
    public ResponseEntity<FunnelConfigEntity> updateFunnel(
            @PathVariable String funnelId,
            @RequestBody FunnelConfigEntity updates) {
        
        FunnelConfigEntity updated = funnelConfigService.updateFunnel(funnelId, updates);
        return ResponseEntity.ok(updated);
    }

    /**
     * 删除漏斗配置
     */
    @DeleteMapping("/{funnelId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteFunnel(@PathVariable String funnelId) {
        funnelConfigService.deleteFunnel(funnelId);
        return ResponseEntity.ok().build();
    }

    /**
     * 启用/禁用漏斗
     */
    @PostMapping("/{funnelId}/toggle")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MANAGER')")
    public ResponseEntity<FunnelConfigEntity> toggleFunnel(
            @PathVariable String funnelId,
            @RequestBody ToggleRequest request) {
        
        FunnelConfigEntity updated = funnelConfigService.toggleFunnel(funnelId, request.enabled);
        return ResponseEntity.ok(updated);
    }

    /**
     * 搜索漏斗配置
     */
    @GetMapping("/search")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MANAGER') or hasRole('ANALYST')")
    public ResponseEntity<Page<FunnelConfigEntity>> searchFunnels(
            @RequestParam String gameId,
            @RequestParam String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        
        Pageable pageable = PageRequest.of(page, size);
        Page<FunnelConfigEntity> funnels = funnelConfigService.searchByName(gameId, query, pageable);
        return ResponseEntity.ok(funnels);
    }

    /**
     * 获取启用的漏斗配置
     */
    @GetMapping("/enabled")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MANAGER') or hasRole('ANALYST')")
    public ResponseEntity<List<FunnelConfigEntity>> getEnabledFunnels(@RequestParam String gameId) {
        List<FunnelConfigEntity> funnels = funnelConfigService.findEnabledByGameId(gameId);
        return ResponseEntity.ok(funnels);
    }

    /**
     * 根据类型获取漏斗配置
     */
    @GetMapping("/type/{type}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MANAGER') or hasRole('ANALYST')")
    public ResponseEntity<List<FunnelConfigEntity>> getFunnelsByType(
            @RequestParam String gameId,
            @PathVariable FunnelConfigEntity.FunnelType type) {
        
        List<FunnelConfigEntity> funnels = funnelConfigService.findByGameIdAndType(gameId, type);
        return ResponseEntity.ok(funnels);
    }

    /**
     * 添加漏斗步骤
     */
    @PostMapping("/{funnelId}/steps")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MANAGER')")
    public ResponseEntity<FunnelStepEntity> addStep(
            @PathVariable String funnelId,
            @RequestBody FunnelStepEntity step) {
        
        FunnelStepEntity created = funnelConfigService.addStep(funnelId, step);
        return ResponseEntity.ok(created);
    }

    /**
     * 更新漏斗步骤
     */
    @PutMapping("/steps/{stepId}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MANAGER')")
    public ResponseEntity<FunnelStepEntity> updateStep(
            @PathVariable String stepId,
            @RequestBody FunnelStepEntity updates) {
        
        FunnelStepEntity updated = funnelConfigService.updateStep(stepId, updates);
        return ResponseEntity.ok(updated);
    }

    /**
     * 删除漏斗步骤
     */
    @DeleteMapping("/steps/{stepId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteStep(@PathVariable String stepId) {
        funnelConfigService.deleteStep(stepId);
        return ResponseEntity.ok().build();
    }

    /**
     * 获取漏斗统计信息
     */
    @GetMapping("/statistics")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MANAGER') or hasRole('ANALYST')")
    public ResponseEntity<FunnelStatistics> getFunnelStatistics(@RequestParam String gameId) {
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