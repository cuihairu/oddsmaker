package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface BlockListRepo extends JpaRepository<BlockListEntity, String> {

    /**
     * 检查目标是否被封禁
     */
    @Query("SELECT bl FROM BlockListEntity bl WHERE bl.gameId = :gameId AND bl.targetType = :targetType AND bl.targetValue = :targetValue AND bl.deletedAt IS NULL AND (bl.unblockedAt IS NULL) AND (bl.isPermanent = true OR bl.expiresAt > :now)")
    Optional<BlockListEntity> findActiveBlock(@Param("gameId") String gameId, @Param("targetType") String targetType, @Param("targetValue") String targetValue, @Param("now") LocalDateTime now);

    /**
     * 查找游戏的所有封禁
     */
    @Query("SELECT bl FROM BlockListEntity bl WHERE bl.gameId = :gameId AND bl.deletedAt IS NULL ORDER BY bl.createdAt DESC")
    List<BlockListEntity> findByGameId(@Param("gameId") String gameId);

    /**
     * 查找环境的封禁
     */
    @Query("SELECT bl FROM BlockListEntity bl WHERE bl.gameId = :gameId AND (bl.environmentId = :environmentId OR bl.environmentId IS NULL) AND bl.deletedAt IS NULL ORDER BY bl.createdAt DESC")
    List<BlockListEntity> findByGameIdAndEnvironment(@Param("gameId") String gameId, @Param("environmentId") String environmentId);

    /**
     * 根据风险案例查找封禁
     */
    @Query("SELECT bl FROM BlockListEntity bl WHERE bl.riskCaseId = :riskCaseId AND bl.deletedAt IS NULL")
    List<BlockListEntity> findByRiskCaseId(@Param("riskCaseId") String riskCaseId);

    /**
     * 查找活跃封禁
     */
    @Query("SELECT bl FROM BlockListEntity bl WHERE bl.gameId = :gameId AND bl.deletedAt IS NULL AND bl.unblockedAt IS NULL AND (bl.isPermanent = true OR bl.expiresAt > :now) ORDER BY bl.createdAt DESC")
    List<BlockListEntity> findActiveBlocks(@Param("gameId") String gameId, @Param("now") LocalDateTime now);

    /**
     * 查找过期封禁
     */
    @Query("SELECT bl FROM BlockListEntity bl WHERE bl.deletedAt IS NULL AND bl.unblockedAt IS NULL AND bl.isPermanent = false AND bl.expiresAt <= :now")
    List<BlockListEntity> findExpiredBlocks(@Param("now") LocalDateTime now);

    /**
     * 根据目标类型查找
     */
    @Query("SELECT bl FROM BlockListEntity bl WHERE bl.gameId = :gameId AND bl.targetType = :targetType AND bl.deletedAt IS NULL ORDER BY bl.createdAt DESC")
    List<BlockListEntity> findByGameIdAndTargetType(@Param("gameId") String gameId, @Param("targetType") String targetType);

    /**
     * 搜索封禁
     */
    @Query("SELECT bl FROM BlockListEntity bl WHERE bl.gameId = :gameId AND (bl.targetValue LIKE %:query% OR bl.targetName LIKE %:query% OR bl.blockReason LIKE %:query%) AND bl.deletedAt IS NULL ORDER BY bl.createdAt DESC")
    List<BlockListEntity> search(@Param("gameId") String gameId, @Param("query") String query);

    /**
     * 记录命中
     */
    @Modifying
    @Query("UPDATE BlockListEntity bl SET bl.hitCount = COALESCE(bl.hitCount, 0) + 1, bl.lastHitAt = :now WHERE bl.id = :id")
    int recordHit(@Param("id") String id, @Param("now") LocalDateTime now);

    /**
     * 批量解除过期封禁
     */
    @Modifying
    @Query("UPDATE BlockListEntity bl SET bl.unblockedAt = :now, bl.unblockReason = 'Expired automatically' WHERE bl.id IN :ids")
    int batchUnblock(@Param("ids") List<String> ids, @Param("now") LocalDateTime now);

    // ========== 玩家数据删除（GDPR erasure）：封禁记录匿名化占位（保留防欺诈审计线索） ==========

    /** 玩家标识类封禁（仅 player_id/user_id/device_id；ip/ip_range/account_id 不属玩家标识不动） */
    List<BlockListEntity> findByGameIdAndTargetValueInAndTargetTypeIn(String gameId,
                                                                      List<String> targetValues,
                                                                      List<String> targetTypes);
}
