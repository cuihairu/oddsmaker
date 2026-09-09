package io.oddsmaker.control.jpa;

import io.oddsmaker.control.dto.EnvironmentDTO;
import io.oddsmaker.control.dto.GameDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖率冲刺第二轮：补齐 EntitiesDeepTest 未覆盖的实体业务分支与生命周期回调。
 * 与 EntitiesSmokeCoverageTest（空构造冒烟）、EntitiesDeepTest（主要状态方法）互补。
 */
@DisplayName("JPA 实体剩余分支覆盖冲刺")
class EntitiesSweep2Test {

    @Test
    @DisplayName("MaintenanceWindowEntity：状态判定/起止条件/状态迁移/实际时长与生命周期回调")
    void maintenanceWindowEntity() {
        LocalDateTime now = LocalDateTime.now();
        MaintenanceWindowEntity m = new MaintenanceWindowEntity();
        m.title = "数据库升级维护";
        m.createdBy = "运维-张三";
        m.scheduledStart = now.minusMinutes(10);
        m.scheduledEnd = now.plusHours(2);

        assertTrue(m.isScheduled());
        assertFalse(m.isPending());
        assertFalse(m.isInProgress());
        assertFalse(m.isCompleted());
        assertFalse(m.isActive());
        assertFalse(m.isOverdue());
        assertFalse(m.isEmergency());
        assertTrue(m.isGlobal());

        m.maintenanceStatus = MaintenanceWindowEntity.MaintenanceStatus.PENDING;
        assertTrue(m.isPending());
        assertTrue(m.shouldStart());
        m.scheduledStart = now.plusHours(1);
        assertFalse(m.shouldStart());
        m.scheduledStart = null;
        assertFalse(m.shouldStart());
        m.scheduledStart = now.minusMinutes(10);

        m.maintenanceStatus = MaintenanceWindowEntity.MaintenanceStatus.IN_PROGRESS;
        assertTrue(m.isInProgress());
        assertTrue(m.isActive());
        assertFalse(m.shouldEnd());
        assertFalse(m.isOverdue());
        m.scheduledEnd = now.minusMinutes(1);
        assertTrue(m.shouldEnd());
        assertTrue(m.isOverdue());
        m.scheduledEnd = null;
        assertFalse(m.shouldEnd());
        assertFalse(m.isOverdue());

        m.maintenanceType = MaintenanceWindowEntity.MaintenanceType.EMERGENCY;
        assertTrue(m.isEmergency());
        m.maintenanceType = MaintenanceWindowEntity.MaintenanceType.SCHEDULED;
        assertFalse(m.isEmergency());

        m.impactScope = MaintenanceWindowEntity.ImpactScope.GAME;
        m.gameId = "g1";
        assertFalse(m.isGlobal());
        assertTrue(m.affectsGame("g1"));
        assertFalse(m.affectsGame("g2"));
        m.gameId = null;
        assertFalse(m.affectsGame("g1"));
        m.impactScope = MaintenanceWindowEntity.ImpactScope.GLOBAL;
        assertTrue(m.affectsGame("任意游戏"));

        m.start();
        assertTrue(m.isInProgress());
        assertNotNull(m.actualStart);
        m.pause();
        assertTrue(m.isActive());
        m.resume();
        assertTrue(m.isInProgress());
        m.complete("升级完成");
        assertTrue(m.isCompleted());
        assertNotNull(m.actualEnd);
        assertEquals("升级完成", m.completionNotes);
        assertEquals(100, m.progressPercent);
        m.resume();
        assertTrue(m.isCompleted());

        MaintenanceWindowEntity c = new MaintenanceWindowEntity();
        c.title = "紧急扩容";
        c.createdBy = "运维-李四";
        c.cancel("变更取消");
        assertEquals(MaintenanceWindowEntity.MaintenanceStatus.CANCELLED, c.maintenanceStatus);
        assertEquals("变更取消", c.completionNotes);
        LocalDateTime extendedTo = LocalDateTime.now().plusHours(5);
        c.extend(extendedTo);
        assertEquals(MaintenanceWindowEntity.MaintenanceStatus.EXTENDED, c.maintenanceStatus);
        assertEquals(extendedTo, c.extendedUntil);
        assertEquals(extendedTo, c.scheduledEnd);

        MaintenanceWindowEntity d = new MaintenanceWindowEntity();
        assertEquals(0, d.getActualDurationMinutes());
        d.actualStart = now.minusMinutes(30);
        assertTrue(d.getActualDurationMinutes() >= 29);
        d.actualEnd = now.minusMinutes(10);
        assertEquals(20, d.getActualDurationMinutes());

        assertNull(m.createdAt);
        m.onCreate();
        assertNotNull(m.createdAt);
        LocalDateTime preset = now.minusDays(1);
        m.createdAt = preset;
        m.onCreate();
        assertEquals(preset, m.createdAt);
        m.onUpdate();
        assertNotNull(m.updatedAt);
    }

    @Test
    @DisplayName("SystemAlertEntity：级别/状态判定/升级需求/确认解决与持续时间")
    void systemAlertEntity() {
        LocalDateTime now = LocalDateTime.now();
        SystemAlertEntity a = new SystemAlertEntity();
        a.title = "CPU 使用率过高";
        a.alertType = SystemAlertEntity.AlertType.HIGH_CPU;
        a.firstOccurredAt = now.minusMinutes(30);

        assertTrue(a.isOpen());
        assertFalse(a.isAcknowledged());
        assertFalse(a.isResolved());
        assertFalse(a.isCritical());
        assertFalse(a.needsEscalation());

        a.severity = SystemAlertEntity.Severity.CRITICAL;
        assertTrue(a.isCritical());
        assertFalse(a.isWarning());
        assertTrue(a.needsEscalation());
        a.escalate();
        assertEquals(1, a.escalationLevel);
        assertNotNull(a.escalatedAt);
        assertFalse(a.needsEscalation());
        a.escalationLevel = 0;
        a.alertStatus = SystemAlertEntity.AlertStatus.RESOLVED;
        assertTrue(a.isResolved());
        assertFalse(a.needsEscalation());
        a.alertStatus = SystemAlertEntity.AlertStatus.CLOSED;
        assertTrue(a.isResolved());
        a.alertStatus = SystemAlertEntity.AlertStatus.OPEN;

        a.severity = SystemAlertEntity.Severity.EMERGENCY;
        assertTrue(a.isCritical());
        a.severity = SystemAlertEntity.Severity.WARNING;
        assertTrue(a.isWarning());
        a.severity = SystemAlertEntity.Severity.INFO;
        assertTrue(a.isWarning());
        a.severity = SystemAlertEntity.Severity.ERROR;
        assertFalse(a.isCritical());
        assertFalse(a.isWarning());

        a.acknowledge("王五", "已确认，开始排查");
        assertTrue(a.isAcknowledged());
        assertEquals("王五", a.acknowledgedBy);
        assertNotNull(a.acknowledgedAt);
        assertEquals("已确认，开始排查", a.acknowledgementComment);

        a.resolvedAt = now.minusMinutes(10);
        assertEquals(20, a.getDurationMinutes());
        a.resolvedAt = null;
        assertTrue(a.getDurationMinutes() >= 29);

        a.resolve("赵六", "扩容后恢复");
        assertEquals(SystemAlertEntity.AlertStatus.RESOLVED, a.alertStatus);
        assertEquals("赵六", a.resolvedBy);
        assertEquals("扩容后恢复", a.resolutionComment);

        a.snooze(now.plusHours(1));
        assertEquals(SystemAlertEntity.AlertStatus.SNOOZED, a.alertStatus);
        assertEquals(now.plusHours(1), a.snoozedUntil);
        a.unsnooze();
        assertEquals(SystemAlertEntity.AlertStatus.OPEN, a.alertStatus);
        assertNull(a.snoozedUntil);
        a.unsnooze();
        assertEquals(SystemAlertEntity.AlertStatus.OPEN, a.alertStatus);

        SystemAlertEntity fresh = new SystemAlertEntity();
        fresh.onCreate();
        assertNotNull(fresh.createdAt);
        assertNotNull(fresh.firstOccurredAt);
        assertNotNull(fresh.lastOccurredAt);
        LocalDateTime pre = now.minusHours(2);
        fresh.createdAt = pre;
        fresh.firstOccurredAt = pre;
        fresh.lastOccurredAt = pre;
        fresh.onCreate();
        assertEquals(pre, fresh.createdAt);
        assertEquals(pre, fresh.firstOccurredAt);
        assertEquals(pre, fresh.lastOccurredAt);
        fresh.onUpdate();
        assertNotNull(fresh.updatedAt);
    }

    @Test
    @DisplayName("DataQualityRuleEntity：启用判定/失败动作/违规率与评估记录")
    void dataQualityRuleEntity() {
        DataQualityRuleEntity r = new DataQualityRuleEntity();
        r.ruleName = "订单金额非空校验";
        r.createdBy = "数据-李四";

        assertTrue(r.isActive());
        assertFalse(r.isInactive());
        assertFalse(r.isCritical());
        assertTrue(r.isWarning());
        assertFalse(r.shouldStopOnFailure());
        assertTrue(r.shouldLogOnFailure());
        assertFalse(r.shouldSkipOnFailure());

        r.ruleStatus = DataQualityRuleEntity.RuleStatus.INACTIVE;
        assertFalse(r.isActive());
        assertTrue(r.isInactive());
        r.ruleStatus = DataQualityRuleEntity.RuleStatus.ACTIVE;
        r.enabled = false;
        assertFalse(r.isActive());
        r.enabled = true;

        r.severity = DataQualityRuleEntity.Severity.CRITICAL;
        assertTrue(r.isCritical());
        r.severity = DataQualityRuleEntity.Severity.ERROR;
        assertTrue(r.isCritical());
        r.severity = DataQualityRuleEntity.Severity.INFO;
        assertTrue(r.isWarning());

        r.actionOnFailure = "stop";
        assertTrue(r.shouldStopOnFailure());
        assertFalse(r.shouldLogOnFailure());
        r.actionOnFailure = "warn";
        assertTrue(r.shouldLogOnFailure());
        r.actionOnFailure = "log";
        assertTrue(r.shouldLogOnFailure());
        r.actionOnFailure = "skip";
        assertTrue(r.shouldSkipOnFailure());
        assertFalse(r.shouldLogOnFailure());
        r.actionOnFailure = null;
        assertTrue(r.shouldLogOnFailure());

        r.totalEvaluations = null;
        assertEquals(0.0, r.getViolationRate(), 0.001);
        r.totalEvaluations = 0;
        assertEquals(0.0, r.getViolationRate(), 0.001);
        r.totalEvaluations = 10;
        r.totalViolations = 3;
        assertEquals(30.0, r.getViolationRate(), 0.001);

        r.recordEvaluation(true, 0);
        assertNotNull(r.lastEvaluatedAt);
        assertEquals(0, r.lastViolationCount);
        assertEquals(11, r.totalEvaluations);
        assertEquals(3, r.totalViolations);
        r.recordEvaluation(false, 2);
        assertEquals(12, r.totalEvaluations);
        assertEquals(5, r.totalViolations);
        assertEquals(2, r.lastViolationCount);

        DataQualityRuleEntity fresh = new DataQualityRuleEntity();
        fresh.ruleName = "新规则";
        fresh.createdBy = "数据-李四";
        fresh.totalEvaluations = null;
        fresh.totalViolations = null;
        fresh.recordEvaluation(false, 4);
        assertEquals(1, fresh.totalEvaluations);
        assertEquals(4, fresh.totalViolations);

        fresh.ruleStatus = null;
        assertNull(fresh.createdAt);
        fresh.onCreate();
        assertNotNull(fresh.createdAt);
        assertEquals(DataQualityRuleEntity.RuleStatus.ACTIVE, fresh.ruleStatus);
        fresh.onUpdate();
        assertNotNull(fresh.updatedAt);
    }

    @Test
    @DisplayName("AuditLogEntity：认证/数据操作判定与全量操作描述映射")
    void auditLogEntity() {
        AuditLogEntity l = new AuditLogEntity();
        l.username = "王小明";
        l.resourceType = "game";

        l.action = AuditLogEntity.AuditAction.LOGIN;
        assertTrue(l.isAuthAction());
        assertFalse(l.isDataAction());
        l.action = AuditLogEntity.AuditAction.LOGIN_FAILED;
        assertTrue(l.isAuthAction());
        l.action = AuditLogEntity.AuditAction.EXPORT;
        assertTrue(l.isDataAction());
        assertFalse(l.isAuthAction());
        l.action = AuditLogEntity.AuditAction.READ;
        assertFalse(l.isDataAction());

        assertTrue(l.isSuccess());
        assertFalse(l.isFailure());
        l.status = AuditLogEntity.AuditStatus.FAILURE;
        assertTrue(l.isFailure());
        assertFalse(l.isSuccess());

        for (AuditLogEntity.AuditAction action : AuditLogEntity.AuditAction.values()) {
            l.action = action;
            assertNotNull(l.getActionDescription());
        }
        l.action = AuditLogEntity.AuditAction.CREATE;
        assertEquals("创建", l.getActionDescription());
        l.action = AuditLogEntity.AuditAction.LOGIN_FAILED;
        assertEquals("登录失败", l.getActionDescription());
        l.action = AuditLogEntity.AuditAction.GRANT_PERMISSION;
        assertEquals("授予权限", l.getActionDescription());
        l.action = AuditLogEntity.AuditAction.SYSTEM_MAINTENANCE;
        assertEquals("系统维护", l.getActionDescription());
    }

    @Test
    @DisplayName("IdentityEntity：主标识切换的显示ID回退/活跃判定/活动记录")
    void identityEntity() {
        IdentityEntity i = new IdentityEntity();
        i.gameId = "g1";
        i.primaryId = "dev-原始标识";

        assertTrue(i.isActive());
        assertFalse(i.isMerged());
        assertFalse(i.hasUserId());
        assertFalse(i.hasPlayerId());
        assertFalse(i.isBoundToUser());
        assertEquals("dev-原始标识", i.getDisplayId());

        i.status = IdentityEntity.IdentityStatus.MERGED;
        assertTrue(i.isMerged());
        assertFalse(i.isActive());
        i.status = IdentityEntity.IdentityStatus.ACTIVE;
        i.deletedAt = LocalDateTime.now();
        assertFalse(i.isActive());
        i.deletedAt = null;

        i.userId = "";
        assertFalse(i.hasUserId());
        i.userId = "u-100";
        assertTrue(i.hasUserId());
        assertTrue(i.isBoundToUser());
        i.playerId = "p-200";
        assertTrue(i.hasPlayerId());

        i.primaryIdentityType = IdentityEntity.IdentityType.USER;
        assertEquals("u-100", i.getDisplayId());
        i.userId = null;
        assertEquals("dev-原始标识", i.getDisplayId());
        i.primaryIdentityType = IdentityEntity.IdentityType.PLAYER;
        assertEquals("p-200", i.getDisplayId());
        i.playerId = null;
        assertEquals("dev-原始标识", i.getDisplayId());
        i.primaryIdentityType = IdentityEntity.IdentityType.CHARACTER;
        i.characterId = "c-300";
        assertEquals("c-300", i.getDisplayId());
        i.characterId = null;
        assertEquals("dev-原始标识", i.getDisplayId());
        i.primaryIdentityType = IdentityEntity.IdentityType.DEVICE;
        i.deviceId = "dev-400";
        assertEquals("dev-400", i.getDisplayId());
        i.deviceId = null;
        assertEquals("dev-原始标识", i.getDisplayId());

        assertEquals(0, i.getDaysSinceFirstSeen());
        assertEquals(0, i.getDaysSinceLastSeen());
        i.firstSeenAt = LocalDateTime.now().minusDays(5);
        assertTrue(i.getDaysSinceFirstSeen() >= 4);
        i.lastSeenAt = LocalDateTime.now().minusDays(3);
        assertTrue(i.getDaysSinceLastSeen() >= 2);
        assertTrue(i.isInactive(2));
        i.lastSeenAt = LocalDateTime.now();
        assertFalse(i.isInactive(2));

        i.eventCount = null;
        i.recordActivity();
        assertEquals(1L, i.eventCount);
        assertNotNull(i.lastSeenAt);
        i.recordActivity();
        assertEquals(2L, i.eventCount);
        i.sessionCount = null;
        i.incrementSessionCount();
        assertEquals(1, i.sessionCount);
        assertNotNull(i.lastSeenAt);
    }

