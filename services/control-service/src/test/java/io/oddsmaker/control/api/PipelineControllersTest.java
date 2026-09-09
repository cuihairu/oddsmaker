package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.AuditLogRepo;
import io.oddsmaker.control.service.DeveloperPortalService;
import io.oddsmaker.control.service.ExportService;
import io.oddsmaker.control.service.FlinkJobService;
import io.oddsmaker.control.service.HealthMonitorService;
import io.oddsmaker.control.service.IntegrationService;
import io.oddsmaker.control.service.MLModelService;
import io.oddsmaker.control.service.PerformanceMonitorService;
import io.oddsmaker.control.service.PipelineService;
import io.oddsmaker.control.service.RateLimitService;
import io.oddsmaker.control.service.ReportService;
import io.oddsmaker.control.service.TrackingPlanService;
import io.oddsmaker.control.service.WebhookService;
import io.oddsmaker.control.security.AccessGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;

/**
 * 管线/开发/集成域 Controller 测试：开发门户/ML 模型/导出/报表/管线/Flink 作业/集成/Webhook/限流/健康/性能/埋点方案/审计。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("管线与集成域 Controller 测试")
class PipelineControllersTest {

    @Mock
    private AccessGuard accessGuard;

    // ===== 开发者门户 =====

    @Mock
    private DeveloperPortalService developerPortalService;

    @InjectMocks
    private DeveloperController developerController;

    @Test
    @DisplayName("开发者门户：SDK 密钥/版本/遥测配置端点委托")
    void developerEndpoints() {
        assertEquals(200, developerController.createSDKKey(new DeveloperController.CreateSDKKeyRequest()).getStatusCode().value());
        assertEquals(200, developerController.getSDKKey("k1").getStatusCode().value());
        assertEquals(200, developerController.getGameSDKKeys("g", null).getStatusCode().value());
        assertEquals(200, developerController.updateSDKKey("k1", new DeveloperController.UpdateSDKKeyRequest()).getStatusCode().value());
        assertEquals(200, developerController.suspendSDKKey("k1", new DeveloperController.SuspendRequest()).getStatusCode().value());
        assertEquals(200, developerController.activateSDKKey("k1", new DeveloperController.ActivateRequest()).getStatusCode().value());
        assertEquals(200, developerController.revokeSDKKey("k1", new DeveloperController.RevokeRequest()).getStatusCode().value());
        assertEquals(200, developerController.deleteSDKKey("k1", new DeveloperController.DeleteRequest()).getStatusCode().value());
        assertEquals(200, developerController.validateSDKKey("pub", "g", "prod").getStatusCode().value());
        assertEquals(200, developerController.createSDKVersion(new DeveloperController.CreateSDKVersionRequest()).getStatusCode().value());
        assertEquals(200, developerController.getSDKVersion("v1").getStatusCode().value());
        assertEquals(200, developerController.getPlatformVersions(io.oddsmaker.control.jpa.SDKVersionEntity.SDKPlatform.WEB, null).getStatusCode().value());
        assertEquals(200, developerController.getLatestVersion(io.oddsmaker.control.jpa.SDKVersionEntity.SDKPlatform.WEB).getStatusCode().value());
        assertEquals(200, developerController.releaseVersion("v1", new DeveloperController.ReleaseVersionRequest()).getStatusCode().value());
        assertEquals(200, developerController.deprecateVersion("v1", new DeveloperController.DeprecateVersionRequest()).getStatusCode().value());
        assertEquals(200, developerController.retireVersion("v1", new DeveloperController.RetireRequest()).getStatusCode().value());
        assertEquals(200, developerController.recordDownload("v1").getStatusCode().value());
        assertEquals(200, developerController.createTelemetryConfig(new DeveloperController.CreateTelemetryConfigRequest()).getStatusCode().value());
        assertEquals(200, developerController.getTelemetryConfig("t1").getStatusCode().value());
        assertEquals(200, developerController.getGameTelemetryConfigs("g", null).getStatusCode().value());
        assertEquals(200, developerController.getEffectiveConfig("g", "prod", io.oddsmaker.control.jpa.TelemetryConfigEntity.ConfigType.BATCH).getStatusCode().value());
        assertEquals(200, developerController.updateTelemetryConfig("t1", new DeveloperController.UpdateTelemetryConfigRequest()).getStatusCode().value());
        assertEquals(200, developerController.activateTelemetryConfig("t1", new DeveloperController.ActivateRequest()).getStatusCode().value());
        assertEquals(200, developerController.deactivateTelemetryConfig("t1", new DeveloperController.DeactivateRequest()).getStatusCode().value());
        assertEquals(200, developerController.archiveTelemetryConfig("t1", new DeveloperController.ArchiveRequest()).getStatusCode().value());
        assertEquals(200, developerController.deleteTelemetryConfig("t1", new DeveloperController.DeleteRequest()).getStatusCode().value());
        assertEquals(200, developerController.getSDKStatistics().getStatusCode().value());
    }

    // ===== ML 模型 =====

    @Mock
    private MLModelService mlModelService;

    @InjectMocks
    private MLModelController mlModelController;

    @Test
    @DisplayName("ML 模型：模型/训练/部署/预测/统计端点委托")
    void mlModelEndpoints() {
        assertEquals(200, mlModelController.createModel(new MLModelController.CreateModelRequest()).getStatusCode().value());
        assertEquals(200, mlModelController.getModel("m1").getStatusCode().value());
        assertEquals(200, mlModelController.getGameModels("g").getStatusCode().value());
        assertEquals(200, mlModelController.getDeployedModels(null).getStatusCode().value());
        assertEquals(200, mlModelController.updateModel("m1", new MLModelController.UpdateModelRequest()).getStatusCode().value());
        assertEquals(200, mlModelController.archiveModel("m1", new MLModelController.ArchiveRequest()).getStatusCode().value());
        assertEquals(200, mlModelController.deleteModel("m1", new MLModelController.DeleteRequest()).getStatusCode().value());
        assertEquals(200, mlModelController.createTrainingJob("m1", new MLModelController.CreateTrainingRequest()).getStatusCode().value());
        assertEquals(200, mlModelController.startTraining("t1").getStatusCode().value());
        assertEquals(200, mlModelController.updateTrainingProgress("t1", new MLModelController.UpdateTrainingProgressRequest()).getStatusCode().value());
        assertEquals(200, mlModelController.completeTraining("t1", new MLModelController.CompleteTrainingRequest()).getStatusCode().value());
        assertEquals(200, mlModelController.failTraining("t1", new MLModelController.FailTrainingRequest()).getStatusCode().value());
        assertEquals(200, mlModelController.cancelTraining("t1", new MLModelController.CancelRequest()).getStatusCode().value());
        assertEquals(200, mlModelController.getTrainingJob("t1").getStatusCode().value());
        assertEquals(200, mlModelController.getTrainingHistory("m1").getStatusCode().value());
        assertEquals(200, mlModelController.deployModel("m1", new MLModelController.DeployModelRequest()).getStatusCode().value());
        assertEquals(200, mlModelController.configureAbTest("m1", new MLModelController.ConfigureAbTestRequest()).getStatusCode().value());
        assertEquals(200, mlModelController.stopAbTest("m1", new MLModelController.StopAbTestRequest()).getStatusCode().value());
        assertEquals(200, mlModelController.recordPrediction(new MLModelController.RecordPredictionRequest()).getStatusCode().value());
        assertEquals(200, mlModelController.completePrediction("p1", new MLModelController.CompletePredictionRequest()).getStatusCode().value());
        assertEquals(200, mlModelController.failPrediction("p1", new MLModelController.FailPredictionRequest()).getStatusCode().value());
        assertEquals(200, mlModelController.addPredictionFeedback("p1", new MLModelController.AddFeedbackRequest()).getStatusCode().value());
        assertEquals(200, mlModelController.getPrediction("p1").getStatusCode().value());
        assertEquals(200, mlModelController.getPredictionHistory("m1", 100).getStatusCode().value());
        assertEquals(200, mlModelController.getPredictionsByTimeRange("m1",
            java.time.LocalDateTime.now().minusDays(1), java.time.LocalDateTime.now()).getStatusCode().value());
        assertEquals(200, mlModelController.getModelStatistics("m1").getStatusCode().value());
        assertEquals(200, mlModelController.getGlobalStatistics().getStatusCode().value());
        assertEquals(200, mlModelController.detectModelDrift("m1", 6).getStatusCode().value());
    }

    // ===== 导出 =====

    @Mock
    private ExportService exportService;

    @InjectMocks
    private ExportController exportController;

    @Test
    @DisplayName("导出：8 个端点委托")
    void exportEndpoints() {
        assertEquals(200, exportController.createExportJob(new ExportController.ExportRequest()).getStatusCode().value());
        assertEquals(200, exportController.getExportJob("e1", "g").getStatusCode().value());
        assertEquals(200, exportController.getUserExports("u1", "g").getStatusCode().value());
        assertEquals(200, exportController.getGameExports("g").getStatusCode().value());
        assertEquals(200, exportController.processExportJob("e1", "g").getStatusCode().value());
        assertEquals(200, exportController.cancelExportJob("e1", "g", new ExportController.CancelRequest()).getStatusCode().value());
        assertEquals(200, exportController.getExportStats("g").getStatusCode().value());
        assertEquals(200, exportController.getUserExportStats("u1", "g").getStatusCode().value());
    }

    // ===== 报表 =====

    @Mock
    private ReportService reportService;

    @InjectMocks
    private ReportController reportController;

    @Test
    @DisplayName("报表：12 个端点委托")
    void reportEndpoints() {
        assertEquals(200, reportController.createReport(new ReportController.ReportRequest()).getStatusCode().value());
        assertEquals(200, reportController.getReport("r1", "g").getStatusCode().value());
        assertEquals(200, reportController.getGameReports("g").getStatusCode().value());
        assertEquals(200, reportController.getPublishedReports("g").getStatusCode().value());
        assertEquals(200, reportController.publishReport("r1", "g", new ReportController.PublishRequest()).getStatusCode().value());
        assertEquals(200, reportController.executeReport("r1", "g", new ReportController.ExecuteRequest()).getStatusCode().value());
        assertEquals(200, reportController.getReportExecutions("r1", "g").getStatusCode().value());
        assertEquals(200, reportController.getReportStats("r1", "g").getStatusCode().value());
        assertEquals(200, reportController.getGameReportOverview("g").getStatusCode().value());
        assertEquals(200, reportController.searchReports("g", "q").getStatusCode().value());
        assertEquals(200, reportController.getPopularReports("g").getStatusCode().value());
        assertEquals(200, reportController.getRecentlyRunReports("g").getStatusCode().value());
    }

    // ===== 数据管线 =====

    @Mock
    private PipelineService pipelineService;

    @InjectMocks
    private PipelineController pipelineController;

    @Test
    @DisplayName("管线：12 个端点委托")
    void pipelineEndpoints() {
        assertEquals(200, pipelineController.createPipeline(new PipelineController.PipelineRequest()).getStatusCode().value());
        assertEquals(200, pipelineController.getPipeline("p1").getStatusCode().value());
        assertEquals(200, pipelineController.getPipelines("g").getStatusCode().value());
        assertEquals(200, pipelineController.getPipelineJobs("p1").getStatusCode().value());
        assertEquals(200, pipelineController.getPipelineStats("p1").getStatusCode().value());
        assertEquals(200, pipelineController.executePipeline("p1", new PipelineController.ExecuteRequest()).getStatusCode().value());
        assertEquals(200, pipelineController.activatePipeline("p1").getStatusCode().value());
        assertEquals(200, pipelineController.pausePipeline("p1").getStatusCode().value());
        assertEquals(200, pipelineController.stopPipeline("p1").getStatusCode().value());
        assertEquals(200, pipelineController.createQualityRule(new PipelineController.QualityRuleRequest()).getStatusCode().value());
        assertEquals(200, pipelineController.getQualityRules("g").getStatusCode().value());
        assertEquals(200, pipelineController.getPipelineQualityRules("p1").getStatusCode().value());
    }

    // ===== Flink 作业 =====

    @Mock
    private FlinkJobService flinkJobService;

    @InjectMocks
    private FlinkJobController flinkJobController;

    @Test
    @DisplayName("Flink 作业：10 个端点委托")
    void flinkJobEndpoints() {
        assertEquals(200, flinkJobController.createJob(new FlinkJobController.FlinkJobRequest()).getStatusCode().value());
        assertEquals(200, flinkJobController.getJob("j1", "g").getStatusCode().value());
        assertEquals(200, flinkJobController.getGameJobs("g").getStatusCode().value());
        assertEquals(200, flinkJobController.getRunningJobs("g").getStatusCode().value());
        assertEquals(200, flinkJobController.deployJob("j1", "g", new FlinkJobController.DeployRequest()).getStatusCode().value());
        assertEquals(200, flinkJobController.stopJob("j1", "g", new FlinkJobController.StopRequest()).getStatusCode().value());
        assertEquals(200, flinkJobController.getJobStats("g").getStatusCode().value());
        assertEquals(200, flinkJobController.getJobConfig("j1", "g").getStatusCode().value());
        assertEquals(200, flinkJobController.getJobRules("j1", "g").getStatusCode().value());
        assertEquals(200, flinkJobController.updateMetrics("j1", "g", new FlinkJobController.MetricsUpdateRequest()).getStatusCode().value());
    }

    // ===== 集成 =====

    @Mock
    private IntegrationService integrationService;

    @InjectMocks
    private IntegrationController integrationController;

    @Test
    @DisplayName("集成：14 个端点委托")
    void integrationEndpoints() {
        assertEquals(200, integrationController.createIntegration(new IntegrationController.IntegrationRequest()).getStatusCode().value());
        assertEquals(200, integrationController.getIntegration("i1", "g").getStatusCode().value());
        assertEquals(200, integrationController.getIntegrations("g").getStatusCode().value());
        assertEquals(200, integrationController.getIntegrationsByType("g", io.oddsmaker.control.jpa.IntegrationEntity.IntegrationType.WEBHOOK).getStatusCode().value());
        assertEquals(200, integrationController.updateIntegration("i1", "g", new IntegrationController.UpdateRequest()).getStatusCode().value());
        assertEquals(200, integrationController.verifyIntegration("i1", "g").getStatusCode().value());
        assertEquals(200, integrationController.enableIntegration("i1", "g").getStatusCode().value());
        assertEquals(200, integrationController.disableIntegration("i1", "g").getStatusCode().value());
        assertEquals(200, integrationController.deleteIntegration("i1", "g").getStatusCode().value());
        assertEquals(200, integrationController.getIntegrationLogs("i1", "g").getStatusCode().value());
        assertEquals(200, integrationController.getIntegrationStats("g").getStatusCode().value());
        assertEquals(200, integrationController.getCallStats("i1", "g", null).getStatusCode().value());
        assertEquals(200, integrationController.triggerIntegration("i1", "g", new IntegrationController.TriggerRequest()).getStatusCode().value());
        assertEquals(200, integrationController.triggerIntegrations("g", io.oddsmaker.control.jpa.IntegrationEntity.IntegrationType.WEBHOOK, new IntegrationController.TriggerRequest()).getStatusCode().value());
    }

    // ===== Webhook =====

    @Mock
    private WebhookService webhookService;

    @InjectMocks
    private WebhookController webhookController;

    @Test
    @DisplayName("Webhook：5 个端点委托")
    void webhookEndpoints() {
        assertEquals(200, webhookController.getGameConfigs("g").getStatusCode().value());
        assertEquals(200, webhookController.getConfig("c1", "g").getStatusCode().value());
        assertEquals(200, webhookController.getWebhookLogs("c1", "g").getStatusCode().value());
        assertEquals(200, webhookController.getWebhookStats("g").getStatusCode().value());
        assertEquals(200, webhookController.testWebhook("c1", "g").getStatusCode().value());
    }

    // ===== 限流 =====

    @Mock
    private RateLimitService rateLimitService;

    @InjectMocks
    private RateLimitController rateLimitController;

    @Test
    @DisplayName("限流：规则与配额端点委托")
    void rateLimitEndpoints() {
        assertEquals(200, rateLimitController.createRateLimit(new RateLimitController.RateLimitRequest()).getStatusCode().value());
        assertEquals(200, rateLimitController.getRateLimit("r1", "g").getStatusCode().value());
        assertEquals(200, rateLimitController.getRateLimits("g").getStatusCode().value());
        assertEquals(200, rateLimitController.updateRateLimit("r1", "g", new RateLimitController.UpdateRequest()).getStatusCode().value());
        assertEquals(200, rateLimitController.deleteRateLimit("r1", "g").getStatusCode().value());
        assertEquals(200, rateLimitController.getRateLimitStats("g").getStatusCode().value());
        assertEquals(200, rateLimitController.createQuota(new RateLimitController.QuotaRequest()).getStatusCode().value());
        assertEquals(200, rateLimitController.checkQuota("g", null, null).getStatusCode().value());
        assertEquals(200, rateLimitController.updateQuotaUsage("g", null, null, 1L).getStatusCode().value());
        assertEquals(200, rateLimitController.getQuotaStats("g").getStatusCode().value());
    }

    // ===== 健康 =====

    @Mock
    private HealthMonitorService healthMonitorService;

    @InjectMocks
    private HealthController healthController;

    @Test
    @DisplayName("健康：11 个端点委托")
    void healthEndpoints() {
        assertEquals(200, healthController.getSystemHealth().getStatusCode().value());
        assertEquals(200, healthController.getHealthChecks().getStatusCode().value());
        assertEquals(200, healthController.getHealthCheck("db").getStatusCode().value());
        assertEquals(200, healthController.runHealthCheck("db").getStatusCode().value());
        assertEquals(200, healthController.liveness().getStatusCode().value());
        assertEquals(200, healthController.readiness().getStatusCode().value());
        assertEquals(200, healthController.getRecentMetrics(io.oddsmaker.control.jpa.HealthMetricEntity.MetricType.CPU_USAGE, null).getStatusCode().value());
        assertEquals(200, healthController.getActiveAlerts().getStatusCode().value());
        assertEquals(200, healthController.getAlertStats().getStatusCode().value());
        assertEquals(200, healthController.acknowledgeAlert("a1", new HealthController.AcknowledgeRequest()).getStatusCode().value());
        assertEquals(200, healthController.resolveAlert("a1", new HealthController.ResolveRequest()).getStatusCode().value());
    }

    // ===== 性能监控 =====

    @Mock
    private PerformanceMonitorService performanceMonitorService;

    @InjectMocks
    private PerformanceMonitoringController performanceController;

    @Test
    @DisplayName("性能监控：10 个端点委托")
    void performanceEndpoints() {
        assertEquals(200, performanceController.getSystemOverview().getStatusCode().value());
        assertEquals(200, performanceController.getApiMetrics().getStatusCode().value());
        assertEquals(200, performanceController.getEventMetrics().getStatusCode().value());
        assertEquals(200, performanceController.getRiskMetrics().getStatusCode().value());
        assertEquals(200, performanceController.getDatabaseMetrics().getStatusCode().value());
        assertEquals(200, performanceController.getKafkaMetrics().getStatusCode().value());
        assertEquals(200, performanceController.getSystemMetrics().getStatusCode().value());
        assertEquals(200, performanceController.getBusinessMetrics().getStatusCode().value());
        assertEquals(200, performanceController.getHealthMetrics().getStatusCode().value());
        assertEquals(200, performanceController.getPrometheusEndpoint().getStatusCode().value());
    }

    // ===== 埋点方案 =====

    @Mock
    private TrackingPlanService trackingPlanService;

    @InjectMocks
    private TrackingPlanController trackingPlanController;

    @Test
    @DisplayName("埋点方案：18 个端点委托")
    void trackingPlanEndpoints() {
        assertEquals(200, trackingPlanController.createTrackingPlan("g", new io.oddsmaker.control.dto.TrackingPlanDTO()).getStatusCode().value());
        assertEquals(404, trackingPlanController.getTrackingPlan("g", "tp1").getStatusCode().value());
        assertEquals(200, trackingPlanController.listTrackingPlans("g").getStatusCode().value());
        assertEquals(200, trackingPlanController.getActiveTrackingPlans("g").getStatusCode().value());
        assertEquals(200, trackingPlanController.getTrackingPlansForEnvironment("g", "e1").getStatusCode().value());
        assertEquals(200, trackingPlanController.updateTrackingPlan("g", "tp1", new io.oddsmaker.control.dto.TrackingPlanDTO()).getStatusCode().value());
        assertEquals(200, trackingPlanController.activateTrackingPlan("g", "tp1", "system").getStatusCode().value());
        assertEquals(200, trackingPlanController.deactivateTrackingPlan("g", "tp1").getStatusCode().value());
        assertEquals(200, trackingPlanController.deleteTrackingPlan("g", "tp1").getStatusCode().value());
        assertEquals(200, trackingPlanController.createEventDefinition("g", "tp1", new io.oddsmaker.control.dto.EventDefinitionDTO()).getStatusCode().value());
        assertEquals(200, trackingPlanController.listEventDefinitions("g", "tp1").getStatusCode().value());
        assertEquals(404, trackingPlanController.getEventDefinition("g", "tp1", "ed1").getStatusCode().value());
        assertEquals(200, trackingPlanController.updateEventDefinition("g", "tp1", "ed1", new io.oddsmaker.control.dto.EventDefinitionDTO()).getStatusCode().value());
        assertEquals(200, trackingPlanController.deleteEventDefinition("g", "tp1", "ed1").getStatusCode().value());
        assertEquals(200, trackingPlanController.createPropertyDefinition("g", "tp1", "ed1", new io.oddsmaker.control.dto.EventPropertyDefinitionDTO()).getStatusCode().value());
        assertEquals(200, trackingPlanController.listPropertyDefinitions("g", "tp1", "ed1").getStatusCode().value());
        assertEquals(200, trackingPlanController.updatePropertyDefinition("g", "tp1", "ed1", "pd1", new io.oddsmaker.control.dto.EventPropertyDefinitionDTO()).getStatusCode().value());
        assertEquals(200, trackingPlanController.deletePropertyDefinition("g", "tp1", "ed1", "pd1").getStatusCode().value());
        verify(trackingPlanService).listTrackingPlans("g");
    }

    // ===== 审计日志 =====

    @Mock
    private AuditLogRepo auditLogRepo;

    @InjectMocks
    private AuditLogController auditLogController;

    @Test
    @DisplayName("审计日志：14 个端点委托")
    void auditLogEndpoints() {
        assertEquals(200, auditLogController.listAuditLogs(0, 50, "createdAt", "desc").getStatusCode().value());
        assertEquals(200, auditLogController.getLogsByUser("u1", 0, 50).getStatusCode().value());
        assertEquals(200, auditLogController.getLogsByResource("game", "g1", 0, 50).getStatusCode().value());
        assertEquals(200, auditLogController.getLogsByAction(io.oddsmaker.control.jpa.AuditLogEntity.AuditAction.CREATE, 0, 50).getStatusCode().value());
        assertEquals(200, auditLogController.getLogsByStatus(io.oddsmaker.control.jpa.AuditLogEntity.AuditStatus.SUCCESS, 0, 50).getStatusCode().value());
        assertEquals(200, auditLogController.getLogsByTimeRange(
            java.time.LocalDateTime.now().minusDays(1), java.time.LocalDateTime.now(), 0, 50).getStatusCode().value());
        assertEquals(200, auditLogController.getLogsByGame("g", 0, 50).getStatusCode().value());
        assertEquals(200, auditLogController.getFailedLogs(0, 50).getStatusCode().value());
        assertEquals(200, auditLogController.getAuthLogs(0, 50).getStatusCode().value());
        assertEquals(200, auditLogController.getSensitiveLogs(0, 50).getStatusCode().value());
        assertEquals(200, auditLogController.searchLogs("q", 0, 50).getStatusCode().value());
        assertEquals(200, auditLogController.getAuditStatistics(7).getStatusCode().value());
        assertEquals(404, auditLogController.getAuditLog(1L).getStatusCode().value());
        assertEquals(200, auditLogController.cleanupOldLogs(90).getStatusCode().value());
    }
}
