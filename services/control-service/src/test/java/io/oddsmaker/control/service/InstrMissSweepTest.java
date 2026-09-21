package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.AuditLogRepo;
import io.oddsmaker.control.experiment.ExperimentRepo;
import io.oddsmaker.control.jpa.ExportJobRepo;
import io.oddsmaker.control.jpa.FeatureFlagRepo;
import io.oddsmaker.control.jpa.GameEntity;
import io.oddsmaker.control.jpa.GameEnvironmentRepo;
import io.oddsmaker.control.jpa.GameRepo;
import io.oddsmaker.control.jpa.IdentityRepo;
import io.oddsmaker.control.jpa.IntegrationRepo;
import io.oddsmaker.control.jpa.MLModelRepo;
import io.oddsmaker.control.jpa.MaintenanceWindowRepo;
import io.oddsmaker.control.jpa.ModelPredictionRepo;
import io.oddsmaker.control.jpa.ModelTrainingRepo;
import io.oddsmaker.control.jpa.PlayerExportJobRepo;
import io.oddsmaker.control.jpa.PlayerLoginLogRepo;
import io.oddsmaker.control.jpa.PlayerPaymentRepo;
import io.oddsmaker.control.jpa.RedeemCodeBatchRepo;
import io.oddsmaker.control.jpa.RedeemCodeEntity;
import io.oddsmaker.control.jpa.RedeemCodeRepo;
import io.oddsmaker.control.jpa.RedeemRecordRepo;
import io.oddsmaker.control.jpa.StorageProfileRepo;
import io.oddsmaker.control.jpa.SystemConfigRepo;
import io.oddsmaker.control.jpa.UserRepo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * INSTR 收口：15 个「同行双 lambda 只走一侧」的缺侧——
 * 各查询方法的 orElseThrow 异常构造 lambda（not-found 侧）此前未执行。
 * 统一以 empty/缺失桩驱动一次，把 not-found lambda 体走到。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("INSTR 收口：查询方法 not-found lambda 侧")
class InstrMissSweepTest {

    @Mock IntegrationRepo integrationRepo;
    @Mock MLModelRepo mlModelRepo;
    @Mock ModelTrainingRepo modelTrainingRepo;
    @Mock ModelPredictionRepo modelPredictionRepo;
    @Mock PlayerExportJobRepo playerExportJobRepo;
    @Mock GameRepo gameRepo;
    @Mock GameEnvironmentRepo gameEnvironmentRepo;
    @Mock StorageProfileRepo storageProfileRepo;
    @Mock IdentityRepo identityRepo;
    @Mock PlayerPaymentRepo playerPaymentRepo;
    @Mock PlayerLoginLogRepo playerLoginLogRepo;
    @Mock RedeemRecordRepo redeemRecordRepo;
    @Mock UserRepo userRepo;
    @Mock AuditLogRepo auditLogRepo;
    @Mock ExperimentRepo experimentRepo;
    @Mock MaintenanceWindowRepo maintenanceWindowRepo;
    @Mock SystemConfigRepo systemConfigRepo;
    @Mock FeatureFlagRepo featureFlagRepo;
    @Mock ExportJobRepo exportJobRepo;
    @Mock RedeemCodeBatchRepo redeemCodeBatchRepo;
    @Mock RedeemCodeRepo redeemCodeRepo;
    @Mock AuditLogService auditLogService;
    @Mock WebhookService webhookService;
    @Mock ClickHouseClient clickHouseClient;
    @Spy ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks IntegrationService integrationService;
    @InjectMocks MLModelService mlModelService;
    @InjectMocks PlayerExportService playerExportService;
    @InjectMocks UserService userService;
    @InjectMocks ExperimentService experimentService;
    @InjectMocks MaintenanceService maintenanceService;
    @InjectMocks ExportService exportService;
    @InjectMocks GameService gameService;
    @InjectMocks RedeemCodeService redeemCodeService;

    private GameEntity liveGame() {
        GameEntity g = new GameEntity();
        g.id = "g1";
        return g;
    }

