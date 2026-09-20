package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * SDK密钥仓库接口
 */
@Repository
public interface SDKKeyRepo extends JpaRepository<SDKKeyEntity, String> {

    /**
     * 根据游戏ID查找SDK密钥
     */
    List<SDKKeyEntity> findByGameIdAndDeletedAtIsNull(String gameId);

    /**
     * 根据游戏ID和环境查找SDK密钥
     */
    List<SDKKeyEntity> findByGameIdAndEnvironmentAndDeletedAtIsNull(String gameId, String environment);

    /**
     * 根据公钥查找
     */
    Optional<SDKKeyEntity> findByPublicKeyAndDeletedAtIsNull(String publicKey);

    /**
     * 根据游戏ID查找活跃的SDK密钥
     */
    @Query("SELECT k FROM SDKKeyEntity k WHERE k.gameId = :gameId AND k.keyStatus = 'ACTIVE' AND k.deletedAt IS NULL AND (k.expiresAt IS NULL OR k.expiresAt > CURRENT_TIMESTAMP)")
    List<SDKKeyEntity> findActiveByGameId(@Param("gameId") String gameId);

    /**
     * 查找过期的SDK密钥
     */
    @Query("SELECT k FROM SDKKeyEntity k WHERE k.keyStatus = 'ACTIVE' AND k.expiresAt IS NOT NULL AND k.expiresAt < CURRENT_TIMESTAMP AND k.deletedAt IS NULL")
    List<SDKKeyEntity> findExpired();

    /**
     * 统计各平台SDK密钥数量
     */
    @Query("SELECT k.platform, COUNT(k) FROM SDKKeyEntity k WHERE k.deletedAt IS NULL GROUP BY k.platform")
    List<Object[]> countByPlatform();

    /**
     * 统计各状态SDK密钥数量
     */
    @Query("SELECT k.keyStatus, COUNT(k) FROM SDKKeyEntity k WHERE k.deletedAt IS NULL GROUP BY k.keyStatus")
    List<Object[]> countByStatus();

}
