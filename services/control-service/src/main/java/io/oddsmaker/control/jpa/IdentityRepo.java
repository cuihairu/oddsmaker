package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface IdentityRepo extends JpaRepository<IdentityEntity, String> {

    /**
     * 根据游戏查找身份
     */
    @Query("SELECT i FROM IdentityEntity i WHERE i.gameId = :gameId AND i.status = 'ACTIVE' AND i.deletedAt IS NULL ORDER BY i.lastSeenAt DESC")
    List<IdentityEntity> findByGameId(@Param("gameId") String gameId);

    /**
     * 根据游戏和环境查找身份
     */
    @Query("SELECT i FROM IdentityEntity i WHERE i.gameId = :gameId AND (i.environmentId = :environmentId OR i.environmentId IS NULL) AND i.status = 'ACTIVE' AND i.deletedAt IS NULL ORDER BY i.lastSeenAt DESC")
    List<IdentityEntity> findByGameIdAndEnvironment(@Param("gameId") String gameId, @Param("environmentId") String environmentId);

    /**
     * 根据设备ID查找身份
     */
    @Query("SELECT i FROM IdentityEntity i WHERE i.gameId = :gameId AND i.deviceId = :deviceId AND i.status = 'ACTIVE' AND i.deletedAt IS NULL")
    Optional<IdentityEntity> findByDeviceId(@Param("gameId") String gameId, @Param("deviceId") String deviceId);

    /**
     * 根据用户ID查找身份
     */
    @Query("SELECT i FROM IdentityEntity i WHERE i.gameId = :gameId AND i.userId = :userId AND i.status = 'ACTIVE' AND i.deletedAt IS NULL")
    List<IdentityEntity> findByUserId(@Param("gameId") String gameId, @Param("userId") String userId);

    /**
     * 根据玩家ID查找身份
     */
    @Query("SELECT i FROM IdentityEntity i WHERE i.gameId = :gameId AND i.playerId = :playerId AND i.status = 'ACTIVE' AND i.deletedAt IS NULL")
    Optional<IdentityEntity> findByPlayerId(@Param("gameId") String gameId, @Param("playerId") String playerId);

    /**
     * 查找非活跃身份（超过N天未出现）
     */
    @Query("SELECT i FROM IdentityEntity i WHERE i.lastSeenAt < :since AND i.status = 'ACTIVE' AND i.deletedAt IS NULL")
    List<IdentityEntity> findInactiveSince(@Param("since") LocalDateTime since);

    /**
     * 查找需要合并的身份（同一设备有多个活跃身份）
     */
    @Query("SELECT i FROM IdentityEntity i WHERE i.deviceId IN (SELECT i2.deviceId FROM IdentityEntity i2 WHERE i2.gameId = :gameId AND i2.status = 'ACTIVE' AND i2.deletedAt IS NULL GROUP BY i2.deviceId HAVING COUNT(*) > 1) AND i.gameId = :gameId AND i.status = 'ACTIVE' AND i.deletedAt IS NULL ORDER BY i.deviceId, i.createdAt")
    List<IdentityEntity> findCandidatesForMerge(@Param("gameId") String gameId);

    /**
     * 统计游戏的活跃用户数
     */
    @Query("SELECT COUNT(DISTINCT i.userId) FROM IdentityEntity i WHERE i.gameId = :gameId AND i.userId IS NOT NULL AND i.status = 'ACTIVE' AND i.deletedAt IS NULL AND i.lastSeenAt >= :since")
    long countActiveUsersSince(@Param("gameId") String gameId, @Param("since") LocalDateTime since);

    /**
     * 统计游戏的活跃设备数
     */
    @Query("SELECT COUNT(DISTINCT i.deviceId) FROM IdentityEntity i WHERE i.gameId = :gameId AND i.status = 'ACTIVE' AND i.deletedAt IS NULL AND i.lastSeenAt >= :since")
    long countActiveDevicesSince(@Param("gameId") String gameId, @Param("since") LocalDateTime since);

    // ========== 玩家数据删除（GDPR erasure）：不做状态过滤，已删/已合并身份也要被清洗 ==========

    /** 按玩家ID查找（AnyStatus：含 MERGED/DELETED/软删行——erasure 漏掉非活跃行等于没删干净） */
    @Query("SELECT i FROM IdentityEntity i WHERE i.gameId = :gameId AND i.playerId = :playerId")
    List<IdentityEntity> findByGameIdAndPlayerIdAnyStatus(@Param("gameId") String gameId, @Param("playerId") String playerId);

    /** 按用户ID查找（AnyStatus） */
    @Query("SELECT i FROM IdentityEntity i WHERE i.gameId = :gameId AND i.userId = :userId")
    List<IdentityEntity> findByGameIdAndUserIdAnyStatus(@Param("gameId") String gameId, @Param("userId") String userId);

    /** 按设备ID查找（AnyStatus） */
    @Query("SELECT i FROM IdentityEntity i WHERE i.gameId = :gameId AND i.deviceId = :deviceId")
    List<IdentityEntity> findByGameIdAndDeviceIdAnyStatus(@Param("gameId") String gameId, @Param("deviceId") String deviceId);

    /** 物理删除（合规擦除：软删行内标识原值仍可查，物理删才彻底） */
    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM IdentityEntity i WHERE i.id IN :ids")
    int deleteAllByIdIn(@Param("ids") List<String> ids);
}
