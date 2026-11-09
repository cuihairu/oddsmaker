package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.api.ControlService;
import io.oddsmaker.control.api.Models;
import io.oddsmaker.control.dto.ExperimentDTO;
import io.oddsmaker.control.dto.GameDTO;
import io.oddsmaker.control.experiment.ExperimentEntity;
import io.oddsmaker.control.experiment.ExperimentRepo;
import io.oddsmaker.control.jpa.ApiKeyRepo;
import io.oddsmaker.control.jpa.AuditLogEntity;
import io.oddsmaker.control.jpa.AuditLogRepo;
import io.oddsmaker.control.jpa.GameEntity;
import io.oddsmaker.control.jpa.GameEnvironmentEntity;
import io.oddsmaker.control.jpa.GameEnvironmentRepo;
import io.oddsmaker.control.jpa.GameRepo;
import io.oddsmaker.control.jpa.StorageProfileEntity;
import io.oddsmaker.control.jpa.StorageProfileRepo;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖率收尾（第四轮·LAY-003 拆分）：ExperimentService、GameService 环境与状态迁移、ControlService 存储档案。与 FinalSweep2Test / ServicesFinalSweep*Test 系列 / 各专项 Service 测试互补。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("覆盖率收尾（第四轮）·实验/游戏/环境域")
class FinalSweep3ExperimentTest {

// ==================== 共享 Mock（原类逐字搬运） ====================

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
    private ApiKeyRepo apiKeyRepo;

    @Mock
    private StorageProfileRepo storageProfileRepo;

    @InjectMocks
    private GameService gameService;

    @InjectMocks
    private ControlService controlService;

    @BeforeEach
    void setUp() {
        lenient().when(auditLogRepo.save(any(AuditLogEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        objectMapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    }

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
    @DisplayName("ExperimentService.updateExperiment 全字段更新（name/salt回退/config/status=running 再校验）")
    void testUpdateExperimentSuccess() throws Exception {
        ExperimentEntity e = new ExperimentEntity();
        e.id = "exp1";
        e.gameId = "g1";
        e.environmentId = "env1";
        e.name = "old";
        e.status = ExperimentEntity.ExperimentStatus.DRAFT;
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
        dto.status = "LIVE";

        ExperimentDTO out = experimentService.updateExperiment("exp1", dto);
        assertEquals("new-name", out.name);
        assertEquals("LIVE", out.status);
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
        e.status = ExperimentEntity.ExperimentStatus.DRAFT;
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
        e.status = ExperimentEntity.ExperimentStatus.DRAFT;
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
        e.status = ExperimentEntity.ExperimentStatus.DRAFT;
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
        e.status = ExperimentEntity.ExperimentStatus.DRAFT;
        e.configJson = "{}";
        when(experimentRepo.findById("exp1")).thenReturn(Optional.of(e));
        ExperimentDTO dto = new ExperimentDTO();
        dto.status = "LIVE";
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
        assertCreateConfigFails("LIVE", "{}");
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
        assertEquals("DRAFT", out.status);
        assertEquals(out.id, out.salt);
        assertEquals("env1", out.environmentId);
        assertTrue(out.config.has("variants"));
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
}
