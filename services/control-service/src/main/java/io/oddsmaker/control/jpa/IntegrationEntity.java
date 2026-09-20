package io.oddsmaker.control.jpa;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 外部系统集成实体
 * 管理与外部系统的连接和集成配置
 */
@Entity
@Table(name = "integrations")
public class IntegrationEntity {

    /**
     * 集成类型
     */
    public enum IntegrationType {
        SLACK,           // Slack通知
        DISCORD,         // Discord通知
        EMAIL_SMTP,      // SMTP邮件
        EMAIL_SES,       // AWS SES邮件
        EMAIL_SENDGRID,  // SendGrid邮件
        PAYMENT_STRIPE,  // Stripe支付
        PAYMENT_PAYPAL,  // PayPal支付
        AUTH_OAUTH,      // OAuth认证
        AUTH_SAML,       // SAML认证
        WEBHOOK,         // 通用Webhook
        CUSTOM,          // 自定义集成
        ALERT_PAGERDUTY, // PagerDuty告警
        ALERT_DATADOG,   // Datadog监控
        ANALYTICS_GA     // Google Analytics
    }

    /**
     * 认证类型
     */
    public enum AuthType {
        NONE,           // 无需认证
        API_KEY,        // API密钥
        BEARER_TOKEN,   // Bearer令牌
        BASIC_AUTH,     // 基础认证
        OAUTH2,         // OAuth2
        HMAC,           // HMAC签名
        MUTUAL_TLS      // 双向TLS
    }

    /**
     * 集成状态
     */
    public enum IntegrationStatus {
        ACTIVE,         // 活跃
        INACTIVE,       // 未激活
        VERIFYING,      // 验证中
        FAILED,         // 连接失败
        DISABLED        // 已禁用
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public String id;

    @Column(name = "game_id", nullable = false)
    public String gameId;

    @Column(name = "integration_type", nullable = false)
    @Enumerated(EnumType.STRING)
    public IntegrationType integrationType;

    @Column(name = "name", nullable = false, length = 100)
    public String name;

    @Column(name = "description", length = 500)
    public String description;

    @Column(name = "auth_type")
    @Enumerated(EnumType.STRING)
    public AuthType authType;

    @Column(name = "endpoint_url", length = 500)
    public String endpointUrl;

    @Column(name = "api_key", length = 255)
    @JsonIgnore
    public String apiKey;

    @Column(name = "api_secret", columnDefinition = "TEXT")
    @JsonIgnore
    public String apiSecret;

    @Column(name = "bearer_token", length = 500)
    @JsonIgnore
    public String bearerToken;

    @Column(name = "username", length = 100)
    @JsonIgnore
    public String username;

    @Column(name = "password", length = 255)
    @JsonIgnore
    public String password;

    @Column(name = "config", columnDefinition = "TEXT")
    public String config;  // JSON格式的额外配置

    @Column(name = "headers", columnDefinition = "TEXT")
    public String headers;  // JSON格式的HTTP头

    @Column(name = "integration_status", nullable = false)
    @Enumerated(EnumType.STRING)
    public IntegrationStatus integrationStatus = IntegrationStatus.INACTIVE;

    @Column(name = "last_verified_at")
    public LocalDateTime lastVerifiedAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    public String lastError;

    @Column(name = "retry_count", columnDefinition = "INTEGER")
    public Integer retryCount = 0;

    @Column(name = "max_retries", columnDefinition = "INTEGER")
    public Integer maxRetries = 3;

    @Column(name = "timeout_seconds", columnDefinition = "INTEGER")
    public Integer timeoutSeconds = 30;

    @Column(name = "enabled", columnDefinition = "BOOLEAN")
    public Boolean enabled = true;

    @Column(name = "priority", columnDefinition = "INTEGER")
    public Integer priority = 0;  // 优先级，数字越大优先级越高

    @Column(name = "version", columnDefinition = "INTEGER")
    public Integer version = 1;

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @Column(name = "updated_at")
    public LocalDateTime updatedAt;

    @Column(name = "created_by", length = 64)
    public String createdBy;

    @Column(name = "deleted_at")
    public LocalDateTime deletedAt;

    // 辅助方法

    public boolean isActive() {
        return integrationStatus == IntegrationStatus.ACTIVE && enabled;
    }

    public boolean isVerifying() {
        return integrationStatus == IntegrationStatus.VERIFYING;
    }

    public boolean hasFailed() {
        return integrationStatus == IntegrationStatus.FAILED;
    }

    public void markAsActive() {
        this.integrationStatus = IntegrationStatus.ACTIVE;
        this.lastVerifiedAt = LocalDateTime.now();
        this.lastError = null;
        this.retryCount = 0;
    }

    public void markAsVerifying() {
        this.integrationStatus = IntegrationStatus.VERIFYING;
    }

    public void markAsFailed(String error) {
        this.integrationStatus = IntegrationStatus.FAILED;
        this.lastError = error;
        this.retryCount++;
    }

    public void incrementRetry() {
        this.retryCount++;
    }

    public boolean hasExceededMaxRetries() {
        return retryCount >= maxRetries;
    }

    public boolean shouldRetry() {
        return retryCount < maxRetries;
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (integrationStatus == null) {
            integrationStatus = IntegrationStatus.INACTIVE;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
