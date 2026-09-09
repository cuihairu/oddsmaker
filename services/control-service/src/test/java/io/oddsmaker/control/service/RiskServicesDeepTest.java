package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.BlockListEntity;
import io.oddsmaker.control.jpa.BlockListRepo;
import io.oddsmaker.control.jpa.GameEntity;
import io.oddsmaker.control.jpa.GameRepo;
import io.oddsmaker.control.jpa.RiskCaseEntity;
import io.oddsmaker.control.jpa.RiskCaseRepo;
import io.oddsmaker.control.jpa.ReviewQueueEntity;
import io.oddsmaker.control.jpa.ReviewQueueRepo;
import io.oddsmaker.control.jpa.RiskRuleEntity;
import io.oddsmaker.control.jpa.RiskRuleRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 风控域 Service 深度测试：风控规则 / 审核队列 / 封禁名单 / 风控大屏指标（纯 Mockito，不启动 Spring）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("风控域 Service 深度测试")
class RiskServicesDeepTest {

    @Mock
    private AuditLogService auditLogService;

    // ===== 风控规则 =====

    @Mock
    private RiskRuleRepo ruleRepo;

    @Mock
    private GameRepo gameRepo;

    @InjectMocks
    private RiskRuleService riskRuleService;

    private static RiskRuleEntity rule(String id, String name) {
        RiskRuleEntity r = new RiskRuleEntity();
        r.id = id;
        r.gameId = "g";
        r.name = name;
        r.ruleType = RiskRuleEntity.RuleType.THRESHOLD;
        r.actionType = RiskRuleEntity.ActionType.ALERT;
        r.status = RiskRuleEntity.RuleStatus.ACTIVE;
        return r;
    }

