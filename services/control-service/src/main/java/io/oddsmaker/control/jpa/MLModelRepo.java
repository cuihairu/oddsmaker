package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * ML模型仓库接口
 */
@Repository
public interface MLModelRepo extends JpaRepository<MLModelEntity, String> {

    /**
     * 根据游戏ID查找模型
     */
    List<MLModelEntity> findByGameIdAndDeletedAtIsNull(String gameId);

    /**
     * 查找已部署的模型
     */
    @Query("SELECT m FROM MLModelEntity m WHERE m.modelStatus = 'DEPLOYED' AND m.deletedAt IS NULL")
    List<MLModelEntity> findAllDeployed();

    /**
     * 根据游戏ID查找已部署的模型
     */
    @Query("SELECT m FROM MLModelEntity m WHERE m.gameId = :gameId AND m.modelStatus = 'DEPLOYED' AND m.deletedAt IS NULL")
    List<MLModelEntity> findDeployedByGameId(@Param("gameId") String gameId);

    /**
     * 查找A/B测试模型
     */
    @Query("SELECT m FROM MLModelEntity m WHERE m.isAbTest = true AND m.deletedAt IS NULL")
    List<MLModelEntity> findAllAbTestModels();

    /**
     * 统计各状态模型数量
     */
    @Query("SELECT m.modelStatus, COUNT(m) FROM MLModelEntity m WHERE m.deletedAt IS NULL GROUP BY m.modelStatus")
    List<Object[]> countByStatus();

    /**
     * 统计各类型模型数量
     */
    @Query("SELECT m.modelType, COUNT(m) FROM MLModelEntity m WHERE m.deletedAt IS NULL GROUP BY m.modelType")
    List<Object[]> countByType();

    /**
     * 检查模型名称是否存在
     */
    boolean existsByModelNameAndGameIdAndDeletedAtIsNull(String modelName, String gameId);
}
