package io.oddsmaker.control.service;

import io.oddsmaker.control.dto.GameDTO;
import io.oddsmaker.control.jpa.ApiKeyEntity;
import io.oddsmaker.control.jpa.ApiKeyRepo;
import io.oddsmaker.control.jpa.GameEntity;
import io.oddsmaker.control.jpa.GameEnvironmentEntity;
import io.oddsmaker.control.jpa.GameEnvironmentRepo;
import io.oddsmaker.control.jpa.GameRepo;
import io.oddsmaker.control.jpa.StorageProfileRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Service 层覆盖率收尾测试（LAY-006 拆分）：游戏分页回填统计、搜索与过滤、缺失与环境查询、级联删除环境与 API Key、无状态变更保存。与 GameServiceTest / AuditLogServiceTest 等互补。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("游戏 Service 深度测试（分页/查询/级联删除）")
class ServicesFinalSweepGameTest {

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
