package io.oddsmaker.control.service;

import io.oddsmaker.control.dto.EnvironmentDTO;
import io.oddsmaker.control.dto.GameDTO;
import io.oddsmaker.control.jpa.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
/**
 * 安全与游戏域 Service 深度单元测试（LAY-004 拆分）：游戏创建/统计、环境创建/更新/删除、发布与下架状态流转。与 GameServiceTest / DashboardServicesTest 互补。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("游戏域 Service 深度测试（环境/发布流转）")
class GameServiceDeepTest {

// ===== 共享 Mock =====

    @Mock
    private AuditLogService auditLog;

    // ===== GameService =====

    @Mock
    private GameRepo gameRepo;

    @Mock
    private GameEnvironmentRepo gameEnvironmentRepo;

    @Mock
    private ApiKeyRepo apiKeyRepo;

    @Mock
    private StorageProfileRepo storageProfileRepo;

    @InjectMocks
    private GameService gameService;

    private GameEntity game(String id, GameEntity.GameStatus status) {
        GameEntity g = new GameEntity();
        g.id = id;
        g.name = "Game-" + id;
        g.status = status;
        return g;
    }

    private GameEnvironmentEntity environment(String gameId, String name) {
        GameEnvironmentEntity e = new GameEnvironmentEntity();
        e.id = "env_" + gameId + "_" + name;
        e.gameId = gameId;
        e.name = name;
        e.displayName = name;
        e.type = GameEnvironmentEntity.EnvironmentType.TESTING;
        e.status = GameEnvironmentEntity.EnvironmentStatus.ACTIVE;
        e.storageProfileId = "shared-nonprod";
        return e;
    }

    @Test
    @DisplayName("createGame：指定 ID 已存在时拒绝")
    void createGameRejectsDuplicateId() {
        GameDTO dto = new GameDTO();
        dto.id = "game_dup";
        dto.name = "Dup";
        lenient().when(gameRepo.existsById("game_dup")).thenReturn(true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> gameService.createGame(dto));
        assertTrue(ex.getMessage().contains("already exists"));
        verify(gameRepo, never()).save(any());
    }

    @Test
    @DisplayName("createGame：复用调用方指定的 ID 并创建默认环境")
    void createGameKeepsCallerProvidedId() {
        GameDTO dto = new GameDTO();
        dto.id = "game_custom";
        dto.name = "Custom";
        lenient().when(gameRepo.existsById("game_custom")).thenReturn(false);
        lenient().when(gameRepo.save(any(GameEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(storageProfileRepo.existsById(anyString())).thenReturn(true);
        lenient().when(gameEnvironmentRepo.save(any(GameEnvironmentEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        GameDTO created = gameService.createGame(dto);

        assertEquals("game_custom", created.id);
        assertEquals(GameEntity.GameStatus.DEVELOPMENT, created.status);
        // 默认环境：dev / staging / prod
        ArgumentCaptor<GameEnvironmentEntity> envCaptor = ArgumentCaptor.forClass(GameEnvironmentEntity.class);
        verify(gameEnvironmentRepo, times(3)).save(envCaptor.capture());
        long devCount = envCaptor.getAllValues().stream()
            .filter(e -> e.type == GameEnvironmentEntity.EnvironmentType.DEVELOPMENT).count();
        long prodCount = envCaptor.getAllValues().stream()
            .filter(e -> e.type == GameEnvironmentEntity.EnvironmentType.PRODUCTION).count();
        assertEquals(1L, devCount);
        assertEquals(1L, prodCount);
    }

    @Test
    @DisplayName("getGame：未删除游戏回填统计，已删除游戏返回空")
    void getGameEnrichesStatisticsOrEmpty() {
        GameEntity existing = game("g1", GameEntity.GameStatus.TESTING);
        lenient().when(gameRepo.findById("g1")).thenReturn(Optional.of(existing));
        lenient().when(gameEnvironmentRepo.countByGameIdAndDeletedAtIsNull("g1")).thenReturn(2L);
        lenient().when(apiKeyRepo.countByGameIdAndStatus("g1", ApiKeyEntity.ApiKeyStatus.ACTIVE)).thenReturn(1L);

        Optional<GameDTO> found = gameService.getGame("g1");
        assertTrue(found.isPresent());
        assertEquals(2, found.get().totalEnvironments);
        assertEquals(1, found.get().totalApiKeys);

        GameEntity deleted = game("g2", GameEntity.GameStatus.DISCONTINUED);
        deleted.deletedAt = LocalDateTime.now();
        lenient().when(gameRepo.findById("g2")).thenReturn(Optional.of(deleted));
        assertTrue(gameService.getGame("g2").isEmpty());
    }

    @Test
    @DisplayName("getGameStatistics：空结果与聚合结果两条分支")
    void getGameStatisticsHandlesEmptyAndRow() {
        lenient().when(gameRepo.getGameStatistics()).thenReturn(List.of());
        Map<String, Object> empty = gameService.getGameStatistics();
        assertEquals(0L, empty.get("totalGames"));
        assertEquals(0L, empty.get("liveGames"));

        lenient().when(gameRepo.getGameStatistics()).thenReturn(List.<Object>of(Map.of(
            "totalGames", 3L, "liveGames", 1L, "devGames", 1L, "testGames", 1L, "multiplayerGames", 0L)));
        Map<String, Object> stats = gameService.getGameStatistics();
        assertEquals(3L, stats.get("totalGames"));
        assertEquals(1L, stats.get("liveGames"));
    }

    @Test
    @DisplayName("createEnvironment：默认值填充（归一化名称/显示名/存储路由/库名）")
    void createEnvironmentAppliesDefaults() {
        GameEntity existing = game("g1", GameEntity.GameStatus.DEVELOPMENT);
        lenient().when(gameRepo.findById("g1")).thenReturn(Optional.of(existing));
        lenient().when(gameEnvironmentRepo.findByGameIdAndNameAndDeletedAtIsNull("g1", "qa"))
            .thenReturn(List.of());
        lenient().when(storageProfileRepo.existsById(anyString())).thenReturn(true);
        lenient().when(gameEnvironmentRepo.save(any(GameEnvironmentEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        EnvironmentDTO dto = new EnvironmentDTO();
        dto.name = " QA ";

        EnvironmentDTO created = gameService.createEnvironment("g1", dto);

        assertEquals("env_g1_qa", created.id);
        assertEquals("qa", created.name);
        assertEquals("QA", created.displayName);
        assertEquals(GameEnvironmentEntity.EnvironmentType.TESTING, created.type);
        assertEquals("shared-nonprod", created.storageProfileId);
        assertEquals("g1_qa", created.dataNamespace);
        assertEquals("g1_qa", created.kafkaTopicPrefix);
        assertEquals("game_g1_qa", created.databaseName);
        verify(auditLog).logCreate(eq("environment"), eq("env_g1_qa"), eq("qa"),
            eq("api"), eq("api"), isNull(), anyMap());
    }

    @Test
    @DisplayName("createEnvironment：重名/空白名/未知存储路由均被拒绝")
    void createEnvironmentRejectsInvalidInput() {
        GameEntity existing = game("g1", GameEntity.GameStatus.DEVELOPMENT);
        lenient().when(gameRepo.findById("g1")).thenReturn(Optional.of(existing));

        EnvironmentDTO blank = new EnvironmentDTO();
        blank.name = "   ";
        assertThrows(IllegalArgumentException.class, () -> gameService.createEnvironment("g1", blank));

        lenient().when(gameEnvironmentRepo.findByGameIdAndNameAndDeletedAtIsNull("g1", "qa"))
            .thenReturn(List.of(environment("g1", "qa")));
        EnvironmentDTO dup = new EnvironmentDTO();
        dup.name = "qa";
        IllegalArgumentException dupEx = assertThrows(IllegalArgumentException.class,
            () -> gameService.createEnvironment("g1", dup));
        assertTrue(dupEx.getMessage().contains("already exists"));

        lenient().when(gameEnvironmentRepo.findByGameIdAndNameAndDeletedAtIsNull("g1", "prod"))
            .thenReturn(List.of());
        lenient().when(storageProfileRepo.existsById(anyString())).thenReturn(false);
        EnvironmentDTO badProfile = new EnvironmentDTO();
        badProfile.name = "prod";
        badProfile.storageProfileId = "ghost-profile";
        IllegalArgumentException profileEx = assertThrows(IllegalArgumentException.class,
            () -> gameService.createEnvironment("g1", badProfile));
        assertTrue(profileEx.getMessage().contains("Storage profile not found"));

        verify(gameEnvironmentRepo, never()).save(any());
    }

    @Test
    @DisplayName("listEnvironments：游戏存在返回 DTO，不存在抛异常")
    void listEnvironmentsRequiresGame() {
        GameEntity existing = game("g1", GameEntity.GameStatus.DEVELOPMENT);
        lenient().when(gameRepo.findById("g1")).thenReturn(Optional.of(existing));
        lenient().when(gameEnvironmentRepo.findByGameIdAndDeletedAtIsNull("g1"))
            .thenReturn(List.of(environment("g1", "qa")));

        List<EnvironmentDTO> envs = gameService.listEnvironments("g1");
        assertEquals(1, envs.size());
        assertEquals("qa", envs.get(0).name);

        lenient().when(gameRepo.findById("g_missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> gameService.listEnvironments("g_missing"));
    }

    @Test
    @DisplayName("updateEnvironment：更新采样率并校验存储路由")
    void updateEnvironmentAppliesChanges() {
        GameEnvironmentEntity existing = environment("g1", "qa");
        existing.sampleRate = 1.0;
        lenient().when(gameEnvironmentRepo.findByGameIdAndNameAndDeletedAtIsNull("g1", "qa"))
            .thenReturn(List.of(existing));
        lenient().when(storageProfileRepo.existsById("shared-nonprod")).thenReturn(true);
        lenient().when(gameEnvironmentRepo.save(any(GameEnvironmentEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        EnvironmentDTO dto = new EnvironmentDTO();
        dto.sampleRate = 0.25;

        EnvironmentDTO updated = gameService.updateEnvironment("g1", "qa", dto);

        assertEquals(0.25, updated.sampleRate);
        assertEquals("qa", updated.name);
        verify(auditLog).logUpdate(eq("environment"), eq("env_g1_qa"), eq("qa"),
            eq("api"), eq("api"), isNull(), anyMap());
    }

    @Test
    @DisplayName("updateEnvironment：环境不存在抛异常")
    void updateEnvironmentRejectsMissing() {
        lenient().when(gameEnvironmentRepo.findByGameIdAndNameAndDeletedAtIsNull("g1", "ghost"))
            .thenReturn(List.of());

        EnvironmentDTO dto = new EnvironmentDTO();
        dto.sampleRate = 0.5;
        assertThrows(IllegalArgumentException.class,
            () -> gameService.updateEnvironment("g1", "ghost", dto));
        verify(gameEnvironmentRepo, never()).save(any());
    }

    @Test
    @DisplayName("deleteEnvironment：被活跃 API Key 占用时拒绝删除")
    void deleteEnvironmentBlockedByActiveKey() {
        GameEntity existing = game("g1", GameEntity.GameStatus.DEVELOPMENT);
        lenient().when(gameRepo.findById("g1")).thenReturn(Optional.of(existing));
        GameEnvironmentEntity env = environment("g1", "qa");
        lenient().when(gameEnvironmentRepo.findByGameIdAndNameAndDeletedAtIsNull("g1", "qa"))
            .thenReturn(List.of(env));

        ApiKeyEntity key = new ApiKeyEntity();
        key.environmentId = env.id;
        lenient().when(apiKeyRepo.findByGameIdAndStatus("g1", ApiKeyEntity.ApiKeyStatus.ACTIVE))
            .thenReturn(List.of(key));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> gameService.deleteEnvironment("g1", "qa"));
        assertTrue(ex.getMessage().contains("API key"));
        verify(gameEnvironmentRepo, never()).save(any());
    }

    @Test
    @DisplayName("deleteEnvironment：软删除并置 INACTIVE")
    void deleteEnvironmentSoftDeletes() {
        GameEntity existing = game("g1", GameEntity.GameStatus.DEVELOPMENT);
        lenient().when(gameRepo.findById("g1")).thenReturn(Optional.of(existing));
        GameEnvironmentEntity env = environment("g1", "qa");
        lenient().when(gameEnvironmentRepo.findByGameIdAndNameAndDeletedAtIsNull("g1", "qa"))
            .thenReturn(List.of(env));

        ApiKeyEntity otherEnvKey = new ApiKeyEntity();
        otherEnvKey.environmentId = "env_g1_prod";
        lenient().when(apiKeyRepo.findByGameIdAndStatus("g1", ApiKeyEntity.ApiKeyStatus.ACTIVE))
            .thenReturn(List.of(otherEnvKey));
        lenient().when(gameEnvironmentRepo.save(any(GameEnvironmentEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        gameService.deleteEnvironment("g1", "qa");

        assertNotNull(env.deletedAt);
        assertEquals(GameEnvironmentEntity.EnvironmentStatus.INACTIVE, env.status);
        verify(auditLog).logDelete("environment", "env_g1_qa", "qa", "api", "api", null);
    }

    @Test
    @DisplayName("publishGame：缺版本号/已上线/不存在均被拒绝")
    void publishGameRejectsInvalidStates() {
        lenient().when(gameRepo.findById("g_none")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> gameService.publishGame("g_none"));

        GameEntity live = game("g_live", GameEntity.GameStatus.LIVE);
        lenient().when(gameRepo.findById("g_live")).thenReturn(Optional.of(live));
        assertThrows(IllegalStateException.class, () -> gameService.publishGame("g_live"));

        GameEntity noVersion = game("g_nov", GameEntity.GameStatus.TESTING);
        noVersion.platforms = Set.of(GameEntity.GamePlatform.WEB);
        lenient().when(gameRepo.findById("g_nov")).thenReturn(Optional.of(noVersion));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> gameService.publishGame("g_nov"));
        assertTrue(ex.getMessage().contains("version"));

        verify(gameRepo, never()).save(any());
    }

    @Test
    @DisplayName("publishGame：TESTING 游戏满足条件后上线并补发 releaseDate")
    void publishGameSetsLiveAndReleaseDate() {
        GameEntity ready = game("g1", GameEntity.GameStatus.TESTING);
        ready.platforms = Set.of(GameEntity.GamePlatform.WEB);
        ready.currentVersion = "1.0.0";
        lenient().when(gameRepo.findById("g1")).thenReturn(Optional.of(ready));
        lenient().when(gameRepo.save(any(GameEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        GameDTO published = gameService.publishGame("g1");

        assertEquals(GameEntity.GameStatus.LIVE, published.status);
        assertNotNull(published.releaseDate);
        verify(auditLog).logUpdate(eq("game"), eq("g1"), eq("Game-g1"),
            eq("api"), eq("api"), isNull(), anyMap());
    }

    @Test
    @DisplayName("unpublishGame：非 LIVE 拒绝，LIVE 转为 MAINTENANCE")
    void unpublishGameTogglesToMaintenance() {
        GameEntity dev = game("g_dev", GameEntity.GameStatus.DEVELOPMENT);
        lenient().when(gameRepo.findById("g_dev")).thenReturn(Optional.of(dev));
        assertThrows(IllegalStateException.class, () -> gameService.unpublishGame("g_dev"));

        GameEntity live = game("g1", GameEntity.GameStatus.LIVE);
        lenient().when(gameRepo.findById("g1")).thenReturn(Optional.of(live));
        lenient().when(gameRepo.save(any(GameEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        GameDTO result = gameService.unpublishGame("g1");

        assertEquals(GameEntity.GameStatus.MAINTENANCE, result.status);
        verify(auditLog).logUpdate(eq("game"), eq("g1"), eq("Game-g1"),
            eq("api"), eq("api"), isNull(), anyMap());
    }

    @Test
    @DisplayName("deleteGame：游戏不存在抛异常")
    void deleteGameRejectsMissingGame() {
        lenient().when(gameRepo.findById("g_none")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> gameService.deleteGame("g_none"));
        verify(gameRepo, never()).save(any());
    }
}
