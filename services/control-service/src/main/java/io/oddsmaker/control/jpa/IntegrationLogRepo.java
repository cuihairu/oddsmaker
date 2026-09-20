package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface IntegrationLogRepo extends JpaRepository<IntegrationLogEntity, String> {

    /**
     * 查找集成的调用日志
     */
    @Query("SELECT l FROM IntegrationLogEntity l WHERE l.integrationId = :integrationId ORDER BY l.createdAt DESC")
    List<IntegrationLogEntity> findByIntegrationId(@Param("integrationId") String integrationId);

    /**
     * 查找游戏的调用日志
     */
    @Query("SELECT l FROM IntegrationLogEntity l WHERE l.gameId = :gameId ORDER BY l.createdAt DESC")
    List<IntegrationLogEntity> findByGameId(@Param("gameId") String gameId);

    /**
     * 统计调用次数
     */
    @Query("SELECT COUNT(l) FROM IntegrationLogEntity l WHERE l.integrationId = :integrationId AND l.createdAt >= :since")
    long countCallsSince(@Param("integrationId") String integrationId, @Param("since") LocalDateTime since);

    /**
     * 统计成功调用次数
     */
    @Query("SELECT COUNT(l) FROM IntegrationLogEntity l WHERE l.integrationId = :integrationId AND l.callStatus = 'SUCCESS' AND l.createdAt >= :since")
    long countSuccessCallsSince(@Param("integrationId") String integrationId, @Param("since") LocalDateTime since);

    /**
     * 统计失败调用次数
     */
    @Query("SELECT COUNT(l) FROM IntegrationLogEntity l WHERE l.integrationId = :integrationId AND l.callStatus IN ('FAILED', 'TIMEOUT') AND l.createdAt >= :since")
    long countFailedCallsSince(@Param("integrationId") String integrationId, @Param("since") LocalDateTime since);

    /**
     * 计算平均响应时间
     */
    @Query("SELECT AVG(l.durationMs) FROM IntegrationLogEntity l WHERE l.integrationId = :integrationId AND l.callStatus = 'SUCCESS' AND l.createdAt >= :since")
    Long averageDurationSince(@Param("integrationId") String integrationId, @Param("since") LocalDateTime since);

    /**
     * 删除过期日志
     */
    @Query("DELETE FROM IntegrationLogEntity l WHERE l.expiresAt < :now")
    int deleteExpired(@Param("now") LocalDateTime now);

    /**
     * 统计游戏日志数量
     */
    @Query("SELECT COUNT(l) FROM IntegrationLogEntity l WHERE l.gameId = :gameId AND l.createdAt >= :since")
    long countByGameIdSince(@Param("gameId") String gameId, @Param("since") LocalDateTime since);
}
