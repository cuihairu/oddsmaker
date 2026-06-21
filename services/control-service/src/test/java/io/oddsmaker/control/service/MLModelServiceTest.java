package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * MLModelService 单元测试
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MLModelService 单元测试")
class MLModelServiceTest {

    @Mock
    private MLModelRepo mlModelRepo;

    @Mock
    private ModelTrainingRepo modelTrainingRepo;

    @Mock
    private ModelPredictionRepo modelPredictionRepo;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private MLModelService mlModelService;

    private MLModelEntity testModel;
    private ModelTrainingEntity testTraining;

    @BeforeEach
    void setUp() {
        testModel = new MLModelEntity();
        testModel.id = "ml_test123";
        testModel.gameId = "game_test123";
        testModel.modelName = "Test Model";
        testModel.modelType = MLModelEntity.ModelType.CLASSIFICATION;
        testModel.modelStatus = MLModelEntity.ModelStatus.DRAFT;
        testModel.version = 1;
        testModel.algorithm = "Random Forest";
        testModel.framework = "Scikit-learn";
        testModel.createdBy = "test_user";
        testModel.createdAt = LocalDateTime.now();

        testTraining = new ModelTrainingEntity();
        testTraining.id = "train_test123";
        testTraining.modelId = "ml_test123";
        testTraining.gameId = "game_test123";
        testTraining.trainingJobName = "Test Training";
        testTraining.trainingStatus = ModelTrainingEntity.TrainingStatus.PENDING;
        testTraining.triggeredBy = "test_user";
        testTraining.createdAt = LocalDateTime.now();
    }

    @Test
    @DisplayName("创建模型 - 成功")
    void createModel_Success() {
        // Given
        when(mlModelRepo.existsByModelNameAndGameIdAndDeletedAtIsNull("Test Model", "game_test123")).thenReturn(false);
        when(mlModelRepo.save(any(MLModelEntity.class))).thenReturn(testModel);
        doNothing().when(auditLogService).logCreate(anyString(), anyString(), anyString(), anyString(), anyString(), any(), any());

        // When
        MLModelEntity result = mlModelService.createModel(
            "game_test123", "Test Model", MLModelEntity.ModelType.CLASSIFICATION,
            "Random Forest", "Scikit-learn", "Test description", "test_user");

        // Then
        assertThat(result).isNotNull();
        assertThat(result.modelName).isEqualTo("Test Model");
        assertThat(result.modelType).isEqualTo(MLModelEntity.ModelType.CLASSIFICATION);
        verify(mlModelRepo, times(1)).save(any(MLModelEntity.class));
    }

