package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * API密钥实体 - 按游戏和环境隔离。
 */
@Entity
@Table(name = "api_keys")
public class ApiKeyEntity {

    @Id
    @Column(length = 64)
    public String apiKey;

    @Column(nullable = false, length = 64)
    public String secret;

    @Column(name = "game_id", nullable = false, length = 32)
    public String gameId;

    // 对齐 V0.9.0 扩宽后的 game_environments.id（varchar(100)）
    @Column(name = "environment_id", nullable = false, length = 100)
    public String environmentId;

    @Column(nullable = false, length = 100)
    public String name;

    @Column(length = 500)
    public String description;

    /**
     * API Key用途: client（游戏客户端，明文 apiKey）、server（游戏服，强制 HMAC）、admin（管理查询）
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "key_type")
    public ApiKeyType keyType = ApiKeyType.CLIENT;

    /**
     * API Key状态: active, inactive, revoked
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public ApiKeyStatus status = ApiKeyStatus.ACTIVE;

    // 限流配置
    public Integer rpm = 600; // 每分钟请求数
    public Integer ipRpm = 300; // 每IP每分钟请求数

    @Column(name = "daily_quota")
    public Long dailyQuota; // 每日配额

    @Column(name = "monthly_quota")
    public Long monthlyQuota; // 每月配额

    // Props白名单和PII策略
    @Column(name = "props_allowlist", columnDefinition = "TEXT")
    public String propsAllowlist; // comma-separated

    @Column(name = "pii_email", length = 10)
    public String piiEmail = "allow";  // allow|mask|drop

    @Column(name = "pii_phone", length = 10)
    public String piiPhone = "allow";  // allow|mask|drop

    @Column(name = "pii_ip", length = 10)
    public String piiIp = "allow";     // allow|coarse|drop

    @Column(name = "deny_keys", columnDefinition = "TEXT")
    public String denyKeys;  // comma-separated

    @Column(name = "mask_keys", columnDefinition = "TEXT")
    public String maskKeys;  // comma-separated

    // 安全配置
    @Column(name = "require_hmac")
    public Boolean requireHmac = false;

    @Column(name = "allowed_origins", columnDefinition = "TEXT")
    public String allowedOrigins; // CORS设置

    @Column(name = "ip_whitelist", columnDefinition = "TEXT")
    public String ipWhitelist;

    @Column(name = "user_agent_whitelist", columnDefinition = "TEXT")
    public String userAgentWhitelist;

    // 功能权限
    @Column(name = "can_write")
    public Boolean canWrite = true; // 是否可写入事件

    @Column(name = "can_read")
    public Boolean canRead = false; // 是否可查询数据

    @Column(name = "can_export")
    public Boolean canExport = false; // 是否可导出数据

    // 使用统计
    @Column(name = "total_requests")
    public Long totalRequests = 0L;

    @Column(name = "total_events")
    public Long totalEvents = 0L;

    @Column(name = "last_used_at")
    public LocalDateTime lastUsedAt;

    @Column(name = "last_used_ip", length = 45)
    public String lastUsedIp;

    // 过期控制
    @Column(name = "expires_at")
    public LocalDateTime expiresAt;

    @Column(name = "auto_rotate")
    public Boolean autoRotate = false; // 是否自动轮换

    @Column(name = "rotation_days")
    public Integer rotationDays = 90; // 轮换周期（天）

    // 创建信息
    @Column(name = "created_by", length = 32)
    public String createdBy; // 创建人用户ID

    // 时间戳
    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    public LocalDateTime updatedAt;

    @Column(name = "revoked_at")
    public LocalDateTime revokedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "game_id", insertable = false, updatable = false)
    public GameEntity game;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "environment_id", insertable = false, updatable = false)
    public GameEnvironmentEntity environment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", insertable = false, updatable = false)
    public UserEntity creator;

    public enum ApiKeyType {
        CLIENT,  // 客户端 SDK：仅 x-api-key，不可持有 secret
        SERVER,  // Server SDK：强制 HMAC 签名
        ADMIN    // 管理/查询：只读，不可写入事件
    }

    public enum ApiKeyStatus {
        ACTIVE,   // 活跃
        INACTIVE, // 非活跃
        REVOKED   // 已吊销
    }

    // 业务方法
    public boolean isActive() {
        return status == ApiKeyStatus.ACTIVE &&
               (expiresAt == null || expiresAt.isAfter(LocalDateTime.now()));
    }

    public boolean isExpired() {
        return expiresAt != null && expiresAt.isBefore(LocalDateTime.now());
    }

    public void revoke() {
        this.status = ApiKeyStatus.REVOKED;
        this.revokedAt = LocalDateTime.now();
    }

}
