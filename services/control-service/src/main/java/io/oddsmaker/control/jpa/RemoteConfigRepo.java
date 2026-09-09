package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RemoteConfigRepo extends JpaRepository<RemoteConfigEntity, String> {

    @Query("SELECT c FROM RemoteConfigEntity c WHERE c.gameId = :gameId AND c.deletedAt IS NULL "
        + "AND (:environment IS NULL OR c.environmentId = '' OR c.environmentId = :environment) "
        + "ORDER BY c.environmentId, c.configKey")
    List<RemoteConfigEntity> findEffective(@Param("gameId") String gameId,
                                           @Param("environment") String environment);

    @Query("SELECT c FROM RemoteConfigEntity c WHERE c.gameId = :gameId AND c.deletedAt IS NULL "
        + "ORDER BY c.environmentId, c.configKey")
    List<RemoteConfigEntity> findByGameId(@Param("gameId") String gameId);

    Optional<RemoteConfigEntity> findByGameIdAndEnvironmentIdAndConfigKeyAndDeletedAtIsNull(
        String gameId, String environmentId, String configKey);
}
