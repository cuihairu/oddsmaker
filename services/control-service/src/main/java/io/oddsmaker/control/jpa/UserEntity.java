package io.oddsmaker.control.jpa;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 用户实体
 * 存储用户信息和权限
 */
@Entity
@Table(name = "users")
public class UserEntity {

    @Id
    @Column(length = 64)
    public String id;

    @Column(nullable = false, unique = true, length = 100)
    public String username;

    /**
     * 本地登录口令（bcrypt）。迁移列 V0.2.0 就有，此前实体未映射、登录端点缺失，
     * 控制面前端 /api/auth/login 一直是死接口。不对外序列化。
     */
    @JsonIgnore
    @Column(name = "password_hash", length = 255)
    public String passwordHash;

    @Column(unique = true, length = 200)
    public String email;

    @Column(name = "display_name", length = 200)
    public String displayName;


    /**
     * 用户状态: active, inactive, locked, pending
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public UserStatus status = UserStatus.ACTIVE;

    /**
     * Keycloak用户ID
     */
    @Column(name = "keycloak_id", unique = true, length = 100)
    public String keycloakId;

    /**
     * 用户角色
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "role")
    public Set<UserRole> roles;

    /**
     * 用户权限范围
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_scopes", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "scope")
    public Set<String> scopes;

    /**
     * 最后登录时间
     */
    // 迁移中列名为 last_login（无 _at 后缀），validate 按列名对齐
    @Column(name = "last_login")
    public LocalDateTime lastLoginAt;

    /**
     * 最后登录IP
     */
    @Column(name = "last_login_ip", length = 45)
    public String lastLoginIp;

    /**
     * 登录次数
     */
    @Column(name = "login_count")
    public Long loginCount = 0L;

    /**
     * 语言
     */
    @Column(length = 10)
    public String language;

    /**
     * 是否启用双因素认证
     */
    @Column(name = "two_factor_enabled")
    public Boolean twoFactorEnabled = false;

    /**
     * 双因素认证密钥
     */
    @Column(name = "two_factor_secret", length = 100)
    public String twoFactorSecret;

    // UserDTO 映射字段
    @Column(length = 200)
    public String name;

    @Column(length = 200)
    public String company;

    @Column(length = 100)
    public String title;

    @Column(length = 50)
    public String phone;

    @Column(length = 50)
    public String timeZone;

    @Column(length = 10)
    public String locale;

    /**
     * 头像 URL（迁移列 avatar_url）。历史上与 avatar 字段重复映射同列，
     * 已合并到本字段（对外 UserDTO 契约字段名）。
     */
    @Column(name = "avatar_url", length = 500)
    public String avatarUrl;

    /**
     * 全局角色
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "global_role")
    public GlobalRole globalRole;

    @Column(name = "notification_email")
    public Boolean notificationEmail;

    @Column(name = "notification_sms")
    public Boolean notificationSms;

    @Column(name = "dashboard_theme", length = 20)
    public String dashboardTheme;

    @Column(name = "email_verified")
    public Boolean emailVerified;

    @Column(name = "login_attempts")
    public Integer loginAttempts;

    /**
     * 全局角色枚举
     */
    public enum GlobalRole {
        USER,
        OPERATOR,
        SUPER_ADMIN
    }

    // 时间戳
    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    public LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    public LocalDateTime deletedAt;

    /**
     * 用户状态枚举
     */
    public enum UserStatus {
        ACTIVE,      // 活跃
        INACTIVE,    // 未激活
        LOCKED,      // 锁定
        PENDING      // 待审核
    }

    /**
     * 用户角色枚举
     */
    public enum UserRole {
        SUPER_ADMIN,  // 超级管理员
        ADMIN,        // 管理员
        MANAGER,      // 经理
        ANALYST,      // 分析师
        DEVELOPER,    // 开发者
        VIEWER        // 查看者
    }

    // 业务方法
    public boolean isActive() {
        return status == UserStatus.ACTIVE && deletedAt == null;
    }

    public boolean isLocked() {
        return status == UserStatus.LOCKED;
    }

    public String getFullName() {
        return displayName != null ? displayName : username;
    }

    public void recordLogin(String ip) {
        this.lastLoginAt = LocalDateTime.now();
        this.lastLoginIp = ip;
        this.loginCount = (loginCount == null ? 0 : loginCount) + 1;
    }
}