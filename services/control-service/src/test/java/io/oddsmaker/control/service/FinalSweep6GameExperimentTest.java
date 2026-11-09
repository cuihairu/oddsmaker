package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.api.ControlService;
import io.oddsmaker.control.api.Models;
import io.oddsmaker.control.dto.EnvironmentDTO;
import io.oddsmaker.control.dto.EventPropertyDefinitionDTO;
import io.oddsmaker.control.dto.ExperimentDTO;
import io.oddsmaker.control.experiment.ExperimentSplitter;
import io.oddsmaker.control.jpa.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖率最终冲刺 6（LAY-007 拆分）：GameService 环境创建与发布校验，ControlService 存储档案分支，ExperimentService 配置非对象分支，TrackingPlanService 允许值分支，ExperimentSplitter 空变体，RemoteConfig 解析回退。与 GameServiceTest / ExperimentServiceTest / TrackingPlanServiceTest 互补。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("覆盖率冲刺 6：游戏/实验/追踪计划/远端配置")
class FinalSweep6GameExperimentTest {

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
    private io.oddsmaker.control.experiment.ExperimentRepo experimentRepo;

    @Mock
    private TrackingPlanRepo trackingPlanRepo;

    @Mock
    private EventDefinitionRepo eventDefinitionRepo;

    @Mock
    private EventPropertyDefinitionRepo propertyDefinitionRepo;

    @Mock
    private RemoteConfigRepo remoteConfigRepo;

    @Mock
    private ApiKeyRepo apiKeyRepoForControl;

    @Mock
    private io.oddsmaker.control.jpa.TrackingPlanRepo trackingPlanRepoForControl;

    @Mock
    private io.oddsmaker.control.jpa.EventDefinitionRepo eventDefinitionRepoForControl;

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

    @Test
    @DisplayName("applyStorageProfile：显式 displayName/active/strategy 与空名拒绝分支")
    void controlApplyStorageProfileBranches() {
        ControlService service = new ControlService(
            apiKeyRepoForControl, gameRepo, gameEnvironmentRepo, storageProfileRepo,
            trackingPlanRepoForControl, eventDefinitionRepoForControl, auditLog);

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

    @Test
    @DisplayName("validateConfig：非对象配置拒绝分支")
    void experimentConfigNotObject() throws Exception {
        ExperimentService service = new ExperimentService(
            experimentRepo, gameRepo, gameEnvironmentRepo, mock(SegmentRepo.class), new ObjectMapper(), mock(AuditLogService.class));

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

    @Test
    @DisplayName("assign：variants 为 null 返回 null 分支")
    void splitterAssignNullVariants() {
        assertNull(ExperimentSplitter.assign("exp", "salt", "u1", null));
        assertNull(ExperimentSplitter.assign("exp", "salt", "u1", List.of()));
    }

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

}
