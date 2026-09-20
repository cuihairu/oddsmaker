package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 权限数据访问接口
 */
@Repository
public interface PermissionRepo extends JpaRepository<PermissionEntity, String> {

    /**
     * 根据名称查找权限
     */
    Optional<PermissionEntity> findByName(String name);

    /**
     * 根据类型查找权限
     */
    List<PermissionEntity> findByType(PermissionEntity.PermissionType type);

    /**
     * 根据资源类型查找权限
     */
    List<PermissionEntity> findByResourceType(String resourceType);

    /**
     * 根据操作类型查找权限
     */
    List<PermissionEntity> findByAction(PermissionEntity.PermissionAction action);

    /**
     * 查找启用的权限
     */
    List<PermissionEntity> findByEnabledTrue();

    /**
     * 根据资源类型和操作查找权限
     */
    Optional<PermissionEntity> findByResourceTypeAndAction(
            String resourceType, PermissionEntity.PermissionAction action);

}