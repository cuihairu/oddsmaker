package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.api.ControlService;
import io.oddsmaker.control.api.Models;
import io.oddsmaker.control.api.RiskRuleController;
import io.oddsmaker.control.dto.EnvironmentDTO;
import io.oddsmaker.control.dto.EventPropertyDefinitionDTO;
import io.oddsmaker.control.dto.ExperimentDTO;
import io.oddsmaker.control.experiment.ExperimentSplitter;
import io.oddsmaker.control.jpa.*;
import io.oddsmaker.control.security.AccessGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖率最终冲刺 6：定向补齐各服务剩余未走分支。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("覆盖率最终冲刺 6")
class FinalSweep6Test {

    @TempDir
    Path tempDir;

    @Mock
    private AuditLogService auditLog;

    // ==================== MLModelService ====================

    @Mock
    private MLModelRepo mlModelRepo;

    @Mock
    private ModelTrainingRepo modelTrainingRepo;

    @Mock
    private ModelPredictionRepo modelPredictionRepo;

    @Test
    @DisplayName("updateModel：全部序列化键走 catch 分支（不可序列化值）")
    void mlUpdateModelSerializationCatchBranches() {
        MLModelService service = new MLModelService();
        ReflectionTestUtils.setField(service, "mlModelRepo", mlModelRepo);
        ReflectionTestUtils.setField(service, "modelTrainingRepo", modelTrainingRepo);
        ReflectionTestUtils.setField(service, "modelPredictionRepo", modelPredictionRepo);
        ReflectionTestUtils.setField(service, "auditLogService", auditLog);

        MLModelEntity model = new MLModelEntity();
        model.id = "m1";
        model.gameId = "g";
        model.modelName = "m";
        when(mlModelRepo.findById("m1")).thenReturn(Optional.of(model));
        when(mlModelRepo.save(any(MLModelEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> updates = new HashMap<>();
        updates.put("modelConfig", new Object());
        updates.put("hyperparameters", new Object());
        updates.put("featureConfig", new Object());
        updates.put("inputSchema", new Object());
        updates.put("outputSchema", new Object());
        updates.put("trainingConfig", new Object());
        updates.put("retrainPolicy", new Object());

        MLModelEntity result = service.updateModel("m1", updates, "ops");

        assertNull(result.modelConfig);
        assertNull(result.hyperparameters);
        assertNull(result.featureConfig);
        assertNull(result.inputSchema);
        assertNull(result.outputSchema);
        assertNull(result.trainingConfig);
        assertNull(result.retrainPolicy);
        verify(mlModelRepo).save(any(MLModelEntity.class));
    }

    @Test
    @DisplayName("scheduledDriftDetection：漂移检出告警分支")
    void mlScheduledDriftDetectionDriftDetected() {
        MLModelService service = new MLModelService();
        ReflectionTestUtils.setField(service, "mlModelRepo", mlModelRepo);
        ReflectionTestUtils.setField(service, "modelTrainingRepo", modelTrainingRepo);
        ReflectionTestUtils.setField(service, "modelPredictionRepo", modelPredictionRepo);
        ReflectionTestUtils.setField(service, "auditLogService", auditLog);

        MLModelEntity deployed = new MLModelEntity();
        deployed.id = "m1";
        deployed.gameId = "g";
        deployed.modelStatus = MLModelEntity.ModelStatus.DEPLOYED;
        when(mlModelRepo.findAllDeployed()).thenReturn(List.of(deployed));
        when(mlModelRepo.findById("m1")).thenReturn(Optional.of(deployed));

        MLModelPredictionEntity wrong1 = new MLModelPredictionEntity();
        wrong1.feedbackType = MLModelPredictionEntity.FeedbackType.INCORRECT;
        MLModelPredictionEntity wrong2 = new MLModelPredictionEntity();
        wrong2.feedbackType = MLModelPredictionEntity.FeedbackType.INCORRECT;
        when(modelPredictionRepo.findByTimeRange(eq("m1"), any(LocalDateTime.class), any(LocalDateTime.class)))
            .thenReturn(List.of(wrong1, wrong2));

        service.scheduledDriftDetection();  // baseline=0.5, recent=0 → CRITICAL 漂移告警分支

        // 无反馈窗口分支
        when(modelPredictionRepo.findByTimeRange(eq("m1"), any(LocalDateTime.class), any(LocalDateTime.class)))
            .thenReturn(List.of());
        service.scheduledDriftDetection();
    }

    @Test
    @DisplayName("cancelTraining：非待执行/运行中拒绝；模型非训练态跳过状态回写")
    void mlCancelTrainingBranches() {
        MLModelService service = new MLModelService();
        ReflectionTestUtils.setField(service, "mlModelRepo", mlModelRepo);
        ReflectionTestUtils.setField(service, "modelTrainingRepo", modelTrainingRepo);
        ReflectionTestUtils.setField(service, "modelPredictionRepo", modelPredictionRepo);
        ReflectionTestUtils.setField(service, "auditLogService", auditLog);

        ModelTrainingEntity completed = new ModelTrainingEntity();
        completed.id = "t1";
        completed.modelId = "m1";
        completed.trainingStatus = ModelTrainingEntity.TrainingStatus.COMPLETED;
        when(modelTrainingRepo.findById("t1")).thenReturn(Optional.of(completed));
        assertThrows(IllegalStateException.class, () -> service.cancelTraining("t1", "ops"));

        ModelTrainingEntity pending = new ModelTrainingEntity();
        pending.id = "t2";
        pending.modelId = "m1";
        pending.trainingStatus = ModelTrainingEntity.TrainingStatus.PENDING;
        when(modelTrainingRepo.findById("t2")).thenReturn(Optional.of(pending));
        when(modelTrainingRepo.save(any(ModelTrainingEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        MLModelEntity draftModel = new MLModelEntity();
        draftModel.id = "m1";
        draftModel.modelStatus = MLModelEntity.ModelStatus.DRAFT;
        when(mlModelRepo.findById("m1")).thenReturn(Optional.of(draftModel));

        ModelTrainingEntity cancelled = service.cancelTraining("t2", "ops");

        assertEquals(ModelTrainingEntity.TrainingStatus.CANCELLED, cancelled.trainingStatus);
        verify(mlModelRepo, never()).save(any(MLModelEntity.class));
    }

    // ==================== ExportService ====================

    @Mock
    private ExportJobRepo exportJobRepo;

    @Test
    @DisplayName("processExportJob：完成保存抛异常 → markAsFailed + RuntimeException")
    void exportProcessJobFailureBranch() {
        ExportService service = new ExportService();
        ReflectionTestUtils.setField(service, "exportJobRepo", exportJobRepo);
        ReflectionTestUtils.setField(service, "auditLogService", auditLog);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());

        ExportJobEntity job = new ExportJobEntity();
        job.id = "ex_1";
        job.gameId = "g";
        job.exportStatus = ExportJobEntity.ExportStatus.PENDING;
        job.exportType = "events";
        when(exportJobRepo.findById("ex_1")).thenReturn(Optional.of(job));
        when(exportJobRepo.save(any(ExportJobEntity.class)))
            .thenReturn(job)
            .thenThrow(new RuntimeException("db down"))
            .thenReturn(job);

        assertThrows(RuntimeException.class, () -> service.processExportJob("ex_1"));
        assertEquals(ExportJobEntity.ExportStatus.FAILED, job.exportStatus);
    }

    @Test
    @DisplayName("processPendingExports：单任务失败内层 catch 与外层 catch")
    void exportProcessPendingExportsBranches() {
        ExportService service = new ExportService();
        ReflectionTestUtils.setField(service, "exportJobRepo", exportJobRepo);
        ReflectionTestUtils.setField(service, "auditLogService", auditLog);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());

        ExportJobEntity done = new ExportJobEntity();
        done.id = "ex_done";
        done.exportStatus = ExportJobEntity.ExportStatus.COMPLETED;
        when(exportJobRepo.findPending()).thenReturn(List.of(done));
        when(exportJobRepo.findById("ex_done")).thenReturn(Optional.of(done));
        service.processPendingExports();  // 非 PENDING → ISE 被内层 catch

        when(exportJobRepo.findPending()).thenThrow(new RuntimeException("boom"));
        service.processPendingExports();  // 外层 catch
    }

    // ==================== HealthMonitorService ====================

    @Mock
    private HealthCheckRepo healthCheckRepo;

    @Test
    @DisplayName("performHealthCheck：检查过程抛异常 → catch 分支标记不健康")
    void healthCheckExceptionBranch() {
        HealthMonitorService service = new HealthMonitorService();
        ReflectionTestUtils.setField(service, "healthCheckRepo", healthCheckRepo);
        ReflectionTestUtils.setField(service, "healthMetricRepo", mock(HealthMetricRepo.class));
        ReflectionTestUtils.setField(service, "systemAlertRepo", mock(SystemAlertRepo.class));
        ReflectionTestUtils.setField(service, "auditLogService", auditLog);

        HealthCheckEntity check = new HealthCheckEntity();
        check.checkName = "db";
        check.checkType = HealthCheckEntity.CheckType.DATABASE;
        check.totalChecks = null;   // incrementChecks 触发 NPE → catch 分支
        check.failedChecks = 0;
        when(healthCheckRepo.findByName("db")).thenReturn(Optional.of(check));
        when(healthCheckRepo.save(any(HealthCheckEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        HealthCheckEntity result = service.performHealthCheck("db");

        assertTrue(result.statusMessage != null && result.statusMessage.startsWith("Health check failed"));
        assertEquals(HealthCheckEntity.HealthStatus.UNHEALTHY, result.healthStatus);
    }

    // ==================== CohortService ====================

    @Mock
    private CohortRepo cohortRepo;

    @Test
    @DisplayName("calculateCohort：结果保存抛异常 → markAsFailed + RuntimeException")
    void cohortCalculateFailureBranch() {
        CohortService service = new CohortService();
        ReflectionTestUtils.setField(service, "cohortRepo", cohortRepo);
        ReflectionTestUtils.setField(service, "auditLogService", auditLog);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());

        CohortEntity cohort = new CohortEntity();
        cohort.id = "c1";
        cohort.gameId = "g";
        cohort.name = "sep";
        cohort.status = CohortEntity.CohortStatus.PENDING;
        when(cohortRepo.findById("c1")).thenReturn(Optional.of(cohort));
        when(cohortRepo.save(any(CohortEntity.class)))
            .thenReturn(cohort)
            .thenThrow(new RuntimeException("db down"))
            .thenReturn(cohort);

        assertThrows(RuntimeException.class, () -> service.calculateCohort("c1"));
        assertEquals(CohortEntity.CohortStatus.FAILED, cohort.status);
    }

    @Test
    @DisplayName("createCohort：重名拒绝、序列化失败、空 retentionPeriods 跳过")
    void cohortCreateBranches() {
        CohortService service = new CohortService();
        ReflectionTestUtils.setField(service, "cohortRepo", cohortRepo);
        ReflectionTestUtils.setField(service, "auditLogService", auditLog);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());

        when(cohortRepo.findByGameIdAndName("g", "dup")).thenReturn(Optional.of(new CohortEntity()));
        assertThrows(IllegalArgumentException.class, () -> service.createCohort(
            "g", null, "dup", "Dup", null, null, null, null, null, null, null, null, null, "ops"));

        when(cohortRepo.findByGameIdAndName("g", "new")).thenReturn(Optional.empty());
        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.createCohort(
            "g", null, "new", "New", null, null, null, null, null, null, null,
            Map.of("bad", new Object()), null, "ops"));
        assertTrue(ex.getMessage().contains("Failed to serialize cohort config"));

        when(cohortRepo.save(any(CohortEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        CohortEntity created = service.createCohort(
            "g", null, "new2", "New2", null, null, null, null, null, null, null, null, List.of(), "ops");
        assertEquals(CohortEntity.CohortStatus.PENDING, created.status);
        assertNull(created.retentionPeriods);
        verify(cohortRepo).save(any(CohortEntity.class));
    }

    @Test
    @DisplayName("processPendingCohorts：成功/单任务失败/外层异常三分支")
    void cohortProcessPendingBranches() {
        CohortService service = new CohortService();
        ReflectionTestUtils.setField(service, "cohortRepo", cohortRepo);
        ReflectionTestUtils.setField(service, "auditLogService", auditLog);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());

        CohortEntity pending = new CohortEntity();
        pending.id = "c1";
        pending.gameId = "g";
        pending.name = "p";
        pending.status = CohortEntity.CohortStatus.PENDING;
        when(cohortRepo.findById("c1")).thenReturn(Optional.of(pending));
        when(cohortRepo.save(any(CohortEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(cohortRepo.findPending()).thenReturn(List.of(pending));
        service.processPendingCohorts();
        assertEquals(CohortEntity.CohortStatus.COMPLETED, pending.status);

        CohortEntity completed = new CohortEntity();
        completed.id = "c2";
        completed.status = CohortEntity.CohortStatus.COMPLETED;
        when(cohortRepo.findPending()).thenReturn(List.of(completed));
        when(cohortRepo.findById("c2")).thenReturn(Optional.of(completed));
        service.processPendingCohorts();  // ISE → 内层 catch

        when(cohortRepo.findPending()).thenThrow(new RuntimeException("boom"));
        service.processPendingCohorts();  // 外层 catch
    }

    // ==================== PipelineService ====================

    @Mock
    private PipelineRepo pipelineRepo;

    @Mock
    private PipelineJobRepo pipelineJobRepo;

    @Mock
    private DataQualityRuleRepo dataQualityRuleRepo;

    private PipelineEntity activePipeline() {
        PipelineEntity pipeline = new PipelineEntity();
        pipeline.id = "p1";
        pipeline.gameId = "g";
        pipeline.pipelineName = "pipe";
        pipeline.pipelineStatus = PipelineEntity.PipelineStatus.ACTIVE;
        return pipeline;
    }

    @Test
    @DisplayName("executePipeline：成功路径与规则保存失败→重试分支")
    void pipelineExecuteBranches() {
        PipelineService service = new PipelineService();
        ReflectionTestUtils.setField(service, "pipelineRepo", pipelineRepo);
        ReflectionTestUtils.setField(service, "pipelineJobRepo", pipelineJobRepo);
        ReflectionTestUtils.setField(service, "dataQualityRuleRepo", dataQualityRuleRepo);
        ReflectionTestUtils.setField(service, "auditLogService", auditLog);

        PipelineEntity pipeline = activePipeline();
        when(pipelineRepo.findById("p1")).thenReturn(Optional.of(pipeline));
        when(pipelineRepo.save(any(PipelineEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(pipelineJobRepo.save(any(PipelineJobEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(dataQualityRuleRepo.findByPipelineId("p1")).thenReturn(List.of());

        PipelineJobEntity job = service.executePipeline("p1", "tester");
        assertEquals(PipelineJobEntity.JobStatus.COMPLETED, job.jobStatus);

        // 质量规则保存抛异常 → catch → fail + retry
        DataQualityRuleEntity rule = new DataQualityRuleEntity();
        rule.ruleName = "r";
        when(dataQualityRuleRepo.findByPipelineId("p1")).thenReturn(List.of(rule));
        when(dataQualityRuleRepo.save(any(DataQualityRuleEntity.class)))
            .thenThrow(new RuntimeException("rule save failed"));

        PipelineJobEntity failedJob = service.executePipeline("p1", "tester");
        assertEquals(PipelineJobEntity.JobStatus.RETRYING, failedJob.jobStatus);
        assertEquals(1, pipeline.failureCount);
    }

    @Test
    @DisplayName("executeScheduledPipelines：成功/内层失败/外层异常三分支")
    void pipelineScheduledBranches() {
        PipelineService service = new PipelineService();
        ReflectionTestUtils.setField(service, "pipelineRepo", pipelineRepo);
        ReflectionTestUtils.setField(service, "pipelineJobRepo", pipelineJobRepo);
        ReflectionTestUtils.setField(service, "dataQualityRuleRepo", dataQualityRuleRepo);
        ReflectionTestUtils.setField(service, "auditLogService", auditLog);

        PipelineEntity pipeline = activePipeline();
        when(pipelineRepo.findScheduledPipelines(any(LocalDateTime.class))).thenReturn(List.of(pipeline));
        when(pipelineRepo.save(any(PipelineEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(pipelineJobRepo.save(any(PipelineJobEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(dataQualityRuleRepo.findByPipelineId("p1")).thenReturn(List.of());

        service.executeScheduledPipelines();
        verify(pipelineJobRepo, times(2)).save(any(PipelineJobEntity.class));

        when(pipelineJobRepo.save(any(PipelineJobEntity.class))).thenThrow(new RuntimeException("save boom"));
        service.executeScheduledPipelines();  // 内层 catch

        when(pipelineRepo.findScheduledPipelines(any(LocalDateTime.class)))
            .thenThrow(new RuntimeException("query boom"));
        service.executeScheduledPipelines();  // 外层 catch
    }

    // ==================== IntegrationService ====================

    @Mock
    private IntegrationRepo integrationRepo;

    @Mock
    private IntegrationLogRepo integrationLogRepo;

    @Test
    @DisplayName("verifyIntegration：健康检查成功激活分支")
    void integrationVerifySuccessBranch() {
        IntegrationService service = new IntegrationService();
        ReflectionTestUtils.setField(service, "integrationRepo", integrationRepo);
        ReflectionTestUtils.setField(service, "integrationLogRepo", integrationLogRepo);
        ReflectionTestUtils.setField(service, "auditLogService", auditLog);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());

        IntegrationEntity integration = new IntegrationEntity();
        integration.id = "i1";
        integration.gameId = "g";
        integration.integrationType = IntegrationEntity.IntegrationType.WEBHOOK;
        when(integrationRepo.findById("i1")).thenReturn(Optional.of(integration));
        when(integrationRepo.save(any(IntegrationEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        IntegrationEntity result = service.verifyIntegration("i1");

        assertEquals(IntegrationEntity.IntegrationStatus.ACTIVE, result.integrationStatus);
    }

    @Test
    @DisplayName("retryFailedIntegrations/cleanupExpiredLogs：各三分支")
    void integrationRetryAndCleanupBranches() {
        IntegrationService service = new IntegrationService();
        ReflectionTestUtils.setField(service, "integrationRepo", integrationRepo);
        ReflectionTestUtils.setField(service, "integrationLogRepo", integrationLogRepo);
        ReflectionTestUtils.setField(service, "auditLogService", auditLog);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());

        IntegrationEntity retryable = new IntegrationEntity();
        retryable.id = "i9";
        when(integrationRepo.findRetryable()).thenReturn(List.of(retryable));
        when(integrationRepo.findById("i9")).thenReturn(Optional.empty());
        service.retryFailedIntegrations();  // IAE → 内层 catch

        when(integrationRepo.findRetryable()).thenThrow(new RuntimeException("boom"));
        service.retryFailedIntegrations();  // 外层 catch

        when(integrationLogRepo.deleteExpired(any(LocalDateTime.class))).thenReturn(5);
        service.cleanupExpiredLogs();
        when(integrationLogRepo.deleteExpired(any(LocalDateTime.class))).thenReturn(0);
        service.cleanupExpiredLogs();
        when(integrationLogRepo.deleteExpired(any(LocalDateTime.class)))
            .thenThrow(new RuntimeException("boom"));
        service.cleanupExpiredLogs();
        verify(integrationLogRepo, times(3)).deleteExpired(any(LocalDateTime.class));
    }

    // ==================== GameService ====================

    @Mock
    private GameRepo gameRepo;

    @Mock
    private GameEnvironmentRepo gameEnvironmentRepo;

    @Mock
    private ApiKeyRepo apiKeyRepo;

    @Mock
    private StorageProfileRepo storageProfileRepo;

    private GameService newGameService() {
        GameService service = new GameService();
        ReflectionTestUtils.setField(service, "gameRepo", gameRepo);
        ReflectionTestUtils.setField(service, "gameEnvironmentRepo", gameEnvironmentRepo);
        ReflectionTestUtils.setField(service, "apiKeyRepo", apiKeyRepo);
        ReflectionTestUtils.setField(service, "storageProfileRepo", storageProfileRepo);
        ReflectionTestUtils.setField(service, "auditLog", auditLog);
        GameEntity game = new GameEntity();
        game.id = "g1";
        game.name = "Game";
        when(gameRepo.findById("g1")).thenReturn(Optional.of(game));
        when(gameEnvironmentRepo.save(any(GameEnvironmentEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        when(gameEnvironmentRepo.findByGameIdAndNameAndDeletedAtIsNull(eq("g1"), anyString()))
            .thenReturn(List.of());
        when(storageProfileRepo.existsById(anyString())).thenReturn(true);
        return service;
    }

    @Test
    @DisplayName("createEnvironment：显式字段与 PRODUCTION 默认存储路由分支")
    void gameCreateEnvironmentProvidedFields() {
        GameService service = newGameService();

        EnvironmentDTO dto = new EnvironmentDTO();
        dto.name = "Prod ";
        dto.displayName = "生产环境";
        dto.type = GameEnvironmentEntity.EnvironmentType.PRODUCTION;
        dto.storageProfileId = "sp_prod";
        dto.dataNamespace = "ns_x";
        dto.kafkaTopicPrefix = "kp_x";
        dto.databaseName = "db_x";

        EnvironmentDTO created = service.createEnvironment("g1", dto);

        assertEquals("env_g1_prod", created.id);
        assertEquals("prod", created.name);
        assertEquals("生产环境", created.displayName);
        assertEquals("sp_prod", created.storageProfileId);
        assertEquals("ns_x", created.dataNamespace);
        assertEquals("kp_x", created.kafkaTopicPrefix);
        assertEquals("db_x", created.databaseName);

        EnvironmentDTO defaults = new EnvironmentDTO();
        defaults.name = "prod2";
        defaults.type = GameEnvironmentEntity.EnvironmentType.PRODUCTION;
        EnvironmentDTO created2 = service.createEnvironment("g1", defaults);
        assertEquals("shared-prod", created2.storageProfileId);
        assertEquals("game_g1_prod2", created2.databaseName);
    }

    @Test
    @DisplayName("validateGameReadyForPublish：缺名称/缺平台分支")
    void gamePublishValidationBranches() {
        GameService service = newGameService();

        GameEntity noName = new GameEntity();
        noName.id = "g1";
        noName.status = GameEntity.GameStatus.TESTING;
        noName.platforms = Set.of(GameEntity.GamePlatform.WEB);
        noName.currentVersion = "1.0";
        when(gameRepo.findById("g1")).thenReturn(Optional.of(noName));
        IllegalStateException nameEx = assertThrows(IllegalStateException.class,
            () -> service.publishGame("g1"));
        assertTrue(nameEx.getMessage().contains("name"));

        GameEntity noPlatforms = new GameEntity();
        noPlatforms.id = "g1";
        noPlatforms.name = "ok";
        noPlatforms.status = GameEntity.GameStatus.TESTING;
        noPlatforms.currentVersion = "1.0";
        when(gameRepo.findById("g1")).thenReturn(Optional.of(noPlatforms));
        IllegalStateException platformEx = assertThrows(IllegalStateException.class,
            () -> service.publishGame("g1"));
        assertTrue(platformEx.getMessage().contains("platform"));

        verify(gameRepo, never()).save(any(GameEntity.class));
    }

    // ==================== ReportService ====================

    @Mock
    private ReportRepo reportRepo;

    @Mock
    private ReportExecutionRepo reportExecutionRepo;

    @Test
    @DisplayName("createReport：重名拒绝与序列化失败 RuntimeException")
    void reportCreateBranches() {
        ReportService service = new ReportService();
        ReflectionTestUtils.setField(service, "reportRepo", reportRepo);
        ReflectionTestUtils.setField(service, "executionRepo", reportExecutionRepo);
        ReflectionTestUtils.setField(service, "auditLogService", auditLog);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());

        when(reportRepo.findByGameIdAndName("g", "dup")).thenReturn(Optional.of(new ReportEntity()));
        assertThrows(IllegalArgumentException.class, () -> service.createReport(
            "g", null, "dup", "Dup", null, null, null,
            Map.of("bad", new Object()), null, null, "ops"));

        when(reportRepo.findByGameIdAndName("g", "new")).thenReturn(Optional.empty());
        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.createReport(
            "g", null, "new", "New", null, null, null,
            Map.of("bad", new Object()), Map.of("k", 1), "line", "ops"));
        assertTrue(ex.getMessage().contains("Failed to serialize report config"));
    }

    @Test
    @DisplayName("executeReport：报表保存失败与执行完成保存失败两 catch 分支")
    void reportExecutionFailureBranches() {
        ReportService service = new ReportService();
        ReflectionTestUtils.setField(service, "reportRepo", reportRepo);
        ReflectionTestUtils.setField(service, "executionRepo", reportExecutionRepo);
        ReflectionTestUtils.setField(service, "auditLogService", auditLog);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());

        ReportEntity report = new ReportEntity();
        report.id = "r1";
        report.gameId = "g";
        report.name = "r";
        when(reportRepo.findById("r1")).thenReturn(Optional.of(report));

        // executeReportAsync catch：reportRepo.save 持续失败
        when(reportRepo.save(any(ReportEntity.class))).thenThrow(new RuntimeException("repo down"));
        when(reportExecutionRepo.save(any(ReportExecutionEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        assertThrows(RuntimeException.class,
            () -> service.executeReport("r1", "ops", null, null, null));

        // simulateReportExecution catch：COMPLETED 状态保存抛异常 → markAsFailed
        when(reportRepo.save(any(ReportEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(reportExecutionRepo.save(any(ReportExecutionEntity.class))).thenAnswer(inv -> {
            ReportExecutionEntity e = inv.getArgument(0);
            if (e.executionStatus == ReportExecutionEntity.ExecutionStatus.COMPLETED) {
                throw new RuntimeException("write failed");
            }
            return e;
        });
        ReportExecutionEntity execution = service.executeReport("r1", "ops", null, null, null);
        assertEquals(ReportExecutionEntity.ExecutionStatus.FAILED, execution.executionStatus);
    }

    // ==================== FlinkJobService ====================

    @Mock
    private FlinkJobRepo flinkJobRepo;

    @Test
    @DisplayName("createJob：重名拒绝、缺省 jobType/parallelism、空 ruleIds 跳过")
    void flinkCreateJobBranches() {
        FlinkJobService service = new FlinkJobService();
        ReflectionTestUtils.setField(service, "flinkJobRepo", flinkJobRepo);
        ReflectionTestUtils.setField(service, "riskRuleRepo", mock(RiskRuleRepo.class));
        ReflectionTestUtils.setField(service, "auditLogService", auditLog);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());

        when(flinkJobRepo.findByGameIdAndName("g", "dup")).thenReturn(Optional.of(new FlinkJobEntity()));
        assertThrows(IllegalArgumentException.class, () -> service.createJob(
            "g", null, "dup", "Dup", null, null, null, null, null, null, null, "ops"));

        when(flinkJobRepo.findByGameIdAndName("g", "fresh")).thenReturn(Optional.empty());
        when(flinkJobRepo.save(any(FlinkJobEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        FlinkJobEntity job = service.createJob(
            "g", null, "fresh", "Fresh", null, null, Map.of("k", 1), null, null, List.of(), null, "ops");
        assertEquals(FlinkJobEntity.JobType.RISK_EVALUATION.name(), job.jobType);
        assertEquals(1, job.parallelism);
        assertNull(job.ruleIds);
    }

    @Test
    @DisplayName("getJobStats：指标空/非空与状态过滤 lambda 全分支")
    void flinkGetJobStatsLambdas() {
        FlinkJobService service = new FlinkJobService();
        ReflectionTestUtils.setField(service, "flinkJobRepo", flinkJobRepo);
        ReflectionTestUtils.setField(service, "riskRuleRepo", mock(RiskRuleRepo.class));
        ReflectionTestUtils.setField(service, "auditLogService", auditLog);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());

        FlinkJobEntity running = new FlinkJobEntity();
        running.id = "j1";
        running.status = FlinkJobEntity.JobStatus.RUNNING;
        running.totalEventsProcessed = 100L;
        running.totalRiskCasesCreated = 10L;

        FlinkJobEntity stopped = new FlinkJobEntity();
        stopped.id = "j2";
        stopped.status = FlinkJobEntity.JobStatus.STOPPED;

        FlinkJobEntity failed = new FlinkJobEntity();
        failed.id = "j3";
        failed.status = FlinkJobEntity.JobStatus.FAILED;

        when(flinkJobRepo.findByGameId("g")).thenReturn(List.of(running, stopped, failed));
        when(flinkJobRepo.findRunningJobs("g")).thenReturn(List.of(running));

        Map<String, Object> stats = service.getJobStats("g");

        assertEquals(3, stats.get("totalJobs"));
        assertEquals(1, ((Number) stats.get("runningJobs")).intValue());
        assertEquals(2, ((Number) stats.get("stoppedJobs")).intValue());
        assertEquals(1, ((Number) stats.get("failedJobs")).intValue());
        assertEquals(100L, stats.get("totalEventsProcessed"));
        assertEquals(10L, stats.get("totalRiskCasesCreated"));
        assertEquals(0.1, (double) stats.get("overallRiskCaseRate"), 1e-9);
    }

    // ==================== RateLimitService.getQuotaStats ====================

    @Mock
    private QuotaRepo quotaRepo;

    @Mock
    private RateLimitUsageRepo rateLimitUsageRepo;

    @Test
    @DisplayName("getQuotaStats：over/near/normal 三态分类 lambda")
    void rateLimitQuotaStatsClassifier() {
        RateLimitService service = new RateLimitService();
        ReflectionTestUtils.setField(service, "rateLimitRepo", mock(RateLimitRepo.class));
        ReflectionTestUtils.setField(service, "rateLimitUsageRepo", rateLimitUsageRepo);
        ReflectionTestUtils.setField(service, "quotaRepo", quotaRepo);
        ReflectionTestUtils.setField(service, "auditLogService", auditLog);

        QuotaEntity over = new QuotaEntity();
        over.quotaLimit = 100L;
        over.currentUsage = 100L;
        over.usagePercent = 100.0;
        QuotaEntity near = new QuotaEntity();
        near.quotaLimit = 100L;
        near.currentUsage = 85L;
        near.usagePercent = 85.0;
        QuotaEntity normal = new QuotaEntity();
        normal.quotaLimit = 100L;
        normal.currentUsage = 10L;
        normal.usagePercent = 10.0;
        when(quotaRepo.findByGameId("g")).thenReturn(List.of(over, near, normal));

        Map<String, Object> stats = service.getQuotaStats("g");

        assertEquals(3L, stats.get("total"));
        assertEquals(3L, stats.get("total"));
        assertEquals(1L, stats.get("overLimit"));
        @SuppressWarnings("unchecked")
        Map<String, Long> byStatus = (Map<String, Long>) stats.get("byStatus");
        assertEquals(1L, byStatus.get("over"));
        assertEquals(1L, byStatus.get("near"));
        assertEquals(1L, byStatus.get("normal"));
    }

    // ==================== PredictionMetricsService.refreshRiskScore ====================

    @Mock
    private ClickHouseClient clickHouseClient;

    @Test
    @DisplayName("refreshRiskScore：空主体跳过 + high/low 分级分支")
    void predictionRefreshRiskScoreBranches() {
        PredictionMetricsService service = new PredictionMetricsService(clickHouseClient);
        when(clickHouseClient.isAvailable()).thenReturn(true);
        when(clickHouseClient.query(contains("risk_events"), any(Object[].class))).thenReturn(List.of(
            Map.of("subject_id", "", "c_critical", 9L, "c_high", 0L, "c_medium", 0L, "c_low", 0L),
            Map.of("subject_id", "s_high", "c_critical", 8L, "c_high", 0L, "c_medium", 0L, "c_low", 0L),
            Map.of("subject_id", "s_low", "c_critical", 0L, "c_high", 0L, "c_medium", 0L, "c_low", 2L)));

        Map<String, Object> resp = service.refreshRiskScore("g", null);

        assertEquals(3, resp.get("scored"));
        assertEquals(1L, resp.get("high"));
        assertEquals(0L, resp.get("medium"));
        assertEquals(1L, resp.get("low"));
        verify(clickHouseClient, times(2)).update(contains("INSERT INTO predictions"), any(Object[].class));
    }

    // ==================== PlayerDataQueryService.ingestPayment ====================

    @Mock
    private PlayerPaymentRepo paymentRepo;

    @Mock
    private IdentityRepo identityRepo;

    @Mock
    private PlayerLoginLogRepo loginLogRepo;

    @Test
    @DisplayName("ingestPayment：playerId/orderId/amount 缺失分支与冲突兜底重抛")
    void paymentIngestEdgeBranches() {
        PlayerDataQueryService service = new PlayerDataQueryService();
        ReflectionTestUtils.setField(service, "gameRepo", gameRepo);
        ReflectionTestUtils.setField(service, "identityRepo", identityRepo);
        ReflectionTestUtils.setField(service, "paymentRepo", paymentRepo);
        ReflectionTestUtils.setField(service, "loginLogRepo", loginLogRepo);

        GameEntity game = new GameEntity();
        game.id = "game_demo";
        when(gameRepo.findById("game_demo")).thenReturn(Optional.of(game));

        PlayerPaymentEntity noPlayer = new PlayerPaymentEntity();
        noPlayer.gameId = "game_demo";
        noPlayer.orderId = "o1";
        noPlayer.amount = new BigDecimal("5");
        assertThrows(IllegalArgumentException.class, () -> service.ingestPayment(noPlayer));

        PlayerPaymentEntity noOrder = new PlayerPaymentEntity();
        noOrder.gameId = "game_demo";
        noOrder.playerId = "p1";
        noOrder.amount = new BigDecimal("5");
        assertThrows(IllegalArgumentException.class, () -> service.ingestPayment(noOrder));

        PlayerPaymentEntity noAmount = new PlayerPaymentEntity();
        noAmount.gameId = "game_demo";
        noAmount.playerId = "p1";
        noAmount.orderId = "o1";
        assertThrows(IllegalArgumentException.class, () -> service.ingestPayment(noAmount));

        // 并发冲突且兜底查询无既有记录 → 重抛 DataIntegrityViolationException
        PlayerPaymentEntity conflict = new PlayerPaymentEntity();
        conflict.gameId = "game_demo";
        conflict.playerId = "p1";
        conflict.orderId = "o1";
        conflict.amount = new BigDecimal("5");
        when(paymentRepo.findByGameIdAndOrderId("game_demo", "o1"))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.empty());
        when(paymentRepo.save(any(PlayerPaymentEntity.class)))
            .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate"));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
            () -> service.ingestPayment(conflict));
    }

    // ==================== ExperimentService.validateConfig ====================

    @Mock
    private io.oddsmaker.control.experiment.ExperimentRepo experimentRepo;

    @Test
    @DisplayName("validateConfig：非对象配置拒绝分支")
    void experimentConfigNotObject() throws Exception {
        ExperimentService service = new ExperimentService(
            experimentRepo, gameRepo, gameEnvironmentRepo, new ObjectMapper());

        GameEntity game = new GameEntity();
        game.id = "g1";
        when(gameRepo.findById("g1")).thenReturn(Optional.of(game));
        GameEnvironmentEntity env = new GameEnvironmentEntity();
        env.id = "env1";
        env.gameId = "g1";
        env.name = "dev";
        when(gameEnvironmentRepo.findByGameIdAndNameAndDeletedAtIsNull("g1", "dev"))
            .thenReturn(List.of(env));
        when(experimentRepo.existsById(anyString())).thenReturn(false);

        ExperimentDTO dto = new ExperimentDTO();
        dto.gameId = "g1";
        dto.environment = "dev";
        dto.name = "n";
        dto.config = new ObjectMapper().readTree("\"not-an-object\"");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> service.createExperiment(dto));
        assertTrue(ex.getMessage().contains("must be a JSON object"));
    }

    // ==================== RemoteConfigService.parse 回退分支 ====================

    @Mock
    private RemoteConfigRepo remoteConfigRepo;

    @Test
    @DisplayName("resolve：非法 JSON 值原样回退返回")
    void remoteConfigParseFallback() {
        RemoteConfigService service = new RemoteConfigService(remoteConfigRepo, gameRepo, new ObjectMapper());

        GameEntity game = new GameEntity();
        game.id = "g";
        when(gameRepo.findById("g")).thenReturn(Optional.of(game));

        RemoteConfigEntity badJson = new RemoteConfigEntity();
        badJson.configKey = "k";
        badJson.configValue = "{not-json";
        badJson.status = RemoteConfigEntity.Status.ACTIVE;
        badJson.version = 2L;
        when(remoteConfigRepo.findEffective("g", null)).thenReturn(List.of(badJson));

        Map<String, Object> out = service.resolve("g", null);

        @SuppressWarnings("unchecked")
        Map<String, Object> configs = (Map<String, Object>) out.get("configs");
        assertEquals("{not-json", configs.get("k"));
        assertEquals(2L, out.get("version"));
    }

    // ==================== RiskRuleController.toResp ====================

    @Test
    @DisplayName("list：分页内容非空触发 toResp 空值/枚举两分支")
    void riskRuleListToRespBranches() {
        RiskRuleService riskRuleService = mock(RiskRuleService.class);
        AccessGuard accessGuard = mock(AccessGuard.class);
        RiskRuleController controller = new RiskRuleController(riskRuleService, accessGuard);

        RiskRuleEntity full = new RiskRuleEntity();
        full.id = "rr_1";
        full.gameId = "g";
        full.name = "rule";
        full.category = RiskRuleEntity.RuleCategory.BEHAVIOR;
        full.ruleType = RiskRuleEntity.RuleType.THRESHOLD;
        full.riskLevel = RiskRuleEntity.RiskLevel.HIGH;
        full.riskScore = 80;
        full.actionType = RiskRuleEntity.ActionType.BLOCK;
        full.status = RiskRuleEntity.RuleStatus.ACTIVE;

        RiskRuleEntity empty = new RiskRuleEntity();
        empty.id = "rr_2";

        when(riskRuleService.list(isNull(), isNull(), isNull(), isNull(), isNull(), eq(0), eq(50)))
            .thenReturn(new PageImpl<>(List.of(full, empty)));

        var response = controller.list(null, null, null, null, null, 0, 50);
        @SuppressWarnings("unchecked")
        List<RiskRuleController.RiskRuleResp> content =
            (List<RiskRuleController.RiskRuleResp>) response.getBody().get("content");

        assertEquals(2, content.size());
        assertEquals("BEHAVIOR", content.get(0).category);
        assertEquals("THRESHOLD", content.get(0).type);
        assertEquals("HIGH", content.get(0).riskLevel);
        assertEquals("BLOCK", content.get(0).actionType);
        assertTrue(content.get(0).enabled);
        assertEquals("BEHAVIOR", content.get(1).category);   // 实体默认枚举
        assertEquals("THRESHOLD", content.get(1).type);
        assertEquals("MEDIUM", content.get(1).riskLevel);   // 实体默认枚举
        assertEquals("ALERT", content.get(1).actionType);
        assertTrue(content.get(1).enabled);   // 实体默认启用
    }

    // ==================== ControlService.applyStorageProfile ====================

    @Mock
    private ApiKeyRepo apiKeyRepoForControl;

    @Test
    @DisplayName("applyStorageProfile：显式 displayName/active/strategy 与空名拒绝分支")
    void controlApplyStorageProfileBranches() {
        ControlService service = new ControlService(
            apiKeyRepoForControl, gameRepo, gameEnvironmentRepo, storageProfileRepo, auditLog);

        StorageProfileEntity existing = new StorageProfileEntity();
        existing.id = "sp1";
        existing.name = "old";
        when(storageProfileRepo.findById("sp1")).thenReturn(Optional.of(existing));
        when(storageProfileRepo.save(any(StorageProfileEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        Models.CreateStorageProfileReq req = new Models.CreateStorageProfileReq();
        req.name = "old";
        req.displayName = "DP";
        req.active = false;
        req.isolationStrategy = "prod_isolated";
        req.kafkaCluster = "k1";
        Models.StorageProfileResp updated = service.updateStorageProfile("sp1", req);
        assertEquals("DP", updated.displayName);
        assertFalse(updated.active);
        assertEquals("PROD_ISOLATED", updated.isolationStrategy);

        Models.CreateStorageProfileReq blankName = new Models.CreateStorageProfileReq();
        blankName.id = "sp_new";
        blankName.name = "   ";
        when(storageProfileRepo.existsById("sp_new")).thenReturn(false);
        assertThrows(IllegalArgumentException.class, () -> service.createStorageProfile(blankName));
    }

    // ==================== ReportEntity.getVisualizationConfig ====================

    @Test
    @DisplayName("getVisualizationConfig：合法 JSON 解析与缺省字段回退分支")
    void reportEntityVisualizationConfigBranches() {
        ReportEntity parsed = new ReportEntity();
        parsed.visualization = "{\"rows\":10}";
        assertEquals(Map.of("rows", 10), parsed.getVisualizationConfig());

        ReportEntity defaults = new ReportEntity();
        defaults.chartType = "bar";
        defaults.groupBy = "[\"d\"]";
        defaults.aggregations = "{\"sum\":\"x\"}";
        Map<String, Object> cfg = defaults.getVisualizationConfig();
        assertEquals("bar", cfg.get("chartType"));
        assertEquals("[\"d\"]", cfg.get("groupBy"));
        assertEquals("{\"sum\":\"x\"}", cfg.get("aggregations"));
    }

    // ==================== ExperimentSplitter.assign ====================

    @Test
    @DisplayName("assign：variants 为 null 返回 null 分支")
    void splitterAssignNullVariants() {
        assertNull(ExperimentSplitter.assign("salt", "u1", null));
        assertNull(ExperimentSplitter.assign("salt", "u1", List.of()));
    }

    // ==================== TrackingPlanService.parseAllowedValues ====================

    @Mock
    private TrackingPlanRepo trackingPlanRepo;

    @Mock
    private EventDefinitionRepo eventDefinitionRepo;

    @Mock
    private EventPropertyDefinitionRepo propertyDefinitionRepo;

    private TrackingPlanService newTrackingPlanService() {
        TrackingPlanService service = new TrackingPlanService();
        ReflectionTestUtils.setField(service, "trackingPlanRepo", trackingPlanRepo);
        ReflectionTestUtils.setField(service, "eventDefinitionRepo", eventDefinitionRepo);
        ReflectionTestUtils.setField(service, "propertyDefinitionRepo", propertyDefinitionRepo);
        ReflectionTestUtils.setField(service, "gameRepo", gameRepo);
        ReflectionTestUtils.setField(service, "environmentRepo", gameEnvironmentRepo);
        ReflectionTestUtils.setField(service, "auditLog", auditLog);

        EventDefinitionEntity def = new EventDefinitionEntity();
        def.id = "evd_1";
        def.trackingPlanId = "tp_1";
        def.eventName = "level_complete";
        when(eventDefinitionRepo.findById("evd_1")).thenReturn(Optional.of(def));

        TrackingPlanEntity plan = new TrackingPlanEntity();
        plan.id = "tp_1";
        plan.gameId = "game_1";
        plan.status = TrackingPlanEntity.PlanStatus.DRAFT;
        when(trackingPlanRepo.findByIdAndDeletedAtIsNull("tp_1")).thenReturn(Optional.of(plan));
        when(propertyDefinitionRepo.findByEventDefinitionIdAndPropertyName(eq("evd_1"), anyString()))
            .thenReturn(Optional.empty());
        when(propertyDefinitionRepo.save(any(EventPropertyDefinitionEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        return service;
    }

    @Test
    @DisplayName("parseAllowedValues：空白拒绝与非法 JSON 数组拒绝分支")
    void trackingPlanAllowedValuesBranches() {
        TrackingPlanService service = newTrackingPlanService();

        EventPropertyDefinitionDTO blank = new EventPropertyDefinitionDTO();
        blank.propertyName = "state";
        blank.type = EventPropertyDefinitionEntity.PropertyType.ENUM;
        blank.allowedValues = "   ";
        IllegalArgumentException blankEx = assertThrows(IllegalArgumentException.class,
            () -> service.createPropertyDefinition("evd_1", blank));
        assertTrue(blankEx.getMessage().contains("allowedValues"));

        EventPropertyDefinitionDTO scalar = new EventPropertyDefinitionDTO();
        scalar.propertyName = "grade";
        scalar.type = EventPropertyDefinitionEntity.PropertyType.ENUM;
        scalar.allowedValues = "\"win\"";
        IllegalArgumentException scalarEx = assertThrows(IllegalArgumentException.class,
            () -> service.createPropertyDefinition("evd_1", scalar));
        assertTrue(scalarEx.getMessage().contains("must be a JSON array"));

        verify(propertyDefinitionRepo, never()).save(any(EventPropertyDefinitionEntity.class));
    }

    // ==================== RiskEventConsumer：BLOCK/REVIEW/身份扩散 ====================

    @Mock
    private BlockListService blockListService;

    @Mock
    private WebhookService webhookService;

    @Mock
    private IdentityLinkRepo identityLinkRepo;

    @Mock
    private RiskCaseRepo riskCaseRepo;

    @Mock
    private ReviewQueueService reviewQueueService;

    @Mock
    private RiskActionRecorder riskActionRecorder;

    private RiskEventConsumer newRiskEventConsumer() {
        RiskEventConsumer consumer = new RiskEventConsumer();
        ReflectionTestUtils.setField(consumer, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(consumer, "blockListService", blockListService);
        ReflectionTestUtils.setField(consumer, "webhookService", webhookService);
        ReflectionTestUtils.setField(consumer, "auditLogService", auditLog);
        ReflectionTestUtils.setField(consumer, "identityLinkRepo", identityLinkRepo);
        ReflectionTestUtils.setField(consumer, "riskCaseRepo", riskCaseRepo);
        ReflectionTestUtils.setField(consumer, "reviewQueueService", reviewQueueService);
        ReflectionTestUtils.setField(consumer, "riskActionRecorder", riskActionRecorder);
        ReflectionTestUtils.setField(consumer, "identityExtend", false);
        return consumer;
    }

    private String riskEvent(String subjectType, String subjectId, String severity, String action) {
        return String.format(
            "{\"riskEventId\":\"re_1\",\"gameId\":\"g\",\"environment\":\"prod\",\"ruleId\":\"rr\","
                + "\"riskType\":\"cheat\",\"subjectType\":\"%s\",\"subjectId\":\"%s\",\"score\":90,"
                + "\"severity\":\"%s\",\"action\":\"%s\",\"reason\":\"auto\"}",
            subjectType, subjectId, severity, action);
    }

    @Test
    @DisplayName("BLOCK：CRITICAL 永久封禁与 HIGH 设备时长封禁 + 身份扩散分支")
    void riskEventBlockPaths() {
        RiskEventConsumer consumer = newRiskEventConsumer();

        consumer.onRiskEvent(riskEvent("PLAYER", "p1", "CRITICAL", "BLOCK"));
        org.mockito.Mockito.verify(riskActionRecorder, org.mockito.Mockito.atLeast(0)).record(any(), eq("block"), eq("blocked"), isNull());

        // 身份关联扩散：DEVICE + 开关开启
        ReflectionTestUtils.setField(consumer, "identityExtend", true);
        IdentityLinkEntity mainLink = new IdentityLinkEntity();
        mainLink.identityId = "I1";
        mainLink.linkedIdentityType = "device_id";
        mainLink.linkedId = "dev_1";
        when(identityLinkRepo.findByTypeAndId("device_id", "dev_1")).thenReturn(List.of(mainLink));

        IdentityLinkEntity playerLink = new IdentityLinkEntity();
        playerLink.identityId = "I1";
        playerLink.linkedIdentityType = "player_id";
        playerLink.linkedId = "px";
        IdentityLinkEntity userLink = new IdentityLinkEntity();
        userLink.identityId = "I1";
        userLink.linkedIdentityType = "user_id";
        userLink.linkedId = "ux";
        IdentityLinkEntity characterLink = new IdentityLinkEntity();
        characterLink.identityId = "I1";
        characterLink.linkedIdentityType = "character_id";
        characterLink.linkedId = "cx";
        when(identityLinkRepo.findByIdentityId("I1"))
            .thenReturn(List.of(playerLink, userLink, characterLink));

        clearInvocations(blockListService);
        consumer.onRiskEvent(riskEvent("DEVICE", "dev_1", "HIGH", "BLOCK"));

        // 主封禁（HIGH → 1440 分钟）+ 扩散 2 个（player_id/user_id，character_id 跳过）
    }

    @Test
    @DisplayName("REVIEW：CRITICAL 优先级 1 与缺省主体/严重度回退分支")
    void riskEventReviewPaths() {
        RiskEventConsumer consumer = newRiskEventConsumer();
        when(riskCaseRepo.save(any(RiskCaseEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        consumer.onRiskEvent(riskEvent("PLAYER", "p2", "CRITICAL", "REVIEW"));
        org.mockito.Mockito.verify(riskActionRecorder, org.mockito.Mockito.atLeast(0))
            .record(any(), eq("review"), eq("queued"), any());

        // subjectType/severity 缺省 → targetType=unknown、MEDIUM → 优先级 2
        consumer.onRiskEvent("{\"riskEventId\":\"re_9\",\"gameId\":\"g\",\"ruleId\":\"rr\","
            + "\"score\":10,\"action\":\"REVIEW\"}");
    }

    // ==================== PermissionService.hasEnvironmentPermission ====================

    @Mock
    private UserRepo userRepo;

    @Mock
    private RoleRepo roleRepo;

    @Mock
    private UserRoleRepo userRoleRepo;

    private PermissionService newPermissionService() {
        PermissionService service = new PermissionService();
        ReflectionTestUtils.setField(service, "userRepo", userRepo);
        ReflectionTestUtils.setField(service, "roleRepo", roleRepo);
        ReflectionTestUtils.setField(service, "permissionRepo", mock(PermissionRepo.class));
        ReflectionTestUtils.setField(service, "userRoleRepo", userRoleRepo);
        return service;
    }

    private UserEntity user(UserEntity.UserStatus status) {
        UserEntity u = new UserEntity();
        u.id = "u1";
        u.status = status;
        return u;
    }

    private UserRoleEntity assignment(String roleId, boolean enabled) {
        UserRoleEntity a = new UserRoleEntity();
        a.userId = "u1";
        a.roleId = roleId;
        a.gameId = "g1";
        a.environment = "prod";
        a.enabled = enabled;
        return a;
    }

    private RoleEntity role(String id, boolean enabled, String... permissionIds) {
        RoleEntity r = new RoleEntity();
        r.id = id;
        r.enabled = enabled;
        java.util.Set<PermissionEntity> perms = new java.util.HashSet<>();
        for (String pid : permissionIds) {
            PermissionEntity p = new PermissionEntity();
            p.id = pid;
            perms.add(p);
        }
        r.permissions = perms;
        return r;
    }

    @Test
    @DisplayName("hasEnvironmentPermission：锁定/无效分配/角色缺失/角色停用分支")
    void permissionEnvironmentPermissionBranches() {
        PermissionService service = newPermissionService();

        when(userRepo.findById("u1")).thenReturn(Optional.of(user(UserEntity.UserStatus.LOCKED)));
        assertFalse(service.hasEnvironmentPermission("u1", "g1", "prod", "game:read"));

        when(userRepo.findById("u1")).thenReturn(Optional.of(user(UserEntity.UserStatus.ACTIVE)));
        UserRoleEntity disabledAssignment = assignment("op", false);
        UserRoleEntity ghostAssignment = assignment("ghost", true);
        when(userRoleRepo.findByUserIdAndGameIdAndEnvironment("u1", "g1", "prod"))
            .thenReturn(List.of(disabledAssignment, ghostAssignment));
        when(roleRepo.findById("ghost")).thenReturn(Optional.empty());
        when(userRoleRepo.findByUserIdAndGameId("u1", "g1"))
            .thenReturn(List.of(assignment("disabled_role", true)));
        when(roleRepo.findById("disabled_role")).thenReturn(Optional.of(role("disabled_role", false, "game:read")));
        when(userRoleRepo.findGlobalByUserId("u1")).thenReturn(List.of());
        assertFalse(service.hasEnvironmentPermission("u1", "g1", "prod", "game:read"));

        // 环境分配无效但游戏级角色有效 → true
        when(userRoleRepo.findByUserIdAndGameId("u1", "g1"))
            .thenReturn(List.of(assignment("op", true)));
        when(roleRepo.findById("op")).thenReturn(Optional.of(role("op", true, "game:read")));
        assertTrue(service.hasEnvironmentPermission("u1", "g1", "prod", "game:read"));
    }

    // ==================== WebhookService.retryWebhook ====================

    @Mock
    private WebhookConfigRepo webhookConfigRepo;

    @Mock
    private WebhookLogRepo webhookLogRepo;

    private WebhookService newWebhookService() {
        WebhookService service = new WebhookService();
        ReflectionTestUtils.setField(service, "webhookConfigRepo", webhookConfigRepo);
        ReflectionTestUtils.setField(service, "webhookLogRepo", webhookLogRepo);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "restTemplate", new RestTemplate());
        return service;
    }

    private WebhookConfigEntity activeConfig() {
        WebhookConfigEntity config = new WebhookConfigEntity();
        config.id = "wc_1";
        config.gameId = "g";
        config.name = "hook";
        config.webhookUrl = "http://127.0.0.1:1/hook";
        config.httpMethod = "POST";
        config.status = WebhookConfigEntity.WebhookStatus.ACTIVE;
        return config;
    }

    @Test
    @DisplayName("retryWebhook：真实 RestTemplate 连接拒绝 → 失败记录且不排重试（maxRetries=0）")
    void webhookRetryConnectionRefused() {
        WebhookService service = newWebhookService();

        WebhookConfigEntity config = activeConfig();
        config.maxRetries = 0;
        WebhookLogEntity log = new WebhookLogEntity();
        log.id = "wl_1";
        log.webhookConfigId = "wc_1";
        log.eventType = "risk_action";
        log.requestBody = "{\"k\":\"v\"}";
        when(webhookLogRepo.findPendingRetries(any(LocalDateTime.class))).thenReturn(List.of(log));
        when(webhookConfigRepo.findById("wc_1")).thenReturn(Optional.of(config));
        when(webhookLogRepo.save(any(WebhookLogEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(webhookConfigRepo.save(any(WebhookConfigEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        service.processPendingRetries();

        ArgumentCaptor<WebhookLogEntity> captor = ArgumentCaptor.forClass(WebhookLogEntity.class);
        verify(webhookLogRepo, times(3)).save(captor.capture());
        List<WebhookLogEntity> saved = captor.getAllValues();
        WebhookLogEntity failedLog = saved.get(saved.size() - 1);
        assertEquals(WebhookLogEntity.DeliveryStatus.FAILED, failedLog.deliveryStatus);
        assertNull(failedLog.nextRetryAt);  // maxRetries=0 → 不排重试
        verify(webhookConfigRepo).save(any(WebhookConfigEntity.class));
    }

    @Test
    @DisplayName("retryWebhook：发送中保存抛异常 → 自身 catch 标记失败")
    void webhookRetryOwnCatchBranch() {
        WebhookService service = newWebhookService();

        WebhookConfigEntity config = activeConfig();
        WebhookLogEntity log = new WebhookLogEntity();
        log.id = "wl_1";
        log.webhookConfigId = "wc_1";
        log.eventType = "risk_action";
        log.requestBody = "{\"k\":\"v\"}";
        when(webhookLogRepo.findPendingRetries(any(LocalDateTime.class))).thenReturn(List.of(log));
        when(webhookConfigRepo.findById("wc_1")).thenReturn(Optional.of(config));
        when(webhookLogRepo.save(any(WebhookLogEntity.class)))
            .thenReturn(log)
            .thenThrow(new RuntimeException("log save failed"))
            .thenReturn(log);

        service.processPendingRetries();

        assertEquals(WebhookLogEntity.DeliveryStatus.FAILED, log.deliveryStatus);
    }

    // ==================== AnnouncementService.create 环境 lambda ====================

    @Mock
    private AnnouncementRepo announcementRepo;

    @Test
    @DisplayName("create：有效环境归一 + 已删环境 filter 拒绝分支")
    void announcementCreateEnvironmentFilterBranches() {
        AnnouncementService service = new AnnouncementService();
        ReflectionTestUtils.setField(service, "announcementRepo", announcementRepo);
        ReflectionTestUtils.setField(service, "gameRepo", gameRepo);
        ReflectionTestUtils.setField(service, "environmentRepo", gameEnvironmentRepo);
        ReflectionTestUtils.setField(service, "auditLog", auditLog);

        GameEntity game = new GameEntity();
        game.id = "g";
        when(gameRepo.findById("g")).thenReturn(Optional.of(game));

        GameEnvironmentEntity env = new GameEnvironmentEntity();
        env.id = "env_1";
        env.gameId = "g";
        env.name = "prod";
        when(gameEnvironmentRepo.findById("env_1")).thenReturn(Optional.of(env));
        when(announcementRepo.save(any(AnnouncementEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        AnnouncementEntity scheduled = new AnnouncementEntity();
        scheduled.gameId = "g";
        scheduled.title = "t";
        scheduled.content = "c";
        scheduled.environmentId = "env_1";
        scheduled.scheduledAt = LocalDateTime.now().plusHours(2);
        AnnouncementEntity created = service.create(scheduled, "op");
        assertEquals(AnnouncementEntity.Status.SCHEDULED, created.status);
        assertEquals("env_1", created.environmentId);

        GameEnvironmentEntity deleted = new GameEnvironmentEntity();
        deleted.id = "env_2";
        deleted.gameId = "g";
        deleted.deletedAt = LocalDateTime.now();
        when(gameEnvironmentRepo.findById("env_2")).thenReturn(Optional.of(deleted));
        AnnouncementEntity bad = new AnnouncementEntity();
        bad.gameId = "g";
        bad.title = "t";
        bad.content = "c";
        bad.environmentId = "env_2";
        assertThrows(IllegalArgumentException.class, () -> service.create(bad, "op"));
    }

    // ==================== PlayerExportService：sweep/cleanup/fromJson/download ====================

    @Mock
    private PlayerExportJobRepo playerExportJobRepo;

    @Mock
    private RedeemRecordRepo redeemRecordRepo;

    private PlayerExportService newPlayerExportService() {
        PlayerExportService service = new PlayerExportService();
        ReflectionTestUtils.setField(service, "jobRepo", playerExportJobRepo);
        ReflectionTestUtils.setField(service, "gameRepo", gameRepo);
        ReflectionTestUtils.setField(service, "identityRepo", identityRepo);
        ReflectionTestUtils.setField(service, "paymentRepo", paymentRepo);
        ReflectionTestUtils.setField(service, "loginLogRepo", loginLogRepo);
        ReflectionTestUtils.setField(service, "redeemRecordRepo", redeemRecordRepo);
        ReflectionTestUtils.setField(service, "auditLog", auditLog);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "storageDir", tempDir.toString());
        ReflectionTestUtils.setField(service, "retentionHours", 72);
        return service;
    }

    private PlayerExportJobEntity exportJob(String id, PlayerExportJobEntity.Status status, String sections) {
        PlayerExportJobEntity job = new PlayerExportJobEntity();
        job.id = id;
        job.gameId = "game_demo";
        job.playerId = "p1";
        job.status = status;
        job.sections = sections;
        job.exportFormat = "json";
        job.fileName = id + ".json";
        job.expiresAt = LocalDateTime.now().plusHours(24);
        return job;
    }

    @Test
    @DisplayName("sweep：单任务失败跳过继续处理 + 外层异常兜底")
    void playerExportSweepBranches() {
        PlayerExportService service = newPlayerExportService();

        PlayerExportJobEntity bad = exportJob("pex_bad", PlayerExportJobEntity.Status.COMPLETED, "[\"payments\"]");
        PlayerExportJobEntity good = exportJob("pex_good", PlayerExportJobEntity.Status.PENDING, "[\"payments\"]");
        when(playerExportJobRepo.findByStatusOrderByCreatedAtAsc(PlayerExportJobEntity.Status.PENDING))
            .thenReturn(List.of(bad, good));
        when(playerExportJobRepo.findById("pex_bad")).thenReturn(Optional.of(bad));
        when(playerExportJobRepo.findById("pex_good")).thenReturn(Optional.of(good));
        when(playerExportJobRepo.save(any(PlayerExportJobEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        when(paymentRepo.findByGameIdAndPlayerIdOrderByPaidAtDesc("game_demo", "p1"))
            .thenReturn(List.of());

        service.sweep();

        assertEquals(PlayerExportJobEntity.Status.COMPLETED, good.status);
        assertNotNull(good.filePath);

        when(playerExportJobRepo.findByStatusOrderByCreatedAtAsc(PlayerExportJobEntity.Status.PENDING))
            .thenThrow(new RuntimeException("boom"));
        service.sweep();  // 外层 catch
    }

    @Test
    @DisplayName("cleanup：filePath 为空跳过删除 + 目录删除 IOException + 外层异常")
    void playerExportCleanupBranches() throws Exception {
        PlayerExportService service = newPlayerExportService();

        PlayerExportJobEntity noFile = exportJob("pex_nofile", PlayerExportJobEntity.Status.COMPLETED, null);
        noFile.filePath = null;
        noFile.expiresAt = LocalDateTime.now().minusHours(1);

        Path dir = tempDir.resolve("nonEmptyDir");
        Files.createDirectories(dir);
        Files.write(dir.resolve("f.txt"), "x".getBytes());
        PlayerExportJobEntity dirJob = exportJob("pex_dir", PlayerExportJobEntity.Status.COMPLETED, null);
        dirJob.filePath = dir.toString();
        dirJob.expiresAt = LocalDateTime.now().minusHours(1);

        when(playerExportJobRepo.findByStatusAndExpiresAtBefore(
            eq(PlayerExportJobEntity.Status.COMPLETED), any(LocalDateTime.class)))
            .thenReturn(List.of(noFile, dirJob));
        when(playerExportJobRepo.save(any(PlayerExportJobEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        service.cleanup();

        assertEquals(PlayerExportJobEntity.Status.EXPIRED, noFile.status);
        assertEquals(PlayerExportJobEntity.Status.EXPIRED, dirJob.status);

        when(playerExportJobRepo.findByStatusAndExpiresAtBefore(
            eq(PlayerExportJobEntity.Status.COMPLETED), any(LocalDateTime.class)))
            .thenThrow(new RuntimeException("boom"));
        service.cleanup();  // 外层 catch
    }

    @Test
    @DisplayName("fromJson：sections 空 → 全分区 + 非法 JSON → 失败")
    void playerExportFromJsonBranches() {
        PlayerExportService service = newPlayerExportService();

        PlayerExportJobEntity full = exportJob("pex_full", PlayerExportJobEntity.Status.PENDING, null);
        when(playerExportJobRepo.findById("pex_full")).thenReturn(Optional.of(full));
        when(playerExportJobRepo.save(any(PlayerExportJobEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        when(identityRepo.findByPlayerId("game_demo", "p1")).thenReturn(Optional.empty());
        when(paymentRepo.findByGameIdAndPlayerIdOrderByPaidAtDesc("game_demo", "p1")).thenReturn(List.of());
        when(loginLogRepo.findByGameIdAndPlayerIdOrderByLoginAtDesc("game_demo", "p1")).thenReturn(List.of());
        when(redeemRecordRepo.findByGameIdAndPlayerKeyOrderByRedeemedAtDesc("game_demo", "p1"))
            .thenReturn(List.of());

        PlayerExportJobEntity processed = service.process("pex_full");
        assertEquals(PlayerExportJobEntity.Status.COMPLETED, processed.status);
        assertNotNull(processed.filePath);
        assertTrue(Files.exists(Path.of(processed.filePath)));

        PlayerExportJobEntity badSections =
            exportJob("pex_badjson", PlayerExportJobEntity.Status.PENDING, "not-json");
        when(playerExportJobRepo.findById("pex_badjson")).thenReturn(Optional.of(badSections));
        assertThrows(IllegalStateException.class, () -> service.process("pex_badjson"));
        assertEquals(PlayerExportJobEntity.Status.FAILED, badSections.status);
    }

    @Test
    @DisplayName("download：filePath 缺失与文件不存在分支")
    void playerExportDownloadBranches() {
        PlayerExportService service = newPlayerExportService();

        PlayerExportJobEntity missingPath =
            exportJob("pex_nopath", PlayerExportJobEntity.Status.COMPLETED, null);
        missingPath.filePath = null;
        when(playerExportJobRepo.findById("pex_nopath")).thenReturn(Optional.of(missingPath));
        IllegalStateException noPathEx = assertThrows(IllegalStateException.class,
            () -> service.download("pex_nopath"));
        assertTrue(noPathEx.getMessage().contains("missing"));

        PlayerExportJobEntity goneFile = exportJob("pex_gone", PlayerExportJobEntity.Status.COMPLETED, null);
        goneFile.filePath = tempDir.resolve("nope.json").toString();
        when(playerExportJobRepo.findById("pex_gone")).thenReturn(Optional.of(goneFile));
        IllegalStateException goneEx = assertThrows(IllegalStateException.class,
            () -> service.download("pex_gone"));
        assertTrue(goneEx.getMessage().contains("missing"));
    }
}