    @Test
    @DisplayName("风控规则：list 过滤/空参与 get 命中/已删/未找到")
    void riskRuleListAndGet() {
        RiskRuleEntity active = rule("rr_1", "Speed Rule");
        lenient().when(ruleRepo.findAll(ArgumentMatchers.<Specification<RiskRuleEntity>>any(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(active)));

        Page<RiskRuleEntity> filtered = riskRuleService.list("g", "env1", "active", "threshold", "speed", 0, 10);
        assertEquals(1, filtered.getTotalElements());
        Page<RiskRuleEntity> unfiltered = riskRuleService.list(null, null, null, null, null, -1, 0);
        assertEquals(1, unfiltered.getContent().size());

        lenient().when(ruleRepo.findById("rr_1")).thenReturn(Optional.of(active));
        RiskRuleEntity deleted = rule("rr_2", "Deleted");
        deleted.deletedAt = LocalDateTime.now();
        lenient().when(ruleRepo.findById("rr_2")).thenReturn(Optional.of(deleted));

        assertEquals("rr_1", riskRuleService.get("rr_1").id);
        assertNull(riskRuleService.get("rr_2"));
        assertNull(riskRuleService.get("rr_missing"));
    }

    @Test
    @DisplayName("风控规则：create 校验分支与 DRAFT/ACTIVE 默认值")
    void riskRuleCreateBranches() {
        GameEntity game = new GameEntity();
        game.id = "g";
        GameEntity deletedGame = new GameEntity();
        deletedGame.id = "gd";
        deletedGame.deletedAt = LocalDateTime.now();
        lenient().when(gameRepo.findById("g")).thenReturn(Optional.of(game));
        lenient().when(gameRepo.findById("gd")).thenReturn(Optional.of(deletedGame));
        lenient().when(gameRepo.findById("g_none")).thenReturn(Optional.empty());
        lenient().when(ruleRepo.findByGameId("g")).thenReturn(List.of());
        lenient().when(ruleRepo.save(any(RiskRuleEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        RiskRuleEntity noGameId = rule(null, "x");
        noGameId.gameId = null;
        assertThrows(IllegalArgumentException.class, () -> riskRuleService.create(noGameId, "op"));
        RiskRuleEntity blankGameId = rule(null, "x");
        blankGameId.gameId = " ";
        assertThrows(IllegalArgumentException.class, () -> riskRuleService.create(blankGameId, "op"));
        RiskRuleEntity gameMissing = rule(null, "x");
        gameMissing.gameId = "g_none";
        assertThrows(IllegalArgumentException.class, () -> riskRuleService.create(gameMissing, "op"));
        RiskRuleEntity gameDeleted = rule(null, "x");
        gameDeleted.gameId = "gd";
        assertThrows(IllegalArgumentException.class, () -> riskRuleService.create(gameDeleted, "op"));
        RiskRuleEntity noName = rule(null, "   ");
        assertThrows(IllegalArgumentException.class, () -> riskRuleService.create(noName, "op"));

        RiskRuleEntity dup = rule(null, "Exist");
        dup.gameId = "gdup";
        lenient().when(gameRepo.findById("gdup")).thenReturn(Optional.of(game));
        lenient().when(ruleRepo.findByGameId("gdup")).thenReturn(List.of(rule("rr_x", "Exist")));
        assertThrows(IllegalArgumentException.class, () -> riskRuleService.create(dup, "op"));

        RiskRuleEntity draftReq = rule(null, "  New Rule  ");
        draftReq.status = null;
        RiskRuleEntity draft = riskRuleService.create(draftReq, "op");
        assertTrue(draft.id.startsWith("rr_"));
        assertEquals("New Rule", draft.name);
        assertEquals(RiskRuleEntity.RuleStatus.DRAFT, draft.status);
        assertEquals(0L, draft.totalTriggeredCount);
        assertEquals(0L, draft.totalBlockedCount);
        assertEquals(0L, draft.totalReviewCount);
        assertEquals("op", draft.createdBy);
        assertNull(draft.activatedAt);

        RiskRuleEntity activeReq = rule(null, "Active Rule");
        activeReq.status = RiskRuleEntity.RuleStatus.ACTIVE;
        RiskRuleEntity active = riskRuleService.create(activeReq, "op");
        assertEquals(RiskRuleEntity.RuleStatus.ACTIVE, active.status);
        assertNotNull(active.activatedAt);
    }

    @Test
    @DisplayName("风控规则：update 全字段/clamp、setStatus 启停、delete 软删除")
    void riskRuleUpdateStatusDelete() {
        RiskRuleEntity existing = rule("rr_1", "Old");
        lenient().when(ruleRepo.findById("rr_1")).thenReturn(Optional.of(existing));
        lenient().when(ruleRepo.save(any(RiskRuleEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        RiskRuleEntity req = rule(null, "   ");
        req.displayName = "DN";
        req.description = "D";
        req.category = RiskRuleEntity.RuleCategory.PAYMENT;
        req.ruleType = RiskRuleEntity.RuleType.VELOCITY;
        req.ruleConditions = "{}";
        req.riskLevel = RiskRuleEntity.RiskLevel.HIGH;
        req.riskScore = 150;
        req.actionType = RiskRuleEntity.ActionType.BLOCK;
        req.actionParams = "{}";
        req.enableAutoBlock = true;
        req.blockDuration = 60;
        req.enableWebhook = true;
        req.webhookUrl = "http://x";
        req.enableReviewQueue = false;
        req.triggerThreshold = 0;
        req.timeWindowMinutes = -3;
        req.cooldownMinutes = 5;
        req.priority = 9;
        req.testMode = true;

        RiskRuleEntity updated = riskRuleService.update("rr_1", req, "op");
        assertEquals("Old", updated.name);
        assertEquals("DN", updated.displayName);
        assertEquals("D", updated.description);
        assertEquals(RiskRuleEntity.RuleCategory.PAYMENT, updated.category);
        assertEquals(RiskRuleEntity.RuleType.VELOCITY, updated.ruleType);
        assertEquals("{}", updated.ruleConditions);
        assertEquals(RiskRuleEntity.RiskLevel.HIGH, updated.riskLevel);
        assertEquals(RiskRuleEntity.ActionType.BLOCK, updated.actionType);
        assertEquals(100, updated.riskScore);
        assertTrue(updated.enableAutoBlock);
        assertEquals(60, updated.blockDuration);
        assertTrue(updated.enableWebhook);
        assertEquals("http://x", updated.webhookUrl);
        assertFalse(updated.enableReviewQueue);
        assertEquals(1, updated.triggerThreshold);
        assertEquals(1, updated.timeWindowMinutes);
        assertEquals(5, updated.cooldownMinutes);
        assertEquals(9, updated.priority);
        assertTrue(updated.testMode);

        RiskRuleEntity rename = rule(null, "Renamed");
        assertEquals("Renamed", riskRuleService.update("rr_1", rename, "op").name);
        assertNull(riskRuleService.update("rr_missing", new RiskRuleEntity(), "op"));

        RiskRuleEntity enabled = riskRuleService.setStatus("rr_1", true, "op");
        assertEquals(RiskRuleEntity.RuleStatus.ACTIVE, enabled.status);
        assertNotNull(enabled.activatedAt);
        RiskRuleEntity paused = riskRuleService.setStatus("rr_1", false, "op");
        assertEquals(RiskRuleEntity.RuleStatus.PAUSED, paused.status);
        assertNull(riskRuleService.setStatus("rr_missing", true, "op"));

        assertTrue(riskRuleService.delete("rr_1", "op"));
        assertNotNull(existing.deletedAt);
        assertEquals(RiskRuleEntity.RuleStatus.ARCHIVED, existing.status);
        assertFalse(riskRuleService.delete("rr_missing", "op"));
    }

    // ===== 审核队列 =====

    @Mock
    private ReviewQueueRepo reviewQueueRepo;

    @Mock
    private RiskCaseRepo riskCaseRepo;

    @InjectMocks
    private ReviewQueueService reviewQueueService;

    private static RiskCaseEntity riskCase(String id, RiskCaseEntity.RiskLevel level, RiskCaseEntity.ActionType action) {
        RiskCaseEntity c = new RiskCaseEntity();
        c.id = id;
        c.riskRuleId = "rr_1";
        c.gameId = "g";
        c.caseNumber = "CASE_" + id;
        c.targetType = "device";
        c.targetId = "d1";
        c.targetName = "Target";
        c.riskLevel = level;
        c.riskScore = 80;
        c.actionTaken = action;
        return c;
    }

    private static ReviewQueueEntity queueItem(String id, ReviewQueueEntity.ReviewStatus status) {
        ReviewQueueEntity q = new ReviewQueueEntity();
        q.id = id;
        q.riskCaseId = "rc_1";
        q.gameId = "g";
        q.caseNumber = "CASE_1";
        q.targetType = "device";
        q.targetId = "d1";
        q.priority = 50;
        q.category = "fraud";
        q.reviewStatus = status;
        return q;
    }

    @Test
    @DisplayName("审核队列：addToQueue 优先级计算/默认值/重复入队与队列查询统计")
    @SuppressWarnings("unchecked")
    void reviewQueueEnqueueAndStats() {
        lenient().when(reviewQueueRepo.findByRiskCaseId(anyString())).thenReturn(Optional.empty());
        lenient().when(reviewQueueRepo.save(any(ReviewQueueEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ReviewQueueEntity critical = reviewQueueService.addToQueue(
            riskCase("rc_1", RiskCaseEntity.RiskLevel.CRITICAL, RiskCaseEntity.ActionType.BLOCK), null, null, null);
        assertTrue(critical.id.startsWith("rq_"));
        assertEquals(100, critical.priority);
        assertEquals("default", critical.queueType);
        assertEquals("suspicious", critical.category);
        assertEquals(ReviewQueueEntity.ReviewStatus.PENDING, critical.reviewStatus);
        assertNotNull(critical.slaDueAt);

        ReviewQueueEntity high = reviewQueueService.addToQueue(
            riskCase("rc_2", RiskCaseEntity.RiskLevel.HIGH, RiskCaseEntity.ActionType.ALERT), 42, "high_priority", "fraud");
        assertEquals(42, high.priority);
        assertEquals("high_priority", high.queueType);
        assertEquals("fraud", high.category);

        assertEquals(70, reviewQueueService.addToQueue(
            riskCase("rc_3", RiskCaseEntity.RiskLevel.HIGH, RiskCaseEntity.ActionType.ALERT), null, null, null).priority);
        assertEquals(50, reviewQueueService.addToQueue(
            riskCase("rc_4", RiskCaseEntity.RiskLevel.MEDIUM, RiskCaseEntity.ActionType.ALERT), null, null, null).priority);
        assertEquals(30, reviewQueueService.addToQueue(
            riskCase("rc_5", RiskCaseEntity.RiskLevel.LOW, RiskCaseEntity.ActionType.ALERT), null, null, null).priority);

        ReviewQueueEntity dupItem = queueItem("rq_dup", ReviewQueueEntity.ReviewStatus.PENDING);
        lenient().when(reviewQueueRepo.findByRiskCaseId("rc_1")).thenReturn(Optional.of(dupItem));
        assertEquals("rq_dup", reviewQueueService.addToQueue(
            riskCase("rc_1", RiskCaseEntity.RiskLevel.CRITICAL, RiskCaseEntity.ActionType.BLOCK), 1, "t", "c").id);

        ReviewQueueEntity a = queueItem("qa", ReviewQueueEntity.ReviewStatus.PENDING);
        a.priority = 80;
        ReviewQueueEntity b = queueItem("qb", ReviewQueueEntity.ReviewStatus.COMPLETED);
        b.category = null;
        b.createdAt = LocalDateTime.now().minusMinutes(30);
        b.resolvedAt = b.createdAt.plusMinutes(8);
        ReviewQueueEntity c = queueItem("qc", ReviewQueueEntity.ReviewStatus.PENDING);
        c.slaDueAt = LocalDateTime.now().minusHours(1);

        lenient().when(reviewQueueRepo.findByGameId("g")).thenReturn(List.of(a, b, c));
        lenient().when(reviewQueueRepo.findPendingByGameId("g")).thenReturn(List.of(a, c));
        lenient().when(reviewQueueRepo.findHighPriority("g", 70)).thenReturn(List.of(a));
        lenient().when(reviewQueueRepo.findByReviewer("alice")).thenReturn(List.of(a));

        assertEquals(3, reviewQueueService.getGameQueue("g").size());
        assertEquals(2, reviewQueueService.getPendingItems("g").size());
        assertEquals(1, reviewQueueService.getHighPriorityItems("g", 70).size());
        assertEquals(1, reviewQueueService.getReviewerItems("alice").size());

        Map<String, Object> stats = reviewQueueService.getQueueStats("g");
        assertEquals(3L, stats.get("totalItems"));
        assertEquals(2L, stats.get("pendingItems"));
        assertEquals(1L, stats.get("highPriorityItems"));
        assertEquals(1L, stats.get("overdueItems"));
        assertEquals(8.0, stats.get("avgResolutionTimeMinutes"));
        Map<String, Long> byStatus = (Map<String, Long>) stats.get("byStatus");
        assertEquals(2L, byStatus.get("PENDING"));
        assertEquals(1L, byStatus.get("COMPLETED"));
        Map<String, Long> byCategory = (Map<String, Long>) stats.get("byCategory");
        assertEquals(2L, byCategory.get("fraud"));
        assertEquals(1L, byCategory.get("unknown"));
    }

    @Test
    @DisplayName("审核队列：分配/认领/开始/完成/升级/取消工作流与 SLA 定时检查")
    void reviewQueueWorkflow() {
        lenient().when(reviewQueueRepo.save(any(ReviewQueueEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThrows(IllegalArgumentException.class, () -> reviewQueueService.assignReviewer("q_missing", "a", "b"));
        assertThrows(IllegalArgumentException.class, () -> reviewQueueService.claimItem("q_missing", "a"));
        assertThrows(IllegalArgumentException.class, () -> reviewQueueService.startReview("q_missing", "a"));
        assertThrows(IllegalArgumentException.class,
            () -> reviewQueueService.completeReview("q_missing", "a", "n", "d", "r"));
        assertThrows(IllegalArgumentException.class,
            () -> reviewQueueService.escalateItem("q_missing", "to", "why", "by"));
        assertThrows(IllegalArgumentException.class,
            () -> reviewQueueService.cancelItem("q_missing", "why", "by"));

        ReviewQueueEntity pending = queueItem("q_pend", ReviewQueueEntity.ReviewStatus.PENDING);
        lenient().when(reviewQueueRepo.findById("q_pend")).thenReturn(Optional.of(pending));
        ReviewQueueEntity assigned = reviewQueueService.assignReviewer("q_pend", "alice", "boss");
        assertEquals(ReviewQueueEntity.ReviewStatus.ASSIGNED, assigned.reviewStatus);
        assertEquals("alice", assigned.assignedTo);
        assertNotNull(assigned.assignedAt);
        assertNotNull(assigned.slaDueAt);

        ReviewQueueEntity highItem = queueItem("q_high", ReviewQueueEntity.ReviewStatus.PENDING);
        highItem.riskLevel = "HIGH";
        lenient().when(reviewQueueRepo.findById("q_high")).thenReturn(Optional.of(highItem));
        assertEquals("bob", reviewQueueService.assignReviewer("q_high", "bob", "boss").assignedTo);

        ReviewQueueEntity done = queueItem("q_done", ReviewQueueEntity.ReviewStatus.COMPLETED);
        lenient().when(reviewQueueRepo.findById("q_done")).thenReturn(Optional.of(done));
        assertThrows(IllegalStateException.class, () -> reviewQueueService.assignReviewer("q_done", "x", "y"));

        ReviewQueueEntity toClaim = queueItem("q_claim", ReviewQueueEntity.ReviewStatus.PENDING);
        lenient().when(reviewQueueRepo.findById("q_claim")).thenReturn(Optional.of(toClaim));
        ReviewQueueEntity claimed = reviewQueueService.claimItem("q_claim", "carol");
        assertEquals(ReviewQueueEntity.ReviewStatus.CLAIMED, claimed.reviewStatus);
        assertEquals("carol", claimed.claimedBy);
        assertNotNull(claimed.claimedAt);

        ReviewQueueEntity cantClaim = queueItem("q_cant", ReviewQueueEntity.ReviewStatus.COMPLETED);
        lenient().when(reviewQueueRepo.findById("q_cant")).thenReturn(Optional.of(cantClaim));
        ReviewQueueEntity untouched = reviewQueueService.claimItem("q_cant", "carol");
        assertEquals(ReviewQueueEntity.ReviewStatus.COMPLETED, untouched.reviewStatus);
        assertNull(untouched.claimedBy);

        ReviewQueueEntity toStart = queueItem("q_start", ReviewQueueEntity.ReviewStatus.ASSIGNED);
        lenient().when(reviewQueueRepo.findById("q_start")).thenReturn(Optional.of(toStart));
        ReviewQueueEntity started = reviewQueueService.startReview("q_start", "carol");
        assertEquals(ReviewQueueEntity.ReviewStatus.IN_REVIEW, started.reviewStatus);
        assertEquals("carol", started.reviewedBy);

        RiskCaseEntity rc = riskCase("rc_1", RiskCaseEntity.RiskLevel.HIGH, RiskCaseEntity.ActionType.REVIEW);
        lenient().when(riskCaseRepo.findById("rc_1")).thenReturn(Optional.of(rc));
        ReviewQueueEntity toComplete = queueItem("q_comp", ReviewQueueEntity.ReviewStatus.IN_REVIEW);
        lenient().when(reviewQueueRepo.findById("q_comp")).thenReturn(Optional.of(toComplete));
        ReviewQueueEntity completed = reviewQueueService.completeReview(
            "q_comp", "carol", "notes", "confirmed_fraud", "banned");
        assertEquals(ReviewQueueEntity.ReviewStatus.COMPLETED, completed.reviewStatus);
        assertEquals("confirmed_fraud", completed.disposition);
        assertEquals("banned", completed.resolution);
        assertNotNull(completed.resolvedAt);
        assertEquals("completed", rc.reviewStatus);
        assertEquals("confirmed_fraud", rc.disposition);
        assertEquals("carol", rc.reviewedBy);

        lenient().when(riskCaseRepo.findById("rc_none")).thenReturn(Optional.empty());
        ReviewQueueEntity noCase = queueItem("q_nocase", ReviewQueueEntity.ReviewStatus.CLAIMED);
        noCase.riskCaseId = "rc_none";
        lenient().when(reviewQueueRepo.findById("q_nocase")).thenReturn(Optional.of(noCase));
        assertEquals(ReviewQueueEntity.ReviewStatus.COMPLETED,
            reviewQueueService.completeReview("q_nocase", "carol", "n", "inconclusive", "r").reviewStatus);

        ReviewQueueEntity toEscalate = queueItem("q_esc", ReviewQueueEntity.ReviewStatus.ASSIGNED);
        lenient().when(reviewQueueRepo.findById("q_esc")).thenReturn(Optional.of(toEscalate));
        ReviewQueueEntity escalated = reviewQueueService.escalateItem("q_esc", "senior", "too complex", "op");
        assertEquals(ReviewQueueEntity.ReviewStatus.ESCALATED, escalated.reviewStatus);
        assertTrue(escalated.escalated);
        assertEquals("senior", escalated.escalatedTo);
        assertEquals("too complex", escalated.escalationReason);

        ReviewQueueEntity toCancel = queueItem("q_cancel", ReviewQueueEntity.ReviewStatus.PENDING);
        lenient().when(reviewQueueRepo.findById("q_cancel")).thenReturn(Optional.of(toCancel));
        ReviewQueueEntity cancelled = reviewQueueService.cancelItem("q_cancel", "false positive", "op");
        assertEquals(ReviewQueueEntity.ReviewStatus.CANCELLED, cancelled.reviewStatus);
        assertEquals("false positive", cancelled.reviewNotes);

        ReviewQueueEntity breach = queueItem("q_sla1", ReviewQueueEntity.ReviewStatus.IN_REVIEW);
        ReviewQueueEntity already = queueItem("q_sla2", ReviewQueueEntity.ReviewStatus.PENDING);
        already.slaBreached = true;
        lenient().when(reviewQueueRepo.findOverdue(any()))
            .thenReturn(List.of(breach, already))
            .thenThrow(new RuntimeException("boom"));
        reviewQueueService.checkSlaBreaches();
        assertTrue(breach.slaBreached);
        assertTrue(already.slaBreached);
        verify(reviewQueueRepo).save(breach);
        verify(reviewQueueRepo, never()).save(already);
        reviewQueueService.checkSlaBreaches();  // 异常被吞掉

        lenient().when(reviewQueueRepo.findNeedsEscalation(any())).thenReturn(List.of(toEscalate));
        reviewQueueService.checkEscalations();
    }

    // ===== 封禁名单 =====

    @Mock
    private BlockListRepo blockListRepo;

    @InjectMocks
    private BlockListService blockListService;

    private static BlockListEntity block(String id, String environmentId) {
        BlockListEntity b = new BlockListEntity();
        b.id = id;
        b.gameId = "g";
        b.environmentId = environmentId;
        b.targetType = "device";
        b.targetValue = "d1";
        b.targetName = "d1";
        b.isPermanent = true;
        return b;
    }

    @Test
    @DisplayName("封禁名单：isBlocked 命中路径与 addBlock 全参版本")
    void blockListCheckAndAdd() {
        lenient().when(blockListRepo.findActiveBlock(anyString(), anyString(), anyString(), any()))
            .thenReturn(Optional.empty());
        lenient().when(blockListRepo.save(any(BlockListEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        BlockListEntity envBlock = block("bl_env", "env1");
        lenient().when(blockListRepo.findActiveBlock(eq("g"), eq("device"), eq("d1"), any()))
            .thenReturn(Optional.of(envBlock));
        assertTrue(blockListService.isBlocked("g", "env1", "device", "d1"));
        assertFalse(blockListService.isBlocked("g", "env2", "device", "d1"));
        verify(blockListRepo).recordHit(eq("bl_env"), any());

        BlockListEntity globalBlock = block("bl_global", null);
        lenient().when(blockListRepo.findActiveBlock(eq("g"), eq("device"), eq("d0"), any()))
            .thenReturn(Optional.of(globalBlock));
        assertTrue(blockListService.isBlocked("g", "device", "d0"));
        verify(blockListRepo).recordHit(eq("bl_global"), any());
        assertFalse(blockListService.isBlocked("g", "device", "d_clean"));

        BlockListEntity existing = block("bl_exist", "env1");
        lenient().when(blockListRepo.findActiveBlock(eq("g"), eq("device"), eq("d9"), any()))
            .thenReturn(Optional.of(existing));
        assertEquals("bl_exist", blockListService.addBlock(
            "g", "env1", "device", "d9", "r", null,
            BlockListEntity.BlockType.HARD, false, 60, "op", null, null).id);

        BlockListEntity permanent = blockListService.addBlock(
            "g", "env1", "device", "d2", "cheat", "fraud",
            BlockListEntity.BlockType.SOFT, true, 30, "op", "rc_1", "note");
        assertTrue(permanent.id.startsWith("bl_"));
        assertTrue(permanent.isPermanent);
        assertNull(permanent.expiresAt);
        assertEquals(BlockListEntity.BlockType.SOFT, permanent.blockType);
        assertEquals("fraud", permanent.blockCategory);
        assertEquals("op", permanent.blockedBy);
        assertNotNull(permanent.blockedAt);
        assertEquals("rc_1", permanent.riskCaseId);

        BlockListEntity timed = blockListService.addBlock(
            "g", null, "ip", "1.2.3.4", "abuse", null,
            BlockListEntity.BlockType.TEMPORARY, false, 30, "op", null, null);
        assertFalse(timed.isPermanent);
        assertNotNull(timed.expiresAt);
        assertEquals("security", timed.blockCategory);
        assertEquals("1.2.3.4", timed.targetName);
    }

    @Test
    @DisplayName("封禁名单：从案例建封禁、解除/批量解除与详情查询")
    void blockListFromRiskCaseAndUnblock() {
        lenient().when(blockListRepo.save(any(BlockListEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(blockListRepo.findActiveBlock(anyString(), anyString(), anyString(), any()))
            .thenReturn(Optional.empty());

        lenient().when(riskCaseRepo.findById("rc_none")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
            () -> blockListService.createBlockFromRiskCase("rc_none", "op"));

        RiskCaseEntity critical = riskCase("rc_1", RiskCaseEntity.RiskLevel.CRITICAL, RiskCaseEntity.ActionType.BLOCK);
        critical.targetId = "d5";
        lenient().when(riskCaseRepo.findById("rc_1")).thenReturn(Optional.of(critical));
        BlockListEntity permanent = blockListService.createBlockFromRiskCase("rc_1", "op");
        assertTrue(permanent.isPermanent);
        assertNull(permanent.expiresAt);
        assertEquals("rc_1", permanent.riskCaseId);
        assertEquals("fraud", permanent.blockCategory);
        assertEquals(BlockListEntity.BlockType.HARD, permanent.blockType);
        assertEquals("d5", permanent.targetValue);
        assertTrue(permanent.blockReason.contains("CASE_rc_1"));

        RiskCaseEntity medium = riskCase("rc_2", RiskCaseEntity.RiskLevel.MEDIUM, RiskCaseEntity.ActionType.ALERT);
        medium.targetId = "d6";
        lenient().when(riskCaseRepo.findById("rc_2")).thenReturn(Optional.of(medium));
        BlockListEntity timed = blockListService.createBlockFromRiskCase("rc_2", "op");
        assertFalse(timed.isPermanent);
        assertNotNull(timed.expiresAt);

        RiskCaseEntity high = riskCase("rc_3", RiskCaseEntity.RiskLevel.HIGH, RiskCaseEntity.ActionType.ALERT);
        high.targetId = "d7";
        lenient().when(riskCaseRepo.findById("rc_3")).thenReturn(Optional.of(high));
        assertNotNull(blockListService.createBlockFromRiskCase("rc_3", "op").expiresAt);

        RiskCaseEntity low = riskCase("rc_4", RiskCaseEntity.RiskLevel.LOW, RiskCaseEntity.ActionType.ALERT);
        low.targetId = "d8";
        lenient().when(riskCaseRepo.findById("rc_4")).thenReturn(Optional.of(low));
        assertNotNull(blockListService.createBlockFromRiskCase("rc_4", "op").expiresAt);

        BlockListEntity activeBlock = block("bl_a", null);
        activeBlock.gameId = "g";
        activeBlock.blockReason = "risk";
        BlockListEntity inactiveBlock = block("bl_i", null);
        inactiveBlock.gameId = "g";
        inactiveBlock.blockReason = "risk";
        inactiveBlock.unblockedAt = LocalDateTime.now();
        lenient().when(blockListRepo.findById("bl_a")).thenReturn(Optional.of(activeBlock));
        lenient().when(blockListRepo.findById("bl_i")).thenReturn(Optional.of(inactiveBlock));

        blockListService.unblock("bl_a", "op", "wrong target");
        assertNotNull(activeBlock.unblockedAt);
        assertEquals("op", activeBlock.unblockedBy);
        assertEquals("wrong target", activeBlock.unblockReason);

        blockListService.unblock("bl_i", "op", "again");
        assertNull(inactiveBlock.unblockReason);
        verify(blockListRepo, never()).save(inactiveBlock);
        assertThrows(IllegalArgumentException.class,
            () -> blockListService.unblock("bl_missing", "op", "r"));

        BlockListEntity second = block("bl_a2", null);
        second.blockReason = "risk";
        lenient().when(blockListRepo.findById("bl_a2")).thenReturn(Optional.of(second));
        assertEquals(1, blockListService.batchUnblock(List.of("bl_a2", "bl_missing"), "op", "cleanup"));
        assertNotNull(second.unblockedAt);

        assertEquals("bl_a", blockListService.getBlock("bl_a").id);
        assertThrows(IllegalArgumentException.class, () -> blockListService.getBlock("bl_missing"));
    }

    // ===== 风控大屏指标 =====

    @Mock
    private ClickHouseClient client;

    @InjectMocks
    private RiskMetricsService riskMetricsService;

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    @Test
    @DisplayName("风控指标：trend/ruleHits/severity/actions 的 ClickHouse 查询路径")
    @SuppressWarnings("unchecked")
    void riskMetricsQueryPaths() {
        lenient().when(client.isAvailable()).thenReturn(true);

        lenient().when(client.query(contains("GROUP BY bucket"), eq("g"), any(Timestamp.class)))
            .thenReturn(List.of(
                row("bucket", LocalDateTime.of(2026, 9, 9, 10, 0), "severity", "HIGH", "c", 2),
                row("bucket", LocalDateTime.of(2026, 9, 9, 10, 0), "severity", "low", "c", 3)));
        Map<String, Object> trend = riskMetricsService.trend("g", null, null);
        assertEquals(Boolean.TRUE, trend.get("available"));
        assertEquals(24, trend.get("hours"));
        List<Map<String, Object>> points = (List<Map<String, Object>>) trend.get("points");
        assertEquals(1, points.size());
        assertEquals(5L, points.get(0).get("total"));
        assertEquals(2L, points.get(0).get("high"));
        assertEquals(3L, points.get(0).get("low"));

        Map<String, Object> envTrend = riskMetricsService.trend("g", "prod", 5000);
        assertEquals(2160, envTrend.get("hours"));
        assertTrue(((List<?>) envTrend.get("points")).isEmpty());
        verify(client).query(anyString(), eq("g"), eq("prod"), any(Timestamp.class));

        lenient().when(client.query(contains("GROUP BY rule_id"), eq("g"), any(Timestamp.class)))
            .thenReturn(List.of(
                row("rule_id", "rr_1", "risk_type", "payment", "hits", 10L, "subjects", 4L,
                    "avg_score", 88.5, "last_hit_at", Timestamp.valueOf("2026-09-09 10:00:00")),
                row("rule_id", "", "hits", 1L)));
        Map<String, Object> hits = riskMetricsService.ruleHits("g", null, 24);
        List<Map<String, Object>> rules = (List<Map<String, Object>>) hits.get("rules");
        assertEquals(2, rules.size());
        assertEquals("rr_1", rules.get(0).get("ruleId"));
        assertEquals(10L, rules.get(0).get("hits"));
        assertEquals(88.5, rules.get(0).get("avgScore"));
        assertFalse(String.valueOf(rules.get(0).get("lastHitAt")).isEmpty());
        assertEquals("unknown", rules.get(1).get("ruleId"));

        lenient().when(client.query(contains("GROUP BY severity"), eq("g"), any(Timestamp.class)))
            .thenReturn(List.of(row("severity", "critical", "c", 7), row("severity", "high", "c", 3)));
        lenient().when(client.query(contains("GROUP BY risk_type"), eq("g"), any(Timestamp.class)))
            .thenReturn(List.of(row("risk_type", "payment", "c", 2)));
        Map<String, Object> severity = riskMetricsService.severity("g", null, null);
        assertEquals(10L, severity.get("total"));
        List<Map<String, Object>> bySeverity = (List<Map<String, Object>>) severity.get("bySeverity");
        assertEquals("critical", bySeverity.get(0).get("severity"));
        assertEquals(7L, bySeverity.get(0).get("count"));
        assertEquals(1, ((List<?>) severity.get("byType")).size());

        lenient().when(client.query(contains("GROUP BY action"), eq("g"), any(Timestamp.class)))
            .thenReturn(List.of(row("action", "block", "c", 5)));
        lenient().when(client.query(contains("GROUP BY state"), eq("g"), any(Timestamp.class)))
            .thenReturn(List.of(row("state", "executed", "c", 4)));
        lenient().when(client.query(contains("ORDER BY ts DESC"), eq("g"), any(Timestamp.class)))
            .thenReturn(List.of(row("ts", Timestamp.valueOf("2026-09-09 10:00:00"),
                "risk_event_id", "ev1", "risk_case_id", "rc1", "rule_id", "rr_1",
                "action", "block", "state", "executed",
                "subject_type", "user", "subject_id", "u1", "severity", "high")));
        Map<String, Object> actions = riskMetricsService.actions("g", null, null);
        List<Map<String, Object>> byAction = (List<Map<String, Object>>) actions.get("byAction");
        assertEquals("block", byAction.get(0).get("action"));
        assertEquals(5L, byAction.get(0).get("count"));
        List<Map<String, Object>> byState = (List<Map<String, Object>>) actions.get("byState");
        assertEquals("executed", byState.get(0).get("state"));
        List<Map<String, Object>> recent = (List<Map<String, Object>>) actions.get("recent");
        assertEquals(1, recent.size());
        assertEquals("ev1", recent.get(0).get("riskEventId"));
        assertEquals("u1", recent.get(0).get("subjectId"));
    }

    @Test
    @DisplayName("风控指标：ClickHouse 不可用降级与 hours 边界")
    void riskMetricsUnavailable() {
        lenient().when(client.isAvailable()).thenReturn(false);

        Map<String, Object> trend = riskMetricsService.trend("g", null, null);
        assertEquals(Boolean.FALSE, trend.get("available"));
        assertEquals(24, trend.get("hours"));
        assertEquals(Boolean.FALSE, riskMetricsService.ruleHits("g", "prod", 1).get("available"));
        assertEquals(Boolean.FALSE, riskMetricsService.severity("g", null, 0).get("available"));
        assertEquals(24, riskMetricsService.severity("g", null, 0).get("hours"));
        Map<String, Object> actions = riskMetricsService.actions("g", null, 24 * 90 + 1);
        assertEquals(Boolean.FALSE, actions.get("available"));
        assertEquals(2160, actions.get("hours"));
    }
}
