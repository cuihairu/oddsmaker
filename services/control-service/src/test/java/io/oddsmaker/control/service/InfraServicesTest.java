package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.DataQualityRuleRepo;
import io.oddsmaker.control.jpa.FlinkJobRepo;
import io.oddsmaker.control.jpa.HealthCheckEntity;
import io.oddsmaker.control.jpa.HealthCheckRepo;
import io.oddsmaker.control.jpa.HealthMetricRepo;
import io.oddsmaker.control.jpa.IntegrationLogRepo;
import io.oddsmaker.control.jpa.IntegrationRepo;
import io.oddsmaker.control.jpa.MFAConfigRepo;
import io.oddsmaker.control.jpa.QuotaEntity;
import io.oddsmaker.control.jpa.PipelineJobRepo;
import io.oddsmaker.control.jpa.PipelineRepo;
import io.oddsmaker.control.jpa.QuotaRepo;
import io.oddsmaker.control.jpa.RateLimitRepo;
import io.oddsmaker.control.jpa.RiskRuleRepo;
import io.oddsmaker.control.jpa.SSOConfigRepo;
import io.oddsmaker.control.jpa.SecurityPolicyRepo;
import io.oddsmaker.control.jpa.SecuritySessionRepo;
import io.oddsmaker.control.jpa.SystemAlertEntity;
import io.oddsmaker.control.jpa.SystemAlertRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 基础设施类 Service 测试：Flink 作业/集成/健康监控/限流/管线/安全（空数据源下的主路径）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("基础设施类 Service 测试")
class InfraServicesTest {

    // ===== Flink 作业 =====

    @Mock
    private FlinkJobRepo flinkJobRepo;

    @Mock
    private io.oddsmaker.control.service.AuditLogService auditLogService;

    @Mock
    private RiskRuleRepo riskRuleRepo;

    @Mock
    private io.oddsmaker.control.service.FlinkRestClient flinkRestClient;

    @Spy
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    @InjectMocks
    private FlinkJobService flinkJobService;

    @Test
    @DisplayName("Flink 作业：查询/运行中/规则关联主路径")
    void flinkJobMainPaths() {
        io.oddsmaker.control.jpa.FlinkJobEntity running = new io.oddsmaker.control.jpa.FlinkJobEntity();
        running.status = io.oddsmaker.control.jpa.FlinkJobEntity.JobStatus.RUNNING;
        // 实体字段声明带 0L 初始化器，显式置 null 才能踩到统计兜底分支
        running.totalEventsProcessed = null;
        running.totalRiskCasesCreated = null;
        lenient().when(flinkJobRepo.findByGameId("g")).thenReturn(List.of(running));
        lenient().when(flinkJobRepo.findRunningJobs("g")).thenReturn(List.of(running));
        lenient().when(riskRuleRepo.findById(anyString())).thenReturn(java.util.Optional.empty());

        flinkJobService.getGameJobs("g");
        flinkJobService.getRunningJobs("g");
        // 计数器全 null：统计兜底 0、风险率 0.0
        Map<String, Object> stats = flinkJobService.getJobStats("g");
        assertEquals(1, stats.get("totalJobs"));
        assertEquals(1, stats.get("runningJobs"));
        assertEquals(0L, stats.get("stoppedJobs"));
        assertEquals(0L, stats.get("failedJobs"));
        assertEquals(0L, stats.get("totalEventsProcessed"));
        assertEquals(0.0, stats.get("overallRiskCaseRate"));
    }

    @Test
    @DisplayName("Flink 作业：配置序列化失败抛出；部署时规则ID解析失败不阻塞部署")
    void flinkJobSerializationAndDeployResilience() throws Exception {
        // createJob：jobConfig 不可序列化 → RuntimeException
        java.util.Map<String, Object> bad = new java.util.HashMap<>();
        bad.put("bad", new Object());
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
            () -> flinkJobService.createJob("g", "env1", "j1", "Job1", "d", null,
                bad, null, null, null, 1, "op"));

