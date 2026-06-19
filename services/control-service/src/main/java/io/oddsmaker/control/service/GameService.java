package io.oddsmaker.control.service;

import io.oddsmaker.control.dto.GameDTO;
import io.oddsmaker.control.jpa.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 游戏管理服务
 * 提供游戏产品的完整生命周期管理
 */
@Service
@Transactional
public class GameService {

    private static final Logger logger = LoggerFactory.getLogger(GameService.class);

    @Autowired
    private GameRepo gameRepo;

    @Autowired
    private GameEnvironmentRepo gameEnvironmentRepo;

    @Autowired
    private ApiKeyRepo apiKeyRepo;

    /**
     * 创建新游戏
     */
    public GameDTO createGame(GameDTO dto) {
        logger.info("Creating game: {}", dto.name);

        // 生成唯一ID
        if (dto.id == null || dto.id.trim().isEmpty()) {
            dto.id = "game_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }

        // 转换并保存
        GameEntity entity = dto.toEntity();
        entity = gameRepo.save(entity);

        // 创建默认生产环境
        createDefaultEnvironments(entity.id);

        logger.info("Game created successfully: {} (ID: {})", entity.name, entity.id);
        return new GameDTO(entity);
    }

    /**
     * 更新游戏信息
     */
    public GameDTO updateGame(String gameId, GameDTO dto) {
        logger.info("Updating game: {}", gameId);

        GameEntity entity = gameRepo.findById(gameId)
            .filter(game -> game.deletedAt == null)
            .orElseThrow(() -> new IllegalArgumentException("Game not found: " + gameId));

        // 检查状态变更权限
        if (dto.status != null && dto.status != entity.status) {
            validateStatusChange(entity, dto.status);
        }

        dto.updateEntity(entity);
        entity = gameRepo.save(entity);

        logger.info("Game updated successfully: {}", gameId);
        return new GameDTO(entity);
    }

    /**
     * 删除游戏（软删除）
     */
    public void deleteGame(String gameId) {
        logger.info("Deleting game: {}", gameId);

        GameEntity entity = gameRepo.findById(gameId)
            .filter(game -> game.deletedAt == null)
            .orElseThrow(() -> new IllegalArgumentException("Game not found: " + gameId));

        // 检查是否可以删除
        if (entity.status == GameEntity.GameStatus.LIVE) {
            throw new IllegalStateException("Cannot delete live game. Please change status first.");
        }

        // 软删除游戏及相关资源
        entity.deletedAt = LocalDateTime.now();
        entity.status = GameEntity.GameStatus.DISCONTINUED;
        gameRepo.save(entity);

        // 软删除相关环境和API密钥
        softDeleteRelatedResources(gameId);

        logger.info("Game deleted successfully: {}", gameId);
    }

    /**
     * 根据ID获取游戏
     */
    @Transactional(readOnly = true)
    public Optional<GameDTO> getGame(String gameId) {
        return gameRepo.findById(gameId)
            .filter(game -> game.deletedAt == null)
            .map(entity -> {
                GameDTO dto = new GameDTO(entity);
                enrichWithStatistics(dto);
                return dto;
            });
    }

    /**
     * 获取游戏列表（分页）
     */
    @Transactional(readOnly = true)
    public Page<GameDTO> getGames(Pageable pageable) {
        return gameRepo.findByDeletedAtIsNull(pageable)
            .map(entity -> {
                GameDTO dto = new GameDTO(entity);
                enrichWithStatistics(dto);
                return dto;
            });
    }

    /**
     * 搜索游戏
     */
    @Transactional(readOnly = true)
    public Page<GameDTO> searchGames(String query, Pageable pageable) {
        return gameRepo.searchByName(query, pageable)
            .map(entity -> {
                GameDTO dto = new GameDTO(entity);
                enrichWithStatistics(dto);
                return dto;
            });
    }

    /**
     * 根据状态获取游戏
     */
    @Transactional(readOnly = true)
    public List<GameDTO> getGamesByStatus(GameEntity.GameStatus status) {
        return gameRepo.findByStatusAndDeletedAtIsNull(status).stream()
            .map(entity -> {
                GameDTO dto = new GameDTO(entity);
                enrichWithStatistics(dto);
                return dto;
            })
            .collect(Collectors.toList());
    }

    /**
     * 根据类型获取游戏
     */
    @Transactional(readOnly = true)
    public List<GameDTO> getGamesByGenre(GameEntity.GameGenre genre) {
        return gameRepo.findByGenreAndDeletedAtIsNull(genre).stream()
            .map(entity -> {
                GameDTO dto = new GameDTO(entity);
                enrichWithStatistics(dto);
                return dto;
            })
            .collect(Collectors.toList());
    }

    /**
     * 根据平台获取游戏
     */
    @Transactional(readOnly = true)
    public List<GameDTO> getGamesByPlatform(GameEntity.GamePlatform platform) {
        return gameRepo.findByPlatform(platform).stream()
            .map(entity -> {
                GameDTO dto = new GameDTO(entity);
                enrichWithStatistics(dto);
                return dto;
            })
            .collect(Collectors.toList());
    }

    /**
     * 发布游戏（变更为LIVE状态）
     */
    public GameDTO publishGame(String gameId) {
        logger.info("Publishing game: {}", gameId);

        GameEntity entity = gameRepo.findById(gameId)
            .filter(game -> game.deletedAt == null)
            .orElseThrow(() -> new IllegalArgumentException("Game not found: " + gameId));

        if (entity.status == GameEntity.GameStatus.LIVE) {
            throw new IllegalStateException("Game is already live");
        }

        // 验证发布前检查
        validateGameReadyForPublish(entity);

        entity.status = GameEntity.GameStatus.LIVE;
        if (entity.releaseDate == null) {
            entity.releaseDate = LocalDateTime.now();
        }
        entity = gameRepo.save(entity);

        logger.info("Game published successfully: {}", gameId);
        return new GameDTO(entity);
    }

    /**
     * 下线游戏
     */
    public GameDTO unpublishGame(String gameId) {
        logger.info("Unpublishing game: {}", gameId);

        GameEntity entity = gameRepo.findById(gameId)
            .filter(game -> game.deletedAt == null)
            .orElseThrow(() -> new IllegalArgumentException("Game not found: " + gameId));

        if (entity.status != GameEntity.GameStatus.LIVE) {
            throw new IllegalStateException("Game is not live");
        }

        entity.status = GameEntity.GameStatus.MAINTENANCE;
        entity = gameRepo.save(entity);

        logger.info("Game unpublished successfully: {}", gameId);
        return new GameDTO(entity);
    }

    /**
     * 获取游戏统计信息
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getGameStatistics() {
        List<Object> stats = gameRepo.getGameStatistics();
        if (stats.isEmpty()) {
            return Map.of(
                "totalGames", 0L,
                "liveGames", 0L,
                "devGames", 0L,
                "testGames", 0L,
                "multiplayerGames", 0L
            );
        }
        return (Map<String, Object>) stats.get(0);
    }

    // 私有辅助方法

    /**
     * 创建默认环境
     */
    private void createDefaultEnvironments(String gameId) {
        // 创建生产环境
        GameEnvironmentEntity prodEnv = new GameEnvironmentEntity();
        prodEnv.id = "env_" + gameId + "_prod";
        prodEnv.gameId = gameId;
        prodEnv.name = "production";
        prodEnv.displayName = "Production Environment";
        prodEnv.type = GameEnvironmentEntity.EnvironmentType.PRODUCTION;
        prodEnv.status = GameEnvironmentEntity.EnvironmentStatus.ACTIVE;
        prodEnv.dataNamespace = gameId + "_prod";
        prodEnv.requireHttps = true;
        prodEnv.enableAlerts = true;
        gameEnvironmentRepo.save(prodEnv);

        // 创建开发环境
        GameEnvironmentEntity devEnv = new GameEnvironmentEntity();
        devEnv.id = "env_" + gameId + "_dev";
        devEnv.gameId = gameId;
        devEnv.name = "development";
        devEnv.displayName = "Development Environment";
        devEnv.type = GameEnvironmentEntity.EnvironmentType.DEVELOPMENT;
        devEnv.status = GameEnvironmentEntity.EnvironmentStatus.ACTIVE;
        devEnv.dataNamespace = gameId + "_dev";
        devEnv.enableDebugMode = true;
        devEnv.requireHttps = false;
        gameEnvironmentRepo.save(devEnv);
    }

    /**
     * 验证状态变更
     */
    private void validateStatusChange(GameEntity entity, GameEntity.GameStatus newStatus) {
        // 定义允许的状态转换
        boolean validTransition = switch (entity.status) {
            case DEVELOPMENT -> newStatus == GameEntity.GameStatus.TESTING;
            case TESTING -> newStatus == GameEntity.GameStatus.LIVE || newStatus == GameEntity.GameStatus.DEVELOPMENT;
            case LIVE -> newStatus == GameEntity.GameStatus.MAINTENANCE;
            case MAINTENANCE -> newStatus == GameEntity.GameStatus.LIVE || newStatus == GameEntity.GameStatus.DISCONTINUED;
            case DISCONTINUED -> false; // 已停服不能变更状态
        };

        if (!validTransition) {
            throw new IllegalArgumentException(
                String.format("Invalid status transition from %s to %s", entity.status, newStatus));
        }
    }

    /**
     * 验证游戏是否准备发布
     */
    private void validateGameReadyForPublish(GameEntity entity) {
        if (entity.name == null || entity.name.trim().isEmpty()) {
            throw new IllegalStateException("Game name is required for publishing");
        }

        if (entity.platforms == null || entity.platforms.isEmpty()) {
            throw new IllegalStateException("At least one platform must be specified for publishing");
        }

        if (entity.currentVersion == null || entity.currentVersion.trim().isEmpty()) {
            throw new IllegalStateException("Current version is required for publishing");
        }
    }

    /**
     * 软删除相关资源
     */
    private void softDeleteRelatedResources(String gameId) {
        // 软删除游戏环境
        List<GameEnvironmentEntity> environments = gameEnvironmentRepo.findByGameIdAndDeletedAtIsNull(gameId);
        for (GameEnvironmentEntity env : environments) {
            env.deletedAt = LocalDateTime.now();
            env.status = GameEnvironmentEntity.EnvironmentStatus.INACTIVE;
            gameEnvironmentRepo.save(env);
        }

        // 撤销相关API密钥
        List<ApiKeyEntity> apiKeys = apiKeyRepo.findByGameIdAndStatus(gameId, ApiKeyEntity.ApiKeyStatus.ACTIVE);
        for (ApiKeyEntity apiKey : apiKeys) {
            apiKey.revoke();
            apiKeyRepo.save(apiKey);
        }
    }

    /**
     * 丰富统计信息
     */
    private void enrichWithStatistics(GameDTO dto) {
        // 统计环境数量
        dto.totalEnvironments = (int) gameEnvironmentRepo.countByGameIdAndDeletedAtIsNull(dto.id);

        // 统计API密钥数量
        dto.totalApiKeys = (int) apiKeyRepo.countByGameIdAndStatus(dto.id, ApiKeyEntity.ApiKeyStatus.ACTIVE);
    }
}
