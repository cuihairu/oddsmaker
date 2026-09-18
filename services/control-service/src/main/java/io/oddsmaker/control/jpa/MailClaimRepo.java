package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MailClaimRepo extends JpaRepository<MailClaimEntity, String> {

    Optional<MailClaimEntity> findByMailIdAndPlayerKey(String mailId, String playerKey);

    /** 玩家数据删除：物理删除领取记录（player_key = player_id ∪ user_id） */
    @Modifying
    @Query("DELETE FROM MailClaimEntity c WHERE c.gameId = :gameId AND c.playerKey IN :playerKeys")
    int deleteByGameIdAndPlayerKeyIn(@Param("gameId") String gameId, @Param("playerKeys") List<String> playerKeys);
}