    @Test
    @DisplayName("SocialAnalyticsEntity：社交事件指标填充与 onCreate 时间戳")
    void socialAnalyticsEntity() {
        SocialAnalyticsEntity s = new SocialAnalyticsEntity();
        s.gameId = "g1";
        s.environment = "prod";
        s.analysisDate = LocalDate.now().minusDays(1);
        s.socialEventType = SocialAnalyticsEntity.SocialEventType.GIFT_SEND;
        s.totalFriendships = 1200L;
        s.newFriendships = 80L;
        s.avgFriendsPerUser = 12.5;
        s.totalGuilds = 45L;
        s.newGuilds = 3L;
        s.avgGuildSize = 26.7;
        s.guildMembers = 1200L;
        s.chatMessages = 88000L;
        s.giftsSent = 3200L;
        s.invitesSent = 560L;
        s.invitesAccepted = 210L;
        s.viralCoefficient = 0.37;
        s.socialUsersRetentionD1 = 0.52;
        s.socialUsersRetentionD7 = 0.28;
        s.nonSocialUsersRetentionD1 = 0.31;
        s.nonSocialUsersRetentionD7 = 0.12;
        s.platform = "ios";

        assertEquals(SocialAnalyticsEntity.SocialEventType.GIFT_SEND, s.socialEventType);
        assertEquals(1200L, s.totalFriendships);
        assertEquals(0.37, s.viralCoefficient, 0.0001);
        assertEquals("ios", s.platform);

        assertNull(s.createdAt);
        s.onCreate();
        assertNotNull(s.createdAt);
        LocalDateTime preset = LocalDateTime.now().minusHours(3);
        s.createdAt = preset;
        s.onCreate();
        assertEquals(preset, s.createdAt);
    }

    @Test
    @DisplayName("ReportExecutionEntity：执行状态迁移/结果判定与状态描述")
    void reportExecutionEntity() {
        ReportExecutionEntity e = new ReportExecutionEntity();
        e.reportId = "rpt-1";
        e.gameId = "g1";
        e.triggeredBy = "分析-王五";

        assertTrue(e.isPending());
        assertFalse(e.isRunning());
        assertFalse(e.isCompleted());
        assertFalse(e.isFailed());
        assertFalse(e.hasResults());
        assertFalse(e.hasStoredResults());
        assertEquals(0, e.getExecutionTimeMinutes());

        e.markAsRunning();
        assertTrue(e.isRunning());
        assertNotNull(e.startTime);

        e.markAsCompleted(100L, "总计 100 行", 120000L);
        assertTrue(e.isCompleted());
        assertEquals(100L, e.rowCount);
        assertEquals("总计 100 行", e.resultSummary);
        assertEquals(120000L, e.executionTimeMs);
        assertNotNull(e.completedAt);
        assertEquals(2, e.getExecutionTimeMinutes());
        assertTrue(e.hasResults());
        assertTrue(e.getStatusDescription().contains("COMPLETED"));
        assertTrue(e.getStatusDescription().contains("100"));
        e.rowCount = 0L;
        assertFalse(e.hasResults());
        e.rowCount = null;
        assertFalse(e.hasResults());

        e.markAsFailed("查询超时");
        assertTrue(e.isFailed());
        assertEquals("查询超时", e.errorMessage);
        assertFalse(e.hasResults());
        e.executionStatus = ReportExecutionEntity.ExecutionStatus.TIMEOUT;
        assertTrue(e.isFailed());

        e.markAsCancelled("用户主动取消");
        assertEquals(ReportExecutionEntity.ExecutionStatus.CANCELLED, e.executionStatus);
        assertEquals("用户主动取消", e.statusMessage);

        e.resultStoragePath = "";
        assertFalse(e.hasStoredResults());
        e.resultStoragePath = "/storage/report/rpt-1.json";
        assertTrue(e.hasStoredResults());

        e.rowCount = null;
        e.executionTimeMs = null;
        assertTrue(e.getStatusDescription().contains("0 rows (0ms)"));
    }

    @Test
    @DisplayName("BlockListEntity：封禁生效/过期/剩余时长/命中记录与描述拼接")
    void blockListEntity() {
        LocalDateTime now = LocalDateTime.now();
        BlockListEntity b = new BlockListEntity();
        b.gameId = "g1";
        b.targetType = "device_id";
        b.targetValue = "dev-9f00";
        b.blockedBy = "风控-赵七";

        assertTrue(b.isHardBlock());
        assertFalse(b.isSoftBlock());
        assertFalse(b.isShadowBlock());
        assertTrue(b.isActive());
        assertFalse(b.isExpired());
        assertEquals(0, b.getRemainingMinutes());

        b.blockType = BlockListEntity.BlockType.SOFT;
        assertTrue(b.isSoftBlock());
        b.blockType = BlockListEntity.BlockType.SHADOW;
        assertTrue(b.isShadowBlock());
        b.blockType = BlockListEntity.BlockType.HARD;

        b.deletedAt = now;
        assertFalse(b.isActive());
        b.deletedAt = null;
        b.unblockedAt = now;
        assertFalse(b.isActive());
        b.unblockedAt = null;

        b.isPermanent = true;
        assertTrue(b.isActive());
        assertFalse(b.isExpired());
        assertEquals(-1, b.getRemainingMinutes());
        b.isPermanent = false;

        b.expiresAt = now.plusMinutes(10);
        assertTrue(b.isActive());
        assertFalse(b.isExpired());
        assertTrue(b.getRemainingMinutes() >= 9);
        b.expiresAt = now.minusMinutes(1);
        assertFalse(b.isActive());
        assertTrue(b.isExpired());
        assertEquals(0, b.getRemainingMinutes());
        b.expiresAt = null;
        assertFalse(b.isExpired());

        b.hitCount = null;
        b.recordHit();
        assertEquals(1L, b.hitCount);
        assertNotNull(b.lastHitAt);
        b.recordHit();
        assertEquals(2L, b.hitCount);

        b.unblock("管理员", "误封申诉通过");
        assertEquals("管理员", b.unblockedBy);
        assertNotNull(b.unblockedAt);
        assertEquals("误封申诉通过", b.unblockReason);
        assertFalse(b.isActive());

        BlockListEntity d = new BlockListEntity();
        d.targetType = "ip";
        d.targetValue = "1.2.3.4";
        assertEquals("hard ip: 1.2.3.4", d.getBlockDescription());
        d.blockReason = "恶意刷量";
        assertTrue(d.getBlockDescription().contains("恶意刷量"));
        d.isPermanent = true;
        assertTrue(d.getBlockDescription().contains("(permanent)"));
        d.isPermanent = false;
        d.expiresAt = now.plusDays(1);
        assertTrue(d.getBlockDescription().contains("expires: "));
    }

    @Test
    @DisplayName("AdAnalysisEntity：广告指标填充与 onCreate")
    void adAnalysisEntity() {
        AdAnalysisEntity a = new AdAnalysisEntity();
        a.gameId = "g1";
        a.environment = "prod";
        a.analysisDate = LocalDate.now().minusDays(1);
        a.adNetwork = AdAnalysisEntity.AdNetwork.APPLOVIN;
        a.adFormat = AdAnalysisEntity.AdFormat.REWARDED;
        a.adPlacement = "结算页激励视频";
        a.impressions = 50000L;
        a.clicks = 1200L;
        a.rewards = 900L;
        a.revenue = 880.5;
        a.ecpm = 17.61;
        a.requests = 60000L;
        a.fills = 52000L;
        a.fillRate = 0.86;
        a.ctr = 0.024;
        a.uniqueUsers = 18000L;
        a.platform = "android";
        a.country = "CN";

        assertEquals(AdAnalysisEntity.AdNetwork.APPLOVIN, a.adNetwork);
        assertEquals(50000L, a.impressions);
        assertEquals(17.61, a.ecpm, 0.001);

        assertNull(a.createdAt);
        a.onCreate();
        assertNotNull(a.createdAt);
        LocalDateTime preset = LocalDateTime.now().minusHours(2);
        a.createdAt = preset;
        a.onCreate();
        assertEquals(preset, a.createdAt);
    }

    @Test
    @DisplayName("FlinkJobEntity：作业状态迁移/部署停止失败/描述与风险案例比率")
    void flinkJobEntity() {
        FlinkJobEntity f = new FlinkJobEntity();
        f.gameId = "g1";
        f.name = "risk-eval";
        f.jobType = "risk_evaluation";

        assertFalse(f.isRunning());
        assertFalse(f.isStopped());
        assertTrue(f.canDeploy());
        assertFalse(f.canStop());

        f.status = FlinkJobEntity.JobStatus.RUNNING;
        assertTrue(f.isRunning());
        assertFalse(f.canDeploy());
        assertTrue(f.canStop());
        f.status = FlinkJobEntity.JobStatus.DEPLOYING;
        assertTrue(f.canStop());
        f.status = FlinkJobEntity.JobStatus.STOPPED;
        assertTrue(f.isStopped());
        assertTrue(f.canDeploy());
        f.status = FlinkJobEntity.JobStatus.FAILED;
        assertTrue(f.isStopped());
        assertTrue(f.canDeploy());

        f.markAsDeployed("flink-77", "http://flink-ui:8081");
        assertEquals(FlinkJobEntity.JobStatus.RUNNING, f.status);
        assertEquals("flink-77", f.flinkJobId);
        assertEquals("http://flink-ui:8081", f.flinkUrl);
        assertNotNull(f.deployedAt);
        assertNotNull(f.startedAt);

        f.failureCount = null;
        f.markAsFailed("任务异常退出");
        assertEquals(FlinkJobEntity.JobStatus.FAILED, f.status);
        assertEquals("任务异常退出", f.errorMessage);
        assertEquals(1, f.failureCount);
        assertNotNull(f.lastFailureAt);
        f.markAsFailed("再次失败");
        assertEquals(2, f.failureCount);

        f.markAsStopped();
        assertEquals(FlinkJobEntity.JobStatus.STOPPED, f.status);
        assertNotNull(f.stoppedAt);

        f.updateMetrics(10000L, 55L, 20L);
        assertEquals(10000L, f.totalEventsProcessed);
        assertEquals(55L, f.totalRiskCasesCreated);
        assertEquals(20L, f.totalActionsExecuted);
        assertNotNull(f.lastMetricsUpdate);

        assertEquals(0.0055, f.getRiskCaseRate(), 0.0001);
        f.totalEventsProcessed = 0L;
        assertEquals(0.0, f.getRiskCaseRate(), 0.0001);
        f.totalEventsProcessed = 10000L;
        f.totalRiskCasesCreated = null;
        assertEquals(0.0, f.getRiskCaseRate(), 0.0001);
        f.totalRiskCasesCreated = 50L;
        assertEquals(0.005, f.getRiskCaseRate(), 0.0001);

        f.displayName = "实时风险评估";
        f.environmentId = "prod";
        assertTrue(f.getJobDescription().contains("实时风险评估"));
        assertTrue(f.getJobDescription().contains("prod"));
        f.displayName = null;
        f.environmentId = null;
        assertTrue(f.getJobDescription().contains("risk-eval"));
        assertTrue(f.getJobDescription().contains("global"));
    }

    @Test
    @DisplayName("FunnelAnalysisEntity：漏斗类型判定/计算频率分支/限定名与窗口分钟")
    void funnelAnalysisEntity() {
        FunnelAnalysisEntity f = new FunnelAnalysisEntity();
        f.gameId = "g1";
        f.name = "新手引导漏斗";

        assertTrue(f.isActive());
        assertTrue(f.isAutoCalcEnabled());
        assertTrue(f.isSequential());
        assertTrue(f.isStrictOrder());
        assertFalse(f.allowsBacktracking());

        f.status = FunnelAnalysisEntity.AnalysisStatus.PAUSED;
        assertFalse(f.needsCalculation());
        f.status = FunnelAnalysisEntity.AnalysisStatus.ACTIVE;
        f.enableAutoCalc = null;
        assertFalse(f.isAutoCalcEnabled());
        assertFalse(f.needsCalculation());
        f.enableAutoCalc = true;

        assertTrue(f.needsCalculation());
        f.lastCalculatedAt = LocalDateTime.now().minusHours(1);
        assertTrue(f.needsCalculation());

        f.calcFrequency = "realtime";
        assertTrue(f.needsCalculation());
        f.lastCalculatedAt = LocalDateTime.now().plusMinutes(1);
        assertFalse(f.needsCalculation());
        f.lastCalculatedAt = null;
        assertNotNull(f.calculateNextCalcTime());
        LocalDateTime base = LocalDateTime.of(2026, 1, 1, 8, 0);
        f.lastCalculatedAt = base;
        f.calcFrequency = "hourly";
        assertEquals(base.plusHours(1), f.calculateNextCalcTime());
        f.calcFrequency = "daily";
        assertEquals(base.plusDays(1), f.calculateNextCalcTime());
        f.calcFrequency = "weekly";
        assertEquals(base.plusWeeks(1), f.calculateNextCalcTime());
        f.calcFrequency = "monthly";
        assertEquals(base.plusDays(1), f.calculateNextCalcTime());

        f.funnelType = FunnelAnalysisEntity.FunnelType.ANY_ORDER;
        assertFalse(f.isSequential());
        f.strictOrder = null;
        assertFalse(f.isStrictOrder());
        f.allowBacktracking = true;
        assertTrue(f.allowsBacktracking());

        assertEquals("新手引导漏斗", f.getQualifiedName());
        GameEntity g = new GameEntity();
        g.id = "g9";
        f.game = g;
        assertEquals("g9_新手引导漏斗", f.getQualifiedName());

        f.windowSize = null;
        assertEquals(10080, f.getWindowSizeInMinutes());
        f.windowSize = 2;
        assertEquals(2880, f.getWindowSizeInMinutes());

        f.deletedAt = LocalDateTime.now();
        assertFalse(f.isActive());
    }

