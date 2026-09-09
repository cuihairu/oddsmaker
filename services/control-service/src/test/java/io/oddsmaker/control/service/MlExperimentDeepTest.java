package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.api.ControlService;
import io.oddsmaker.control.dto.EventDefinitionDTO;
import io.oddsmaker.control.dto.EventPropertyDefinitionDTO;
import io.oddsmaker.control.dto.ExperimentDTO;
import io.oddsmaker.control.dto.TrackingPlanDTO;
import io.oddsmaker.control.experiment.ExperimentEntity;
import io.oddsmaker.control.experiment.ExperimentRepo;
import io.oddsmaker.control.jpa.EventDefinitionEntity;
import io.oddsmaker.control.jpa.EventDefinitionRepo;
import io.oddsmaker.control.jpa.EventPropertyDefinitionEntity;
import io.oddsmaker.control.jpa.EventPropertyDefinitionRepo;
import io.oddsmaker.control.jpa.GameEntity;
import io.oddsmaker.control.jpa.GameEnvironmentEntity;
import io.oddsmaker.control.jpa.GameEnvironmentRepo;
import io.oddsmaker.control.jpa.GameRepo;
import io.oddsmaker.control.jpa.MLModelEntity;
import io.oddsmaker.control.jpa.MLModelPredictionEntity;
import io.oddsmaker.control.jpa.MLModelRepo;
import io.oddsmaker.control.jpa.ModelPredictionRepo;
import io.oddsmaker.control.jpa.ModelTrainingEntity;
import io.oddsmaker.control.jpa.ModelTrainingRepo;
import io.oddsmaker.control.jpa.PermissionEntity;
import io.oddsmaker.control.jpa.PermissionRepo;
import io.oddsmaker.control.jpa.RoleEntity;
import io.oddsmaker.control.jpa.RoleRepo;
import io.oddsmaker.control.jpa.TrackingPlanEntity;
import io.oddsmaker.control.jpa.TrackingPlanRepo;
import io.oddsmaker.control.jpa.UserEntity;
import io.oddsmaker.control.jpa.UserRepo;
import io.oddsmaker.control.jpa.UserRoleEntity;
import io.oddsmaker.control.jpa.UserRoleRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/**
 * ML 模型与实验平台 Service 深度单元测试。
 * 与 MLModelServiceTest / ExperimentServiceTest / TrackingPlanServiceTest / PermissionServiceTest 互补，
 * 覆盖统计、漂移检测、预测生命周期、实验配置校验、追踪计划状态机与 RBAC 环境权限等分支。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ML 模型与实验平台 Service 深度单元测试")
class MlExperimentDeepTest {

    // ==================== 共享依赖 ====================

    @Mock
    private GameRepo gameRepo;

    @Mock
    private GameEnvironmentRepo environmentRepo;

    @Mock
    private AuditLogService auditLogService;

    // ==================== MLModelService 依赖 ====================

    @Mock
    private MLModelRepo mlModelRepo;

    @Mock
    private ModelTrainingRepo modelTrainingRepo;

    @Mock
    private ModelPredictionRepo modelPredictionRepo;

    // ==================== ExperimentService 依赖 ====================

    @Mock
    private ExperimentRepo experimentRepo;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    // ==================== TrackingPlanService 依赖 ====================

    @Mock
    private TrackingPlanRepo trackingPlanRepo;

    @Mock
    private EventDefinitionRepo eventDefinitionRepo;

    @Mock
    private EventPropertyDefinitionRepo propertyDefinitionRepo;

    // ==================== PermissionService 依赖 ====================

    @Mock
    private UserRepo userRepo;

    @Mock
    private RoleRepo roleRepo;

    @Mock
    private PermissionRepo permissionRepo;

    @Mock
    private UserRoleRepo userRoleRepo;

    // ==================== 被测服务 ====================

    @InjectMocks
    private MLModelService mlModelService;

    @InjectMocks
    private ExperimentService experimentService;

    @InjectMocks
    private TrackingPlanService trackingPlanService;

    @InjectMocks
    private PermissionService permissionService;

    // ==================== 测试数据构造 ====================

    private static final String EXP_CONFIG =
        "{\"variants\":[{\"name\":\"control\",\"weight\":5000},{\"name\":\"treatment\",\"weight\":5000}]}";

    private static MLModelEntity mlModel(MLModelEntity.ModelStatus status) {
        MLModelEntity m = new MLModelEntity();
        m.id = "ml_1";
        m.gameId = "game_1";
        m.modelName = "Churn Model";
        m.modelType = MLModelEntity.ModelType.CLASSIFICATION;
        m.modelStatus = status;
        m.version = 1;
        m.createdBy = "tester";
        return m;
    }

    private static MLModelPredictionEntity prediction(MLModelPredictionEntity.FeedbackType feedback) {
        MLModelPredictionEntity p = new MLModelPredictionEntity();
        p.id = "pred_1";
        p.modelId = "ml_1";
        p.feedbackType = feedback;
        return p;
    }

    private static ModelTrainingEntity training(ModelTrainingEntity.TrainingStatus status) {
        ModelTrainingEntity t = new ModelTrainingEntity();
        t.id = "train_1";
        t.modelId = "ml_1";
        t.gameId = "game_1";
        t.trainingJobName = "nightly retrain";
        t.trainingStatus = status;
        return t;
    }

    private static GameEntity game() {
        GameEntity g = new GameEntity();
        g.id = "game_1";
        g.name = "Demo Game";
        return g;
    }

    private static GameEnvironmentEntity env(String id) {
        GameEnvironmentEntity e = new GameEnvironmentEntity();
        e.id = id;
        e.gameId = "game_1";
        e.name = "prod";
        return e;
    }

    private static ExperimentEntity experiment(String status, String configJson) {
        ExperimentEntity e = new ExperimentEntity();
        e.id = "exp_1";
        e.gameId = "game_1";
        e.environmentId = "env_prod";
        e.name = "Old Name";
        e.status = status;
        e.salt = "salt_1";
        e.configJson = configJson;
        e.createdAt = Instant.now();
        e.updatedAt = Instant.now();
        return e;
    }

    private static TrackingPlanEntity plan(String id, TrackingPlanEntity.PlanStatus status) {
        TrackingPlanEntity p = new TrackingPlanEntity();
        p.id = id;
        p.gameId = "game_1";
        p.name = "v1";
        p.status = status;
        return p;
    }

    private static EventDefinitionEntity eventDef(String id, String planId) {
        EventDefinitionEntity e = new EventDefinitionEntity();
        e.id = id;
        e.trackingPlanId = planId;
        e.eventName = "level_complete";
        e.eventType = "progression";
        return e;
    }

    private static UserEntity user(UserEntity.UserStatus status) {
        UserEntity u = new UserEntity();
        u.id = "u1";
        u.username = "alice";
        u.status = status;
        return u;
    }

    private static PermissionEntity permission(String id) {
        PermissionEntity p = new PermissionEntity();
        p.id = id;
        p.enabled = true;
        p.type = PermissionEntity.PermissionType.API;
        p.action = PermissionEntity.PermissionAction.READ;
        p.scope = PermissionEntity.PermissionScope.GLOBAL;
        int colon = id.indexOf(':');
        p.resourceType = colon > 0 ? id.substring(0, colon) : id;
        return p;
    }

    private static RoleEntity role(String id, PermissionEntity... perms) {
        RoleEntity r = new RoleEntity();
        r.id = id;
        r.name = id.toUpperCase();
        r.type = RoleEntity.RoleType.SYSTEM;
        r.enabled = true;
        r.permissions = new HashSet<>(Arrays.asList(perms));
        return r;
    }

    private static UserRoleEntity assignment(String roleId, String gameId, String environment) {
        UserRoleEntity a = new UserRoleEntity();
        a.userId = "u1";
        a.roleId = roleId;
        a.gameId = gameId;
        a.environment = environment;
        a.enabled = true;
        return a;
    }

    // ==================== MLModelService：全局统计 ====================

    @Test
    @DisplayName("getGlobalStatistics - 汇总模型/训练/部署状态分布")
    void getGlobalStatistics_aggregatesAllSources() {
        lenient().when(mlModelRepo.countByStatus())
            .thenReturn(List.<Object[]>of(new Object[]{MLModelEntity.ModelStatus.DEPLOYED, 3L}));
        lenient().when(mlModelRepo.countByType())
            .thenReturn(List.<Object[]>of(new Object[]{MLModelEntity.ModelType.CLASSIFICATION, 2L}));
        lenient().when(modelTrainingRepo.countByStatus())
            .thenReturn(List.<Object[]>of(new Object[]{ModelTrainingEntity.TrainingStatus.COMPLETED, 5L}));
        lenient().when(modelTrainingRepo.calculateTotalGpuHours()).thenReturn(12.5);
        lenient().when(modelTrainingRepo.calculateTotalCpuHours()).thenReturn(40.0);
        lenient().when(mlModelRepo.findAllDeployed()).thenReturn(List.of(mlModel(MLModelEntity.ModelStatus.DEPLOYED)));
        lenient().when(mlModelRepo.findAllAbTestModels()).thenReturn(List.of(mlModel(MLModelEntity.ModelStatus.DEPLOYED)));

        Map<String, Object> stats = mlModelService.getGlobalStatistics();

        assertThat(stats.get("modelsByStatus")).isEqualTo(Map.of("DEPLOYED", 3L));
        assertThat(stats.get("modelsByType")).isEqualTo(Map.of("CLASSIFICATION", 2L));
        assertThat(stats.get("trainingJobsByStatus")).isEqualTo(Map.of("COMPLETED", 5L));
        assertThat(stats.get("totalGpuHours")).isEqualTo(12.5);
        assertThat(stats.get("totalCpuHours")).isEqualTo(40.0);
        assertThat(stats.get("deployedModels")).isEqualTo(1L);
        assertThat(stats.get("abTestModels")).isEqualTo(1L);
    }

    @Test
    @DisplayName("getModelStatistics - 反馈分布与缓存命中率")
    void getModelStatistics_withFeedbackDistribution() {
        MLModelEntity model = mlModel(MLModelEntity.ModelStatus.DEPLOYED);
        model.predictionCount = 10L;
        ModelTrainingEntity job = training(ModelTrainingEntity.TrainingStatus.COMPLETED);
        job.durationMs = null;

        lenient().when(mlModelRepo.findById("ml_1")).thenReturn(Optional.of(model));
        lenient().when(modelTrainingRepo.countByModelId("ml_1")).thenReturn(3L);
        lenient().when(modelTrainingRepo.calculateAverageDuration("ml_1")).thenReturn(1000.0);
        lenient().when(modelTrainingRepo.findRecentByModelId("ml_1")).thenReturn(List.of(job));
        lenient().when(modelPredictionRepo.countByModelId("ml_1")).thenReturn(10L);
        lenient().when(modelPredictionRepo.calculateAverageLatency("ml_1")).thenReturn(25.0);
        lenient().when(modelPredictionRepo.countCacheHits("ml_1")).thenReturn(5L);
        lenient().when(modelPredictionRepo.countByFeedbackType("ml_1")).thenReturn(List.of(
            new Object[]{MLModelPredictionEntity.FeedbackType.CORRECT, 3L},
            new Object[]{MLModelPredictionEntity.FeedbackType.INCORRECT, 1L}));

        Map<String, Object> stats = mlModelService.getModelStatistics("ml_1");

        assertThat(stats.get("modelId")).isEqualTo("ml_1");
        assertThat(stats.get("trainingCount")).isEqualTo(3L);
        assertThat(stats.get("averageTrainingDurationMs")).isEqualTo(1000.0);
        assertThat(stats.get("predictionTotal")).isEqualTo(10L);
        assertThat(stats.get("averageLatencyMs")).isEqualTo(25.0);
        assertThat(stats.get("cacheHitRate")).isEqualTo(0.5);
        assertThat(stats.get("feedbackDistribution")).isEqualTo(Map.of("CORRECT", 3L, "INCORRECT", 1L));
        assertThat(stats.get("accuracyFromFeedback")).isEqualTo(0.75);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> recent = (List<Map<String, Object>>) stats.get("recentTrainings");
        assertThat(recent).hasSize(1);
        assertThat(recent.get(0)).containsEntry("durationMs", 0L);
    }

    @Test
    @DisplayName("getModelStatistics - 零预测量时缓存命中率为 0、反馈准确率为 null")
    void getModelStatistics_zeroPredictions() {
        lenient().when(mlModelRepo.findById("ml_1")).thenReturn(Optional.of(mlModel(MLModelEntity.ModelStatus.DRAFT)));
        lenient().when(modelTrainingRepo.countByModelId("ml_1")).thenReturn(0L);
        lenient().when(modelTrainingRepo.calculateAverageDuration("ml_1")).thenReturn(null);
        lenient().when(modelTrainingRepo.findRecentByModelId("ml_1")).thenReturn(List.of());
        lenient().when(modelPredictionRepo.countByModelId("ml_1")).thenReturn(0L);
        lenient().when(modelPredictionRepo.calculateAverageLatency("ml_1")).thenReturn(null);
        lenient().when(modelPredictionRepo.countCacheHits("ml_1")).thenReturn(0L);
        lenient().when(modelPredictionRepo.countByFeedbackType("ml_1")).thenReturn(List.of());

        Map<String, Object> stats = mlModelService.getModelStatistics("ml_1");

        assertThat(stats.get("cacheHitRate")).isEqualTo(0.0);
        assertThat(stats.get("accuracyFromFeedback")).isNull();
        assertThat(stats.get("predictionTotal")).isEqualTo(0L);
    }

    // ==================== MLModelService：漂移检测 ====================

    @Test
    @DisplayName("detectModelDrift - 窗口内无反馈预测时不检出")
    void detectModelDrift_noFeedbackReturnsNoDrift() {
        MLModelEntity model = mlModel(MLModelEntity.ModelStatus.DEPLOYED);
        model.accuracyMetric = 0.9;
        lenient().when(mlModelRepo.findById("ml_1")).thenReturn(Optional.of(model));
        lenient().when(modelPredictionRepo.findByTimeRange(eq("ml_1"), any(LocalDateTime.class), any(LocalDateTime.class)))
            .thenReturn(List.of());

        Map<String, Object> report = mlModelService.detectModelDrift("ml_1", 24);

        assertThat(report.get("driftDetected")).isEqualTo(false);
        assertThat((String) report.get("message")).contains("No predictions with feedback");
        assertThat(report.get("totalPredictions")).isEqualTo(0);
    }

    @Test
    @DisplayName("detectModelDrift - 准确率骤降 0.9 时判定 CRITICAL")
    void detectModelDrift_criticalSeverity() {
        MLModelEntity model = mlModel(MLModelEntity.ModelStatus.DEPLOYED);
        model.accuracyMetric = 0.9;
        lenient().when(mlModelRepo.findById("ml_1")).thenReturn(Optional.of(model));
        lenient().when(modelPredictionRepo.findByTimeRange(eq("ml_1"), any(LocalDateTime.class), any(LocalDateTime.class)))
            .thenReturn(List.of(
                prediction(MLModelPredictionEntity.FeedbackType.INCORRECT),
                prediction(MLModelPredictionEntity.FeedbackType.INCORRECT)));

        Map<String, Object> report = mlModelService.detectModelDrift("ml_1", 6);

        assertThat(report.get("driftDetected")).isEqualTo(true);
        assertThat(report.get("recentAccuracy")).isEqualTo(0.0);
        assertThat(report.get("baselineAccuracy")).isEqualTo(0.9);
        assertThat(report.get("severity")).isEqualTo("CRITICAL");
        assertThat((String) report.get("recommendation")).contains("retraining");
    }

    @Test
    @DisplayName("detectModelDrift - 准确率下降约 0.12 时判定 HIGH")
    void detectModelDrift_highSeverity() {
        MLModelEntity model = mlModel(MLModelEntity.ModelStatus.DEPLOYED);
        model.accuracyMetric = 0.92;
        List<MLModelPredictionEntity> predictions = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            predictions.add(prediction(MLModelPredictionEntity.FeedbackType.CORRECT));
        }
        predictions.add(prediction(MLModelPredictionEntity.FeedbackType.INCORRECT));
        predictions.add(prediction(MLModelPredictionEntity.FeedbackType.INCORRECT));
        lenient().when(mlModelRepo.findById("ml_1")).thenReturn(Optional.of(model));
        lenient().when(modelPredictionRepo.findByTimeRange(eq("ml_1"), any(LocalDateTime.class), any(LocalDateTime.class)))
            .thenReturn(predictions);

        Map<String, Object> report = mlModelService.detectModelDrift("ml_1", 6);

        assertThat(report.get("driftDetected")).isEqualTo(true);
        assertThat(report.get("severity")).isEqualTo("HIGH");
    }

    @Test
    @DisplayName("detectModelDrift - 准确率下降约 0.08 时判定 MEDIUM")
    void detectModelDrift_mediumSeverity() {
        MLModelEntity model = mlModel(MLModelEntity.ModelStatus.DEPLOYED);
        model.accuracyMetric = 0.88;
        List<MLModelPredictionEntity> predictions = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            predictions.add(prediction(MLModelPredictionEntity.FeedbackType.CORRECT));
        }
        predictions.add(prediction(MLModelPredictionEntity.FeedbackType.INCORRECT));
        predictions.add(prediction(MLModelPredictionEntity.FeedbackType.INCORRECT));
        lenient().when(mlModelRepo.findById("ml_1")).thenReturn(Optional.of(model));
        lenient().when(modelPredictionRepo.findByTimeRange(eq("ml_1"), any(LocalDateTime.class), any(LocalDateTime.class)))
            .thenReturn(predictions);

        Map<String, Object> report = mlModelService.detectModelDrift("ml_1", 6);

        assertThat(report.get("driftDetected")).isEqualTo(true);
        assertThat(report.get("severity")).isEqualTo("MEDIUM");
    }

    @Test
    @DisplayName("detectModelDrift - 基线为空时按 0.5 兜底，全对则不检出")
    void detectModelDrift_nullBaselineDefaultsToHalf() {
        MLModelEntity model = mlModel(MLModelEntity.ModelStatus.DEPLOYED);
        model.accuracyMetric = null;
        lenient().when(mlModelRepo.findById("ml_1")).thenReturn(Optional.of(model));
        lenient().when(modelPredictionRepo.findByTimeRange(eq("ml_1"), any(LocalDateTime.class), any(LocalDateTime.class)))
            .thenReturn(List.of(prediction(MLModelPredictionEntity.FeedbackType.CORRECT)));

        Map<String, Object> report = mlModelService.detectModelDrift("ml_1", 6);

        assertThat(report.get("baselineAccuracy")).isEqualTo(0.5);
        assertThat(report.get("driftDetected")).isEqualTo(false);
        assertThat(report).doesNotContainKey("severity");
    }

    @Test
    @DisplayName("detectModelDrift - 模型不存在抛参数异常")
    void detectModelDrift_modelNotFound() {
        lenient().when(mlModelRepo.findById("ml_x")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> mlModelService.detectModelDrift("ml_x", 6))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ML model not found");
    }

    // ==================== MLModelService：预测生命周期 ====================

    @Test
    @DisplayName("recordPrediction - A/B 与金丝雀模型标记预测并递增计数")
    void recordPrediction_marksAbTestAndCanary() {
        MLModelEntity model = mlModel(MLModelEntity.ModelStatus.DEPLOYED);
        model.isAbTest = true;
        model.trafficSplit = 100;
        model.canaryDeployment = true;
        model.predictionCount = 0L;
        lenient().when(mlModelRepo.findById("ml_1")).thenReturn(Optional.of(model));
        lenient().when(modelPredictionRepo.save(any(MLModelPredictionEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(mlModelRepo.save(any(MLModelEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        MLModelPredictionEntity result = mlModelService.recordPrediction(
            "ml_1", "user", "user_1", Map.of("feature", 1.0), "req_1", "client_1", "api");

        assertThat(result.modelId).isEqualTo("ml_1");
        assertThat(result.modelVersion).isEqualTo(1);
        assertThat(result.gameId).isEqualTo("game_1");
        assertThat(result.isAbTest()).isTrue();
        assertThat(result.abTestGroup).isEqualTo("treatment");
        assertThat(result.isCanary()).isTrue();
        assertThat(result.inputData).contains("feature");
        assertThat(model.predictionCount).isEqualTo(1L);
        assertThat(model.lastPredictionAt).isNotNull();
    }

    @Test
    @DisplayName("completePrediction - 填充分类/概率/Top-N/特征重要度/解释字段")
    void completePrediction_populatesOutputFields() {
        MLModelPredictionEntity p = prediction(null);
        lenient().when(modelPredictionRepo.findById("pred_1")).thenReturn(Optional.of(p));
        lenient().when(modelPredictionRepo.save(any(MLModelPredictionEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> output = new HashMap<>();
        output.put("class", "win");
        output.put("probability", 0.87);
        output.put("topPredictions", List.of(Map.of("label", "win")));
        output.put("featureImportance", Map.of("f1", 0.4));
        output.put("explanation", "dominant feature");

        MLModelPredictionEntity result = mlModelService.completePrediction("pred_1", output, 0.9, 42);

        assertThat(result.predictionStatus).isEqualTo(MLModelPredictionEntity.PredictionStatus.COMPLETED);
        assertThat(result.predictionClass).isEqualTo("win");
        assertThat(result.predictionProbability).isEqualTo(0.87);
        assertThat(result.predictionScore).isEqualTo(0.9);
        assertThat(result.topPredictions).contains("win");
        assertThat(result.featureImportance).contains("f1");
        assertThat(result.explanation).isEqualTo("dominant feature");
        assertThat(result.latencyMs).isEqualTo(42);
        assertThat(result.completedAt).isNotNull();
    }

    @Test
    @DisplayName("completePrediction - output 为 null 时仅写入分数")
    void completePrediction_nullOutput() {
        MLModelPredictionEntity p = prediction(null);
        lenient().when(modelPredictionRepo.findById("pred_1")).thenReturn(Optional.of(p));
        lenient().when(modelPredictionRepo.save(any(MLModelPredictionEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        MLModelPredictionEntity result = mlModelService.completePrediction("pred_1", null, 0.5, 10);

        assertThat(result.predictionStatus).isEqualTo(MLModelPredictionEntity.PredictionStatus.COMPLETED);
        assertThat(result.outputPrediction).isNull();
        assertThat(result.predictionClass).isNull();
        assertThat(result.latencyMs).isEqualTo(10);
    }

    @Test
    @DisplayName("completePrediction - 预测不存在抛参数异常")
    void completePrediction_notFound() {
        lenient().when(modelPredictionRepo.findById("pred_x")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> mlModelService.completePrediction("pred_x", Map.of(), 0.5, 10))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Prediction not found");
    }

    @Test
    @DisplayName("failPrediction - 写入错误码与错误信息")
    void failPrediction_setsErrorFields() {
        MLModelPredictionEntity p = prediction(null);
        lenient().when(modelPredictionRepo.findById("pred_1")).thenReturn(Optional.of(p));
        lenient().when(modelPredictionRepo.save(any(MLModelPredictionEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        MLModelPredictionEntity result = mlModelService.failPrediction("pred_1", "TIMEOUT_ERR", "request timeout");

        assertThat(result.predictionStatus).isEqualTo(MLModelPredictionEntity.PredictionStatus.FAILED);
        assertThat(result.errorCode).isEqualTo("TIMEOUT_ERR");
        assertThat(result.errorMessage).isEqualTo("request timeout");
    }

    @Test
    @DisplayName("addPredictionFeedback - 写入反馈类型/实际值/反馈人")
    void addPredictionFeedback_success() {
        MLModelPredictionEntity p = prediction(null);
        lenient().when(modelPredictionRepo.findById("pred_1")).thenReturn(Optional.of(p));
        lenient().when(modelPredictionRepo.save(any(MLModelPredictionEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        MLModelPredictionEntity result = mlModelService.addPredictionFeedback(
            "pred_1", MLModelPredictionEntity.FeedbackType.CORRECT, "win", "analyst");

        assertThat(result.feedbackType).isEqualTo(MLModelPredictionEntity.FeedbackType.CORRECT);
        assertThat(result.actualValue).isEqualTo("win");
        assertThat(result.feedbackBy).isEqualTo("analyst");
        assertThat(result.feedbackAt).isNotNull();
    }

    @Test
    @DisplayName("addPredictionFeedback - 预测不存在抛参数异常")
    void addPredictionFeedback_notFound() {
        lenient().when(modelPredictionRepo.findById("pred_x")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> mlModelService.addPredictionFeedback(
            "pred_x", MLModelPredictionEntity.FeedbackType.CORRECT, "win", "analyst"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Prediction not found");
    }

    @Test
    @DisplayName("getPrediction - 不存在抛参数异常")
    void getPrediction_notFound() {
        lenient().when(modelPredictionRepo.findById("pred_x")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> mlModelService.getPrediction("pred_x"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Prediction not found");
    }

    @Test
    @DisplayName("getPredictionHistory - 按 limit 截断最近预测")
    void getPredictionHistory_appliesLimit() {
        lenient().when(modelPredictionRepo.findRecentByModelId("ml_1")).thenReturn(List.of(
            prediction(null), prediction(null), prediction(null)));

        List<MLModelPredictionEntity> result = mlModelService.getPredictionHistory("ml_1", 2);

        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("getPredictionsByTimeRange - 透传时间范围查询")
    void getPredictionsByTimeRange_delegates() {
        LocalDateTime start = LocalDateTime.now().minusHours(1);
        LocalDateTime end = LocalDateTime.now();
        List<MLModelPredictionEntity> expected = List.of(prediction(null));
        lenient().when(modelPredictionRepo.findByTimeRange("ml_1", start, end)).thenReturn(expected);

        assertThat(mlModelService.getPredictionsByTimeRange("ml_1", start, end)).isSameAs(expected);
    }

    // ==================== MLModelService：部署与 A/B 测试 ====================

    @Test
    @DisplayName("deployModel - 非 EVALUATING/STAGING 状态拒绝部署")
    void deployModel_invalidStatus() {
        MLModelEntity model = mlModel(MLModelEntity.ModelStatus.DRAFT);
        model.modelArtifactPath = "/models/m.bin";
        lenient().when(mlModelRepo.findById("ml_1")).thenReturn(Optional.of(model));

        assertThatThrownBy(() -> mlModelService.deployModel("ml_1", null, "tester"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("EVALUATING or STAGING");
    }

    @Test
    @DisplayName("deployModel - STAGING 可部署并支持金丝雀配置")
    void deployModel_fromStagingWithCanaryConfig() {
        MLModelEntity model = mlModel(MLModelEntity.ModelStatus.STAGING);
        model.modelArtifactPath = "/models/m.bin";
        lenient().when(mlModelRepo.findById("ml_1")).thenReturn(Optional.of(model));
        lenient().when(mlModelRepo.save(any(MLModelEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> config = new HashMap<>();
        config.put("servingEndpoint", "http://serving/predict");
        config.put("canaryDeployment", true);

        MLModelEntity result = mlModelService.deployModel("ml_1", config, "tester");

        assertThat(result.modelStatus).isEqualTo(MLModelEntity.ModelStatus.DEPLOYED);
        assertThat(result.lastDeployedAt).isNotNull();
        assertThat(result.servingEndpoint).isEqualTo("http://serving/predict");
        assertThat(result.canaryDeployment).isTrue();
        assertThat(result.deploymentConfig).contains("servingEndpoint");
    }

    @Test
    @DisplayName("configureAbTest - 未部署模型拒绝配置")
    void configureAbTest_notDeployedThrows() {
        lenient().when(mlModelRepo.findById("ml_1"))
            .thenReturn(Optional.of(mlModel(MLModelEntity.ModelStatus.DRAFT)));

        assertThatThrownBy(() -> mlModelService.configureAbTest("ml_1", "ml_base", 50, null, "tester"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("must be deployed");
    }

    @Test
    @DisplayName("stopAbTest - 清空 A/B 字段")
    void stopAbTest_clearsFields() {
        MLModelEntity model = mlModel(MLModelEntity.ModelStatus.DEPLOYED);
        model.isAbTest = true;
        model.baselineModelId = "ml_base";
        model.trafficSplit = 50;
        model.abTestConfig = "{\"k\":1}";
        lenient().when(mlModelRepo.findById("ml_1")).thenReturn(Optional.of(model));
        lenient().when(mlModelRepo.save(any(MLModelEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        MLModelEntity result = mlModelService.stopAbTest("ml_1", false, "tester");

        assertThat(result.isAbTest).isFalse();
        assertThat(result.baselineModelId).isNull();
        assertThat(result.trafficSplit).isEqualTo(0);
        assertThat(result.abTestConfig).isNull();
        verify(mlModelRepo).save(model);
    }

    // ==================== MLModelService：模型配置更新 ====================

    @Test
    @DisplayName("updateModel - 序列化全部配置分区")
    void updateModel_serializesAllConfigSections() {
        MLModelEntity model = mlModel(MLModelEntity.ModelStatus.DRAFT);
        lenient().when(mlModelRepo.findById("ml_1")).thenReturn(Optional.of(model));
        lenient().when(mlModelRepo.save(any(MLModelEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> updates = new HashMap<>();
        updates.put("description", "updated desc");
        updates.put("modelConfig", Map.of("trees", 100));
        updates.put("hyperparameters", Map.of("lr", 0.01));
        updates.put("featureConfig", Map.of("features", 10));
        updates.put("inputSchema", Map.of("type", "object"));
        updates.put("outputSchema", Map.of("type", "array"));
        updates.put("trainingConfig", Map.of("epochs", 50));
        updates.put("retrainPolicy", Map.of("monthly", true));

        MLModelEntity result = mlModelService.updateModel("ml_1", updates, "tester");

        assertThat(result.description).isEqualTo("updated desc");
        assertThat(result.modelConfig).contains("trees");
        assertThat(result.hyperparameters).contains("lr");
        assertThat(result.featureConfig).contains("features");
        assertThat(result.inputSchema).contains("object");
        assertThat(result.outputSchema).contains("array");
        assertThat(result.trainingConfig).contains("epochs");
        assertThat(result.retrainPolicy).contains("monthly");
    }

    // ==================== MLModelService：训练进度与查找 ====================

    @Test
    @DisplayName("updateTrainingProgress - 运行中任务写入进度与指标")
    void updateTrainingProgress_success() {
        ModelTrainingEntity job = training(ModelTrainingEntity.TrainingStatus.RUNNING);
        lenient().when(modelTrainingRepo.findById("train_1")).thenReturn(Optional.of(job));
        lenient().when(modelTrainingRepo.save(any(ModelTrainingEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ModelTrainingEntity result = mlModelService.updateTrainingProgress("train_1", 5, 10, 0.25, Map.of("auc", 0.88));

        assertThat(result.currentEpoch).isEqualTo(5);
        assertThat(result.trainingEpochs).isEqualTo(10);
        assertThat(result.progressPercent).isEqualTo(50);
        assertThat(result.trainingMetrics).contains("auc");
    }

    @Test
    @DisplayName("updateTrainingProgress - 非运行中任务抛状态异常")
    void updateTrainingProgress_notRunningThrows() {
        lenient().when(modelTrainingRepo.findById("train_1"))
            .thenReturn(Optional.of(training(ModelTrainingEntity.TrainingStatus.PENDING)));

        assertThatThrownBy(() -> mlModelService.updateTrainingProgress("train_1", 1, 10, 0.5, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not running");
    }

    @Test
    @DisplayName("getTrainingJob - 不存在抛参数异常")
    void getTrainingJob_notFound() {
        lenient().when(modelTrainingRepo.findById("train_x")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> mlModelService.getTrainingJob("train_x"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Training job not found");
    }

    // ==================== MLModelService：定时任务 ====================

    @Test
    @DisplayName("cleanupExpiredPredictions - 删除 90 天前预测")
    void cleanupExpiredPredictions_deletesOldRecords() {
        mlModelService.cleanupExpiredPredictions();

        verify(modelPredictionRepo).deleteByCreatedAtBefore(any(LocalDateTime.class));
    }

    @Test
    @DisplayName("detectTimedOutTrainings - 超时任务与模型均置为 FAILED")
    void detectTimedOutTrainings_failsModelAndJob() {
        ModelTrainingEntity job = training(ModelTrainingEntity.TrainingStatus.RUNNING);
        job.startedAt = LocalDateTime.now().minusHours(25);
        MLModelEntity model = mlModel(MLModelEntity.ModelStatus.TRAINING);
        lenient().when(modelTrainingRepo.findTimedOutJobs(any(LocalDateTime.class))).thenReturn(List.of(job));
        lenient().when(modelTrainingRepo.save(any(ModelTrainingEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(mlModelRepo.findById("ml_1")).thenReturn(Optional.of(model));
        lenient().when(mlModelRepo.save(any(MLModelEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        mlModelService.detectTimedOutTrainings();

        assertThat(job.trainingStatus).isEqualTo(ModelTrainingEntity.TrainingStatus.FAILED);
        assertThat(job.errorMessage).contains("timed out");
        assertThat(model.modelStatus).isEqualTo(MLModelEntity.ModelStatus.FAILED);
    }

    @Test
    @DisplayName("scheduledDriftDetection - 单个模型异常不影响整体执行")
    void scheduledDriftDetection_toleratesFailures() {
        MLModelEntity ok = mlModel(MLModelEntity.ModelStatus.DEPLOYED);
        ok.id = "ml_ok";
        MLModelEntity broken = mlModel(MLModelEntity.ModelStatus.DEPLOYED);
        broken.id = "ml_broken";
        lenient().when(mlModelRepo.findAllDeployed()).thenReturn(List.of(ok, broken));
        lenient().when(mlModelRepo.findById("ml_ok")).thenReturn(Optional.of(ok));
        lenient().when(mlModelRepo.findById("ml_broken")).thenReturn(Optional.empty());
        lenient().when(modelPredictionRepo.findByTimeRange(eq("ml_ok"), any(LocalDateTime.class), any(LocalDateTime.class)))
            .thenReturn(List.of());

        assertThatCode(() -> mlModelService.scheduledDriftDetection()).doesNotThrowAnyException();
    }

    // ==================== ExperimentService：创建 ====================

    @Test
    @DisplayName("createExperiment - 默认生成 id/salt，状态为 draft")
    void createExperiment_success_defaults() throws Exception {
        GameEnvironmentEntity prod = env("env_prod");
        ExperimentDTO dto = new ExperimentDTO();
        dto.gameId = "game_1";
        dto.environment = "prod";
        dto.name = "Checkout Test";
        dto.config = objectMapper.readTree(EXP_CONFIG);
        lenient().when(gameRepo.findById("game_1")).thenReturn(Optional.of(game()));
        lenient().when(environmentRepo.findByGameIdAndNameAndDeletedAtIsNull("game_1", "prod"))
            .thenReturn(List.of(prod));
        lenient().when(experimentRepo.existsById(anyString())).thenReturn(false);
        lenient().when(experimentRepo.save(any(ExperimentEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(environmentRepo.findById("env_prod")).thenReturn(Optional.of(prod));

        ExperimentDTO created = experimentService.createExperiment(dto);

        assertThat(created.id).startsWith("exp_");
        assertThat(created.status).isEqualTo("draft");
        assertThat(created.environment).isEqualTo("prod");
        assertThat(created.environmentId).isEqualTo("env_prod");
        assertThat(created.salt).isEqualTo(created.id);
        assertThat(created.config.has("variants")).isTrue();
    }

    @Test
    @DisplayName("createExperiment - 显式 id 重复抛参数异常")
    void createExperiment_duplicateIdThrows() {
        ExperimentDTO dto = new ExperimentDTO();
        dto.id = "exp_dup";
        dto.gameId = "game_1";
        dto.environment = "prod";
        dto.name = "Dup";
        lenient().when(gameRepo.findById("game_1")).thenReturn(Optional.of(game()));
        lenient().when(environmentRepo.findByGameIdAndNameAndDeletedAtIsNull("game_1", "prod"))
            .thenReturn(List.of(env("env_prod")));
        lenient().when(experimentRepo.existsById("exp_dup")).thenReturn(true);

        assertThatThrownBy(() -> experimentService.createExperiment(dto))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("already exists");
    }

    @Test
    @DisplayName("createExperiment - 游戏不存在抛参数异常")
    void createExperiment_gameNotFound() {
        lenient().when(gameRepo.findById("game_x")).thenReturn(Optional.empty());
        ExperimentDTO dto = new ExperimentDTO();
        dto.gameId = "game_x";

        assertThatThrownBy(() -> experimentService.createExperiment(dto))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Game not found");
    }

    @Test
    @DisplayName("createExperiment - 环境不存在抛参数异常")
    void createExperiment_environmentNotFound() {
        lenient().when(gameRepo.findById("game_1")).thenReturn(Optional.of(game()));
        lenient().when(environmentRepo.findByGameIdAndNameAndDeletedAtIsNull("game_1", "prod"))
            .thenReturn(List.of());
        ExperimentDTO dto = new ExperimentDTO();
        dto.gameId = "game_1";
        dto.environment = "prod";
        dto.name = "X";

        assertThatThrownBy(() -> experimentService.createExperiment(dto))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Environment not found");
    }

    @Test
    @DisplayName("createExperiment - running 状态缺少 variants 抛参数异常")
    void createExperiment_runningWithoutVariantsThrows() {
        ExperimentDTO dto = new ExperimentDTO();
        dto.gameId = "game_1";
        dto.environment = "prod";
        dto.name = "X";
        dto.status = "running";
        lenient().when(gameRepo.findById("game_1")).thenReturn(Optional.of(game()));
        lenient().when(environmentRepo.findByGameIdAndNameAndDeletedAtIsNull("game_1", "prod"))
            .thenReturn(List.of(env("env_prod")));
        lenient().when(experimentRepo.existsById(anyString())).thenReturn(false);

        assertThatThrownBy(() -> experimentService.createExperiment(dto))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Running experiment requires variants");
    }

    @Test
    @DisplayName("createExperiment - 非法状态抛参数异常")
    void createExperiment_unsupportedStatusThrows() {
        ExperimentDTO dto = new ExperimentDTO();
        dto.gameId = "game_1";
        dto.environment = "prod";
        dto.name = "X";
        dto.status = "live";
        lenient().when(gameRepo.findById("game_1")).thenReturn(Optional.of(game()));
        lenient().when(environmentRepo.findByGameIdAndNameAndDeletedAtIsNull("game_1", "prod"))
            .thenReturn(List.of(env("env_prod")));
        lenient().when(experimentRepo.existsById(anyString())).thenReturn(false);

        assertThatThrownBy(() -> experimentService.createExperiment(dto))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported experiment status");
    }

    @Test
    @DisplayName("createExperiment - 空名称抛参数异常")
    void createExperiment_blankNameThrows() {
        ExperimentDTO dto = new ExperimentDTO();
        dto.gameId = "game_1";
        dto.environment = "prod";
        dto.name = "   ";
        lenient().when(gameRepo.findById("game_1")).thenReturn(Optional.of(game()));
        lenient().when(environmentRepo.findByGameIdAndNameAndDeletedAtIsNull("game_1", "prod"))
            .thenReturn(List.of(env("env_prod")));
        lenient().when(experimentRepo.existsById(anyString())).thenReturn(false);

        assertThatThrownBy(() -> experimentService.createExperiment(dto))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("name is required");
    }

    @Test
    @DisplayName("validateConfig - variants 少于两个抛参数异常")
    void createExperiment_variantsNeedTwo() throws Exception {
        ExperimentDTO dto = baseExperimentDto();
        dto.status = "running";
        dto.config = objectMapper.readTree("{\"variants\":[{\"name\":\"control\",\"weight\":1}]}");

        assertThatThrownBy(() -> experimentService.createExperiment(dto))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at least two variants");
    }

    @Test
    @DisplayName("validateConfig - 变体名重复抛参数异常")
    void createExperiment_duplicateVariantNamesThrows() throws Exception {
        ExperimentDTO dto = baseExperimentDto();
        dto.status = "running";
        dto.config = objectMapper.readTree(
            "{\"variants\":[{\"name\":\"control\",\"weight\":1},{\"name\":\"control\",\"weight\":1}]}");

        assertThatThrownBy(() -> experimentService.createExperiment(dto))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must be unique");
    }

    @Test
    @DisplayName("validateConfig - 变体权重非正整数抛参数异常")
    void createExperiment_invalidWeightThrows() throws Exception {
        ExperimentDTO dto = baseExperimentDto();
        dto.status = "running";
        dto.config = objectMapper.readTree(
            "{\"variants\":[{\"name\":\"control\",\"weight\":0},{\"name\":\"treatment\",\"weight\":1}]}");

        assertThatThrownBy(() -> experimentService.createExperiment(dto))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("positive integer");
    }

    @Test
    @DisplayName("validateConfig - targeting 非对象抛参数异常")
    void createExperiment_targetingMustBeObject() throws Exception {
        ExperimentDTO dto = baseExperimentDto();
        dto.config = objectMapper.readTree("{\"targeting\":\"everyone\"}");

        assertThatThrownBy(() -> experimentService.createExperiment(dto))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("targeting must be a JSON object");
    }

    private ExperimentDTO baseExperimentDto() {
        ExperimentDTO dto = new ExperimentDTO();
        dto.gameId = "game_1";
        dto.environment = "prod";
        dto.name = "Validation";
        lenient().when(gameRepo.findById("game_1")).thenReturn(Optional.of(game()));
        lenient().when(environmentRepo.findByGameIdAndNameAndDeletedAtIsNull("game_1", "prod"))
            .thenReturn(List.of(env("env_prod")));
        lenient().when(experimentRepo.existsById(anyString())).thenReturn(false);
        return dto;
    }

    // ==================== ExperimentService：更新/发布/暂停 ====================

    @Test
    @DisplayName("updateExperiment - 重命名并发布为 running")
    void updateExperiment_success_renamesAndPublishes() {
        ExperimentEntity entity = experiment("draft", EXP_CONFIG);
        lenient().when(experimentRepo.findById("exp_1")).thenReturn(Optional.of(entity));
        lenient().when(experimentRepo.save(any(ExperimentEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(environmentRepo.findById("env_prod")).thenReturn(Optional.of(env("env_prod")));

        ExperimentDTO dto = new ExperimentDTO();
        dto.name = "New Name";
        dto.status = "running";

        ExperimentDTO updated = experimentService.updateExperiment("exp_1", dto);

        assertThat(updated.status).isEqualTo("running");
        assertThat(updated.name).isEqualTo("New Name");
        assertThat(entity.updatedAt).isNotNull();
    }

    @Test
    @DisplayName("updateExperiment - 实验不存在抛参数异常")
    void updateExperiment_notFoundThrows() {
        lenient().when(experimentRepo.findById("exp_x")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> experimentService.updateExperiment("exp_x", new ExperimentDTO()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Experiment not found");
    }

    @Test
    @DisplayName("updateExperiment - 更换游戏抛参数异常")
    void updateExperiment_gameChangeThrows() {
        ExperimentEntity entity = experiment("draft", EXP_CONFIG);
        lenient().when(experimentRepo.findById("exp_1")).thenReturn(Optional.of(entity));

        ExperimentDTO dto = new ExperimentDTO();
        dto.gameId = "game_2";

        assertThatThrownBy(() -> experimentService.updateExperiment("exp_1", dto))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("game cannot be changed");
    }

    @Test
    @DisplayName("updateExperiment - 更换环境抛参数异常")
    void updateExperiment_environmentChangeThrows() {
        ExperimentEntity entity = experiment("draft", EXP_CONFIG);
        lenient().when(experimentRepo.findById("exp_1")).thenReturn(Optional.of(entity));
        lenient().when(environmentRepo.findById("env_other")).thenReturn(Optional.of(env("env_other")));

        ExperimentDTO dto = new ExperimentDTO();
        dto.environmentId = "env_other";

        assertThatThrownBy(() -> experimentService.updateExperiment("exp_1", dto))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("environment cannot be changed");
    }

    @Test
    @DisplayName("updateExperiment - 切换 running 但缺 variants 抛参数异常")
    void updateExperiment_runningWithoutVariantsThrows() {
        ExperimentEntity entity = experiment("draft", "{}");
        lenient().when(experimentRepo.findById("exp_1")).thenReturn(Optional.of(entity));

        ExperimentDTO dto = new ExperimentDTO();
        dto.status = "running";

        assertThatThrownBy(() -> experimentService.updateExperiment("exp_1", dto))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Running experiment requires variants");
    }

    @Test
    @DisplayName("publishExperiment - 校验通过后置为 running")
    void publishExperiment_success() {
        ExperimentEntity entity = experiment("draft", EXP_CONFIG);
        lenient().when(experimentRepo.findById("exp_1")).thenReturn(Optional.of(entity));
        lenient().when(experimentRepo.save(any(ExperimentEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(environmentRepo.findById("env_prod")).thenReturn(Optional.of(env("env_prod")));

        ExperimentDTO published = experimentService.publishExperiment("exp_1");

        assertThat(published.status).isEqualTo("running");
        assertThat(entity.updatedAt).isNotNull();
    }

    @Test
    @DisplayName("publishExperiment - 缺少 variants 拒绝发布")
    void publishExperiment_invalidConfigThrows() {
        ExperimentEntity entity = experiment("draft", "{\"metrics\":{}}");
        lenient().when(experimentRepo.findById("exp_1")).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> experimentService.publishExperiment("exp_1"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Running experiment requires variants");
    }

    @Test
    @DisplayName("publishExperiment - configJson 非法 JSON 抛状态异常")
    void publishExperiment_invalidJsonThrows() {
        ExperimentEntity entity = experiment("draft", "{{invalid-json");
        lenient().when(experimentRepo.findById("exp_1")).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> experimentService.publishExperiment("exp_1"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not valid JSON");
    }

    @Test
    @DisplayName("pauseExperiment - 置为 paused")
    void pauseExperiment_success() {
        ExperimentEntity entity = experiment("running", EXP_CONFIG);
        lenient().when(experimentRepo.findById("exp_1")).thenReturn(Optional.of(entity));
        lenient().when(experimentRepo.save(any(ExperimentEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(environmentRepo.findById("env_prod")).thenReturn(Optional.of(env("env_prod")));

        ExperimentDTO paused = experimentService.pauseExperiment("exp_1");

        assertThat(paused.status).isEqualTo("paused");
    }

    @Test
    @DisplayName("pauseExperiment - 实验不存在抛参数异常")
    void pauseExperiment_notFoundThrows() {
        lenient().when(experimentRepo.findById("exp_x")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> experimentService.pauseExperiment("exp_x"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Experiment not found");
    }

    // ==================== ExperimentService：列表与分流 ====================

    @Test
    @DisplayName("listExperiments - 仅按环境名过滤时必须提供 gameId")
    void listExperiments_environmentNameRequiresGameId() {
        assertThatThrownBy(() -> experimentService.listExperiments(null, null, "prod", null, 0, 10))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("gameId is required");
    }

    @Test
    @DisplayName("listExperiments - 返回分页 DTO")
    void listExperiments_success() {
        ExperimentEntity entity = experiment("running", EXP_CONFIG);
        PageImpl<ExperimentEntity> page = new PageImpl<>(List.of(entity), PageRequest.of(0, 10), 1);
        lenient().when(experimentRepo.search(any(), any(), any(), any(Pageable.class))).thenReturn(page);
        lenient().when(environmentRepo.findById("env_prod")).thenReturn(Optional.of(env("env_prod")));

        ControlService.Paged<ExperimentDTO> result =
            experimentService.listExperiments("game_1", null, null, null, 0, 10);

        assertThat(result.items).hasSize(1);
        assertThat(result.items.get(0).id).isEqualTo("exp_1");
        assertThat(result.total).isEqualTo(1L);
    }

    @Test
    @DisplayName("assign - 对 subjectId 做 trim 后分流")
    void assign_trimsSubjectId() {
        ExperimentEntity entity = experiment("running", EXP_CONFIG);
        lenient().when(experimentRepo.findById("exp_1")).thenReturn(Optional.of(entity));

        String assigned = experimentService.assign("exp_1", "  user_42  ");

        assertThat(assigned).isIn("control", "treatment");
        assertThat(experimentService.assign("exp_1", "user_42")).isEqualTo(assigned);
    }

    // ==================== TrackingPlanService：计划生命周期 ====================

    @Test
    @DisplayName("createTrackingPlan - 成功创建草稿计划")
    void createTrackingPlan_success() {
        lenient().when(gameRepo.findById("game_1")).thenReturn(Optional.of(game()));
        lenient().when(trackingPlanRepo.findByGameIdAndDeletedAtIsNullOrderByCreatedAtDesc("game_1"))
            .thenReturn(List.of());
        lenient().when(trackingPlanRepo.save(any(TrackingPlanEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        TrackingPlanDTO dto = new TrackingPlanDTO();
        dto.name = "2026-Q1";
        dto.displayName = "2026 Q1 Plan";
        dto.version = "1.0.0";

        TrackingPlanDTO created = trackingPlanService.createTrackingPlan("game_1", dto);

        assertThat(created.id).startsWith("tp_");
        assertThat(created.status).isEqualTo(TrackingPlanEntity.PlanStatus.DRAFT);
        assertThat(created.gameId).isEqualTo("game_1");
        assertThat(created.totalEvents).isZero();
    }

    @Test
    @DisplayName("createTrackingPlan - 游戏不存在抛参数异常")
    void createTrackingPlan_gameNotFound() {
        lenient().when(gameRepo.findById("game_x")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> trackingPlanService.createTrackingPlan("game_x", new TrackingPlanDTO()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Game not found");
    }

    @Test
    @DisplayName("createTrackingPlan - 名称重复抛参数异常")
    void createTrackingPlan_duplicateNameThrows() {
        lenient().when(gameRepo.findById("game_1")).thenReturn(Optional.of(game()));
        lenient().when(trackingPlanRepo.findByGameIdAndDeletedAtIsNullOrderByCreatedAtDesc("game_1"))
            .thenReturn(List.of(plan("tp_0", TrackingPlanEntity.PlanStatus.ACTIVE)));

        TrackingPlanDTO dto = new TrackingPlanDTO();
        dto.name = "v1";

        assertThatThrownBy(() -> trackingPlanService.createTrackingPlan("game_1", dto))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("name already exists");
    }

    @Test
    @DisplayName("createTrackingPlan - 绑定存在环境成功")
    void createTrackingPlan_withEnvironment() {
        lenient().when(gameRepo.findById("game_1")).thenReturn(Optional.of(game()));
        lenient().when(trackingPlanRepo.findByGameIdAndDeletedAtIsNullOrderByCreatedAtDesc("game_1"))
            .thenReturn(List.of());
        lenient().when(environmentRepo.findById("env_1")).thenReturn(Optional.of(env("env_1")));
        lenient().when(trackingPlanRepo.save(any(TrackingPlanEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        TrackingPlanDTO dto = new TrackingPlanDTO();
        dto.name = "prod-plan";
        dto.environmentId = "env_1";

        TrackingPlanDTO created = trackingPlanService.createTrackingPlan("game_1", dto);

        assertThat(created.environmentId).isEqualTo("env_1");
    }

    @Test
    @DisplayName("createTrackingPlan - 绑定不存在环境抛参数异常")
    void createTrackingPlan_environmentNotFound() {
        lenient().when(gameRepo.findById("game_1")).thenReturn(Optional.of(game()));
        lenient().when(trackingPlanRepo.findByGameIdAndDeletedAtIsNullOrderByCreatedAtDesc("game_1"))
            .thenReturn(List.of());
        lenient().when(environmentRepo.findById("env_x")).thenReturn(Optional.empty());

        TrackingPlanDTO dto = new TrackingPlanDTO();
        dto.name = "prod-plan";
        dto.environmentId = "env_x";

        assertThatThrownBy(() -> trackingPlanService.createTrackingPlan("game_1", dto))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Environment not found");
    }

    @Test
    @DisplayName("updateTrackingPlan - 草稿可更新展示字段")
    void updateTrackingPlan_success() {
        TrackingPlanEntity draft = plan("tp_1", TrackingPlanEntity.PlanStatus.DRAFT);
        lenient().when(trackingPlanRepo.findByIdAndDeletedAtIsNull("tp_1")).thenReturn(Optional.of(draft));
        lenient().when(trackingPlanRepo.save(any(TrackingPlanEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        TrackingPlanDTO dto = new TrackingPlanDTO();
        dto.displayName = "New Display";
        dto.description = "updated";
        dto.version = "1.1.0";

        TrackingPlanDTO updated = trackingPlanService.updateTrackingPlan("tp_1", dto);

        assertThat(updated.displayName).isEqualTo("New Display");
        assertThat(updated.version).isEqualTo("1.1.0");
        assertThat(draft.updatedAt).isNotNull();
    }

    @Test
    @DisplayName("updateTrackingPlan - 非草稿状态抛状态异常")
    void updateTrackingPlan_notDraftThrows() {
        lenient().when(trackingPlanRepo.findByIdAndDeletedAtIsNull("tp_1"))
            .thenReturn(Optional.of(plan("tp_1", TrackingPlanEntity.PlanStatus.ACTIVE)));

        assertThatThrownBy(() -> trackingPlanService.updateTrackingPlan("tp_1", new TrackingPlanDTO()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Only draft tracking plans can be edited");
    }

    @Test
    @DisplayName("updateTrackingPlan - 计划不存在抛参数异常")
    void updateTrackingPlan_notFoundThrows() {
        lenient().when(trackingPlanRepo.findByIdAndDeletedAtIsNull("tp_x")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> trackingPlanService.updateTrackingPlan("tp_x", new TrackingPlanDTO()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Tracking plan not found");
    }

    @Test
    @DisplayName("activateTrackingPlan - 激活并刷新事件计数")
    void activateTrackingPlan_success_updatesCounts() {
        TrackingPlanEntity draft = plan("tp_1", TrackingPlanEntity.PlanStatus.DRAFT);
        EventDefinitionEntity active = eventDef("ed_a", "tp_1");
        EventDefinitionEntity disabled = eventDef("ed_b", "tp_1");
        disabled.status = EventDefinitionEntity.DefinitionStatus.DISABLED;
        lenient().when(trackingPlanRepo.findByIdAndDeletedAtIsNull("tp_1")).thenReturn(Optional.of(draft));
        lenient().when(trackingPlanRepo.save(any(TrackingPlanEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(eventDefinitionRepo.countByTrackingPlanId("tp_1")).thenReturn(3L);
        lenient().when(eventDefinitionRepo.findActiveByTrackingPlanId("tp_1"))
            .thenReturn(List.of(active, disabled));

        TrackingPlanDTO result = trackingPlanService.activateTrackingPlan("tp_1", "ops");

        assertThat(result.status).isEqualTo(TrackingPlanEntity.PlanStatus.ACTIVE);
        assertThat(result.activatedBy).isEqualTo("ops");
        assertThat(result.activatedAt).isNotNull();
        assertThat(result.totalEvents).isEqualTo(3);
        assertThat(result.activeEvents).isEqualTo(1);
    }

    @Test
    @DisplayName("activateTrackingPlan - 非草稿状态抛状态异常")
    void activateTrackingPlan_notDraftThrows() {
        lenient().when(trackingPlanRepo.findByIdAndDeletedAtIsNull("tp_1"))
            .thenReturn(Optional.of(plan("tp_1", TrackingPlanEntity.PlanStatus.ACTIVE)));

        assertThatThrownBy(() -> trackingPlanService.activateTrackingPlan("tp_1", "ops"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Only draft plans can be activated");
    }

    @Test
    @DisplayName("deactivateTrackingPlan - 活跃计划置为弃用")
    void deactivateTrackingPlan_success() {
        TrackingPlanEntity active = plan("tp_1", TrackingPlanEntity.PlanStatus.ACTIVE);
        lenient().when(trackingPlanRepo.findByIdAndDeletedAtIsNull("tp_1")).thenReturn(Optional.of(active));
        lenient().when(trackingPlanRepo.save(any(TrackingPlanEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        TrackingPlanDTO result = trackingPlanService.deactivateTrackingPlan("tp_1");

        assertThat(result.status).isEqualTo(TrackingPlanEntity.PlanStatus.DEPRECATED);
        assertThat(result.deactivatedAt).isNotNull();
    }

    @Test
    @DisplayName("deactivateTrackingPlan - 非活跃状态抛状态异常")
    void deactivateTrackingPlan_notActiveThrows() {
        lenient().when(trackingPlanRepo.findByIdAndDeletedAtIsNull("tp_1"))
            .thenReturn(Optional.of(plan("tp_1", TrackingPlanEntity.PlanStatus.DRAFT)));

        assertThatThrownBy(() -> trackingPlanService.deactivateTrackingPlan("tp_1"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Only active plans can be deactivated");
    }

    @Test
    @DisplayName("deleteTrackingPlan - 活跃计划拒绝删除")
    void deleteTrackingPlan_activeThrows() {
        lenient().when(trackingPlanRepo.findByIdAndDeletedAtIsNull("tp_1"))
            .thenReturn(Optional.of(plan("tp_1", TrackingPlanEntity.PlanStatus.ACTIVE)));

        assertThatThrownBy(() -> trackingPlanService.deleteTrackingPlan("tp_1"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Cannot delete active tracking plan");
    }

    @Test
    @DisplayName("deleteTrackingPlan - 软删除并级联弃用事件定义")
    void deleteTrackingPlan_cascadesToEventDefinitions() {
        TrackingPlanEntity deprecated = plan("tp_1", TrackingPlanEntity.PlanStatus.DEPRECATED);
        EventDefinitionEntity def = eventDef("ed_1", "tp_1");
        lenient().when(trackingPlanRepo.findByIdAndDeletedAtIsNull("tp_1")).thenReturn(Optional.of(deprecated));
        lenient().when(trackingPlanRepo.save(any(TrackingPlanEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(eventDefinitionRepo.findByTrackingPlanIdAndDeletedAtIsNullOrderByEventNameAsc("tp_1"))
            .thenReturn(List.of(def));
        lenient().when(eventDefinitionRepo.save(any(EventDefinitionEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        trackingPlanService.deleteTrackingPlan("tp_1");

        assertThat(deprecated.deletedAt).isNotNull();
        assertThat(deprecated.status).isEqualTo(TrackingPlanEntity.PlanStatus.DEPRECATED);
        assertThat(def.deletedAt).isNotNull();
        assertThat(def.status).isEqualTo(EventDefinitionEntity.DefinitionStatus.DEPRECATED);
        verify(eventDefinitionRepo).save(def);
    }

    @Test
    @DisplayName("getTrackingPlan - 回填游戏与环境名称")
    void getTrackingPlan_enrichesGameInfo() {
        TrackingPlanEntity p = plan("tp_1", TrackingPlanEntity.PlanStatus.DRAFT);
        p.environmentId = "env_1";
        lenient().when(trackingPlanRepo.findByIdAndDeletedAtIsNull("tp_1")).thenReturn(Optional.of(p));
        lenient().when(gameRepo.findById("game_1")).thenReturn(Optional.of(game()));
        lenient().when(environmentRepo.findById("env_1")).thenReturn(Optional.of(env("env_1")));

        Optional<TrackingPlanDTO> dto = trackingPlanService.getTrackingPlan("tp_1");

        assertThat(dto).isPresent();
        assertThat(dto.get().gameName).isEqualTo("Demo Game");
        assertThat(dto.get().environmentName).isEqualTo("prod");
    }

    @Test
    @DisplayName("listEventDefinitions - 映射为 DTO 列表")
    void listEventDefinitions_mapsDtos() {
        lenient().when(trackingPlanRepo.findByIdAndDeletedAtIsNull("tp_1"))
            .thenReturn(Optional.of(plan("tp_1", TrackingPlanEntity.PlanStatus.DRAFT)));
        lenient().when(eventDefinitionRepo.findByTrackingPlanIdAndDeletedAtIsNullOrderByEventNameAsc("tp_1"))
            .thenReturn(List.of(eventDef("ed_a", "tp_1"), eventDef("ed_b", "tp_1")));

        assertThat(trackingPlanService.listEventDefinitions("tp_1")).hasSize(2);
    }

    // ==================== TrackingPlanService：事件定义 ====================

    @Test
    @DisplayName("createEventDefinition - 草稿计划内创建并刷新计数")
    void createEventDefinition_success() {
        TrackingPlanEntity draft = plan("tp_1", TrackingPlanEntity.PlanStatus.DRAFT);
        lenient().when(trackingPlanRepo.findByIdAndDeletedAtIsNull("tp_1")).thenReturn(Optional.of(draft));
        lenient().when(eventDefinitionRepo.existsByTrackingPlanIdAndEventName("tp_1", "level_start"))
            .thenReturn(false);
        lenient().when(eventDefinitionRepo.save(any(EventDefinitionEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(eventDefinitionRepo.countByTrackingPlanId("tp_1")).thenReturn(1L);
        lenient().when(trackingPlanRepo.save(any(TrackingPlanEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        EventDefinitionDTO dto = new EventDefinitionDTO();
        dto.eventName = "level_start";
        dto.eventType = "progression";

        EventDefinitionDTO created = trackingPlanService.createEventDefinition("tp_1", dto);

        assertThat(created.id).startsWith("ed_");
        assertThat(created.eventName).isEqualTo("level_start");
        assertThat(created.usageCount).isZero();
        assertThat(draft.totalEvents).isEqualTo(1);
    }

    @Test
    @DisplayName("createEventDefinition - 非草稿计划抛状态异常")
    void createEventDefinition_notDraftThrows() {
        lenient().when(trackingPlanRepo.findByIdAndDeletedAtIsNull("tp_1"))
            .thenReturn(Optional.of(plan("tp_1", TrackingPlanEntity.PlanStatus.ACTIVE)));

        assertThatThrownBy(() -> trackingPlanService.createEventDefinition("tp_1", new EventDefinitionDTO()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("draft tracking plans");
    }

    @Test
    @DisplayName("createEventDefinition - 事件名重复抛参数异常")
    void createEventDefinition_duplicateEventNameThrows() {
        lenient().when(trackingPlanRepo.findByIdAndDeletedAtIsNull("tp_1"))
            .thenReturn(Optional.of(plan("tp_1", TrackingPlanEntity.PlanStatus.DRAFT)));
        lenient().when(eventDefinitionRepo.existsByTrackingPlanIdAndEventName("tp_1", "level_complete"))
            .thenReturn(true);

        EventDefinitionDTO dto = new EventDefinitionDTO();
        dto.eventName = "level_complete";
        dto.eventType = "progression";

        assertThatThrownBy(() -> trackingPlanService.createEventDefinition("tp_1", dto))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Event name already exists");
    }

    @Test
    @DisplayName("updateEventDefinition - 草稿计划内更新展示名")
    void updateEventDefinition_success() {
        EventDefinitionEntity def = eventDef("ed_1", "tp_1");
        lenient().when(eventDefinitionRepo.findById("ed_1")).thenReturn(Optional.of(def));
        lenient().when(trackingPlanRepo.findByIdAndDeletedAtIsNull("tp_1"))
            .thenReturn(Optional.of(plan("tp_1", TrackingPlanEntity.PlanStatus.DRAFT)));
        lenient().when(eventDefinitionRepo.save(any(EventDefinitionEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        EventDefinitionDTO dto = new EventDefinitionDTO();
        dto.displayName = "Level Completed";
        dto.description = "desc";

        EventDefinitionDTO updated = trackingPlanService.updateEventDefinition("ed_1", dto);

        assertThat(updated.displayName).isEqualTo("Level Completed");
        assertThat(def.updatedAt).isNotNull();
    }

    @Test
    @DisplayName("updateEventDefinition - 非草稿计划抛状态异常")
    void updateEventDefinition_notDraftThrows() {
        lenient().when(eventDefinitionRepo.findById("ed_1")).thenReturn(Optional.of(eventDef("ed_1", "tp_1")));
        lenient().when(trackingPlanRepo.findByIdAndDeletedAtIsNull("tp_1"))
            .thenReturn(Optional.of(plan("tp_1", TrackingPlanEntity.PlanStatus.ACTIVE)));

        assertThatThrownBy(() -> trackingPlanService.updateEventDefinition("ed_1", new EventDefinitionDTO()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("draft tracking plans");
    }

    @Test
    @DisplayName("updateEventDefinition - 事件不存在抛参数异常")
    void updateEventDefinition_notFoundThrows() {
        lenient().when(eventDefinitionRepo.findById("ed_x")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> trackingPlanService.updateEventDefinition("ed_x", new EventDefinitionDTO()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Event definition not found");
    }

    // ==================== TrackingPlanService：属性定义 ====================

    @Test
    @DisplayName("createPropertyDefinition - 非草稿计划抛状态异常")
    void createPropertyDefinition_notDraftThrows() {
        lenient().when(eventDefinitionRepo.findById("ed_1")).thenReturn(Optional.of(eventDef("ed_1", "tp_1")));
        lenient().when(trackingPlanRepo.findByIdAndDeletedAtIsNull("tp_1"))
            .thenReturn(Optional.of(plan("tp_1", TrackingPlanEntity.PlanStatus.ACTIVE)));

        assertThatThrownBy(() -> trackingPlanService.createPropertyDefinition("ed_1", new EventPropertyDefinitionDTO()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("draft tracking plans");
    }

    @Test
    @DisplayName("createPropertyDefinition - 属性名重复抛参数异常")
    void createPropertyDefinition_duplicatePropertyNameThrows() {
        lenient().when(eventDefinitionRepo.findById("ed_1")).thenReturn(Optional.of(eventDef("ed_1", "tp_1")));
        lenient().when(trackingPlanRepo.findByIdAndDeletedAtIsNull("tp_1"))
            .thenReturn(Optional.of(plan("tp_1", TrackingPlanEntity.PlanStatus.DRAFT)));
        lenient().when(propertyDefinitionRepo.findByEventDefinitionIdAndPropertyName("ed_1", "tier"))
            .thenReturn(Optional.of(new EventPropertyDefinitionEntity()));

        EventPropertyDefinitionDTO dto = new EventPropertyDefinitionDTO();
        dto.propertyName = "tier";
        dto.type = EventPropertyDefinitionEntity.PropertyType.STRING;

        assertThatThrownBy(() -> trackingPlanService.createPropertyDefinition("ed_1", dto))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Property name already exists");
    }

    @Test
    @DisplayName("updatePropertyDefinition - 属性属于其他事件时抛参数异常")
    void updatePropertyDefinition_wrongEventThrows() {
        lenient().when(eventDefinitionRepo.findById("ed_1")).thenReturn(Optional.of(eventDef("ed_1", "tp_1")));
        lenient().when(trackingPlanRepo.findByIdAndDeletedAtIsNull("tp_1"))
            .thenReturn(Optional.of(plan("tp_1", TrackingPlanEntity.PlanStatus.DRAFT)));
        EventPropertyDefinitionEntity other = new EventPropertyDefinitionEntity();
        other.id = "epd_1";
        other.eventDefinitionId = "ed_other";
        lenient().when(propertyDefinitionRepo.findById("epd_1")).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> trackingPlanService.updatePropertyDefinition(
            "ed_1", "epd_1", new EventPropertyDefinitionDTO()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Property definition not found");
    }

    // ==================== PermissionService：环境权限 ====================

    @Test
    @DisplayName("hasEnvironmentPermission - 环境级角色授权通过")
    void hasEnvironmentPermission_viaEnvironmentRole() {
        lenient().when(userRepo.findById("u1")).thenReturn(Optional.of(user(UserEntity.UserStatus.ACTIVE)));
        lenient().when(userRoleRepo.findByUserIdAndGameIdAndEnvironment("u1", "g1", "prod"))
            .thenReturn(List.of(assignment("operator", "g1", "prod")));
        lenient().when(roleRepo.findById("operator"))
            .thenReturn(Optional.of(role("operator", permission("game:read"))));

        assertThat(permissionService.hasEnvironmentPermission("u1", "g1", "prod", "game:read")).isTrue();
    }

    @Test
    @DisplayName("hasEnvironmentPermission - 回退到游戏级角色授权通过")
    void hasEnvironmentPermission_viaGameRole() {
        lenient().when(userRepo.findById("u1")).thenReturn(Optional.of(user(UserEntity.UserStatus.ACTIVE)));
        lenient().when(userRoleRepo.findByUserIdAndGameIdAndEnvironment("u1", "g1", "prod"))
            .thenReturn(List.of());
        lenient().when(userRoleRepo.findByUserIdAndGameId("u1", "g1"))
            .thenReturn(List.of(assignment("operator", "g1", null)));
        lenient().when(roleRepo.findById("operator"))
            .thenReturn(Optional.of(role("operator", permission("game:read"))));

        assertThat(permissionService.hasEnvironmentPermission("u1", "g1", "prod", "game:read")).isTrue();
    }

    @Test
    @DisplayName("hasEnvironmentPermission - 回退到全局角色授权通过")
    void hasEnvironmentPermission_viaGlobalRole() {
        lenient().when(userRepo.findById("u1")).thenReturn(Optional.of(user(UserEntity.UserStatus.ACTIVE)));
        lenient().when(userRoleRepo.findByUserIdAndGameIdAndEnvironment("u1", "g1", "prod"))
            .thenReturn(List.of());
        lenient().when(userRoleRepo.findByUserIdAndGameId("u1", "g1")).thenReturn(List.of());
        lenient().when(userRoleRepo.findGlobalByUserId("u1"))
            .thenReturn(List.of(assignment("operator", null, null)));
        lenient().when(roleRepo.findById("operator"))
            .thenReturn(Optional.of(role("operator", permission("game:read"))));

        assertThat(permissionService.hasEnvironmentPermission("u1", "g1", "prod", "game:read")).isTrue();
    }

    @Test
    @DisplayName("hasEnvironmentPermission - 无匹配角色返回 false")
    void hasEnvironmentPermission_denied() {
        lenient().when(userRepo.findById("u1")).thenReturn(Optional.of(user(UserEntity.UserStatus.ACTIVE)));
        lenient().when(userRoleRepo.findByUserIdAndGameIdAndEnvironment("u1", "g1", "prod"))
            .thenReturn(List.of());
        lenient().when(userRoleRepo.findByUserIdAndGameId("u1", "g1")).thenReturn(List.of());
        lenient().when(userRoleRepo.findGlobalByUserId("u1")).thenReturn(List.of());

        assertThat(permissionService.hasEnvironmentPermission("u1", "g1", "prod", "game:read")).isFalse();
    }

    @Test
    @DisplayName("hasEnvironmentPermission - 用户未激活返回 false")
    void hasEnvironmentPermission_inactiveUser() {
        lenient().when(userRepo.findById("u1")).thenReturn(Optional.of(user(UserEntity.UserStatus.INACTIVE)));

        assertThat(permissionService.hasEnvironmentPermission("u1", "g1", "prod", "game:read")).isFalse();
    }

    @Test
    @DisplayName("getEnvironmentPermissions - 合并环境/游戏/全局三层权限")
    void getEnvironmentPermissions_mergesAllScopes() {
        lenient().when(userRepo.findById("u1")).thenReturn(Optional.of(user(UserEntity.UserStatus.ACTIVE)));
        lenient().when(userRoleRepo.findByUserIdAndGameIdAndEnvironment("u1", "g1", "prod"))
            .thenReturn(List.of(assignment("env_role", "g1", "prod")));
        lenient().when(userRoleRepo.findByUserIdAndGameId("u1", "g1"))
            .thenReturn(List.of(assignment("game_role", "g1", null)));
        lenient().when(userRoleRepo.findGlobalByUserId("u1"))
            .thenReturn(List.of(assignment("global_role", null, null)));
        lenient().when(roleRepo.findById("env_role")).thenReturn(Optional.of(role("env_role", permission("p_env"))));
        lenient().when(roleRepo.findById("game_role")).thenReturn(Optional.of(role("game_role", permission("p_game"))));
        lenient().when(roleRepo.findById("global_role")).thenReturn(Optional.of(role("global_role", permission("p_global"))));

        Set<String> permissions = permissionService.getEnvironmentPermissions("u1", "g1", "prod");

        assertThat(permissions).containsExactlyInAnyOrder("p_env", "p_game", "p_global");
    }

    // ==================== PermissionService：资源动作权限 ====================

    @Test
    @DisplayName("hasPermission(resourceType, action) - 命中权限定义后按权限 ID 检查")
    void hasPermission_byResourceAndAction_found() {
        PermissionEntity read = permission("game:read");
        lenient().when(permissionRepo.findByResourceTypeAndAction("game", PermissionEntity.PermissionAction.READ))
            .thenReturn(Optional.of(read));
        lenient().when(userRepo.findById("u1")).thenReturn(Optional.of(user(UserEntity.UserStatus.ACTIVE)));
        lenient().when(userRoleRepo.findValidByUserId(eq("u1"), any(LocalDateTime.class)))
            .thenReturn(List.of(assignment("operator", null, null)));
        lenient().when(roleRepo.findById("operator"))
            .thenReturn(Optional.of(role("operator", read)));

        assertThat(permissionService.hasPermission("u1", "game", PermissionEntity.PermissionAction.READ)).isTrue();
    }

    @Test
    @DisplayName("hasPermission(resourceType, action) - 权限定义缺失直接返回 false")
    void hasPermission_byResourceAndAction_permissionMissing() {
        lenient().when(permissionRepo.findByResourceTypeAndAction("game", PermissionEntity.PermissionAction.EXPORT))
            .thenReturn(Optional.empty());

        assertThat(permissionService.hasPermission("u1", "game", PermissionEntity.PermissionAction.EXPORT)).isFalse();
    }

    // ==================== PermissionService：角色分配 ====================

    @Test
    @DisplayName("assignRole - 用户不存在抛参数异常")
    void assignRole_userNotFoundThrows() {
        lenient().when(userRepo.existsById("u_x")).thenReturn(false);

        assertThatThrownBy(() -> permissionService.assignRole("u_x", "operator", null, null, "admin"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("User not found");
    }

    @Test
    @DisplayName("assignRole - 角色不存在抛参数异常")
    void assignRole_roleNotFoundThrows() {
        lenient().when(userRepo.existsById("u1")).thenReturn(true);
        lenient().when(roleRepo.existsById("r_x")).thenReturn(false);

        assertThatThrownBy(() -> permissionService.assignRole("u1", "r_x", null, null, "admin"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Role not found");
    }

    @Test
    @DisplayName("assignRole - 已失效的历史分配允许重新分配")
    void assignRole_reassignAfterDisabled() {
        UserRoleEntity disabled = assignment("operator", null, null);
        disabled.enabled = false;
        lenient().when(userRepo.existsById("u1")).thenReturn(true);
        lenient().when(roleRepo.existsById("operator")).thenReturn(true);
        lenient().when(userRoleRepo.findByUserIdAndRoleId("u1", "operator"))
            .thenReturn(Optional.of(disabled));
        lenient().when(userRoleRepo.save(any(UserRoleEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        UserRoleEntity saved = permissionService.assignRole("u1", "operator", "g1", "prod", "admin");

        assertThat(saved.userId).isEqualTo("u1");
        assertThat(saved.roleId).isEqualTo("operator");
        assertThat(saved.gameId).isEqualTo("g1");
        assertThat(saved.environment).isEqualTo("prod");
        assertThat(saved.assignedBy).isEqualTo("admin");
        assertThat(saved.enabled).isTrue();
        verify(userRoleRepo).save(any(UserRoleEntity.class));
    }

    @Test
    @DisplayName("listAssignments - 用户不存在抛参数异常")
    void listAssignments_userNotFoundThrows() {
        lenient().when(userRepo.existsById("u_x")).thenReturn(false);

        assertThatThrownBy(() -> permissionService.listAssignments("u_x"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("User not found");
    }

    @Test
    @DisplayName("listAssignments - 输出 global/game/environment 三种 scope")
    void listAssignments_reportsScopes() {
        UserRoleEntity global = assignment("viewer", null, null);
        UserRoleEntity gameScoped = assignment("operator", "g1", null);
        UserRoleEntity envScoped = assignment("analyst", "g1", "prod");
        lenient().when(userRepo.existsById("u1")).thenReturn(true);
        lenient().when(userRoleRepo.findByUserId("u1"))
            .thenReturn(List.of(global, gameScoped, envScoped));
        lenient().when(roleRepo.findById("viewer")).thenReturn(Optional.of(role("viewer")));
        lenient().when(roleRepo.findById("operator")).thenReturn(Optional.empty());
        lenient().when(roleRepo.findById("analyst")).thenReturn(Optional.of(role("analyst")));

        List<Map<String, Object>> assignments = permissionService.listAssignments("u1");

        assertThat(assignments).hasSize(3);
        assertThat(assignments.get(0))
            .containsEntry("scope", "global")
            .containsEntry("roleName", "VIEWER")
            .containsEntry("valid", true);
        assertThat(assignments.get(1))
            .containsEntry("scope", "game")
            .containsEntry("roleName", "operator");
        assertThat(assignments.get(2))
            .containsEntry("scope", "environment")
            .containsEntry("gameId", "g1")
            .containsEntry("environment", "prod");
    }

    @Test
    @DisplayName("getUserPermissions - 跳过权限集合为空的角色")
    void getUserPermissions_skipsRoleWithoutPermissions() {
        RoleEntity noPerms = role("r2");
        noPerms.permissions = null;
        lenient().when(userRoleRepo.findValidByUserId(eq("u1"), any(LocalDateTime.class)))
            .thenReturn(List.of(assignment("r1", null, null), assignment("r2", null, null)));
        lenient().when(roleRepo.findById("r1")).thenReturn(Optional.of(role("r1", permission("p_one"))));
        lenient().when(roleRepo.findById("r2")).thenReturn(Optional.of(noPerms));

        Set<String> permissions = permissionService.getUserPermissions("u1");

        assertThat(permissions).containsExactly("p_one");
    }

    @Test
    @DisplayName("hasGamePermission - 用户被锁定返回 false")
    void hasGamePermission_lockedUser() {
        lenient().when(userRepo.findById("u1")).thenReturn(Optional.of(user(UserEntity.UserStatus.LOCKED)));

        assertThat(permissionService.hasGamePermission("u1", "g1", "game:read")).isFalse();
    }

    @Test
    @DisplayName("getGamePermissions - 跳过过期分配，仅保留全局权限")
    void getGamePermissions_skipsExpiredAssignment() {
        UserRoleEntity expired = assignment("game_role", "g1", null);
        expired.expiresAt = LocalDateTime.now().minusDays(1);
        lenient().when(userRoleRepo.findByUserIdAndGameId("u1", "g1")).thenReturn(List.of(expired));
        lenient().when(userRoleRepo.findGlobalByUserId("u1"))
            .thenReturn(List.of(assignment("global_role", null, null)));
        lenient().when(roleRepo.findById("game_role"))
            .thenReturn(Optional.of(role("game_role", permission("p_game"))));
        lenient().when(roleRepo.findById("global_role"))
            .thenReturn(Optional.of(role("global_role", permission("p_global"))));

        Set<String> permissions = permissionService.getGamePermissions("u1", "g1");

        assertThat(permissions).containsExactly("p_global");
    }
}
