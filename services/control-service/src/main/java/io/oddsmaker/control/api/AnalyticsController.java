package io.oddsmaker.control.api;

import io.oddsmaker.control.service.AnalyticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 分析 API 控制器
 * 提供收入、广告、会话、性能、社交分析接口
 */
@RestController
@RequestMapping("/api/analytics")
@Tag(name = "Analytics API", description = "分析 API - 收入、广告、会话、性能、社交分析")
public class AnalyticsController {

    @Autowired
    private AnalyticsService analyticsService;

    // ==================== 收入分析 ====================

    @GetMapping("/revenue/{gameId}/overview")
    @Operation(summary = "获取收入概览")
    public ResponseEntity<Map<String, Object>> getRevenueOverview(
            @PathVariable String gameId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(analyticsService.getRevenueOverview(gameId, startDate, endDate));
    }

    @GetMapping("/revenue/{gameId}/arpu")
    @Operation(summary = "获取 ARPU/ARPPU 趋势")
    public ResponseEntity<List<Map<String, Object>>> getArpuTrends(
            @PathVariable String gameId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(analyticsService.getArpuTrends(gameId, startDate, endDate));
    }

    @GetMapping("/revenue/{gameId}/by-platform")
    @Operation(summary = "获取按平台收入分布")
    public ResponseEntity<List<Map<String, Object>>> getRevenueByPlatform(
            @PathVariable String gameId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(analyticsService.getRevenueByPlatform(gameId, date));
    }

    // ==================== 广告分析 ====================

    @GetMapping("/ads/{gameId}/overview")
    @Operation(summary = "获取广告性能概览")
    public ResponseEntity<Map<String, Object>> getAdPerformanceOverview(
            @PathVariable String gameId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(analyticsService.getAdPerformanceOverview(gameId, startDate, endDate));
    }

    @GetMapping("/ads/{gameId}/by-network")
    @Operation(summary = "获取按广告网络性能")
    public ResponseEntity<List<Map<String, Object>>> getAdPerformanceByNetwork(
            @PathVariable String gameId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(analyticsService.getAdPerformanceByNetwork(gameId, startDate, endDate));
    }

    // ==================== 会话分析 ====================

    @GetMapping("/sessions/{gameId}/overview")
    @Operation(summary = "获取会话概览")
    public ResponseEntity<Map<String, Object>> getSessionOverview(
            @PathVariable String gameId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(analyticsService.getSessionOverview(gameId, startDate, endDate));
    }

    @GetMapping("/sessions/{gameId}/trends")
    @Operation(summary = "获取会话趋势")
    public ResponseEntity<List<Map<String, Object>>> getSessionTrends(
            @PathVariable String gameId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(analyticsService.getSessionTrends(gameId, startDate, endDate));
    }

    // ==================== 性能监控 ====================

    @GetMapping("/performance/{gameId}/overview")
    @Operation(summary = "获取性能概览")
    public ResponseEntity<Map<String, Object>> getPerformanceOverview(
            @PathVariable String gameId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate) {
        return ResponseEntity.ok(analyticsService.getPerformanceOverview(gameId, startDate, endDate));
    }

    @GetMapping("/performance/{gameId}/crashes")
    @Operation(summary = "获取崩溃分组")
    public ResponseEntity<List<Map<String, Object>>> getCrashGroups(@PathVariable String gameId) {
        return ResponseEntity.ok(analyticsService.getCrashGroups(gameId));
    }

    // ==================== 社交分析 ====================

    @GetMapping("/social/{gameId}/overview")
    @Operation(summary = "获取社交概览")
    public ResponseEntity<Map<String, Object>> getSocialOverview(
            @PathVariable String gameId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(analyticsService.getSocialOverview(gameId, startDate, endDate));
    }

    @GetMapping("/social/{gameId}/retention-impact")
    @Operation(summary = "获取社交对留存的影响")
    public ResponseEntity<Map<String, Object>> getSocialRetentionImpact(
            @PathVariable String gameId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(analyticsService.getSocialRetentionImpact(gameId, date));
    }
}
