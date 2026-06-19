package io.oddsmaker.control.jpa;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 游戏数据访问接口
 */
@Repository
public interface GameRepo extends JpaRepository<GameEntity, String> {

    /**
     * 查找所有未删除游戏（分页）
     */
    Page<GameEntity> findByDeletedAtIsNull(Pageable pageable);

    /**
     * 查找已上线的游戏
     */
    List<GameEntity> findByStatusAndDeletedAtIsNull(GameEntity.GameStatus status);

    /**
     * 根据类型查找游戏
     */
    List<GameEntity> findByGenreAndDeletedAtIsNull(GameEntity.GameGenre genre);

    /**
     * 根据平台查找游戏
     */
    @Query("SELECT g FROM GameEntity g JOIN g.platforms p WHERE p = :platform AND g.deletedAt IS NULL")
    List<GameEntity> findByPlatform(@Param("platform") GameEntity.GamePlatform platform);

    /**
     * 根据名称搜索游戏
     */
    @Query("SELECT g FROM GameEntity g WHERE " +
           "(LOWER(g.name) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(g.displayName) LIKE LOWER(CONCAT('%', :query, '%'))) AND " +
           "g.deletedAt IS NULL")
    Page<GameEntity> searchByName(@Param("query") String query, Pageable pageable);

    /**
     * 统计未删除的游戏数量
     */
    long countByDeletedAtIsNull();

    /**
     * 统计指定状态的游戏数量
     */
    long countByStatusAndDeletedAtIsNull(GameEntity.GameStatus status);

    /**
     * 查找多人游戏
     */
    List<GameEntity> findByHasMultiplayerTrueAndDeletedAtIsNull();

    /**
     * 查找支持公会的游戏
     */
    List<GameEntity> findByHasGuildsTrueAndDeletedAtIsNull();

    /**
     * 查找启用实时分析的游戏
     */
    List<GameEntity> findByEnableRealTimeAnalyticsTrueAndDeletedAtIsNull();

    /**
     * 根据虚拟货币查找游戏
     */
    @Query("SELECT g FROM GameEntity g WHERE g.virtualCurrencies LIKE CONCAT('%', :currency, '%') AND g.deletedAt IS NULL")
    List<GameEntity> findByVirtualCurrency(@Param("currency") String currency);

    /**
     * 查找GDPR合规的游戏
     */
    List<GameEntity> findByGdprComplianceTrueAndDeletedAtIsNull();

    /**
     * 查找COPPA合规的游戏
     */
    List<GameEntity> findByCoppaComplianceTrueAndDeletedAtIsNull();

    /**
     * 获取游戏统计信息
     */
    @Query("SELECT new map(" +
           "COUNT(g) as totalGames, " +
           "COUNT(CASE WHEN g.status = 'LIVE' THEN 1 END) as liveGames, " +
           "COUNT(CASE WHEN g.status = 'DEVELOPMENT' THEN 1 END) as devGames, " +
           "COUNT(CASE WHEN g.status = 'TESTING' THEN 1 END) as testGames, " +
           "COUNT(CASE WHEN g.hasMultiplayer = TRUE THEN 1 END) as multiplayerGames" +
           ") FROM GameEntity g WHERE g.deletedAt IS NULL")
    List<Object> getGameStatistics();

}
