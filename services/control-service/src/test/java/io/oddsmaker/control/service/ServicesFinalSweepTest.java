package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.oddsmaker.control.api.ControlService;
import io.oddsmaker.control.dto.ExperimentDTO;
import io.oddsmaker.control.dto.GameDTO;
import io.oddsmaker.control.experiment.ExperimentEntity;
import io.oddsmaker.control.experiment.ExperimentRepo;
import io.oddsmaker.control.jpa.ApiKeyEntity;
import io.oddsmaker.control.jpa.ApiKeyRepo;
import io.oddsmaker.control.jpa.AuditLogEntity;
import io.oddsmaker.control.jpa.AuditLogRepo;
import io.oddsmaker.control.jpa.RiskCaseRepo;
import io.oddsmaker.control.service.ClickHouseClient;
import io.oddsmaker.control.jpa.EventDefinitionEntity;
import io.oddsmaker.control.jpa.EventDefinitionRepo;
import io.oddsmaker.control.jpa.EventPropertyDefinitionEntity;
import io.oddsmaker.control.jpa.EventPropertyDefinitionRepo;
import io.oddsmaker.control.jpa.FunnelConfigEntity;
import io.oddsmaker.control.jpa.FunnelConfigRepo;
import io.oddsmaker.control.jpa.FunnelStepEntity;
import io.oddsmaker.control.jpa.FunnelStepRepo;
import io.oddsmaker.control.jpa.GameEntity;
import io.oddsmaker.control.jpa.GameEnvironmentEntity;
import io.oddsmaker.control.jpa.GameEnvironmentRepo;
import io.oddsmaker.control.jpa.GameRepo;
import io.oddsmaker.control.jpa.HealthCheckRepo;
import io.oddsmaker.control.jpa.HealthMetricEntity;
import io.oddsmaker.control.jpa.HealthMetricRepo;
import io.oddsmaker.control.jpa.IdentityLinkEntity;
import io.oddsmaker.control.jpa.IdentityLinkRepo;
import io.oddsmaker.control.jpa.IdentityRepo;
import io.oddsmaker.control.jpa.IntegrationEntity;
import io.oddsmaker.control.jpa.IntegrationLogRepo;
import io.oddsmaker.control.jpa.IntegrationRepo;
import io.oddsmaker.control.jpa.MLModelEntity;
import io.oddsmaker.control.jpa.MLModelRepo;
import io.oddsmaker.control.jpa.ModelPredictionRepo;
import io.oddsmaker.control.jpa.ModelTrainingEntity;
import io.oddsmaker.control.jpa.ModelTrainingRepo;
import io.oddsmaker.control.jpa.PlayerExportJobEntity;
import io.oddsmaker.control.jpa.PlayerExportJobRepo;
import io.oddsmaker.control.jpa.PlayerLoginLogRepo;
import io.oddsmaker.control.jpa.PlayerPaymentRepo;
import io.oddsmaker.control.jpa.RedeemCodeBatchEntity;
import io.oddsmaker.control.jpa.RedeemCodeBatchRepo;
import io.oddsmaker.control.jpa.RedeemCodeEntity;
import io.oddsmaker.control.jpa.RedeemCodeRepo;
import io.oddsmaker.control.jpa.RedeemRecordRepo;
import io.oddsmaker.control.jpa.RiskRuleEntity;
import io.oddsmaker.control.jpa.RiskRuleRepo;
import io.oddsmaker.control.jpa.SDKKeyEntity;
import io.oddsmaker.control.jpa.SDKKeyRepo;
import io.oddsmaker.control.jpa.SDKVersionEntity;
import io.oddsmaker.control.jpa.SDKVersionRepo;
import io.oddsmaker.control.jpa.StorageProfileRepo;
import io.oddsmaker.control.jpa.SystemAlertEntity;
import io.oddsmaker.control.jpa.SystemAlertRepo;
import io.oddsmaker.control.jpa.TelemetryConfigEntity;
import io.oddsmaker.control.jpa.TelemetryConfigRepo;
import io.oddsmaker.control.jpa.TrackingPlanEntity;
import io.oddsmaker.control.jpa.TrackingPlanRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Service 层覆盖率收尾测试（第二轮）：与 AuditLogServiceTest / GameServiceTest /
 * SecurityGameDeepTest / RiskEventConsumerTest / MLModelServiceTest / MlExperimentDeepTest /
 * ExperimentServiceTest / TrackingPlanServiceTest / DeveloperPortalServiceTest /
 * UserPortalDeepTest / InfraServicesTest / InfraServicesDeepTest2 / RiskServicesDeepTest /
 * FunnelConfigServiceTest / PerformanceMonitorServiceTest / RedeemCodeServiceTest /
 * PlayerExportServiceTest 互补，覆盖各自尚未触及的分支。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Service 层覆盖率收尾测试（第二轮）")
class ServicesFinalSweepTest {

    // ==================== 共享 Mock（类型唯一） ====================

    @Mock
    private AuditLogRepo auditLogRepo;

    @Mock
    private AuditLogService auditLog;

    @Mock
    private GameRepo gameRepo;

    @Mock
    private GameEnvironmentRepo gameEnvironmentRepo;

    @Mock
    private ApiKeyRepo apiKeyRepo;

    @Mock
    private StorageProfileRepo storageProfileRepo;

    @Mock
    private MLModelRepo mlModelRepo;

    @Mock
    private ModelTrainingRepo modelTrainingRepo;

    @Mock
    private ModelPredictionRepo modelPredictionRepo;

    @Mock
    private ExperimentRepo experimentRepo;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private TrackingPlanRepo trackingPlanRepo;

    @Mock
    private EventDefinitionRepo eventDefinitionRepo;

    @Mock
    private EventPropertyDefinitionRepo propertyDefinitionRepo;

    @Mock
    private SDKKeyRepo sdkKeyRepo;

    @Mock
    private SDKVersionRepo sdkVersionRepo;

    @Mock
    private TelemetryConfigRepo telemetryConfigRepo;

    @Mock
    private IntegrationRepo integrationRepo;

    @Mock
    private IntegrationLogRepo integrationLogRepo;

    @Mock
    private ClickHouseClient clickHouseClient;

    @Mock
    private RiskRuleRepo riskRuleRepo;

    @Mock
    private FunnelConfigRepo funnelConfigRepo;

    @Mock
    private FunnelStepRepo funnelStepRepo;

    @Mock
    private HealthCheckRepo healthCheckRepo;

    @Mock
    private HealthMetricRepo healthMetricRepo;

    @Mock
    private SystemAlertRepo systemAlertRepo;

    @Mock
    private RedeemCodeBatchRepo batchRepo;

    @Mock
    private RedeemCodeRepo codeRepo;

    @Mock
    private RedeemRecordRepo redeemRecordRepo;

    @Mock
    private PlayerExportJobRepo jobRepo;

    @Mock
    private IdentityRepo identityRepo;

    @Mock
    private PlayerPaymentRepo paymentRepo;

    @Mock
    private PlayerLoginLogRepo loginLogRepo;

    @Mock
    private IdentityLinkRepo identityLinkRepo;

    @Mock
    private BlockListService blockListService;

    @Mock
    private WebhookService webhookService;

    @Mock
    private ReviewQueueService reviewQueueService;

    @Mock
    private RiskCaseRepo riskCaseRepo;

    @Mock
    private RiskActionRecorder riskActionRecorder;

    // ==================== 被测服务（共享上面的 Mock） ====================

    @InjectMocks
    private AuditLogService auditLogService;

    @InjectMocks
    private GameService gameService;

    @InjectMocks
    private MLModelService mlModelService;

    @InjectMocks
    private ExperimentService experimentService;

    @InjectMocks
    private TrackingPlanService trackingPlanService;

    @InjectMocks
    private DeveloperPortalService developerPortalService;

    @InjectMocks
    private IntegrationService integrationService;

    @InjectMocks
    private RiskMetricsService riskMetricsService;

    @InjectMocks
    private RiskRuleService riskRuleService;

    @InjectMocks
    private FunnelConfigService funnelConfigService;

    @InjectMocks
    private HealthMonitorService healthMonitorService;

    @InjectMocks
    private RedeemCodeService redeemCodeService;

    @InjectMocks
    private PlayerExportService playerExportService;

    private RiskEventConsumer riskEventConsumer;

    private PerformanceMonitorService performanceMonitorService;