    @Test
    @DisplayName("Integration：getIntegration/deleteIntegration 不存在抛 IAE")
    void integrationNotFound() {
        when(integrationRepo.findById("missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> integrationService.getIntegration("missing"));
        assertThrows(IllegalArgumentException.class, () -> integrationService.deleteIntegration("missing"));
    }

    @Test
    @DisplayName("MLModel：训练/预测四入口不存在均抛 IAE")
    void mlModelNotFound() {
        when(modelTrainingRepo.findById("t_missing")).thenReturn(Optional.empty());
        when(modelPredictionRepo.findById("p_missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
            () -> mlModelService.updateTrainingProgress("t_missing", 1, 2, 0.5, null));
        assertThrows(IllegalArgumentException.class, () -> mlModelService.getTrainingJob("t_missing"));
        assertThrows(IllegalArgumentException.class,
            () -> mlModelService.failPrediction("p_missing", "E_TIMEOUT", "boom"));
        assertThrows(IllegalArgumentException.class, () -> mlModelService.getPrediction("p_missing"));
    }

    @Test
    @DisplayName("PlayerExport.process：任务不存在抛 IAE")
    void playerExportNotFound() {
        when(playerExportJobRepo.findById("missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> playerExportService.process("missing"));
    }

    @Test
    @DisplayName("User.deleteUser：用户不存在抛 IAE")
    void userNotFound() {
        when(userRepo.findById("missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> userService.deleteUser("missing", "op"));
    }

    @Test
    @DisplayName("Experiment.publishExperiment：实验不存在抛 IAE")
    void experimentNotFound() {
        when(experimentRepo.findById("missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> experimentService.publishExperiment("missing"));
    }

    @Test
    @DisplayName("Maintenance.completeMaintenance：窗口不存在抛 IAE")
    void maintenanceNotFound() {
        when(maintenanceWindowRepo.findById("missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
            () -> maintenanceService.completeMaintenance("missing", "done"));
    }

    @Test
    @DisplayName("Export.getExportJob：任务不存在抛 IAE")
    void exportJobNotFound() {
        when(exportJobRepo.findById("missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> exportService.getExportJob("missing"));
    }

    @Test
    @DisplayName("Game.deleteEnvironment：环境不存在抛 IAE（requireGame 通过后查无环境）")
    void gameEnvironmentNotFound() {
        when(gameRepo.findById("g1")).thenReturn(Optional.of(liveGame()));
        when(gameEnvironmentRepo.findByGameIdAndNameAndDeletedAtIsNull("g1", "prod"))
            .thenReturn(List.of());
        assertThrows(IllegalArgumentException.class,
            () -> gameService.deleteEnvironment("g1", "prod"));
    }

    @Test
    @DisplayName("Redeem.redeem：码存在但批次已删抛 invalid_code（batch orElseThrow 侧）")
    void redeemBatchMissing() {
        when(gameRepo.findById("g1")).thenReturn(Optional.of(liveGame()));
        RedeemCodeEntity code = new RedeemCodeEntity();
        code.code = "SAVE10";
        code.batchId = "b_deleted";
        when(redeemCodeRepo.findByCode("SAVE10")).thenReturn(Optional.of(code));
        lenient().when(redeemCodeBatchRepo.findByIdAndDeletedAtIsNull("b_deleted"))
            .thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
            () -> redeemCodeService.redeem("g1", "SAVE10", "p1"));
    }

    // ==== found 侧（正常返回）：这四个单行查询方法此前只走过 not-found 抛异常出口，
    // areturn 出口探针段从未执行（jaCoCo 方法体 3 条指令恒 miss），必须正常返回一次 ====

    @Test
    @DisplayName("found 侧：getIntegration/getTrainingJob/getPrediction/getExportJob 正常返回")
    void simpleGettersFoundSide() {
        io.oddsmaker.control.jpa.IntegrationEntity integration = new io.oddsmaker.control.jpa.IntegrationEntity();
        integration.id = "i1";
        when(integrationRepo.findById("i1")).thenReturn(Optional.of(integration));
        assertEquals(integration, integrationService.getIntegration("i1"));

        io.oddsmaker.control.jpa.ModelTrainingEntity training = new io.oddsmaker.control.jpa.ModelTrainingEntity();
        training.id = "t1";
        when(modelTrainingRepo.findById("t1")).thenReturn(Optional.of(training));
        assertEquals(training, mlModelService.getTrainingJob("t1"));

        io.oddsmaker.control.jpa.MLModelPredictionEntity prediction =
            new io.oddsmaker.control.jpa.MLModelPredictionEntity();
        prediction.id = "p1";
        when(modelPredictionRepo.findById("p1")).thenReturn(Optional.of(prediction));
        assertEquals(prediction, mlModelService.getPrediction("p1"));

        io.oddsmaker.control.jpa.ExportJobEntity job = new io.oddsmaker.control.jpa.ExportJobEntity();
        job.id = "e1";
        when(exportJobRepo.findById("e1")).thenReturn(Optional.of(job));
        assertEquals(job, exportService.getExportJob("e1"));
    }
}
