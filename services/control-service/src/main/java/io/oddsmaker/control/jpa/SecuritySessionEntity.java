package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 安全会话实体
 * 管理用户活跃会话
 */
@Entity
@Table(name = "security_sessions")
public class SecuritySessionEntity {

    /**
     * 会话状态
     */
    public enum SessionStatus {
        ACTIVE,         // 活跃
        EXPIRED,        // 已过期
        REVOKED,        // 已撤销
        TERMINATED      // 已终止
    }

    /**
     * 认证方法
     */
    public enum AuthMethod {
        PASSWORD,       // 密码
        MFA,            // 多因素认证
        SSO,            // 单点登录
        API_KEY,        // API密钥
        OAUTH           // OAuth
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public String id;

    @Column(name = "user_id", nullable = false, length = 64)
    public String userId;  // 用户ID

    @Column(name = "session_token", nullable = false, length = 255)
    public String sessionToken;  // 会话令牌

    @Column(name = "refresh_token", length = 255)
    public String refreshToken;  // 刷新令牌

    @Column(name = "session_status", nullable = false)
    @Enumerated(EnumType.STRING)
    public SessionStatus sessionStatus = SessionStatus.ACTIVE;

    @Column(name = "auth_method", nullable = false)
    @Enumerated(EnumType.STRING)
    public AuthMethod authMethod = AuthMethod.PASSWORD;

    @Column(name = "mfa_verified", columnDefinition = "BOOLEAN")
    public Boolean mfaVerified = false;  // MFA是否已验证

    @Column(name = "mfa_method")
    @Enumerated(EnumType.STRING)
    public MFAConfigEntity.MFAMethod mfaMethod;  // 使用的MFA方法

    @Column(name = "sso_provider", length = 100)
    public String ssoProvider;  // SSO提供商

    @Column(name = "sso_config_id")
    public String ssoConfigId;  // SSO配置ID

    @Column(name = "ip_address", length = 50)
    public String ipAddress;  // IP地址

    @Column(name = "user_agent", length = 500)
    public String userAgent;  // 用户代理

    @Column(name = "device_fingerprint", length = 100)
    public String deviceFingerprint;  // 设备指纹

    @Column(name = "device_type", length = 50)
    public String deviceType;  // 设备类型（mobile, desktop, tablet）

    @Column(name = "browser", length = 100)
    public String browser;  // 浏览器

    @Column(name = "os", length = 100)
    public String os;  // 操作系统

    @Column(name = "location_country", length = 100)
    public String locationCountry;  // 国家

    @Column(name = "location_city", length = 100)
    public String locationCity;  // 城市

    @Column(name = "login_at", nullable = false)
    public LocalDateTime loginAt;  // 登录时间

    @Column(name = "last_activity_at", nullable = false)
    public LocalDateTime lastActivityAt;  // 最后活动时间

    @Column(name = "expires_at", nullable = false)
    public LocalDateTime expiresAt;  // 过期时间

    @Column(name = "max_renewal_times", columnDefinition = "INTEGER")
    public Integer maxRenewalTimes = 0;  // 最大续期次数

    @Column(name = "renewal_count", columnDefinition = "INTEGER")
    public Integer renewalCount = 0;  // 续期次数

    @Column(name = "concurrent_session_id", length = 32)
    public String concurrentSessionId;  // 并发会话ID（同一用户的同一设备）

    @Column(name = "is_current", columnDefinition = "BOOLEAN")
    public Boolean isCurrent = false;  // 是否为当前会话

    @Column(name = "terminated_by", length = 64)
    public String terminatedBy;  // 终止人

    @Column(name = "terminated_at")
    public LocalDateTime terminatedAt;  // 终止时间

    @Column(name = "termination_reason", length = 200)
    public String terminationReason;  // 终止原因

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @Column(name = "updated_at")
    public LocalDateTime updatedAt;

    // 辅助方法

    public boolean isActive() {
        return sessionStatus == SessionStatus.ACTIVE &&
               (expiresAt == null || LocalDateTime.now().isBefore(expiresAt));
    }

    public boolean isExpired() {
        return sessionStatus == SessionStatus.EXPIRED ||
               (expiresAt != null && LocalDateTime.now().isAfter(expiresAt));
    }

    public boolean isRevoked() {
        return sessionStatus == SessionStatus.REVOKED;
    }

    public boolean isTerminated() {
        return sessionStatus == SessionStatus.TERMINATED;
    }

    public boolean needsMFA() {
        return !mfaVerified;
    }

    public boolean canRenew() {
        return maxRenewalTimes == null || renewalCount < maxRenewalTimes;
    }

    public void renew(Integer minutes) {
        if (canRenew()) {
            this.expiresAt = this.expiresAt != null ? this.expiresAt.plusMinutes(minutes) : LocalDateTime.now().plusMinutes(minutes);
            this.renewalCount++;
            this.lastActivityAt = LocalDateTime.now();
        }
    }

    public void updateActivity() {
        this.lastActivityAt = LocalDateTime.now();
    }

    public void revoke(String reason) {
        this.sessionStatus = SessionStatus.REVOKED;
        this.terminationReason = reason;
        this.terminatedAt = LocalDateTime.now();
    }

    public void terminate(String terminatedBy, String reason) {
        this.sessionStatus = SessionStatus.TERMINATED;
        this.terminatedBy = terminatedBy;
        this.terminationReason = reason;
        this.terminatedAt = LocalDateTime.now();
    }

    public long getSessionDurationMinutes() {
        LocalDateTime end = terminatedAt != null ? terminatedAt : LocalDateTime.now();
        return java.time.Duration.between(loginAt, end).toMinutes();
    }

    public boolean isFromNewDevice() {
        // 检查是否为新设备
        return deviceFingerprint != null;  // 实际实现需要与历史记录比较
    }

    public boolean isSuspiciousLocation() {
        // 检查是否为可疑位置
        return false;  // 实际实现需要位置比较
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (loginAt == null) {
            loginAt = LocalDateTime.now();
        }
        if (lastActivityAt == null) {
            lastActivityAt = LocalDateTime.now();
        }
        if (sessionStatus == null) {
            sessionStatus = SessionStatus.ACTIVE;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
