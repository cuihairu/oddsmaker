package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 审计日志：记录系统中的所有重要操作
 * 用于安全审计、合规检查和问题排查
 */
@Entity
@Table(name = "audit_logs")
public class AuditLogEntity {

    @Id
    @Column(length = 32)
    public String id;

    // 操作者信息
    @Column(name = "user_id", length = 32)
    public String userId;  // 操作人ID

    @Column(name = "user_name", length = 100)
    public String userName;  // 操作人名称

    @Column(name = "user_email", length = 200)
    public String userEmail;  // 操作人邮箱

    // 操作信息
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public AuditAction action;  // 操作类型

    @Column(name = "resource_type", nullable = false, length = 100)
    public String resourceType;  // 资源类型：game, environment, api_key等

    @Column(name = "resource_id", length = 32)
    public String resourceId;  // 资源ID

    @Column(name = "resource_name", length = 200)
    public String resourceName;  // 资源名称（用于快速查看）

    // 操作范围
    @Column(name = "scope_game_id", length = 32)
    public String scopeGameId;  // 操作涉及的游戏ID

    @Column(name = "scope_environment_id", length = 32)
    public String scopeEnvironmentId;  // 操作涉及的环境ID

    // 操作详情
    @Column(name = "action_description", length = 500)
    public String actionDescription;  // 操作描述

    @Column(name = "changes", columnDefinition = "TEXT")
    public String changes;  // 变更内容（JSON格式）

    // 请求信息
    @Column(name = "request_id", length = 64)
    public String requestId;  // 请求ID（用于关联）

    @Column(name = "request_method", length = 10)
    public String requestMethod;  // HTTP方法

    @Column(name = "request_path", length = 500)
    public String requestPath;  // 请求路径

    // 客户端信息
    @Column(name = "client_ip", length = 50)
    public String clientIp;  // 客户端IP

    @Column(name = "user_agent", length = 500)
    public String userAgent;  // 用户代理

    // 结果
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public AuditResult result = AuditResult.SUCCESS;  // 操作结果

    @Column(name = "error_message", length = 1000)
    public String errorMessage;  // 错误信息

    // 时间戳
    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    // TTL（数据保留）
    @Column(name = "expire_at")
    public LocalDateTime expireAt;  // 过期时间（用于自动清理）

    @Column(name = "alerted")
    public Boolean alerted = false;  // 是否已发送告警

    public enum AuditAction {
        // 资源操作
        CREATE,                 // 创建
        READ,                   // 读取
        UPDATE,                 // 更新
        DELETE,                 // 删除
        RESTORE,                // 恢复

        // 状态变更
        ACTIVATE,               // 激活
        DEACTIVATE,             // 停用
        ARCHIVE,                // 归档

        // 权限操作
        GRANT,                  // 授权
        REVOKE,                 // 撤销
        INVITE,                 // 邀请
        ACCEPT_INVITE,          // 接受邀请

        // 敏感操作
        LOGIN,                  // 登录
        LOGOUT,                 // 登出
        PASSWORD_CHANGE,        // 修改密码
        API_KEY_GENERATE,       // 生成API密钥
        API_KEY_REVEAL,         // 查看API密钥
        EXPORT_DATA,            // 导出数据
        BULK_DELETE,            // 批量删除

        // 配置变更
        CONFIG_CHANGE,          // 配置变更
        POLICY_CHANGE,          // 策略变更
        SCHEMA_CHANGE,          // 模式变更

        // 安全相关
        SECURITY_ALERT,         // 安全告警
        RATE_LIMIT_EXCEEDED,    // 超过限流
        UNAUTHORIZED_ACCESS,   // 未授权访问
        SUSPICIOUS_ACTIVITY     // 可疑活动
    }

    public enum AuditResult {
        SUCCESS,                // 成功
        FAILURE,                // 失败
        PARTIAL,                // 部分成功
        BLOCKED,                // 已阻止
        ERROR                   // 错误
    }

    // 业务方法
    public boolean isSuccess() {
        return result == AuditResult.SUCCESS;
    }

    public boolean isFailure() {
        return result == AuditResult.FAILURE || result == AuditResult.ERROR;
    }

    public boolean isBlocked() {
        return result == AuditResult.BLOCKED;
    }

    public boolean isSensitiveAction() {
        return action == AuditAction.API_KEY_REVEAL ||
               action == AuditAction.PASSWORD_CHANGE ||
               action == AuditAction.DELETE ||
               action == AuditAction.BULK_DELETE ||
               action == AuditAction.GRANT ||
               action == AuditAction.REVOKE;
    }

    public boolean isSecurityEvent() {
        return action == AuditAction.SECURITY_ALERT ||
               action == AuditAction.RATE_LIMIT_EXCEEDED ||
               action == AuditAction.UNAUTHORIZED_ACCESS ||
               action == AuditAction.SUSPICIOUS_ACTIVITY;
    }

    public boolean requiresImmediateAlert() {
        return isSecurityEvent() ||
               (isFailure() && isSensitiveAction());
    }

    public String getFullDescription() {
        StringBuilder sb = new StringBuilder();
        sb.append(action.name().toLowerCase());
        sb.append(" ").append(resourceType.toLowerCase());
        if (resourceName != null) {
            sb.append(" (").append(resourceName).append(")");
        }
        if (actionDescription != null) {
            sb.append(": ").append(actionDescription);
        }
        return sb.toString();
    }
}
