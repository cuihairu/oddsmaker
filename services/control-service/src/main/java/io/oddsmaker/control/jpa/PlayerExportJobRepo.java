package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface PlayerExportJobRepo extends JpaRepository<PlayerExportJobEntity, String> {

    List<PlayerExportJobEntity> findByGameIdAndPlayerIdOrderByCreatedAtDesc(String gameId, String playerId);

    List<PlayerExportJobEntity> findByGameIdOrderByCreatedAtDesc(String gameId);

    List<PlayerExportJobEntity> findByStatusOrderByCreatedAtAsc(PlayerExportJobEntity.Status status);

    List<PlayerExportJobEntity> findByStatusAndExpiresAtBefore(PlayerExportJobEntity.Status status,
                                                               LocalDateTime now);
}
