package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RiskSampleSetRepo extends JpaRepository<RiskSampleSetEntity, String> {

    List<RiskSampleSetEntity> findByGameIdOrderByCreatedAtDesc(String gameId);

    boolean existsByGameIdAndName(String gameId, String name);
}
