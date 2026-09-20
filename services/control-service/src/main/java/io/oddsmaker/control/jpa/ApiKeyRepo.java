package io.oddsmaker.control.jpa;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * API密钥数据访问接口。查询入口以 game/environment 为主。
 */
@Repository
public interface ApiKeyRepo extends JpaRepository<ApiKeyEntity, String> {

    List<ApiKeyEntity> findByGameId(String gameId);

    /**
     * 根据游戏ID和状态查找API密钥
     */
    List<ApiKeyEntity> findByGameIdAndStatus(String gameId, ApiKeyEntity.ApiKeyStatus status);

    /**
     * 统计游戏的API密钥数量
     */
    long countByGameIdAndStatus(String gameId, ApiKeyEntity.ApiKeyStatus status);

    /**
     * 根据状态查找API密钥
     */
    List<ApiKeyEntity> findByStatus(ApiKeyEntity.ApiKeyStatus status);

    /**
     * 按游戏和环境搜索 API 密钥。
     */
    @Query("SELECT ak FROM ApiKeyEntity ak WHERE " +
           "(:gameId = '' OR ak.gameId = :gameId) AND " +
           "(:environmentId = '' OR ak.environmentId = :environmentId) AND " +
           "(:query = '' OR " +
           "LOWER(COALESCE(ak.name, '')) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(COALESCE(ak.description, '')) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(ak.apiKey) LIKE LOWER(CONCAT('%', :query, '%'))) AND " +
           "ak.revokedAt IS NULL")
    Page<ApiKeyEntity> searchApiKeysByScope(@Param("gameId") String gameId,
                                            @Param("environmentId") String environmentId,
                                            @Param("query") String query,
                                            Pageable pageable);

}