    @Test
    @DisplayName("FunnelStepEntity：可选步骤/过滤条件/时间窗口判定")
    void funnelStepEntity() {
        FunnelStepEntity s = new FunnelStepEntity();
        s.stepOrder = 1;
        s.name = "完成新手教程";
        s.eventName = "tutorial_complete";

        assertFalse(s.isOptional());
        assertFalse(s.hasFilter());
        assertFalse(s.hasTimeWindow());
        s.optional = null;
        assertFalse(s.isOptional());
        s.optional = true;
        assertTrue(s.isOptional());

        s.eventFilter = "";
        assertFalse(s.hasFilter());
        s.eventFilter = "{\"level\":\">=3\"}";
        assertTrue(s.hasFilter());

        s.timeWindowSec = 0L;
        assertFalse(s.hasTimeWindow());
        s.timeWindowSec = 300L;
        assertTrue(s.hasTimeWindow());
    }

    @Test
    @DisplayName("HealthMetricEntity：警告/严重阈值分支/健康状态推导与 onCreate")
    void healthMetricEntity() {
        HealthMetricEntity m = new HealthMetricEntity();
        m.metricName = "CPU 使用率";
        m.metricType = HealthMetricEntity.MetricType.CPU_USAGE;
        m.warningThreshold = 60.0;
        m.criticalThreshold = 90.0;
        m.metricValue = 55.0;

        assertFalse(m.isWarning());
        assertFalse(m.isCritical());
        assertTrue(m.isNormal());
        assertEquals(HealthCheckEntity.HealthStatus.HEALTHY, m.getHealthStatus());

        m.metricValue = 60.0;
        assertTrue(m.isWarning());
        assertFalse(m.isCritical());
        assertFalse(m.isNormal());
        assertEquals(HealthCheckEntity.HealthStatus.DEGRADED, m.getHealthStatus());

        m.metricValue = 95.0;
        assertTrue(m.isCritical());
        assertTrue(m.isWarning());  // 95 >= warning(60)
        assertEquals(HealthCheckEntity.HealthStatus.UNHEALTHY, m.getHealthStatus());

        m.metricValue = null;
        assertFalse(m.isWarning());
        assertFalse(m.isCritical());
        m.warningThreshold = null;
        m.criticalThreshold = null;
        m.metricValue = 99.0;
        assertFalse(m.isWarning());
        assertFalse(m.isCritical());
        assertTrue(m.isNormal());

        assertNull(m.createdAt);
        assertNull(m.collectedAt);
        m.onCreate();
        assertNotNull(m.createdAt);
        assertNotNull(m.collectedAt);
        LocalDateTime preset = LocalDateTime.now().minusMinutes(5);
        m.createdAt = preset;
        m.collectedAt = preset;
        m.onCreate();
        assertEquals(preset, m.createdAt);
        assertEquals(preset, m.collectedAt);
    }

    @Test
    @DisplayName("IntegrationEntity：集成状态迁移/重试上限/onCreate 默认填充")
    void integrationEntity() {
        IntegrationEntity i = new IntegrationEntity();
        i.gameId = "g1";
        i.integrationType = IntegrationEntity.IntegrationType.SLACK;
        i.name = "风控通知";

        assertFalse(i.isActive());
        assertFalse(i.isVerifying());
        assertFalse(i.hasFailed());

        i.integrationStatus = IntegrationEntity.IntegrationStatus.ACTIVE;
        assertTrue(i.isActive());
        i.enabled = false;
        assertFalse(i.isActive());
        i.enabled = true;

        i.markAsVerifying();
        assertTrue(i.isVerifying());
        i.markAsFailed("连接超时");
        assertTrue(i.hasFailed());
        assertEquals("连接超时", i.lastError);
        assertEquals(1, i.retryCount);
        i.incrementRetry();
        assertEquals(2, i.retryCount);
        assertTrue(i.shouldRetry());
        assertFalse(i.hasExceededMaxRetries());
        i.incrementRetry();
        assertEquals(3, i.retryCount);
        assertFalse(i.shouldRetry());
        assertTrue(i.hasExceededMaxRetries());

        i.markAsActive();
        assertTrue(i.isActive());
        assertNotNull(i.lastVerifiedAt);
        assertNull(i.lastError);
        assertEquals(0, i.retryCount);

        i.markAsDisabled();
        assertEquals(IntegrationEntity.IntegrationStatus.DISABLED, i.integrationStatus);

        IntegrationEntity fresh = new IntegrationEntity();
        fresh.onCreate();
        assertNotNull(fresh.createdAt);
        assertEquals(IntegrationEntity.IntegrationStatus.INACTIVE, fresh.integrationStatus);
        fresh.integrationStatus = null;
        LocalDateTime preset = LocalDateTime.now().minusHours(1);
        fresh.createdAt = preset;
        fresh.onCreate();
        assertEquals(preset, fresh.createdAt);
        assertEquals(IntegrationEntity.IntegrationStatus.INACTIVE, fresh.integrationStatus);
        fresh.onUpdate();
        assertNotNull(fresh.updatedAt);
    }

    @Test
    @DisplayName("IntegrationLogEntity：调用状态迁移/重试判定/onCreate 过期时间")
    void integrationLogEntity() {
        IntegrationLogEntity l = new IntegrationLogEntity();
        l.integrationId = "int-1";
        l.gameId = "g1";
        l.integrationType = "SLACK";
        l.eventType = "risk_case";

        assertFalse(l.isSuccess());
        assertFalse(l.isFailed());
        assertFalse(l.isRetrying());
        assertTrue(l.shouldRetry());

        l.markAsSuccess();
        assertTrue(l.isSuccess());
        assertFalse(l.isFailed());

        l.markAsFailed("远端返回 500");
        assertTrue(l.isFailed());
        assertEquals("远端返回 500", l.errorMessage);

        l.markAsTimeout();
        assertTrue(l.isFailed());
        assertEquals("Request timeout", l.errorMessage);

        l.markAsRetrying();
        assertTrue(l.isRetrying());
        assertEquals(1, l.retryAttempt);
        assertTrue(l.shouldRetry());
        l.retryAttempt = 3;
        assertFalse(l.shouldRetry());

        assertNull(l.createdAt);
        assertNull(l.expiresAt);
        l.onCreate();
        assertNotNull(l.createdAt);
        assertNotNull(l.expiresAt);
        assertTrue(l.expiresAt.isAfter(LocalDateTime.now().plusDays(89)));
        LocalDateTime preset = LocalDateTime.now().minusHours(1);
        l.createdAt = preset;
        l.expiresAt = preset;
        l.onCreate();
        assertEquals(preset, l.createdAt);
        assertEquals(preset, l.expiresAt);
    }

    @Test
    @DisplayName("LevelProgressionEntity：活跃判定与追踪开关空值防御")
    void levelProgressionEntity() {
        LevelProgressionEntity l = new LevelProgressionEntity();
        l.gameId = "g1";
        l.name = "主线关卡进度";

        assertEquals("level_start", l.levelStartEvent);
        assertEquals("level_complete", l.levelCompleteEvent);
        assertEquals("level_fail", l.levelFailEvent);
        assertTrue(l.isActive());
        assertTrue(l.isAutoCalcEnabled());
        assertTrue(l.tracksCompletionTime());
        assertTrue(l.hasAnomalyDetection());

        l.status = LevelProgressionEntity.ProgressionStatus.PAUSED;
        assertFalse(l.isActive());
        l.status = LevelProgressionEntity.ProgressionStatus.ACTIVE;
        l.deletedAt = LocalDateTime.now();
        assertFalse(l.isActive());
        l.deletedAt = null;

        l.enableAutoCalc = null;
        assertFalse(l.isAutoCalcEnabled());
        l.trackCompletionTime = null;
        assertFalse(l.tracksCompletionTime());
        l.enableAnomalyDetection = null;
        assertFalse(l.hasAnomalyDetection());
        l.trackCompletionTime = true;
        l.enableAnomalyDetection = true;
        assertTrue(l.tracksCompletionTime());
        assertTrue(l.hasAnomalyDetection());
    }

    @Test
    @DisplayName("MLModelPredictionEntity：预测状态迁移/反馈记录/延迟计算与 onCreate")
    void mlModelPredictionEntity() {
        MLModelPredictionEntity p = new MLModelPredictionEntity();
        p.modelId = "m-1";
        p.gameId = "g1";

        assertTrue(p.isPending());
        assertFalse(p.isCompleted());
        assertFalse(p.isFailed());
        assertFalse(p.hasFeedback());
        assertFalse(p.isCorrect());
        assertFalse(p.isAbTest());
        assertFalse(p.isCanary());
        assertFalse(p.isCacheHit());

        p.predictionStatus = MLModelPredictionEntity.PredictionStatus.PROCESSING;
        assertTrue(p.isPending());

        p.complete("{\"churn\":true}", 0.87);
        assertTrue(p.isCompleted());
        assertEquals("{\"churn\":true}", p.outputPrediction);
        assertEquals(0.87, p.predictionScore, 0.0001);
        assertNotNull(p.completedAt);

        p.markCached("{\"churn\":false}");
        assertEquals(MLModelPredictionEntity.PredictionStatus.CACHED, p.predictionStatus);
        assertTrue(p.isCompleted());
        assertTrue(p.isCacheHit());

        p.fail("模型服务不可用");
        assertTrue(p.isFailed());
        assertEquals("模型服务不可用", p.errorMessage);
        p.timeout();
        assertEquals(MLModelPredictionEntity.PredictionStatus.TIMEOUT, p.predictionStatus);
        assertEquals("Prediction request timed out", p.errorMessage);
        assertTrue(p.isFailed());

        p.isAbTest = true;
        assertTrue(p.isAbTest());
        p.isCanary = true;
        assertTrue(p.isCanary());

        p.addFeedback(MLModelPredictionEntity.FeedbackType.CORRECT, "确实流失", "运营-周八");
        assertTrue(p.hasFeedback());
        assertTrue(p.isCorrect());
        assertEquals("确实流失", p.actualValue);
        assertEquals("运营-周八", p.feedbackBy);
        assertNotNull(p.feedbackAt);
        p.feedbackType = MLModelPredictionEntity.FeedbackType.INCORRECT;
        assertFalse(p.isCorrect());

        p.latencyMs = 150;
        assertEquals(150L, p.getLatencyMs());
        p.latencyMs = null;
        assertEquals(0L, p.getLatencyMs());
        LocalDateTime base = LocalDateTime.now();
        p.createdAt = base.minusSeconds(3);
        p.completedAt = base;
        assertEquals(3000L, p.getLatencyMs());

        MLModelPredictionEntity fresh = new MLModelPredictionEntity();
        fresh.onCreate();
        assertNotNull(fresh.createdAt);
        assertEquals(MLModelPredictionEntity.PredictionStatus.PENDING, fresh.predictionStatus);
        fresh.predictionStatus = null;
        LocalDateTime preset = LocalDateTime.now().minusMinutes(1);
        fresh.createdAt = preset;
        fresh.onCreate();
        assertEquals(preset, fresh.createdAt);
        assertEquals(MLModelPredictionEntity.PredictionStatus.PENDING, fresh.predictionStatus);
    }

    @Test
    @DisplayName("MailEntity：可领取判定/全服与个人可见性/收件人静态匹配")
    void mailEntity() {
        LocalDateTime now = LocalDateTime.now();
        MailEntity m = new MailEntity();
        m.gameId = "g1";
        m.title = "周年庆补偿";
        m.content = "感谢陪伴，送上钻石礼包";

        assertFalse(m.claimable(now));
        m.status = MailEntity.Status.SENT;
        assertTrue(m.claimable(now));
        m.scope = MailEntity.Scope.INDIVIDUAL;
        assertFalse(m.visibleTo("p1", now));

        m.scope = MailEntity.Scope.ALL;
        assertTrue(m.visibleTo("任意玩家", now));

        m.scope = MailEntity.Scope.INDIVIDUAL;
        m.recipients = "p1, p2";
        assertTrue(m.visibleTo("p1", now));
        assertFalse(m.visibleTo("p3", now));
        m.recipients = null;
        assertFalse(m.visibleTo("p1", now));

        m.recipients = "p1, p2";
        m.expireAt = now.minusMinutes(1);
        assertFalse(m.claimable(now));
        m.expireAt = now.plusHours(1);
        assertTrue(m.claimable(now));

        m.deletedAt = now;
        assertFalse(m.claimable(now));
        m.deletedAt = null;

        assertTrue(MailEntity.containsRecipient("a , b , c", "b"));
        assertFalse(MailEntity.containsRecipient("a,b,c", "d"));
    }

    @Test
    @DisplayName("ModelTrainingEntity：训练生命周期/最佳损失更新/进度与时长计算")
    void modelTrainingEntity() {
        LocalDateTime now = LocalDateTime.now();
        ModelTrainingEntity t = new ModelTrainingEntity();
        t.modelId = "m-1";
        t.trainingJobName = "流失预测周训练";

        assertTrue(t.isPending());
        assertFalse(t.isRunning());
        assertFalse(t.isCompleted());
        assertFalse(t.isFailed());
        assertFalse(t.isCancelled());

        t.trainingStatus = ModelTrainingEntity.TrainingStatus.TIMEOUT;
        assertTrue(t.isFailed());

        t.start();
        assertTrue(t.isRunning());
        assertNotNull(t.startedAt);

        t.updateProgress(1, 10, 0.5);
        assertEquals(1, t.currentEpoch);
        assertEquals(10, t.trainingEpochs);
        assertEquals(10, t.progressPercent);
        assertEquals(0.5, t.bestLoss, 0.0001);
        assertEquals(1, t.bestEpoch);
        t.updateProgress(2, 10, 0.9);
        assertEquals(0.5, t.bestLoss, 0.0001);
        assertEquals(1, t.bestEpoch);
        t.updateProgress(3, 10, 0.3);
        assertEquals(0.3, t.bestLoss, 0.0001);
        assertEquals(3, t.bestEpoch);
        assertEquals(30.0, t.getProgress(), 0.001);

        t.trainingEpochs = null;
        t.progressPercent = 40;
        assertEquals(40.0, t.getProgress(), 0.001);
        t.progressPercent = null;
        assertEquals(0.0, t.getProgress(), 0.001);

        assertEquals(0, new ModelTrainingEntity().getDurationMinutes());
        t.startedAt = now.minusMinutes(30);
        t.completedAt = now.minusMinutes(10);
        assertEquals(20, t.getDurationMinutes());
        t.completedAt = null;
        assertTrue(t.getDurationMinutes() >= 29);

        t.complete();
        assertTrue(t.isCompleted());
        assertNotNull(t.completedAt);
        assertEquals(100, t.progressPercent);
        assertNotNull(t.durationMs);

        ModelTrainingEntity f = new ModelTrainingEntity();
        f.fail("loss 不收敛");
        assertTrue(f.isFailed());
        assertEquals("loss 不收敛", f.errorMessage);
        assertNull(f.durationMs);
        f.cancel();
        assertTrue(f.isCancelled());

        ModelTrainingEntity fresh = new ModelTrainingEntity();
        fresh.onCreate();
        assertNotNull(fresh.createdAt);
        assertEquals(ModelTrainingEntity.TrainingStatus.PENDING, fresh.trainingStatus);
        fresh.trainingStatus = null;
        LocalDateTime preset = LocalDateTime.now().minusHours(2);
        fresh.createdAt = preset;
        fresh.onCreate();
        assertEquals(preset, fresh.createdAt);
        assertEquals(ModelTrainingEntity.TrainingStatus.PENDING, fresh.trainingStatus);
        fresh.onUpdate();
        assertNotNull(fresh.updatedAt);
    }

