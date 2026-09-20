package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SystemConfigRepo extends JpaRepository<SystemConfigEntity, String> {

    /**
     * 根据配置键查找
     */
    @Query("SELECT sc FROM SystemConfigEntity sc WHERE sc.configKey = :configKey AND sc.deletedAt IS NULL")
    Optional<SystemConfigEntity> findByKey(@Param("configKey") String configKey);

    /**
     * 根据类型查找
     */
    @Query("SELECT sc FROM SystemConfigEntity sc WHERE sc.configType = :type AND sc.deletedAt IS NULL ORDER BY sc.category")
    List<SystemConfigEntity> findByType(@Param("type") SystemConfigEntity.ConfigType type);

    /**
     * 查找公开配置
     */
    @Query("SELECT sc FROM SystemConfigEntity sc WHERE sc.isPublic = true AND sc.deletedAt IS NULL")
    List<SystemConfigEntity> findPublic();

    /**
     * 搜索配置
     */
    @Query("SELECT sc FROM SystemConfigEntity sc WHERE sc.configKey LIKE :query OR sc.description LIKE :query AND sc.deletedAt IS NULL")
    List<SystemConfigEntity> search(@Param("query") String query);
}
