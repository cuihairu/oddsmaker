package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 游戏环境数据访问接口
 */
@Repository
public interface GameEnvironmentRepo extends JpaRepository<GameEnvironmentEntity, String> {

    /**
     * 根据游戏ID查找环境
     */
    List<GameEnvironmentEntity> findByGameIdAndDeletedAtIsNull(String gameId);

    /**
     * 根据游戏ID和环境名称查找
     */
    List<GameEnvironmentEntity> findByGameIdAndNameAndDeletedAtIsNull(String gameId, String name);

    /**
     * 根据环境类型查找
     */
    List<GameEnvironmentEntity> findByTypeAndDeletedAtIsNull(GameEnvironmentEntity.EnvironmentType type);

    /**
     * 根据状态查找环境
     */
    List<GameEnvironmentEntity> findByStatusAndDeletedAtIsNull(GameEnvironmentEntity.EnvironmentStatus status);

    /**
     * 统计游戏的环境数量
     */
    long countByGameIdAndDeletedAtIsNull(String gameId);

    /**
     * 查找活跃的生产环境
     */
    @Query("SELECT e FROM GameEnvironmentEntity e WHERE e.type = 'PRODUCTION' AND e.status = 'ACTIVE' AND e.deletedAt IS NULL")
    List<GameEnvironmentEntity> findActiveProductionEnvironments();

    /**
     * 查找启用调试模式的环境
     */
    List<GameEnvironmentEntity> findByEnableDebugModeTrueAndDeletedAtIsNull();
}