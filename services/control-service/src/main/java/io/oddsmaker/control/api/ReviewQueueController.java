package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.ReviewQueueEntity;
import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.ReviewQueueService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 审核队列API控制器
 * 提供审核工作流管理的API接口；鉴权走 AccessGuard 行内风格（game:read / risk:manage / risk:review，全部 game 级）。
 * 历史形态为 @PreAuthorize hasAuthority('READ_GAME:'+gameId) 拼接式，
 * 而全仓只签发 ROLE_* authority，方法安全开启后这些注解恒 403——故换成权限种子（V0.9.8）+ AccessGuard 解析。
 */
@RestController
@RequestMapping("/api/review-queue")
public class ReviewQueueController {

    @Autowired
    private ReviewQueueService reviewQueueService;

    @Autowired
    private AccessGuard accessGuard;

    /**
     * 获取游戏的审核队列
     */
    @GetMapping("/game/{gameId}")
    public ResponseEntity<List<ReviewQueueEntity>> getGameQueue(@PathVariable String gameId) {
        accessGuard.requireGamePermission(gameId, "game:read");
        List<ReviewQueueEntity> items = reviewQueueService.getGameQueue(gameId);
        return ResponseEntity.ok(items);
    }

    /**
     * 获取待处理项
     */
    @GetMapping("/pending/{gameId}")
    public ResponseEntity<List<ReviewQueueEntity>> getPendingItems(@PathVariable String gameId) {
        accessGuard.requireGamePermission(gameId, "game:read");
        List<ReviewQueueEntity> items = reviewQueueService.getPendingItems(gameId);
        return ResponseEntity.ok(items);
    }

    /**
     * 获取高优先级项
     */
    @GetMapping("/high-priority/{gameId}")
    public ResponseEntity<List<ReviewQueueEntity>> getHighPriorityItems(
            @PathVariable String gameId,
            @RequestParam(defaultValue = "70") int minPriority) {
        accessGuard.requireGamePermission(gameId, "game:read");
        List<ReviewQueueEntity> items = reviewQueueService.getHighPriorityItems(gameId, minPriority);
        return ResponseEntity.ok(items);
    }

    /**
     * 分配审核人
     */
    @PostMapping("/{queueItemId}/assign")
    public ResponseEntity<ReviewQueueEntity> assignReviewer(
            @PathVariable String queueItemId,
            @RequestParam String gameId,
            @RequestBody AssignRequest request) {
        accessGuard.requireGamePermission(gameId, "risk:manage");
        ReviewQueueEntity item = reviewQueueService.assignReviewer(queueItemId, request.reviewer, request.assignedBy);
        return ResponseEntity.ok(item);
    }

    /**
     * 认领审核项
     */
    @PostMapping("/{queueItemId}/claim")
    public ResponseEntity<ReviewQueueEntity> claimItem(
            @PathVariable String queueItemId,
            @RequestParam String gameId,
            @RequestBody ClaimRequest request) {
        accessGuard.requireGamePermission(gameId, "risk:review");
        ReviewQueueEntity item = reviewQueueService.claimItem(queueItemId, request.reviewer);
        return ResponseEntity.ok(item);
    }

    /**
     * 开始审核
     */
    @PostMapping("/{queueItemId}/start")
    public ResponseEntity<ReviewQueueEntity> startReview(
            @PathVariable String queueItemId,
            @RequestParam String gameId,
            @RequestBody StartReviewRequest request) {
        accessGuard.requireGamePermission(gameId, "risk:review");
        ReviewQueueEntity item = reviewQueueService.startReview(queueItemId, request.reviewer);
        return ResponseEntity.ok(item);
    }

    /**
     * 完成审核
     */
    @PostMapping("/{queueItemId}/complete")
    public ResponseEntity<ReviewQueueEntity> completeReview(
            @PathVariable String queueItemId,
            @RequestParam String gameId,
            @RequestBody CompleteReviewRequest request) {
        accessGuard.requireGamePermission(gameId, "risk:review");
        ReviewQueueEntity item = reviewQueueService.completeReview(
            queueItemId,
            request.reviewer,
            request.notes,
            request.disposition,
            request.resolution
        );
        return ResponseEntity.ok(item);
    }

    /**
     * 升级案例
     */
    @PostMapping("/{queueItemId}/escalate")
    public ResponseEntity<ReviewQueueEntity> escalateItem(
            @PathVariable String queueItemId,
            @RequestParam String gameId,
            @RequestBody EscalateRequest request) {
        accessGuard.requireGamePermission(gameId, "risk:manage");
        ReviewQueueEntity item = reviewQueueService.escalateItem(queueItemId, request.escalatedTo, request.reason, request.escalatedBy);
        return ResponseEntity.ok(item);
    }

    /**
     * 取消审核
     */
    @PostMapping("/{queueItemId}/cancel")
    public ResponseEntity<ReviewQueueEntity> cancelItem(
            @PathVariable String queueItemId,
            @RequestParam String gameId,
            @RequestBody CancelRequest request) {
        accessGuard.requireGamePermission(gameId, "risk:manage");
        ReviewQueueEntity item = reviewQueueService.cancelItem(queueItemId, request.reason, request.cancelledBy);
        return ResponseEntity.ok(item);
    }

    /**
     * 获取审核人的工作项
     */
    @GetMapping("/reviewer/{reviewer}")
    public ResponseEntity<List<ReviewQueueEntity>> getReviewerItems(
            @PathVariable String reviewer,
            @RequestParam String gameId) {
        accessGuard.requireGamePermission(gameId, "risk:review");
        List<ReviewQueueEntity> items = reviewQueueService.getReviewerItems(reviewer);
        return ResponseEntity.ok(items);
    }

    /**
     * 获取队列统计
     */
    @GetMapping("/stats/{gameId}")
    public ResponseEntity<Map<String, Object>> getQueueStats(@PathVariable String gameId) {
        accessGuard.requireGamePermission(gameId, "game:read");
        Map<String, Object> stats = reviewQueueService.getQueueStats(gameId);
        return ResponseEntity.ok(stats);
    }

    // Request DTOs

    public static class AssignRequest {
        public String reviewer;
        public String assignedBy;
    }

    public static class ClaimRequest {
        public String reviewer;
    }

    public static class StartReviewRequest {
        public String reviewer;
    }

    public static class CompleteReviewRequest {
        public String reviewer;
        public String notes;
        public String disposition;  // confirmed_fraud, confirmed_benign, inconclusive, needs_investigation
        public String resolution;
    }

    public static class EscalateRequest {
        public String escalatedTo;
        public String reason;
        public String escalatedBy;
    }

    public static class CancelRequest {
        public String reason;
        public String cancelledBy;
    }
}
