package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public interface PlayerPaymentRepo extends JpaRepository<PlayerPaymentEntity, String> {

    List<PlayerPaymentEntity> findByGameIdAndPlayerIdOrderByPaidAtDesc(String gameId, String playerId);

    Optional<PlayerPaymentEntity> findByGameIdAndOrderId(String gameId, String orderId);

    long countByGameIdAndPlayerIdAndStatus(String gameId, String playerId, PlayerPaymentEntity.Status status);

    /** 累计充值（仅 COMPLETED），无记录返回 0 */
    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM PlayerPaymentEntity p "
        + "WHERE p.gameId = :gameId AND p.playerId = :playerId AND p.status = 'COMPLETED'")
    BigDecimal sumCompletedAmount(@Param("gameId") String gameId, @Param("playerId") String playerId);
}
