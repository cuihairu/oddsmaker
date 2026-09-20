package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface FeatureFlagRepo extends JpaRepository<FeatureFlagEntity, String> {

    /**
     * 根据开关键查找
     */
    @Query("SELECT ff FROM FeatureFlagEntity ff WHERE ff.flagKey = :flagKey AND ff.deletedAt IS NULL")
    Optional<FeatureFlagEntity> findByKey(@Param("flagKey") String flagKey);

    /**
     * 查找启用的开关
     */
    @Query("SELECT ff FROM FeatureFlagEntity ff WHERE ff.flagStatus = 'ENABLED' AND ff.deletedAt IS NULL ORDER BY ff.flagKey")
    List<FeatureFlagEntity> findEnabled();

    /**
     * 查找已过期的开关
     */
    @Query("SELECT ff FROM FeatureFlagEntity ff WHERE ff.expiryDate IS NOT NULL AND ff.expiryDate < :now AND ff.flagStatus = 'ENABLED' AND ff.deletedAt IS NULL")
    List<FeatureFlagEntity> findExpired(@Param("now") LocalDateTime now);

    /**
     * 查找计划启用的开关
     */
    @Query("SELECT ff FROM FeatureFlagEntity ff WHERE ff.scheduledEnableAt IS NOT NULL AND ff.scheduledEnableAt <= :now AND ff.flagStatus = 'DISABLED' AND ff.deletedAt IS NULL")
    List<FeatureFlagEntity> findScheduledToEnable(@Param("now") LocalDateTime now);

    /**
     * 查找计划禁用的开关
     */
    @Query("SELECT ff FROM FeatureFlagEntity ff WHERE ff.scheduledDisableAt IS NOT NULL AND ff.scheduledDisableAt <= :now AND ff.flagStatus = 'ENABLED' AND ff.deletedAt IS NULL")
    List<FeatureFlagEntity> findScheduledToDisable(@Param("now") LocalDateTime now);

    /**
     * 搜索开关
     */
    @Query("SELECT ff FROM FeatureFlagEntity ff WHERE ff.flagKey LIKE :query OR ff.flagName LIKE :query OR ff.description LIKE :query AND ff.deletedAt IS NULL")
    List<FeatureFlagEntity> search(@Param("query") String query);

    /**
     * 统计开关状态
     */
    @Query("SELECT COUNT(ff) FROM FeatureFlagEntity ff WHERE ff.flagStatus = :status AND ff.deletedAt IS NULL")
    long countByStatus(@Param("status") FeatureFlagEntity.FlagStatus status);
}
