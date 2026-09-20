package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 角色数据访问接口
 */
@Repository
public interface RoleRepo extends JpaRepository<RoleEntity, String> {

    /**
     * 根据名称查找角色
     */
    Optional<RoleEntity> findByName(String name);

    /**
     * 根据类型查找角色
     */
    List<RoleEntity> findByType(RoleEntity.RoleType type);

    /**
     * 查找启用的角色
     */
    List<RoleEntity> findByEnabledTrue();

}