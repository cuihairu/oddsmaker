package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface QuotaRepo extends JpaRepository<QuotaEntity, String> {

    /**
     * 查找游戏的所有配额
     */
    @Query("SELECT q FROM QuotaEntity q WHERE q.gameId = :gameId AND q.environmentId IS NULL AND q.deletedAt IS NULL ORDER BY q.resourceType")
    List<QuotaEntity> findByGameId(@Param("gameId") String gameId);

    /**
     * 查找特定资源类型的配额
     */
    @Query("SELECT q FROM QuotaEntity q WHERE q.gameId = :gameId AND q.environmentId IS NULL AND q.resourceType = :resourceType AND q.deletedAt IS NULL")
    Optional<QuotaEntity> findByGameAndResourceType(@Param("gameId") String gameId, @Param("resourceType") QuotaEntity.ResourceType resourceType);

    /**
     * 查找环境的特定资源类型配额
     */
    @Query("SELECT q FROM QuotaEntity q WHERE q.gameId = :gameId AND q.environmentId = :environmentId AND q.resourceType = :resourceType AND q.deletedAt IS NULL")
    Optional<QuotaEntity> findByGameEnvironmentAndResourceType(@Param("gameId") String gameId, @Param("environmentId") String environmentId, @Param("resourceType") QuotaEntity.ResourceType resourceType);

    /**
     * 查找需要重置的配额
     */
    @Query("SELECT q FROM QuotaEntity q WHERE q.resetAt IS NOT NULL AND q.resetAt < :now AND q.deletedAt IS NULL")
    List<QuotaEntity> findResetNeeded(@Param("now") LocalDateTime now);

}