    private SimpleMeterRegistry meterRegistry;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        riskEventConsumer = new RiskEventConsumer();
        ReflectionTestUtils.setField(riskEventConsumer, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(riskEventConsumer, "blockListService", blockListService);
        ReflectionTestUtils.setField(riskEventConsumer, "webhookService", webhookService);
        ReflectionTestUtils.setField(riskEventConsumer, "auditLogService", auditLog);
        ReflectionTestUtils.setField(riskEventConsumer, "identityLinkRepo", identityLinkRepo);
        ReflectionTestUtils.setField(riskEventConsumer, "riskCaseRepo", riskCaseRepo);
        ReflectionTestUtils.setField(riskEventConsumer, "reviewQueueService", reviewQueueService);
        ReflectionTestUtils.setField(riskEventConsumer, "riskActionRecorder", riskActionRecorder);

        meterRegistry = new SimpleMeterRegistry();
        performanceMonitorService = new PerformanceMonitorService(meterRegistry);

        ReflectionTestUtils.setField(playerExportService, "storageDir", tempDir.toString());
        ReflectionTestUtils.setField(playerExportService, "retentionHours", 72);
    }

    // ==================== 数据构造 ====================

    private static GameEntity game(String id, GameEntity.GameStatus status) {
        GameEntity g = new GameEntity();
        g.id = id;
        g.name = "Game-" + id;
        g.status = status;
        return g;
    }

    private static GameEnvironmentEntity environment(String gameId, String name) {
        GameEnvironmentEntity e = new GameEnvironmentEntity();
        e.id = "env_" + gameId + "_" + name;
        e.gameId = gameId;
        e.name = name;
        e.status = GameEnvironmentEntity.EnvironmentStatus.ACTIVE;
        return e;
    }

    // =========================================================
    // AuditLogService：通用 12 参重载 + userId-first 便捷方法 + 查询/统计/清理
    // =========================================================

