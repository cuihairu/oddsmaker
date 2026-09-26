package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DimensionSyncStatusRepo extends JpaRepository<DimensionSyncStatusEntity, String> {

    List<DimensionSyncStatusEntity> findByGameId(String gameId);

    List<DimensionSyncStatusEntity> findByGameIdAndEnvironment(String gameId, String environment);

    Optional<DimensionSyncStatusEntity> findByGameIdAndEnvironmentAndSourceKey(
            String gameId, String environment, String sourceKey);
}
