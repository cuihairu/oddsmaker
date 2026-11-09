package io.oddsmaker.control.service;

import io.oddsmaker.control.dto.GameDTO;
import io.oddsmaker.control.jpa.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
/**
 * 安全与游戏域 Service 深度单元测试（LAY-004 拆分）：管道 CRUD/执行/质量规则/统计，风控大屏概览/趋势/排名/规则表现/封禁/任务/近期案例/审核队列。与 GameServiceTest / DashboardServicesTest 互补。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("管道与风控大屏域 Service 深度测试")
class PipelineRiskDashboardDeepTest {

// ===== 共享 Mock =====

    @Mock
    private AuditLogService auditLog;

    // ===== PipelineService =====

    @Mock
    private PipelineRepo pipelineRepo;

    @Mock
    private PipelineJobRepo pipelineJobRepo;

    @Mock
    private DataQualityRuleRepo dataQualityRuleRepo;

    @Mock
    private ClickHouseClient clickHouseClient;

    @InjectMocks
    private PipelineService pipelineService;

    // ===== RiskDashboardService =====

    @Mock
    private RiskCaseRepo riskCaseRepo;

    @Mock
    private RiskRuleRepo riskRuleRepo;

    @Mock
    private BlockListRepo blockListRepo;

    @Mock
    private FlinkJobRepo flinkJobRepo;

    @InjectMocks
    private RiskDashboardService riskDashboardService;

    // =========================================================
    // SecurityService
    // =========================================================

    private PipelineEntity activePipeline(String id) {
        PipelineEntity p = new PipelineEntity();
        p.id = id;
        p.gameId = "g1";
        p.environmentId = "env_g1_prod";
        p.pipelineName = "nightly-etl";
        p.pipelineType = PipelineEntity.PipelineType.ETL;
        p.pipelineStatus = PipelineEntity.PipelineStatus.ACTIVE;
        p.enabled = true;
        p.createdBy = "admin";
        return p;
    }

    private RiskCaseEntity riskCase(String id, RiskCaseEntity.RiskLevel level,
                                    RiskCaseEntity.ActionType action,
                                    RiskCaseEntity.ExecutionStatus status,
                                    String reviewStatus) {
        RiskCaseEntity c = new RiskCaseEntity();
        c.id = id;
        c.riskRuleId = "r1";
        c.gameId = "g1";
        c.caseNumber = "CASE_1";
        c.targetType = "user_id";
        c.targetId = "t-1";
        c.riskLevel = level;
        c.actionTaken = action;
        c.executionStatus = status;
        c.reviewStatus = reviewStatus;
        c.createdAt = LocalDateTime.now();
        return c;
    }

    private BlockListEntity blockEntity(String id, boolean permanent) {
        BlockListEntity b = new BlockListEntity();
        b.id = id;
        b.gameId = "g1";
        b.targetType = "user_id";
        b.targetValue = "t-1";
        b.blockType = BlockListEntity.BlockType.HARD;
        b.isPermanent = permanent;
        b.hitCount = 0L;
        return b;
    }

    private FlinkJobEntity flinkJob(String id, FlinkJobEntity.JobStatus status) {
        FlinkJobEntity j = new FlinkJobEntity();
        j.id = id;
        j.gameId = "g1";
        j.name = "job-" + id;
        j.jobType = "risk_evaluation";
        j.status = status;
        j.totalEventsProcessed = 0L;
        j.totalRiskCasesCreated = 0L;
        j.totalActionsExecuted = 0L;
        return j;
    }

    @Test
    @DisplayName("createPipeline：序列化三类配置并记录审计")
    void createPipelineSerializesConfigs() {
        lenient().when(pipelineRepo.save(any(PipelineEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        PipelineEntity pipeline = pipelineService.createPipeline(
            "g1", "env_g1_prod", "nightly-etl", PipelineEntity.PipelineType.ETL, "nightly job",
            Map.of("source", "kafka"), Map.of("op", "dedup"), Map.of("dest", "clickhouse"), "admin");

        assertTrue(pipeline.id.startsWith("pipe_"));
        assertEquals(PipelineEntity.PipelineStatus.DRAFT, pipeline.pipelineStatus);
        assertEquals("admin", pipeline.createdBy);
        assertTrue(pipeline.sourceConfig.contains("kafka"));
        assertTrue(pipeline.transformConfig.contains("dedup"));
        assertTrue(pipeline.destinationConfig.contains("clickhouse"));
        verify(auditLog).logCreate(eq("pipeline"), eq(pipeline.id), eq("nightly-etl"),
            eq("admin"), eq("admin"), isNull(), anyMap());
    }

    @Test
    @DisplayName("createPipeline：null 配置保持为空")
    void createPipelineWithNullConfigs() {
        lenient().when(pipelineRepo.save(any(PipelineEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        PipelineEntity pipeline = pipelineService.createPipeline(
            "g1", null, "empty", PipelineEntity.PipelineType.STREAMING, null,
            null, null, null, "admin");

        assertNull(pipeline.sourceConfig);
        assertNull(pipeline.transformConfig);
        assertNull(pipeline.destinationConfig);
        assertNull(pipeline.environmentId);
    }

    @Test
    @DisplayName("getPipeline：不存在抛出异常")
    void getPipelineRejectsMissing() {
        lenient().when(pipelineRepo.findById("pipe_missing")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> pipelineService.getPipeline("pipe_missing"));
    }

    @Test
    @DisplayName("activate/pause/stopPipeline：状态流转；不存在抛异常")
    void pipelineLifecycleTransitions() {
        PipelineEntity pipeline = new PipelineEntity();
        pipeline.id = "p1";
        pipeline.pipelineName = "etl";
        pipeline.pipelineStatus = PipelineEntity.PipelineStatus.DRAFT;
        lenient().when(pipelineRepo.findById("p1")).thenReturn(Optional.of(pipeline));
        lenient().when(pipelineRepo.save(any(PipelineEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        assertEquals(PipelineEntity.PipelineStatus.ACTIVE, pipelineService.activatePipeline("p1").pipelineStatus);
        assertEquals(PipelineEntity.PipelineStatus.PAUSED, pipelineService.pausePipeline("p1").pipelineStatus);
        assertEquals(PipelineEntity.PipelineStatus.STOPPED, pipelineService.stopPipeline("p1").pipelineStatus);

        lenient().when(pipelineRepo.findById("pipe_missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> pipelineService.activatePipeline("pipe_missing"));
    }

    @Test
    @DisplayName("executePipeline：不存在/草稿/禁用均被拒绝")
    void executePipelineRejectsInvalidStates() {
        lenient().when(pipelineRepo.findById("pipe_missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
            () -> pipelineService.executePipeline("pipe_missing", "tester"));

        PipelineEntity draft = activePipeline("p_draft");
        draft.pipelineStatus = PipelineEntity.PipelineStatus.DRAFT;
        lenient().when(pipelineRepo.findById("p_draft")).thenReturn(Optional.of(draft));
        IllegalStateException draftEx = assertThrows(IllegalStateException.class,
            () -> pipelineService.executePipeline("p_draft", "tester"));
        assertTrue(draftEx.getMessage().contains("not active"));

        PipelineEntity disabled = activePipeline("p_disabled");
        disabled.enabled = false;
        lenient().when(pipelineRepo.findById("p_disabled")).thenReturn(Optional.of(disabled));
        assertThrows(IllegalStateException.class,
            () -> pipelineService.executePipeline("p_disabled", "tester"));

        verify(pipelineJobRepo, never()).save(any());
    }

    @Test
    @DisplayName("executePipeline：活跃管道执行完成并评估质量规则")
    void executePipelineCompletesJob() {
        PipelineEntity pipeline = activePipeline("p1");
        lenient().when(pipelineRepo.findById("p1")).thenReturn(Optional.of(pipeline));
        lenient().when(pipelineJobRepo.save(any(PipelineJobEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(pipelineRepo.save(any(PipelineEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        DataQualityRuleEntity rule = new DataQualityRuleEntity();
        rule.ruleName = "not-null";
        rule.ruleStatus = DataQualityRuleEntity.RuleStatus.ACTIVE;
        rule.enabled = true;
        rule.actionOnFailure = "stop";
        rule.ruleType = DataQualityRuleEntity.RuleType.COMPLETENESS;
        rule.targetTable = "events";
        rule.targetColumn = "user_id";
        lenient().when(dataQualityRuleRepo.findByPipelineId("p1")).thenReturn(List.of(rule));
        lenient().when(clickHouseClient.query(anyString(), any(Object[].class)))
            .thenReturn(List.of(Map.of("total", 5000L, "v", 0L)));

        PipelineJobEntity job = pipelineService.executePipeline("p1", "tester");

        assertTrue(job.id.startsWith("pjob_"));
        assertEquals("tester", job.triggeredBy);
        assertEquals("manual", job.triggerType);
        assertEquals(PipelineJobEntity.JobStatus.COMPLETED, job.jobStatus);
        assertNotNull(job.startedAt);
        assertNotNull(job.completedAt);
        assertTrue(job.processedRows >= 1000);
        assertEquals(1, pipeline.runCount);
        assertEquals(1, pipeline.successCount);
        assertNotNull(pipeline.lastRunAt);
        assertEquals(1, rule.totalEvaluations);
        verify(dataQualityRuleRepo).save(rule);
        verify(pipelineRepo).save(pipeline);
    }

    @Test
    @DisplayName("getPipelineStats：运行统计与任务状态聚合")
    void getPipelineStatsAggregates() {
        PipelineEntity pipeline = activePipeline("p1");
        pipeline.runCount = 10;
        pipeline.successCount = 8;
        pipeline.failureCount = 2;
        pipeline.lastRunAt = LocalDateTime.now().minusHours(1);
        pipeline.lastSuccessAt = LocalDateTime.now().minusHours(1);
        lenient().when(pipelineRepo.findById("p1")).thenReturn(Optional.of(pipeline));

        PipelineJobEntity done = new PipelineJobEntity();
        done.jobStatus = PipelineJobEntity.JobStatus.COMPLETED;
        PipelineJobEntity failed = new PipelineJobEntity();
        failed.jobStatus = PipelineJobEntity.JobStatus.FAILED;
        lenient().when(pipelineJobRepo.findByPipelineId("p1")).thenReturn(List.of(done, failed));

        Map<String, Object> stats = pipelineService.getPipelineStats("p1");

        assertEquals(PipelineEntity.PipelineStatus.ACTIVE, stats.get("pipelineStatus"));
        assertEquals(10, stats.get("totalRuns"));
        assertEquals(8, stats.get("successCount"));
        assertEquals(2, stats.get("failureCount"));
        assertEquals(80.0, (double) stats.get("successRate"), 0.0001);
        assertEquals(2L, stats.get("totalJobs"));
        assertEquals(1L, stats.get("completedJobs"));
        assertEquals(1L, stats.get("failedJobs"));
        assertEquals(pipeline.lastRunAt, stats.get("lastRun"));
    }

    @Test
    @DisplayName("createQualityRule：序列化规则定义并记录审计")
    void createQualityRuleSerializesDefinition() {
        lenient().when(dataQualityRuleRepo.save(any(DataQualityRuleEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        DataQualityRuleEntity rule = pipelineService.createQualityRule(
            "g1", "p1", "not-null-user", DataQualityRuleEntity.RuleType.COMPLETENESS,
            DataQualityRuleEntity.Severity.ERROR, "events", "user_id",
            Map.of("min", 1), "admin");

        assertTrue(rule.id.startsWith("dqr_"));
        assertEquals(DataQualityRuleEntity.Severity.ERROR, rule.severity);
        assertEquals("events", rule.targetTable);
        assertEquals("user_id", rule.targetColumn);
        assertTrue(rule.ruleDefinition.contains("min"));
        verify(auditLog).logCreate(eq("data_quality_rule"), eq(rule.id), eq("not-null-user"),
            eq("admin"), eq("admin"), isNull(), anyMap());
    }

    @Test
    @DisplayName("executeScheduledPipelines：到期管道被调度执行")
    void executeScheduledPipelinesRunsDuePipeline() {
        PipelineEntity pipeline = activePipeline("p1");
        lenient().when(pipelineRepo.findScheduledPipelines(any(LocalDateTime.class)))
            .thenReturn(List.of(pipeline));
        lenient().when(pipelineJobRepo.save(any(PipelineJobEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(pipelineRepo.save(any(PipelineEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        org.springframework.test.util.ReflectionTestUtils.setField(pipelineService, "scheduleEnabled", true);

        assertDoesNotThrow(() -> pipelineService.executeScheduledPipelines());

        ArgumentCaptor<PipelineJobEntity> captor = ArgumentCaptor.forClass(PipelineJobEntity.class);
        // 创建任务一次 + executePipelineJob 收尾一次
        verify(pipelineJobRepo, times(2)).save(captor.capture());
        assertEquals("schedule", captor.getValue().triggerType);
        assertEquals("system", captor.getValue().triggeredBy);
        assertEquals(PipelineJobEntity.JobStatus.COMPLETED, captor.getValue().jobStatus);
        assertEquals(1, pipeline.runCount);
    }

    @Test
    @DisplayName("cleanupOldJobs：删除90天前的完成任务")
    void cleanupOldJobsInvokesRepo() {
        lenient().when(pipelineJobRepo.deleteCompletedBefore(any(LocalDateTime.class))).thenReturn(7);

        assertDoesNotThrow(() -> pipelineService.cleanupOldJobs());

        verify(pipelineJobRepo).deleteCompletedBefore(any(LocalDateTime.class));
    }

    @Test
    @DisplayName("getOverview：案例分组/高风险/待审核/封禁/作业计数")
    void getOverviewAggregatesRealData() {
        RiskCaseEntity blocked = riskCase("c1", RiskCaseEntity.RiskLevel.HIGH,
            RiskCaseEntity.ActionType.BLOCK, RiskCaseEntity.ExecutionStatus.EXECUTED, null);
        RiskCaseEntity pendingReview = riskCase("c2", RiskCaseEntity.RiskLevel.MEDIUM,
            RiskCaseEntity.ActionType.ALERT, RiskCaseEntity.ExecutionStatus.PENDING, "pending");
        lenient().when(riskCaseRepo.countByGameIdSince(eq("g1"), any(LocalDateTime.class))).thenReturn(5L);
        lenient().when(riskCaseRepo.findByGameId("g1")).thenReturn(List.of(blocked, pendingReview));
        lenient().when(blockListRepo.findActiveBlocks(eq("g1"), any(LocalDateTime.class)))
            .thenReturn(List.of(blockEntity("b1", true)));
        lenient().when(flinkJobRepo.findRunningJobs("g1"))
            .thenReturn(List.of(flinkJob("j1", FlinkJobEntity.JobStatus.RUNNING)));

        Map<String, Object> overview = riskDashboardService.getOverview("g1", LocalDateTime.now().minusDays(1));

        assertEquals(5L, overview.get("totalCases"));
        assertEquals(1L, overview.get("highRiskCases"));
        assertEquals(1L, overview.get("pendingReview"));
        assertEquals(1, overview.get("activeBlocks"));
        assertEquals(1, overview.get("runningJobs"));
        Map<?, ?> byRiskLevel = (Map<?, ?>) overview.get("byRiskLevel");
        assertEquals(1L, byRiskLevel.get("HIGH"));
        assertEquals(1L, byRiskLevel.get("MEDIUM"));
        Map<?, ?> byActionType = (Map<?, ?>) overview.get("byActionType");
        assertEquals(1L, byActionType.get("BLOCK"));
    }

    @Test
    @DisplayName("getRiskTrends：按时间桶统计高风险/封禁/已审核案例")
    void getRiskTrendsAggregatesBuckets() {
        LocalDateTime since = LocalDateTime.now().minusHours(3);
        RiskCaseEntity blockedHigh = riskCase("c1", RiskCaseEntity.RiskLevel.HIGH,
            RiskCaseEntity.ActionType.BLOCK, RiskCaseEntity.ExecutionStatus.EXECUTED, null);
        RiskCaseEntity reviewed = riskCase("c2", RiskCaseEntity.RiskLevel.MEDIUM,
            RiskCaseEntity.ActionType.ALERT, RiskCaseEntity.ExecutionStatus.PENDING, "pending");
        lenient().when(riskCaseRepo.findByGameIdAndTimeRange(eq("g1"), any(LocalDateTime.class), any(LocalDateTime.class)))
            .thenReturn(List.of(blockedHigh, reviewed));

        List<Map<String, Object>> trends = riskDashboardService.getRiskTrends("g1", since, 24);

        assertEquals(1, trends.size());
        Map<String, Object> row = trends.get(0);
        assertEquals(2L, row.get("totalCases"));
        assertEquals(1L, row.get("highRiskCases"));
        assertEquals(1L, row.get("blockedCases"));
        assertEquals(1L, row.get("reviewedCases"));
    }

    @Test
    @DisplayName("getHighRiskTargets：Object[] 行映射且受 limit 截断")
    void getHighRiskTargetsMapsRows() {
        List<Object[]> rows = List.<Object[]>of(
            new Object[]{"user_id", "u-77", 5L},
            new Object[]{"device_id", "d-1", 3L});
        lenient().when(riskCaseRepo.findFrequentTargets(any(LocalDateTime.class), anyLong()))
            .thenReturn(rows);

        List<Map<String, Object>> targets = riskDashboardService.getHighRiskTargets(
            "g1", LocalDateTime.now().minusDays(1), 1);

        assertEquals(1, targets.size());
        assertEquals("user_id", targets.get(0).get("targetType"));
        assertEquals("u-77", targets.get(0).get("targetId"));
        assertEquals(5L, targets.get(0).get("caseCount"));
    }

    @Test
    @DisplayName("getRulePerformance：触发/拦截计数、blockRate 与名称回退")
    void getRulePerformanceComputesRates() {
        RiskRuleEntity withStats = new RiskRuleEntity();
        withStats.id = "r1";
        withStats.name = "speed-hack";
        withStats.displayName = null;
        withStats.category = RiskRuleEntity.RuleCategory.BEHAVIOR;
        withStats.riskLevel = RiskRuleEntity.RiskLevel.HIGH;
        withStats.actionType = RiskRuleEntity.ActionType.BLOCK;
        withStats.totalTriggeredCount = 10L;
        withStats.totalBlockedCount = 4L;
        withStats.lastTriggeredAt = LocalDateTime.now().minusHours(1);

        RiskRuleEntity withoutStats = new RiskRuleEntity();
        withoutStats.id = "r2";
        withoutStats.name = "payment-spike";
        withoutStats.displayName = "Payment Spike";
        withoutStats.category = RiskRuleEntity.RuleCategory.PAYMENT;
        withoutStats.riskLevel = RiskRuleEntity.RiskLevel.MEDIUM;
        withoutStats.actionType = RiskRuleEntity.ActionType.REVIEW;
        withoutStats.totalTriggeredCount = null;
        withoutStats.totalBlockedCount = null;

        lenient().when(riskRuleRepo.findActiveByGameId("g1")).thenReturn(List.of(withStats, withoutStats));
        lenient().when(riskCaseRepo.findByRiskRuleId("r1")).thenReturn(List.of(
            riskCase("c1", RiskCaseEntity.RiskLevel.HIGH, RiskCaseEntity.ActionType.BLOCK,
                RiskCaseEntity.ExecutionStatus.EXECUTED, null),
            riskCase("c2", RiskCaseEntity.RiskLevel.MEDIUM, RiskCaseEntity.ActionType.BLOCK,
                RiskCaseEntity.ExecutionStatus.PENDING, null)));
        lenient().when(riskCaseRepo.findByRiskRuleId("r2")).thenReturn(List.of());

        List<Map<String, Object>> performance = riskDashboardService.getRulePerformance("g1");

        assertEquals(2, performance.size());
        Map<String, Object> first = performance.get(0);
        assertEquals("r1", first.get("ruleId"));
        assertEquals("speed-hack", first.get("ruleName"));
        assertEquals("BEHAVIOR", first.get("category"));
        assertEquals(10L, first.get("triggerCount"));
        assertEquals(4L, first.get("blockCount"));
        assertEquals(0.4, (double) first.get("blockRate"), 0.0001);
        assertEquals(2, first.get("recentCases"));
        assertEquals(withStats.lastTriggeredAt, first.get("lastTriggered"));

        Map<String, Object> second = performance.get(1);
        assertEquals("Payment Spike", second.get("ruleName"));
        assertEquals(0L, second.get("triggerCount"));
        assertEquals(0.0, (double) second.get("blockRate"), 0.0001);
        assertEquals(0, second.get("recentCases"));
    }

    @Test
    @DisplayName("getBlockStats：永久/临时封禁、分类回退与命中合计")
    void getBlockStatsAggregatesBlocks() {
        BlockListEntity permanent = blockEntity("b1", true);
        permanent.targetType = "user_id";
        permanent.blockCategory = "fraud";
        permanent.blockType = BlockListEntity.BlockType.HARD;
        permanent.hitCount = 5L;

        BlockListEntity temporary = blockEntity("b2", false);
        temporary.targetType = "device_id";
        temporary.blockCategory = null;
        temporary.blockType = BlockListEntity.BlockType.SOFT;
        temporary.hitCount = 3L;

        lenient().when(blockListRepo.findActiveBlocks(eq("g1"), any(LocalDateTime.class)))
            .thenReturn(List.of(permanent, temporary));

        Map<String, Object> stats = riskDashboardService.getBlockStats("g1");

        assertEquals(2, stats.get("totalActive"));
        assertEquals(1L, stats.get("permanentBlocks"));
        assertEquals(1L, stats.get("temporaryBlocks"));
        assertEquals(8L, stats.get("totalHits"));
        Map<?, ?> byCategory = (Map<?, ?>) stats.get("byCategory");
        assertEquals(1L, byCategory.get("fraud"));
        assertEquals(1L, byCategory.get("unknown"));
        Map<?, ?> byBlockType = (Map<?, ?>) stats.get("byBlockType");
        assertEquals(1L, byBlockType.get("HARD"));
        assertEquals(1L, byBlockType.get("SOFT"));
    }

    @Test
    @DisplayName("getJobStats：事件/案例/动作合计与状态、类型分组")
    void getJobStatsAggregatesJobs() {
        FlinkJobEntity running = flinkJob("j1", FlinkJobEntity.JobStatus.RUNNING);
        running.jobType = "risk_evaluation";
        running.totalEventsProcessed = 1000L;
        running.totalRiskCasesCreated = 10L;
        running.totalActionsExecuted = 5L;

        FlinkJobEntity failed = flinkJob("j2", FlinkJobEntity.JobStatus.FAILED);
        failed.jobType = "fraud_detection";
        failed.totalEventsProcessed = null;
        failed.totalRiskCasesCreated = null;
        failed.totalActionsExecuted = null;

        lenient().when(flinkJobRepo.findByGameId("g1")).thenReturn(List.of(running, failed));
        lenient().when(flinkJobRepo.findRunningJobs("g1")).thenReturn(List.of(running));

        Map<String, Object> stats = riskDashboardService.getJobStats("g1");

        assertEquals(2, stats.get("totalJobs"));
        assertEquals(1, stats.get("runningJobs"));
        assertEquals(1L, stats.get("stoppedJobs"));
        assertEquals(1L, stats.get("failedJobs"));
        assertEquals(1000L, stats.get("totalEventsProcessed"));
        assertEquals(10L, stats.get("totalRiskCasesCreated"));
        assertEquals(5L, stats.get("totalActionsExecuted"));
        Map<?, ?> byStatus = (Map<?, ?>) stats.get("byStatus");
        assertEquals(1L, byStatus.get("RUNNING"));
        assertEquals(1L, byStatus.get("FAILED"));
        Map<?, ?> byType = (Map<?, ?>) stats.get("byType");
        assertEquals(1L, byType.get("risk_evaluation"));
        assertEquals(1L, byType.get("fraud_detection"));
    }

    @Test
    @DisplayName("getRecentCases：字段映射、reviewStatus 空值回退与 limit 截断")
    void getRecentCasesMapsFields() {
        RiskCaseEntity withReview = riskCase("c1", RiskCaseEntity.RiskLevel.HIGH,
            RiskCaseEntity.ActionType.BLOCK, RiskCaseEntity.ExecutionStatus.EXECUTED, "pending");
        withReview.createdAt = LocalDateTime.now().minusMinutes(30);
        RiskCaseEntity noReview = riskCase("c2", RiskCaseEntity.RiskLevel.LOW,
            RiskCaseEntity.ActionType.ALERT, RiskCaseEntity.ExecutionStatus.PENDING, null);
        noReview.createdAt = LocalDateTime.now();
        lenient().when(riskCaseRepo.findByGameId("g1")).thenReturn(List.of(withReview, noReview));

        List<Map<String, Object>> cases = riskDashboardService.getRecentCases("g1", 1);

        assertEquals(1, cases.size());
        Map<String, Object> row = cases.get(0);
        assertEquals("c1", row.get("caseId"));
        assertEquals("CASE_1", row.get("caseNumber"));
        assertEquals("user_id", row.get("targetType"));
        assertEquals("t-1", row.get("targetId"));
        assertEquals("HIGH", row.get("riskLevel"));
        assertEquals("BLOCK", row.get("actionTaken"));
        assertEquals("EXECUTED", row.get("status"));
        assertEquals("pending", row.get("reviewStatus"));
    }

    @Test
    @DisplayName("getReviewQueueStats：待审数量/高优先级与平均等待时间")
    void getReviewQueueStatsComputesAverages() {
        RiskCaseEntity high = riskCase("c1", RiskCaseEntity.RiskLevel.HIGH,
            RiskCaseEntity.ActionType.BLOCK, RiskCaseEntity.ExecutionStatus.EXECUTED, "pending");
        high.createdAt = LocalDateTime.now().minusMinutes(10);
        RiskCaseEntity low = riskCase("c2", RiskCaseEntity.RiskLevel.MEDIUM,
            RiskCaseEntity.ActionType.REVIEW, RiskCaseEntity.ExecutionStatus.PENDING, "pending");
        low.createdAt = LocalDateTime.now().minusMinutes(10);
        lenient().when(riskCaseRepo.findPendingReview("g1")).thenReturn(List.of(high, low));

        Map<String, Object> stats = riskDashboardService.getReviewQueueStats("g1");

        assertEquals(2L, stats.get("totalPending"));
        assertEquals(1L, stats.get("highPriority"));
        double avgWait = (double) stats.get("avgWaitMinutes");
        assertTrue(avgWait >= 9.0 && avgWait <= 11.0);
        Map<?, ?> byRiskLevel = (Map<?, ?>) stats.get("byRiskLevel");
        assertEquals(1L, byRiskLevel.get("HIGH"));
        assertEquals(1L, byRiskLevel.get("MEDIUM"));
    }

    @Test
    @DisplayName("RiskDashboard 对侧：needsReview 已审、hitCount null、reviewStatus none 回退、createdAt null 过滤")
    void riskDashboardNullSides() {
        // 75 行：needsReview true && reviewStatus 非 null（不计入 pendingReview）
        RiskCaseEntity reviewed = riskCase("c9", RiskCaseEntity.RiskLevel.HIGH,
            RiskCaseEntity.ActionType.BLOCK, RiskCaseEntity.ExecutionStatus.EXECUTED, "approved");
        reviewed.createdAt = LocalDateTime.now();
        lenient().when(riskCaseRepo.countByGameIdSince(eq("g1"), any(LocalDateTime.class))).thenReturn(1L);
        lenient().when(riskCaseRepo.findByGameId("g1")).thenReturn(List.of(reviewed));
        lenient().when(blockListRepo.findActiveBlocks(eq("g1"), any(LocalDateTime.class)))
            .thenReturn(List.of());
        lenient().when(flinkJobRepo.findRunningJobs("g1")).thenReturn(List.of());
        Map<String, Object> overview = riskDashboardService.getOverview("g1", LocalDateTime.now().minusDays(1));
        assertEquals(0L, overview.get("pendingReview"));

        // 216 行：hitCount null → 计 0
        BlockListEntity noHits = blockEntity("b9", true);
        noHits.hitCount = null;
        lenient().when(blockListRepo.findActiveBlocks(eq("g2"), any(LocalDateTime.class)))
            .thenReturn(List.of(noHits));
        Map<String, Object> stats = riskDashboardService.getBlockStats("g2");
        assertEquals(0L, stats.get("totalHits"));

        // 302 行：reviewStatus null → "none"
        RiskCaseEntity noReview = riskCase("c10", RiskCaseEntity.RiskLevel.LOW,
            RiskCaseEntity.ActionType.ALERT, RiskCaseEntity.ExecutionStatus.PENDING, null);
        noReview.createdAt = LocalDateTime.now();
        lenient().when(riskCaseRepo.findByGameId("g3")).thenReturn(List.of(noReview));
        List<Map<String, Object>> recent = riskDashboardService.getRecentCases("g3", 5);
        assertEquals("none", recent.get(0).get("reviewStatus"));

        // 325 行：createdAt null 不参与平均等待
        RiskCaseEntity noTs = riskCase("c11", RiskCaseEntity.RiskLevel.MEDIUM,
            RiskCaseEntity.ActionType.REVIEW, RiskCaseEntity.ExecutionStatus.PENDING, "pending");
        noTs.createdAt = null;
        lenient().when(riskCaseRepo.findPendingReview("g4")).thenReturn(List.of(noTs));
        Map<String, Object> qs = riskDashboardService.getReviewQueueStats("g4");
        assertEquals(1L, qs.get("totalPending"));
        assertEquals(0.0, qs.get("avgWaitMinutes"));
    }
}