    @Test
    @DisplayName("PerformanceMetricEntity：崩溃指标填充与 onCreate")
    void performanceMetricEntity() {
        PerformanceMetricEntity p = new PerformanceMetricEntity();
        p.gameId = "g1";
        p.environment = "prod";
        p.metricType = PerformanceMetricEntity.MetricType.CRASH;
        p.severity = PerformanceMetricEntity.Severity.CRITICAL;
        p.userId = "u-1";
        p.deviceId = "dev-1";
        p.sessionId = "sess-1";
        p.platform = "android";
        p.deviceModel = "Pixel 8";
        p.osVersion = "14";
        p.appVersion = "2.3.1";
        p.metricValue = 1.0;
        p.metricUnit = "count";
        p.crashType = "OOM";
        p.crashMessage = "OutOfMemoryError: 堆内存溢出";
        p.crashHash = "hash-9";
        p.contextData = "{\"level\":88}";

        assertEquals(PerformanceMetricEntity.MetricType.CRASH, p.metricType);
        assertEquals("OOM", p.crashType);
        assertEquals("hash-9", p.crashHash);

        assertNull(p.createdAt);
        p.onCreate();
        assertNotNull(p.createdAt);
        LocalDateTime preset = LocalDateTime.now().minusMinutes(3);
        p.createdAt = preset;
        p.onCreate();
        assertEquals(preset, p.createdAt);
    }

    @Test
    @DisplayName("PipelineEntity：管道状态迁移/成功率/重试需求/运行记录与 onCreate")
    void pipelineEntity() {
        PipelineEntity p = new PipelineEntity();
        p.gameId = "g1";
        p.pipelineName = "事件入仓";
        p.createdBy = "数据-钱九";

        assertTrue(p.isBatch());
        assertFalse(p.isStreaming());
        assertFalse(p.isActive());
        assertFalse(p.isPaused());
        assertFalse(p.isStopped());
        assertFalse(p.isFailed());

        p.activate();
        assertTrue(p.isActive());
        p.enabled = false;
        assertFalse(p.isActive());
        p.enabled = true;
        p.pause();
        assertTrue(p.isPaused());
        p.stop();
        assertTrue(p.isStopped());
        p.pipelineStatus = PipelineEntity.PipelineStatus.FAILED;
        assertTrue(p.isFailed());
        p.pipelineType = PipelineEntity.PipelineType.STREAMING;
        assertTrue(p.isStreaming());
        assertFalse(p.isBatch());

        assertEquals(0.0, p.getSuccessRate(), 0.001);
        p.runCount = 0;
        assertEquals(0.0, p.getSuccessRate(), 0.001);
        p.runCount = 20;
        p.successCount = 15;
        assertEquals(75.0, p.getSuccessRate(), 0.001);

        assertFalse(p.needsRetry());
        p.failureCount = 1;
        p.maxRetries = null;
        assertTrue(p.needsRetry());
        p.maxRetries = 3;
        assertTrue(p.needsRetry());
        p.failureCount = 3;
        assertFalse(p.needsRetry());

        PipelineEntity r = new PipelineEntity();
        r.runCount = 0;
        r.successCount = 0;
        r.failureCount = 0;
        r.recordRun(true, null);
        assertEquals(1, r.runCount);
        assertEquals(1, r.successCount);
        assertEquals(0, r.failureCount);
        assertNotNull(r.lastRunAt);
        assertNotNull(r.lastSuccessAt);
        assertNull(r.lastError);
        r.recordRun(false, "源表缺失");
        assertEquals(1, r.failureCount);
        assertNotNull(r.lastFailureAt);
        assertEquals("源表缺失", r.lastError);

        PipelineEntity fresh = new PipelineEntity();
        fresh.onCreate();
        assertNotNull(fresh.createdAt);
        assertEquals(PipelineEntity.PipelineStatus.DRAFT, fresh.pipelineStatus);
        fresh.pipelineStatus = null;
        LocalDateTime preset = LocalDateTime.now().minusHours(1);
        fresh.createdAt = preset;
        fresh.onCreate();
        assertEquals(preset, fresh.createdAt);
        assertEquals(PipelineEntity.PipelineStatus.DRAFT, fresh.pipelineStatus);
        fresh.onUpdate();
        assertNotNull(fresh.updatedAt);
    }

    @Test
    @DisplayName("PlayerExportJobEntity：导出任务字段与默认状态")
    void playerExportJobEntity() {
        PlayerExportJobEntity e = new PlayerExportJobEntity();
        e.id = "pex-1";
        e.gameId = "g1";
        e.environmentId = "prod";
        e.playerId = "p-100";
        e.sections = "[\"profile\",\"payments\"]";
        e.requestedBy = "合规-孙十";
        e.expiresAt = LocalDateTime.now().plusDays(7);

        assertEquals("json", e.exportFormat);
        assertEquals(PlayerExportJobEntity.Status.PENDING, e.status);
        assertNull(e.fileName);
        e.status = PlayerExportJobEntity.Status.COMPLETED;
        e.fileName = "p-100-export.json";
        e.filePath = "/exports/pex-1.json";
        e.fileSizeBytes = 20480L;
        e.rowCount = 356L;
        assertEquals(PlayerExportJobEntity.Status.COMPLETED, e.status);
        assertEquals(20480L, e.fileSizeBytes);
        assertEquals(356L, e.rowCount);
        e.status = PlayerExportJobEntity.Status.FAILED;
        e.errorMessage = "玩家数据不存在";
        assertEquals("玩家数据不存在", e.errorMessage);
    }

    @Test
    @DisplayName("RateLimitEntity：作用域判定/六种窗口时长换算与生命周期回调")
    void rateLimitEntity() {
        RateLimitEntity r = new RateLimitEntity();
        r.createdBy = "平台-管理员";

        assertTrue(r.isEnabled());
        assertTrue(r.isGlobal());
        assertFalse(r.isGameLevel());
        assertFalse(r.isApiKeyLevel());
        assertFalse(r.isEndpointLevel());
        assertFalse(r.isUserLevel());

        r.scope = RateLimitEntity.Scope.GAME;
        assertTrue(r.isGameLevel());
        r.scope = RateLimitEntity.Scope.API_KEY;
        assertTrue(r.isApiKeyLevel());
        r.scope = RateLimitEntity.Scope.ENDPOINT;
        assertTrue(r.isEndpointLevel());
        r.scope = RateLimitEntity.Scope.USER;
        assertTrue(r.isUserLevel());

        r.enabled = null;
        assertFalse(r.isEnabled());
        r.enabled = true;
        r.deletedAt = LocalDateTime.now();
        assertFalse(r.isEnabled());
        r.deletedAt = null;

        r.windowSize = 2;
        r.windowType = RateLimitEntity.WindowType.SECOND;
        assertEquals(2000L, r.getWindowDurationMs());
        r.windowType = RateLimitEntity.WindowType.MINUTE;
        assertEquals(120000L, r.getWindowDurationMs());
        r.windowType = RateLimitEntity.WindowType.HOUR;
        assertEquals(7200000L, r.getWindowDurationMs());
        r.windowType = RateLimitEntity.WindowType.DAY;
        assertEquals(172800000L, r.getWindowDurationMs());
        r.windowType = RateLimitEntity.WindowType.WEEK;
        assertEquals(1209600000L, r.getWindowDurationMs());
        r.windowType = RateLimitEntity.WindowType.MONTH;
        assertEquals(5184000000L, r.getWindowDurationMs());

        assertNull(r.createdAt);
        r.onCreate();
        assertNotNull(r.createdAt);
        LocalDateTime preset = LocalDateTime.now().minusHours(1);
        r.createdAt = preset;
        r.onCreate();
        assertEquals(preset, r.createdAt);
        r.onUpdate();
        assertNotNull(r.updatedAt);
    }

    @Test
    @DisplayName("RateLimitRuleEntity：条件匹配/触发计数/生效阈值回退")
    void rateLimitRuleEntity() {
        RateLimitRuleEntity r = new RateLimitRuleEntity();
        r.rateLimitPolicyId = "p-1";
        r.ruleName = "支付事件限流";

        assertTrue(r.isActive());
        assertTrue(r.matchesCondition("payment", "purchase"));

        r.status = RateLimitRuleEntity.RuleStatus.DISABLED;
        assertFalse(r.isActive());
        assertFalse(r.matchesCondition("payment", "purchase"));
        r.status = RateLimitRuleEntity.RuleStatus.ACTIVE;
        r.deletedAt = LocalDateTime.now();
        assertFalse(r.isActive());
        r.deletedAt = null;

        r.eventType = "payment";
        assertFalse(r.matchesCondition("ad", "purchase"));
        assertTrue(r.matchesCondition("payment", "任意"));
        r.eventName = "purchase";
        assertFalse(r.matchesCondition("payment", "refund"));
        assertTrue(r.matchesCondition("payment", "purchase"));
        r.eventType = null;
        r.eventName = null;
        assertTrue(r.matchesCondition("任意类型", "任意名称"));

        r.triggeredCount = null;
        r.recordTriggered();
        assertEquals(1L, r.triggeredCount);
        assertNotNull(r.lastTriggeredAt);
        r.recordTriggered();
        assertEquals(2L, r.triggeredCount);

        assertEquals(100, r.getEffectiveRequestsPerSecond(100));
        r.overrideRequestsPerSecond = 50;
        assertEquals(50, r.getEffectiveRequestsPerSecond(100));
        assertEquals(200, r.getEffectiveEventsPerSecond(200));
        r.overrideEventsPerSecond = 80;
        assertEquals(80, r.getEffectiveEventsPerSecond(200));
        assertEquals(1000000L, r.getEffectiveEventsPerDay(1000000L));
        r.overrideEventsPerDay = 500000L;
        assertEquals(500000L, r.getEffectiveEventsPerDay(1000000L));
    }

    @Test
    @DisplayName("RedeemCodeBatchEntity：批次可兑换判定（状态/删除/过期）")
    void redeemCodeBatchEntity() {
        LocalDateTime now = LocalDateTime.now();
        RedeemCodeBatchEntity b = new RedeemCodeBatchEntity();
        b.gameId = "g1";
        b.name = "春节礼包码";
        b.reward = "[{\"type\":\"item\",\"id\":\"gem\",\"count\":100}]";
        b.codeType = RedeemCodeBatchEntity.CodeType.SHARED;
        b.total = 1000;
        b.perUserLimit = 1;
        b.createdBy = "运营-郑一";

        assertTrue(b.redeemable(now));
        b.status = RedeemCodeBatchEntity.Status.DISABLED;
        assertFalse(b.redeemable(now));
        b.status = RedeemCodeBatchEntity.Status.ACTIVE;
        b.deletedAt = now;
        assertFalse(b.redeemable(now));
        b.deletedAt = null;
        b.expiresAt = now.minusMinutes(1);
        assertFalse(b.redeemable(now));
        b.expiresAt = now.plusDays(3);
        assertTrue(b.redeemable(now));
    }

    @Test
    @DisplayName("RedeemCodeEntity：兑换码状态与兑换信息回填")
    void redeemCodeEntity() {
        RedeemCodeEntity c = new RedeemCodeEntity();
        c.id = "rc-1";
        c.batchId = "batch-1";
        c.code = "SPRING2026";

        assertEquals(RedeemCodeEntity.Status.AVAILABLE, c.status);
        assertNull(c.redeemedBy);
        c.status = RedeemCodeEntity.Status.REDEEMED;
        c.redeemedBy = "p-100";
        c.redeemedAt = LocalDateTime.now().minusMinutes(5);
        assertEquals(RedeemCodeEntity.Status.REDEEMED, c.status);
        assertEquals("p-100", c.redeemedBy);
        assertNotNull(c.redeemedAt);
    }

    @Test
    @DisplayName("RedeemRecordEntity：兑换记录字段固化")
    void redeemRecordEntity() {
        RedeemRecordEntity r = new RedeemRecordEntity();
        r.id = "rr-1";
        r.batchId = "batch-1";
        r.gameId = "g1";
        r.playerKey = "p-100";
        r.seq = 1;
        r.code = "SPRING2026";
        r.reward = "[{\"type\":\"item\",\"id\":\"gem\",\"count\":100}]";
        r.redeemedAt = LocalDateTime.now().minusMinutes(2);

        assertEquals("batch-1", r.batchId);
        assertEquals("p-100", r.playerKey);
        assertEquals(1, r.seq);
        assertEquals("SPRING2026", r.code);
        assertTrue(r.reward.contains("gem"));
    }

    @Test
    @DisplayName("ReportEntity：报表状态迁移/运行记录/描述与可视化配置解析")
    void reportEntity() {
        ReportEntity r = new ReportEntity();
        r.gameId = "g1";
        r.name = "日活跃报表";
        r.displayName = "每日活跃用户";
        r.reportCategory = "analytics";
        r.chartType = "line";

        assertTrue(r.isDraft());
        assertFalse(r.isActive());
        assertFalse(r.isScheduled());
        assertFalse(r.isPublicAccessible());

        r.markAsPublished();
        assertTrue(r.isActive());
        assertFalse(r.isDraft());
        r.status = ReportEntity.ReportStatus.SCHEDULED;
        assertTrue(r.isScheduled());
        assertTrue(r.isActive());
        r.markAsArchived();
        assertFalse(r.isActive());

        r.isPublic = null;
        assertFalse(r.isPublicAccessible());
        r.isPublic = true;
        assertTrue(r.isPublicAccessible());

        r.totalRuns = null;
        r.recordRun("成功");
        assertEquals(1L, r.totalRuns);
        assertNotNull(r.lastRunAt);
        assertEquals("成功", r.lastRunStatus);
        r.recordRun("失败");
        assertEquals(2L, r.totalRuns);
        assertEquals("失败", r.lastRunStatus);

        String desc = r.getReportDescription();
        assertTrue(desc.contains("custom"));
        assertTrue(desc.contains("每日活跃用户"));
        assertTrue(desc.contains("analytics"));
        assertTrue(desc.contains("line"));
        r.displayName = null;
        r.reportCategory = null;
        r.chartType = null;
        assertTrue(r.getReportDescription().contains("日活跃报表"));
        assertTrue(r.getReportDescription().contains("general"));
        assertTrue(r.getReportDescription().contains("table"));

        assertEquals(0, r.getAverageRunTimeMinutes());

        assertTrue(r.getVisualizationConfig().containsKey("chartType"));
        assertEquals("table", r.getVisualizationConfig().get("chartType"));
        r.visualization = "{\"chartType\":\"pie\"}";
        assertEquals("pie", r.getVisualizationConfig().get("chartType"));
        r.visualization = "不是合法JSON";
        assertTrue(r.getVisualizationConfig().isEmpty());
    }

