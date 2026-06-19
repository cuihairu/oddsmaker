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
}
