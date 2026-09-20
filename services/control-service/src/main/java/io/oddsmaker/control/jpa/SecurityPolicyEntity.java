package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 安全策略实体
 * 管理密码和会话策略
 */
@Entity
@Table(name = "security_policies")
public class SecurityPolicyEntity {

    /**
     * 策略类型
     */
    public enum PolicyType {
        PASSWORD,       // 密码策略
        SESSION,        // 会话策略
        MFA,            // MFA策略
        ACCESS,         // 访问策略
        API             // API策略
    }

    /**
     * 策略范围
     */
    public enum PolicyScope {
        GLOBAL,         // 全局策略
        GAME,           // 游戏策略
        ENVIRONMENT,    // 环境策略
        USER            // 用户策略
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public String id;

    @Column(name = "policy_type", nullable = false)
    @Enumerated(EnumType.STRING)
    public PolicyType policyType;  // 策略类型

    @Column(name = "policy_scope", nullable = false)
    @Enumerated(EnumType.STRING)
    public PolicyScope policyScope = PolicyScope.GLOBAL;  // 策略范围

    @Column(name = "game_id")
    public String gameId;  // 游戏ID（游戏级别策略时使用）

    @Column(name = "environment_id")
    public String environmentId;  // 环境ID（环境级别策略时使用）

    @Column(name = "user_id", length = 64)
    public String userId;  // 用户ID（用户级别策略时使用）

    @Column(name = "policy_name", nullable = false, length = 100)
    public String policyName;  // 策略名称

    @Column(name = "description", length = 500)
    public String description;  // 描述

    @Column(name = "enabled", columnDefinition = "BOOLEAN")
    public Boolean enabled = true;  // 是否启用

    @Column(name = "priority", columnDefinition = "INTEGER")
    public Integer priority = 0;  // 优先级（数字越大优先级越高）

    // 密码策略配置
    @Column(name = "min_password_length", columnDefinition = "INTEGER")
    public Integer minPasswordLength = 8;  // 最小密码长度

    @Column(name = "max_password_length", columnDefinition = "INTEGER")
    public Integer maxPasswordLength = 128;  // 最大密码长度

    @Column(name = "require_uppercase", columnDefinition = "BOOLEAN")
    public Boolean requireUppercase = true;  // 需要大写字母

    @Column(name = "require_lowercase", columnDefinition = "BOOLEAN")
    public Boolean requireLowercase = true;  // 需要小写字母

    @Column(name = "require_numbers", columnDefinition = "BOOLEAN")
    public Boolean requireNumbers = true;  // 需要数字

    @Column(name = "require_special_chars", columnDefinition = "BOOLEAN")
    public Boolean requireSpecialChars = true;  // 需要特殊字符

    @Column(name = "forbidden_passwords", columnDefinition = "TEXT")
    public String forbiddenPasswords;  // JSON格式的禁止密码列表

    @Column(name = "password_history", columnDefinition = "INTEGER")
    public Integer passwordHistory = 5;  // 密码历史记录数量

    @Column(name = "password_expiry_days", columnDefinition = "INTEGER")
    public Integer passwordExpiryDays = 90;  // 密码过期天数

    @Column(name = "password_expiry_warning_days", columnDefinition = "INTEGER")
    public Integer passwordExpiryWarningDays = 7;  // 密码过期警告天数

    @Column(name = "allow_password_reuse", columnDefinition = "BOOLEAN")
    public Boolean allowPasswordReuse = false;  // 允许密码重用

    // 会话策略配置
    @Column(name = "session_timeout_minutes", columnDefinition = "INTEGER")
    public Integer sessionTimeoutMinutes = 30;  // 会话超时（分钟）

    @Column(name = "max_concurrent_sessions", columnDefinition = "INTEGER")
    public Integer maxConcurrentSessions = 5;  // 最大并发会话数

    @Column(name = "session_renewal_allowed", columnDefinition = "BOOLEAN")
    public Boolean sessionRenewalAllowed = true;  // 允许会话续期

    @Column(name = "max_renewal_times", columnDefinition = "INTEGER")
    public Integer maxRenewalTimes = 3;  // 最大续期次数

    @Column(name = "idle_timeout_minutes", columnDefinition = "INTEGER")
    public Integer idleTimeoutMinutes = 15;  // 空闲超时（分钟）

    @Column(name = "absolute_timeout_minutes", columnDefinition = "INTEGER")
    public Integer absoluteTimeoutMinutes = 480;  // 绝对超时（分钟，8小时）

    // MFA策略配置
    @Column(name = "mfa_required", columnDefinition = "BOOLEAN")
    public Boolean mfaRequired = false;  // 是否需要MFA

    @Column(name = "mfa_methods", columnDefinition = "TEXT")
    public String mfaMethods;  // JSON格式的允许MFA方法列表

    @Column(name = "mfa_trust_device_days", columnDefinition = "INTEGER")
    public Integer mfaTrustDeviceDays = 30;  // 信任设备天数

    // 访问策略配置
    @Column(name = "allowed_ip_ranges", columnDefinition = "TEXT")
    public String allowedIpRanges;  // JSON格式的允许IP范围列表

    @Column(name = "denied_ip_ranges", columnDefinition = "TEXT")
    public String deniedIpRanges;  // JSON格式的拒绝IP范围列表

    @Column(name = "allowed_countries", columnDefinition = "TEXT")
    public String allowedCountries;  // JSON格式的允许国家列表

    @Column(name = "blocked_countries", columnDefinition = "TEXT")
    public String blockedCountries;  // JSON格式的阻止国家列表

    @Column(name = "require_ip_whitelist", columnDefinition = "BOOLEAN")
    public Boolean requireIpWhitelist = false;  // 需要IP白名单

    // API策略配置
    @Column(name = "api_rate_limit", columnDefinition = "INTEGER")
    public Integer apiRateLimit = 100;  // API速率限制（每分钟）

    @Column(name = "api_burst_limit", columnDefinition = "INTEGER")
    public Integer apiBurstLimit = 200;  // API突发限制

    @Column(name = "require_https", columnDefinition = "BOOLEAN")
    public Boolean requireHttps = true;  // 需要HTTPS

    @Column(name = "api_key_required", columnDefinition = "BOOLEAN")
    public Boolean apiKeyRequired = false;  // 需要API密钥

    @Column(name = "created_by", nullable = false, length = 64)
    public String createdBy;  // 创建人

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @Column(name = "updated_at")
    public LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    public LocalDateTime deletedAt;

    // 辅助方法

    public boolean isPasswordPolicy() {
        return policyType == PolicyType.PASSWORD;
    }

    public boolean isSessionPolicy() {
        return policyType == PolicyType.SESSION;
    }

    public boolean isMFAPolicy() {
        return policyType == PolicyType.MFA;
    }

    public boolean isAccessPolicy() {
        return policyType == PolicyType.ACCESS;
    }

    public boolean isApiPolicy() {
        return policyType == PolicyType.API;
    }

    public boolean isGlobal() {
        return policyScope == PolicyScope.GLOBAL;
    }

    public boolean isGameLevel() {
        return policyScope == PolicyScope.GAME;
    }

    public boolean isEnabled() {
        return enabled != null && enabled && deletedAt == null;
    }

    public boolean isIpWhitelistRequired() {
        return requireIpWhitelist != null && requireIpWhitelist;
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (enabled == null) {
            enabled = true;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
