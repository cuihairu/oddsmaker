package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 审核队列服务
 * 管理风险案例的人工审核工作流
 */
@Service
@Transactional
public class ReviewQueueService {

    private static final Logger logger = LoggerFactory.getLogger(ReviewQueueService.class);

    @Autowired
    private ReviewQueueRepo reviewQueueRepo;

    @Autowired
    private RiskCaseRepo riskCaseRepo;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private WebhookService webhookService;

    /**
     * 添加案例到审核队列
     */
    public ReviewQueueEntity addToQueue(RiskCaseEntity riskCase, Integer priority, String queueType, String category) {
        // 检查是否已在队列中
        var existing = reviewQueueRepo.findByRiskCaseId(riskCase.id);
        if (existing.isPresent()) {
            logger.warn("Risk case {} already in review queue", riskCase.id);
            return existing.get();
        }

        ReviewQueueEntity queueItem = new ReviewQueueEntity();
        queueItem.id = "rq_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        queueItem.riskCaseId = riskCase.id;
        queueItem.gameId = riskCase.gameId;
        queueItem.environmentId = riskCase.environmentId;
        queueItem.caseNumber = riskCase.caseNumber;
        queueItem.targetType = riskCase.targetType;
        queueItem.targetId = riskCase.targetId;
        queueItem.targetName = riskCase.targetName;
        queueItem.riskLevel = riskCase.riskLevel.name();
        queueItem.riskScore = riskCase.riskScore;
        queueItem.actionType = riskCase.actionTaken.name();
        queueItem.priority = priority != null ? priority : calculatePriority(riskCase);
        queueItem.queueType = queueType != null ? queueType : "default";
        queueItem.category = category != null ? category : "suspicious";
        queueItem.reviewStatus = ReviewQueueEntity.ReviewStatus.PENDING;

        // 设置SLA
        queueItem.slaDueAt = calculateSlaDue(riskCase.riskLevel);

        queueItem = reviewQueueRepo.save(queueItem);

        logger.info("Added risk case {} to review queue with priority {}", riskCase.caseNumber, queueItem.priority);
        return queueItem;
    }

    /**
     * 分配审核人
     */
    public ReviewQueueEntity assignReviewer(String queueItemId, String reviewer, String assignedBy) {
        ReviewQueueEntity item = reviewQueueRepo.findById(queueItemId)
            .orElseThrow(() -> new IllegalArgumentException("Queue item not found: " + queueItemId));

        if (!item.isPending()) {
            throw new IllegalStateException("Cannot assign item in status: " + item.reviewStatus);
        }

        item.assignTo(reviewer, calculateSlaDue(item.riskLevel != null ?
            RiskCaseEntity.RiskLevel.valueOf(item.riskLevel) : RiskCaseEntity.RiskLevel.MEDIUM));
        reviewQueueRepo.save(item);

        // 记录审计日志
        auditLogService.log(
            AuditLogEntity.AuditAction.GRANT,
            "review_queue",
            item.id,
            item.caseNumber,
            "Assigned to reviewer: " + reviewer,
            AuditLogEntity.AuditResult.SUCCESS,
            assignedBy,
            null,
            null,
            null,
            null,
            Map.of("gameId", item.gameId, "reviewer", reviewer)
        );

        logger.info("Assigned review queue item {} to {}", item.caseNumber, reviewer);
        return item;
    }

    /**
     * 认领审核项
     */
    public ReviewQueueEntity claimItem(String queueItemId, String reviewer) {
        ReviewQueueEntity item = reviewQueueRepo.findById(queueItemId)
            .orElseThrow(() -> new IllegalArgumentException("Queue item not found: " + queueItemId));

        if (item.isAssigned() || item.isPending()) {
            item.claim(reviewer);
            reviewQueueRepo.save(item);

            logger.info("Reviewer {} claimed item {}", reviewer, item.caseNumber);
        }

        return item;
    }

    /**
     * 开始审核
     */
    public ReviewQueueEntity startReview(String queueItemId, String reviewer) {
        ReviewQueueEntity item = reviewQueueRepo.findById(queueItemId)
            .orElseThrow(() -> new IllegalArgumentException("Queue item not found: " + queueItemId));

        item.startReview(reviewer);
        reviewQueueRepo.save(item);

        logger.info("Reviewer {} started review of item {}", reviewer, item.caseNumber);
        return item;
    }

