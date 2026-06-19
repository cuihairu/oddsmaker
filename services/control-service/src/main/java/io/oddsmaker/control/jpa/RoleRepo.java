package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

import static io.oddsmaker.control.jpa.RoleEntity.RoleScope;

@Repository
public interface RoleRepo extends JpaRepository<RoleEntity, String> {

    /**
     * 根据代码查找角色
     */
    Optional<RoleEntity> findByCodeAndDeletedAtIsNull(String code);

    /**
     * 根据类型查找角色
     */
    List<RoleEntity> findByTypeAndDeletedAtIsNullOrderByLevelAsc(RoleEntity.RoleType type);

    /**
     * 查找所有活跃角色
     */
    @Query("SELECT r FROM RoleEntity r WHERE r.status = 'ACTIVE' AND r.deletedAt IS NULL ORDER BY r.level, r.code")
    List<RoleEntity> findActive();

    /**
     * 根据范围查找角色
     */
    @Query("SELECT r FROM RoleEntity r WHERE r.scope = :scope AND r.deletedAt IS NULL ORDER BY r.level, r.code")
    List<RoleEntity> findByScope(@Param("scope") RoleScope scope);

    /**
     * 查找默认角色
     */
    @Query("SELECT r FROM RoleEntity r WHERE r.isDefault = true AND r.status = 'ACTIVE' AND r.deletedAt IS NULL")
    List<RoleEntity> findDefaultRoles();

    /**
     * 查找系统角色
     */
    @Query("SELECT r FROM RoleEntity r WHERE r.isSystem = true AND r.deletedAt IS NULL ORDER BY r.level, r.code")
    List<RoleEntity> findSystemRoles();

    /**
     * 根据父角色查找子角色
     */
    @Query("SELECT r FROM RoleEntity r WHERE r.parentRoleId = :parentRoleId AND r.deletedAt IS NULL ORDER BY r.level, r.code")
    List<RoleEntity> findByParentRoleId(@Param("parentRoleId") String parentRoleId);

    /**
     * 检查角色代码是否存在
     */
    @Query("SELECT CASE WHEN COUNT(r) > 0 THEN true ELSE false END FROM RoleEntity r WHERE r.code = :code AND r.deletedAt IS NULL")
    boolean existsByCode(@Param("code") String code);

    /**
     * 增加角色用户计数
     */
    @Query("UPDATE RoleEntity r SET r.userCount = r.userCount + 1 WHERE r.id = :roleId")
    void incrementUserCount(@Param("roleId") String roleId);

    /**
     * 减少角色用户计数
     */
    @Query("UPDATE RoleEntity r SET r.userCount = GREATEST(0, r.userCount - 1) WHERE r.id = :roleId")
    void decrementUserCount(@Param("roleId") String roleId);
}
