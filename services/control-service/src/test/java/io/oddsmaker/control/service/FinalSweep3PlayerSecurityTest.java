package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.jpa.AuditLogEntity;
import io.oddsmaker.control.jpa.AuditLogRepo;
import io.oddsmaker.control.jpa.BlockListEntity;
import io.oddsmaker.control.jpa.BlockListRepo;
import io.oddsmaker.control.jpa.ExportJobEntity;
import io.oddsmaker.control.jpa.ExportJobRepo;
import io.oddsmaker.control.jpa.GameEnvironmentEntity;
import io.oddsmaker.control.jpa.GameEnvironmentRepo;
import io.oddsmaker.control.jpa.IdentityEntity;
import io.oddsmaker.control.jpa.IdentityLinkEntity;
import io.oddsmaker.control.jpa.IdentityLinkRepo;
import io.oddsmaker.control.jpa.IdentityRepo;
import io.oddsmaker.control.jpa.MFAConfigEntity;
import io.oddsmaker.control.jpa.MFAConfigRepo;
import io.oddsmaker.control.jpa.MLModelEntity;
import io.oddsmaker.control.jpa.MLModelPredictionEntity;
import io.oddsmaker.control.jpa.MLModelRepo;
import io.oddsmaker.control.jpa.ModelPredictionRepo;
import io.oddsmaker.control.jpa.ModelTrainingRepo;
import io.oddsmaker.control.jpa.PlayerExportJobEntity;
import io.oddsmaker.control.jpa.PlayerExportJobRepo;
import io.oddsmaker.control.jpa.PlayerLoginLogRepo;
import io.oddsmaker.control.jpa.PlayerPaymentRepo;
import io.oddsmaker.control.jpa.RedeemRecordRepo;
import io.oddsmaker.control.jpa.RiskCaseRepo;
import io.oddsmaker.control.jpa.SSOConfigRepo;
import io.oddsmaker.control.jpa.SecurityPolicyRepo;
import io.oddsmaker.control.jpa.SecuritySessionRepo;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖率收尾（第四轮·LAY-003 拆分）：PlayerExportService、MLModelService、IdentityConsumer、SecurityService（MFA）、BlockListService、RiskEventConsumer。与 FinalSweep2Test / ServicesFinalSweepTest / 各专项 Service 测试互补。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("覆盖率收尾（第四轮）·玩家导出/ML/身份/MFA/风控消费域")
class FinalSweep3PlayerSecurityTest {

// ==================== 共享 Mock（原类逐字搬运） ====================

    @Mock
    private AuditLogRepo auditLogRepo;

    @Mock
    private AuditLogService auditLogService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private PlayerExportJobRepo playerExportJobRepo;

    @Mock
    private IdentityRepo identityRepo;

    @Mock
    private PlayerPaymentRepo playerPaymentRepo;

    @Mock
    private PlayerLoginLogRepo playerLoginLogRepo;

    @Mock
    private RedeemRecordRepo redeemRecordRepo;

    @InjectMocks
    private PlayerExportService playerExportService;

    @Mock
    private MLModelRepo mlModelRepo;

    @Mock
    private ModelTrainingRepo modelTrainingRepo;

    @Mock
    private ModelPredictionRepo modelPredictionRepo;

    @InjectMocks
    private MLModelService mlModelService;

    @Mock
    private GameEnvironmentRepo gameEnvironmentRepo;

    @Mock
    private IdentityLinkRepo identityLinkRepo;

    @InjectMocks
    private IdentityConsumer identityConsumer;

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

    @Mock
    private BlockListRepo blockListRepo;

    @Mock
    private RiskCaseRepo riskCaseRepo;

    @InjectMocks
    private BlockListService blockListService;

    @Mock
    private WebhookService webhookServiceMock;

    @Mock
    private ReviewQueueService reviewQueueService;

    @Mock
    private RiskActionRecorder riskActionRecorder;

    @InjectMocks
    private RiskEventConsumer riskEventConsumer;