    @Test
    @DisplayName("RevenueAnalysisEntity：收入指标填充与 onCreate")
    void revenueAnalysisEntity() {
        RevenueAnalysisEntity v = new RevenueAnalysisEntity();
        v.gameId = "g1";
        v.environment = "prod";
        v.analysisDate = LocalDate.now().minusDays(1);
        v.revenueType = RevenueAnalysisEntity.RevenueType.IAP;
        v.totalRevenue = 58000.5;
        v.iapRevenue = 50000.0;
        v.adRevenue = 5000.5;
        v.subscriptionRevenue = 3000.0;
        v.totalUsers = 100000L;
        v.payingUsers = 4200L;
        v.newPayingUsers = 260L;
        v.arpu = 0.58;
        v.arppu = 13.8;
        v.totalTransactions = 6100L;
        v.avgTransactionValue = 9.5;
        v.platform = "ios";
        v.country = "CN";
        v.currency = "CNY";

        assertEquals(RevenueAnalysisEntity.RevenueType.IAP, v.revenueType);
        assertEquals(58000.5, v.totalRevenue, 0.001);
        assertEquals(4200L, v.payingUsers);

        assertNull(v.createdAt);
        v.onCreate();
        assertNotNull(v.createdAt);
        LocalDateTime preset = LocalDateTime.now().minusHours(1);
        v.createdAt = preset;
        v.onCreate();
        assertEquals(preset, v.createdAt);
    }

    @Test
    @DisplayName("RiskCaseEntity：执行状态/风险等级/处置与解除封禁分支")
    void riskCaseEntity() {
        LocalDateTime now = LocalDateTime.now();
        RiskCaseEntity c = new RiskCaseEntity();
        c.riskRuleId = "rule-1";
        c.gameId = "g1";
        c.caseNumber = "CASE_20260909_001";
        c.targetType = "player_id";
        c.targetId = "p-100";
        c.createdAt = now.minusHours(2);

        assertTrue(c.isPending());
        assertFalse(c.isExecuted());
        assertFalse(c.isFailed());
        assertFalse(c.needsReview());
        assertFalse(c.isHighRisk());
        assertFalse(c.isBlocked());
        assertFalse(c.isUnblocked());
        assertFalse(c.isConfirmedFraud());
        assertFalse(c.isConfirmedBenign());
        assertFalse(c.isResolved());
        assertEquals(0, c.getResolutionTimeMinutes());

        c.actionTaken = RiskCaseEntity.ActionType.REVIEW;
        assertTrue(c.needsReview());
        c.actionTaken = RiskCaseEntity.ActionType.BLOCK;
        assertTrue(c.needsReview());
        assertFalse(c.isBlocked());
        c.markAsExecuted();
        assertTrue(c.isExecuted());
        assertTrue(c.isBlocked());
        assertNotNull(c.executedAt);

        c.riskLevel = RiskCaseEntity.RiskLevel.HIGH;
        assertTrue(c.isHighRisk());
        c.riskLevel = RiskCaseEntity.RiskLevel.CRITICAL;
        assertTrue(c.isHighRisk());

        RiskCaseEntity f = new RiskCaseEntity();
        f.riskRuleId = "rule-1";
        f.gameId = "g1";
        f.caseNumber = "CASE_20260909_002";
        f.targetType = "device_id";
        f.targetId = "dev-1";
        f.markAsFailed("动作执行异常");
        assertTrue(f.isFailed());
        assertEquals("动作执行异常", f.executionError);

        c.disposition = "confirmed_fraud";
        assertTrue(c.isConfirmedFraud());
        assertFalse(c.isConfirmedBenign());
        c.disposition = "confirmed_benign";
        assertTrue(c.isConfirmedBenign());
        assertTrue(c.isResolved());

        c.unblock("审核员-甲", "误判解除");
        assertTrue(c.isUnblocked());
        assertEquals("审核员-甲", c.unblockedBy);
        assertEquals("误判解除", c.unblockReason);

        c.completeReview("审核员-乙", "证据充分", "confirmed_fraud");
        assertEquals("completed", c.reviewStatus);
        assertEquals("审核员-乙", c.reviewedBy);
        assertEquals("confirmed_fraud", c.disposition);
        assertNotNull(c.resolvedAt);
        assertTrue(c.getResolutionTimeMinutes() >= 119);

        assertTrue(c.getCaseTitle().contains("p-100"));
        assertTrue(c.getCaseTitle().contains("block"));
    }

    @Test
    @DisplayName("RiskRuleEntity：规则启用/自动封禁/冷却窗口与动作描述")
    void riskRuleEntity() {
        RiskRuleEntity r = new RiskRuleEntity();
        r.gameId = "g1";
        r.name = "高频充值风控";

        assertTrue(r.isActive());
        assertTrue(r.isGlobal());
        assertFalse(r.isAutoBlockEnabled());
        assertFalse(r.isInTestMode());
        assertTrue(r.needsReview());
        assertFalse(r.isHighRisk());
        assertFalse(r.isCriticalRisk());

        r.deletedAt = LocalDateTime.now();
        assertFalse(r.isActive());
        r.deletedAt = null;
        r.environmentId = "env-1";
        assertFalse(r.isGlobal());

        r.enableAutoBlock = true;
        assertFalse(r.isAutoBlockEnabled());
        r.actionType = RiskRuleEntity.ActionType.BLOCK;
        assertTrue(r.isAutoBlockEnabled());
        r.enableAutoBlock = null;
        assertFalse(r.isAutoBlockEnabled());

        r.testMode = null;
        assertFalse(r.isInTestMode());
        r.testMode = true;
        assertTrue(r.isInTestMode());

        r.enableReviewQueue = false;
        assertFalse(r.needsReview());
        r.actionType = RiskRuleEntity.ActionType.REVIEW;
        assertTrue(r.needsReview());
        r.actionType = RiskRuleEntity.ActionType.ALERT;

        r.riskLevel = RiskRuleEntity.RiskLevel.HIGH;
        assertTrue(r.isHighRisk());
        r.riskLevel = RiskRuleEntity.RiskLevel.CRITICAL;
        assertTrue(r.isHighRisk());
        assertTrue(r.isCriticalRisk());

        r.totalTriggeredCount = null;
        r.recordTrigger();
        assertEquals(1L, r.totalTriggeredCount);
        assertNotNull(r.lastTriggeredAt);
        r.totalBlockedCount = null;
        r.recordBlock();
        assertEquals(1L, r.totalBlockedCount);
        r.totalReviewCount = null;
        r.recordReview();
        assertEquals(1L, r.totalReviewCount);

        assertFalse(r.isInCooldown(null));
        r.cooldownMinutes = 0;
        assertFalse(r.isInCooldown(LocalDateTime.now().minusMinutes(1)));
        r.cooldownMinutes = null;
        assertFalse(r.isInCooldown(LocalDateTime.now()));
        r.cooldownMinutes = 60;
        assertFalse(r.isInCooldown(null));
        assertTrue(r.isInCooldown(LocalDateTime.now().minusMinutes(30)));
        assertTrue(r.isInCooldown(LocalDateTime.now().minusMinutes(2)));  // 仍在 60min 冷却内
        assertFalse(r.isInCooldown(LocalDateTime.now().minusMinutes(90)));

        r.actionType = RiskRuleEntity.ActionType.BLOCK;
        r.blockDuration = 60;
        assertEquals("block for 60 minutes", r.getActionDescription());
        r.blockDuration = null;
        assertEquals("block", r.getActionDescription());
        r.actionType = RiskRuleEntity.ActionType.ALERT;
        assertEquals("alert", r.getActionDescription());
    }

    @Test
    @DisplayName("RoleEntity：启用/内置判定/权限匹配两个重载与权限ID集合")
    void roleEntity() {
        RoleEntity role = new RoleEntity();
        role.id = "analyst";
        role.name = "数据分析师";
        role.description = "只读分析权限";

        assertTrue(role.isEnabled());
        assertFalse(role.isSystem());
        role.enabled = null;
        assertFalse(role.isEnabled());
        role.enabled = true;
        role.system = true;
        assertTrue(role.isSystem());

        assertFalse(role.hasPermission("game:read"));
        assertTrue(role.getPermissionIds().isEmpty());

        PermissionEntity p1 = new PermissionEntity();
        p1.id = "game:read";
        p1.name = "读取游戏";
        p1.type = PermissionEntity.PermissionType.DATA;
        p1.resourceType = "game";
        p1.action = PermissionEntity.PermissionAction.READ;
        p1.scope = PermissionEntity.PermissionScope.GAME;
        p1.enabled = true;

        PermissionEntity p2 = new PermissionEntity();
        p2.id = "game:delete";
        p2.name = "删除游戏";
        p2.type = PermissionEntity.PermissionType.SYSTEM;
        p2.resourceType = "game";
        p2.action = PermissionEntity.PermissionAction.DELETE;
        p2.scope = PermissionEntity.PermissionScope.GLOBAL;
        p2.enabled = false;

        PermissionEntity p3 = new PermissionEntity();
        p3.id = "weird:manage";
        p3.name = "无资源类型权限";
        p3.type = PermissionEntity.PermissionType.API;
        p3.action = PermissionEntity.PermissionAction.MANAGE;
        p3.scope = PermissionEntity.PermissionScope.GLOBAL;
        p3.enabled = true;

        role.permissions = Set.of(p1, p2, p3);
        assertTrue(role.hasPermission("game:read"));
        assertFalse(role.hasPermission("game:delete"));
        assertFalse(role.hasPermission("不存在"));
        assertTrue(role.hasPermission("game", PermissionEntity.PermissionAction.READ));
        assertFalse(role.hasPermission("game", PermissionEntity.PermissionAction.DELETE));
        assertFalse(role.hasPermission("environment", PermissionEntity.PermissionAction.READ));
        assertTrue(role.getPermissionIds().contains("weird:manage"));
        assertEquals(3, role.getPermissionIds().size());
    }

    @Test
    @DisplayName("SDKVersionEntity：版本状态/变更类型/可用性/退役临近与生命周期回调")
    void sdkVersionEntity() {
        SDKVersionEntity v = new SDKVersionEntity();
        v.platform = SDKVersionEntity.SDKPlatform.ANDROID;
        v.version = "2.1.0";
        v.createdBy = "SDK-团队";

        assertTrue(v.isDraft());
        assertFalse(v.isTesting());
        assertFalse(v.isBeta());
        assertFalse(v.isReleased());
        assertFalse(v.isDeprecated());
        assertFalse(v.isRetired());
        assertFalse(v.isMajor());
        assertFalse(v.isMinor());
        assertTrue(v.isPatch());
        assertFalse(v.isHotfix());
        assertFalse(v.isAvailable());
        assertFalse(v.isRetiredSoon());

        v.versionStatus = SDKVersionEntity.VersionStatus.TESTING;
        assertTrue(v.isTesting());
        v.versionStatus = SDKVersionEntity.VersionStatus.BETA;
        assertTrue(v.isBeta());
        assertTrue(v.isAvailable());
        v.changeType = SDKVersionEntity.ChangeType.MAJOR;
        assertTrue(v.isMajor());
        v.changeType = SDKVersionEntity.ChangeType.MINOR;
        assertTrue(v.isMinor());
        v.changeType = SDKVersionEntity.ChangeType.HOTFIX;
        assertTrue(v.isHotfix());

        v.retirementDate = LocalDateTime.now().plusDays(30);
        assertTrue(v.isRetiredSoon());
        v.retirementDate = LocalDateTime.now().plusDays(200);
        assertFalse(v.isRetiredSoon());

        v.release("发布员-甲");
        assertTrue(v.isReleased());
        assertTrue(v.isAvailable());
        assertNotNull(v.releasedAt);
        assertEquals("发布员-甲", v.releasedBy);
        v.recordDownload();
        assertEquals(1L, v.totalDownloads);
        v.recordDownload();
        assertEquals(2L, v.totalDownloads);
        v.updateActiveInstallations(500L);
        assertEquals(500L, v.activeInstallations);

        v.deprecate("请升级到 3.0");
        assertTrue(v.isDeprecated());
        assertEquals("请升级到 3.0", v.deprecationNotice);
        v.retire();
        assertTrue(v.isRetired());
        assertNotNull(v.retirementDate);

        SDKVersionEntity fresh = new SDKVersionEntity();
        assertNull(fresh.createdAt);
        fresh.onCreate();
        assertNotNull(fresh.createdAt);
        assertEquals(SDKVersionEntity.VersionStatus.DRAFT, fresh.versionStatus);
        assertEquals(SDKVersionEntity.ChangeType.PATCH, fresh.changeType);
        fresh.versionStatus = SDKVersionEntity.VersionStatus.RELEASED;
        fresh.changeType = SDKVersionEntity.ChangeType.MAJOR;
        LocalDateTime preset = LocalDateTime.now().minusHours(1);
        fresh.createdAt = preset;
        fresh.onCreate();
        assertEquals(preset, fresh.createdAt);
        assertEquals(SDKVersionEntity.VersionStatus.RELEASED, fresh.versionStatus);
        assertEquals(SDKVersionEntity.ChangeType.MAJOR, fresh.changeType);
        fresh.onUpdate();
        assertNotNull(fresh.updatedAt);
    }

    @Test
    @DisplayName("SSOConfigEntity：协议判定/状态迁移/自动开户开关与生命周期回调")
    void ssoConfigEntity() {
        SSOConfigEntity s = new SSOConfigEntity();
        s.name = "企业 Okta 登录";
        s.createdBy = "管理员";

        assertTrue(s.isDisabled());
        assertFalse(s.isActive());
        assertFalse(s.isTesting());
        assertFalse(s.hasError());
        assertFalse(s.isSAML());
        assertFalse(s.isOAuth());
        assertFalse(s.isOIDC());
        assertFalse(s.isAutoProvisionEnabled());

        s.ssoProtocol = SSOConfigEntity.SSOProtocol.SAML2;
        assertTrue(s.isSAML());
        assertFalse(s.isOAuth());
        s.ssoProtocol = SSOConfigEntity.SSOProtocol.OAUTH2;
        assertTrue(s.isOAuth());
        assertFalse(s.isOIDC());
        s.ssoProtocol = SSOConfigEntity.SSOProtocol.OIDC;
        assertTrue(s.isOAuth());
        assertTrue(s.isOIDC());

        s.recordError("证书校验失败");
        assertTrue(s.hasError());
        assertEquals("证书校验失败", s.errorMessage);
        s.activate();
        assertTrue(s.isActive());
        assertNull(s.errorMessage);
        s.startTesting();
        assertTrue(s.isTesting());
        s.disable();
        assertTrue(s.isDisabled());

        s.autoProvision = null;
        assertFalse(s.isAutoProvisionEnabled());
        s.autoProvision = true;
        assertTrue(s.isAutoProvisionEnabled());

        SSOConfigEntity fresh = new SSOConfigEntity();
        assertNull(fresh.createdAt);
        fresh.onCreate();
        assertNotNull(fresh.createdAt);
        assertEquals(SSOConfigEntity.SSOStatus.DISABLED, fresh.ssoStatus);
        fresh.ssoStatus = null;
        LocalDateTime preset = LocalDateTime.now().minusHours(2);
        fresh.createdAt = preset;
        fresh.onCreate();
        assertEquals(preset, fresh.createdAt);
        assertEquals(SSOConfigEntity.SSOStatus.DISABLED, fresh.ssoStatus);
        fresh.onUpdate();
        assertNotNull(fresh.updatedAt);
    }

