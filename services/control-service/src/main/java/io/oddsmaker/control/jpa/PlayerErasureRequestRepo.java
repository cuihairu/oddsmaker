package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PlayerErasureRequestRepo extends JpaRepository<PlayerErasureRequestEntity, String> {

    List<PlayerErasureRequestEntity> findByGameIdOrderByCreatedAtDesc(String gameId);

    List<PlayerErasureRequestEntity> findByStatusOrderByCreatedAtAsc(PlayerErasureRequestEntity.Status status);

    List<PlayerErasureRequestEntity> findByStatus(PlayerErasureRequestEntity.Status status);

    /** 僵尸 PROCESSING：startedAt 早于阈值（sweep 重置回 PENDING） */
    @Query("SELECT r FROM PlayerErasureRequestEntity r WHERE r.status = 'PROCESSING' AND r.startedAt < :threshold")
    List<PlayerErasureRequestEntity> findStaleProcessing(@Param("threshold") java.time.LocalDateTime threshold);
}
