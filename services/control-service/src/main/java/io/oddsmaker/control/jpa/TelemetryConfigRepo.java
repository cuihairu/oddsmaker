package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 遥测配置仓库接口
 */
@Repository
public interface TelemetryConfigRepo extends JpaRepository<TelemetryConfigEntity, String> {

    /**
     * 根据游戏ID查找配置
     */
    List<TelemetryConfigEntity> findByGameIdAndDeletedAtIsNull(String gameId);

    /**
     * 根据游戏ID和环境查找配置
     */
    List<TelemetryConfigEntity> findByGameIdAndEnvironmentIdAndDeletedAtIsNull(String gameId, String environmentId);

    /**
     * 查找活跃的全局配置
     */
    @Query("SELECT c FROM TelemetryConfigEntity c WHERE c.gameId IS NULL AND c.configStatus = 'ACTIVE' AND c.deletedAt IS NULL")
    List<TelemetryConfigEntity> findActiveGlobalConfigs();

    /**
     * 根据游戏ID和配置类型查找活跃配置
     */
    @Query("SELECT c FROM TelemetryConfigEntity c WHERE c.gameId = :gameId AND c.configType = :configType AND c.configStatus = 'ACTIVE' AND c.deletedAt IS NULL ORDER BY c.priority ASC")
    List<TelemetryConfigEntity> findActiveByGameIdAndType(
            @Param("gameId") String gameId,
            @Param("configType") TelemetryConfigEntity.ConfigType configType);

    /**
     * 根据游戏ID、环境和配置类型查找活跃配置
     */
    @Query("SELECT c FROM TelemetryConfigEntity c WHERE c.gameId = :gameId AND c.environmentId = :environmentId AND c.configType = :configType AND c.configStatus = 'ACTIVE' AND c.deletedAt IS NULL ORDER BY c.priority ASC")
    List<TelemetryConfigEntity> findActiveByGameIdAndEnvironmentIdAndType(
            @Param("gameId") String gameId,
            @Param("environmentId") String environmentId,
            @Param("configType") TelemetryConfigEntity.ConfigType configType);

    /**
     * 统计各类型配置数量
     */
    @Query("SELECT c.configType, COUNT(c) FROM TelemetryConfigEntity c WHERE c.deletedAt IS NULL GROUP BY c.configType")
    List<Object[]> countByType();

    /**
     * 统计各状态配置数量
     */
    @Query("SELECT c.configStatus, COUNT(c) FROM TelemetryConfigEntity c WHERE c.deletedAt IS NULL GROUP BY c.configStatus")
    List<Object[]> countByStatus();

}
