package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface DataQualityMetricsRepo extends JpaRepository<DataQualityMetricsEntity, Long> {

    Optional<DataQualityMetricsEntity> findByGameIdAndEnvironmentAndWindowStartAndWindowSec(
        String gameId, String environment, LocalDateTime windowStart, int windowSec);

    List<DataQualityMetricsEntity> findByGameIdAndEnvironmentAndWindowStartGreaterThanEqualOrderByWindowStartDesc(
        String gameId, String environment, LocalDateTime since);
}
