package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PlayerLoginLogRepo extends JpaRepository<PlayerLoginLogEntity, String> {

    List<PlayerLoginLogEntity> findByGameIdAndPlayerIdOrderByLoginAtDesc(String gameId, String playerId);

    long countByGameIdAndPlayerId(String gameId, String playerId);

    Optional<PlayerLoginLogEntity> findFirstByGameIdAndPlayerIdOrderByLoginAtDesc(String gameId, String playerId);

    /** 玩家数据删除：按玩家或设备维度物理删除登录日志（IP/设备型指纹属个人数据） */
    @Modifying
    @Query("DELETE FROM PlayerLoginLogEntity l WHERE l.gameId = :gameId "
        + "AND (l.playerId IN :playerIds OR l.deviceId IN :deviceIds)")
    int deleteByGameIdAndPlayerIdInOrDeviceIdIn(@Param("gameId") String gameId,
                                                @Param("playerIds") List<String> playerIds,
                                                @Param("deviceIds") List<String> deviceIds);
}
