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
     * 根据状态查找环境
     */
    List<GameEnvironmentEntity> findByStatusAndDeletedAtIsNull(GameEnvironmentEntity.EnvironmentStatus status);

    /**
     * 根据存储路由配置查找环境
     */
    List<GameEnvironmentEntity> findByStorageProfileIdAndDeletedAtIsNull(String storageProfileId);

    /**
     * 统计游戏的环境数量
     */
    long countByGameIdAndDeletedAtIsNull(String gameId);

}
