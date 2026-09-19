package io.oddsmaker.control.jpa;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Webhook配置实体
 * 管理风险告警和通知的Webhook端点配置
 */
@Entity
@Table(name = "webhook_configs")
public class WebhookConfigEntity {

    @Id
    @Column(length = 32)
    public String id;

    // 基本信息
    @Column(name = "game_id", nullable = false, length = 32)
    public String gameId;

    @Column(name = "environment_id", length = 32)
    public String environmentId;

    @Column(nullable = false, length = 100)
    public String name;  // Webhook名称

    @Column(name = "display_name", length = 200)
    public String displayName;  // 显示名称

    @Column(columnDefinition = "TEXT")
    public String description;  // 描述

    // Webhook端点配置
    @Column(name = "webhook_url", nullable = false, length = 500)
    public String webhookUrl;  // Webhook URL

    @Column(name = "http_method", length = 10)
    public String httpMethod = "POST";  // HTTP方法：POST, PUT, PATCH

    // 鉴权配置
    @Column(name = "auth_type", length = 20)
    public String authType;  // 鉴权类型：none, basic, bearer, api_key, hmac

    @Column(name = "auth_config", columnDefinition = "TEXT")
    @JsonIgnore  // 明文 secret 不经 API 回显；编辑语义为"留空=保留原值"（WebhookService.updateWebhookConfig）
    public String authConfig;  // 鉴权配置（JSON格式，如 {"token":"..."}）

    // 事件类型
    @Column(name = "event_types", columnDefinition = "TEXT")
    public String eventTypes;  // 订阅的事件类型（JSON数组）

    @Column(name = "risk_levels", columnDefinition = "TEXT")
    public String riskLevels;  // 触发的风险等级（JSON数组）

    // 请求配置
    @Column(name = "request_headers", columnDefinition = "TEXT")
    public String requestHeaders;  // 自定义请求头（JSON格式）

    @Column(name = "request_template", columnDefinition = "TEXT")
    public String requestTemplate;  // 请求模板（JSON格式）

    @Column(name = "timeout_seconds")
    public Integer timeoutSeconds = 30;  // 超时时间

    // 重试配置
    @Column(name = "retry_config", columnDefinition = "TEXT")
    public String retryConfig;  // 重试配置（JSON格式）

    @Column(name = "max_retries")
    public Integer maxRetries = 3;  // 最大重试次数

    @Column(name = "retry_backoff_ms")
    public Integer retryBackoffMs = 1000;  // 重试退避时间（毫秒）

    // 状态
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public WebhookStatus status = WebhookStatus.ACTIVE;  // 状态

    // 统计信息
    @Column(name = "total_sent")
    public Long totalSent = 0L;  // 总发送次数

    @Column(name = "total_success")
    public Long totalSuccess = 0L;  // 成功次数

    @Column(name = "total_failed")
    public Long totalFailed = 0L;  // 失败次数

    @Column(name = "last_sent_at")
    public LocalDateTime lastSentAt;  // 最后发送时间

    @Column(name = "last_success_at")
    public LocalDateTime lastSuccessAt;  // 最后成功时间

    @Column(name = "last_failure_at")
    public LocalDateTime lastFailureAt;  // 最后失败时间

    @Column(name = "last_error", columnDefinition = "TEXT")
    public String lastError;  // 最后错误信息

    // 版本控制
    @Column(name = "version", length = 20)
    public String version = "1.0";

    @Column(name = "created_by", length = 64)
    public String createdBy;  // 创建人

    @Column(name = "updated_by", length = 64)
    public String updatedBy;  // 更新人

    // 时间戳
    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    public LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    public LocalDateTime deletedAt;  // 软删除时间

    public enum WebhookStatus {
        ACTIVE,            // 激活
        INACTIVE,          // 停用
        PAUSED,            // 暂停
        FAILED             // 失败（连续失败多次后自动暂停）
    }

    public enum AuthType {
        NONE,              // 无鉴权
        BASIC,             // HTTP Basic
        BEARER,            // Bearer Token
        API_KEY,           // API Key
        HMAC,              // HMAC签名
        OAUTH2             // OAuth 2.0
    }

    // 业务方法
    public boolean isActive() {
        return status == WebhookStatus.ACTIVE;
    }

    public boolean shouldRetry() {
        return maxRetries != null && maxRetries > 0;
    }

    public double getSuccessRate() {
        long total = totalSent != null ? totalSent : 0;
        if (total == 0) return 0.0;
        long success = totalSuccess != null ? totalSuccess : 0;
        return (double) success / total;
    }

    public void recordSuccess() {
        totalSent = (totalSent != null ? totalSent : 0) + 1;
        totalSuccess = (totalSuccess != null ? totalSuccess : 0) + 1;
        lastSentAt = LocalDateTime.now();
        lastSuccessAt = LocalDateTime.now();
        status = WebhookStatus.ACTIVE;
    }

    public void recordFailure(String error) {
        totalSent = (totalSent != null ? totalSent : 0) + 1;
        totalFailed = (totalFailed != null ? totalFailed : 0) + 1;
        lastSentAt = LocalDateTime.now();
        lastFailureAt = LocalDateTime.now();
        lastError = error;

        // 检查是否需要标记为失败
        long consecutiveFailures = totalFailed - (totalSuccess != null ? totalSuccess : 0);
        if (consecutiveFailures >= 5) {
            status = WebhookStatus.FAILED;
        }
    }

    public boolean shouldSendForEvent(String eventType, String riskLevel) {
        if (!isActive()) return false;

        // 检查事件类型匹配
        if (eventTypes != null && !eventTypes.isEmpty()) {
            try {
                String[] types = eventTypes.split(",");
                boolean typeMatch = false;
                for (String type : types) {
                    if (type.trim().equalsIgnoreCase(eventType)) {
                        typeMatch = true;
                        break;
                    }
                }
                if (!typeMatch) return false;
            } catch (Exception e) {
                // 解析失败，默认发送
            }
        }

        // 检查风险等级匹配
        if (riskLevels != null && !riskLevels.isEmpty()) {
            try {
                String[] levels = riskLevels.split(",");
                boolean levelMatch = false;
                for (String level : levels) {
                    if (level.trim().equalsIgnoreCase(riskLevel)) {
                        levelMatch = true;
                        break;
                    }
                }
                if (!levelMatch) return false;
            } catch (Exception e) {
                // 解析失败，默认发送
            }
        }

        return true;
    }

    public String getWebhookDescription() {
        return String.format("[%s] %s -> %s (%s)",
            status.name().toLowerCase(),
            displayName != null ? displayName : name,
            webhookUrl,
            authType != null ? authType : "none"
        );
    }
}
