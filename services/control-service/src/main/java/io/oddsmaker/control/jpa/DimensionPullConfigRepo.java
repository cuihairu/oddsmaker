package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 维度同步 HTTP Pull 配置仓储。
 */
public interface DimensionPullConfigRepo extends JpaRepository<DimensionPullConfigEntity, String> {

    List<DimensionPullConfigEntity> findByGameIdAndEnvironment(String gameId, String environment);

    Optional<DimensionPullConfigEntity> findByGameIdAndEnvironmentAndSourceKey(
            String gameId, String environment, String sourceKey);

    List<DimensionPullConfigEntity> findByGameId(String gameId);
}
