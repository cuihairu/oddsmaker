package io.oddsmaker.control.jpa;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖率冲刺第二轮·基础设施/运维域（LAY-002 拆分）：维护窗口、告警、数据质量、报表、Flink 作业、健康指标、集成、管道、限流、SDK 版本、存储、系统配置、埋点计划、webhook。与 EntitiesSmokeCoverageTest（空构造冒烟）、EntitiesDeep* 拆分类（主要状态方法）互补。
 */
class EntitiesSweep2OpsTest {

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
        m.scheduledStart = now.minusMinutes(10);

        m.maintenanceStatus = MaintenanceWindowEntity.MaintenanceStatus.IN_PROGRESS;
        assertTrue(m.isInProgress());
        assertTrue(m.isActive());
        m.scheduledEnd = now.minusMinutes(1);
        assertTrue(m.isOverdue());
        m.scheduledEnd = null;
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
        m.complete("升级完成");
        assertTrue(m.isCompleted());
        assertNotNull(m.actualEnd);
        assertEquals("升级完成", m.completionNotes);
        assertEquals(100, m.progressPercent);
        assertTrue(m.isCompleted());

        MaintenanceWindowEntity c = new MaintenanceWindowEntity();
        c.title = "紧急扩容";
        c.createdBy = "运维-李四";
        c.cancel("变更取消");
        assertEquals(MaintenanceWindowEntity.MaintenanceStatus.CANCELLED, c.maintenanceStatus);
        assertEquals("变更取消", c.completionNotes);

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

        a.severity = SystemAlertEntity.Severity.CRITICAL;
        assertTrue(a.isCritical());
        assertFalse(a.isWarning());
        a.escalate();
        assertEquals(1, a.escalationLevel);
        assertNotNull(a.escalatedAt);
        a.escalationLevel = 0;
        a.alertStatus = SystemAlertEntity.AlertStatus.RESOLVED;
        assertTrue(a.isResolved());
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

        e.rowCount = null;
        e.executionTimeMs = null;
        assertTrue(e.getStatusDescription().contains("0 rows (0ms)"));
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

        l.callStatus = IntegrationLogEntity.CallStatus.RETRYING;
        l.retryAttempt = 1;
        assertTrue(l.isRetrying());
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
        r.status = ReportEntity.ReportStatus.ARCHIVED;
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

        assertTrue(r.getVisualizationConfig().containsKey("chartType"));
        assertEquals("table", r.getVisualizationConfig().get("chartType"));
        r.visualization = "{\"chartType\":\"pie\"}";
        assertEquals("pie", r.getVisualizationConfig().get("chartType"));
        r.visualization = "不是合法JSON";
        assertTrue(r.getVisualizationConfig().isEmpty());
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
}
