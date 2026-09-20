package io.oddsmaker.control.jpa;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Webhook日志实体
 * 记录每次Webhook发送的详细日志
 */
@Entity
@Table(name = "webhook_logs")
public class WebhookLogEntity {

    @Id
    @Column(length = 32)
    public String id;

    // 关联信息
    @Column(name = "webhook_config_id", nullable = false, length = 32)
    public String webhookConfigId;

    @Column(name = "game_id", nullable = false, length = 32)
    public String gameId;

    @Column(name = "risk_case_id", length = 32)
    public String riskCaseId;  // 关联的风险案例ID

    @Column(name = "event_type", length = 50)
    public String eventType;  // 事件类型

    @Column(name = "event_id", length = 100)
    public String eventId;  // 事件ID

    // 请求信息
    @Column(name = "request_url", length = 500)
    public String requestUrl;  // 请求URL

    @Column(name = "request_method", length = 10)
    public String requestMethod;  // HTTP方法

    @Column(name = "request_headers", columnDefinition = "TEXT")
    public String requestHeaders;  // 请求头（JSON格式）

    @Column(name = "request_body", columnDefinition = "TEXT")
    public String requestBody;  // 请求体

    // 响应信息
    @Column(name = "response_status")
    public Integer responseStatus;  // HTTP状态码

    @Column(name = "response_headers", columnDefinition = "TEXT")
    public String responseHeaders;  // 响应头

    @Column(name = "response_body", columnDefinition = "TEXT")
    public String responseBody;  // 响应体

    @Column(name = "response_time_ms")
    public Long responseTimeMs;  // 响应时间（毫秒）

    // 状态
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public DeliveryStatus deliveryStatus = DeliveryStatus.PENDING;

    @Column(name = "retry_count")
    public Integer retryCount = 0;  // 重试次数

    @Column(name = "attempt_number")
    public Integer attemptNumber = 1;  // 尝试次数

    // 错误信息
    @Column(name = "error_message", columnDefinition = "TEXT")
    public String errorMessage;  // 错误信息

    @Column(name = "error_type", length = 100)
    public String errorType;  // 错误类型

    // 时间信息
    @Column(name = "sent_at")
    public LocalDateTime sentAt;  // 发送时间

    @Column(name = "delivered_at")
    public LocalDateTime deliveredAt;  // 送达时间

    @Column(name = "next_retry_at")
    public LocalDateTime nextRetryAt;  // 下次重试时间

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    // 关联关系
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "webhook_config_id", insertable = false, updatable = false)
    @JsonIgnore  // LAZY 代理在事务外（open-in-view: false）序列化必 500，日志响应只输出日志自身字段
    public WebhookConfigEntity webhookConfig;

    public enum DeliveryStatus {
        PENDING,           // 待发送
        SENDING,           // 发送中
        SUCCESS,           // 成功
        FAILED,            // 失败
        RETRYING,          // 重试中
        TIMEOUT,           // 超时
        CANCELLED          // 已取消
    }

    // 业务方法
    public boolean isSuccess() {
        return deliveryStatus == DeliveryStatus.SUCCESS;
    }

    public boolean isFailed() {
        return deliveryStatus == DeliveryStatus.FAILED ||
               deliveryStatus == DeliveryStatus.TIMEOUT;
    }

    public boolean shouldRetry() {
        return deliveryStatus == DeliveryStatus.FAILED ||
               deliveryStatus == DeliveryStatus.TIMEOUT;
    }

    public boolean isPending() {
        return deliveryStatus == DeliveryStatus.PENDING ||
               deliveryStatus == DeliveryStatus.RETRYING;
    }

    public void markAsSuccess(Integer responseStatus, String responseBody, Long responseTimeMs) {
        this.deliveryStatus = DeliveryStatus.SUCCESS;
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
        this.responseTimeMs = responseTimeMs;
        this.deliveredAt = LocalDateTime.now();
    }

    public void markAsFailed(String errorMessage, String errorType) {
        this.deliveryStatus = DeliveryStatus.FAILED;
        this.errorMessage = errorMessage;
        this.errorType = errorType;
    }

    public void scheduleRetry(LocalDateTime nextRetryAt) {
        this.deliveryStatus = DeliveryStatus.RETRYING;
        this.nextRetryAt = nextRetryAt;
        this.retryCount = (this.retryCount == null ? 0 : this.retryCount) + 1;
    }

    public String getLogSummary() {
        return String.format("[%s] %s %s -> %d (%dms)",
            deliveryStatus.name(),
            requestMethod,
            requestUrl,
            responseStatus != null ? responseStatus : 0,
            responseTimeMs != null ? responseTimeMs : 0
        );
    }
}
