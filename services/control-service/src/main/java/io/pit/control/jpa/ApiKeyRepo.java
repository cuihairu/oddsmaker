package io.pit.control.jpa;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * API密钥数据访问接口 - 多租户模型
 */
@Repository
public interface ApiKeyRepo extends JpaRepository<ApiKeyEntity, String> {

    // 多租户方法
    List<ApiKeyEntity> findByOrgId(String orgId);
    List<ApiKeyEntity> findByGameId(String gameId);
    List<ApiKeyEntity> findByEnvironmentId(String environmentId);

    /**
     * 根据游戏ID和状态查找API密钥
     */
    List<ApiKeyEntity> findByGameIdAndStatus(String gameId, ApiKeyEntity.ApiKeyStatus status);

    /**
     * 统计游戏的API密钥数量
     */
    long countByGameIdAndStatus(String gameId, ApiKeyEntity.ApiKeyStatus status);

    /**
     * 根据组织ID查找API密钥（分页）
     */
    Page<ApiKeyEntity> findByOrgId(String orgId, Pageable pageable);

    /**
     * 根据状态查找API密钥
     */
    List<ApiKeyEntity> findByStatus(ApiKeyEntity.ApiKeyStatus status);

    /**
     * 查找活跃的API密钥
     */
    @Query("SELECT ak FROM ApiKeyEntity ak WHERE ak.status = 'ACTIVE' AND " +
           "(ak.expiresAt IS NULL OR ak.expiresAt > :now) AND ak.revokedAt IS NULL")
    List<ApiKeyEntity> findActiveApiKeys(@Param("now") LocalDateTime now);

    /**
     * 查找过期的API密钥
     */
    @Query("SELECT ak FROM ApiKeyEntity ak WHERE ak.expiresAt IS NOT NULL AND ak.expiresAt <= :now AND ak.status = 'ACTIVE'")
    List<ApiKeyEntity> findExpiredApiKeys(@Param("now") LocalDateTime now);

    /**
     * 查找需要轮换的API密钥
     */
    @Query("SELECT ak FROM ApiKeyEntity ak WHERE ak.autoRotate = TRUE AND ak.rotationDays IS NOT NULL AND " +
           "ak.createdAt <= :rotationTime AND ak.status = 'ACTIVE'")
    List<ApiKeyEntity> findApiKeysNeedingRotation(@Param("rotationTime") LocalDateTime rotationTime);

    /**
     * 根据创建者查找API密钥
     */
    List<ApiKeyEntity> findByCreatedBy(String userId);

    /**
     * 搜索API密钥
     */
    @Query("SELECT ak FROM ApiKeyEntity ak WHERE " +
           "(LOWER(ak.name) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(ak.description) LIKE LOWER(CONCAT('%', :query, '%'))) AND " +
           "ak.revokedAt IS NULL")
    Page<ApiKeyEntity> searchApiKeys(@Param("query") String query, Pageable pageable);

    /**
     * 在指定组织内搜索API密钥
     */
    @Query("SELECT ak FROM ApiKeyEntity ak WHERE ak.orgId = :orgId AND " +
           "(LOWER(ak.name) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(ak.description) LIKE LOWER(CONCAT('%', :query, '%'))) AND " +
           "ak.revokedAt IS NULL")
    Page<ApiKeyEntity> searchApiKeysInOrg(@Param("orgId") String orgId, @Param("query") String query, Pageable pageable);

    /**
     * 统计组织的API密钥数量
     */
    long countByOrgIdAndStatus(String orgId, ApiKeyEntity.ApiKeyStatus status);

    /**
     * 查找高使用量的API密钥
     */
    @Query("SELECT ak FROM ApiKeyEntity ak WHERE ak.totalRequests > :threshold ORDER BY ak.totalRequests DESC")
    List<ApiKeyEntity> findHighUsageApiKeys(@Param("threshold") Long threshold);

    /**
     * 查找长时间未使用的API密钥
     */
    @Query("SELECT ak FROM ApiKeyEntity ak WHERE (ak.lastUsedAt IS NULL OR ak.lastUsedAt < :beforeDate) AND ak.status = 'ACTIVE'")
    List<ApiKeyEntity> findUnusedApiKeys(@Param("beforeDate") LocalDateTime beforeDate);
}
