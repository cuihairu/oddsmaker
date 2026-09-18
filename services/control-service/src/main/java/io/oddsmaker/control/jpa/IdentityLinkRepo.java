package io.oddsmaker.control.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface IdentityLinkRepo extends JpaRepository<IdentityLinkEntity, String> {

    /**
     * 查找身份的所有关联
     */
    @Query("SELECT il FROM IdentityLinkEntity il WHERE il.identityId = :identityId AND il.deletedAt IS NULL ORDER BY il.createdAt DESC")
    List<IdentityLinkEntity> findByIdentityId(@Param("identityId") String identityId);

    /**
     * 根据类型和ID查找关联
     */
    @Query("SELECT il FROM IdentityLinkEntity il WHERE il.linkedIdentityType = :type AND il.linkedId = :id AND il.status = 'ACTIVE' AND il.deletedAt IS NULL")
    List<IdentityLinkEntity> findByTypeAndId(@Param("type") String type, @Param("id") String id);

    /**
     * 按唯一三元组查活跃 link（供 IdentityConsumer 幂等 upsert：命中则更新，未命中则插入）
     */
    @Query("SELECT il FROM IdentityLinkEntity il WHERE il.identityId = :identityId AND il.linkedIdentityType = :type AND il.linkedId = :linkedId AND il.status = 'ACTIVE' AND il.deletedAt IS NULL")
    Optional<IdentityLinkEntity> findActiveByIdentityIdAndTypeAndLinkedId(@Param("identityId") String identityId, @Param("type") String type, @Param("linkedId") String linkedId);

    /**
     * 查找已确认的关联
     */
    @Query("SELECT il FROM IdentityLinkEntity il WHERE il.identityId = :identityId AND il.verificationStatus = 'CONFIRMED' AND il.status = 'ACTIVE' AND il.deletedAt IS NULL")
    List<IdentityLinkEntity> findConfirmedByIdentityId(@Param("identityId") String identityId);

    /**
     * 查找强关联
     */
    @Query("SELECT il FROM IdentityLinkEntity il WHERE il.identityId = :identityId AND il.linkStrength >= 0.8 AND il.status = 'ACTIVE' AND il.deletedAt IS NULL")
    List<IdentityLinkEntity> findStrongLinksByIdentityId(@Param("identityId") String identityId);

    /**
     * 查找已过期的关联
     */
    @Query("SELECT il FROM IdentityLinkEntity il WHERE il.expiredAt < :now AND il.status = 'ACTIVE' AND il.deletedAt IS NULL")
    List<IdentityLinkEntity> findExpired(@Param("now") LocalDateTime now);

    /**
     * 查找待验证的关联
     */
    @Query("SELECT il FROM IdentityLinkEntity il WHERE il.verificationStatus = 'PENDING' AND il.status = 'ACTIVE' AND il.deletedAt IS NULL AND il.createdAt < :before ORDER BY il.createdAt ASC")
    List<IdentityLinkEntity> findPendingVerification(@Param("before") LocalDateTime before);

    /**
     * 统计身份的活跃关联数
     */
    @Query("SELECT COUNT(il) FROM IdentityLinkEntity il WHERE il.identityId = :identityId AND il.status = 'ACTIVE' AND il.deletedAt IS NULL")
    long countActiveByIdentityId(@Param("identityId") String identityId);

    /**
     * 根据来源查找关联
     */
    @Query("SELECT il FROM IdentityLinkEntity il WHERE il.linkSource = :source AND il.status = 'ACTIVE' AND il.deletedAt IS NULL ORDER BY il.createdAt DESC")
    List<IdentityLinkEntity> findByLinkSource(@Param("source") String source);

    // ========== 玩家数据删除（GDPR erasure）：AnyStatus 反查 + 物理删除 ==========

    /** 根据类型和ID查找关联（AnyStatus：非活跃 link 仍暴露标识关联，须纳入展开） */
    @Query("SELECT il FROM IdentityLinkEntity il WHERE il.linkedIdentityType = :type AND il.linkedId = :id")
    List<IdentityLinkEntity> findByTypeAndIdAnyStatus(@Param("type") String type, @Param("id") String id);

    /** 查找身份的所有关联（AnyStatus） */
    @Query("SELECT il FROM IdentityLinkEntity il WHERE il.identityId = :identityId")
    List<IdentityLinkEntity> findByIdentityIdAnyStatus(@Param("identityId") String identityId);

    /** 物理删除身份的全部关联（切断删除后再关联） */
    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM IdentityLinkEntity il WHERE il.identityId IN :identityIds")
    int deleteByIdentityIdIn(@Param("identityIds") List<String> identityIds);
}