    /**
     * 完成审核
     */
    public ReviewQueueEntity completeReview(String queueItemId, String reviewer, String notes,
                                           String disposition, String resolution) {
        ReviewQueueEntity item = reviewQueueRepo.findById(queueItemId)
            .orElseThrow(() -> new IllegalArgumentException("Queue item not found: " + queueItemId));

        // 同时更新风险案例
        RiskCaseEntity riskCase = riskCaseRepo.findById(item.riskCaseId).orElse(null);
        if (riskCase != null) {
            riskCase.completeReview(reviewer, notes, disposition);
            riskCaseRepo.save(riskCase);
        }

        item.complete(reviewer, notes, disposition, resolution);
        reviewQueueRepo.save(item);

        // 记录审计日志
        auditLogService.log(
            AuditLogEntity.AuditAction.UPDATE,
            "review_queue",
            item.id,
            item.caseNumber,
            "Completed review with disposition: " + disposition,
            AuditLogEntity.AuditResult.SUCCESS,
            reviewer,
            null,
            null,
            null,
            null,
            Map.of(
                "gameId", item.gameId,
                "disposition", disposition,
                "resolution", resolution
            )
        );

        logger.info("Reviewer {} completed review of item {} with disposition: {}", reviewer, item.caseNumber, disposition);
        return item;
    }

    /**
     * 升级案例
     */
    public ReviewQueueEntity escalateItem(String queueItemId, String escalatedTo, String reason, String escalatedBy) {
        ReviewQueueEntity item = reviewQueueRepo.findById(queueItemId)
            .orElseThrow(() -> new IllegalArgumentException("Queue item not found: " + queueItemId));

        item.escalate(escalatedTo, reason);
        reviewQueueRepo.save(item);

        // 记录审计日志
        auditLogService.log(
            AuditLogEntity.AuditAction.UPDATE,
            "review_queue",
            item.id,
            item.caseNumber,
            "Escalated to: " + escalatedTo,
            AuditLogEntity.AuditResult.SUCCESS,
            escalatedBy,
            null,
            null,
            null,
            null,
            Map.of("gameId", item.gameId, "escalatedTo", escalatedTo, "reason", reason)
        );

        logger.info("Escalated item {} to {} due to: {}", item.caseNumber, escalatedTo, reason);
        return item;
    }

    /**
     * 取消审核
     */
    public ReviewQueueEntity cancelItem(String queueItemId, String reason, String cancelledBy) {
        ReviewQueueEntity item = reviewQueueRepo.findById(queueItemId)
            .orElseThrow(() -> new IllegalArgumentException("Queue item not found: " + queueItemId));

        item.cancel(reason);
        reviewQueueRepo.save(item);

        // 记录审计日志
        auditLogService.log(
            AuditLogEntity.AuditAction.DELETE,
            "review_queue",
            item.id,
            item.caseNumber,
            "Cancelled review: " + reason,
            AuditLogEntity.AuditResult.SUCCESS,
            cancelledBy,
            null,
            null,
            null,
            null,
            Map.of("gameId", item.gameId, "reason", reason)
        );

        logger.info("Cancelled review of item {}: {}", item.caseNumber, reason);
        return item;
    }

    /**
     * 获取审核队列
     */
    @Transactional(readOnly = true)
    public List<ReviewQueueEntity> getGameQueue(String gameId) {
        return reviewQueueRepo.findByGameId(gameId);
    }

    /**
     * 获取待处理项
     */
    @Transactional(readOnly = true)
    public List<ReviewQueueEntity> getPendingItems(String gameId) {
        return reviewQueueRepo.findPendingByGameId(gameId);
    }

    /**
     * 获取高优先级项
     */
    @Transactional(readOnly = true)
    public List<ReviewQueueEntity> getHighPriorityItems(String gameId, int minPriority) {
        return reviewQueueRepo.findHighPriority(gameId, minPriority);
    }

    /**
     * 获取审核人的工作项
     */
    @Transactional(readOnly = true)
    public List<ReviewQueueEntity> getReviewerItems(String reviewer) {
        return reviewQueueRepo.findByReviewer(reviewer);
    }

