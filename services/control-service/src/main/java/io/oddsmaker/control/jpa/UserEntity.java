package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 用户实体 - 平台用户管理
 * 支持单公司、多游戏、多环境场景下的细粒度权限控制
 */
@Entity
@Table(name = "users")
public class UserEntity {

    @Id
    @Column(length = 32)
    public String id;

    @Column(nullable = false, unique = true, length = 255)
    public String email;

    @Column(length = 100)
    public String name;

    @Column(name = "display_name", length = 100)
    public String displayName;

    @Column(name = "avatar_url", length = 500)
    public String avatarUrl;

    // 认证信息
    @Column(name = "password_hash", length = 255)
    public String passwordHash; // BCrypt哈希

    @Column(name = "email_verified")
    public Boolean emailVerified = false;

    @Column(name = "email_verification_token", length = 255)
    public String emailVerificationToken;

    @Column(name = "password_reset_token", length = 255)
    public String passwordResetToken;

    @Column(name = "password_reset_expires")
    public LocalDateTime passwordResetExpires;

    /**
     * 全局角色: super_admin, operator, user
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "global_role", nullable = false)
    public GlobalRole globalRole = GlobalRole.USER;

    /**
     * 用户状态: active, inactive, suspended, deleted
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public UserStatus status = UserStatus.ACTIVE;

    // 个人信息
    @Column(length = 100)
    public String company;

    @Column(length = 50)
    public String title; // 职位

    @Column(length = 20)
    public String phone;

    @Column(name = "time_zone", length = 50)
    public String timeZone = "UTC";

    @Column(length = 10)
    public String locale = "en-US";

    // 偏好设置
    @Column(name = "notification_email")
    public Boolean notificationEmail = true;

    @Column(name = "notification_sms")
    public Boolean notificationSms = false;

    @Column(name = "dashboard_theme", length = 20)
    public String dashboardTheme = "light"; // light, dark

    // 安全设置
    @Column(name = "two_factor_enabled")
    public Boolean twoFactorEnabled = false;

    @Column(name = "two_factor_secret", length = 32)
    public String twoFactorSecret;

    @Column(name = "login_attempts")
    public Integer loginAttempts = 0;

    @Column(name = "locked_until")
    public LocalDateTime lockedUntil;

    @Column(name = "last_login")
    public LocalDateTime lastLogin;

    @Column(name = "last_login_ip", length = 45)
    public String lastLoginIp;

    // 时间戳
    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    public LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    public LocalDateTime deletedAt;

    @OneToMany(mappedBy = "user", fetch = FetchType.LAZY)
    public List<UserRoleEntity> roles;

    @OneToMany(mappedBy = "user", fetch = FetchType.LAZY)
    public List<UserInvitationEntity> invitations;

    public enum GlobalRole {
        SUPER_ADMIN, // 超级管理员
        OPERATOR,    // 公司级运营管理员
        USER         // 普通用户
    }

    public enum UserStatus {
        ACTIVE,    // 活跃
        INACTIVE,  // 非活跃
        SUSPENDED, // 暂停
        DELETED    // 已删除
    }

    // 业务方法
    public boolean isSuperAdmin() {
        return globalRole == GlobalRole.SUPER_ADMIN;
    }

    public boolean isOperator() {
        return globalRole == GlobalRole.OPERATOR || isSuperAdmin();
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE && deletedAt == null;
    }

    public boolean isLocked() {
        return lockedUntil != null && lockedUntil.isAfter(LocalDateTime.now());
    }

    public boolean isEmailVerified() {
        return emailVerified != null && emailVerified;
    }

    public String getDisplayName() {
        if (displayName != null && !displayName.trim().isEmpty()) {
            return displayName;
        }
        if (name != null && !name.trim().isEmpty()) {
            return name;
        }
        return email;
    }

    public void incrementLoginAttempts() {
        loginAttempts = (loginAttempts == null ? 0 : loginAttempts) + 1;
        if (loginAttempts >= 5) {
            // 锁定30分钟
            lockedUntil = LocalDateTime.now().plusMinutes(30);
        }
    }

    public void resetLoginAttempts() {
        loginAttempts = 0;
        lockedUntil = null;
    }
}