    @Test
    @DisplayName("审计：通用 12 参重载按 result 映射状态并提取 metadata")
    void auditLogGenericOverloadMapsResultAndMetadata() {
        lenient().when(auditLogRepo.save(any(AuditLogEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        AuditLogEntity ok = auditLogService.log(
            AuditLogEntity.AuditAction.BLOCK, "block_list", "bl_1", "device-1", "auto block",
            AuditLogEntity.AuditResult.SUCCESS, "risk-automation", "active", "blocked",
            "10.0.0.1", "curl", Map.of("gameId", "game_demo", "environment", "prod"));
        assertEquals(AuditLogEntity.AuditStatus.SUCCESS, ok.status);
        assertEquals("risk-automation", ok.userId);
        assertEquals("game_demo", ok.gameId);
        assertEquals("prod", ok.environment);
        assertEquals("active", ok.oldValue);
        assertEquals("blocked", ok.newValue);

        AuditLogEntity failure = auditLogService.log(
            AuditLogEntity.AuditAction.BLOCK, "block_list", "bl_2", null, null,
            AuditLogEntity.AuditResult.FAILURE, "op", null, null, null, null, null);
        assertEquals(AuditLogEntity.AuditStatus.FAILURE, failure.status);
        assertNull(failure.gameId);

        AuditLogEntity partial = auditLogService.log(
            AuditLogEntity.AuditAction.READ, "report", "r_1", null, null,
            AuditLogEntity.AuditResult.PARTIAL, "op", null, null, null, null, null);
        assertEquals(AuditLogEntity.AuditStatus.PARTIAL, partial.status);
    }

    @Test
    @DisplayName("审计：userId-first 的增删改/导出便捷方法字段组装")
    void auditLogUserFirstConvenienceMethods() {
        lenient().when(auditLogRepo.save(any(AuditLogEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        AuditLogEntity created = auditLogService.logCreate(
            "u1", "alice", "report", "r_1", "Daily", "{\"k\":1}", "1.2.3.4");
        assertEquals(AuditLogEntity.AuditAction.CREATE, created.action);
        assertEquals("u1", created.userId);
        assertEquals("alice", created.username);
        assertEquals("{\"k\":1}", created.newValue);
        assertEquals("1.2.3.4", created.ipAddress);

        AuditLogEntity updated = auditLogService.logUpdate(
            "u1", "alice", "report", "r_1", "Daily", "v1", "v2", "1.2.3.4");
        assertEquals(AuditLogEntity.AuditAction.UPDATE, updated.action);
        assertEquals("v1", updated.oldValue);
        assertEquals("v2", updated.newValue);

        AuditLogEntity deleted = auditLogService.logDelete(
            "u1", "alice", "report", "r_1", "Daily", "v1", "1.2.3.4");
        assertEquals(AuditLogEntity.AuditAction.DELETE, deleted.action);
        assertEquals("v1", deleted.oldValue);

        AuditLogEntity exported = auditLogService.logExport(
            "u1", "alice", "player_export", "pex_1", "p1.json", "gdpr", "1.2.3.4");
        assertEquals(AuditLogEntity.AuditAction.EXPORT, exported.action);
        assertEquals("gdpr", exported.details);
    }

    @Test
    @DisplayName("审计：logPermissionChange 以目标用户为资源")
    void auditLogPermissionChange() {
        lenient().when(auditLogRepo.save(any(AuditLogEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        AuditLogEntity entity = auditLogService.logPermissionChange(
            "admin", "root", "u2", "bob", "GRANT_ROLE", "viewer", "operator", "1.2.3.4");

        assertEquals(AuditLogEntity.AuditAction.GRANT_ROLE, entity.action);
        assertEquals("u2", entity.resourceId);
        assertEquals("bob", entity.resourceName);
        assertEquals("viewer", entity.oldValue);
        assertEquals("operator", entity.newValue);
        assertEquals(AuditLogEntity.AuditStatus.SUCCESS, entity.status);
    }

    @Test
    @DisplayName("审计：findById 命中返回实体")
    void auditLogFindByIdPresent() {
        AuditLogEntity entity = new AuditLogEntity();
        entity.id = 9L;
        lenient().when(auditLogRepo.findById(9L)).thenReturn(Optional.of(entity));

        assertSame(entity, auditLogService.findById(9L));
    }

    @Test
    @DisplayName("审计：分页查询方法透传 Repo 参数")
    void auditLogPagedQueryDelegation() {
        AuditLogEntity entity = new AuditLogEntity();
        Page<AuditLogEntity> page = new PageImpl<>(List.of(entity));
        Pageable pageable = PageRequest.of(0, 10);

        lenient().when(auditLogRepo.findRecentLogs(pageable)).thenReturn(page);
        lenient().when(auditLogRepo.findByUserIdOrderByCreatedAtDesc("u1", pageable)).thenReturn(page);
        lenient().when(auditLogRepo.findByResourceTypeAndResourceIdOrderByCreatedAtDesc("game", "g1", pageable)).thenReturn(page);
        lenient().when(auditLogRepo.findByActionOrderByCreatedAtDesc(AuditLogEntity.AuditAction.CREATE, pageable)).thenReturn(page);
        lenient().when(auditLogRepo.findByStatusOrderByCreatedAtDesc(AuditLogEntity.AuditStatus.FAILURE, pageable)).thenReturn(page);
        lenient().when(auditLogRepo.findByGameIdOrderByCreatedAtDesc("g1", pageable)).thenReturn(page);

        LocalDateTime start = LocalDateTime.now().minusDays(1);
        LocalDateTime end = LocalDateTime.now();
        lenient().when(auditLogRepo.findByCreatedAtBetweenOrderByCreatedAtDesc(start, end, pageable)).thenReturn(page);

        assertSame(page, auditLogService.listAuditLogs(pageable));
        assertSame(page, auditLogService.findByUserId("u1", pageable));
        assertSame(page, auditLogService.findByResource("game", "g1", pageable));
        assertSame(page, auditLogService.findByAction(AuditLogEntity.AuditAction.CREATE, pageable));
        assertSame(page, auditLogService.findByStatus(AuditLogEntity.AuditStatus.FAILURE, pageable));
        assertSame(page, auditLogService.findByGameId("g1", pageable));
        assertSame(page, auditLogService.findByTimeRange(start, end, pageable));
    }

    @Test
    @DisplayName("审计：失败/认证/敏感/搜索四类专用查询")
    void auditLogSpecialQueries() {
        Page<AuditLogEntity> page = new PageImpl<>(List.of(new AuditLogEntity()));
        Pageable pageable = PageRequest.of(0, 5);

        lenient().when(auditLogRepo.findFailedActions(pageable)).thenReturn(page);
        lenient().when(auditLogRepo.findAuthActions(pageable)).thenReturn(page);
        lenient().when(auditLogRepo.findSensitiveActions(pageable)).thenReturn(page);
        lenient().when(auditLogRepo.searchLogs("alice", pageable)).thenReturn(page);

        assertSame(page, auditLogService.findFailedLogs(pageable));
        assertSame(page, auditLogService.findAuthLogs(pageable));
        assertSame(page, auditLogService.findSensitiveLogs(pageable));
        assertSame(page, auditLogService.searchLogs("alice", pageable));
    }

    @Test
    @DisplayName("审计：getAuditStatistics 聚合五项统计")
    void auditLogStatistics() {
        lenient().when(auditLogRepo.countLogsSince(any(LocalDateTime.class))).thenReturn(42L);
        lenient().when(auditLogRepo.countActionsByUser(any(LocalDateTime.class))).thenReturn(List.of());
        lenient().when(auditLogRepo.countActionsByResourceType(any(LocalDateTime.class))).thenReturn(List.of());
        lenient().when(auditLogRepo.countActionsByActionType(any(LocalDateTime.class))).thenReturn(List.of());
        lenient().when(auditLogRepo.countActionsByHour(any(LocalDateTime.class))).thenReturn(List.of());

        Map<String, Object> stats = auditLogService.getAuditStatistics(7);

        assertEquals(42L, stats.get("totalLogs"));
        assertTrue(((List<?>) stats.get("actionsByUser")).isEmpty());
        assertTrue(((List<?>) stats.get("actionsByResourceType")).isEmpty());
        assertTrue(((List<?>) stats.get("actionsByActionType")).isEmpty());
        assertTrue(((List<?>) stats.get("actionsByHour")).isEmpty());
    }

    @Test
    @DisplayName("审计：cleanupOldLogs 返回删除条数")
    void auditLogCleanupOldLogs() {
        lenient().when(auditLogRepo.deleteLogsBefore(any(LocalDateTime.class))).thenReturn(7);

        assertEquals(7, auditLogService.cleanupOldLogs(30));
        verify(auditLogRepo).deleteLogsBefore(any(LocalDateTime.class));
    }

    // =========================================================
    // GameService：分页/搜索/过滤查询 + 更新缺失 + 删除级联非空
    // =========================================================

    @Test
    @DisplayName("游戏：getGames 分页映射并回填环境/密钥统计")
    void gameGetGamesPageEnrichesStatistics() {
        GameEntity entity = game("g1", GameEntity.GameStatus.LIVE);
        lenient().when(gameRepo.findByDeletedAtIsNull(any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(entity)));
        lenient().when(gameEnvironmentRepo.countByGameIdAndDeletedAtIsNull("g1")).thenReturn(3L);
        lenient().when(apiKeyRepo.countByGameIdAndStatus("g1", ApiKeyEntity.ApiKeyStatus.ACTIVE)).thenReturn(2L);

        Page<GameDTO> result = gameService.getGames(PageRequest.of(0, 10));

        assertEquals(1, result.getContent().size());
        assertEquals(3, result.getContent().get(0).totalEnvironments);
        assertEquals(2, result.getContent().get(0).totalApiKeys);
    }

    @Test
    @DisplayName("游戏：searchGames 与按状态/类型/平台过滤查询")
    void gameSearchAndFilterQueries() {
        GameEntity entity = game("g1", GameEntity.GameStatus.LIVE);
        lenient().when(gameEnvironmentRepo.countByGameIdAndDeletedAtIsNull("g1")).thenReturn(0L);
        lenient().when(apiKeyRepo.countByGameIdAndStatus("g1", ApiKeyEntity.ApiKeyStatus.ACTIVE)).thenReturn(0L);

        lenient().when(gameRepo.searchByName(eq("demo"), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(entity)));
        assertEquals(1, gameService.searchGames("demo", PageRequest.of(0, 10)).getContent().size());

        lenient().when(gameRepo.findByStatusAndDeletedAtIsNull(GameEntity.GameStatus.LIVE)).thenReturn(List.of(entity));
        lenient().when(gameRepo.findByGenreAndDeletedAtIsNull(GameEntity.GameGenre.RPG)).thenReturn(List.of(entity));
        lenient().when(gameRepo.findByPlatform(GameEntity.GamePlatform.MOBILE)).thenReturn(List.of(entity));

        assertEquals(1, gameService.getGamesByStatus(GameEntity.GameStatus.LIVE).size());
        assertEquals(1, gameService.getGamesByGenre(GameEntity.GameGenre.RPG).size());
        assertEquals(1, gameService.getGamesByPlatform(GameEntity.GamePlatform.MOBILE).size());
    }

    @Test
    @DisplayName("游戏：updateGame 目标不存在抛异常；getEnvironment 命中/未命中")
    void gameUpdateMissingAndGetEnvironment() {
        lenient().when(gameRepo.findById("g_missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
            () -> gameService.updateGame("g_missing", new GameDTO()));
        verify(gameRepo, never()).save(any());

        GameEntity existing = game("g1", GameEntity.GameStatus.DEVELOPMENT);
        GameEnvironmentEntity qa = environment("g1", "qa");
        lenient().when(gameRepo.findById("g1")).thenReturn(Optional.of(existing));
        lenient().when(gameEnvironmentRepo.findByGameIdAndNameAndDeletedAtIsNull("g1", "qa"))
            .thenReturn(List.of(qa));
        lenient().when(gameEnvironmentRepo.findByGameIdAndNameAndDeletedAtIsNull("g1", "staging"))
            .thenReturn(List.of());

        assertTrue(gameService.getEnvironment("g1", " QA ").isPresent());
        assertEquals("env_g1_qa", gameService.getEnvironment("g1", "qa").get().id);
        assertTrue(gameService.getEnvironment("g1", "staging").isEmpty());
    }

    @Test
    @DisplayName("游戏：deleteGame 级联软删除环境并吊销活跃密钥")
    void gameDeleteCascadesEnvironmentsAndApiKeys() {
        GameEntity existing = game("g1", GameEntity.GameStatus.MAINTENANCE);
        GameEnvironmentEntity env = environment("g1", "qa");
        ApiKeyEntity key = new ApiKeyEntity();
        key.apiKey = "key_1";
        key.status = ApiKeyEntity.ApiKeyStatus.ACTIVE;

        lenient().when(gameRepo.findById("g1")).thenReturn(Optional.of(existing));
        lenient().when(gameRepo.save(any(GameEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(gameEnvironmentRepo.save(any(GameEnvironmentEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(apiKeyRepo.save(any(ApiKeyEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(gameEnvironmentRepo.findByGameIdAndDeletedAtIsNull("g1")).thenReturn(List.of(env));
        lenient().when(apiKeyRepo.findByGameIdAndStatus("g1", ApiKeyEntity.ApiKeyStatus.ACTIVE))
            .thenReturn(List.of(key));

        gameService.deleteGame("g1");

        assertNotNull(env.deletedAt);
        assertEquals(GameEnvironmentEntity.EnvironmentStatus.INACTIVE, env.status);
        assertEquals(ApiKeyEntity.ApiKeyStatus.REVOKED, key.status);
        assertNotNull(key.revokedAt);
        verify(gameEnvironmentRepo).save(env);
        verify(apiKeyRepo).save(key);
    }

    // =========================================================
    // RiskEventConsumer：空 action / 未知 action / LOW·未知时长 / 默认 reason /
    //                    身份扩散 / REVIEW 非法 severity / webhook 失败不阻断
    // =========================================================

    private String riskEventJson(String action, String severity, String subjectType, String subjectId, String reason) {
        StringBuilder sb = new StringBuilder("{")
            .append("\"game_id\":\"game_demo\"")
            .append(",\"environment\":\"prod\"")
            .append(",\"risk_event_id\":\"re_sweep\"")
            .append(",\"source_event_id\":\"evt_sweep\"")
            .append(",\"rule_id\":\"rr_1\"")
            .append(",\"risk_type\":\"amount_threshold\"")
            .append(",\"subject_type\":\"").append(subjectType).append("\"")
            .append(",\"subject_id\":\"").append(subjectId).append("\"")
            .append(",\"score\":0.9")
            .append(",\"action\":").append(action == null ? "null" : "\"" + action + "\"");
        if (severity != null) {
            sb.append(",\"severity\":\"").append(severity).append("\"");
        }
        if (reason != null) {
            sb.append(",\"reason\":\"").append(reason).append("\"");
        }
        return sb.append("}").toString();
    }

    @Test
    @DisplayName("风控消费：action 缺失或空白时静默跳过")
    void riskEventSkipsWithoutAction() {
        riskEventConsumer.onRiskEvent(riskEventJson(null, "HIGH", "DEVICE", "dev_1", "r"));
        riskEventConsumer.onRiskEvent(riskEventJson("  ", "HIGH", "DEVICE", "dev_1", "r"));

        verifyNoInteractions(blockListService, webhookService, auditLog,
            riskActionRecorder, riskCaseRepo, reviewQueueService);
    }

    @Test
    @DisplayName("风控消费：未知 action 走 SECURITY_ALERT 审计并以小写动作名归档")
    void riskEventUnknownActionLoggedAsAlert() {
        riskEventConsumer.onRiskEvent(riskEventJson("QUARANTINE", "HIGH", "PLAYER", "u1", "manual"));

        verify(auditLog).log(
            eq(AuditLogEntity.AuditAction.SECURITY_ALERT), eq("risk_event"), eq("re_sweep"),
            any(), any(), eq(AuditLogEntity.AuditResult.SUCCESS), eq("risk-automation"),
            isNull(), isNull(), isNull(), isNull(), any());
        verify(riskActionRecorder).record(any(), eq("quarantine"), eq("logged"), isNull());
        verify(blockListService, never()).addBlock(
            any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any());
        verify(webhookService, never()).sendCustomWebhook(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("风控消费：BLOCK 时长矩阵 LOW=60 分钟、未知 severity=1440 分钟、reason 缺省")
    void riskEventBlockDurationMatrixAndDefaultReason() {
        riskEventConsumer.onRiskEvent(riskEventJson("BLOCK", "LOW", "PLAYER", "u1", null));
        verify(blockListService).addBlock(
            eq("game_demo"), eq("prod"), eq("player_id"), eq("u1"),
            eq("Risk automation: amount_threshold"),
            eq("fraud"), eq(io.oddsmaker.control.jpa.BlockListEntity.BlockType.HARD),
            eq(false), eq(60), eq("risk-automation"), isNull(), isNull());

        riskEventConsumer.onRiskEvent(riskEventJson("BLOCK", "EXTREME", "DEVICE", "dev_1", "abuse"));
        verify(blockListService).addBlock(
            eq("game_demo"), eq("prod"), eq("device_id"), eq("dev_1"),
            eq("abuse"), eq("fraud"), eq(io.oddsmaker.control.jpa.BlockListEntity.BlockType.HARD),
            eq(false), eq(1440), eq("risk-automation"), isNull(), eq("abuse"));
    }

    @Test
    @DisplayName("风控消费：身份扩散封禁只覆盖 player_id/user_id 关联身份")
    void riskEventIdentityExtendBlocksLinkedPlayers() {
        ReflectionTestUtils.setField(riskEventConsumer, "identityExtend", true);

        IdentityLinkEntity deviceLink = new IdentityLinkEntity();
        deviceLink.identityId = "idA";
        deviceLink.linkedIdentityType = "device_id";
        deviceLink.linkedId = "dev_1";
        IdentityLinkEntity playerLink = new IdentityLinkEntity();
        playerLink.identityId = "idA";
        playerLink.linkedIdentityType = "player_id";
        playerLink.linkedId = "player_9";
        IdentityLinkEntity characterLink = new IdentityLinkEntity();
        characterLink.identityId = "idA";
        characterLink.linkedIdentityType = "character_id";
        characterLink.linkedId = "char_1";

        lenient().when(identityLinkRepo.findByTypeAndId("device_id", "dev_1")).thenReturn(List.of(deviceLink));
        lenient().when(identityLinkRepo.findByIdentityId("idA"))
            .thenReturn(List.of(playerLink, characterLink));

        riskEventConsumer.onRiskEvent(riskEventJson("BLOCK", "HIGH", "DEVICE", "dev_1", "cheat"));

        // 1 次主封禁 + 1 次扩散（character_id 不扩散）
        verify(blockListService, times(2)).addBlock(
            any(), any(), any(), any(), any(), any(),
            eq(io.oddsmaker.control.jpa.BlockListEntity.BlockType.HARD),
            anyBoolean(), any(), any(), any(), any());
        verify(blockListService).addBlock(
            eq("game_demo"), eq("prod"), eq("player_id"), eq("player_9"),
            contains("identity-linked"), eq("fraud"),
            eq(io.oddsmaker.control.jpa.BlockListEntity.BlockType.HARD),
            eq(false), eq(1440), eq("risk-automation"), isNull(), eq("cheat"));
    }

    @Test
    @DisplayName("风控消费：REVIEW 非法 severity 回退 MEDIUM 并入普通优先级队列")
    void riskEventReviewInvalidSeverityFallsBackToMedium() {
        lenient().when(riskCaseRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        riskEventConsumer.onRiskEvent(riskEventJson("REVIEW", "EXTREME", "PLAYER", "u1", "check"));

        ArgumentCaptor<io.oddsmaker.control.jpa.RiskCaseEntity> captor =
            ArgumentCaptor.forClass(io.oddsmaker.control.jpa.RiskCaseEntity.class);
        verify(riskCaseRepo).save(captor.capture());
        assertEquals(io.oddsmaker.control.jpa.RiskCaseEntity.RiskLevel.MEDIUM,
            captor.getValue().riskLevel);
        verify(reviewQueueService).addToQueue(any(), eq(2), eq("risk_automation"), eq("fraud"));
    }

    @Test
    @DisplayName("风控消费：risk_action webhook 失败不阻断本地封禁与归档")
    void riskEventWebhookFailureDoesNotBlockDisposition() {
        lenient().doThrow(new RuntimeException("network down"))
            .when(webhookService).sendCustomWebhook(anyString(), anyString(), any());

        assertDoesNotThrow(() ->
            riskEventConsumer.onRiskEvent(riskEventJson("BLOCK", "HIGH", "DEVICE", "dev_1", "cheat")));

        verify(blockListService).addBlock(
            eq("game_demo"), eq("prod"), eq("device_id"), eq("dev_1"),
            anyString(), anyString(), eq(io.oddsmaker.control.jpa.BlockListEntity.BlockType.HARD),
            eq(false), eq(1440), eq("risk-automation"), isNull(), anyString());
        verify(riskActionRecorder).record(any(), eq("block"), eq("blocked"), isNull());
    }

    // =========================================================
    // MLModelService：completeTraining 指标写回 / 任务缺失与状态分支 / 配置序列化
    // =========================================================

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

    // =========================================================
    // ExperimentService：delete / getExperiment / getRunningConfig / list 环境名与非法状态
    // =========================================================

    private static ExperimentEntity experimentEntity(String status) {
        ExperimentEntity e = new ExperimentEntity();
        e.id = "exp_1";
        e.gameId = "game_1";
        e.environmentId = "env_game_1_prod";
        e.name = "Sweep Exp";
        e.status = status;
        e.salt = "salt";
        e.configJson = "{\"variants\":[{\"name\":\"control\",\"weight\":1},"
            + "{\"name\":\"treatment\",\"weight\":1}]}";
        e.createdAt = Instant.now();
        e.updatedAt = Instant.now();
        return e;
    }

    @Test
    @DisplayName("实验：deleteExperiment 存在返回 true 并删除，不存在返回 false")
    void experimentDeleteReturnsTrueFalse() {
        lenient().when(experimentRepo.existsById("exp_1")).thenReturn(true);
        assertTrue(experimentService.deleteExperiment("exp_1"));
        verify(experimentRepo).deleteById("exp_1");

        lenient().when(experimentRepo.existsById("exp_missing")).thenReturn(false);
        assertFalse(experimentService.deleteExperiment("exp_missing"));
        verify(experimentRepo, never()).deleteById("exp_missing");
    }

    @Test
    @DisplayName("实验：getExperiment 回填环境名")
    void experimentGetMapsEnvironmentName() {
        GameEnvironmentEntity prod = environment("game_1", "prod");
        lenient().when(experimentRepo.findById("exp_1")).thenReturn(Optional.of(experimentEntity("running")));
        lenient().when(gameEnvironmentRepo.findById("env_game_1_prod")).thenReturn(Optional.of(prod));

        Optional<ExperimentDTO> found = experimentService.getExperiment("exp_1");

        assertTrue(found.isPresent());
        assertEquals("prod", found.get().environment);
        assertEquals("Sweep Exp", found.get().name);
        assertTrue(found.get().config.has("variants"));
    }

    @Test
    @DisplayName("实验：getRunningConfig 三分支（成功/环境不活跃/游戏不存在）")
    void experimentRunningConfigBranches() {
        GameEntity g = game("game_1", GameEntity.GameStatus.LIVE);
        GameEnvironmentEntity prod = environment("game_1", "prod");
        lenient().when(gameRepo.findById("game_1")).thenReturn(Optional.of(g));
        lenient().when(gameEnvironmentRepo.findByGameIdAndNameAndDeletedAtIsNull("game_1", "prod"))
            .thenReturn(List.of(prod));
        lenient().when(experimentRepo.findRunningConfigs("game_1", "env_game_1_prod"))
            .thenReturn(List.of(experimentEntity("running")));

        assertEquals(1, experimentService.getRunningConfig("game_1", "prod").size());

        GameEnvironmentEntity inactive = environment("game_1", "prod");
        inactive.status = GameEnvironmentEntity.EnvironmentStatus.INACTIVE;
        lenient().when(gameEnvironmentRepo.findByGameIdAndNameAndDeletedAtIsNull("game_1", "staging"))
            .thenReturn(List.of(inactive));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> experimentService.getRunningConfig("game_1", "staging"));
        assertTrue(ex.getMessage().contains("not active"));

        lenient().when(gameRepo.findById("game_x")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
            () -> experimentService.getRunningConfig("game_x", "prod"));
    }

    @Test
    @DisplayName("实验：listExperiments 按环境名解析 environmentId，非法 status 过滤被拒绝")
    void experimentListByEnvironmentNameAndInvalidStatus() {
        GameEntity g = game("game_1", GameEntity.GameStatus.LIVE);
        GameEnvironmentEntity prod = environment("game_1", "prod");
        lenient().when(gameRepo.findById("game_1")).thenReturn(Optional.of(g));
        lenient().when(gameEnvironmentRepo.findByGameIdAndNameAndDeletedAtIsNull("game_1", "prod"))
            .thenReturn(List.of(prod));
        lenient().when(experimentRepo.search(any(), any(), any(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));

        ControlService.Paged<ExperimentDTO> result =
            experimentService.listExperiments("game_1", null, "PROD", null, 0, 10);
        assertEquals(0L, result.total);
        verify(experimentRepo).search(eq("game_1"), eq("env_game_1_prod"), isNull(), any(Pageable.class));

    }

    // =========================================================
    // TrackingPlanService：deleteEventDefinition / 计划列表查询 / 定义读取
    // =========================================================

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

    @Test
    @DisplayName("追踪计划：deleteEventDefinition 软删除并刷新计数，非草稿/缺失被拒绝")
    void trackingPlanDeleteEventDefinition() {
        TrackingPlanEntity draft = plan("tp_1", TrackingPlanEntity.PlanStatus.DRAFT);
        EventDefinitionEntity def = eventDef("ed_1", "tp_1");
        lenient().when(eventDefinitionRepo.findById("ed_1")).thenReturn(Optional.of(def));
        lenient().when(trackingPlanRepo.findByIdAndDeletedAtIsNull("tp_1")).thenReturn(Optional.of(draft));
        lenient().when(eventDefinitionRepo.countByTrackingPlanId("tp_1")).thenReturn(0L);
        lenient().when(eventDefinitionRepo.save(any(EventDefinitionEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(trackingPlanRepo.save(any(TrackingPlanEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        trackingPlanService.deleteEventDefinition("ed_1");

        assertNotNull(def.deletedAt);
        assertEquals(EventDefinitionEntity.DefinitionStatus.DEPRECATED, def.status);
        assertEquals(0, draft.totalEvents);

        def.deletedAt = null;
        lenient().when(trackingPlanRepo.findByIdAndDeletedAtIsNull("tp_1"))
            .thenReturn(Optional.of(plan("tp_1", TrackingPlanEntity.PlanStatus.ACTIVE)));
        assertThrows(IllegalStateException.class,
            () -> trackingPlanService.deleteEventDefinition("ed_1"));

        lenient().when(eventDefinitionRepo.findById("ed_missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
            () -> trackingPlanService.deleteEventDefinition("ed_missing"));
    }

    @Test
    @DisplayName("追踪计划：三个列表查询（游戏全量/活跃/按环境）")
    void trackingPlanListQueries() {
        GameEntity g = game("game_1", GameEntity.GameStatus.LIVE);
        TrackingPlanEntity p = plan("tp_1", TrackingPlanEntity.PlanStatus.ACTIVE);
        lenient().when(gameRepo.findById("game_1")).thenReturn(Optional.of(g));
        lenient().when(gameRepo.findById("game_x")).thenReturn(Optional.empty());
        lenient().when(trackingPlanRepo.findByGameIdAndDeletedAtIsNullOrderByCreatedAtDesc("game_1"))
            .thenReturn(List.of(p));
        lenient().when(trackingPlanRepo.findActiveByGameId("game_1")).thenReturn(List.of(p));
        lenient().when(trackingPlanRepo.findByGameIdAndEnvironment("game_1", "env_prod")).thenReturn(List.of(p));

        assertEquals(1, trackingPlanService.listTrackingPlans("game_1").size());
        assertEquals(1, trackingPlanService.getActiveTrackingPlans("game_1").size());
        assertEquals(1, trackingPlanService.getTrackingPlansForEnvironment("game_1", "env_prod").size());

        assertThrows(IllegalArgumentException.class,
            () -> trackingPlanService.listTrackingPlans("game_x"));
        assertThrows(IllegalArgumentException.class,
            () -> trackingPlanService.getActiveTrackingPlans("game_x"));
    }

    @Test
    @DisplayName("追踪计划：getEventDefinition 过滤已删除，listPropertyDefinitions 校验事件存在")
    void trackingPlanDefinitionReads() {
        EventDefinitionEntity def = eventDef("ed_1", "tp_1");
        lenient().when(eventDefinitionRepo.findById("ed_1")).thenReturn(Optional.of(def));
        EventDefinitionEntity deleted = eventDef("ed_2", "tp_1");
        deleted.deletedAt = LocalDateTime.now();
        lenient().when(eventDefinitionRepo.findById("ed_2")).thenReturn(Optional.of(deleted));
        lenient().when(eventDefinitionRepo.findById("ed_missing")).thenReturn(Optional.empty());

        assertTrue(trackingPlanService.getEventDefinition("ed_1").isPresent());
        assertTrue(trackingPlanService.getEventDefinition("ed_2").isEmpty());
        assertTrue(trackingPlanService.getEventDefinition("ed_missing").isEmpty());

        EventPropertyDefinitionEntity prop = new EventPropertyDefinitionEntity();
        prop.id = "epd_1";
        prop.eventDefinitionId = "ed_1";
        prop.propertyName = "level_name";
        lenient().when(propertyDefinitionRepo
            .findByEventDefinitionIdAndDeletedAtIsNullOrderByDisplayOrderAsc("ed_1")).thenReturn(List.of(prop));

        assertEquals(1, trackingPlanService.listPropertyDefinitions("ed_1").size());
        assertThrows(IllegalArgumentException.class,
            () -> trackingPlanService.listPropertyDefinitions("ed_missing"));
    }

    // =========================================================
    // DeveloperPortalService：getEffectiveConfig 回退链 / 版本生命周期守卫 / 密钥校验
    // =========================================================

    private static TelemetryConfigEntity telemetryConfig(String id) {
        TelemetryConfigEntity c = new TelemetryConfigEntity();
        c.id = id;
        c.gameId = "game_1";
        c.configType = TelemetryConfigEntity.ConfigType.BATCH;
        c.configStatus = TelemetryConfigEntity.ConfigStatus.ACTIVE;
        return c;
    }

    @Test
    @DisplayName("开发者门户：getEffectiveConfig 依次回退到游戏级/全局/内置默认配置")
    void portalEffectiveConfigFallbackChain() {
        TelemetryConfigEntity gameLevel = telemetryConfig("tc_game");
        lenient().when(telemetryConfigRepo.findActiveByGameIdAndEnvironmentIdAndType(
            eq("game_1"), eq("prod"), any())).thenReturn(List.of());
        lenient().when(telemetryConfigRepo.findActiveByGameIdAndType(eq("game_1"), any()))
            .thenReturn(List.of(gameLevel));
        assertEquals("tc_game", developerPortalService.getEffectiveConfig(
            "game_1", "prod", TelemetryConfigEntity.ConfigType.BATCH).id);

        TelemetryConfigEntity global = telemetryConfig("tc_global");
        lenient().when(telemetryConfigRepo.findActiveByGameIdAndType(eq("game_1"), any()))
            .thenReturn(List.of());
        lenient().when(telemetryConfigRepo.findActiveGlobalConfigs()).thenReturn(List.of(global));
        assertEquals("tc_global", developerPortalService.getEffectiveConfig(
            "game_1", "prod", TelemetryConfigEntity.ConfigType.BATCH).id);

        lenient().when(telemetryConfigRepo.findActiveGlobalConfigs()).thenReturn(List.of());
        TelemetryConfigEntity fallback = developerPortalService.getEffectiveConfig(
            "game_1", "prod", TelemetryConfigEntity.ConfigType.BATCH);
        assertNull(fallback.id);
        assertTrue(fallback.isDefault);
        assertEquals("REALTIME", fallback.deliveryMode);
        assertEquals(500, fallback.batchSize);
        assertEquals("GZIP", fallback.compressionAlgorithm);
        assertEquals(1.0, fallback.sampleRate);
    }

    @Test
    @DisplayName("开发者门户：版本发布/弃用/退役前置状态校验")
    void portalVersionLifecycleGuards() {
        SDKVersionEntity draft = new SDKVersionEntity();
        draft.id = "sv_1";
        draft.version = "2.0.0";
        draft.platform = SDKVersionEntity.SDKPlatform.UNITY;
        draft.versionStatus = SDKVersionEntity.VersionStatus.DRAFT;
        lenient().when(sdkVersionRepo.findById("sv_1")).thenReturn(Optional.of(draft));
        lenient().when(sdkVersionRepo.save(any(SDKVersionEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        SDKVersionEntity released = developerPortalService.releaseVersion(
            "sv_1", "https://dl/2.0.0", "com.x.sdk", "maven", "sha256", 1024L, "rel");
        assertEquals(SDKVersionEntity.VersionStatus.RELEASED, released.versionStatus);
        assertEquals("sha256", released.checksumSha256);
        assertEquals(1024L, released.fileSizeBytes);
        verify(auditLog).logUpdate(eq("sdk_version"), eq("sv_1"), eq("2.0.0"),
            eq("rel"), eq("rel"), isNull(), anyMap());

        assertThrows(IllegalStateException.class,
            () -> developerPortalService.releaseVersion("sv_1", null, null, null, null, null, "rel"));

        SDKVersionEntity unreleased = new SDKVersionEntity();
        unreleased.id = "sv_2";
        unreleased.versionStatus = SDKVersionEntity.VersionStatus.BETA;
        lenient().when(sdkVersionRepo.findById("sv_2")).thenReturn(Optional.of(unreleased));
        assertThrows(IllegalStateException.class,
            () -> developerPortalService.deprecateVersion("sv_2", "notice", "op"));

        SDKVersionEntity notDeprecated = new SDKVersionEntity();
        notDeprecated.id = "sv_3";
        notDeprecated.versionStatus = SDKVersionEntity.VersionStatus.RELEASED;
        lenient().when(sdkVersionRepo.findById("sv_3")).thenReturn(Optional.of(notDeprecated));
        assertThrows(IllegalStateException.class,
            () -> developerPortalService.retireVersion("sv_3", "op"));
    }

    @Test
    @DisplayName("开发者门户：validateSDKKey 拒绝未知公钥/非活跃密钥/环境不匹配")
    void portalValidateSdkKeyRejectsInvalidStates() {
        SDKKeyEntity suspended = new SDKKeyEntity();
        suspended.id = "sdk_1";
        suspended.gameId = "game_1";
        suspended.environment = "prod";
        suspended.keyStatus = SDKKeyEntity.KeyStatus.SUSPENDED;
        lenient().when(sdkKeyRepo.findByPublicKeyAndDeletedAtIsNull("pk_susp")).thenReturn(Optional.of(suspended));
        assertFalse(developerPortalService.validateSDKKey("pk_susp", "game_1", "prod"));

        SDKKeyEntity envMismatch = new SDKKeyEntity();
        envMismatch.gameId = "game_1";
        envMismatch.environment = "prod";
        envMismatch.keyStatus = SDKKeyEntity.KeyStatus.ACTIVE;
        lenient().when(sdkKeyRepo.findByPublicKeyAndDeletedAtIsNull("pk_env")).thenReturn(Optional.of(envMismatch));
        assertFalse(developerPortalService.validateSDKKey("pk_env", "game_1", "dev"));
        assertTrue(developerPortalService.validateSDKKey("pk_env", "game_1", "prod"));

        lenient().when(sdkKeyRepo.findByPublicKeyAndDeletedAtIsNull("pk_ghost")).thenReturn(Optional.empty());
        assertFalse(developerPortalService.validateSDKKey("pk_ghost", "game_1", "prod"));
    }

    // =========================================================
    // IntegrationService：统计聚合 / 调用统计成功率
    // =========================================================

    private static IntegrationEntity integration(String id, IntegrationEntity.IntegrationStatus status,
                                                 boolean enabled) {
        IntegrationEntity e = new IntegrationEntity();
        e.id = id;
        e.gameId = "game_1";
        e.name = "hook-" + id;
        e.integrationType = IntegrationEntity.IntegrationType.SLACK;
        e.integrationStatus = status;
        e.enabled = enabled;
        return e;
    }

    @Test
    @DisplayName("集成：getIntegrationStats 按状态与类型聚合")
    void integrationStatsAggregation() {
        lenient().when(integrationRepo.findByGameId("game_1")).thenReturn(List.of(
            integration("i_active", IntegrationEntity.IntegrationStatus.ACTIVE, true),
            integration("i_failed", IntegrationEntity.IntegrationStatus.FAILED, true),
            integration("i_inactive", IntegrationEntity.IntegrationStatus.INACTIVE, false)));

        Map<String, Object> stats = integrationService.getIntegrationStats("game_1");

        assertEquals(3L, stats.get("total"));
        assertEquals(1L, stats.get("active"));
        assertEquals(1L, stats.get("failed"));
        assertEquals(1L, stats.get("inactive"));
        assertEquals(Map.of("SLACK", 3L), stats.get("byType"));
    }

    @Test
    @DisplayName("集成：getCallStats 计算成功率与平均耗时")
    void integrationCallStatsSuccessRate() {
        lenient().when(integrationLogRepo.countCallsSince(eq("i_1"), any())).thenReturn(10L);
        lenient().when(integrationLogRepo.countSuccessCallsSince(eq("i_1"), any())).thenReturn(8L);
        lenient().when(integrationLogRepo.countFailedCallsSince(eq("i_1"), any())).thenReturn(2L);
        lenient().when(integrationLogRepo.averageDurationSince(eq("i_1"), any())).thenReturn(120L);

        Map<String, Object> stats = integrationService.getCallStats("i_1", LocalDateTime.now().minusHours(1));

        assertEquals(10L, stats.get("totalCalls"));
        assertEquals(8L, stats.get("successCalls"));
        assertEquals(2L, stats.get("failedCalls"));
        assertEquals(0.8, (double) stats.get("successRate"), 0.0001);
        assertEquals(120L, stats.get("averageDurationMs"));
    }

    @Test
    @DisplayName("集成：retryFailedIntegrations 对可重试集成触发验证")
    void integrationRetryTriggersVerify() {
        IntegrationEntity retryable = integration("i_retry", IntegrationEntity.IntegrationStatus.FAILED, true);
        lenient().when(integrationRepo.findRetryable()).thenReturn(List.of(retryable));
        lenient().when(integrationRepo.findById("i_retry")).thenReturn(Optional.of(retryable));
        lenient().when(integrationRepo.save(any(IntegrationEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        assertDoesNotThrow(() -> integrationService.retryFailedIntegrations());

        // verifyIntegration 内部两次 save（置 VERIFYING + 置 ACTIVE）
        verify(integrationRepo, times(2)).save(retryable);
        assertEquals(IntegrationEntity.IntegrationStatus.ACTIVE, retryable.integrationStatus);
    }

    // =========================================================
    // RiskMetricsService：environment 过滤分支（四参查询）
    // =========================================================

    @Test
    @DisplayName("风控指标：ruleHits/severity/actions 带 environment 走四参查询")
    void riskMetricsEnvFilteredQueries() {
        lenient().when(clickHouseClient.isAvailable()).thenReturn(true);
        lenient().when(clickHouseClient.query(anyString(), any(), any(), any(Timestamp.class)))
            .thenReturn(List.of());

        Map<String, Object> hits = riskMetricsService.ruleHits("g", "prod", 24);
        Map<String, Object> severity = riskMetricsService.severity("g", "prod", 24);
        Map<String, Object> actions = riskMetricsService.actions("g", "prod", 24);

        assertEquals(Boolean.TRUE, hits.get("available"));
        assertTrue(((List<?>) hits.get("rules")).isEmpty());
        assertEquals(0L, severity.get("total"));
        assertTrue(((List<?>) actions.get("recent")).isEmpty());

        verify(clickHouseClient).query(contains("GROUP BY rule_id"),
            eq("g"), eq("prod"), any(Timestamp.class));
        verify(clickHouseClient).query(contains("GROUP BY severity"),
            eq("g"), eq("prod"), any(Timestamp.class));
        verify(clickHouseClient).query(contains("GROUP BY risk_type"),
            eq("g"), eq("prod"), any(Timestamp.class));
        verify(clickHouseClient).query(contains("GROUP BY action"),
            eq("g"), eq("prod"), any(Timestamp.class));
        verify(clickHouseClient).query(contains("GROUP BY state"),
            eq("g"), eq("prod"), any(Timestamp.class));
        verify(clickHouseClient).query(contains("ORDER BY ts DESC"),
            eq("g"), eq("prod"), any(Timestamp.class));
    }

    // =========================================================
    // RiskRuleService：list 非法枚举过滤
    // =========================================================

    // =========================================================
    // FunnelConfigService：默认值 / 重名守卫 / 步骤重建 / 计数与守卫查询
    // =========================================================

    @Test
    @DisplayName("漏斗：createFunnel 填充默认 type/userKey/enabled 并保留指定 ID")
    void funnelCreateAppliesDefaults() {
        FunnelConfigEntity funnel = new FunnelConfigEntity();
        funnel.id = "funnel_custom";
        funnel.gameId = "game_1";
        funnel.name = "Onboarding";

        lenient().when(funnelConfigRepo.existsByGameIdAndNameAndDeletedAtIsNull("game_1", "Onboarding"))
            .thenReturn(false);
        lenient().when(funnelConfigRepo.save(any(FunnelConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        FunnelConfigEntity created = funnelConfigService.createFunnel(funnel);

        assertEquals("funnel_custom", created.id);
        assertEquals(FunnelConfigEntity.FunnelType.STANDARD, created.type);
        assertEquals("user_id", created.userKey);
        assertTrue(created.enabled);
    }

    @Test
    @DisplayName("漏斗：updateFunnel 重名拒绝、steps 重建、deleteFunnel 缺失抛异常")
    void funnelUpdateGuardsAndStepRebuild() {
        FunnelConfigEntity existing = new FunnelConfigEntity();
        existing.id = "funnel_1";
        existing.gameId = "game_1";
        existing.name = "Old";
        existing.enabled = true;
        lenient().when(funnelConfigRepo.findById("funnel_1")).thenReturn(Optional.of(existing));
        lenient().when(funnelConfigRepo.save(any(FunnelConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        FunnelConfigEntity rename = new FunnelConfigEntity();
        rename.name = "Taken";
        lenient().when(funnelConfigRepo.existsByGameIdAndNameAndDeletedAtIsNull("game_1", "Taken"))
            .thenReturn(true);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> funnelConfigService.updateFunnel("funnel_1", rename));
        assertTrue(ex.getMessage().contains("already exists"));

        FunnelConfigEntity withSteps = new FunnelConfigEntity();
        FunnelStepEntity step = new FunnelStepEntity();
        step.name = "S2";
        step.eventName = "level_complete";
        withSteps.steps = List.of(step);
        FunnelConfigEntity updated = funnelConfigService.updateFunnel("funnel_1", withSteps);
        assertEquals(1, updated.steps.size());
        verify(funnelStepRepo).deleteByFunnelId("funnel_1");
        verify(funnelStepRepo).save(step);
        assertSame(existing, step.funnel);

        lenient().when(funnelConfigRepo.findById("funnel_missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
            () -> funnelConfigService.deleteFunnel("funnel_missing"));
    }

    @Test
    @DisplayName("漏斗：类型过滤/计数查询与 addStep·updateStep·deleteStep 缺失守卫")
    void funnelQueriesAndStepGuards() {
        FunnelConfigEntity funnel = new FunnelConfigEntity();
        funnel.id = "funnel_1";
        funnel.gameId = "game_1";
        funnel.name = "F";
        funnel.enabled = true;
        lenient().when(funnelConfigRepo.findByGameIdAndTypeAndDeletedAtIsNull(
            "game_1", FunnelConfigEntity.FunnelType.STANDARD)).thenReturn(List.of(funnel));
        lenient().when(funnelConfigRepo.countByGameIdAndDeletedAtIsNull("game_1")).thenReturn(5L);
        lenient().when(funnelConfigRepo.countByGameIdAndEnabledTrueAndDeletedAtIsNull("game_1")).thenReturn(2L);

        assertEquals(1, funnelConfigService.findByGameIdAndType(
            "game_1", FunnelConfigEntity.FunnelType.STANDARD).size());
        assertEquals(5L, funnelConfigService.getFunnelCount("game_1"));
        assertEquals(2L, funnelConfigService.getEnabledFunnelCount("game_1"));

        lenient().when(funnelConfigRepo.findById("funnel_missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
            () -> funnelConfigService.addStep("funnel_missing", new FunnelStepEntity()));

        lenient().when(funnelStepRepo.findById(99L)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
            () -> funnelConfigService.updateStep(99L, new FunnelStepEntity()));
        assertThrows(IllegalArgumentException.class, () -> funnelConfigService.deleteStep(99L));
    }

    // =========================================================
    // PerformanceMonitorService：连接池/消费延迟/自定义仪表/漏斗转化
    // =========================================================

    @Test
    @DisplayName("性能监控：连接池、Kafka 延迟、自定义仪表与漏斗转化指标")
    void performancePoolLagCustomGaugeAndFunnel() {
        performanceMonitorService.recordDatabaseConnectionPool("hikari", 3, 2, 10);
        assertEquals(3.0, meterRegistry.find("oddsmaker.database.pool.active").tag("pool", "hikari").gauge().value());
        assertEquals(2.0, meterRegistry.find("oddsmaker.database.pool.idle").tag("pool", "hikari").gauge().value());
        assertEquals(10.0, meterRegistry.find("oddsmaker.database.pool.total").tag("pool", "hikari").gauge().value());

        performanceMonitorService.recordKafkaConsumerLag("events_raw", "ingest", 42);
        assertEquals(42.0, meterRegistry.find("oddsmaker.kafka.consumer.lag")
            .tag("topic", "events_raw").tag("group", "ingest").gauge().value());

        performanceMonitorService.setCustomGauge("custom.biz.gauge", 7);
        assertEquals(7.0, meterRegistry.find("custom.biz.gauge").gauge().value());
        performanceMonitorService.setCustomGauge("custom.biz.gauge", 9);
        assertEquals(9.0, meterRegistry.find("custom.biz.gauge").gauge().value());

        performanceMonitorService.recordFunnelConversion("game_1", "funnel_1", 2, 0.35);
        assertEquals(0.35, meterRegistry.find("oddsmaker.funnel.conversion")
            .tag("funnel_id", "funnel_1").tag("step", "2").gauge().value(), 0.0001);
    }

    // =========================================================
    // HealthMonitorService：内存/错误率阈值告警 + 告警升级
    // =========================================================

    @Test
    @DisplayName("健康监控：MEMORY/ERROR_RATE 超阈值创建对应 CRITICAL 告警")
    void healthCollectMetricMemoryAndErrorRateAlerts() {
        lenient().when(healthMetricRepo.save(any(HealthMetricEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        HealthMetricEntity memory = healthMonitorService.collectMetric(
            HealthMetricEntity.MetricType.MEMORY_USAGE, "node-1", 96.0);
        assertEquals("percent", memory.unit);
        assertEquals(80.0, memory.warningThreshold);
        assertEquals(95.0, memory.criticalThreshold);
        assertTrue(memory.isCritical());

        ArgumentCaptor<SystemAlertEntity> captor = ArgumentCaptor.forClass(SystemAlertEntity.class);
        verify(systemAlertRepo).save(captor.capture());
        assertEquals(SystemAlertEntity.AlertType.HIGH_MEMORY, captor.getValue().alertType);
        assertEquals(SystemAlertEntity.Severity.CRITICAL, captor.getValue().severity);
        assertEquals("node-1", captor.getValue().source);

        HealthMetricEntity errorRate = healthMonitorService.collectMetric(
            HealthMetricEntity.MetricType.ERROR_RATE, "api", 6.0);
        assertEquals("percent", errorRate.unit);
        assertEquals(5.0, errorRate.criticalThreshold);
        assertTrue(errorRate.isCritical());

        ArgumentCaptor<SystemAlertEntity> second = ArgumentCaptor.forClass(SystemAlertEntity.class);
        verify(systemAlertRepo, times(2)).save(second.capture());
        assertEquals(SystemAlertEntity.AlertType.HIGH_ERROR_RATE, second.getAllValues().get(1).alertType);
    }

    @Test
    @DisplayName("健康监控：checkAlertEscalations 升级到期告警并保存")
    void healthCheckAlertEscalations() {
        SystemAlertEntity alert = new SystemAlertEntity();
        alert.id = "alert_1";
        alert.alertStatus = SystemAlertEntity.AlertStatus.OPEN;
        alert.escalationLevel = 0;
        lenient().when(systemAlertRepo.findNeedingEscalation()).thenReturn(List.of(alert));
        lenient().when(systemAlertRepo.save(any(SystemAlertEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        assertDoesNotThrow(() -> healthMonitorService.checkAlertEscalations());

        assertEquals(1, alert.escalationLevel);
        assertNotNull(alert.escalatedAt);
        verify(systemAlertRepo).save(alert);

        lenient().when(systemAlertRepo.findNeedingEscalation()).thenThrow(new RuntimeException("boom"));
        assertDoesNotThrow(() -> healthMonitorService.checkAlertEscalations());
    }

    // =========================================================
    // RedeemCodeService：列表/详情/停用 / SHARED 生成码 / UNIQUE 上限 / 入参校验
    // =========================================================

    private static RedeemCodeBatchEntity batch(RedeemCodeBatchEntity.CodeType type, int total, int perUserLimit) {
        RedeemCodeBatchEntity b = new RedeemCodeBatchEntity();
        b.gameId = "game_demo";
        b.name = "活动批次";
        b.reward = "[{\"type\":\"item\",\"id\":\"gem\",\"count\":10}]";
        b.codeType = type;
        b.total = total;
        b.perUserLimit = perUserLimit;
        b.status = RedeemCodeBatchEntity.Status.ACTIVE;
        return b;
    }

    @Test
    @DisplayName("兑换码：listBatches/getBatch/disable 与游戏校验")
    void redeemBatchQueriesAndDisable() {
        GameEntity g = game("game_demo", GameEntity.GameStatus.LIVE);
        RedeemCodeBatchEntity b = batch(RedeemCodeBatchEntity.CodeType.SHARED, 0, 1);
        b.id = "rb_1";
        lenient().when(gameRepo.findById("game_demo")).thenReturn(Optional.of(g));
        lenient().when(gameRepo.findById("game_x")).thenReturn(Optional.empty());
        lenient().when(batchRepo.findByGameIdAndDeletedAtIsNullOrderByCreatedAtDesc("game_demo"))
            .thenReturn(List.of(b));
        lenient().when(batchRepo.findByIdAndDeletedAtIsNull("rb_1")).thenReturn(Optional.of(b));
        lenient().when(batchRepo.findByIdAndDeletedAtIsNull("rb_missing")).thenReturn(Optional.empty());
        lenient().when(batchRepo.save(any(RedeemCodeBatchEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        assertEquals(1, redeemCodeService.listBatches("game_demo").size());
        assertThrows(IllegalArgumentException.class, () -> redeemCodeService.listBatches("game_x"));

        assertSame(b, redeemCodeService.getBatch("rb_1"));
        assertNull(redeemCodeService.getBatch("rb_missing"));

        RedeemCodeBatchEntity disabled = redeemCodeService.disable("rb_1", "op");
        assertEquals(RedeemCodeBatchEntity.Status.DISABLED, disabled.status);
        verify(auditLog).logUpdate(eq("redeem_batch"), eq("rb_1"), eq("活动批次"),
            eq("op"), eq("op"), isNull(), anyMap());
    }

    @Test
    @DisplayName("兑换码：SHARED 未指定码时生成，UNIQUE 超上限与入参校验")
    void redeemCreateSharedGeneratedCodeAndGuards() {
        GameEntity g = game("game_demo", GameEntity.GameStatus.LIVE);
        lenient().when(gameRepo.findById("game_demo")).thenReturn(Optional.of(g));
        lenient().when(batchRepo.save(any(RedeemCodeBatchEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(codeRepo.save(any(RedeemCodeEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(codeRepo.findByCode(anyString())).thenReturn(Optional.empty());

        RedeemCodeBatchEntity created = redeemCodeService.createBatch(
            batch(RedeemCodeBatchEntity.CodeType.SHARED, 0, 1), null, 0, null, "op");

        ArgumentCaptor<RedeemCodeEntity> codeCaptor = ArgumentCaptor.forClass(RedeemCodeEntity.class);
        verify(codeRepo).save(codeCaptor.capture());
        assertNotNull(codeCaptor.getValue().code);
        assertFalse(codeCaptor.getValue().code.isBlank());
        assertEquals(created.id, codeCaptor.getValue().batchId);

        RedeemCodeBatchEntity huge = batch(RedeemCodeBatchEntity.CodeType.UNIQUE, 1_000_001, 1);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> redeemCodeService.createBatch(huge, null, 12, null, "op"));
        assertTrue(ex.getMessage().contains("total must be between"));

        assertThrows(IllegalArgumentException.class, () -> redeemCodeService.redeem("  ", "p1"));
        assertThrows(IllegalArgumentException.class, () -> redeemCodeService.redeem("CODE", " "));

        lenient().when(batchRepo.findByIdAndDeletedAtIsNull("rb_missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> redeemCodeService.listCodes("rb_missing"));
    }

    // =========================================================
    // PlayerExportService：get/list 分支 / download 守卫 / 分区归一化 / 保留期回退
    // =========================================================

    @Test
    @DisplayName("玩家导出：get 缺失抛异常，list 空 playerId 返回整游戏列表")
    void playerExportGetAndListBranches() {
        GameEntity g = game("game_demo", GameEntity.GameStatus.LIVE);
        lenient().when(gameRepo.findById("game_demo")).thenReturn(Optional.of(g));
        lenient().when(jobRepo.findById("pex_missing")).thenReturn(Optional.empty());
        lenient().when(jobRepo.findByGameIdOrderByCreatedAtDesc("game_demo"))
            .thenReturn(List.of(new PlayerExportJobEntity()));

        assertThrows(IllegalArgumentException.class, () -> playerExportService.get("pex_missing"));
        assertEquals(1, playerExportService.list("game_demo", "  ").size());
        verify(jobRepo).findByGameIdOrderByCreatedAtDesc("game_demo");
        verify(jobRepo, never()).findByGameIdAndPlayerIdOrderByCreatedAtDesc(anyString(), anyString());
    }

    @Test
    @DisplayName("玩家导出：download 对缺失文件/未知路径的守卫与 csv 类型")
    void playerExportDownloadGuards() throws Exception {
        PlayerExportJobEntity missingPath = new PlayerExportJobEntity();
        missingPath.id = "pex_1";
        missingPath.fileName = "a.zip";
        missingPath.status = PlayerExportJobEntity.Status.COMPLETED;
        missingPath.expiresAt = LocalDateTime.now().plusHours(1);
        lenient().when(jobRepo.findById("pex_1")).thenReturn(Optional.of(missingPath));

        IllegalStateException noPath = assertThrows(IllegalStateException.class,
            () -> playerExportService.download("pex_1"));
        assertTrue(noPath.getMessage().contains("missing"));

        PlayerExportJobEntity ghostFile = new PlayerExportJobEntity();
        ghostFile.id = "pex_2";
        ghostFile.fileName = "b.zip";
        ghostFile.exportFormat = "csv";
        ghostFile.status = PlayerExportJobEntity.Status.COMPLETED;
        ghostFile.expiresAt = LocalDateTime.now().plusHours(1);
        ghostFile.filePath = tempDir.resolve("ghost.zip").toString();
        lenient().when(jobRepo.findById("pex_2")).thenReturn(Optional.of(ghostFile));
        assertThrows(IllegalStateException.class, () -> playerExportService.download("pex_2"));

        PlayerExportJobEntity ready = new PlayerExportJobEntity();
        ready.id = "pex_3";
        ready.fileName = "c.zip";
        ready.exportFormat = "csv";
        ready.status = PlayerExportJobEntity.Status.COMPLETED;
        ready.expiresAt = LocalDateTime.now().plusHours(1);
        Path file = tempDir.resolve("c.zip");
        Files.write(file, "PK-dummy".getBytes(StandardCharsets.UTF_8));
        ready.filePath = file.toString();
        lenient().when(jobRepo.findById("pex_3")).thenReturn(Optional.of(ready));

        PlayerExportService.ExportedFile exported = playerExportService.download("pex_3");
        assertEquals("c.zip", exported.fileName());
        assertEquals("application/zip", exported.contentType());
        assertEquals("PK-dummy", new String(exported.content(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("玩家导出：create 分区去重按标准顺序，retentionHours<=0 回退 72 小时")
    void playerExportCreateNormalizesSectionsAndRetention() throws Exception {
        GameEntity g = game("game_demo", GameEntity.GameStatus.LIVE);
        lenient().when(gameRepo.findById("game_demo")).thenReturn(Optional.of(g));
        lenient().when(jobRepo.save(any(PlayerExportJobEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        PlayerExportJobEntity job = playerExportService.create(
            "game_demo", "p1", null, null, List.of("redeem-records", "profile", "redeem-records"), "ops");
        assertEquals(List.of("profile", "redeem-records"),
            objectMapper.readValue(job.sections, List.class));

        ReflectionTestUtils.setField(playerExportService, "retentionHours", 0);
        PlayerExportJobEntity fallback = playerExportService.create(
            "game_demo", "p1", null, null, null, "ops");
        assertTrue(fallback.expiresAt.isAfter(LocalDateTime.now().plusHours(71)));
        ReflectionTestUtils.setField(playerExportService, "retentionHours", 72);
    }

    // =========================================================
    // 补充：GameService updateGame 成功更新分支（状态不变更 + 审计）
    // =========================================================

    @Test
    @DisplayName("游戏：updateGame 无状态变更时直接落库并审计")
    void gameUpdateWithoutStatusChangeSaves() {
        GameEntity existing = game("g1", GameEntity.GameStatus.TESTING);
        lenient().when(gameRepo.findById("g1")).thenReturn(Optional.of(existing));
        lenient().when(gameRepo.save(any(GameEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        GameDTO dto = new GameDTO();
        dto.name = "Renamed";

        GameDTO updated = gameService.updateGame("g1", dto);

        assertNotNull(updated);
        verify(gameRepo).save(existing);
        verify(auditLog).logUpdate(eq("game"), eq("g1"), any(), eq("api"), eq("api"), isNull(), anyMap());
    }
}
