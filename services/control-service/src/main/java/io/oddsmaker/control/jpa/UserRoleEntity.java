package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 用户角色关联实体 - 细粒度权限控制
 * 支持用户在公司内不同游戏、环境中拥有不同角色
 */
@Entity
@Table(name = "user_roles")
public class UserRoleEntity {

    @Id
    @Column(length = 32)
    public String id;

    @Column(name = "user_id", nullable = false, length = 32)
    public String userId;

    @Column(name = "game_id", length = 32)
    public String gameId; // 可为空，表示全局权限

    @Column(name = "environment_id", length = 32)
    public String environmentId; // 可为空，表示游戏级权限

    /**
     * 角色类型: operator, game_admin, analyst, viewer, developer, qa
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public RoleType role;

    /**
     * 权限范围: global, game, environment
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public PermissionScope scope;

    // 具体权限配置 (JSON格式)
    @Column(name = "permissions", columnDefinition = "TEXT")
    public String permissions; // {"read": true, "write": false, "admin": false}

    // 邀请信息
    @Column(name = "invited_by", length = 32)
    public String invitedBy; // 邀请人用户ID

    @Column(name = "invitation_accepted")
    public Boolean invitationAccepted = false;

    @Column(name = "invitation_token", length = 255)
    public String invitationToken;

    @Column(name = "invitation_expires")
    public LocalDateTime invitationExpires;

    // 时间戳
    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @Column(name = "expires_at")
    public LocalDateTime expiresAt; // 角色过期时间

    // 关联关系
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", insertable = false, updatable = false)
    public UserEntity user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "game_id", insertable = false, updatable = false)
    public GameEntity game;

    public enum RoleType {
        OPERATOR,     // 公司级运营管理员
        GAME_ADMIN,   // 游戏管理员：管理特定游戏
        ANALYST,      // 数据分析师：查看分析报告，创建图表
        VIEWER,       // 观察者：只读访问
        DEVELOPER,    // 开发者：技术配置权限
        QA,           // 测试：测试环境权限
        MARKETING,    // 市场：用户分析和A/B测试
        FINANCE       // 财务：收入和商业化数据
    }

    public enum PermissionScope {
        GLOBAL,       // 全局权限
        GAME,         // 游戏级权限
        ENVIRONMENT   // 环境级权限
    }

    // 业务方法
    public boolean isAdmin() {
        return role == RoleType.OPERATOR || role == RoleType.GAME_ADMIN;
    }

    public boolean canManageUsers() {
        return role == RoleType.OPERATOR || role == RoleType.GAME_ADMIN;
    }

    public boolean canViewAnalytics() {
        return role != RoleType.QA; // QA角色通常不需要分析权限
    }

    public boolean canEditSettings() {
        return role == RoleType.OPERATOR || role == RoleType.GAME_ADMIN || role == RoleType.DEVELOPER;
    }

    public boolean isExpired() {
        return expiresAt != null && expiresAt.isBefore(LocalDateTime.now());
    }

    public boolean isPendingInvitation() {
        return !invitationAccepted &&
               invitationExpires != null &&
               invitationExpires.isAfter(LocalDateTime.now());
    }
}
