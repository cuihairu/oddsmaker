package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CohortRepo extends JpaRepository<CohortEntity, String> {

    /**
     * 查找游戏的所有同期群
     */
    @Query("SELECT c FROM CohortEntity c WHERE c.gameId = :gameId AND c.deletedAt IS NULL ORDER BY c.createdAt DESC")
    List<CohortEntity> findByGameId(@Param("gameId") String gameId);

    /**
     * 查找环境的同期群
     */
    @Query("SELECT c FROM CohortEntity c WHERE c.gameId = :gameId AND (c.environmentId = :environmentId OR c.environmentId IS NULL) AND c.deletedAt IS NULL ORDER BY c.createdAt DESC")
    List<CohortEntity> findByGameIdAndEnvironment(@Param("gameId") String gameId, @Param("environmentId") String environmentId);

    /**
     * 查找已完成的同期群
     */
    @Query("SELECT c FROM CohortEntity c WHERE c.gameId = :gameId AND c.status = 'COMPLETED' AND c.deletedAt IS NULL ORDER BY c.calculatedAt DESC")
    List<CohortEntity> findCompletedByGameId(@Param("gameId") String gameId);

    /**
     * 根据类型查找
     */
    @Query("SELECT c FROM CohortEntity c WHERE c.gameId = :gameId AND c.cohortType = :cohortType AND c.deletedAt IS NULL ORDER BY c.createdAt DESC")
    List<CohortEntity> findByGameIdAndType(@Param("gameId") String gameId, @Param("cohortType") CohortEntity.CohortType cohortType);

    /**
     * 查找待计算的同期群
     */
    @Query("SELECT c FROM CohortEntity c WHERE c.status = 'PENDING' AND c.deletedAt IS NULL ORDER BY c.createdAt ASC")
    List<CohortEntity> findPending();

    /**
     * 根据名称查找
     */
    @Query("SELECT c FROM CohortEntity c WHERE c.gameId = :gameId AND c.name = :name AND c.deletedAt IS NULL")
    Optional<CohortEntity> findByGameIdAndName(@Param("gameId") String gameId, @Param("name") String name);

    /**
     * 搜索同期群
     */
    @Query("SELECT c FROM CohortEntity c WHERE c.gameId = :gameId AND (c.name LIKE %:query% OR c.displayName LIKE %:query% OR c.description LIKE %:query%) AND c.deletedAt IS NULL")
    List<CohortEntity> search(@Param("gameId") String gameId, @Param("query") String query);

    /**
     * 查找最近的同期群分析
     */
    @Query("SELECT c FROM CohortEntity c WHERE c.gameId = :gameId AND c.status = 'COMPLETED' AND c.calculatedAt IS NOT NULL ORDER BY c.calculatedAt DESC")
    List<CohortEntity> findRecent(@Param("gameId") String gameId);

}
