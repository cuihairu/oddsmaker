package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AlertEmailConfigRepo extends JpaRepository<AlertEmailConfigEntity, String> {

    /** 每游戏一条配置（唯一约束兜底） */
    Optional<AlertEmailConfigEntity> findByGameId(String gameId);

    /** 启用中的配置（按游戏），供告警触发侧投递 */
    List<AlertEmailConfigEntity> findByGameIdAndEnabledTrue(String gameId);
}
