package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PermissionRepo extends JpaRepository<PermissionEntity, String> {

    /**
     * 根据代码查找权限
     */
    Optional<PermissionEntity> findByCodeAndDeletedAtIsNull(String code);

    /**
     * 根据资源类型查找权限
     */
    List<PermissionEntity> findByResourceAndDeletedAtIsNullOrderByCodeAsc(PermissionEntity.Resource resource);

    /**
     * 根据资源和操作查找权限
     */
    @Query("SELECT p FROM PermissionEntity p WHERE p.resource = :resource AND p.operation = :operation AND p.deletedAt IS NULL")
    Optional<PermissionEntity> findByResourceAndOperation(@Param("resource") PermissionEntity.Resource resource, @Param("operation") PermissionEntity.Operation operation);

    /**
     * 查找所有活跃权限
     */
    @Query("SELECT p FROM PermissionEntity p WHERE p.status = 'ACTIVE' AND p.deletedAt IS NULL ORDER BY p.category, p.displayOrder, p.code")
    List<PermissionEntity> findActive();

    /**
     * 根据分类查找权限
     */
    @Query("SELECT p FROM PermissionEntity p WHERE p.category = :category AND p.deletedAt IS NULL ORDER BY p.displayOrder")
    List<PermissionEntity> findByCategory(@Param("category") String category);

    /**
     * 查找系统权限
     */
    @Query("SELECT p FROM PermissionEntity p WHERE p.isSystem = true AND p.deletedAt IS NULL ORDER BY p.code")
    List<PermissionEntity> findSystemPermissions();

    /**
     * 检查权限代码是否存在
     */
    @Query("SELECT CASE WHEN COUNT(p) > 0 THEN true ELSE false END FROM PermissionEntity p WHERE p.code = :code AND p.deletedAt IS NULL")
    boolean existsByCode(@Param("code") String code);
}
