package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RedeemRecordRepo extends JpaRepository<RedeemRecordEntity, String> {

    List<RedeemRecordEntity> findByBatchIdAndPlayerKey(String batchId, String playerKey);

    long countByBatchId(String batchId);

    List<RedeemRecordEntity> findByGameIdAndPlayerKeyOrderByRedeemedAtDesc(String gameId, String playerKey);

    /** 玩家数据删除：物理删除兑换记录（player_key = player_id ∪ user_id） */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("DELETE FROM RedeemRecordEntity r "
        + "WHERE r.gameId = :gameId AND r.playerKey IN :playerKeys")
    int deleteByGameIdAndPlayerKeyIn(@Param("gameId") String gameId, @Param("playerKeys") List<String> playerKeys);
}