    @DisplayName("SamplingPolicyEntity：策略启用/采样开关/有效采样率与丢弃率")
    @Test
    void samplingPolicyEntity() {
        SamplingPolicyEntity s = new SamplingPolicyEntity();
        s.gameId = "g1";
        s.name = "全局采样策略";

        assertTrue(s.isActive());
        assertTrue(s.isGlobal());
        assertFalse(s.isDynamicSamplingEnabled());
        assertFalse(s.isPrioritySamplingEnabled());

        s.deletedAt = LocalDateTime.now();
        assertFalse(s.isActive());
        s.deletedAt = null;
        s.status = SamplingPolicyEntity.PolicyStatus.DISABLED;
        assertFalse(s.isActive());
        s.status = SamplingPolicyEntity.PolicyStatus.ACTIVE;
        s.environmentId = "env-1";
        assertFalse(s.isGlobal());

        s.enableDynamicSampling = null;
        assertFalse(s.isDynamicSamplingEnabled());
        s.enableDynamicSampling = true;
        assertTrue(s.isDynamicSamplingEnabled());
        s.enablePrioritySampling = null;
        assertFalse(s.isPrioritySamplingEnabled());
        s.enablePrioritySampling = true;
        assertTrue(s.isPrioritySamplingEnabled());

        s.effectiveSampleRate = null;
        s.defaultSampleRate = 0.3;
        assertEquals(0.3, s.getEffectiveSampleRate(), 0.0001);
        s.effectiveSampleRate = 0.7;
        assertEquals(0.7, s.getEffectiveSampleRate(), 0.0001);
        assertTrue(s.shouldSample("任意事件"));

        assertEquals(0.0, s.getCurrentDropRate(), 0.0001);
        s.totalSampledCount = null;
        s.totalDroppedCount = null;
        s.incrementSampled();
        s.incrementSampled();
        s.incrementSampled();
        s.incrementDropped();
        assertEquals(3L, s.totalSampledCount);
        assertEquals(1L, s.totalDroppedCount);
        assertEquals(0.25, s.getCurrentDropRate(), 0.0001);
    }

    @Test
    @DisplayName("SecurityPolicyEntity：五种策略类型/范围判定/开关防御与生命周期回调")
    void securityPolicyEntity() {
        SecurityPolicyEntity p = new SecurityPolicyEntity();
        p.policyName = "全局密码策略";
        p.createdBy = "安全-吴九";
        p.policyType = SecurityPolicyEntity.PolicyType.PASSWORD;

        assertTrue(p.isPasswordPolicy());
        assertFalse(p.isSessionPolicy());
        assertFalse(p.isMFAPolicy());
        assertFalse(p.isAccessPolicy());
        assertFalse(p.isApiPolicy());
        p.policyType = SecurityPolicyEntity.PolicyType.SESSION;
        assertTrue(p.isSessionPolicy());
        p.policyType = SecurityPolicyEntity.PolicyType.MFA;
        assertTrue(p.isMFAPolicy());
        p.policyType = SecurityPolicyEntity.PolicyType.ACCESS;
        assertTrue(p.isAccessPolicy());
        p.policyType = SecurityPolicyEntity.PolicyType.API;
        assertTrue(p.isApiPolicy());

        assertTrue(p.isGlobal());
        assertFalse(p.isGameLevel());
        p.policyScope = SecurityPolicyEntity.PolicyScope.GAME;
        assertTrue(p.isGameLevel());
        assertFalse(p.isGlobal());

        assertTrue(p.isEnabled());
        p.enabled = null;
        assertFalse(p.isEnabled());
        p.enabled = true;
        p.deletedAt = LocalDateTime.now();
        assertFalse(p.isEnabled());
        p.deletedAt = null;

        assertFalse(p.requiresMFA());
        p.mfaRequired = null;
        assertFalse(p.requiresMFA());
        p.mfaRequired = true;
        assertTrue(p.requiresMFA());

        assertFalse(p.allowsPasswordReuse());
        p.allowPasswordReuse = null;
        assertFalse(p.allowsPasswordReuse());
        p.allowPasswordReuse = true;
        assertTrue(p.allowsPasswordReuse());

        p.requireIpWhitelist = null;
        assertFalse(p.isIpWhitelistRequired());
        p.requireIpWhitelist = true;
        assertTrue(p.isIpWhitelistRequired());

        p.requireHttps = null;
        assertFalse(p.requiresHttps());
        p.requireHttps = true;
        assertTrue(p.requiresHttps());

        SecurityPolicyEntity fresh = new SecurityPolicyEntity();
        fresh.onCreate();
        assertNotNull(fresh.createdAt);
        assertEquals(Boolean.TRUE, fresh.enabled);
        fresh.enabled = null;
        LocalDateTime preset = LocalDateTime.now().minusHours(1);
        fresh.createdAt = preset;
        fresh.onCreate();
        assertEquals(preset, fresh.createdAt);
        assertEquals(Boolean.TRUE, fresh.enabled);
        fresh.onUpdate();
        assertNotNull(fresh.updatedAt);
    }

    @Test
    @DisplayName("SessionAnalysisEntity：会话指标填充与 onCreate")
    void sessionAnalysisEntity() {
        SessionAnalysisEntity s = new SessionAnalysisEntity();
        s.gameId = "g1";
        s.environment = "prod";
        s.analysisDate = LocalDate.now().minusDays(1);
        s.totalSessions = 45000L;
        s.uniqueUsers = 21000L;
        s.avgSessionDuration = 1_800_000L;
        s.medianSessionDuration = 1_200_000L;
        s.p95SessionDuration = 5_400_000L;
        s.avgEventsPerSession = 42.5;
        s.avgPagesPerSession = 8.3;
        s.bounceSessions = 5000L;
        s.bounceRate = 0.11;
        s.highQualitySessions = 18000L;
        s.mediumQualitySessions = 15000L;
        s.lowQualitySessions = 12000L;
        s.platform = "android";
        s.sessionSource = "organic";

        assertEquals(45000L, s.totalSessions);
        assertEquals(0.11, s.bounceRate, 0.0001);
        assertEquals("organic", s.sessionSource);

        assertNull(s.createdAt);
        s.onCreate();
        assertNotNull(s.createdAt);
        LocalDateTime preset = LocalDateTime.now().minusHours(1);
        s.createdAt = preset;
        s.onCreate();
        assertEquals(preset, s.createdAt);
    }

    @Test
    @DisplayName("StandardEventEntity：重要性/分析用途判定与全名拼接")
    void standardEventEntity() {
        StandardEventEntity e = new StandardEventEntity();
        e.eventTypeId = "et-1";
        e.eventName = "purchase";
        e.displayName = "内购完成";

        assertTrue(e.isActive());
        assertFalse(e.isCritical());
        assertFalse(e.isImportant());
        assertFalse(e.supportsFunnel());
        assertFalse(e.supportsRetention());
        assertFalse(e.supportsCohort());
        assertFalse(e.isRevenueEvent());
        assertEquals("purchase", e.getFullEventName());

        e.importance = StandardEventEntity.EventImportance.CRITICAL;
        assertTrue(e.isCritical());
        assertTrue(e.isImportant());
        e.importance = StandardEventEntity.EventImportance.HIGH;
        assertFalse(e.isCritical());
        assertTrue(e.isImportant());

        e.enableFunnel = null;
        assertFalse(e.supportsFunnel());
        e.enableFunnel = true;
        assertTrue(e.supportsFunnel());
        e.enableRetention = true;
        assertTrue(e.supportsRetention());
        e.enableCohort = true;
        assertTrue(e.supportsCohort());
        e.enableRevenue = true;
        assertTrue(e.isRevenueEvent());

        StandardEventTypeEntity type = new StandardEventTypeEntity();
        type.id = "et-1";
        type.code = "business";
        e.eventType = type;
        assertEquals("business.purchase", e.getFullEventName());

        e.status = StandardEventEntity.EventStatus.DEPRECATED;
        assertFalse(e.isActive());
        e.status = StandardEventEntity.EventStatus.ACTIVE;
        e.deletedAt = LocalDateTime.now();
        assertFalse(e.isActive());
    }

    @Test
    @DisplayName("StandardEventTypeEntity：核心事件/聚合开关与分类推断全分支")
    void standardEventTypeEntity() {
        StandardEventTypeEntity t = new StandardEventTypeEntity();
        t.id = "et-session";
        t.code = "session";
        t.name = "会话事件";

        assertTrue(t.isActive());
        assertFalse(t.isCoreEvent());
        assertTrue(t.supportsAggregation());
        assertEquals("lifecycle", t.getCategory());

        t.isCore = null;
        assertFalse(t.isCoreEvent());
        t.isCore = true;
        assertTrue(t.isCoreEvent());
        t.enableAggregation = null;
        assertFalse(t.supportsAggregation());
        t.enableAggregation = true;

        t.category = "自定义分类";
        assertEquals("自定义分类", t.getCategory());
        t.category = null;

        t.code = "user";
        assertEquals("lifecycle", t.getCategory());
        t.code = "business";
        assertEquals("monetization", t.getCategory());
        t.code = "progression";
        assertEquals("progression", t.getCategory());
        t.code = "design";
        assertEquals("progression", t.getCategory());
        t.code = "error";
        assertEquals("system", t.getCategory());
        t.code = "risk";
        assertEquals("system", t.getCategory());
        t.code = "ad";
        assertEquals("engagement", t.getCategory());

        t.deletedAt = LocalDateTime.now();
        assertFalse(t.isActive());
    }

    @Test
    @DisplayName("StorageProfileEntity：活跃判定与专用隔离策略")
    void storageProfileEntity() {
        StorageProfileEntity s = new StorageProfileEntity();
        s.id = "sp-1";
        s.name = "专用集群-华东";
        s.displayName = "华东专用";

        assertTrue(s.isActive());
        assertFalse(s.isDedicated());
        s.active = null;
        assertFalse(s.isActive());
        s.active = true;
        s.deletedAt = LocalDateTime.now();
        assertFalse(s.isActive());
        s.deletedAt = null;
        s.isolationStrategy = StorageProfileEntity.IsolationStrategy.DEDICATED;
        assertTrue(s.isDedicated());
    }

    @Test
    @DisplayName("SystemConfigEntity：类型判定/取值转换/加密脱敏/版本递增与生命周期回调")
    void systemConfigEntity() {
        SystemConfigEntity c = new SystemConfigEntity();
        c.configKey = "ingest.batch.size";
        c.valueType = "boolean";
        c.configValue = "true";

        assertTrue(c.isActive());
        assertTrue(c.isBoolean());
        assertFalse(c.isInteger());
        assertFalse(c.isString());
        assertFalse(c.isJson());
        assertEquals(Boolean.TRUE, c.getBooleanValue());
        assertNull(c.getIntegerValue());

        c.configValue = null;
        assertNull(c.getBooleanValue());
        assertNull(c.getStringValue());
        c.valueType = "integer";
        assertNull(c.getBooleanValue());
        c.configValue = "42";
        assertEquals(42, c.getIntegerValue());
        c.valueType = "string";
        assertNull(c.getIntegerValue());
        assertTrue(c.isString());
        c.valueType = "json";
        assertTrue(c.isJson());

        c.isEncrypted = true;
        assertEquals("[ENCRYPTED]", c.getStringValue());
        assertEquals("42", c.getRawValue());
        c.isEncrypted = false;
        assertEquals("42", c.getStringValue());

        c.version = 1;
        c.setStringValue("100");
        assertEquals("100", c.configValue);
        assertEquals(2, c.version);
        assertNotNull(c.lastModifiedAt);

        SystemConfigEntity fresh = new SystemConfigEntity();
        fresh.onCreate();
        assertNotNull(fresh.createdAt);
        fresh.configValue = null;
        fresh.defaultValue = "默认值";
        fresh.onCreate();
        assertEquals("默认值", fresh.configValue);
        fresh.configValue = "已有值";
        fresh.defaultValue = "另一个默认";
        fresh.onCreate();
        assertEquals("已有值", fresh.configValue);
        assertNull(fresh.lastModifiedAt);
        fresh.onUpdate();
        assertNotNull(fresh.lastModifiedAt);
        LocalDateTime preset = LocalDateTime.now().minusHours(1);
        fresh.lastModifiedAt = preset;
        fresh.onUpdate();
        assertEquals(preset, fresh.lastModifiedAt);
    }

    @Test
    @DisplayName("TrackingPlanEntity：草稿编辑/激活弃用状态机（含异常分支）/全名")
    void trackingPlanEntity() {
        TrackingPlanEntity t = new TrackingPlanEntity();
        t.gameId = "g1";
        t.name = "2026-Q1 计划";

        assertTrue(t.isDraft());
        assertTrue(t.canEdit());
        assertFalse(t.isActive());
        assertTrue(t.isGlobal());

        t.activate("管理员-甲");
        assertTrue(t.isActive());
        assertFalse(t.isDraft());
        assertFalse(t.canEdit());
        assertNotNull(t.activatedAt);
        assertEquals("管理员-甲", t.activatedBy);
        assertThrows(IllegalStateException.class, () -> t.activate("再激活"));

        t.deactivate();
        assertEquals(TrackingPlanEntity.PlanStatus.DEPRECATED, t.status);
        assertNotNull(t.deactivatedAt);
        assertThrows(IllegalStateException.class, t::deactivate);

        assertEquals("2026-Q1 计划", t.getFullName());
        GameEntity g = new GameEntity();
        g.name = "星域远征";
        t.game = g;
        assertEquals("星域远征 - 2026-Q1 计划", t.getFullName());

        t.environmentId = "env-1";
        assertFalse(t.isGlobal());

        TrackingPlanEntity deleted = new TrackingPlanEntity();
        deleted.name = "已删除计划";
        deleted.status = TrackingPlanEntity.PlanStatus.ACTIVE;
        deleted.deletedAt = LocalDateTime.now();
        assertFalse(deleted.isActive());
        assertFalse(deleted.isDraft());
        assertFalse(deleted.canEdit());
    }

