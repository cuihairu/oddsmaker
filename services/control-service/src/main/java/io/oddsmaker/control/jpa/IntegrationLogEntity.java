package io.oddsmaker.control.jpa;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 集成日志实体
 * 记录外部系统集成的调用日志
 */
@Entity
@Table(name = "integration_logs")
public class IntegrationLogEntity {

    /**
     * 日志级别
     */
    public enum LogLevel {
        DEBUG, INFO, WARN, ERROR
    }

    /**
     * 调用状态
     */
    public enum CallStatus {
        SUCCESS,    // 成功
        FAILED,     // 失败
        RETRYING,   // 重试中
        TIMEOUT,    // 超时
        CANCELLED   // 已取消
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public String id;

    @Column(name = "integration_id", nullable = false)
    public String integrationId;

    @Column(name = "game_id", nullable = false)
    public String gameId;

    @Column(name = "integration_type", nullable = false, length = 50)
    public String integrationType;

    @Column(name = "event_type", nullable = false, length = 100)
    public String eventType;  // 触发事件类型：risk_case, block, alert, etc.

    @Column(name = "call_status", nullable = false)
    @Enumerated(EnumType.STRING)
    public CallStatus callStatus;

    @Column(name = "log_level", nullable = false)
    @Enumerated(EnumType.STRING)
    public LogLevel logLevel = LogLevel.INFO;

    @Column(name = "http_method", length = 10)
    public String httpMethod;  // GET, POST, PUT, DELETE

    @Column(name = "request_url", length = 1000)
    public String requestUrl;

    @Column(name = "request_headers", columnDefinition = "TEXT")
    public String requestHeaders;  // JSON格式

    @Column(name = "request_body", columnDefinition = "TEXT")
    @JsonIgnore
    public String requestBody;  // JSON格式

    @Column(name = "response_status", columnDefinition = "INTEGER")
    public Integer responseStatus;  // HTTP状态码

    @Column(name = "response_headers", columnDefinition = "TEXT")
    public String responseHeaders;  // JSON格式

    @Column(name = "response_body", columnDefinition = "TEXT")
    public String responseBody;

    @Column(name = "error_message", columnDefinition = "TEXT")
    public String errorMessage;

    @Column(name = "error_stack_trace", columnDefinition = "TEXT")
    public String errorStackTrace;

    @Column(name = "retry_attempt", columnDefinition = "INTEGER")
    public Integer retryAttempt = 0;

    @Column(name = "duration_ms", columnDefinition = "BIGINT")
    public Long durationMs;  // 请求耗时（毫秒）

    @Column(name = "request_size_bytes", columnDefinition = "BIGINT")
    public Long requestSizeBytes;

    @Column(name = "response_size_bytes", columnDefinition = "BIGINT")
    public Long responseSizeBytes;

    @Column(name = "correlation_id", length = 100)
    public String correlationId;  // 关联ID，用于追踪

    @Column(name = "triggered_by", length = 100)
    public String triggeredBy;  // 触发者：user_id, system, etc.

    @Column(name = "metadata", columnDefinition = "TEXT")
    public String metadata;  // JSON格式的额外元数据

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @Column(name = "expires_at")
    public LocalDateTime expiresAt;  // 日志过期时间，用于自动清理

    // 辅助方法

    public boolean isSuccess() {
        return callStatus == CallStatus.SUCCESS;
    }

    public boolean isFailed() {
        return callStatus == CallStatus.FAILED || callStatus == CallStatus.TIMEOUT;
    }

    public boolean isRetrying() {
        return callStatus == CallStatus.RETRYING;
    }

    public void markAsSuccess() {
        this.callStatus = CallStatus.SUCCESS;
    }

    public void markAsFailed(String error) {
        this.callStatus = CallStatus.FAILED;
        this.errorMessage = error;
    }

    public void markAsTimeout() {
        this.callStatus = CallStatus.TIMEOUT;
        this.errorMessage = "Request timeout";
    }

    public void markAsRetrying() {
        this.callStatus = CallStatus.RETRYING;
        this.retryAttempt++;
    }

    public boolean shouldRetry() {
        return retryAttempt < 3;  // 默认最多重试3次
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (expiresAt == null) {
            // 日志保留90天
            expiresAt = LocalDateTime.now().plusDays(90);
        }
    }
}
