package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.api.ControlService;
import io.oddsmaker.control.api.InternalApiKeyController;
import io.oddsmaker.control.api.MailController;
import io.oddsmaker.control.api.Models;
import io.oddsmaker.control.api.RedeemCodeController;
import io.oddsmaker.control.api.RiskRuleController;
import io.oddsmaker.control.api.RoleAssignmentController;
import io.oddsmaker.control.dto.EventDefinitionDTO;
import io.oddsmaker.control.dto.EventPropertyDefinitionDTO;
import io.oddsmaker.control.dto.ExperimentDTO;
import io.oddsmaker.control.dto.GameDTO;
import io.oddsmaker.control.dto.StorageProfileDTO;
import io.oddsmaker.control.dto.UserDTO;
import io.oddsmaker.control.exception.RateLimitExceededException;
import io.oddsmaker.control.experiment.ExperimentEntity;
import io.oddsmaker.control.experiment.ExperimentRepo;
import io.oddsmaker.control.jpa.AuditLogEntity;
import io.oddsmaker.control.jpa.AuditLogRepo;
import io.oddsmaker.control.jpa.BlockListEntity;
import io.oddsmaker.control.jpa.BlockListRepo;
import io.oddsmaker.control.jpa.CohortEntity;
import io.oddsmaker.control.jpa.CohortRepo;
import io.oddsmaker.control.jpa.DataQualityRuleEntity;
import io.oddsmaker.control.jpa.DataQualityRuleRepo;
import io.oddsmaker.control.jpa.ExportJobEntity;
import io.oddsmaker.control.jpa.ExportJobRepo;
import io.oddsmaker.control.jpa.FlinkJobEntity;
import io.oddsmaker.control.jpa.FlinkJobRepo;
import io.oddsmaker.control.jpa.FunnelConfigEntity;
import io.oddsmaker.control.jpa.FunnelConfigRepo;
import io.oddsmaker.control.jpa.FunnelStepEntity;
import io.oddsmaker.control.jpa.FunnelStepRepo;
import io.oddsmaker.control.jpa.GameEntity;
import io.oddsmaker.control.jpa.GameEnvironmentEntity;
import io.oddsmaker.control.jpa.GameEnvironmentRepo;
import io.oddsmaker.control.jpa.GameRepo;
import io.oddsmaker.control.jpa.HealthCheckEntity;
import io.oddsmaker.control.jpa.HealthCheckRepo;
import io.oddsmaker.control.jpa.HealthMetricRepo;
import io.oddsmaker.control.jpa.IdentityEntity;
import io.oddsmaker.control.jpa.IdentityLinkRepo;
import io.oddsmaker.control.jpa.IdentityRepo;
import io.oddsmaker.control.jpa.IntegrationEntity;
import io.oddsmaker.control.jpa.IntegrationLogEntity;
import io.oddsmaker.control.jpa.IntegrationLogRepo;
import io.oddsmaker.control.jpa.IntegrationRepo;
import io.oddsmaker.control.jpa.MFAConfigEntity;
import io.oddsmaker.control.jpa.MFAConfigRepo;
import io.oddsmaker.control.jpa.MLModelEntity;
import io.oddsmaker.control.jpa.MLModelPredictionEntity;
import io.oddsmaker.control.jpa.MLModelRepo;
import io.oddsmaker.control.jpa.ModelPredictionRepo;
import io.oddsmaker.control.jpa.ModelTrainingRepo;
import io.oddsmaker.control.jpa.PermissionEntity;
import io.oddsmaker.control.jpa.PermissionRepo;
import io.oddsmaker.control.jpa.PipelineEntity;
import io.oddsmaker.control.jpa.PipelineJobEntity;
import io.oddsmaker.control.jpa.PipelineJobRepo;
import io.oddsmaker.control.jpa.PipelineRepo;
import io.oddsmaker.control.jpa.PlayerExportJobEntity;
import io.oddsmaker.control.jpa.PlayerExportJobRepo;
import io.oddsmaker.control.jpa.PlayerLoginLogRepo;
import io.oddsmaker.control.jpa.PlayerPaymentRepo;
import io.oddsmaker.control.jpa.RateLimitPolicyEntity;
import io.oddsmaker.control.jpa.RedeemRecordEntity;
import io.oddsmaker.control.jpa.RedeemRecordRepo;
import io.oddsmaker.control.jpa.ReportEntity;
import io.oddsmaker.control.jpa.ReportExecutionEntity;
import io.oddsmaker.control.jpa.ReportExecutionRepo;
import io.oddsmaker.control.jpa.ReportRepo;
import io.oddsmaker.control.jpa.RetentionAnalysisEntity;
import io.oddsmaker.control.jpa.RiskCaseRepo;
import io.oddsmaker.control.jpa.RiskRuleEntity;
import io.oddsmaker.control.jpa.RiskRuleRepo;
import io.oddsmaker.control.jpa.RoleEntity;
import io.oddsmaker.control.jpa.RoleRepo;
import io.oddsmaker.control.jpa.SDKKeyEntity;
import io.oddsmaker.control.jpa.SDKKeyRepo;
import io.oddsmaker.control.jpa.SDKVersionEntity;
import io.oddsmaker.control.jpa.SDKVersionRepo;
import io.oddsmaker.control.jpa.SSOConfigRepo;
import io.oddsmaker.control.jpa.SecurityPolicyRepo;
import io.oddsmaker.control.jpa.SecuritySessionRepo;
import io.oddsmaker.control.jpa.StorageProfileEntity;
import io.oddsmaker.control.jpa.StorageProfileRepo;
import io.oddsmaker.control.jpa.SystemAlertRepo;
import io.oddsmaker.control.jpa.TelemetryConfigRepo;
import io.oddsmaker.control.jpa.UserEntity;
import io.oddsmaker.control.jpa.UserRepo;
import io.oddsmaker.control.jpa.UserRoleEntity;
import io.oddsmaker.control.jpa.UserRoleRepo;
import io.oddsmaker.control.jpa.WebhookConfigEntity;
import io.oddsmaker.control.jpa.WebhookConfigRepo;
import io.oddsmaker.control.jpa.WebhookLogEntity;
import io.oddsmaker.control.jpa.WebhookLogRepo;
import io.oddsmaker.control.jpa.ApiKeyRepo;
import io.oddsmaker.control.jpa.EventDefinitionEntity;
import io.oddsmaker.control.jpa.EventPropertyDefinitionEntity;
import io.oddsmaker.control.jpa.IdentityLinkEntity;
import io.oddsmaker.control.jpa.MailClaimEntity;
import io.oddsmaker.control.security.AccessGuard;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖率收尾（第四轮）：针对 JaCoCo 未覆盖方法清单定向补测。
 * 与 FinalSweep2Test / ServicesFinalSweepTest / 各专项 Service 测试互补。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("覆盖率收尾测试（第四轮）")
class FinalSweep3Test {

    // ==================== 共享 Mock ====================

    @Mock
    private AuditLogRepo auditLogRepo;

    @Mock
    private AuditLogService auditLogService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private GameRepo gameRepo;
    @Mock
    private GameEnvironmentRepo gameEnvironmentRepo;
    @Mock
    private ExperimentRepo experimentRepo;
    @InjectMocks
    private ExperimentService experimentService;

    @Mock
    private UserRepo userRepo;
    @Mock
    private RoleRepo roleRepo;
    @Mock
    private PermissionRepo permissionRepo;
    @Mock
    private UserRoleRepo userRoleRepo;
    @InjectMocks
    private PermissionService permissionService;

    @Mock
    private HealthCheckRepo healthCheckRepo;
    @Mock
    private HealthMetricRepo healthMetricRepo;
    @Mock
    private SystemAlertRepo systemAlertRepo;
    @InjectMocks
    private HealthMonitorService healthMonitorService;

    @Mock
    private SDKKeyRepo sdkKeyRepo;
    @Mock
    private SDKVersionRepo sdkVersionRepo;
    @Mock
    private TelemetryConfigRepo telemetryConfigRepo;
    @InjectMocks
    private DeveloperPortalService developerPortalService;

    @Mock
    private ExportJobRepo exportJobRepo;
    @InjectMocks
    private ExportService exportService;

    @Mock
    private ClickHouseClient clickHouseClient;
    @InjectMocks
    private CrashMetricsService crashMetricsService;
    @InjectMocks
    private PredictionMetricsService predictionMetricsService;

    @Mock
    private IntegrationRepo integrationRepo;
    @Mock
    private IntegrationLogRepo integrationLogRepo;
    @InjectMocks
    private IntegrationService integrationService;

    @Mock
    private ReportRepo reportRepo;
    @Mock
    private ReportExecutionRepo executionRepo;
    @InjectMocks
    private ReportService reportService;

    @Mock
    private FlinkJobRepo flinkJobRepo;
    @Mock
    private RiskRuleRepo riskRuleRepo;
    @InjectMocks
    private FlinkJobService flinkJobService;

    @Mock
    private FunnelConfigRepo funnelConfigRepo;
    @Mock
    private FunnelStepRepo funnelStepRepo;
    @InjectMocks
    private FunnelConfigService funnelConfigService;

    @Mock
    private PipelineRepo pipelineRepo;
    @Mock
    private PipelineJobRepo pipelineJobRepo;
    @Mock
    private DataQualityRuleRepo dataQualityRuleRepo;
    @InjectMocks
    private PipelineService pipelineService;

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
    private ApiKeyRepo apiKeyRepo;
    @Mock
    private StorageProfileRepo storageProfileRepo;
    @InjectMocks
    private GameService gameService;