    @Test
    @DisplayName("创建模型 - 名称已存在")
    void createModel_NameAlreadyExists() {
        // Given
        when(mlModelRepo.existsByModelNameAndGameIdAndDeletedAtIsNull("Test Model", "game_test123")).thenReturn(true);

        // When & Then
        assertThatThrownBy(() -> mlModelService.createModel(
            "game_test123", "Test Model", MLModelEntity.ModelType.CLASSIFICATION,
            "Random Forest", "Scikit-learn", "Test description", "test_user"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Model name already exists");
    }

    @Test
    @DisplayName("获取模型 - 存在")
    void getModel_Exists() {
        // Given
        when(mlModelRepo.findById("ml_test123")).thenReturn(Optional.of(testModel));

        // When
        MLModelEntity result = mlModelService.getModel("ml_test123");

        // Then
        assertThat(result).isNotNull();
        assertThat(result.modelName).isEqualTo("Test Model");
    }

    @Test
    @DisplayName("获取模型 - 不存在")
    void getModel_NotExists() {
        // Given
        when(mlModelRepo.findById("ml_notexist")).thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> mlModelService.getModel("ml_notexist"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ML model not found");
    }

    @Test
    @DisplayName("获取游戏的模型列表")
    void getGameModels() {
        // Given
        List<MLModelEntity> models = Arrays.asList(testModel);
        when(mlModelRepo.findByGameIdAndDeletedAtIsNull("game_test123")).thenReturn(models);

        // When
        List<MLModelEntity> result = mlModelService.getGameModels("game_test123");

        // Then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).modelName).isEqualTo("Test Model");
    }

    @Test
    @DisplayName("获取已部署的模型")
    void getDeployedModels() {
        // Given
        testModel.modelStatus = MLModelEntity.ModelStatus.DEPLOYED;
        List<MLModelEntity> models = Arrays.asList(testModel);
        when(mlModelRepo.findAllDeployed()).thenReturn(models);

        // When
        List<MLModelEntity> result = mlModelService.getDeployedModels(null);

        // Then
        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("获取已部署的模型 - 按游戏")
    void getDeployedModels_ByGame() {
        // Given
        testModel.modelStatus = MLModelEntity.ModelStatus.DEPLOYED;
        List<MLModelEntity> models = Arrays.asList(testModel);
        when(mlModelRepo.findDeployedByGameId("game_test123")).thenReturn(models);

        // When
        List<MLModelEntity> result = mlModelService.getDeployedModels("game_test123");

        // Then
        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("更新模型 - 成功")
    void updateModel_Success() {
        // Given
        Map<String, Object> updates = new HashMap<>();
        updates.put("description", "Updated description");

        when(mlModelRepo.findById("ml_test123")).thenReturn(Optional.of(testModel));
        when(mlModelRepo.save(any(MLModelEntity.class))).thenReturn(testModel);
        doNothing().when(auditLogService).logUpdate(anyString(), anyString(), anyString(), anyString(), anyString(), any(), any());

        // When
        MLModelEntity result = mlModelService.updateModel("ml_test123", updates, "test_user");

        // Then
        assertThat(result).isNotNull();
        verify(mlModelRepo, times(1)).save(any(MLModelEntity.class));
    }

    @Test
    @DisplayName("归档模型 - 成功")
    void archiveModel_Success() {
        // Given
        testModel.modelStatus = MLModelEntity.ModelStatus.DRAFT;
        when(mlModelRepo.findById("ml_test123")).thenReturn(Optional.of(testModel));
        when(mlModelRepo.save(any(MLModelEntity.class))).thenReturn(testModel);
        doNothing().when(auditLogService).logUpdate(anyString(), anyString(), anyString(), anyString(), anyString(), any(), any());

        // When
        MLModelEntity result = mlModelService.archiveModel("ml_test123", "test_user");

        // Then
        assertThat(result).isNotNull();
        assertThat(result.modelStatus).isEqualTo(MLModelEntity.ModelStatus.ARCHIVED);
        verify(mlModelRepo, times(1)).save(any(MLModelEntity.class));
    }

    @Test
    @DisplayName("归档模型 - 训练中失败")
    void archiveModel_TrainingFails() {
        // Given
        testModel.modelStatus = MLModelEntity.ModelStatus.TRAINING;
        when(mlModelRepo.findById("ml_test123")).thenReturn(Optional.of(testModel));

        // When & Then
        assertThatThrownBy(() -> mlModelService.archiveModel("ml_test123", "test_user"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Cannot archive model while training");
    }

    @Test
    @DisplayName("删除模型 - 成功")
    void deleteModel_Success() {
        // Given
        testModel.modelStatus = MLModelEntity.ModelStatus.DRAFT;
        when(mlModelRepo.findById("ml_test123")).thenReturn(Optional.of(testModel));
        when(mlModelRepo.save(any(MLModelEntity.class))).thenReturn(testModel);
        doNothing().when(auditLogService).logDelete(anyString(), anyString(), anyString(), anyString(), anyString(), any());

        // When
        mlModelService.deleteModel("ml_test123", "test_user");

        // Then
        verify(mlModelRepo, times(1)).save(any(MLModelEntity.class));
        assertThat(testModel.deletedAt).isNotNull();
    }

    @Test
    @DisplayName("删除模型 - 已部署失败")
    void deleteModel_DeployedFails() {
        // Given
        testModel.modelStatus = MLModelEntity.ModelStatus.DEPLOYED;
        when(mlModelRepo.findById("ml_test123")).thenReturn(Optional.of(testModel));

        // When & Then
        assertThatThrownBy(() -> mlModelService.deleteModel("ml_test123", "test_user"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Cannot delete deployed model");
    }

    @Test
    @DisplayName("创建训练任务 - 成功")
    void createTrainingJob_Success() {
        // Given
        testModel.modelStatus = MLModelEntity.ModelStatus.DRAFT;
        when(mlModelRepo.findById("ml_test123")).thenReturn(Optional.of(testModel));
        when(modelTrainingRepo.save(any(ModelTrainingEntity.class))).thenReturn(testTraining);
        when(mlModelRepo.save(any(MLModelEntity.class))).thenReturn(testModel);
        doNothing().when(auditLogService).logCreate(anyString(), anyString(), anyString(), anyString(), anyString(), any(), any());

        Map<String, Object> trainingConfig = new HashMap<>();
        trainingConfig.put("epochs", 100);
        trainingConfig.put("batchSize", 32);

        // When
        ModelTrainingEntity result = mlModelService.createTrainingJob(
            "ml_test123", "Test Training", trainingConfig, null, null, "test_user");

        // Then
        assertThat(result).isNotNull();
        assertThat(result.trainingJobName).isEqualTo("Test Training");
        verify(modelTrainingRepo, times(1)).save(any(ModelTrainingEntity.class));
    }

    @Test
    @DisplayName("创建训练任务 - 模型训练中失败")
    void createTrainingJob_AlreadyTrainingFails() {
        // Given
        testModel.modelStatus = MLModelEntity.ModelStatus.TRAINING;
        when(mlModelRepo.findById("ml_test123")).thenReturn(Optional.of(testModel));

        Map<String, Object> trainingConfig = new HashMap<>();

        // When & Then
        assertThatThrownBy(() -> mlModelService.createTrainingJob(
            "ml_test123", "Test Training", trainingConfig, null, null, "test_user"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Model is already training");
    }

    @Test
    @DisplayName("启动训练任务 - 成功")
    void startTraining_Success() {
        // Given
        when(modelTrainingRepo.findById("train_test123")).thenReturn(Optional.of(testTraining));
        when(modelTrainingRepo.save(any(ModelTrainingEntity.class))).thenReturn(testTraining);

        // When
        ModelTrainingEntity result = mlModelService.startTraining("train_test123");

        // Then
        assertThat(result).isNotNull();
        assertThat(result.trainingStatus).isEqualTo(ModelTrainingEntity.TrainingStatus.RUNNING);
        verify(modelTrainingRepo, times(1)).save(any(ModelTrainingEntity.class));
    }

    @Test
    @DisplayName("启动训练任务 - 非待处理状态失败")
    void startTraining_NotPendingFails() {
        // Given
        testTraining.trainingStatus = ModelTrainingEntity.TrainingStatus.RUNNING;
        when(modelTrainingRepo.findById("train_test123")).thenReturn(Optional.of(testTraining));

        // When & Then
        assertThatThrownBy(() -> mlModelService.startTraining("train_test123"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Training job is not pending");
    }

    @Test
    @DisplayName("完成训练任务 - 成功")
    void completeTraining_Success() {
        // Given
        testTraining.trainingStatus = ModelTrainingEntity.TrainingStatus.RUNNING;
        testTraining.startedAt = LocalDateTime.now();
        when(modelTrainingRepo.findById("train_test123")).thenReturn(Optional.of(testTraining));
        when(modelTrainingRepo.save(any(ModelTrainingEntity.class))).thenReturn(testTraining);
        when(mlModelRepo.findById("ml_test123")).thenReturn(Optional.of(testModel));
        when(mlModelRepo.save(any(MLModelEntity.class))).thenReturn(testModel);

        Map<String, Object> finalMetrics = new HashMap<>();
        finalMetrics.put("accuracy", 0.95);
        finalMetrics.put("f1", 0.93);

        // When
        ModelTrainingEntity result = mlModelService.completeTraining(
            "train_test123", "/models/test.model", finalMetrics);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.trainingStatus).isEqualTo(ModelTrainingEntity.TrainingStatus.COMPLETED);
        verify(modelTrainingRepo, times(1)).save(any(ModelTrainingEntity.class));
    }

    @Test
    @DisplayName("训练失败 - 成功")
    void failTraining_Success() {
        // Given
        testTraining.trainingStatus = ModelTrainingEntity.TrainingStatus.RUNNING;
        testTraining.startedAt = LocalDateTime.now();
        when(modelTrainingRepo.findById("train_test123")).thenReturn(Optional.of(testTraining));
        when(modelTrainingRepo.save(any(ModelTrainingEntity.class))).thenReturn(testTraining);
        when(mlModelRepo.findById("ml_test123")).thenReturn(Optional.of(testModel));
        when(mlModelRepo.save(any(MLModelEntity.class))).thenReturn(testModel);

        // When
        ModelTrainingEntity result = mlModelService.failTraining(
            "train_test123", "Out of memory", "java.lang.OutOfMemoryError");

        // Then
        assertThat(result).isNotNull();
        assertThat(result.trainingStatus).isEqualTo(ModelTrainingEntity.TrainingStatus.FAILED);
        assertThat(result.errorMessage).isEqualTo("Out of memory");
        verify(modelTrainingRepo, times(1)).save(any(ModelTrainingEntity.class));
    }

    @Test
    @DisplayName("取消训练任务 - 成功")
    void cancelTraining_Success() {
        // Given
        testTraining.trainingStatus = ModelTrainingEntity.TrainingStatus.RUNNING;
        when(modelTrainingRepo.findById("train_test123")).thenReturn(Optional.of(testTraining));
        when(modelTrainingRepo.save(any(ModelTrainingEntity.class))).thenReturn(testTraining);
        when(mlModelRepo.findById("ml_test123")).thenReturn(Optional.of(testModel));
        doNothing().when(auditLogService).logUpdate(anyString(), anyString(), anyString(), anyString(), anyString(), any(), any());

        // When
        ModelTrainingEntity result = mlModelService.cancelTraining("train_test123", "test_user");

        // Then
        assertThat(result).isNotNull();
        assertThat(result.trainingStatus).isEqualTo(ModelTrainingEntity.TrainingStatus.CANCELLED);
        verify(modelTrainingRepo, times(1)).save(any(ModelTrainingEntity.class));
    }

    @Test
    @DisplayName("部署模型 - 成功")
    void deployModel_Success() {
        // Given
        testModel.modelStatus = MLModelEntity.ModelStatus.EVALUATING;
        testModel.modelArtifactPath = "/models/test.model";
        when(mlModelRepo.findById("ml_test123")).thenReturn(Optional.of(testModel));
        when(mlModelRepo.save(any(MLModelEntity.class))).thenReturn(testModel);
        doNothing().when(auditLogService).logUpdate(anyString(), anyString(), anyString(), anyString(), anyString(), any(), any());

        Map<String, Object> deploymentConfig = new HashMap<>();
        deploymentConfig.put("servingEndpoint", "http://localhost:8080/predict");

        // When
        MLModelEntity result = mlModelService.deployModel("ml_test123", deploymentConfig, "test_user");

        // Then
        assertThat(result).isNotNull();
        assertThat(result.modelStatus).isEqualTo(MLModelEntity.ModelStatus.DEPLOYED);
        verify(mlModelRepo, times(1)).save(any(MLModelEntity.class));
    }

    @Test
    @DisplayName("部署模型 - 无模型文件失败")
    void deployModel_NoArtifactFails() {
        // Given
        testModel.modelStatus = MLModelEntity.ModelStatus.EVALUATING;
        testModel.modelArtifactPath = null;
        when(mlModelRepo.findById("ml_test123")).thenReturn(Optional.of(testModel));

        Map<String, Object> deploymentConfig = new HashMap<>();

        // When & Then
        assertThatThrownBy(() -> mlModelService.deployModel("ml_test123", deploymentConfig, "test_user"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Model has no artifact");
    }

    @Test
    @DisplayName("配置A/B测试 - 成功")
    void configureAbTest_Success() {
        // Given
        testModel.modelStatus = MLModelEntity.ModelStatus.DEPLOYED;
        when(mlModelRepo.findById("ml_test123")).thenReturn(Optional.of(testModel));
        when(mlModelRepo.save(any(MLModelEntity.class))).thenReturn(testModel);
        doNothing().when(auditLogService).logUpdate(anyString(), anyString(), anyString(), anyString(), anyString(), any(), any());

        Map<String, Object> abTestConfig = new HashMap<>();
        abTestConfig.put("minSampleSize", 1000);

        // When
        MLModelEntity result = mlModelService.configureAbTest(
            "ml_test123", "ml_baseline", 50, abTestConfig, "test_user");

        // Then
        assertThat(result).isNotNull();
        assertThat(result.isAbTest).isTrue();
        assertThat(result.baselineModelId).isEqualTo("ml_baseline");
        assertThat(result.trafficSplit).isEqualTo(50);
        verify(mlModelRepo, times(1)).save(any(MLModelEntity.class));
    }

    @Test
    @DisplayName("停止A/B测试 - 成功")
    void stopAbTest_Success() {
        // Given
        testModel.isAbTest = true;
        testModel.baselineModelId = "ml_baseline";
        testModel.trafficSplit = 50;
        when(mlModelRepo.findById("ml_test123")).thenReturn(Optional.of(testModel));
        when(mlModelRepo.save(any(MLModelEntity.class))).thenReturn(testModel);
        doNothing().when(auditLogService).logUpdate(anyString(), anyString(), anyString(), anyString(), anyString(), any(), any());

        // When
        MLModelEntity result = mlModelService.stopAbTest("ml_test123", true, "test_user");

        // Then
        assertThat(result).isNotNull();
        assertThat(result.isAbTest).isFalse();
        assertThat(result.baselineModelId).isNull();
        assertThat(result.trafficSplit).isEqualTo(0);
        verify(mlModelRepo, times(1)).save(any(MLModelEntity.class));
    }

    @Test
    @DisplayName("记录预测 - 成功")
    void recordPrediction_Success() {
        // Given
        testModel.modelStatus = MLModelEntity.ModelStatus.DEPLOYED;
        when(mlModelRepo.findById("ml_test123")).thenReturn(Optional.of(testModel));
        when(modelPredictionRepo.save(any(MLModelPredictionEntity.class))).thenReturn(new MLModelPredictionEntity());
        when(mlModelRepo.save(any(MLModelEntity.class))).thenReturn(testModel);

        Map<String, Object> inputData = new HashMap<>();
        inputData.put("feature1", 1.0);
        inputData.put("feature2", 2.0);

        // When
        MLModelPredictionEntity result = mlModelService.recordPrediction(
            "ml_test123", "user", "user_123", inputData, "req_123", "client_123", "api");

        // Then
        assertThat(result).isNotNull();
        verify(modelPredictionRepo, times(1)).save(any(MLModelPredictionEntity.class));
    }

    @Test
    @DisplayName("记录预测 - 未部署模型失败")
    void recordPrediction_NotDeployedFails() {
        // Given
        testModel.modelStatus = MLModelEntity.ModelStatus.DRAFT;
        when(mlModelRepo.findById("ml_test123")).thenReturn(Optional.of(testModel));

        Map<String, Object> inputData = new HashMap<>();

        // When & Then
        assertThatThrownBy(() -> mlModelService.recordPrediction(
            "ml_test123", "user", "user_123", inputData, "req_123", "client_123", "api"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Model is not deployed");
    }

    @Test
    @DisplayName("获取模型统计信息")
    void getModelStatistics() {
        // Given
        when(mlModelRepo.findById("ml_test123")).thenReturn(Optional.of(testModel));
        when(modelTrainingRepo.countByModelId("ml_test123")).thenReturn(5L);
        when(modelTrainingRepo.calculateAverageDuration("ml_test123")).thenReturn(3600000.0);
        when(modelTrainingRepo.findRecentByModelId("ml_test123")).thenReturn(Arrays.asList(testTraining));
        when(modelPredictionRepo.countByModelId("ml_test123")).thenReturn(1000L);
        when(modelPredictionRepo.calculateAverageLatency("ml_test123")).thenReturn(50.0);
        when(modelPredictionRepo.countCacheHits("ml_test123")).thenReturn(200L);
        when(modelPredictionRepo.countByFeedbackType("ml_test123")).thenReturn(Arrays.asList());

        // When
        Map<String, Object> stats = mlModelService.getModelStatistics("ml_test123");

        // Then
        assertThat(stats).isNotNull();
        assertThat(stats.get("modelId")).isEqualTo("ml_test123");
        assertThat(stats.get("modelName")).isEqualTo("Test Model");
        assertThat(stats.get("trainingCount")).isEqualTo(5L);
        assertThat(stats.get("predictionTotal")).isEqualTo(1000L);
    }
}
