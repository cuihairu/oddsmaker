package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.MLModelEntity;
import io.oddsmaker.control.jpa.MLModelPredictionEntity;
import io.oddsmaker.control.jpa.MLModelRepo;
import io.oddsmaker.control.jpa.ModelPredictionRepo;
import io.oddsmaker.control.jpa.ModelTrainingEntity;
import io.oddsmaker.control.jpa.ModelTrainingRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.LocalDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/**
 * ML 模型与实验平台 Service 深度单元测试（LAY-005 拆分）：全局与模型统计、漂移检测与定时派发、预测生命周期（记录/完成/失败/反馈/查询）、AB 测试与金丝雀、部署流转、训练进度与超时、清理调度。与 MLModelServiceTest / ExperimentServiceTest / TrackingPlanServiceTest / PermissionServiceTest 互补。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ML 模型 Service 深度测试（统计/漂移/预测/部署）")
class MLModelServiceDeepTest {

    // ==================== MLModelService 依赖 ====================

    @Mock
    private MLModelRepo mlModelRepo;

    @Mock
    private ModelTrainingRepo modelTrainingRepo;

    @Mock
    private ModelPredictionRepo modelPredictionRepo;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private WebhookService webhookService;

    // ==================== 被测服务 ====================

    @InjectMocks
    private MLModelService mlModelService;

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
    @DisplayName("scheduledDriftDetection - 检出漂移按 game 维度派发 model_drift webhook")
    @SuppressWarnings("unchecked")
    void scheduledDriftDetection_dispatchesWebhook() {
        MLModelEntity model = mlModel(MLModelEntity.ModelStatus.DEPLOYED);
        lenient().when(mlModelRepo.findAllDeployed()).thenReturn(List.of(model));
        lenient().when(mlModelRepo.findById("ml_1")).thenReturn(Optional.of(model));
        lenient().when(modelPredictionRepo.findByTimeRange(eq("ml_1"), any(LocalDateTime.class), any(LocalDateTime.class)))
            .thenReturn(List.of(
                prediction(MLModelPredictionEntity.FeedbackType.INCORRECT),
                prediction(MLModelPredictionEntity.FeedbackType.INCORRECT)));

        mlModelService.scheduledDriftDetection();

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(webhookService).sendCustomWebhook(eq("game_1"), eq(WebhookService.EVENT_MODEL_DRIFT), payload.capture());
        assertThat(payload.getValue().get("model_id")).isEqualTo("ml_1");
        assertThat(payload.getValue().get("model_name")).isEqualTo("Churn Model");
        assertThat(payload.getValue().get("game_id")).isEqualTo("game_1");
        assertThat(payload.getValue().get("severity")).isEqualTo("CRITICAL");
        assertThat(payload.getValue().get("event_type")).isEqualTo("model_drift");
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

}