    @Test
    @DisplayName("UserInvitationEntity：有效/过期判定/接受拒绝取消与范围描述")
    void userInvitationEntity() {
        LocalDateTime now = LocalDateTime.now();
        UserInvitationEntity i = new UserInvitationEntity();
        i.email = "newbie@oddsmaker.io";
        i.inviterId = "u-1";
        i.token = "invite-token-9";
        i.role = UserRoleEntity.RoleType.ANALYST;
        i.scope = UserRoleEntity.PermissionScope.GAME;
        i.expiresAt = now.plusDays(3);

        assertTrue(i.isValid());
        assertFalse(i.isExpired());
        assertEquals("https://oddsmaker.io/invitation/accept?token=invite-token-9",
                i.getInvitationUrl("https://oddsmaker.io"));
        assertEquals("", i.getScopeDescription());

        i.expiresAt = now.minusMinutes(1);
        assertFalse(i.isValid());
        assertTrue(i.isExpired());
        i.expiresAt = now.plusDays(3);

        GameEntity g = new GameEntity();
        g.name = "仙侠传说";
        i.game = g;
        assertEquals("游戏: 仙侠传说", i.getScopeDescription());
        i.environmentId = "env-9";
        assertEquals("游戏: 仙侠传说 > 环境: env-9", i.getScopeDescription());
        i.game = null;
        assertEquals("环境: env-9", i.getScopeDescription());

        i.accept("u-99");
        assertEquals(UserInvitationEntity.InvitationStatus.ACCEPTED, i.status);
        assertNotNull(i.acceptedAt);
        assertEquals("u-99", i.createdUserId);
        assertFalse(i.isValid());

        UserInvitationEntity r = new UserInvitationEntity();
        r.email = "other@oddsmaker.io";
        r.inviterId = "u-1";
        r.token = "invite-token-8";
        r.role = UserRoleEntity.RoleType.VIEWER;
        r.scope = UserRoleEntity.PermissionScope.GLOBAL;
        r.expiresAt = now.plusDays(1);
        r.reject();
        assertEquals(UserInvitationEntity.InvitationStatus.REJECTED, r.status);
        assertNotNull(r.rejectedAt);
        r.cancel();
        assertEquals(UserInvitationEntity.InvitationStatus.CANCELED, r.status);
    }

    @Test
    @DisplayName("UserRoleEntity：启用/过期/有效判定与三种作用域")
    void userRoleEntity() {
        UserRoleEntity u = new UserRoleEntity();
        u.userId = "u-1";
        u.roleId = "analyst";

        assertTrue(u.isEnabled());
        assertFalse(u.isExpired());
        assertTrue(u.isValid());
        assertTrue(u.isGlobal());
        assertFalse(u.isGameScoped());
        assertFalse(u.isEnvironmentScoped());

        u.enabled = null;
        assertFalse(u.isEnabled());
        assertFalse(u.isValid());
        u.enabled = false;
        assertFalse(u.isValid());
        u.enabled = true;

        u.expiresAt = LocalDateTime.now().minusMinutes(1);
        assertTrue(u.isExpired());
        assertFalse(u.isValid());
        u.expiresAt = LocalDateTime.now().plusDays(1);
        assertFalse(u.isExpired());
        assertTrue(u.isValid());

        u.gameId = "g1";
        assertFalse(u.isGlobal());
        assertTrue(u.isGameScoped());
        assertFalse(u.isEnvironmentScoped());
        u.environment = "prod";
        assertTrue(u.isEnvironmentScoped());
        assertFalse(u.isGameScoped());
    }

    @Test
    @DisplayName("VirtualEconomyEntity：货币类型/通胀流量开关/告警阈值分支")
    void virtualEconomyEntity() {
        VirtualEconomyEntity v = new VirtualEconomyEntity();
        v.gameId = "g1";
        v.name = "钻石经济监控";
        v.currencyId = "gem";

        assertTrue(v.isActive());
        assertTrue(v.isAutoCalcEnabled());
        assertTrue(v.tracksInflation());
        assertTrue(v.hasFlowAnalysis());
        assertTrue(v.isPremiumCurrency());

        v.currencyType = VirtualEconomyEntity.CurrencyType.HARD;
        assertTrue(v.isPremiumCurrency());
        v.currencyType = VirtualEconomyEntity.CurrencyType.SOFT;
        assertFalse(v.isPremiumCurrency());

        v.enableAutoCalc = null;
        assertFalse(v.isAutoCalcEnabled());
        v.enableInflationMonitoring = null;
        assertFalse(v.tracksInflation());
        v.enableFlowAnalysis = null;
        assertFalse(v.hasFlowAnalysis());

        assertFalse(v.needsAlert(0.5));
        v.enableAlerts = false;
        assertFalse(v.needsAlert(0.1));
        v.enableAlerts = null;
        assertFalse(v.needsAlert(0.1));
        v.enableAlerts = true;
        assertFalse(v.needsAlert(null));
        v.alertThresholdLow = 0.2;
        v.alertThresholdHigh = 0.8;
        assertTrue(v.needsAlert(0.1));
        assertTrue(v.needsAlert(0.9));
        assertFalse(v.needsAlert(0.5));
        v.alertThresholdLow = null;
        v.alertThresholdHigh = null;
        assertFalse(v.needsAlert(0.5));

        v.deletedAt = LocalDateTime.now();
        assertFalse(v.isActive());
    }

    @Test
    @DisplayName("WebhookLogEntity：投递状态迁移/重试调度与日志摘要")
    void webhookLogEntity() {
        WebhookLogEntity w = new WebhookLogEntity();
        w.webhookConfigId = "wc-1";
        w.gameId = "g1";
        w.requestUrl = "https://hooks.example.com/risk";
        w.requestMethod = "POST";

        assertTrue(w.isPending());
        assertFalse(w.isSuccess());
        assertFalse(w.isFailed());
        assertFalse(w.shouldRetry());

        w.markAsSuccess(200, "{\"ok\":true}", 120L);
        assertTrue(w.isSuccess());
        assertEquals(200, w.responseStatus);
        assertEquals("{\"ok\":true}", w.responseBody);
        assertEquals(120L, w.responseTimeMs);
        assertNotNull(w.deliveredAt);
        assertTrue(w.getLogSummary().contains("200"));

        w.markAsFailed("连接被拒绝", "ConnectException");
        assertTrue(w.isFailed());
        assertTrue(w.shouldRetry());
        assertEquals("连接被拒绝", w.errorMessage);
        assertEquals("ConnectException", w.errorType);

        w.markAsTimeout();
        assertTrue(w.isFailed());
        assertEquals("Request timeout", w.errorMessage);
        assertEquals("TIMEOUT", w.errorType);
        assertTrue(w.shouldRetry());

        w.retryCount = null;
        LocalDateTime next = LocalDateTime.now().plusMinutes(5);
        w.scheduleRetry(next);
        assertEquals(WebhookLogEntity.DeliveryStatus.RETRYING, w.deliveryStatus);
        assertEquals(next, w.nextRetryAt);
        assertEquals(1, w.retryCount);
        assertTrue(w.isPending());

        w.responseStatus = null;
        w.responseTimeMs = null;
        assertTrue(w.getLogSummary().contains("(0ms)"));
    }

    @Test
    @DisplayName("IdentityLinkEntity：链接活跃/过期/强度判定/确认撤销与最后活跃天数")
    void identityLinkEntity() {
        IdentityLinkEntity l = new IdentityLinkEntity();
        l.identityId = "id-1";
        l.linkedIdentityType = "device_id";
        l.linkedId = "dev-77";
        l.linkStrength = 0.5;  // 默认 1.0 会命中强链接判定

        assertTrue(l.isActive());
        assertFalse(l.isConfirmed());
        assertFalse(l.isExpired());
        assertFalse(l.isStrongLink());
        assertFalse(l.isOwnedRelation());

        l.linkStrength = 0.9;
        assertTrue(l.isStrongLink());
        l.linkStrength = 0.5;
        assertFalse(l.isStrongLink());
        l.linkStrength = null;
        assertFalse(l.isStrongLink());
        l.linkStrength = 1.0;

        l.linkType = IdentityLinkEntity.LinkType.OWNED;
        assertTrue(l.isOwnedRelation());
        l.linkType = IdentityLinkEntity.LinkType.ASSOCIATED;
        assertFalse(l.isOwnedRelation());

        l.expiredAt = LocalDateTime.now().plusDays(1);
        assertFalse(l.isExpired());
        assertTrue(l.isActive());
        l.expiredAt = LocalDateTime.now().minusMinutes(1);
        assertTrue(l.isExpired());
        assertFalse(l.isActive());
        l.expiredAt = null;
        assertTrue(l.isActive());

        l.deletedAt = LocalDateTime.now();
        assertFalse(l.isActive());
        l.deletedAt = null;

        l.confirm("login");
        assertTrue(l.isConfirmed());
        assertNotNull(l.verifiedAt);
        assertNotNull(l.lastConfirmedAt);
        assertEquals("login", l.verificationMethod);

        l.usageCount = null;
        l.recordUsage();
        assertEquals(1L, l.usageCount);
        assertNotNull(l.lastSeenAt);

        assertEquals(0L, l.getDaysSinceLastSeen());  // lastSeenAt 刚被 recordActivity 更新
        l.lastSeenAt = LocalDateTime.now().minusDays(3);
        assertTrue(l.getDaysSinceLastSeen() >= 2);

        l.revoke();
        assertEquals(IdentityLinkEntity.LinkStatus.REVOKED, l.status);
        assertNotNull(l.expiredAt);
        assertFalse(l.isActive());
    }

    @Test
    @DisplayName("MFAConfigEntity：MFA 状态迁移/方式判定/尝试次数上限与生命周期回调")
    void mfaConfigEntity() {
        MFAConfigEntity m = new MFAConfigEntity();
        m.userId = "u-1";
        m.mfaMethod = MFAConfigEntity.MFAMethod.TOTP;

        assertFalse(m.isEnabled());
        assertFalse(m.isPending());
        assertFalse(m.isLocked());
        assertFalse(m.isRecoveryMode());
        assertFalse(m.isPrimary());
        assertTrue(m.isTOTP());
        assertFalse(m.isSMS());
        assertFalse(m.isEmail());
        assertFalse(m.hasExceededAttempts());

        m.mfaMethod = MFAConfigEntity.MFAMethod.SMS;
        assertTrue(m.isSMS());
        m.mfaMethod = MFAConfigEntity.MFAMethod.EMAIL;
        assertTrue(m.isEmail());
        m.mfaMethod = MFAConfigEntity.MFAMethod.TOTP;

        m.mfaStatus = MFAConfigEntity.MFAStatus.PENDING;
        assertTrue(m.isPending());
        m.mfaStatus = MFAConfigEntity.MFAStatus.LOCKED;
        assertTrue(m.isLocked());
        m.mfaStatus = MFAConfigEntity.MFAStatus.RECOVERY_MODE;
        assertTrue(m.isRecoveryMode());

        m.recordFailedVerification();
        m.recordFailedVerification();
        assertFalse(m.hasExceededAttempts());
        m.recordFailedVerification();
        assertTrue(m.hasExceededAttempts());
        m.verificationAttempts = null;
        assertFalse(m.hasExceededAttempts());

        m.enable();
        assertTrue(m.isEnabled());
        assertEquals(0, m.verificationAttempts);
        assertNotNull(m.enrolledAt);
        m.recordVerification();
        assertNotNull(m.lastVerifiedAt);
        m.markAsPrimary();
        assertTrue(m.isPrimary());
        m.lock();
        assertTrue(m.isLocked());
        m.unlock();
        assertTrue(m.isEnabled());
        assertEquals(0, m.verificationAttempts);
        m.disable();
        assertFalse(m.isEnabled());
        m.isPrimary = null;
        assertFalse(m.isPrimary());

        MFAConfigEntity fresh = new MFAConfigEntity();
        assertNull(fresh.createdAt);
        fresh.onCreate();
        assertNotNull(fresh.createdAt);
        assertEquals(MFAConfigEntity.MFAStatus.DISABLED, fresh.mfaStatus);
        fresh.mfaStatus = null;
        LocalDateTime preset = LocalDateTime.now().minusHours(1);
        fresh.createdAt = preset;
        fresh.onCreate();
        assertEquals(preset, fresh.createdAt);
        assertEquals(MFAConfigEntity.MFAStatus.DISABLED, fresh.mfaStatus);
        fresh.onUpdate();
        assertNotNull(fresh.updatedAt);
    }

    @Test
    @DisplayName("MailClaimEntity：领取记录字段固化")
    void mailClaimEntity() {
        MailClaimEntity c = new MailClaimEntity();
        c.id = "claim-1";
        c.mailId = "mail-1";
        c.gameId = "g1";
        c.playerKey = "p-100";
        c.claimedAttachments = "[{\"type\":\"item\",\"id\":\"gem\",\"count\":100}]";
        c.claimedAt = LocalDateTime.now().minusMinutes(1);

        assertEquals("mail-1", c.mailId);
        assertEquals("p-100", c.playerKey);
        assertTrue(c.claimedAttachments.contains("gem"));
        assertNotNull(c.claimedAt);
    }

    @Test
    @DisplayName("PermissionEntity：启用/内置/作用域判定与全权限名拼接")
    void permissionEntity() {
        PermissionEntity p = new PermissionEntity();
        p.id = "game:export";
        p.name = "导出游戏数据";
        p.type = PermissionEntity.PermissionType.DATA;
        p.resourceType = "game";
        p.action = PermissionEntity.PermissionAction.EXPORT;
        p.scope = PermissionEntity.PermissionScope.GAME;

        assertTrue(p.isEnabled());
        assertFalse(p.isSystem());
        assertFalse(p.isGlobal());
        assertTrue(p.isGameScoped());
        assertFalse(p.isEnvironmentScoped());
        assertEquals("game:export", p.getFullPermission());

        p.enabled = null;
        assertFalse(p.isEnabled());
        p.enabled = true;
        p.system = true;
        assertTrue(p.isSystem());

        p.scope = PermissionEntity.PermissionScope.GLOBAL;
        assertTrue(p.isGlobal());
        p.scope = PermissionEntity.PermissionScope.ENVIRONMENT;
        assertTrue(p.isEnvironmentScoped());
    }

