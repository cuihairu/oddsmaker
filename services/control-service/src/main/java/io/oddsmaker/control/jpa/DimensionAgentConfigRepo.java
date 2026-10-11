package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Sync Agent 属性面托管档仓储。
 */
public interface DimensionAgentConfigRepo extends JpaRepository<DimensionAgentConfigEntity, String> {

    List<DimensionAgentConfigEntity> findByGameId(String gameId);

    Optional<DimensionAgentConfigEntity> findByGameIdAndEnvironmentAndAgentKey(
            String gameId, String environment, String agentKey);
}
