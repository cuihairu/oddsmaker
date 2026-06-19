package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 存储路由配置访问接口。
 */
@Repository
public interface StorageProfileRepo extends JpaRepository<StorageProfileEntity, String> {

    List<StorageProfileEntity> findByActiveTrueAndDeletedAtIsNullOrderByNameAsc();

    boolean existsByNameAndDeletedAtIsNull(String name);
}
