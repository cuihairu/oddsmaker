package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 用户邀请实体 - 邀请用户加入游戏或环境。
 */
@Entity
@Table(name = "user_invitations")
public class UserInvitationEntity {

    @Id
    @Column(length = 32)
    public String id;

    // 邀请相关信息
    @Column(name = "email", nullable = false, length = 255)
    public String email; // 被邀请人邮箱

    @Column(name = "inviter_id", nullable = false, length = 32)
    public String inviterId; // 邀请人ID

    @Column(name = "token", nullable = false, unique = true, length = 255)
    public String token; // 邀请令牌

    // 权限范围
    @Column(name = "game_id", length = 32)
    public String gameId;

    @Column(name = "environment_id", length = 32)
    public String environmentId;

    // 预设角色
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    public UserRoleEntity.RoleType role;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false)
    public UserRoleEntity.PermissionScope scope;

    // 邀请状态
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public InvitationStatus status = InvitationStatus.PENDING;

    // 个性化消息
    @Column(name = "message", length = 500)
    public String message;

    @Column(name = "subject", length = 200)
    public String subject;

    // 时间控制
    @Column(name = "expires_at", nullable = false)
    public LocalDateTime expiresAt;

    @Column(name = "accepted_at")
    public LocalDateTime acceptedAt;

    @Column(name = "rejected_at")
    public LocalDateTime rejectedAt;

    // 接受邀请后创建的用户ID
    @Column(name = "created_user_id", length = 32)
    public String createdUserId;

    // 时间戳
    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    public LocalDateTime updatedAt;

    // 关联关系
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "inviter_id", insertable = false, updatable = false)
    public UserEntity inviter;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_user_id", insertable = false, updatable = false)
    public UserEntity user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "game_id", insertable = false, updatable = false)
    public GameEntity game;

    public enum InvitationStatus {
        PENDING,  // 待处理
        ACCEPTED, // 已接受
        REJECTED, // 已拒绝
        EXPIRED,  // 已过期
        CANCELED  // 已取消
    }

    // 业务方法
    public boolean isValid() {
        return status == InvitationStatus.PENDING &&
               expiresAt.isAfter(LocalDateTime.now());
    }

    public boolean isExpired() {
        return expiresAt.isBefore(LocalDateTime.now());
    }

    public void accept(String userId) {
        this.status = InvitationStatus.ACCEPTED;
        this.acceptedAt = LocalDateTime.now();
        this.createdUserId = userId;
    }

    public void reject() {
        this.status = InvitationStatus.REJECTED;
        this.rejectedAt = LocalDateTime.now();
    }

    public void cancel() {
        this.status = InvitationStatus.CANCELED;
    }

    public String getInvitationUrl(String baseUrl) {
        return String.format("%s/invitation/accept?token=%s", baseUrl, token);
    }

    public String getScopeDescription() {
        StringBuilder desc = new StringBuilder();

        if (game != null) {
            desc.append("游戏: ").append(game.name);
        }

        if (environmentId != null) {
            if (desc.length() > 0) desc.append(" > ");
            desc.append("环境: ").append(environmentId);
        }

        return desc.toString();
    }
}
