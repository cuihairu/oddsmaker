package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface MFAConfigRepo extends JpaRepository<MFAConfigEntity, String> {

    /**
     * 查找用户的MFA配置
     */
    @Query("SELECT m FROM MFAConfigEntity m WHERE m.userId = :userId AND m.deletedAt IS NULL ORDER BY m.isPrimary DESC, m.createdAt DESC")
    List<MFAConfigEntity> findByUserId(@Param("userId") String userId);

    /**
     * 查找用户启用的MFA配置
     */
    @Query("SELECT m FROM MFAConfigEntity m WHERE m.userId = :userId AND m.mfaStatus = 'ENABLED' AND m.deletedAt IS NULL")
    List<MFAConfigEntity> findEnabledByUserId(@Param("userId") String userId);

    /**
     * 根据类型查找用户的MFA配置
     */
    @Query("SELECT m FROM MFAConfigEntity m WHERE m.userId = :userId AND m.mfaMethod = :method AND m.deletedAt IS NULL")
    Optional<MFAConfigEntity> findByUserIdAndMethod(@Param("userId") String userId, @Param("method") MFAConfigEntity.MFAMethod method);

    /**
     * 查找待验证的MFA配置
     */
    @Query("SELECT m FROM MFAConfigEntity m WHERE m.mfaStatus = 'PENDING' AND m.deletedAt IS NULL")
    List<MFAConfigEntity> findPending();

    /**
     * 检查用户是否启用了MFA
     */
    @Query("SELECT CASE WHEN COUNT(m) > 0 THEN true ELSE false END FROM MFAConfigEntity m WHERE m.userId = :userId AND m.mfaStatus = 'ENABLED' AND m.deletedAt IS NULL")
    boolean isMFAEnabledForUser(@Param("userId") String userId);
}