    @BeforeEach
    void setUp() {
        lenient().when(auditLogRepo.save(any(AuditLogEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        objectMapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    }

    private GameEnvironmentEntity env(String id, String gameId, String name) {
        GameEnvironmentEntity e = new GameEnvironmentEntity();
        e.id = id;
        e.gameId = gameId;
        e.name = name;
        return e;
    }

    private MFAConfigEntity mfa(MFAConfigEntity.MFAMethod method) {
        MFAConfigEntity c = new MFAConfigEntity();
        c.userId = "u1";
        c.mfaMethod = method;
        return c;
    }

    @Test
    @DisplayName("PlayerExportService.sweep 处理 PENDING 任务并落盘")
    void testSweep() throws Exception {
        java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("final3-exports");
        org.springframework.test.util.ReflectionTestUtils.setField(playerExportService, "storageDir", tempDir.toString());
        PlayerExportJobEntity job = new PlayerExportJobEntity();
        job.id = "pex1";
        job.gameId = "sweep-g1";
        job.playerId = "p1";
        job.exportFormat = "json";
        job.sections = "[]";
        job.fileName = "sweep-final3.json";
        when(playerExportJobRepo.findByStatusOrderByCreatedAtAsc(PlayerExportJobEntity.Status.PENDING))
            .thenReturn(List.of(job));
        when(playerExportJobRepo.findById("pex1")).thenReturn(Optional.of(job));
        when(playerExportJobRepo.save(any(PlayerExportJobEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        assertDoesNotThrow(() -> playerExportService.sweep());
        assertEquals(PlayerExportJobEntity.Status.COMPLETED, job.status);
        assertNotNull(job.filePath);
    }

    @Test
    @DisplayName("PlayerExportService.cleanup 到期任务标记 EXPIRED 并删除文件")
    void testCleanup() {
        PlayerExportJobEntity noFile = new PlayerExportJobEntity();
        noFile.id = "pex1";
        noFile.status = PlayerExportJobEntity.Status.COMPLETED;
        PlayerExportJobEntity withFile = new PlayerExportJobEntity();
        withFile.id = "pex2";
        withFile.status = PlayerExportJobEntity.Status.COMPLETED;
        withFile.filePath = "/tmp/opencode/final3-no-such-file.json";
        when(playerExportJobRepo.findByStatusAndExpiresAtBefore(
            eq(PlayerExportJobEntity.Status.COMPLETED), any(LocalDateTime.class)))
            .thenReturn(List.of(noFile, withFile));
        when(playerExportJobRepo.save(any(PlayerExportJobEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        assertDoesNotThrow(() -> playerExportService.cleanup());
        assertEquals(PlayerExportJobEntity.Status.EXPIRED, noFile.status);
        assertEquals(PlayerExportJobEntity.Status.EXPIRED, withFile.status);
    }

    @Test
    @DisplayName("MLModelService.updateModel 全配置项序列化更新")
    void testUpdateModel() {
        MLModelEntity model = new MLModelEntity();
        model.id = "m1";
        model.modelName = "churn";
        when(mlModelRepo.findById("m1")).thenReturn(Optional.of(model));
        when(mlModelRepo.save(any(MLModelEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> updates = new HashMap<>();
        updates.put("description", "new desc");
        updates.put("modelConfig", Map.of("k", "v"));
        updates.put("hyperparameters", Map.of("lr", 0.1));
        updates.put("featureConfig", Map.of("f", 1));
        updates.put("inputSchema", Map.of("type", "object"));
        updates.put("outputSchema", Map.of("type", "number"));
        updates.put("trainingConfig", Map.of("epochs", 10));
        updates.put("retrainPolicy", Map.of("cron", "0 0 * * *"));

        MLModelEntity out = mlModelService.updateModel("m1", updates, "ops");
        assertEquals("new desc", out.description);
        assertTrue(out.modelConfig.contains("k"));
        assertTrue(out.hyperparameters.contains("lr"));
        assertTrue(out.featureConfig.contains("f"));
        assertTrue(out.inputSchema.contains("object"));
        assertTrue(out.outputSchema.contains("number"));
        assertTrue(out.trainingConfig.contains("epochs"));
        assertTrue(out.retrainPolicy.contains("cron"));
        verify(auditLogService).logUpdate(eq("ml_model"), eq("m1"), eq("churn"), eq("ops"), eq("ops"),
            isNull(), anyMap());
    }

    @Test
    @DisplayName("MLModelService.completePrediction 完整输出映射与 null 输出分支")
    void testCompletePrediction() {
        MLModelPredictionEntity prediction = new MLModelPredictionEntity();
        when(modelPredictionRepo.findById("pred1")).thenReturn(Optional.of(prediction));
        when(modelPredictionRepo.save(any(MLModelPredictionEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("class", "churn");
        output.put("probability", 0.87);
        output.put("topPredictions", List.of(Map.of("label", "churn")));
        output.put("featureImportance", Map.of("days_inactive", 0.6));
        output.put("explanation", "inactive too long");
        MLModelPredictionEntity out = mlModelService.completePrediction("pred1", output, 0.87, 12);
        assertEquals("churn", out.predictionClass);
        assertEquals(0.87, out.predictionProbability, 1e-9);
        assertTrue(out.topPredictions.contains("churn"));
        assertTrue(out.featureImportance.contains("days_inactive"));
        assertEquals("inactive too long", out.explanation);
        assertEquals(12, out.latencyMs);

        MLModelPredictionEntity bare = new MLModelPredictionEntity();
        when(modelPredictionRepo.findById("pred2")).thenReturn(Optional.of(bare));
        MLModelPredictionEntity out2 = mlModelService.completePrediction("pred2", null, 0.5, 3);
        assertNotNull(out2);
        assertEquals(3, out2.latencyMs);
        assertNull(out2.predictionClass);
    }

    @Test
    @DisplayName("IdentityConsumer.resolveEnvironmentId 命中/未命中/异常三分支")
    void testResolveEnvironmentIdBranches() {
        when(gameEnvironmentRepo.findByGameIdAndNameAndDeletedAtIsNull("g1", "prod"))
            .thenReturn(List.of(env("env-1", "g1", "prod")));
        when(gameEnvironmentRepo.findByGameIdAndNameAndDeletedAtIsNull("g1", "ghost"))
            .thenReturn(List.of());
        when(gameEnvironmentRepo.findByGameIdAndNameAndDeletedAtIsNull("g1", "boom"))
            .thenThrow(new RuntimeException("db down"));
        when(identityRepo.findById(anyString())).thenReturn(Optional.empty());
        when(identityRepo.save(any(IdentityEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(identityLinkRepo.findActiveByIdentityIdAndTypeAndLinkedId(anyString(), anyString(), anyString()))
            .thenReturn(Optional.empty());
        when(identityLinkRepo.save(any(IdentityLinkEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        identityConsumer.onIdentityEvent(
            "{\"identity_id\":\"id-1\",\"game_id\":\"g1\",\"environment\":\"prod\",\"user_id\":\"u1\",\"device_ids\":[\"d1\"],\"first_seen\":1000,\"last_seen\":2000}");
        identityConsumer.onIdentityEvent(
            "{\"identity_id\":\"id-2\",\"game_id\":\"g1\",\"environment\":\"ghost\",\"device_ids\":[\"d2\"]}");
        identityConsumer.onIdentityEvent(
            "{\"identity_id\":\"id-3\",\"game_id\":\"g1\",\"environment\":\"boom\",\"device_ids\":[\"d3\"]}");

        ArgumentCaptor<IdentityEntity> captor = ArgumentCaptor.forClass(IdentityEntity.class);
        verify(identityRepo, times(3)).save(captor.capture());
        List<IdentityEntity> saved = captor.getAllValues();
        assertEquals("env-1", saved.get(0).environmentId);
        assertNull(saved.get(1).environmentId);
        assertNull(saved.get(2).environmentId);
    }

    @Test
    @DisplayName("IdentityConsumer 非法消息与空 identityId 跳过")
    void testIdentityConsumerSkips() {
        identityConsumer.onIdentityEvent("not-a-json");
        identityConsumer.onIdentityEvent("{\"game_id\":\"g1\"}");
        verify(identityRepo, never()).save(any());
    }

    @Test
    @DisplayName("SecurityService.verifyMFACode TOTP 真验证 + 无投递通道方法一律拒绝")
    void testVerifyMfaCodeBranches() {
        // TOTP：当前真码通过（真化后任意 6 位数字不再通过）
        MFAConfigEntity totp = mfa(MFAConfigEntity.MFAMethod.TOTP);
        totp.secretKey = TotpUtil.generateSecret();
        when(mfaConfigRepo.findEnabledByUserId("u1")).thenReturn(List.of(totp));
        assertTrue(securityService.verifyMFA("u1", TotpUtil.currentCode(totp.secretKey, java.time.Instant.now())));
        verify(mfaConfigRepo).save(any(MFAConfigEntity.class));

        // TOTP：格式错拒绝
        MFAConfigEntity totp2 = mfa(MFAConfigEntity.MFAMethod.TOTP);
        totp2.secretKey = TotpUtil.generateSecret();
        when(mfaConfigRepo.findEnabledByUserId("u1")).thenReturn(List.of(totp2));
        assertFalse(securityService.verifyMFA("u1", "12a456"));

        // TOTP：真码但错位（另一密钥的码）拒绝
        assertFalse(securityService.verifyMFA("u1", TotpUtil.currentCode(TotpUtil.generateSecret(), java.time.Instant.now())));

        // 无投递通道方法（enableMFA 已收窄，DB 直插的异常配置）一律 false
        when(mfaConfigRepo.findEnabledByUserId("u1")).thenReturn(List.of(mfa(MFAConfigEntity.MFAMethod.SMS)));
        assertFalse(securityService.verifyMFA("u1", "123456"));

        when(mfaConfigRepo.findEnabledByUserId("u1")).thenReturn(List.of(mfa(MFAConfigEntity.MFAMethod.EMAIL)));
        assertFalse(securityService.verifyMFA("u1", "123456"));

        when(mfaConfigRepo.findEnabledByUserId("u1")).thenReturn(List.of(mfa(MFAConfigEntity.MFAMethod.HARDWARE_TOKEN)));
        assertFalse(securityService.verifyMFA("u1", "123456"));
    }

    @Test
    @DisplayName("BlockListService.cleanupExpiredBlocks 批量解禁与空清单")
    void testCleanupExpiredBlocks() {
        BlockListEntity b1 = new BlockListEntity();
        b1.id = "b1";
        when(blockListRepo.findExpiredBlocks(any(LocalDateTime.class))).thenReturn(List.of(b1));
        when(blockListRepo.batchUnblock(anyList(), any(LocalDateTime.class))).thenReturn(1);

        assertDoesNotThrow(() -> blockListService.cleanupExpiredBlocks());
        verify(blockListRepo).batchUnblock(eq(List.of("b1")), any(LocalDateTime.class));

        when(blockListRepo.findExpiredBlocks(any(LocalDateTime.class))).thenReturn(List.of());
        assertDoesNotThrow(() -> blockListService.cleanupExpiredBlocks());
        verify(blockListRepo, times(1)).batchUnblock(anyList(), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("RiskEventConsumer.onRiskEvent REVIEW 建案入队（handleReview）")
    void testOnRiskEventReview() {
        when(riskCaseRepo.save(any(io.oddsmaker.control.jpa.RiskCaseEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        riskEventConsumer.onRiskEvent("{\"risk_event_id\":\"re1\",\"game_id\":\"g1\",\"environment\":\"prod\","
            + "\"rule_id\":\"r1\",\"risk_type\":\"cheat\",\"severity\":\"HIGH\",\"subject_type\":\"PLAYER\","
            + "\"subject_id\":\"p1\",\"score\":0.9,\"action\":\"REVIEW\",\"reason\":\"multi-account\"}");

        ArgumentCaptor<io.oddsmaker.control.jpa.RiskCaseEntity> caseCaptor =
            ArgumentCaptor.forClass(io.oddsmaker.control.jpa.RiskCaseEntity.class);
        verify(riskCaseRepo).save(caseCaptor.capture());
        io.oddsmaker.control.jpa.RiskCaseEntity riskCase = caseCaptor.getValue();
        assertEquals("player_id", riskCase.targetType);
        assertEquals(io.oddsmaker.control.jpa.RiskCaseEntity.RiskLevel.HIGH, riskCase.riskLevel);
        verify(reviewQueueService).addToQueue(eq(riskCase), eq(2), eq("risk_automation"), eq("fraud"));
        verify(riskActionRecorder).record(any(), eq("review"), eq("queued"), eq(riskCase.id));
        verify(webhookServiceMock).sendCustomWebhook(eq("g1"), eq("risk_action"), anyMap());
    }

    @Test
    @DisplayName("RiskEventConsumer.onRiskEvent 空动作/未知动作/非法消息")
    void testOnRiskEventEdgeCases() {
        riskEventConsumer.onRiskEvent("{\"risk_event_id\":\"r\",\"action\":\"  \"}");
        verify(riskCaseRepo, never()).save(any());

        riskEventConsumer.onRiskEvent("not-json");
        verify(riskCaseRepo, never()).save(any());

        riskEventConsumer.onRiskEvent("{\"risk_event_id\":\"r2\",\"game_id\":\"g1\",\"action\":\"FOO\","
            + "\"subject_type\":\"DEVICE\",\"subject_id\":\"d1\",\"risk_type\":\"cheat\"}");
        verify(riskActionRecorder).record(any(), eq("foo"), eq("logged"), isNull());
    }
}