    /**
     * 获取队列统计
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getQueueStats(String gameId) {
        List<ReviewQueueEntity> allItems = reviewQueueRepo.findByGameId(gameId);
        List<ReviewQueueEntity> pendingItems = reviewQueueRepo.findPendingByGameId(gameId);

        long total = allItems.size();
        long pending = pendingItems.size();
        long highPriority = pendingItems.stream().filter(ReviewQueueEntity::isHighPriority).count();
        long overdue = allItems.stream().filter(ReviewQueueEntity::isOverdue).count();

        // 按状态分组
        Map<String, Long> byStatus = allItems.stream()
            .collect(Collectors.groupingBy(i -> i.reviewStatus.name(), Collectors.counting()));

        // 按分类分组
        Map<String, Long> byCategory = allItems.stream()
            .collect(Collectors.groupingBy(i -> i.category != null ? i.category : "unknown", Collectors.counting()));

        // 平均处理时间
        double avgResolutionTime = allItems.stream()
            .filter(i -> i.isCompleted())
            .mapToLong(ReviewQueueEntity::getResolutionTimeMinutes)
            .average()
            .orElse(0.0);

        return Map.of(
            "totalItems", total,
            "pendingItems", pending,
            "highPriorityItems", highPriority,
            "overdueItems", overdue,
            "byStatus", byStatus,
            "byCategory", byCategory,
            "avgResolutionTimeMinutes", avgResolutionTime
        );
    }

    /**
     * 定期检查SLA违规
     */
    @Scheduled(fixedDelay = 300000)  // 每5分钟执行一次
    @Transactional
    public void checkSlaBreaches() {
        try {
            List<ReviewQueueEntity> overdueItems = reviewQueueRepo.findOverdue(LocalDateTime.now());

            for (ReviewQueueEntity item : overdueItems) {
                if (!item.slaBreached) {
                    item.slaBreached = true;
                    reviewQueueRepo.save(item);

                    logger.warn("SLA breach detected for review item: {}", item.caseNumber);

                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("event_type", WebhookService.EVENT_REVIEW_SLA_BREACH);
                    payload.put("item_id", item.id);
                    payload.put("case_number", item.caseNumber);
                    payload.put("game_id", item.gameId);
                    payload.put("environment_id", item.environmentId);
                    payload.put("target_type", item.targetType);
                    payload.put("target_id", item.targetId);
                    payload.put("target_name", item.targetName);
                    payload.put("priority", item.priority);
                    payload.put("category", item.category);
                    payload.put("queue_type", item.queueType);
                    payload.put("sla_due_at", item.slaDueAt != null ? item.slaDueAt.toString() : null);
                    payload.put("breached_at", LocalDateTime.now().toString());
                    try {
                        webhookService.sendCustomWebhook(item.gameId, WebhookService.EVENT_REVIEW_SLA_BREACH, payload);
                    } catch (Exception e) {
                        logger.warn("Review SLA breach webhook dispatch failed: {}", e.getMessage());
                    }
                }
            }

            if (!overdueItems.isEmpty()) {
                logger.info("Detected {} SLA breaches in review queue", overdueItems.size());
            }
        } catch (Exception e) {
            logger.error("Failed to check SLA breaches", e);
        }
    }

    /**
     * 定期检查需要升级的项
     */
    @Scheduled(cron = "0 0 * * * ?")  // 每小时执行一次
    @Transactional
    public void checkEscalations() {
        try {
            // 查找处理时间超过24小时的项
            LocalDateTime threshold = LocalDateTime.now().minusHours(24);
            List<ReviewQueueEntity> needsEscalation = reviewQueueRepo.findNeedsEscalation(threshold);

            for (ReviewQueueEntity item : needsEscalation) {
                logger.warn("Item {} needs escalation - in review for over 24 hours", item.caseNumber);

                // 置升级标志（不动 reviewStatus，避免把处理中项挪出审核者工作流）；
                // findNeedsEscalation 过滤 escalated=false，每项至多升级一次
                item.escalated = true;
                item.escalatedTo = "system";
                item.escalationReason = "Auto-escalated: in review for over 24 hours";
                item.escalatedAt = LocalDateTime.now();
                reviewQueueRepo.save(item);

                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("event_type", WebhookService.EVENT_REVIEW_ESCALATION);
                payload.put("item_id", item.id);
                payload.put("case_number", item.caseNumber);
                payload.put("game_id", item.gameId);
                payload.put("environment_id", item.environmentId);
                payload.put("target_type", item.targetType);
                payload.put("target_id", item.targetId);
                payload.put("target_name", item.targetName);
                payload.put("priority", item.priority);
                payload.put("category", item.category);
                payload.put("sla_due_at", item.slaDueAt != null ? item.slaDueAt.toString() : null);
                payload.put("escalated_to", item.escalatedTo);
                payload.put("escalation_reason", item.escalationReason);
                payload.put("escalated_at", item.escalatedAt.toString());
                payload.put("created_at", item.createdAt != null ? item.createdAt.toString() : null);
                try {
                    webhookService.sendCustomWebhook(item.gameId, WebhookService.EVENT_REVIEW_ESCALATION, payload);
                } catch (Exception e) {
                    logger.warn("Review escalation webhook dispatch failed: {}", e.getMessage());
                }
            }

            if (!needsEscalation.isEmpty()) {
                logger.info("Detected {} items needing escalation", needsEscalation.size());
            }
        } catch (Exception e) {
            logger.error("Failed to check escalations", e);
        }
    }

    // 私有辅助方法

    private Integer calculatePriority(RiskCaseEntity riskCase) {
        int priority = 50;  // 默认优先级

        // 根据风险等级调整
        priority = switch (riskCase.riskLevel) {
            case CRITICAL -> 90;
            case HIGH -> 70;
            case MEDIUM -> 50;
            case LOW -> 30;
        };

        // 根据动作类型调整
        if (riskCase.actionTaken == RiskCaseEntity.ActionType.BLOCK) {
            priority += 10;
        }

        return Math.min(100, priority);
    }

    private LocalDateTime calculateSlaDue(RiskCaseEntity.RiskLevel riskLevel) {
        LocalDateTime now = LocalDateTime.now();
        return switch (riskLevel) {
            case CRITICAL -> now.plusHours(4);   // 4小时
            case HIGH -> now.plusHours(8);      // 8小时
            case MEDIUM -> now.plusHours(24);   // 24小时
            case LOW -> now.plusDays(3);        // 3天
        };
    }
}
