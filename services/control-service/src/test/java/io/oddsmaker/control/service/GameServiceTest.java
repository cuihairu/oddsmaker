package io.oddsmaker.control.service;

import io.oddsmaker.control.dto.GameDTO;
import io.oddsmaker.control.jpa.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
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

    @InjectMocks
    private GameService gameService;

    private GameEntity testGame;
    private GameDTO testGameDTO;

    @BeforeEach
    void setUp() {
        testGame = new GameEntity();
        testGame.id = "game_test123";
        testGame.name = "Test Game";
        testGame.genre = GameEntity.GameGenre.RPG;
        testGame.status = GameEntity.GameStatus.DRAFT;
        testGame.createdAt = LocalDateTime.now();

        testGameDTO = new GameDTO();
        testGameDTO.id = "game_test123";
        testGameDTO.name = "Test Game";
        testGameDTO.genre = "RPG";
    }

    @Test
    @DisplayName("创建游戏 - 成功")
    void createGame_Success() {
        // Given
        when(gameRepo.save(any(GameEntity.class))).thenReturn(testGame);
        when(gameEnvironmentRepo.findByGameIdAndDeletedAtIsNull(anyString())).thenReturn(Arrays.asList());

        // When
        GameDTO result = gameService.createGame(testGameDTO);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.id).isEqualTo("game_test123");
        assertThat(result.name).isEqualTo("Test Game");
        verify(gameRepo, times(1)).save(any(GameEntity.class));
    }

    @Test
    @DisplayName("创建游戏 - 自动生成ID")
    void createGame_AutoGenerateId() {
        // Given
        testGameDTO.id = null;
        when(gameRepo.save(any(GameEntity.class))).thenReturn(testGame);
        when(gameEnvironmentRepo.findByGameIdAndDeletedAtIsNull(anyString())).thenReturn(Arrays.asList());

        // When
        GameDTO result = gameService.createGame(testGameDTO);

        // Then
        assertThat(result).isNotNull();
        verify(gameRepo, times(1)).save(any(GameEntity.class));
    }

    @Test
    @DisplayName("获取游戏 - 存在")
    void getGame_Exists() {
        // Given
        when(gameRepo.findById("game_test123")).thenReturn(Optional.of(testGame));

        // When
        Optional<GameDTO> result = gameService.getGame("game_test123");

        // Then
        assertThat(result).isPresent();
        assertThat(result.get().name).isEqualTo("Test Game");
    }

    @Test
    @DisplayName("获取游戏 - 不存在")
    void getGame_NotExists() {
        // Given
        when(gameRepo.findById("game_notexist")).thenReturn(Optional.empty());

        // When
        Optional<GameDTO> result = gameService.getGame("game_notexist");

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("获取游戏 - 已删除")
    void getGame_Deleted() {
        // Given
        testGame.deletedAt = LocalDateTime.now();
        when(gameRepo.findById("game_test123")).thenReturn(Optional.of(testGame));

        // When
        Optional<GameDTO> result = gameService.getGame("game_test123");

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("获取游戏列表 - 分页")
    void getGames_Paged() {
        // Given
        List<GameEntity> games = Arrays.asList(testGame);
        Page<GameEntity> page = new PageImpl<>(games, PageRequest.of(0, 10), 1);
        when(gameRepo.findByDeletedAtIsNull(any(PageRequest.class))).thenReturn(page);

        // When
        Page<GameDTO> result = gameService.getGames(PageRequest.of(0, 10));

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).name).isEqualTo("Test Game");
    }

    @Test
    @DisplayName("更新游戏 - 成功")
    void updateGame_Success() {
        // Given
        GameDTO updateDTO = new GameDTO();
        updateDTO.name = "Updated Game";
        updateDTO.genre = "strategy";

        when(gameRepo.findById("game_test123")).thenReturn(Optional.of(testGame));
        when(gameRepo.save(any(GameEntity.class))).thenReturn(testGame);

        // When
        GameDTO result = gameService.updateGame("game_test123", updateDTO);

        // Then
        assertThat(result).isNotNull();
        verify(gameRepo, times(1)).save(any(GameEntity.class));
    }

    @Test
    @DisplayName("更新游戏 - 不存在")
    void updateGame_NotExists() {
        // Given
        when(gameRepo.findById("game_notexist")).thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> gameService.updateGame("game_notexist", testGameDTO))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Game not found");
    }

    @Test
    @DisplayName("删除游戏 - 成功（非LIVE状态）")
    void deleteGame_Success() {
        // Given
        testGame.status = GameEntity.GameStatus.DRAFT;
        when(gameRepo.findById("game_test123")).thenReturn(Optional.of(testGame));
        when(gameRepo.save(any(GameEntity.class))).thenReturn(testGame);

        // When
        gameService.deleteGame("game_test123");

        // Then
        verify(gameRepo, times(1)).save(any(GameEntity.class));
        assertThat(testGame.deletedAt).isNotNull();
        assertThat(testGame.status).isEqualTo(GameEntity.GameStatus.DISCONTINUED);
    }

    @Test
    @DisplayName("删除游戏 - LIVE状态失败")
    void deleteGame_LiveGameFails() {
        // Given
        testGame.status = GameEntity.GameStatus.LIVE;
        when(gameRepo.findById("game_test123")).thenReturn(Optional.of(testGame));

        // When & Then
        assertThatThrownBy(() -> gameService.deleteGame("game_test123"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Cannot delete live game");
    }

    @Test
    @DisplayName("删除游戏 - 不存在")
    void deleteGame_NotExists() {
        // Given
        when(gameRepo.findById("game_notexist")).thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> gameService.deleteGame("game_notexist"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Game not found");
    }

    @Test
    @DisplayName("搜索游戏")
    void searchGames() {
        // Given
        List<GameEntity> games = Arrays.asList(testGame);
        Page<GameEntity> page = new PageImpl<>(games, PageRequest.of(0, 10), 1);
        when(gameRepo.searchByName(eq("Test"), any(PageRequest.class))).thenReturn(page);

        // When
        Page<GameDTO> result = gameService.searchGames("Test", PageRequest.of(0, 10));

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    @DisplayName("游戏状态转换 - DRAFT到STAGING")
    void statusTransition_DraftToStaging() {
        // Given
        testGame.status = GameEntity.GameStatus.DRAFT;
        GameDTO updateDTO = new GameDTO();
        updateDTO.status = GameEntity.GameStatus.STAGING;

        when(gameRepo.findById("game_test123")).thenReturn(Optional.of(testGame));
        when(gameRepo.save(any(GameEntity.class))).thenReturn(testGame);

        // When
        GameDTO result = gameService.updateGame("game_test123", updateDTO);

        // Then
        assertThat(result).isNotNull();
        verify(gameRepo, times(1)).save(any(GameEntity.class));
    }

    @Test
    @DisplayName("游戏状态转换 - STAGING到LIVE")
    void statusTransition_StagingToLive() {
        // Given
        testGame.status = GameEntity.GameStatus.STAGING;
        GameDTO updateDTO = new GameDTO();
        updateDTO.status = GameEntity.GameStatus.LIVE;

        when(gameRepo.findById("game_test123")).thenReturn(Optional.of(testGame));
        when(gameRepo.save(any(GameEntity.class))).thenReturn(testGame);

        // When
        GameDTO result = gameService.updateGame("game_test123", updateDTO);

        // Then
        assertThat(result).isNotNull();
        verify(gameRepo, times(1)).save(any(GameEntity.class));
    }

    @Test
    @DisplayName("游戏状态转换 - 无效转换")
    void statusTransition_Invalid() {
        // Given
        testGame.status = GameEntity.GameStatus.DRAFT;
        GameDTO updateDTO = new GameDTO();
        updateDTO.status = GameEntity.GameStatus.LIVE;

        when(gameRepo.findById("game_test123")).thenReturn(Optional.of(testGame));

        // When & Then
        assertThatThrownBy(() -> gameService.updateGame("game_test123", updateDTO))
            .isInstanceOf(IllegalStateException.class);
    }
}