        // deployJob：ruleIds 非法 JSON → buildProgramArgs 内 catch，部署仍成功（REST 走 mock）
        java.nio.file.Path jarDir = java.nio.file.Files.createTempDirectory("flink-jars-test");
        java.nio.file.Files.writeString(jarDir.resolve("risk-job-0.1.0-all.jar"), "fake-jar");
        java.lang.reflect.Field jarDirField = FlinkJobService.class.getDeclaredField("jarDir");
        jarDirField.setAccessible(true);
        jarDirField.set(flinkJobService, jarDir.toString());
        when(flinkRestClient.uploadJar(any(java.nio.file.Path.class))).thenReturn("jar_1");
        when(flinkRestClient.launch(anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt(), anyString()))
            .thenReturn("job-1");
        io.oddsmaker.control.jpa.FlinkJobEntity job = new io.oddsmaker.control.jpa.FlinkJobEntity();
        job.id = "fj1";
        job.gameId = "g";
        job.name = "j1";
        job.jobType = io.oddsmaker.control.jpa.FlinkJobEntity.JobType.RISK_EVALUATION.name();
        job.status = io.oddsmaker.control.jpa.FlinkJobEntity.JobStatus.DRAFT;
        job.ruleIds = "not-json";
        when(flinkJobRepo.findById("fj1")).thenReturn(java.util.Optional.of(job));
        when(flinkJobRepo.save(any(io.oddsmaker.control.jpa.FlinkJobEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        io.oddsmaker.control.jpa.FlinkJobEntity deployed = flinkJobService.deployJob("fj1", "op");
        org.junit.jupiter.api.Assertions.assertEquals(
            io.oddsmaker.control.jpa.FlinkJobEntity.JobStatus.RUNNING, deployed.status);
    }

    // ===== 集成 =====

    @Mock
    private IntegrationRepo integrationRepo;

    @Mock
    private IntegrationLogRepo integrationLogRepo;

    @InjectMocks
    private IntegrationService integrationService;

    @Test
    @DisplayName("集成：查询/统计/重试清理主路径")
    void integrationMainPaths() {
        lenient().when(integrationRepo.findByGameId("g")).thenReturn(List.of());
        lenient().when(integrationLogRepo.findByIntegrationId("i1")).thenReturn(List.of());
        lenient().when(integrationLogRepo.countCallsSince(eq("i1"), any())).thenReturn(0L);
        lenient().when(integrationLogRepo.countSuccessCallsSince(eq("i1"), any())).thenReturn(0L);
        lenient().when(integrationLogRepo.countFailedCallsSince(eq("i1"), any())).thenReturn(0L);
        lenient().when(integrationLogRepo.averageDurationSince(eq("i1"), any())).thenReturn(0L);

        integrationService.getIntegrations("g");
        integrationService.getIntegrationLogs("i1");
        assertNotNull(integrationService.getIntegrationStats("g"));
        assertNotNull(integrationService.getCallStats("i1", null));
        integrationService.retryFailedIntegrations();
        integrationService.cleanupExpiredLogs();
    }

    // ===== 健康监控 =====

    @Mock
    private HealthCheckRepo healthCheckRepo;

    @Mock
    private HealthMetricRepo healthMetricRepo;

    @Mock
    private SystemAlertRepo systemAlertRepo;

    @Mock
    private WebhookService webhookService;

    @InjectMocks
    private HealthMonitorService healthMonitorService;

    @Test
    @DisplayName("健康监控：检查/指标/告警查询主路径")
    void healthMainPaths() {
        lenient().when(healthCheckRepo.findAll()).thenReturn(List.of());
        lenient().when(healthCheckRepo.findEnabled()).thenReturn(List.of());
        lenient().when(healthMetricRepo.findRecentByType(any(), any())).thenReturn(List.of());
        lenient().when(systemAlertRepo.findActive()).thenReturn(List.of());
        lenient().when(systemAlertRepo.countByStatus(any())).thenReturn(0L);
        lenient().when(systemAlertRepo.countActiveBySeverity(any())).thenReturn(0L);

        healthMonitorService.getHealthChecks();
        healthMonitorService.getRecentMetrics(io.oddsmaker.control.jpa.HealthMetricEntity.MetricType.CPU_USAGE,
            java.time.LocalDateTime.now().minusHours(1));
        healthMonitorService.getActiveAlerts();
        assertNotNull(healthMonitorService.getAlertStats());
        assertNotNull(healthMonitorService.getSystemHealth());
        healthMonitorService.cleanupExpiredMetrics();
        healthMonitorService.cleanupClosedAlerts();
    }

    @Test
    @DisplayName("健康监控：DISK 指标超阈值触发挥盘告警；各定时任务异常被顶层 catch 吞掉")
    void healthResilienceAndDiskAlert() {
        // 模拟调度默认被 oddsmaker.health.* 门关闭，此处显式开启以验证调度方法行为
        org.springframework.test.util.ReflectionTestUtils.setField(
            healthMonitorService, "simulatedChecksEnabled", true);
        org.springframework.test.util.ReflectionTestUtils.setField(
            healthMonitorService, "simulatedMetricsEnabled", true);
        // DISK_USAGE 超过 critical 阈值 → isCritical → 创建 HIGH_DISK 告警
        when(healthMetricRepo.save(any(io.oddsmaker.control.jpa.HealthMetricEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        io.oddsmaker.control.jpa.HealthMetricEntity disk = healthMonitorService.collectMetric(
            io.oddsmaker.control.jpa.HealthMetricEntity.MetricType.DISK_USAGE, "system", 95.0);
        org.junit.jupiter.api.Assertions.assertTrue(disk.isAnomaly);
        verify(systemAlertRepo).save(any(io.oddsmaker.control.jpa.SystemAlertEntity.class));

        // performScheduledHealthChecks：repo 抛异常被顶层 catch 吞掉
        when(healthCheckRepo.findDueChecks(any(java.time.LocalDateTime.class)))
            .thenThrow(new IllegalStateException("db down"));
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> healthMonitorService.performScheduledHealthChecks());

        // collectSystemMetrics：save 抛异常被顶层 catch 吞掉
        when(healthMetricRepo.save(any(io.oddsmaker.control.jpa.HealthMetricEntity.class)))
            .thenThrow(new IllegalStateException("db down"));
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> healthMonitorService.collectSystemMetrics());

        // cleanupExpiredMetrics / cleanupClosedAlerts：repo 抛异常被吞
        when(healthMetricRepo.deleteExpired(any(java.time.LocalDateTime.class)))
            .thenThrow(new IllegalStateException("db down"));
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> healthMonitorService.cleanupExpiredMetrics());
        when(systemAlertRepo.deleteClosedBefore(any(java.time.LocalDateTime.class)))
            .thenThrow(new IllegalStateException("db down"));
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> healthMonitorService.cleanupClosedAlerts());
    }

    @Test
    @DisplayName("Webhook 接线：告警升级派发 alert_escalation；gameId 空只升级不派发；派发异常被吞")
    @SuppressWarnings("unchecked")
    void alertEscalationDispatchesWebhook() {
        lenient().when(systemAlertRepo.save(any(SystemAlertEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        SystemAlertEntity biz = new SystemAlertEntity();
        biz.id = "sa_1";
        biz.title = "支付成功率骤降";
        biz.severity = SystemAlertEntity.Severity.CRITICAL;
        biz.gameId = "g";
        biz.source = "metric-monitor";
        biz.affectedResource = "payment-api";
        SystemAlertEntity platform = new SystemAlertEntity();
        platform.id = "sa_2";
        platform.title = "平台级告警";
        when(systemAlertRepo.findNeedingEscalation()).thenReturn(List.of(biz, platform));

        healthMonitorService.checkAlertEscalations();

        // 平台级（gameId 空）只升级不派发，不伪造路由
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(webhookService, times(1)).sendCustomWebhook(anyString(), anyString(), payload.capture());
        assertEquals("sa_1", payload.getValue().get("alert_id"));
        assertEquals("CRITICAL", payload.getValue().get("severity"));
        assertEquals("g", payload.getValue().get("game_id"));
        assertEquals("metric-monitor", payload.getValue().get("source"));
        assertEquals(Integer.valueOf(1), biz.escalationLevel);
        assertEquals(Integer.valueOf(1), platform.escalationLevel);

        // 派发异常被吞：升级照常完成，不向外抛
        doThrow(new RuntimeException("wh down")).when(webhookService)
            .sendCustomWebhook(anyString(), anyString(), anyMap());
        when(systemAlertRepo.findNeedingEscalation()).thenReturn(List.of(biz));
        assertDoesNotThrow(() -> healthMonitorService.checkAlertEscalations());
        assertEquals(Integer.valueOf(2), biz.escalationLevel);
    }

    // ===== 限流 =====

    @Mock
    private RateLimitRepo rateLimitRepo;

    @Mock
    private QuotaRepo quotaRepo;

    @InjectMocks
    private RateLimitService rateLimitService;

    @Test
    @DisplayName("限流：规则/配额查询与统计主路径")
    void rateLimitMainPaths() {
        lenient().when(rateLimitRepo.findByGameId("g")).thenReturn(List.of());
        lenient().when(quotaRepo.findByGameId("g")).thenReturn(List.of());
        lenient().when(quotaRepo.findAll()).thenReturn(List.of());

        rateLimitService.getRateLimits("g");
        assertNotNull(rateLimitService.getRateLimitStats("g"));
        assertNotNull(rateLimitService.getQuotaStats("g"));
    }

    @Test
    @DisplayName("Webhook 接线：配额超限派发 quota_warning/quota_alert 各一次；派发异常被吞")
    @SuppressWarnings("unchecked")
    void quotaAlertsDispatchWebhook() {
        QuotaEntity quota = new QuotaEntity();
        quota.id = "qt_1";
        quota.gameId = "g";
        quota.resourceType = QuotaEntity.ResourceType.API_CALLS_PER_DAY;
        quota.quotaLimit = 100L;
        quota.currentUsage = 96L;
        when(quotaRepo.findByGameAndResourceType("g", QuotaEntity.ResourceType.API_CALLS_PER_DAY))
            .thenReturn(Optional.of(quota));
        // 原子自增后重读形态：96+1=97 越过 warning(80%) 与 alert(95%) 两条阈值
        QuotaEntity after = new QuotaEntity();
        after.id = "qt_1";
        after.gameId = "g";
        after.resourceType = QuotaEntity.ResourceType.API_CALLS_PER_DAY;
        after.quotaLimit = 100L;
        after.currentUsage = 97L;
        after.warningThreshold = 80.0;
        after.alertThreshold = 95.0;
        when(quotaRepo.findById("qt_1")).thenReturn(Optional.of(after));

        rateLimitService.updateQuotaUsage("g", null, QuotaEntity.ResourceType.API_CALLS_PER_DAY, 1L);

        ArgumentCaptor<Map<String, Object>> payloads = ArgumentCaptor.forClass(Map.class);
        verify(webhookService, times(2)).sendCustomWebhook(eq("g"), anyString(), payloads.capture());
        assertEquals("quota_warning", payloads.getAllValues().get(0).get("event_type"));
        assertEquals("quota_alert", payloads.getAllValues().get(1).get("event_type"));
        assertEquals("API_CALLS_PER_DAY", payloads.getAllValues().get(0).get("resource_type"));
        assertEquals(80.0, payloads.getAllValues().get(0).get("warning_threshold"));
        verify(quotaRepo).incrementUsageAtomic("qt_1", 1L);
        verify(quotaRepo).markWarningSentAtomic("qt_1");
        verify(quotaRepo).markAlertSentAtomic("qt_1");

        // 派发异常被吞：新配额同样越限，更新主流程不炸
        QuotaEntity quota2 = new QuotaEntity();
        quota2.id = "qt_2";
        quota2.gameId = "g";
        quota2.resourceType = QuotaEntity.ResourceType.API_CALLS_PER_DAY;
        quota2.quotaLimit = 100L;
        quota2.currentUsage = 96L;
        when(quotaRepo.findByGameAndResourceType("g", QuotaEntity.ResourceType.API_CALLS_PER_DAY))
            .thenReturn(Optional.of(quota2));
        QuotaEntity after2 = new QuotaEntity();
        after2.id = "qt_2";
        after2.gameId = "g";
        after2.resourceType = QuotaEntity.ResourceType.API_CALLS_PER_DAY;
        after2.quotaLimit = 100L;
        after2.currentUsage = 97L;
        after2.warningThreshold = 80.0;
        after2.alertThreshold = 95.0;
        when(quotaRepo.findById("qt_2")).thenReturn(Optional.of(after2));
        doThrow(new RuntimeException("wh down")).when(webhookService)
            .sendCustomWebhook(anyString(), anyString(), anyMap());
        assertDoesNotThrow(() ->
            rateLimitService.updateQuotaUsage("g", null, QuotaEntity.ResourceType.API_CALLS_PER_DAY, 1L));
        verify(quotaRepo).markWarningSentAtomic("qt_2");
    }

    // ===== 数据管线 =====

    @Mock
    private PipelineRepo pipelineRepo;

    @Mock
    private PipelineJobRepo pipelineJobRepo;

    @Mock
    private DataQualityRuleRepo dataQualityRuleRepo;

    @InjectMocks
    private PipelineService pipelineService;

    @Test
    @DisplayName("管线：查询/统计/清理主路径")
    void pipelineMainPaths() {
        lenient().when(pipelineRepo.findByGameId("g")).thenReturn(List.of());
        lenient().when(pipelineJobRepo.findByPipelineId("p1")).thenReturn(List.of());
        lenient().when(dataQualityRuleRepo.findByGameId("g")).thenReturn(List.of());
        lenient().when(dataQualityRuleRepo.findByPipelineId("p1")).thenReturn(List.of());

        pipelineService.getPipelines("g");
        pipelineService.getPipelineJobs("p1");
        io.oddsmaker.control.jpa.PipelineEntity pipeline = new io.oddsmaker.control.jpa.PipelineEntity();
        pipeline.id = "p1";
        pipeline.gameId = "g";
        pipeline.pipelineStatus = io.oddsmaker.control.jpa.PipelineEntity.PipelineStatus.ACTIVE;
        pipeline.runCount = 0;
        pipeline.successCount = 0;
        pipeline.failureCount = 0;
        pipeline.lastRunAt = java.time.LocalDateTime.now();
        pipeline.lastSuccessAt = java.time.LocalDateTime.now();
        lenient().when(pipelineRepo.findById("p1")).thenReturn(java.util.Optional.of(pipeline));
        assertNotNull(pipelineService.getPipelineStats("p1"));
        pipelineService.getQualityRules("g");
        pipelineService.getPipelineQualityRules("p1");
        pipelineService.cleanupOldJobs();
    }

    @Test
    @DisplayName("管线：配置序列化失败吞掉、执行遇 stopOnFailure 质量失败、清理异常吞掉")
    void pipelineResilienceBranches() throws Exception {
        when(pipelineRepo.save(any(io.oddsmaker.control.jpa.PipelineEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        when(dataQualityRuleRepo.save(any(io.oddsmaker.control.jpa.DataQualityRuleEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        // 不可 JSON 序列化的对象 → 序列化 catch 吞掉，创建仍成功
        java.util.Map<String, Object> unserializable = new java.util.HashMap<>();
        unserializable.put("bad", new Object());
        assertNotNull(pipelineService.createPipeline("g", "prod", "p",
            io.oddsmaker.control.jpa.PipelineEntity.PipelineType.BATCH, "d",
            unserializable, null, null, "op"));
        assertNotNull(pipelineService.createQualityRule("g", "p1", "r",
            io.oddsmaker.control.jpa.DataQualityRuleEntity.RuleType.COMPLETENESS,
            io.oddsmaker.control.jpa.DataQualityRuleEntity.Severity.ERROR,
            "t", "c", unserializable, "op"));

        // cleanupOldJobs：repo 抛异常被 catch
        when(pipelineJobRepo.deleteCompletedBefore(any(java.time.LocalDateTime.class)))
            .thenThrow(new IllegalStateException("ch down"));
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> pipelineService.cleanupOldJobs());

        // 执行：质量规则未通过且 stopOnFailure → job 记录质量失败
        io.oddsmaker.control.jpa.PipelineEntity active = new io.oddsmaker.control.jpa.PipelineEntity();
        active.id = "p_run";
        active.gameId = "g";
        active.pipelineStatus = io.oddsmaker.control.jpa.PipelineEntity.PipelineStatus.ACTIVE;
        when(pipelineRepo.findById("p_run")).thenReturn(java.util.Optional.of(active));
        when(pipelineJobRepo.save(any(io.oddsmaker.control.jpa.PipelineJobEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        io.oddsmaker.control.jpa.DataQualityRuleEntity failing =
            new io.oddsmaker.control.jpa.DataQualityRuleEntity();
        failing.ruleName = "not_null_check";
        failing.ruleStatus = io.oddsmaker.control.jpa.DataQualityRuleEntity.RuleStatus.ACTIVE;
        failing.enabled = true;
        failing.actionOnFailure = "stop";
        when(dataQualityRuleRepo.findByPipelineId("p_run")).thenReturn(List.of(failing));

        io.oddsmaker.control.jpa.PipelineJobEntity job = pipelineService.executePipeline("p_run", "op");
        assertNotNull(job);
    }

    // ===== 安全（MFA/SSO/会话） =====

    @Mock
    private MFAConfigRepo mfaConfigRepo;

    @Mock
    private SSOConfigRepo ssoConfigRepo;

    @Mock
    private SecuritySessionRepo securitySessionRepo;

    @Mock
    private SecurityPolicyRepo securityPolicyRepo;

    @InjectMocks
    private SecurityService securityService;

    @Test
    @DisplayName("安全：MFA/SSO/会话查询主路径")
    void securityMainPaths() {
        lenient().when(mfaConfigRepo.findByUserId("u1")).thenReturn(List.of());
        lenient().when(ssoConfigRepo.findActive()).thenReturn(List.of());
        lenient().when(securitySessionRepo.findByToken("token")).thenReturn(java.util.Optional.empty());
        lenient().when(securityPolicyRepo.findPasswordPolicyForGame("g")).thenReturn(List.of());
        lenient().when(securityPolicyRepo.findSessionPolicyForGame("g")).thenReturn(List.of());
        lenient().when(securityPolicyRepo.findMFAPolicyForGame("g")).thenReturn(List.of());

        securityService.getUserMFAConfigs("u1");
        securityService.getActiveSSOConfigs();
        assertFalse(securityService.isUserMFAEnabled("u1"));
        securityService.getPasswordPolicies("g");
        securityService.getSessionPolicies("g");
        securityService.getMFAPolicies("g");
                securityService.cleanupExpiredSessions();
    }

    @Test
    @DisplayName("安全：过期会话清理与旧会话删除任务异常被顶层 catch 吞掉")
    void securityScheduledResilience() {
        when(securitySessionRepo.findExpired(any(java.time.LocalDateTime.class)))
            .thenThrow(new IllegalStateException("db down"));
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> securityService.cleanupExpiredSessions());

        when(securitySessionRepo.deleteExpired(any(java.time.LocalDateTime.class)))
            .thenThrow(new IllegalStateException("db down"));
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> securityService.deleteOldSessions());
    }


    @Test
    @DisplayName("分支对侧：degraded 检查致 DEGRADED、调度清扫开关与空/非空、空白 gameId 告警只升级、无待升级")
    void healthMonitorBranchSides() {
        // degraded > 0 且 slow 空（|| 第一条件 true 侧）
        HealthCheckEntity degradedCheck = new HealthCheckEntity();
        degradedCheck.checkName = "cache";
        degradedCheck.healthStatus = HealthCheckEntity.HealthStatus.DEGRADED;
        HealthCheckEntity ok = new HealthCheckEntity();
        ok.checkName = "db";
        ok.markAsHealthy("fine");
        lenient().when(healthCheckRepo.findEnabled()).thenReturn(java.util.List.of(ok, degradedCheck));
        lenient().when(healthCheckRepo.findUnhealthy()).thenReturn(java.util.List.of());
        lenient().when(healthCheckRepo.findSlowResponses()).thenReturn(java.util.List.of());
        Map<String, Object> health = healthMonitorService.getSystemHealth();
        assertEquals(HealthCheckEntity.HealthStatus.DEGRADED, health.get("overallStatus"));

        // 调度健康检查：开关关闭直接返回；开启后空列表（false 侧）与非空（true 侧，内部异常被吞）
        org.springframework.test.util.ReflectionTestUtils.setField(healthMonitorService, "simulatedChecksEnabled", false);
        healthMonitorService.performScheduledHealthChecks();
        org.springframework.test.util.ReflectionTestUtils.setField(healthMonitorService, "simulatedChecksEnabled", true);
        lenient().when(healthCheckRepo.findDueChecks(any(java.time.LocalDateTime.class)))
            .thenReturn(java.util.List.of());
        healthMonitorService.performScheduledHealthChecks();
        HealthCheckEntity due = new HealthCheckEntity();
        due.checkName = "db";
        lenient().when(healthCheckRepo.findDueChecks(any(java.time.LocalDateTime.class)))
            .thenReturn(java.util.List.of(due));
        healthMonitorService.performScheduledHealthChecks();

        // 空白 gameId + severity null 的告警：只升级不派发（isBlank 侧 + severity null 三元）
        SystemAlertEntity blank = new SystemAlertEntity();
        blank.id = "sa_blank";
        blank.title = "平台告警";
        blank.gameId = "   ";
        blank.severity = null;
        lenient().when(systemAlertRepo.save(any(SystemAlertEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(systemAlertRepo.findNeedingEscalation()).thenReturn(java.util.List.of(blank));
        healthMonitorService.checkAlertEscalations();
        assertEquals(Integer.valueOf(1), blank.escalationLevel);
        verify(webhookService, org.mockito.Mockito.never()).sendCustomWebhook(anyString(), anyString(), anyMap());

        // 无待升级告警（!needingEscalation.isEmpty() false 侧）
        when(systemAlertRepo.findNeedingEscalation()).thenReturn(java.util.List.of());
        healthMonitorService.checkAlertEscalations();
    }
}
