package io.oddsmaker.control.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.oddsmaker.control.dto.GameDTO;
import io.oddsmaker.control.dto.EnvironmentDTO;
import io.oddsmaker.control.jpa.GameEntity;
import io.oddsmaker.control.jpa.GameEnvironmentEntity;
import io.oddsmaker.control.jpa.GameRepo;
import io.oddsmaker.control.jpa.GameEnvironmentRepo;
import io.oddsmaker.control.jpa.ApiKeyRepo;
import io.oddsmaker.control.jpa.StorageProfileRepo;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;

/**
 * GameService 单元测试
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GameService 单元测试")
class GameServiceTest {

    @Mock
    private GameRepo gameRepo;

    @Mock
    private GameEnvironmentRepo gameEnvironmentRepo;

    @Mock
    private ApiKeyRepo apiKeyRepo;

    @Mock
    private StorageProfileRepo storageProfileRepo;

    @Mock
    private AuditLogService auditLog;

    @InjectMocks
    private GameService gameService;

    private GameEntity savedGame(GameEntity entity) {
        // 模拟 JPA save：回填并返回同一实例
        return entity;
    }

    @Test
    @DisplayName("创建游戏：默认时区/货币生效并记录审计")
    void createGameAppliesDefaultsAndAudits() {
        when(gameRepo.existsById(any())).thenReturn(false);
        when(gameRepo.save(any())).thenAnswer(inv -> savedGame(inv.getArgument(0)));
        when(storageProfileRepo.existsById(any())).thenReturn(true);
        when(gameEnvironmentRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        GameDTO dto = new GameDTO();
        dto.name = "Demo RPG";
        dto.platforms = Set.of(GameEntity.GamePlatform.MOBILE);

        GameDTO created = gameService.createGame(dto);

        assertNotNull(created.id);
        assertEquals("UTC", created.defaultTimezone);
        assertEquals("USD", created.defaultCurrency);
        assertEquals(GameEntity.GameStatus.DEVELOPMENT, created.status);
        verify(auditLog).logCreate(eq("game"), eq(created.id), eq("Demo RPG"),
            eq("api"), eq("api"), isNull(), anyMap());
    }

    @Test
    @DisplayName("创建游戏：自定义时区/货币透传")
    void createGameKeepsExplicitTimezoneAndCurrency() {
        when(gameRepo.existsById(any())).thenReturn(false);
        when(gameRepo.save(any())).thenAnswer(inv -> savedGame(inv.getArgument(0)));
        when(storageProfileRepo.existsById(any())).thenReturn(true);
        when(gameEnvironmentRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        GameDTO dto = new GameDTO();
        dto.name = "CN Game";
        dto.defaultTimezone = "Asia/Shanghai";
        dto.defaultCurrency = "CNY";

        GameDTO created = gameService.createGame(dto);

        assertEquals("Asia/Shanghai", created.defaultTimezone);
        assertEquals("CNY", created.defaultCurrency);
    }

    @Test
    @DisplayName("创建游戏：非法时区被拒绝")
    void createGameRejectsInvalidTimezone() {
        GameDTO dto = new GameDTO();
        dto.name = "Bad TZ";
        dto.defaultTimezone = "Mars/Olympus_Mons";

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> gameService.createGame(dto));
        assertTrue(ex.getMessage().contains("Unknown timezone"));
    }

    @Test
    @DisplayName("更新游戏：非法时区被拒绝且不落库")
    void updateGameRejectsInvalidTimezone() {
        GameEntity existing = new GameEntity();
        existing.id = "game_1";
        existing.name = "Existing";
        when(gameRepo.findById("game_1")).thenReturn(Optional.of(existing));

        GameDTO dto = new GameDTO();
        dto.defaultTimezone = "Not/A Zone!";

        assertThrows(IllegalArgumentException.class,
            () -> gameService.updateGame("game_1", dto));
        verify(gameRepo, never()).save(any());
    }

    @Test
    @DisplayName("更新游戏：dataRetentionDays 超出 [7,3650] 被拒绝（防误配 0 立即清光数据）")
    void updateGameRejectsOutOfRangeRetentionDays() {
        org.springframework.test.util.ReflectionTestUtils.setField(gameService, "retentionMinDays", 7);
        org.springframework.test.util.ReflectionTestUtils.setField(gameService, "retentionMaxDays", 3650);
        GameEntity existing = new GameEntity();
        existing.id = "game_rt";
        existing.name = "Retention Game";
        when(gameRepo.findById("game_rt")).thenReturn(Optional.of(existing));

        GameDTO zero = new GameDTO();
        zero.dataRetentionDays = 0;
        assertThrows(IllegalArgumentException.class, () -> gameService.updateGame("game_rt", zero));

        GameDTO huge = new GameDTO();
        huge.dataRetentionDays = 3651;
        assertThrows(IllegalArgumentException.class, () -> gameService.updateGame("game_rt", huge));

        verify(gameRepo, never()).save(any());
    }

    @Test
    @DisplayName("更新环境：dataRetentionDays 超出范围被拒绝，合法值透传")
    void updateEnvironmentValidatesRetentionDays() {
        org.springframework.test.util.ReflectionTestUtils.setField(gameService, "retentionMinDays", 7);
        org.springframework.test.util.ReflectionTestUtils.setField(gameService, "retentionMaxDays", 3650);
        GameEnvironmentEntity existing = new GameEnvironmentEntity();
        existing.id = "env_game_1_prod";
        existing.gameId = "game_1";
        existing.name = "prod";
        when(gameEnvironmentRepo.findByGameIdAndNameAndDeletedAtIsNull("game_1", "prod"))
            .thenReturn(List.of(existing));

        EnvironmentDTO zero = new EnvironmentDTO();
        zero.name = "prod";
        zero.dataRetentionDays = 0;
        assertThrows(IllegalArgumentException.class,
            () -> gameService.updateEnvironment("game_1", "prod", zero));

        EnvironmentDTO valid = new EnvironmentDTO();
        valid.name = "prod";
        valid.dataRetentionDays = 180;
        when(gameEnvironmentRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        EnvironmentDTO updated = gameService.updateEnvironment("game_1", "prod", valid);
        assertEquals(180, updated.dataRetentionDays);
    }

    @Test
    @DisplayName("状态机：DEVELOPMENT 只能转 TESTING")
    void invalidStatusTransitionRejected() {
        GameEntity existing = new GameEntity();
        existing.id = "game_2";
        existing.name = "Dev Game";
        existing.status = GameEntity.GameStatus.DEVELOPMENT;
        when(gameRepo.findById("game_2")).thenReturn(Optional.of(existing));

        GameDTO dto = new GameDTO();
        dto.status = GameEntity.GameStatus.LIVE;

        assertThrows(IllegalArgumentException.class,
            () -> gameService.updateGame("game_2", dto));
    }

    @Test
    @DisplayName("状态机：TESTING 可转 LIVE 并记录审计")
    void validTransitionToLiveAudited() {
        GameEntity existing = new GameEntity();
        existing.id = "game_3";
        existing.name = "Ready Game";
        existing.status = GameEntity.GameStatus.TESTING;
        existing.platforms = Set.of(GameEntity.GamePlatform.WEB);
        when(gameRepo.findById("game_3")).thenReturn(Optional.of(existing));
        when(gameRepo.save(any())).thenAnswer(inv -> savedGame(inv.getArgument(0)));

        GameDTO dto = new GameDTO();
        dto.status = GameEntity.GameStatus.LIVE;

        GameDTO updated = gameService.updateGame("game_3", dto);

        assertEquals(GameEntity.GameStatus.LIVE, updated.status);
        verify(auditLog).logUpdate(eq("game"), eq("game_3"), eq("Ready Game"),
            eq("api"), eq("api"), isNull(), anyMap());
    }

    @Test
    @DisplayName("删除游戏：LIVE 状态不可删除")
    void liveGameCannotBeDeleted() {
        GameEntity existing = new GameEntity();
        existing.id = "game_live";
        existing.name = "Live Game";
        existing.status = GameEntity.GameStatus.LIVE;
        when(gameRepo.findById("game_live")).thenReturn(Optional.of(existing));

        assertThrows(IllegalStateException.class, () -> gameService.deleteGame("game_live"));
    }

    @Test
    @DisplayName("创建环境：loadtest/staging 环境名映射默认显示名")
    void createEnvironmentDefaultsDisplayNameForLoadtest() {
        when(gameRepo.findById("game_env")).thenReturn(Optional.of(new GameEntity()));
        when(gameEnvironmentRepo.findByGameIdAndNameAndDeletedAtIsNull(any(), any()))
            .thenReturn(List.of());
        when(storageProfileRepo.existsById(any())).thenReturn(true);
        when(gameEnvironmentRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        EnvironmentDTO loadtest = new EnvironmentDTO();
        loadtest.name = "loadtest";
        assertEquals("Load Test", gameService.createEnvironment("game_env", loadtest).displayName);

        EnvironmentDTO staging = new EnvironmentDTO();
        staging.name = "staging";
        assertEquals("Staging", gameService.createEnvironment("game_env", staging).displayName);
    }

    @Test
    @DisplayName("状态机：PUBLISHED 可转 DISCONTINUED 并记录审计")
    void publishedToDiscontinuedAllowed() {
        GameEntity existing = new GameEntity();
        existing.id = "game_pub";
        existing.name = "Published Game";
        existing.status = GameEntity.GameStatus.PUBLISHED;
        existing.platforms = Set.of(GameEntity.GamePlatform.WEB);
        when(gameRepo.findById("game_pub")).thenReturn(Optional.of(existing));
        when(gameRepo.save(any())).thenAnswer(inv -> savedGame(inv.getArgument(0)));

        GameDTO dto = new GameDTO();
        dto.status = GameEntity.GameStatus.DISCONTINUED;

        assertEquals(GameEntity.GameStatus.DISCONTINUED, gameService.updateGame("game_pub", dto).status);
        verify(auditLog).logUpdate(eq("game"), eq("game_pub"), eq("Published Game"),
            eq("api"), eq("api"), isNull(), anyMap());
    }

    @Test
    @DisplayName("删除游戏：软删除并级联处置环境与密钥")
    void deleteGameSoftDeletesAndCascades() {
        GameEntity existing = new GameEntity();
        existing.id = "game_del";
        existing.name = "Old Game";
        existing.status = GameEntity.GameStatus.MAINTENANCE;
        when(gameRepo.findById("game_del")).thenReturn(Optional.of(existing));
        when(gameRepo.save(any())).thenAnswer(inv -> savedGame(inv.getArgument(0)));
        when(gameEnvironmentRepo.findByGameIdAndDeletedAtIsNull("game_del")).thenReturn(List.of());
        when(apiKeyRepo.findByGameIdAndStatus("game_del", io.oddsmaker.control.jpa.ApiKeyEntity.ApiKeyStatus.ACTIVE))
            .thenReturn(List.of());

        gameService.deleteGame("game_del");

        assertNotNull(existing.deletedAt);
        assertEquals(GameEntity.GameStatus.DISCONTINUED, existing.status);
        verify(auditLog).logDelete("game", "game_del", "Old Game", "api", "api", null);
    }
}
