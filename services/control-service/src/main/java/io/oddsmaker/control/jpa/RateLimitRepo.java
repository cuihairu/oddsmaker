package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 限流规则仓库（限流执行在网关内存实现，这里只承载规则管理 CRUD）。
 * 历史上的 findGlobal/findForApiKey/findForEndpoint/findForUser/findByApiKeyId/countByGameId
 * 查询只服务于已删除的 checkRequest 执行链，一并移除。
 */
@Repository
public interface RateLimitRepo extends JpaRepository<RateLimitEntity, String> {

    /**
     * 根据游戏查找
     */
    @Query("SELECT rl FROM RateLimitEntity rl WHERE rl.gameId = :gameId AND rl.deletedAt IS NULL ORDER BY rl.createdAt DESC")
    List<RateLimitEntity> findByGameId(@Param("gameId") String gameId);
}
