package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 身份关联实体
 * 记录不同标识符之间的关联关系，用于身份合并
 */
@Entity
@Table(name = "identity_links")
public class IdentityLinkEntity {

    @Id
    @Column(length = 32)
    public String id;

    @Column(name = "identity_id", nullable = false, length = 32)
    public String identityId;  // 主身份ID

    @Column(name = "linked_identity_type", nullable = false, length = 50)
    public String linkedIdentityType;  // 关联的标识类型：device_id, user_id, player_id, character_id

    @Column(name = "linked_id", nullable = false, length = 200)
    public String linkedId;  // 关联的ID值

    // 关联类型
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public LinkType linkType = LinkType.ASSOCIATED;

    // 关联强度
    @Column(name = "link_strength")
    public Double linkStrength = 1.0;  // 关联强度（0-1）

    // 时间信息
    @Column(name = "first_linked_at")
    public LocalDateTime firstLinkedAt;  // 首次关联时间

    @Column(name = "last_confirmed_at")
    public LocalDateTime lastConfirmedAt;  // 最后确认时间

    @Column(name = "last_seen_at")
    public LocalDateTime lastSeenAt;  // 最后使用时间

    // 验证状态
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public VerificationStatus verificationStatus = VerificationStatus.PENDING;

    @Column(name = "verified_at")
    public LocalDateTime verifiedAt;  // 验证时间

    @Column(name = "verification_method", length = 50)
    public String verificationMethod;  // 验证方法：login, signup, device_match等

    // 来源
    @Column(name = "link_source", length = 100)
    public String linkSource;  // 关联来源：user_login, device_binding等

    @Column(name = "source_event_id", length = 100)
    public String sourceEventId;  // 产生此关联的事件ID

    // 统计
    @Column(name = "usage_count")
    public Long usageCount = 0L;  // 使用次数

    // 状态
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public LinkStatus status = LinkStatus.ACTIVE;

    // 时间戳
    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    public LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    public LocalDateTime deletedAt;

    @Column(name = "expired_at")
    public LocalDateTime expiredAt;  // 过期时间

    // 关联关系
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "identity_id", insertable = false, updatable = false)
    public IdentityEntity identity;

    public enum LinkType {
        OWNED,          // 拥有关系：用户拥有设备
        ASSOCIATED,     // 关联关系：玩家关联角色
        MAPPED,         // 映射关系：设备映射到用户
        INFERRED,       // 推断关系：基于行为推断
        MERGED         // 已合并
    }

    public enum VerificationStatus {
        PENDING,        // 待验证
        CONFIRMED,      // 已确认
        REJECTED,       // 已拒绝
        EXPIRED         // 已过期
    }

    public enum LinkStatus {
        ACTIVE,         // 活跃
        INACTIVE,       // 非活跃
        REVOKED,        // 已撤销
        EXPIRED         // 已过期
    }

    // 业务方法
    public boolean isActive() {
        return status == LinkStatus.ACTIVE && deletedAt == null &&
               (expiredAt == null || expiredAt.isAfter(LocalDateTime.now()));
    }

    public boolean isConfirmed() {
        return verificationStatus == VerificationStatus.CONFIRMED;
    }

    public boolean isExpired() {
        return expiredAt != null && expiredAt.isBefore(LocalDateTime.now());
    }

    public boolean isStrongLink() {
        return linkStrength != null && linkStrength >= 0.8;
    }

    public boolean isOwnedRelation() {
        return linkType == LinkType.OWNED;
    }

    public void recordUsage() {
        usageCount = (usageCount != null ? usageCount : 0) + 1;
        lastSeenAt = LocalDateTime.now();
    }

    public void confirm(String method) {
        verificationStatus = VerificationStatus.CONFIRMED;
        verifiedAt = LocalDateTime.now();
        lastConfirmedAt = LocalDateTime.now();
        verificationMethod = method;
    }

    public void revoke() {
        status = LinkStatus.REVOKED;
        expiredAt = LocalDateTime.now();
    }

    public long getDaysSinceLastSeen() {
        if (lastSeenAt == null) return Long.MAX_VALUE;
        return java.time.temporal.ChronoUnit.DAYS.between(lastSeenAt, LocalDateTime.now());
    }
}