    @Test
    @DisplayName("PiiFieldMappingEntity：敏感度分级/加密要求/保留天数回退")
    void piiFieldMappingEntity() {
        PiiFieldMappingEntity p = new PiiFieldMappingEntity();
        p.privacyPolicyId = "pp-1";
        p.fieldName = "email";

        assertTrue(p.isActive());
        assertFalse(p.isHighlySensitive());
        assertFalse(p.requiresEncryption());
        assertEquals(90, p.getEffectiveRetentionDays(90));
        p.retentionDays = 30;
        assertEquals(30, p.getEffectiveRetentionDays(90));
        p.retentionDays = null;

        p.piiSensitivity = PiiFieldMappingEntity.PiiSensitivity.HIGH;
        assertTrue(p.isHighlySensitive());
        p.piiSensitivity = PiiFieldMappingEntity.PiiSensitivity.CRITICAL;
        assertTrue(p.isHighlySensitive());
        p.piiSensitivity = PiiFieldMappingEntity.PiiSensitivity.LOW;
        assertFalse(p.isHighlySensitive());

        p.handling = PiiFieldMappingEntity.PiiHandling.MASK;
        assertFalse(p.requiresEncryption());
        p.handling = PiiFieldMappingEntity.PiiHandling.ENCRYPT;
        assertFalse(p.requiresEncryption());
        p.piiSensitivity = PiiFieldMappingEntity.PiiSensitivity.HIGH;
        assertTrue(p.requiresEncryption());

        p.deletedAt = LocalDateTime.now();
        assertFalse(p.isActive());
    }

    @Test
    @DisplayName("EventDefinitionEntity：重要性/弃用判定/必需标识与全名拼接")
    void eventDefinitionEntity() {
        EventDefinitionEntity e = new EventDefinitionEntity();
        e.trackingPlanId = "tp-1";
        e.eventName = "level_complete";
        e.eventType = "progression";

        assertTrue(e.isActive());
        assertFalse(e.isRequired());
        assertFalse(e.isDeprecated());
        assertFalse(e.hasRequiredIdentity());
        assertEquals("level_complete", e.getFullEventName());

        e.importance = EventDefinitionEntity.Importance.CRITICAL;
        assertTrue(e.isRequired());
        e.importance = EventDefinitionEntity.Importance.HIGH;
        assertTrue(e.isRequired());
        e.importance = EventDefinitionEntity.Importance.NORMAL;
        assertFalse(e.isRequired());

        e.status = EventDefinitionEntity.DefinitionStatus.DEPRECATED;
        assertTrue(e.isDeprecated());
        e.status = EventDefinitionEntity.DefinitionStatus.ACTIVE;
        e.importance = EventDefinitionEntity.Importance.DEPRECATED;
        assertTrue(e.isDeprecated());
        e.importance = EventDefinitionEntity.Importance.NORMAL;
        e.deletedAt = LocalDateTime.now();
        assertTrue(e.isDeprecated());
        assertFalse(e.isActive());
        e.deletedAt = null;

        e.requireUserId = true;
        assertTrue(e.hasRequiredIdentity());
        e.requireUserId = false;
        e.requireSessionId = true;
        assertTrue(e.hasRequiredIdentity());
        e.requireSessionId = false;
        e.requirePlayerId = true;
        assertTrue(e.hasRequiredIdentity());
        e.requirePlayerId = false;
        assertFalse(e.hasRequiredIdentity());

        TrackingPlanEntity tp = new TrackingPlanEntity();
        e.trackingPlan = tp;
        assertEquals("level_complete", e.getFullEventName());
        GameEntity g = new GameEntity();
        g.id = "g7";
        tp.game = g;
        assertEquals("g7.level_complete", e.getFullEventName());
    }

    @Test
    @DisplayName("EventPropertyDefinitionEntity：类型判定/校验存在性逐字段分支与描述拼接")
    void eventPropertyDefinitionEntity() {
        EventPropertyDefinitionEntity p = new EventPropertyDefinitionEntity();
        p.eventDefinitionId = "ed-1";
        p.propertyName = "level";

        assertTrue(p.isActive());
        assertFalse(p.isNumeric());
        assertTrue(p.isString());
        assertFalse(p.hasValidation());
        assertFalse(p.isSensitive());
        assertEquals("", p.getValidationDescription());

        p.type = EventPropertyDefinitionEntity.PropertyType.INTEGER;
        assertTrue(p.isNumeric());
        assertFalse(p.isString());
        p.type = EventPropertyDefinitionEntity.PropertyType.FLOAT;
        assertTrue(p.isNumeric());
        p.type = EventPropertyDefinitionEntity.PropertyType.ENUM;
        assertTrue(p.isString());
        p.type = EventPropertyDefinitionEntity.PropertyType.STRING;
        assertTrue(p.isString());

        p.minValue = 0.0;
        assertTrue(p.hasValidation());
        p.minValue = null;
        p.maxValue = 99.0;
        assertTrue(p.hasValidation());
        p.maxValue = null;
        p.minLength = 1;
        assertTrue(p.hasValidation());
        p.minLength = null;
        p.maxLength = 3;
        assertTrue(p.hasValidation());
        p.maxLength = null;
        p.regexPattern = "^\\d+$";
        assertTrue(p.hasValidation());
        p.regexPattern = null;
        p.allowedValues = "[1,2,3]";
        assertTrue(p.hasValidation());
        p.allowedValues = null;
        p.cardinalityLimit = 100;
        assertTrue(p.hasValidation());
        p.cardinalityLimit = null;
        assertFalse(p.hasValidation());

        p.required = true;
        p.minValue = 0.0;
        p.maxValue = 99.0;
        p.minLength = 1;
        p.maxLength = 3;
        p.regexPattern = "^\\d+$";
        p.allowedValues = "[1,2,3]";
        String desc = p.getValidationDescription();
        assertTrue(desc.startsWith("Required"));
        assertTrue(desc.contains("Min=0.0"));
        assertTrue(desc.contains("Max=99.0"));
        assertTrue(desc.contains("MinLen=1"));
        assertTrue(desc.contains("MaxLen=3"));
        assertTrue(desc.contains("Pattern"));
        assertTrue(desc.contains("Enum"));

        p.isPii = null;
        assertFalse(p.isSensitive());
        p.isPii = true;
        assertTrue(p.isSensitive());

        p.deletedAt = LocalDateTime.now();
        assertFalse(p.isActive());
    }

    @Test
    @DisplayName("已覆盖实体的 @PrePersist/@PreUpdate 生命周期回调补齐")
    void lifecycleCallbacksForCoveredEntities() {
        FeatureFlagEntity flag = new FeatureFlagEntity();
        assertNull(flag.createdAt);
        flag.onCreate();
        assertNotNull(flag.createdAt);
        assertEquals(FeatureFlagEntity.FlagStatus.DISABLED, flag.flagStatus);
        flag.onUpdate();
        assertNotNull(flag.updatedAt);

        HealthCheckEntity check = new HealthCheckEntity();
        check.onCreate();
        assertNotNull(check.createdAt);
        check.onUpdate();
        assertNotNull(check.updatedAt);

        MLModelEntity model = new MLModelEntity();
        model.modelStatus = null;
        model.onCreate();
        assertNotNull(model.createdAt);
        assertEquals(MLModelEntity.ModelStatus.DRAFT, model.modelStatus);
        model.onUpdate();
        assertNotNull(model.updatedAt);

        PipelineJobEntity job = new PipelineJobEntity();
        job.jobStatus = null;
        job.onCreate();
        assertNotNull(job.createdAt);
        assertEquals(PipelineJobEntity.JobStatus.PENDING, job.jobStatus);
        job.onUpdate();
        assertNotNull(job.updatedAt);

        QuotaEntity quota = new QuotaEntity();
        quota.onCreate();
        assertNotNull(quota.createdAt);
        quota.onUpdate();
        assertNotNull(quota.updatedAt);

        SDKKeyEntity key = new SDKKeyEntity();
        key.keyStatus = null;
        key.deliveryMode = null;
        key.onCreate();
        assertNotNull(key.createdAt);
        assertEquals(SDKKeyEntity.KeyStatus.ACTIVE, key.keyStatus);
        assertEquals(SDKKeyEntity.DeliveryMode.REALTIME, key.deliveryMode);
        key.onUpdate();
        assertNotNull(key.updatedAt);

        SecuritySessionEntity session = new SecuritySessionEntity();
        session.sessionStatus = null;
        session.onCreate();
        assertNotNull(session.createdAt);
        assertNotNull(session.loginAt);
        assertNotNull(session.lastActivityAt);
        assertEquals(SecuritySessionEntity.SessionStatus.ACTIVE, session.sessionStatus);
        session.onUpdate();
        assertNotNull(session.updatedAt);

        TelemetryConfigEntity config = new TelemetryConfigEntity();
        config.configStatus = null;
        config.onCreate();
        assertNotNull(config.createdAt);
        assertEquals(TelemetryConfigEntity.ConfigStatus.DRAFT, config.configStatus);
        config.onUpdate();
        assertNotNull(config.updatedAt);
    }

    @Test
    @DisplayName("GameDTO 补余：updateEntity 全字段补丁与 coppa 合规分支")
    void gameDtoRemainingBranches() {
        GameDTO patch = new GameDTO();
        LocalDateTime t = LocalDateTime.of(2026, 9, 9, 10, 0);
        patch.name = "新游戏名";
        patch.displayName = "星海物语";
        patch.description = "太空题材新游";
        patch.genre = GameEntity.GameGenre.STRATEGY;
        patch.platforms = Set.of(GameEntity.GamePlatform.PC);
        patch.status = GameEntity.GameStatus.LIVE;
        patch.currentVersion = "1.2.3";
        patch.minSupportedVersion = "1.0.0";
        patch.releaseDate = t;
        patch.appStoreUrl = "https://app.example.com";
        patch.googlePlayUrl = "https://play.example.com";
        patch.steamUrl = "https://steam.example.com";
        patch.defaultCurrency = "CNY";
        patch.defaultTimezone = "Asia/Shanghai";
        patch.virtualCurrencies = "[\"coin\"]";
        patch.maxLevel = 120;
        patch.hasMultiplayer = true;
        patch.hasGuilds = true;
        patch.hasPvp = true;
        patch.dataRetentionDays = 365;
        patch.enableRealTimeAnalytics = false;
        patch.enableCrashReporting = false;
        patch.sampleRate = 0.5;
        patch.piiDetectionEnabled = true;
        patch.gdprCompliance = true;
        patch.coppaCompliance = true;

        GameEntity target = new GameEntity();
        target.name = "旧名";
        patch.updateEntity(target);
        assertEquals("新游戏名", target.name);
        assertEquals("星海物语", target.displayName);
        assertEquals("太空题材新游", target.description);
        assertEquals(GameEntity.GameGenre.STRATEGY, target.genre);
        assertEquals(Set.of(GameEntity.GamePlatform.PC), target.platforms);
        assertEquals(GameEntity.GameStatus.LIVE, target.status);
        assertEquals("1.2.3", target.currentVersion);
        assertEquals("1.0.0", target.minSupportedVersion);
        assertEquals(t, target.releaseDate);
        assertEquals("https://app.example.com", target.appStoreUrl);
        assertEquals("https://play.example.com", target.googlePlayUrl);
        assertEquals("https://steam.example.com", target.steamUrl);
        assertEquals("CNY", target.defaultCurrency);
        assertEquals("Asia/Shanghai", target.defaultTimezone);
        assertEquals("[\"coin\"]", target.virtualCurrencies);
        assertEquals(120, target.maxLevel);
        assertTrue(target.hasMultiplayer);
        assertTrue(target.hasGuilds);
        assertTrue(target.hasPvp);
        assertEquals(365, target.dataRetentionDays);
        assertFalse(target.enableRealTimeAnalytics);
        assertFalse(target.enableCrashReporting);
        assertEquals(0.5, target.sampleRate, 1e-9);
        assertTrue(target.piiDetectionEnabled);
        assertTrue(target.gdprCompliance);
        assertTrue(target.coppaCompliance);

        GameDTO coppaOnly = new GameDTO();
        coppaOnly.coppaCompliance = true;
        assertTrue(coppaOnly.isCompliant());
        GameDTO gdprOnly = new GameDTO();
        gdprOnly.gdprCompliance = false;
        gdprOnly.coppaCompliance = false;
        assertFalse(gdprOnly.isCompliant());
    }

    @Test
    @DisplayName("EnvironmentDTO 补余：inferType 补充分支与 updateEntity 全字段补丁")
    void environmentDtoRemainingBranches() {
        EnvironmentDTO prod = new EnvironmentDTO();
        prod.name = "prod";
        assertEquals(GameEnvironmentEntity.EnvironmentType.PRODUCTION, prod.toEntity().type);
        EnvironmentDTO stage = new EnvironmentDTO();
        stage.name = "stage";
        assertEquals(GameEnvironmentEntity.EnvironmentType.STAGING, stage.toEntity().type);
        EnvironmentDTO test = new EnvironmentDTO();
        test.name = "test";
        assertEquals(GameEnvironmentEntity.EnvironmentType.TESTING, test.toEntity().type);

        EnvironmentDTO patch = new EnvironmentDTO();
        patch.displayName = "新生产环境";
        patch.description = "全字段更新";
        patch.type = GameEnvironmentEntity.EnvironmentType.PRODUCTION;
        patch.status = GameEnvironmentEntity.EnvironmentStatus.INACTIVE;
        patch.storageProfileId = "sp-9";
        patch.apiEndpoint = "https://api2.example.com";
        patch.dataNamespace = "ns-9";
        patch.kafkaTopicPrefix = "kp-9";
        patch.databaseName = "db_g1_v2";
        patch.dataRetentionDays = 60;
        patch.maxEventsPerDay = 200000L;
        patch.enableDebugMode = true;
        patch.enableSampling = false;
        patch.sampleRate = 0.25;
        patch.enableRealTime = false;
        patch.requireHttps = false;
        patch.allowedOrigins = "https://console.example.com";
        patch.ipWhitelist = "10.0.0.0/8";
        patch.enableAlerts = false;
        patch.alertEmail = "ops@example.com";
        patch.errorThreshold = 0.2;
        patch.schemaVersion = "v2";
        patch.configVersion = "cfg-2";

        GameEnvironmentEntity target = new GameEnvironmentEntity();
        target.displayName = "旧显示名";
        patch.updateEntity(target);
        assertEquals("新生产环境", target.displayName);
        assertEquals("全字段更新", target.description);
        assertEquals(GameEnvironmentEntity.EnvironmentType.PRODUCTION, target.type);
        assertEquals(GameEnvironmentEntity.EnvironmentStatus.INACTIVE, target.status);
        assertEquals("sp-9", target.storageProfileId);
        assertEquals("https://api2.example.com", target.apiEndpoint);
        assertEquals("ns-9", target.dataNamespace);
        assertEquals("kp-9", target.kafkaTopicPrefix);
        assertEquals("db_g1_v2", target.databaseName);
        assertEquals(60, target.dataRetentionDays);
        assertEquals(200000L, target.maxEventsPerDay);
        assertTrue(target.enableDebugMode);
        assertFalse(target.enableSampling);
        assertEquals(0.25, target.sampleRate, 1e-9);
        assertFalse(target.enableRealTime);
        assertFalse(target.requireHttps);
        assertEquals("https://console.example.com", target.allowedOrigins);
        assertEquals("10.0.0.0/8", target.ipWhitelist);
        assertFalse(target.enableAlerts);
        assertEquals("ops@example.com", target.alertEmail);
        assertEquals(0.2, target.errorThreshold, 1e-9);
        assertEquals("v2", target.schemaVersion);
        assertEquals("cfg-2", target.configVersion);
    }
}
