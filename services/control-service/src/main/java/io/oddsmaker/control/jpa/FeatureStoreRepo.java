package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface FeatureStoreRepo extends JpaRepository<FeatureStoreEntity, Long> {

    List<FeatureStoreEntity> findByGameIdAndEnvironmentAndScopeKeyAndWindowEndGreaterThanEqualOrderByWindowEndDesc(
        String gameId, String environment, String scopeKey, LocalDateTime since);
}
