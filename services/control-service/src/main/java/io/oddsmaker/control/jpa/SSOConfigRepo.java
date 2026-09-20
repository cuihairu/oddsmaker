package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SSOConfigRepo extends JpaRepository<SSOConfigEntity, String> {

    /**
     * 查找活跃的SSO配置
     */
    @Query("SELECT s FROM SSOConfigEntity s WHERE s.ssoStatus = 'ACTIVE' AND s.deletedAt IS NULL ORDER BY s.isDefault DESC, s.createdAt DESC")
    List<SSOConfigEntity> findActive();

}