    @Mock
    private BlockListRepo blockListRepo;
    @Mock
    private RiskCaseRepo riskCaseRepo;
    @InjectMocks
    private BlockListService blockListService;

    @Mock
    private CohortRepo cohortRepo;
    @InjectMocks
    private CohortService cohortService;

    @Mock
    private MLModelRepo mlModelRepo;
    @Mock
    private ModelTrainingRepo modelTrainingRepo;
    @Mock
    private ModelPredictionRepo modelPredictionRepo;
    @InjectMocks
    private MLModelService mlModelService;

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
    private WebhookConfigRepo webhookConfigRepo;
    @Mock
    private WebhookLogRepo webhookLogRepo;
    @InjectMocks
    private WebhookService webhookService;
    @Mock
    private WebhookService webhookServiceMock;
    @Mock
    private ReviewQueueService reviewQueueService;
    @Mock
    private RiskActionRecorder riskActionRecorder;
    @InjectMocks
    private RiskEventConsumer riskEventConsumer;

    @InjectMocks
    private RiskRuleService riskRuleService;

    @InjectMocks
    private ControlService controlService;

    @BeforeEach
    void setUp() {
        lenient().when(auditLogRepo.save(any(AuditLogEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        objectMapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    }

    // ==================== ExperimentService ====================

    private GameEntity game(String id) {
        GameEntity g = new GameEntity();
        g.id = id;
        return g;
    }

    private GameEnvironmentEntity env(String id, String gameId, String name) {
        GameEnvironmentEntity e = new GameEnvironmentEntity();
        e.id = id;
        e.gameId = gameId;
        e.name = name;
        return e;
    }

    @Test
    @DisplayName("ExperimentService.updateExperiment 全字段更新（name/salt回退/config/status=running 再校验）")
    void testUpdateExperimentSuccess() throws Exception {
        ExperimentEntity e = new ExperimentEntity();
        e.id = "exp1";
        e.gameId = "g1";
        e.environmentId = "env1";
        e.name = "old";
        e.status = "draft";
        e.salt = "salt1";
        e.configJson = "{\"variants\":[{\"name\":\"a\",\"weight\":1},{\"name\":\"b\",\"weight\":2}]}";
        when(experimentRepo.findById("exp1")).thenReturn(Optional.of(e));
        when(experimentRepo.save(any(ExperimentEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(gameEnvironmentRepo.findById("env1")).thenReturn(Optional.of(env("env1", "g1", "dev")));

        ExperimentDTO dto = new ExperimentDTO();
        dto.gameId = "g1";
        dto.environmentId = "env1";
        dto.name = "new-name";
        dto.salt = "   ";
        dto.config = objectMapper.readTree("{\"variants\":[{\"name\":\"a\",\"weight\":3},{\"name\":\"b\",\"weight\":7}]}");
        dto.status = "running";

        ExperimentDTO out = experimentService.updateExperiment("exp1", dto);
        assertEquals("new-name", out.name);
        assertEquals("running", out.status);
        assertEquals("exp1", out.salt);
        assertTrue(out.config.has("variants"));
        verify(experimentRepo, times(1)).save(any(ExperimentEntity.class));
    }

    @Test
    @DisplayName("ExperimentService.updateExperiment 游戏不可变更")
    void testUpdateExperimentGameChangeThrows() {
        ExperimentEntity e = new ExperimentEntity();
        e.id = "exp1";
        e.gameId = "g1";
        e.environmentId = "env1";
        e.name = "n";
        e.status = "draft";
        when(experimentRepo.findById("exp1")).thenReturn(Optional.of(e));
        ExperimentDTO dto = new ExperimentDTO();
        dto.gameId = "g2";
        assertThrows(IllegalArgumentException.class,
            () -> experimentService.updateExperiment("exp1", dto));
    }

    @Test
    @DisplayName("ExperimentService.updateExperiment 环境不可变更（解析出的环境 id 不同）")
    void testUpdateExperimentEnvironmentChangeThrows() {
        ExperimentEntity e = new ExperimentEntity();
        e.id = "exp1";
        e.gameId = "g1";
        e.environmentId = "env1";
        e.name = "n";
        e.status = "draft";
        when(experimentRepo.findById("exp1")).thenReturn(Optional.of(e));
        when(gameEnvironmentRepo.findByGameIdAndNameAndDeletedAtIsNull("g1", "prod"))
            .thenReturn(List.of(env("env2", "g1", "prod")));
        ExperimentDTO dto = new ExperimentDTO();
        dto.gameId = "g1";
        dto.environment = "prod";
        assertThrows(IllegalArgumentException.class,
            () -> experimentService.updateExperiment("exp1", dto));
    }

    @Test
    @DisplayName("ExperimentService.resolveEnvironment 环境属于其他游戏 / id 未命中回退按名查找")
    void testResolveEnvironmentBranches() {
        ExperimentEntity e = new ExperimentEntity();
        e.id = "exp1";
        e.gameId = "g1";
        e.environmentId = "env1";
        e.name = "n";
        e.status = "draft";
        when(experimentRepo.findById("exp1")).thenReturn(Optional.of(e));

        GameEnvironmentEntity foreign = env("envX", "g2", "dev");
        when(gameEnvironmentRepo.findById("envX")).thenReturn(Optional.of(foreign));
        ExperimentDTO dto = new ExperimentDTO();
        dto.gameId = "g1";
        dto.environmentId = "envX";
        assertThrows(IllegalArgumentException.class,
            () -> experimentService.updateExperiment("exp1", dto));

        when(gameEnvironmentRepo.findById("env9")).thenReturn(Optional.empty());
        when(gameEnvironmentRepo.findByGameIdAndNameAndDeletedAtIsNull("g1", "env9"))
            .thenReturn(List.of(env("env1", "g1", "env9")));
        ExperimentDTO dto2 = new ExperimentDTO();
        dto2.gameId = "g1";
        dto2.environmentId = "env9";
        dto2.name = "renamed";
        when(experimentRepo.save(any(ExperimentEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(gameEnvironmentRepo.findById("env1")).thenReturn(Optional.of(env("env1", "g1", "env9")));
        assertEquals("renamed", experimentService.updateExperiment("exp1", dto2).name);
    }

    @Test
    @DisplayName("ExperimentService.updateExperiment 环境名为空抛异常 / running 缺 variants 抛异常 / 不存在抛异常")
    void testUpdateExperimentErrorBranches() throws Exception {
        when(experimentRepo.findById("missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
            () -> experimentService.updateExperiment("missing", new ExperimentDTO()));

        ExperimentEntity e = new ExperimentEntity();
        e.id = "exp1";
        e.gameId = "g1";
        e.environmentId = "env1";
        e.name = "n";
        e.status = "draft";
        e.configJson = "{}";
        when(experimentRepo.findById("exp1")).thenReturn(Optional.of(e));
        ExperimentDTO dto = new ExperimentDTO();
        dto.status = "running";
        assertThrows(IllegalArgumentException.class,
            () -> experimentService.updateExperiment("exp1", dto));

        ExperimentDTO noEnv = new ExperimentDTO();
        noEnv.environment = "  ";
        noEnv.environmentId = "  ";
        assertThrows(IllegalArgumentException.class,
            () -> experimentService.updateExperiment("exp1", noEnv));

        ExperimentDTO badStatus = new ExperimentDTO();
        badStatus.status = "bogus";
        assertThrows(IllegalArgumentException.class,
            () -> experimentService.updateExperiment("exp1", badStatus));
    }

    private void assertCreateConfigFails(String status, String configJson) throws Exception {
        when(gameRepo.findById("g1")).thenReturn(Optional.of(game("g1")));
        when(gameEnvironmentRepo.findByGameIdAndNameAndDeletedAtIsNull(eq("g1"), anyString()))
            .thenReturn(List.of(env("env1", "g1", "dev")));
        when(experimentRepo.existsById(anyString())).thenReturn(false);
        ExperimentDTO dto = new ExperimentDTO();
        dto.gameId = "g1";
        dto.environment = "dev";
        dto.name = "n";
        dto.status = status;
        dto.config = objectMapper.readTree(configJson);
        assertThrows(IllegalArgumentException.class,
            () -> experimentService.createExperiment(dto));
    }

    @Test
    @DisplayName("ExperimentService.validateConfig 各非法分支")
    void testValidateConfigBranches() throws Exception {
        assertCreateConfigFails(null, "{\"targeting\":\"not-object\"}");
        assertCreateConfigFails(null, "{\"metrics\":[]}");
        assertCreateConfigFails(null, "{\"variants\":\"not-array\"}");
        assertCreateConfigFails(null, "{\"variants\":[{\"name\":\"a\",\"weight\":1}]}");
        assertCreateConfigFails(null, "{\"variants\":[{\"name\":\"a\",\"weight\":1},\"plain\"]}");
        assertCreateConfigFails(null, "{\"variants\":[{\"name\":\"a\",\"weight\":1},{\"weight\":2}]}");
        assertCreateConfigFails(null, "{\"variants\":[{\"name\":\"  \",\"weight\":1},{\"name\":\"b\",\"weight\":2}]}");
        assertCreateConfigFails(null, "{\"variants\":[{\"name\":123,\"weight\":1},{\"name\":\"b\",\"weight\":2}]}");
        assertCreateConfigFails(null, "{\"variants\":[{\"name\":\"a\",\"weight\":1},{\"name\":\"a\",\"weight\":2}]}");
        assertCreateConfigFails(null, "{\"variants\":[{\"name\":\"a\",\"weight\":0},{\"name\":\"b\",\"weight\":2}]}");
        assertCreateConfigFails(null, "{\"variants\":[{\"name\":\"a\",\"weight\":1.5},{\"name\":\"b\",\"weight\":2}]}");
        assertCreateConfigFails("running", "{}");
        assertCreateConfigFails("bogus", "{}");
    }

    @Test
    @DisplayName("ExperimentService.createExperiment 成功（salt 缺省回退 id + config 写入）")
    void testCreateExperimentSuccess() throws Exception {
        when(gameRepo.findById("g1")).thenReturn(Optional.of(game("g1")));
        when(gameEnvironmentRepo.findByGameIdAndNameAndDeletedAtIsNull("g1", "dev"))
            .thenReturn(List.of(env("env1", "g1", "dev")));
        when(gameEnvironmentRepo.findById("env1")).thenReturn(Optional.of(env("env1", "g1", "dev")));
        when(experimentRepo.existsById(anyString())).thenReturn(false);
        when(experimentRepo.save(any(ExperimentEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ExperimentDTO dto = new ExperimentDTO();
        dto.gameId = "g1";
        dto.environment = "Dev";
        dto.name = "exp-name";
        dto.config = objectMapper.readTree(
            "{\"variants\":[{\"name\":\"a\",\"weight\":1},{\"name\":\"b\",\"weight\":2}],\"targeting\":{},\"metrics\":{}}");
        ExperimentDTO out = experimentService.createExperiment(dto);
        assertEquals("draft", out.status);
        assertEquals(out.id, out.salt);
        assertEquals("env1", out.environmentId);
        assertTrue(out.config.has("variants"));
    }

    // ==================== PermissionService ====================

    private RoleEntity role(String id, String... permissionIds) {
        RoleEntity r = new RoleEntity();
        r.id = id;
        r.enabled = true;
        Set<PermissionEntity> perms = new java.util.HashSet<>();
        for (String pid : permissionIds) {
            PermissionEntity p = new PermissionEntity();
            p.id = pid;
            perms.add(p);
        }
        r.permissions = perms;
        return r;
    }

    private UserRoleEntity ur(String roleId, String gameId) {
        UserRoleEntity ur = new UserRoleEntity();
        ur.userId = "u1";
        ur.roleId = roleId;
        ur.gameId = gameId;
        return ur;
    }

    @Test
    @DisplayName("PermissionService.hasGamePermission 游戏级角色授权")
    void testHasGamePermissionViaGameRole() {
        UserEntity user = new UserEntity();
        when(userRepo.findById("u1")).thenReturn(Optional.of(user));
        when(userRoleRepo.findByUserIdAndGameId("u1", "g1")).thenReturn(List.of(ur("r1", "g1")));
        when(roleRepo.findById("r1")).thenReturn(Optional.of(role("r1", "game:read")));
        assertTrue(permissionService.hasGamePermission("u1", "g1", "game:read"));
    }

    @Test
    @DisplayName("PermissionService.hasGamePermission 全局角色兜底授权")
    void testHasGamePermissionViaGlobalRole() {
        UserEntity user = new UserEntity();
        when(userRepo.findById("u1")).thenReturn(Optional.of(user));
        when(userRoleRepo.findByUserIdAndGameId("u1", "g1")).thenReturn(List.of());
        when(userRoleRepo.findGlobalByUserId("u1")).thenReturn(List.of(ur("r2", null)));
        when(roleRepo.findById("r2")).thenReturn(Optional.of(role("r2", "game:read")));
        assertTrue(permissionService.hasGamePermission("u1", "g1", "game:read"));
    }

    @Test
    @DisplayName("PermissionService.hasGamePermission 失效分配被跳过")
    void testHasGamePermissionSkipsInvalidAssignments() {
        UserEntity user = new UserEntity();
        when(userRepo.findById("u1")).thenReturn(Optional.of(user));
        UserRoleEntity expired = ur("r1", "g1");
        expired.expiresAt = LocalDateTime.now().minusDays(1);
        when(userRoleRepo.findByUserIdAndGameId("u1", "g1")).thenReturn(List.of(expired));
        UserRoleEntity expiredGlobal = ur("r2", null);
        expiredGlobal.enabled = false;
        when(userRoleRepo.findGlobalByUserId("u1")).thenReturn(List.of(expiredGlobal));
        assertFalse(permissionService.hasGamePermission("u1", "g1", "game:read"));

        when(userRepo.findById("u0")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
            () -> permissionService.hasGamePermission("u0", "g1", "game:read"));
    }

    @Test
    @DisplayName("PermissionService.getGamePermissions 汇总游戏级 + 全局角色权限")
    void testGetGamePermissions() {
        when(userRoleRepo.findByUserIdAndGameId("u1", "g1"))
            .thenReturn(List.of(ur("r1", "g1")));
        when(roleRepo.findById("r1")).thenReturn(Optional.of(role("r1", "a", "b")));
        UserRoleEntity expired = ur("r3", "g1");
        expired.expiresAt = LocalDateTime.now().minusHours(1);
        when(userRoleRepo.findGlobalByUserId("u1")).thenReturn(List.of(expired, ur("r2", null)));
        when(roleRepo.findById("r2")).thenReturn(Optional.of(role("r2", "c")));
        when(roleRepo.findById("r3")).thenReturn(Optional.of(role("r3", "dead")));

        Set<String> perms = permissionService.getGamePermissions("u1", "g1");
        assertEquals(Set.of("a", "b", "c"), perms);
    }

    // ==================== HealthMonitorService ====================

    @Test
    @DisplayName("HealthMonitorService.performHealthCheck 成功与未找到")
    void testPerformHealthCheck() {
        HealthCheckEntity check = new HealthCheckEntity();
        check.checkName = "primary-db";
        check.checkType = HealthCheckEntity.CheckType.DATABASE;
        when(healthCheckRepo.findByName("primary-db")).thenReturn(Optional.of(check));
        when(healthCheckRepo.save(any(HealthCheckEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        HealthCheckEntity out = healthMonitorService.performHealthCheck("primary-db");
        assertNotNull(out.lastCheckedAt);
        assertNotNull(out.responseTimeMs);
        assertTrue(out.totalChecks >= 1);

        when(healthCheckRepo.findByName("ghost")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
            () -> healthMonitorService.performHealthCheck("ghost"));
    }

    // ==================== DeveloperPortalService ====================

    @Test
    @DisplayName("DeveloperPortalService.cleanupExpiredKeys 过期密钥标记 EXPIRED")
    void testCleanupExpiredKeys() {
        SDKKeyEntity k1 = new SDKKeyEntity();
        k1.id = "sdk1";
        SDKKeyEntity k2 = new SDKKeyEntity();
        k2.id = "sdk2";
        when(sdkKeyRepo.findExpired()).thenReturn(List.of(k1, k2));
        when(sdkKeyRepo.save(any(SDKKeyEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        developerPortalService.cleanupExpiredKeys();
        assertEquals(SDKKeyEntity.KeyStatus.EXPIRED, k1.keyStatus);
        assertEquals(SDKKeyEntity.KeyStatus.EXPIRED, k2.keyStatus);
        verify(sdkKeyRepo, times(2)).save(any(SDKKeyEntity.class));
    }

    @Test
    @DisplayName("DeveloperPortalService.checkRetiringVersions 遍历即将退役版本")
    void testCheckRetiringVersions() {
        SDKVersionEntity v = new SDKVersionEntity();
        v.version = "1.2.3";
        v.platform = SDKVersionEntity.SDKPlatform.values()[0];
        v.retirementDate = LocalDateTime.now().plusDays(10);
        when(sdkVersionRepo.findRetiringSoon(any(LocalDateTime.class))).thenReturn(List.of(v));
        assertDoesNotThrow(() -> developerPortalService.checkRetiringVersions());
        verify(sdkVersionRepo).findRetiringSoon(any(LocalDateTime.class));
    }

    // ==================== ExportService ====================

    private ExportJobEntity pendingJob(String id) {
        ExportJobEntity job = new ExportJobEntity();
        job.id = id;
        job.gameId = "g1";
        job.exportType = "events";
        job.exportFormat = "json";
        job.fileName = id + ".json";
        job.notifyOnComplete = true;
        job.notificationEmail = "ops@example.com";
        return job;
    }

    @Test
    @DisplayName("ExportService.processExportJob 完成导出并触发通知分支")
    void testProcessExportJob() {
        ExportJobEntity job = pendingJob("ex1");
        when(exportJobRepo.findById("ex1")).thenReturn(Optional.of(job));
        when(exportJobRepo.save(any(ExportJobEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ExportJobEntity out = exportService.processExportJob("ex1");
        assertTrue(out.isCompleted());
        assertNotNull(out.filePath);
        assertTrue(out.fileSizeBytes != null && out.fileSizeBytes > 0);

        ExportJobEntity done = pendingJob("ex2");
        done.exportStatus = ExportJobEntity.ExportStatus.COMPLETED;
        when(exportJobRepo.findById("ex2")).thenReturn(Optional.of(done));
        assertThrows(IllegalStateException.class, () -> exportService.processExportJob("ex2"));
    }

    @Test
    @DisplayName("ExportService.processPendingExports 扫描处理 PENDING 任务")
    void testProcessPendingExports() {
        ExportJobEntity job = pendingJob("ex1");
        when(exportJobRepo.findPending()).thenReturn(List.of(job));
        when(exportJobRepo.findById("ex1")).thenReturn(Optional.of(job));
        when(exportJobRepo.save(any(ExportJobEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        assertDoesNotThrow(() -> exportService.processPendingExports());
        assertTrue(job.isCompleted());
    }

    // ==================== CrashMetricsService ====================

    @Test
    @DisplayName("CrashMetricsService.topGroups 不可用 / 带环境 / 不带环境")
    void testTopGroups() {
        when(clickHouseClient.isAvailable()).thenReturn(false);
        Map<String, Object> unavailable = crashMetricsService.topGroups("g1", null, 30);
        assertEquals(false, unavailable.get("available"));

        when(clickHouseClient.isAvailable()).thenReturn(true);
        when(clickHouseClient.query(anyString(), eq("g1"), eq("prod"), any(LocalDate.class)))
            .thenReturn(List.of());
        Map<String, Object> withEnv = crashMetricsService.topGroups("g1", "prod", 30);
        assertEquals(true, withEnv.get("available"));
        assertEquals(List.of(), withEnv.get("groups"));

        when(clickHouseClient.query(anyString(), eq("g1"), any(LocalDate.class)))
            .thenReturn(List.of());
        Map<String, Object> noEnv = crashMetricsService.topGroups("g1", "  ", 0);
        assertEquals(true, noEnv.get("available"));
        assertEquals(14, noEnv.get("days"));
    }

    // ==================== IntegrationService ====================

    @Test
    @DisplayName("IntegrationService.verifyIntegration 健康检查通过激活")
    void testVerifyIntegration() {
        IntegrationEntity integration = new IntegrationEntity();
        integration.id = "i1";
        integration.gameId = "g1";
        integration.integrationType = IntegrationEntity.IntegrationType.SLACK;
        integration.endpointUrl = "http://example/hook";
        when(integrationRepo.findById("i1")).thenReturn(Optional.of(integration));
        when(integrationRepo.save(any(IntegrationEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        IntegrationEntity out = integrationService.verifyIntegration("i1");
        assertTrue(out.isActive());
        verify(integrationRepo, atLeastOnce()).save(any(IntegrationEntity.class));
    }

    @Test
    @DisplayName("IntegrationService.retryFailedIntegrations 调度重试")
    void testRetryFailedIntegrations() {
        IntegrationEntity integration = new IntegrationEntity();
        integration.id = "i1";
        integration.gameId = "g1";
        integration.integrationType = IntegrationEntity.IntegrationType.SLACK;
        when(integrationRepo.findRetryable()).thenReturn(List.of(integration));
        when(integrationRepo.findById("i1")).thenReturn(Optional.of(integration));
        when(integrationRepo.save(any(IntegrationEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        assertDoesNotThrow(() -> integrationService.retryFailedIntegrations());
        assertTrue(integration.isActive());
    }

    @Test
    @DisplayName("IntegrationService.buildHeaders 三种鉴权头（经 callIntegration 走真实 RestTemplate 失败路径）")
    void testBuildHeadersAuthVariants() {
        for (IntegrationEntity.AuthType authType : List.of(
                IntegrationEntity.AuthType.API_KEY,
                IntegrationEntity.AuthType.BEARER_TOKEN,
                IntegrationEntity.AuthType.BASIC_AUTH)) {
            IntegrationEntity integration = new IntegrationEntity();
            integration.id = "i-" + authType.name();
            integration.gameId = "g1";
            integration.integrationType = IntegrationEntity.IntegrationType.SLACK;
            integration.endpointUrl = "http://127.0.0.1:1/hook";
            integration.integrationStatus = IntegrationEntity.IntegrationStatus.ACTIVE;
            integration.authType = authType;
            integration.apiKey = "ak";
            integration.bearerToken = "bt";
            integration.username = "user";
            integration.password = "pass";
            when(integrationRepo.findById(integration.id)).thenReturn(Optional.of(integration));
            when(integrationLogRepo.save(any(IntegrationLogEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            IntegrationLogEntity log = integrationService.callIntegration(integration.id, "ping", Map.of("k", "v"), "cid");
            assertNotNull(log);
            assertTrue(log.isFailed());
            assertTrue(integration.retryCount >= 1);
        }
    }

    // ==================== PredictionMetricsService ====================

    @Test
    @DisplayName("PredictionMetricsService.topRiskScore 不可用与可用（含特征回填分支）")
    void testTopRiskScore() {
        when(clickHouseClient.isAvailable()).thenReturn(false);
        Map<String, Object> unavailable = predictionMetricsService.topRiskScore("g1", "prod", 10);
        assertEquals(false, unavailable.get("available"));

        when(clickHouseClient.isAvailable()).thenReturn(true);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("user_id", "u1");
        row.put("score", 0.9);
        row.put("predicted_at", Timestamp.valueOf("2026-01-01 00:00:00"));
        row.put("days_inactive", 5L);
        row.put("session_count", 3L);
        row.put("revenue_total", 12.5);
        when(clickHouseClient.query(anyString(), eq("g1"), eq("prod"))).thenReturn(List.of(row));
        Map<String, Object> withEnv = predictionMetricsService.topRiskScore("g1", "prod", 10);
        assertEquals(true, withEnv.get("available"));
        List<Map<String, Object>> users = (List<Map<String, Object>>) withEnv.get("users");
        assertEquals(1, users.size());
        assertEquals("u1", users.get(0).get("userId"));
        assertEquals(5L, users.get(0).get("daysInactive"));

        when(clickHouseClient.query(anyString(), eq("g1"))).thenReturn(List.of());
        Map<String, Object> noEnv = predictionMetricsService.topRiskScore("g1", null, 0);
        assertEquals(List.of(), noEnv.get("users"));
    }

    // ==================== AuditLogService.logCreate（resourceType-first） ====================

    @Test
    @DisplayName("AuditLogService.logCreate resourceType-first：username 缺省回退 + metadata.gameId 注入")
    void testLogCreateResourceTypeFirst() {
        AuditLogService real = new AuditLogService();
        org.springframework.test.util.ReflectionTestUtils.setField(real, "auditLogRepo", auditLogRepo);

        AuditLogEntity out = real.logCreate("game", "g1", "My Game", "u1", null, null,
            Map.of("gameId", "g2"));
        assertNotNull(out);
        assertEquals("u1", out.username);
        assertEquals("g2", out.gameId);
        assertEquals(AuditLogEntity.AuditAction.CREATE, out.action);
        assertEquals("game", out.resourceType);

        AuditLogEntity withUsername = real.logCreate("game", "g1", "My Game", "u1", "bob",
            "1.2.3.4", (Map<String, ?>) null);
        assertEquals("bob", withUsername.username);
        assertEquals("1.2.3.4", withUsername.ipAddress);
        assertNull(withUsername.gameId);

        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogRepo, times(2)).save(captor.capture());
        assertEquals(AuditLogEntity.AuditStatus.SUCCESS, captor.getValue().status);
    }

    // ==================== ReportService ====================

    @Test
    @DisplayName("ReportService.createReport 重名抛异常与创建成功")
    void testCreateReport() {
        when(reportRepo.findByGameIdAndName("g1", "r1"))
            .thenReturn(Optional.of(new ReportEntity()));
        assertThrows(IllegalArgumentException.class, () -> reportService.createReport(
            "g1", null, "r1", "R1", "d", null, null, null,
            Map.of("metric", "dau"), null, "creator"));

        when(reportRepo.findByGameIdAndName("g1", "r2")).thenReturn(Optional.empty());
        when(reportRepo.save(any(ReportEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        ReportEntity created = reportService.createReport(
            "g1", "env1", "r2", "R2", "d", ReportEntity.ReportType.SCHEDULED, "growth",
            Map.of("metric", "retention"), Map.of("chart", "line"), "bar", "creator");
        assertEquals("r2", created.name);
        assertEquals(ReportEntity.ReportType.SCHEDULED, created.reportType);
        assertTrue(created.queryConfig.contains("retention"));
        assertTrue(created.visualization.contains("line"));
    }

    @Test
    @DisplayName("ReportService.executeReportAsync 成功完成（markAsRunning→markAsCompleted）")
    void testExecuteReportSuccess() {
        ReportEntity report = new ReportEntity();
        report.id = "r1";
        report.gameId = "g1";
        report.name = "r1";
        when(reportRepo.findById("r1")).thenReturn(Optional.of(report));
        when(executionRepo.save(any(ReportExecutionEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(reportRepo.save(any(ReportEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ReportExecutionEntity execution = reportService.executeReport(
            "r1", "api", "manual", Map.of("p", "1"), Map.of("f", "2"));
        assertTrue(execution.isCompleted());
        assertTrue(execution.parameters.contains("p"));
        assertTrue(execution.filters.contains("f"));
        assertEquals(1, report.totalRuns);
    }

    @Test
    @DisplayName("ReportService.executeReportAsync 失败回滚（保存报表抛异常→FAILED）")
    void testExecuteReportAsyncFailure() {
        ReportEntity report = new ReportEntity();
        report.id = "r1";
        report.gameId = "g1";
        report.name = "r1";
        when(reportRepo.findById("r1")).thenReturn(Optional.of(report));
        when(executionRepo.save(any(ReportExecutionEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(reportRepo.save(any(ReportEntity.class)))
            .thenThrow(new RuntimeException("boom"))
            .thenAnswer(inv -> inv.getArgument(0));

        ReportExecutionEntity execution = reportService.executeReport("r1", "api", null, null, null);
        assertTrue(execution.isFailed());
        verify(reportRepo, times(2)).save(any(ReportEntity.class));
    }

    // ==================== FlinkJobService ====================

    @Test
    @DisplayName("FlinkJobService.createJob 重名抛异常与创建成功")
    void testCreateJob() {
        when(flinkJobRepo.findByGameIdAndName("g1", "j1"))
            .thenReturn(Optional.of(new FlinkJobEntity()));
        assertThrows(IllegalArgumentException.class, () -> flinkJobService.createJob(
            "g1", "env1", "j1", "J1", "d", null,
            Map.of("k", "v"), Map.of("s", "v"), Map.of("t", "v"),
            List.of("r1"), null, "creator"));

        when(flinkJobRepo.findByGameIdAndName("g1", "j2")).thenReturn(Optional.empty());
        when(flinkJobRepo.save(any(FlinkJobEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        FlinkJobEntity job = flinkJobService.createJob(
            "g1", "env1", "j2", "J2", "d", null,
            Map.of("k", "v"), Map.of("s", "v"), Map.of("t", "v"),
            List.of("r1"), 4, "creator");
        assertEquals("j2", job.name);
        assertEquals(4, job.parallelism);
        assertTrue(job.jobConfig.contains("k"));
        assertTrue(job.ruleIds.contains("r1"));
        verify(auditLogService).logCreate(eq("flink_job"), eq(job.id), eq("j2"), eq("creator"),
            isNull(), isNull(), anyMap());
    }

    // ==================== FunnelConfigService ====================

    @Test
    @DisplayName("FunnelConfigService.createFunnel 默认值回填与步骤级联保存")
    void testCreateFunnel() {
        FunnelConfigEntity funnel = new FunnelConfigEntity();
        funnel.gameId = "g1";
        funnel.name = "purchase";
        funnel.type = null;
        funnel.userKey = null;
        funnel.enabled = null;
        FunnelStepEntity step = new FunnelStepEntity();
        step.name = "login";
        funnel.steps = new ArrayList<>(List.of(step));

        when(funnelConfigRepo.existsByGameIdAndNameAndDeletedAtIsNull("g1", "purchase")).thenReturn(false);
        when(funnelConfigRepo.save(any(FunnelConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(funnelStepRepo.save(any(FunnelStepEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        FunnelConfigEntity out = funnelConfigService.createFunnel(funnel);
        assertTrue(out.id.startsWith("funnel_"));
        assertEquals(FunnelConfigEntity.FunnelType.STANDARD, out.type);
        assertEquals("user_id", out.userKey);
        assertEquals(Boolean.TRUE, out.enabled);
        assertSame(out, step.funnel);
        verify(funnelStepRepo).save(step);

        when(funnelConfigRepo.existsByGameIdAndNameAndDeletedAtIsNull("g1", "purchase")).thenReturn(true);
        FunnelConfigEntity dup = new FunnelConfigEntity();
        dup.gameId = "g1";
        dup.name = "purchase";
        assertThrows(IllegalArgumentException.class, () -> funnelConfigService.createFunnel(dup));
    }

    @Test
    @DisplayName("FunnelConfigService.updateStep 全字段更新与未找到")
    void testUpdateStep() {
        FunnelStepEntity step = new FunnelStepEntity();
        when(funnelStepRepo.findById(5L)).thenReturn(Optional.of(step));
        when(funnelStepRepo.save(any(FunnelStepEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        FunnelStepEntity updates = new FunnelStepEntity();
        updates.name = "pay";
        updates.description = "d";
        updates.eventName = "purchase";
        updates.eventFilter = "{\"amount\":1}";
        updates.timeWindowSec = 60L;
        updates.optional = true;
        updates.icon = "cart";
        updates.color = "blue";
        FunnelStepEntity out = funnelConfigService.updateStep(5L, updates);
        assertEquals("pay", out.name);
        assertEquals("d", out.description);
        assertEquals("purchase", out.eventName);
        assertEquals("{\"amount\":1}", out.eventFilter);
        assertEquals(60L, out.timeWindowSec);
        assertEquals(Boolean.TRUE, out.optional);
        assertEquals("cart", out.icon);
        assertEquals("blue", out.color);

        when(funnelStepRepo.findById(6L)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
            () -> funnelConfigService.updateStep(6L, new FunnelStepEntity()));
    }

    // ==================== PipelineService ====================

    private PipelineEntity activePipeline() {
        PipelineEntity pipeline = new PipelineEntity();
        pipeline.id = "p1";
        pipeline.gameId = "g1";
        pipeline.pipelineName = "etl";
        pipeline.pipelineStatus = PipelineEntity.PipelineStatus.ACTIVE;
        return pipeline;
    }

    @Test
    @DisplayName("PipelineService.executePipelineJob 活跃管道执行完成（含质量规则过滤）")
    void testExecutePipeline() {
        PipelineEntity pipeline = activePipeline();
        when(pipelineRepo.findById("p1")).thenReturn(Optional.of(pipeline));
        DataQualityRuleEntity active = new DataQualityRuleEntity();
        active.ruleName = "not-null";
        active.actionOnFailure = "stop";
        DataQualityRuleEntity inactive = new DataQualityRuleEntity();
        inactive.ruleName = "inactive";
        inactive.ruleStatus = DataQualityRuleEntity.RuleStatus.INACTIVE;
        when(dataQualityRuleRepo.findByPipelineId("p1")).thenReturn(List.of(active, inactive));
        when(pipelineJobRepo.save(any(PipelineJobEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(pipelineRepo.save(any(PipelineEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        PipelineJobEntity job = pipelineService.executePipeline("p1", "api");
        assertTrue(job.isCompleted());
        assertEquals(1, pipeline.runCount);

        PipelineEntity draft = new PipelineEntity();
        draft.id = "p2";
        when(pipelineRepo.findById("p2")).thenReturn(Optional.of(draft));
        assertThrows(IllegalStateException.class, () -> pipelineService.executePipeline("p2", "api"));
    }

    @Test
    @DisplayName("PipelineService.executeScheduledPipelines 调度扫描执行")
    void testExecuteScheduledPipelines() {
        PipelineEntity pipeline = activePipeline();
        when(pipelineRepo.findScheduledPipelines(any(LocalDateTime.class))).thenReturn(List.of(pipeline));
        when(pipelineJobRepo.save(any(PipelineJobEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(pipelineRepo.save(any(PipelineEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(dataQualityRuleRepo.findByPipelineId("p1")).thenReturn(List.of());

        assertDoesNotThrow(() -> pipelineService.executeScheduledPipelines());
        assertEquals(1, pipeline.runCount);
    }

    // ==================== PlayerExportService ====================

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

    // ==================== GameService ====================

    private void stubEnvCreate(String normalizedName) {
        when(gameRepo.findById("g1")).thenReturn(Optional.of(game("g1")));
        when(gameEnvironmentRepo.findByGameIdAndNameAndDeletedAtIsNull("g1", normalizedName))
            .thenReturn(List.of());
        when(storageProfileRepo.existsById(anyString())).thenReturn(false, false, true);
        lenient().when(storageProfileRepo.save(any(StorageProfileEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        when(gameEnvironmentRepo.save(any(GameEnvironmentEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("GameService.createEnvironment dev：默认 displayName/存储档案/命名空间/分库")
    void testCreateEnvironmentDev() {
        stubEnvCreate("dev");
        io.oddsmaker.control.dto.EnvironmentDTO dto = new io.oddsmaker.control.dto.EnvironmentDTO();
        dto.name = " Dev ";
        io.oddsmaker.control.dto.EnvironmentDTO out = gameService.createEnvironment("g1", dto);
        assertEquals("dev", out.name);
        assertEquals("Development", out.displayName);
        assertEquals("shared-nonprod", out.storageProfileId);
        assertEquals("g1_dev", out.dataNamespace);
        assertEquals("g1_dev", out.kafkaTopicPrefix);
        assertEquals("game_g1_dev", out.databaseName);
    }

    @Test
    @DisplayName("GameService.defaultDisplayName qa/prod/自定义分支")
    void testCreateEnvironmentDisplayNameVariants() {
        stubEnvCreate("qa");
        io.oddsmaker.control.dto.EnvironmentDTO qa = new io.oddsmaker.control.dto.EnvironmentDTO();
        qa.name = "qa";
        assertEquals("QA", gameService.createEnvironment("g1", qa).displayName);

        stubEnvCreate("prod");
        io.oddsmaker.control.dto.EnvironmentDTO prod = new io.oddsmaker.control.dto.EnvironmentDTO();
        prod.name = "prod";
        io.oddsmaker.control.dto.EnvironmentDTO prodOut = gameService.createEnvironment("g1", prod);
        assertEquals("Production", prodOut.displayName);
        assertEquals("shared-prod", prodOut.storageProfileId);

        stubEnvCreate("eu");
        io.oddsmaker.control.dto.EnvironmentDTO eu = new io.oddsmaker.control.dto.EnvironmentDTO();
        eu.name = "eu";
        assertEquals("Eu", gameService.createEnvironment("g1", eu).displayName);

        stubEnvCreate("loadtest");
        io.oddsmaker.control.dto.EnvironmentDTO lt = new io.oddsmaker.control.dto.EnvironmentDTO();
        lt.name = "loadtest";
        assertEquals("Load Test", gameService.createEnvironment("g1", lt).displayName);
    }

    @Test
    @DisplayName("GameService.createEnvironment 环境重名抛异常")
    void testCreateEnvironmentDuplicate() {
        when(gameRepo.findById("g1")).thenReturn(Optional.of(game("g1")));
        when(gameEnvironmentRepo.findByGameIdAndNameAndDeletedAtIsNull("g1", "dev"))
            .thenReturn(List.of(env("env1", "g1", "dev")));
        io.oddsmaker.control.dto.EnvironmentDTO dto = new io.oddsmaker.control.dto.EnvironmentDTO();
        dto.name = "dev";
        assertThrows(IllegalArgumentException.class, () -> gameService.createEnvironment("g1", dto));
    }

    @Test
    @DisplayName("GameService.validateStatusChange 合法/非法状态迁移")
    void testValidateStatusChange() {
        GameEntity dev = new GameEntity();
        dev.id = "g1";
        dev.status = GameEntity.GameStatus.DEVELOPMENT;
        when(gameRepo.findById("g1")).thenReturn(Optional.of(dev));
        when(gameRepo.save(any(GameEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        GameDTO toTesting = new GameDTO();
        toTesting.status = GameEntity.GameStatus.TESTING;
        assertEquals(GameEntity.GameStatus.TESTING, gameService.updateGame("g1", toTesting).status);

        GameEntity devAgain = new GameEntity();
        devAgain.id = "g1";
        devAgain.status = GameEntity.GameStatus.DEVELOPMENT;
        when(gameRepo.findById("g1")).thenReturn(Optional.of(devAgain));
        GameDTO toLive = new GameDTO();
        toLive.status = GameEntity.GameStatus.LIVE;
        assertThrows(IllegalArgumentException.class, () -> gameService.updateGame("g1", toLive));

        GameEntity dead = new GameEntity();
        dead.id = "g1";
        dead.status = GameEntity.GameStatus.DISCONTINUED;
        when(gameRepo.findById("g1")).thenReturn(Optional.of(dead));
        assertThrows(IllegalArgumentException.class, () -> gameService.updateGame("g1", toLive));
    }

    // ==================== BlockListService ====================

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

    // ==================== CohortService ====================

    @Test
    @DisplayName("CohortService.createCohort 重名抛异常与创建成功（含序列化）")
    void testCreateCohort() {
        when(cohortRepo.findByGameIdAndName("g1", "c1")).thenReturn(Optional.of(new CohortEntity()));
        assertThrows(IllegalArgumentException.class, () -> cohortService.createCohort(
            "g1", null, "c1", "C1", "d", null, null, null, null, null, null,
            null, null, null));

        when(cohortRepo.findByGameIdAndName("g1", "c2")).thenReturn(Optional.empty());
        when(cohortRepo.save(any(CohortEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        CohortEntity out = cohortService.createCohort(
            "g1", "env1", "c2", "C2", "d", null, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1),
            "week", "retention", "return_rate",
            Map.of("event", "login"), List.of(1, 7), "creator");
        assertEquals("c2", out.name);
        assertTrue(out.behaviorDefinition.contains("login"));
        assertEquals("[1,7]", out.retentionPeriods);
        assertEquals(CohortEntity.CohortStatus.PENDING, out.status);
    }

    @Test
    @DisplayName("CohortService.calculateCohort 成功完成与失败回滚")
    void testCalculateCohort() {
        CohortEntity cohort = new CohortEntity();
        cohort.id = "ch1";
        cohort.name = "c";
        cohort.retentionPeriods = "[1,7]";
        when(cohortRepo.findById("ch1")).thenReturn(Optional.of(cohort));
        when(cohortRepo.save(any(CohortEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        CohortEntity out = cohortService.calculateCohort("ch1");
        assertEquals(CohortEntity.CohortStatus.COMPLETED, out.status);
        assertNotNull(out.resultData);
        assertTrue(out.cohortCount >= 1000);

        CohortEntity failing = new CohortEntity();
        failing.id = "ch2";
        failing.name = "f";
        when(cohortRepo.findById("ch2")).thenReturn(Optional.of(failing));
        when(cohortRepo.save(any(CohortEntity.class)))
            .thenReturn(failing)
            .thenThrow(new IllegalStateException("db down"));
        assertThrows(RuntimeException.class, () -> cohortService.calculateCohort("ch2"));
        assertEquals(CohortEntity.CohortStatus.FAILED, failing.status);
    }

    @Test
    @DisplayName("CohortService.processPendingCohorts 扫描处理")
    void testProcessPendingCohorts() {
        CohortEntity cohort = new CohortEntity();
        cohort.id = "ch1";
        cohort.name = "c";
        cohort.retentionPeriods = "[1,7]";
        when(cohortRepo.findPending()).thenReturn(List.of(cohort));
        when(cohortRepo.findById("ch1")).thenReturn(Optional.of(cohort));
        when(cohortRepo.save(any(CohortEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        assertDoesNotThrow(() -> cohortService.processPendingCohorts());
        assertEquals(CohortEntity.CohortStatus.COMPLETED, cohort.status);
    }

    // ==================== MLModelService ====================

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

    // ==================== IdentityConsumer.resolveEnvironmentId ====================

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

    // ==================== SecurityService.verifyMFACode ====================

    private MFAConfigEntity mfa(MFAConfigEntity.MFAMethod method) {
        MFAConfigEntity c = new MFAConfigEntity();
        c.userId = "u1";
        c.mfaMethod = method;
        return c;
    }

    @Test
    @DisplayName("SecurityService.verifyMFACode TOTP/SMS/EMAIL/HARDWARE 分支")
    void testVerifyMfaCodeBranches() {
        when(mfaConfigRepo.findEnabledByUserId("u1")).thenReturn(List.of(mfa(MFAConfigEntity.MFAMethod.TOTP)));
        assertTrue(securityService.verifyMFA("u1", "123456"));
        verify(mfaConfigRepo).save(any(MFAConfigEntity.class));

        when(mfaConfigRepo.findEnabledByUserId("u1")).thenReturn(List.of(mfa(MFAConfigEntity.MFAMethod.TOTP)));
        assertFalse(securityService.verifyMFA("u1", "12a456"));

        when(mfaConfigRepo.findEnabledByUserId("u1")).thenReturn(List.of(mfa(MFAConfigEntity.MFAMethod.SMS)));
        assertFalse(securityService.verifyMFA("u1", "abc12d"));

        when(mfaConfigRepo.findEnabledByUserId("u1")).thenReturn(List.of(mfa(MFAConfigEntity.MFAMethod.EMAIL)));
        assertFalse(securityService.verifyMFA("u1", "12345"));

        when(mfaConfigRepo.findEnabledByUserId("u1")).thenReturn(List.of(mfa(MFAConfigEntity.MFAMethod.HARDWARE_TOKEN)));
        assertFalse(securityService.verifyMFA("u1", "123456"));
    }

    // ==================== WebhookService.retryWebhook ====================

    @Test
    @DisplayName("WebhookService.retryWebhook 重发失败并安排重试（经 processPendingRetries，restTemplate 为 null 走异常分支）")
    void testRetryWebhook() {
        WebhookConfigEntity config = new WebhookConfigEntity();
        config.id = "w1";
        config.name = "hook";
        config.gameId = "g1";
        config.webhookUrl = "http://example/cb";
        config.httpMethod = "POST";
        config.requestHeaders = "{\"X-Test\":\"1\"}";
        config.authType = "bearer";
        config.authConfig = "{\"token\":\"tok\"}";
        config.maxRetries = 3;
        config.retryBackoffMs = 10;

        WebhookLogEntity log1 = new WebhookLogEntity();
        log1.webhookConfigId = "w1";
        log1.eventType = "risk_event";
        log1.eventId = "e1";
        log1.requestBody = "{\"a\":1}";
        log1.retryCount = 0;
        WebhookLogEntity log2 = new WebhookLogEntity();
        log2.webhookConfigId = "w2";

        when(webhookLogRepo.findPendingRetries(any(LocalDateTime.class))).thenReturn(List.of(log1, log2));
        when(webhookConfigRepo.findById("w1")).thenReturn(Optional.of(config));
        when(webhookConfigRepo.findById("w2")).thenReturn(Optional.empty());
        when(webhookLogRepo.save(any(WebhookLogEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(webhookConfigRepo.save(any(WebhookConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        assertDoesNotThrow(() -> webhookService.processPendingRetries());
        ArgumentCaptor<WebhookLogEntity> logCaptor = ArgumentCaptor.forClass(WebhookLogEntity.class);
        verify(webhookLogRepo, atLeastOnce()).save(logCaptor.capture());
        assertTrue(logCaptor.getAllValues().stream()
            .anyMatch(l -> l.deliveryStatus == WebhookLogEntity.DeliveryStatus.RETRYING
                && l.nextRetryAt != null && l.retryCount != null && l.retryCount >= 1));
        assertEquals(WebhookLogEntity.DeliveryStatus.SENDING, log1.deliveryStatus);
        verify(webhookConfigRepo).save(config);
    }

    // ==================== RiskEventConsumer ====================

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

    // ==================== RiskRuleService.list ====================

    @Test
    @DisplayName("RiskRuleService.list Specification 分页查询")
    void testRiskRuleList() {
        RiskRuleEntity rule = new RiskRuleEntity();
        rule.id = "rr1";
        rule.gameId = "g1";
        when(riskRuleRepo.findAll(any(Specification.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(rule)));

        Page<RiskRuleEntity> page = riskRuleService.list("g1", "env1", "ACTIVE", "THRESHOLD", "q", 0, 10);
        assertEquals(1, page.getTotalElements());
        assertEquals("rr1", page.getContent().get(0).id);
    }

    // ==================== JPA 实体 ====================

    @Test
    @DisplayName("RetentionAnalysisEntity.calculateNextCalcTime 各频率分支")
    void testCalculateNextCalcTime() {
        RetentionAnalysisEntity never = new RetentionAnalysisEntity();
        assertNotNull(never.calculateNextCalcTime());

        LocalDateTime base = LocalDateTime.of(2026, 1, 1, 0, 0);
        RetentionAnalysisEntity hourly = new RetentionAnalysisEntity();
        hourly.lastCalculatedAt = base;
        hourly.calcFrequency = "hourly";
        assertEquals(base.plusHours(1), hourly.calculateNextCalcTime());

        RetentionAnalysisEntity daily = new RetentionAnalysisEntity();
        daily.lastCalculatedAt = base;
        daily.calcFrequency = "daily";
        assertEquals(base.plusDays(1), daily.calculateNextCalcTime());

        RetentionAnalysisEntity weekly = new RetentionAnalysisEntity();
        weekly.lastCalculatedAt = base;
        weekly.calcFrequency = "weekly";
        assertEquals(base.plusWeeks(1), weekly.calculateNextCalcTime());

        RetentionAnalysisEntity unknown = new RetentionAnalysisEntity();
        unknown.lastCalculatedAt = base;
        unknown.calcFrequency = "monthly";
        assertEquals(base.plusDays(1), unknown.calculateNextCalcTime());
    }

    @Test
    @DisplayName("RateLimitPolicyEntity.isOverLimit / getLimitPerDay 全分支")
    void testRateLimitPolicyEntity() {
        RateLimitPolicyEntity clean = new RateLimitPolicyEntity();
        assertFalse(clean.isOverLimit());

        RateLimitPolicyEntity noReset = new RateLimitPolicyEntity();
        noReset.limitExceededAt = LocalDateTime.of(2026, 1, 1, 10, 0);
        assertTrue(noReset.isOverLimit());

        RateLimitPolicyEntity afterReset = new RateLimitPolicyEntity();
        afterReset.limitExceededAt = LocalDateTime.of(2026, 1, 1, 10, 0);
        afterReset.lastResetAt = LocalDateTime.of(2026, 1, 1, 9, 0);
        assertTrue(afterReset.isOverLimit());

        RateLimitPolicyEntity beforeReset = new RateLimitPolicyEntity();
        beforeReset.limitExceededAt = LocalDateTime.of(2026, 1, 1, 9, 0);
        beforeReset.lastResetAt = LocalDateTime.of(2026, 1, 1, 10, 0);
        assertFalse(beforeReset.isOverLimit());

        RateLimitPolicyEntity perDay = new RateLimitPolicyEntity();
        perDay.eventsPerDay = 100L;
        assertEquals(100L, perDay.getLimitPerDay());

        RateLimitPolicyEntity perMinute = new RateLimitPolicyEntity();
        perMinute.eventsPerMinute = 10;
        assertEquals(14400L, perMinute.getLimitPerDay());

        RateLimitPolicyEntity perSecond = new RateLimitPolicyEntity();
        perSecond.eventsPerSecond = 1;
        assertEquals(86400L, perSecond.getLimitPerDay());

        assertEquals(Long.MAX_VALUE, new RateLimitPolicyEntity().getLimitPerDay());
    }

    // ==================== DTO updateEntity ====================

    @Test
    @DisplayName("EventPropertyDefinitionDTO.updateEntity 全字段映射")
    void testEventPropertyDefinitionUpdateEntity() {
        EventPropertyDefinitionEntity e = new EventPropertyDefinitionEntity();
        EventPropertyDefinitionDTO d = new EventPropertyDefinitionDTO();
        d.displayName = "DN";
        d.description = "D";
        d.type = EventPropertyDefinitionEntity.PropertyType.FLOAT;
        d.arrayElementType = "string";
        d.required = true;
        d.defaultValue = "1.0";
        d.allowedValues = "[1,2]";
        d.cardinalityLimit = 50;
        d.minValue = 0.1;
        d.maxValue = 9.9;
        d.minLength = 1;
        d.maxLength = 10;
        d.regexPattern = "^a";
        d.isPii = true;
        d.piiType = "email";
        d.isIndexed = false;
        d.propertyGroup = "core";
        d.displayOrder = 3;
        d.status = EventPropertyDefinitionEntity.PropertyStatus.DEPRECATED;
        d.updateEntity(e);
        assertEquals("DN", e.displayName);
        assertEquals("D", e.description);
        assertEquals(EventPropertyDefinitionEntity.PropertyType.FLOAT, e.type);
        assertEquals("string", e.arrayElementType);
        assertEquals(Boolean.TRUE, e.required);
        assertEquals("1.0", e.defaultValue);
        assertEquals("[1,2]", e.allowedValues);
        assertEquals(50, e.cardinalityLimit);
        assertEquals(0.1, e.minValue);
        assertEquals(9.9, e.maxValue);
        assertEquals(1, e.minLength);
        assertEquals(10, e.maxLength);
        assertEquals("^a", e.regexPattern);
        assertEquals(Boolean.TRUE, e.isPii);
        assertEquals("email", e.piiType);
        assertEquals(Boolean.FALSE, e.isIndexed);
        assertEquals("core", e.propertyGroup);
        assertEquals(3, e.displayOrder);
        assertEquals(EventPropertyDefinitionEntity.PropertyStatus.DEPRECATED, e.status);
    }

    @Test
    @DisplayName("UserDTO.updateEntity 全字段映射")
    void testUserUpdateEntity() {
        UserEntity e = new UserEntity();
        UserDTO d = new UserDTO();
        d.email = "u@x.io";
        d.name = "N";
        d.displayName = "DN";
        d.avatarUrl = "http://a";
        d.globalRole = UserEntity.GlobalRole.OPERATOR;
        d.status = UserEntity.UserStatus.INACTIVE;
        d.company = "C";
        d.title = "T";
        d.phone = "+1";
        d.timeZone = "UTC";
        d.locale = "zh-CN";
        d.notificationEmail = false;
        d.notificationSms = true;
        d.dashboardTheme = "dark";
        d.updateEntity(e);
        assertEquals("u@x.io", e.email);
        assertEquals("N", e.name);
        assertEquals("DN", e.displayName);
        assertEquals("http://a", e.avatarUrl);
        assertEquals(UserEntity.GlobalRole.OPERATOR, e.globalRole);
        assertEquals(UserEntity.UserStatus.INACTIVE, e.status);
        assertEquals("C", e.company);
        assertEquals("T", e.title);
        assertEquals("+1", e.phone);
        assertEquals("UTC", e.timeZone);
        assertEquals("zh-CN", e.locale);
        assertEquals(Boolean.FALSE, e.notificationEmail);
        assertEquals(Boolean.TRUE, e.notificationSms);
        assertEquals("dark", e.dashboardTheme);
    }

    @Test
    @DisplayName("StorageProfileDTO.updateEntity 全字段映射")
    void testStorageProfileUpdateEntity() {
        StorageProfileEntity e = new StorageProfileEntity();
        StorageProfileDTO d = new StorageProfileDTO();
        d.name = "eu";
        d.displayName = "EU";
        d.description = "D";
        d.isolationStrategy = StorageProfileEntity.IsolationStrategy.DEDICATED;
        d.kafkaCluster = "k";
        d.clickhouseCluster = "ch";
        d.redisCluster = "r";
        d.archiveBucket = "b";
        d.active = false;
        d.updateEntity(e);
        assertEquals("eu", e.name);
        assertEquals("EU", e.displayName);
        assertEquals("D", e.description);
        assertEquals(StorageProfileEntity.IsolationStrategy.DEDICATED, e.isolationStrategy);
        assertEquals("k", e.kafkaCluster);
        assertEquals("ch", e.clickhouseCluster);
        assertEquals("r", e.redisCluster);
        assertEquals("b", e.archiveBucket);
        assertEquals(Boolean.FALSE, e.active);
    }

    @Test
    @DisplayName("EventDefinitionDTO.updateEntity 全字段映射")
    void testEventDefinitionUpdateEntity() {
        EventDefinitionEntity e = new EventDefinitionEntity();
        EventDefinitionDTO d = new EventDefinitionDTO();
        d.displayName = "DN";
        d.description = "D";
        d.category = "eco";
        d.subcategory = "pay";
        d.identity = EventDefinitionEntity.EventIdentity.USER;
        d.requireUserId = true;
        d.requireSessionId = true;
        d.requirePlayerId = true;
        d.validationRules = "{}";
        d.examplePayload = "{}";
        d.docUrl = "http://doc";
        d.importance = EventDefinitionEntity.Importance.CRITICAL;
        d.status = EventDefinitionEntity.DefinitionStatus.DEPRECATED;
        d.updateEntity(e);
        assertEquals("DN", e.displayName);
        assertEquals("D", e.description);
        assertEquals("eco", e.category);
        assertEquals("pay", e.subcategory);
        assertEquals(EventDefinitionEntity.EventIdentity.USER, e.identity);
        assertEquals(Boolean.TRUE, e.requireUserId);
        assertEquals(Boolean.TRUE, e.requireSessionId);
        assertEquals(Boolean.TRUE, e.requirePlayerId);
        assertEquals("{}", e.validationRules);
        assertEquals("{}", e.examplePayload);
        assertEquals("http://doc", e.docUrl);
        assertEquals(EventDefinitionEntity.Importance.CRITICAL, e.importance);
        assertEquals(EventDefinitionEntity.DefinitionStatus.DEPRECATED, e.status);
    }

    // ==================== API 控制器 ====================

    @Test
    @DisplayName("InternalApiKeyController.getActiveKey 命中/未命中")
    void testInternalApiKeyController() {
        ControlService cs = mock(ControlService.class);
        InternalApiKeyController controller = new InternalApiKeyController(cs);
        Models.InternalApiKeyResp resp = new Models.InternalApiKeyResp();
        resp.apiKey = "pk1";
        when(cs.getActiveKeyForGateway("pk1")).thenReturn(resp);
        ResponseEntity<Models.InternalApiKeyResp> ok = controller.getActiveKey("pk1");
        assertEquals(200, ok.getStatusCode().value());
        assertEquals("pk1", ok.getBody().apiKey);

        when(cs.getActiveKeyForGateway("pk0")).thenReturn(null);
        assertEquals(404, controller.getActiveKey("pk0").getStatusCode().value());
    }

    @Test
    @DisplayName("MailController.claim 成功与 409")
    void testMailControllerClaim() {
        MailService mailService = mock(MailService.class);
        AccessGuard guard = mock(AccessGuard.class);
        MailController controller = new MailController(mailService, guard);

        MailClaimEntity claim = new MailClaimEntity();
        claim.id = "c1";
        claim.mailId = "m1";
        claim.playerKey = "k";
        claim.claimedAttachments = "sword";
        claim.claimedAt = LocalDateTime.now();
        when(mailService.claim("m1", "k")).thenReturn(claim);
        ResponseEntity<Map<String, Object>> ok = controller.claim("m1", "g1", "k");
        assertEquals(200, ok.getStatusCode().value());
        assertEquals("c1", ok.getBody().get("claimId"));
        assertEquals("sword", ok.getBody().get("attachments"));

        when(mailService.claim("m2", "k")).thenReturn(null);
        ResponseEntity<Map<String, Object>> conflict = controller.claim("m2", "g1", "k");
        assertEquals(409, conflict.getStatusCode().value());
        assertEquals("mail_not_claimable", conflict.getBody().get("error"));
    }

    @Test
    @DisplayName("RedeemCodeController.redeem 成功与 409")
    void testRedeemCodeControllerRedeem() {
        RedeemCodeService redeemCodeService = mock(RedeemCodeService.class);
        AccessGuard guard = mock(AccessGuard.class);
        RedeemCodeController controller = new RedeemCodeController(redeemCodeService, guard);

        RedeemRecordEntity record = new RedeemRecordEntity();
        record.id = "rr1";
        record.batchId = "b1";
        record.gameId = "g1";
        record.playerKey = "k";
        record.code = "CODE";
        record.reward = "gold";
        record.redeemedAt = LocalDateTime.now();
        when(redeemCodeService.redeem("g1", "CODE", "k")).thenReturn(record);
        ResponseEntity<Map<String, Object>> ok = controller.redeem("g1", "k", "CODE");
        assertEquals(200, ok.getStatusCode().value());
        assertEquals("rr1", ok.getBody().get("recordId"));
        assertEquals("gold", ok.getBody().get("reward"));

        when(redeemCodeService.redeem("g1", "DUP", "k"))
            .thenThrow(new IllegalStateException("already redeemed"));
        ResponseEntity<Map<String, Object>> conflict = controller.redeem("g1", "k", "DUP");
        assertEquals(409, conflict.getStatusCode().value());
        assertEquals("already redeemed", conflict.getBody().get("error"));
    }

    @Test
    @DisplayName("RoleAssignmentController.validateScope 环境维度缺 gameId 抛异常 / 合法分配")
    void testRoleAssignmentValidateScope() {
        PermissionService ps = mock(PermissionService.class);
        AccessGuard guard = mock(AccessGuard.class);
        AuditLogService audit = mock(AuditLogService.class);
        RoleAssignmentController controller = new RoleAssignmentController(ps, guard, audit);

        RoleAssignmentController.AssignReq bad = new RoleAssignmentController.AssignReq();
        bad.roleId = "viewer";
        bad.environment = "prod";
        bad.gameId = null;
        assertThrows(IllegalArgumentException.class, () -> controller.assign("u1", bad));

        RoleAssignmentController.AssignReq badRole = new RoleAssignmentController.AssignReq();
        badRole.roleId = "super_admin";
        assertThrows(IllegalArgumentException.class, () -> controller.assign("u1", badRole));

        RoleAssignmentController.AssignReq good = new RoleAssignmentController.AssignReq();
        good.roleId = "viewer";
        good.gameId = "g1";
        UserRoleEntity assignment = new UserRoleEntity();
        assignment.userId = "u1";
        assignment.roleId = "viewer";
        assignment.gameId = "g1";
        when(ps.assignRole(eq("u1"), eq("viewer"), eq("g1"), isNull(), anyString()))
            .thenReturn(assignment);
        ResponseEntity<Map<String, Object>> ok = controller.assign("u1", good);
        assertEquals(200, ok.getStatusCode().value());
        assertEquals("g1", ok.getBody().get("gameId"));
        assertEquals("", ok.getBody().get("environment"));
        assertEquals(Boolean.TRUE, ok.getBody().get("enabled"));
    }

    @Test
    @DisplayName("RiskRuleController.toResp 经 get 映射 / 未找到 404")
    void testRiskRuleControllerToResp() {
        RiskRuleService rrs = mock(RiskRuleService.class);
        AccessGuard guard = mock(AccessGuard.class);
        RiskRuleController controller = new RiskRuleController(rrs, guard);

        RiskRuleEntity rule = new RiskRuleEntity();
        rule.id = "rr1";
        rule.gameId = "g1";
        rule.name = "rule";
        rule.status = RiskRuleEntity.RuleStatus.ACTIVE;
        rule.ruleType = RiskRuleEntity.RuleType.THRESHOLD;
        when(rrs.get("rr1")).thenReturn(rule);
        ResponseEntity<RiskRuleController.RiskRuleResp> ok = controller.get("rr1");
        assertEquals(200, ok.getStatusCode().value());
        assertEquals("rr1", ok.getBody().id);
        assertEquals("THRESHOLD", ok.getBody().type);
        assertEquals(Boolean.TRUE, ok.getBody().enabled);

        when(rrs.get("missing")).thenReturn(null);
        assertEquals(404, controller.get("missing").getStatusCode().value());
    }

    @Test
    @DisplayName("ControlService.applyStorageProfile 经 createStorageProfile 应用与空名校验")
    void testApplyStorageProfile() {
        Models.CreateStorageProfileReq req = new Models.CreateStorageProfileReq();
        req.name = "eu-prod";
        req.isolationStrategy = " prod_isolated ";
        req.kafkaCluster = "k";
        req.clickhouseCluster = "ch";
        req.redisCluster = "r";
        req.archiveBucket = "b";
        when(storageProfileRepo.existsById("eu-prod")).thenReturn(false);
        when(storageProfileRepo.existsByNameAndDeletedAtIsNull("eu-prod")).thenReturn(false);
        when(storageProfileRepo.save(any(StorageProfileEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        Models.StorageProfileResp resp = controlService.createStorageProfile(req);
        assertEquals("eu-prod", resp.id);
        assertEquals("eu-prod", resp.displayName);
        assertEquals("PROD_ISOLATED", resp.isolationStrategy);
        assertEquals("k", resp.kafkaCluster);
        assertEquals("ch", resp.clickhouseCluster);
        assertEquals("r", resp.redisCluster);
        assertEquals("b", resp.archiveBucket);
        assertEquals(Boolean.TRUE, resp.active);

        ArgumentCaptor<StorageProfileEntity> captor = ArgumentCaptor.forClass(StorageProfileEntity.class);
        verify(storageProfileRepo).save(captor.capture());
        assertEquals("eu-prod", captor.getValue().name);

        Models.CreateStorageProfileReq blank = new Models.CreateStorageProfileReq();
        blank.name = "  ";
        assertThrows(IllegalArgumentException.class, () -> controlService.createStorageProfile(blank));
    }

    // ==================== RateLimitExceededException ====================

    @Test
    @DisplayName("RateLimitExceededException 两个构造器与访问器")
    void testRateLimitExceededException() {
        RateLimitExceededException simple = new RateLimitExceededException("too fast");
        assertEquals("too fast", simple.getMessage());
        assertNull(simple.getUserId());
        assertNull(simple.getEndpoint());
        assertEquals(0, simple.getRetryAfter());

        RateLimitExceededException full = new RateLimitExceededException("slow down", "u1", "/api/x", 30L);
        assertEquals("slow down", full.getMessage());
        assertEquals("u1", full.getUserId());
        assertEquals("/api/x", full.getEndpoint());
        assertEquals(30L, full.getRetryAfter());
    }
}
