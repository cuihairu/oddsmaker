package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖率最终冲刺 6（LAY-007 拆分）：MLModelService 序列化 catch 分支/漂移检出/训练取消，PredictionMetricsService 风险分刷新分支。与 MLModelServiceTest / MLModelServiceDeepTest / PredictionMetricsServiceTest 互补。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("覆盖率冲刺 6：ML 模型与预测指标")
class FinalSweep6MLTest {

    @Mock
    private AuditLogService auditLog;

    @Mock
    private MLModelRepo mlModelRepo;

    @Mock
    private ModelTrainingRepo modelTrainingRepo;

    @Mock
    private ModelPredictionRepo modelPredictionRepo;

    @Mock
    private ClickHouseClient clickHouseClient;

    @Mock
    private io.oddsmaker.control.service.MlArtifactRegistry mlArtifactRegistry;

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

    @Test
    @DisplayName("refreshRiskScore：空主体跳过 + high/low 分级分支")
    void predictionRefreshRiskScoreBranches() {
        PredictionMetricsService service = new PredictionMetricsService(clickHouseClient, mlArtifactRegistry);
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

}
