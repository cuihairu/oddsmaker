package io.oddsmaker.control.service;

import io.oddsmaker.control.experiment.ExperimentEntity;
import io.oddsmaker.control.jpa.MLModelEntity;
import io.oddsmaker.control.jpa.MLModelRepo;
import io.oddsmaker.control.jpa.ModelPredictionRepo;
import io.oddsmaker.control.jpa.ModelTrainingEntity;
import io.oddsmaker.control.jpa.ModelTrainingRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Service 层覆盖率收尾测试（LAY-006 拆分）：训练完成回填模型指标、作业不存在与非法状态、创建训练作业序列化配置、训练历史委托。与 MLModelServiceTest / MLModelServiceDeepTest 等互补。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ML 模型 Service 深度测试（训练完成/作业状态）")
class ServicesFinalSweepMLModelTest {

    @Mock
    private MLModelRepo mlModelRepo;

    @Mock
    private ModelTrainingRepo modelTrainingRepo;

    @Mock
    private ModelPredictionRepo modelPredictionRepo;

    @Mock
    private AuditLogService auditLog;

    @Mock
    private WebhookService webhookService;

    @InjectMocks
    private MLModelService mlModelService;

    private static ExperimentEntity experimentEntity(String status) {
        ExperimentEntity e = new ExperimentEntity();
        e.id = "exp_1";
        e.gameId = "game_1";
        e.environmentId = "env_game_1_prod";
        e.name = "Sweep Exp";
        e.status = "running".equals(status) ? ExperimentEntity.ExperimentStatus.LIVE : ExperimentEntity.ExperimentStatus.valueOf(status.toUpperCase());
        e.salt = "salt";
        e.configJson = "{\"variants\":[{\"name\":\"control\",\"weight\":1},"
            + "{\"name\":\"treatment\",\"weight\":1}]}";
        e.createdAt = LocalDateTime.now();
        e.updatedAt = LocalDateTime.now();
        return e;
    }

    @Test
    @DisplayName("ML：completeTraining 把五项指标写回模型并递增版本")
    void mlCompleteTrainingPopulatesModelMetrics() {
        ModelTrainingEntity training = new ModelTrainingEntity();
        training.id = "train_1";
        training.modelId = "ml_1";
        training.trainingJobName = "nightly";
        training.trainingStatus = ModelTrainingEntity.TrainingStatus.RUNNING;
        training.startedAt = LocalDateTime.now();
        training.triggeredBy = "tester";

        MLModelEntity model = new MLModelEntity();
        model.id = "ml_1";
        model.modelName = "Churn";
        model.version = 1;

        lenient().when(modelTrainingRepo.findById("train_1")).thenReturn(Optional.of(training));
        lenient().when(modelTrainingRepo.save(any(ModelTrainingEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(mlModelRepo.findById("ml_1")).thenReturn(Optional.of(model));
        lenient().when(mlModelRepo.save(any(MLModelEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ModelTrainingEntity done = mlModelService.completeTraining("train_1", "/models/m.bin",
            Map.of("accuracy", 0.95, "precision", 0.91, "recall", 0.89, "f1", 0.90, "auc", 0.97));

        assertEquals(ModelTrainingEntity.TrainingStatus.COMPLETED, done.trainingStatus);
        assertEquals("/models/m.bin", done.artifactPath);
        assertTrue(done.validationMetrics.contains("accuracy"));
        assertEquals(0.95, model.accuracyMetric);
        assertEquals(0.91, model.precisionMetric);
        assertEquals(0.89, model.recallMetric);
        assertEquals(0.90, model.f1Score);
        assertEquals(0.97, model.aucScore);
        assertEquals(2, model.version);
        assertEquals(MLModelEntity.ModelStatus.EVALUATING, model.modelStatus);
        assertEquals("/models/m.bin", model.modelArtifactPath);
    }

    @Test
    @DisplayName("ML：训练任务缺失与完结状态下的操作被拒绝")
    void mlTrainingJobNotFoundAndInvalidStates() {
        lenient().when(modelTrainingRepo.findById("train_missing")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> mlModelService.startTraining("train_missing"));
        assertThrows(IllegalArgumentException.class, () -> mlModelService.completeTraining("train_missing", null, null));
        assertThrows(IllegalArgumentException.class, () -> mlModelService.failTraining("train_missing", "e", null));
        assertThrows(IllegalArgumentException.class, () -> mlModelService.cancelTraining("train_missing", "op"));
        assertThrows(IllegalArgumentException.class, () -> mlModelService.getTrainingJob("train_missing"));

        ModelTrainingEntity completed = new ModelTrainingEntity();
        completed.id = "train_done";
        completed.modelId = "ml_1";
        completed.trainingStatus = ModelTrainingEntity.TrainingStatus.COMPLETED;
        lenient().when(modelTrainingRepo.findById("train_done")).thenReturn(Optional.of(completed));

        assertThrows(IllegalStateException.class, () -> mlModelService.startTraining("train_done"));
        assertThrows(IllegalStateException.class, () -> mlModelService.cancelTraining("train_done", "op"));
        assertThrows(IllegalStateException.class,
            () -> mlModelService.completeTraining("train_done", null, null));
        assertThrows(IllegalStateException.class,
            () -> mlModelService.updateTrainingProgress("train_done", 1, 2, 0.5, null));
    }

    @Test
    @DisplayName("ML：createTrainingJob 序列化三份配置并把模型置为 TRAINING")
    void mlCreateTrainingJobSerializesConfigs() {
        MLModelEntity model = new MLModelEntity();
        model.id = "ml_1";
        model.gameId = "game_1";
        model.modelName = "Churn";
        model.modelStatus = MLModelEntity.ModelStatus.DRAFT;
        lenient().when(mlModelRepo.findById("ml_1")).thenReturn(Optional.of(model));
        lenient().when(mlModelRepo.save(any(MLModelEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(modelTrainingRepo.save(any(ModelTrainingEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ModelTrainingEntity job = mlModelService.createTrainingJob("ml_1", "retrain",
            Map.of("epochs", 50), Map.of("table", "events"), Map.of("lr", 0.01), "tester");

        assertTrue(job.id.startsWith("train_"));
        assertTrue(job.trainingConfig.contains("epochs"));
        assertTrue(job.datasetConfig.contains("events"));
        assertTrue(job.hyperparameterConfig.contains("lr"));
        assertEquals("manual", job.triggerType);
        assertEquals("game_1", job.gameId);
        assertEquals(MLModelEntity.ModelStatus.TRAINING, model.modelStatus);
        verify(auditLog).logCreate(eq("model_training"), eq(job.id), eq("retrain"),
            eq("tester"), eq("tester"), isNull(), anyMap());
    }

    @Test
    @DisplayName("ML：getTrainingHistory 透传仓库查询")
    void mlTrainingHistoryDelegates() {
        ModelTrainingEntity job = new ModelTrainingEntity();
        lenient().when(modelTrainingRepo.findByModelIdOrderByCreatedAtDesc("ml_1")).thenReturn(List.of(job));

        assertEquals(1, mlModelService.getTrainingHistory("ml_1").size());
    }

}
