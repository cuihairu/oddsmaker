package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PlayerLoginLogRepo extends JpaRepository<PlayerLoginLogEntity, String> {

    List<PlayerLoginLogEntity> findByGameIdAndPlayerIdOrderByLoginAtDesc(String gameId, String playerId);

    long countByGameIdAndPlayerId(String gameId, String playerId);

    Optional<PlayerLoginLogEntity> findFirstByGameIdAndPlayerIdOrderByLoginAtDesc(String gameId, String playerId);
}
