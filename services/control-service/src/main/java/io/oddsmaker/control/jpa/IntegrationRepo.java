package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface IntegrationRepo extends JpaRepository<IntegrationEntity, String> {

    /**
     * 查找游戏的活跃集成
     */
    @Query("SELECT i FROM IntegrationEntity i WHERE i.gameId = :gameId AND i.integrationStatus = 'ACTIVE' AND i.enabled = true AND i.deletedAt IS NULL ORDER BY i.priority DESC")
    List<IntegrationEntity> findActiveByGameId(@Param("gameId") String gameId);

    /**
     * 查找游戏的所有集成
     */
    @Query("SELECT i FROM IntegrationEntity i WHERE i.gameId = :gameId AND i.deletedAt IS NULL ORDER BY i.createdAt DESC")
    List<IntegrationEntity> findByGameId(@Param("gameId") String gameId);

    /**
     * 根据类型查找集成
     */
    @Query("SELECT i FROM IntegrationEntity i WHERE i.gameId = :gameId AND i.integrationType = :type AND i.deletedAt IS NULL ORDER BY i.priority DESC")
    List<IntegrationEntity> findByGameIdAndType(@Param("gameId") String gameId, @Param("type") IntegrationEntity.IntegrationType type);

    /**
     * 查找需要重试的集成
     */
    @Query("SELECT i FROM IntegrationEntity i WHERE i.integrationStatus = 'FAILED' AND i.retryCount < i.maxRetries AND i.enabled = true AND i.deletedAt IS NULL")
    List<IntegrationEntity> findRetryable();

    /**
     * 根据名称查找
     */
    @Query("SELECT i FROM IntegrationEntity i WHERE i.gameId = :gameId AND i.name = :name AND i.deletedAt IS NULL")
    Optional<IntegrationEntity> findByGameIdAndName(@Param("gameId") String gameId, @Param("name") String name);
}
