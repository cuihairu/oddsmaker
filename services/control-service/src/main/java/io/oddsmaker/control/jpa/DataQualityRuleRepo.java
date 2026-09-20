package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DataQualityRuleRepo extends JpaRepository<DataQualityRuleEntity, String> {

    /**
     * 根据游戏查找规则
     */
    @Query("SELECT r FROM DataQualityRuleEntity r WHERE r.gameId = :gameId AND r.deletedAt IS NULL ORDER BY r.createdAt DESC")
    List<DataQualityRuleEntity> findByGameId(@Param("gameId") String gameId);

    /**
     * 根据管道查找规则
     */
    @Query("SELECT r FROM DataQualityRuleEntity r WHERE r.pipelineId = :pipelineId AND r.deletedAt IS NULL ORDER BY r.createdAt DESC")
    List<DataQualityRuleEntity> findByPipelineId(@Param("pipelineId") String pipelineId);

    /**
     * 查找活跃规则
     */
    @Query("SELECT r FROM DataQualityRuleEntity r WHERE r.enabled = true AND r.ruleStatus = 'ACTIVE' AND r.deletedAt IS NULL")
    List<DataQualityRuleEntity> findActive();

    /**
     * 根据类型查找规则
     */
    @Query("SELECT r FROM DataQualityRuleEntity r WHERE r.ruleType = :type AND r.deletedAt IS NULL ORDER BY r.severity DESC")
    List<DataQualityRuleEntity> findByType(@Param("type") DataQualityRuleEntity.RuleType type);

    /**
     * 根据名称查找规则
     */
    @Query("SELECT r FROM DataQualityRuleEntity r WHERE r.ruleName = :name AND r.deletedAt IS NULL")
    Optional<DataQualityRuleEntity> findByName(@Param("name") String name);
}
