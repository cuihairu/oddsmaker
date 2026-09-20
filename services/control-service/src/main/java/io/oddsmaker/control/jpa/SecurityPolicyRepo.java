package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SecurityPolicyRepo extends JpaRepository<SecurityPolicyEntity, String> {

    /**
     * 根据类型查找策略
     */
    @Query("SELECT p FROM SecurityPolicyEntity p WHERE p.policyType = :type AND p.enabled = true AND p.deletedAt IS NULL ORDER BY p.priority DESC")
    List<SecurityPolicyEntity> findByType(@Param("type") SecurityPolicyEntity.PolicyType type);

    /**
     * 查找游戏的密码策略
     */
    @Query("SELECT p FROM SecurityPolicyEntity p WHERE p.policyType = 'PASSWORD' AND (p.policyScope = 'GLOBAL' OR (p.policyScope = 'GAME' AND p.gameId = :gameId)) AND p.enabled = true AND p.deletedAt IS NULL ORDER BY p.priority DESC")
    List<SecurityPolicyEntity> findPasswordPolicyForGame(@Param("gameId") String gameId);

    /**
     * 查找游戏的会话策略
     */
    @Query("SELECT p FROM SecurityPolicyEntity p WHERE p.policyType = 'SESSION' AND (p.policyScope = 'GLOBAL' OR (p.policyScope = 'GAME' AND p.gameId = :gameId)) AND p.enabled = true AND p.deletedAt IS NULL ORDER BY p.priority DESC")
    List<SecurityPolicyEntity> findSessionPolicyForGame(@Param("gameId") String gameId);

    /**
     * 查找游戏的MFA策略
     */
    @Query("SELECT p FROM SecurityPolicyEntity p WHERE p.policyType = 'MFA' AND (p.policyScope = 'GLOBAL' OR (p.policyScope = 'GAME' AND p.gameId = :gameId)) AND p.enabled = true AND p.deletedAt IS NULL ORDER BY p.priority DESC")
    List<SecurityPolicyEntity> findMFAPolicyForGame(@Param("gameId") String gameId);

    /**
     * 检查游戏是否需要MFA
     */
    @Query("SELECT CASE WHEN COUNT(p) > 0 THEN true ELSE false END FROM SecurityPolicyEntity p WHERE p.policyType = 'MFA' AND p.mfaRequired = true AND (p.policyScope = 'GLOBAL' OR (p.policyScope = 'GAME' AND p.gameId = :gameId)) AND p.enabled = true AND p.deletedAt IS NULL")
    boolean isMFARequiredForGame(@Param("gameId") String gameId);
}
