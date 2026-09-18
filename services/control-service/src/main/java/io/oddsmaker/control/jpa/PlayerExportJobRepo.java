package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
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

    /** 玩家数据删除：定位该玩家全量导出任务（行删除前先清理 file_path 磁盘文件） */
    List<PlayerExportJobEntity> findByGameIdAndPlayerIdIn(String gameId, List<String> playerIds);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("DELETE FROM PlayerExportJobEntity j "
        + "WHERE j.gameId = :gameId AND j.playerId IN :playerIds")
    int deleteByGameIdAndPlayerIdIn(@Param("gameId") String gameId, @Param("playerIds") List<String> playerIds);
}
