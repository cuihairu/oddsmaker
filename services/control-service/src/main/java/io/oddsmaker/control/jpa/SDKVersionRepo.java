package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * SDK版本仓库接口
 */
@Repository
public interface SDKVersionRepo extends JpaRepository<SDKVersionEntity, String> {

    /**
     * 根据平台查找版本
     */
    List<SDKVersionEntity> findByPlatformOrderByCreatedAtDesc(SDKVersionEntity.SDKPlatform platform);

    /**
     * 根据平台和状态查找版本
     */
    List<SDKVersionEntity> findByPlatformAndVersionStatusOrderByCreatedAtDesc(
            SDKVersionEntity.SDKPlatform platform, SDKVersionEntity.VersionStatus status);

    /**
     * 查找最新版本
     */
    @Query("SELECT v FROM SDKVersionEntity v WHERE v.platform = :platform AND v.versionStatus = 'RELEASED' ORDER BY v.releasedAt DESC")
    Optional<SDKVersionEntity> findLatestByPlatform(@Param("platform") SDKVersionEntity.SDKPlatform platform);

    /**
     * 查找即将退役的版本
     */
    @Query("SELECT v FROM SDKVersionEntity v WHERE v.versionStatus = 'DEPRECATED' AND v.retirementDate IS NOT NULL AND v.retirementDate < :threshold ORDER BY v.retirementDate ASC")
    List<SDKVersionEntity> findRetiringSoon(@Param("threshold") java.time.LocalDateTime threshold);

    /**
     * 统计各平台版本数量
     */
    @Query("SELECT v.platform, COUNT(v) FROM SDKVersionEntity v GROUP BY v.platform")
    List<Object[]> countByPlatform();

    /**
     * 统计各状态版本数量
     */
    @Query("SELECT v.versionStatus, COUNT(v) FROM SDKVersionEntity v GROUP BY v.versionStatus")
    List<Object[]> countByStatus();

    /**
     * 检查版本是否存在
     */
    boolean existsByPlatformAndVersion(SDKVersionEntity.SDKPlatform platform, String version);

}
