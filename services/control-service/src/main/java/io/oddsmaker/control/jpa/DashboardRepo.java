package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 仪表盘仓储：软删过滤 + 游戏内唯一名查询。
 */
public interface DashboardRepo extends JpaRepository<DashboardEntity, String> {

    List<DashboardEntity> findByGameIdAndDeletedAtIsNullOrderByNameAsc(String gameId);

    List<DashboardEntity> findByGameIdAndDeletedAtIsNull(String gameId);

    Optional<DashboardEntity> findByIdAndDeletedAtIsNull(String id);

    Optional<DashboardEntity> findByGameIdAndNameAndDeletedAtIsNull(String gameId, String name);
}
