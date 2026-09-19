package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 审计日志实体
 * 记录所有敏感操作
 */
@Entity
@Table(name = "audit_logs")
public class AuditLogEntity {

    @Id
    public String id;

    /**
     * 主键生成：迁移定义为 VARCHAR(32)，H2/PG 均无自增，落库前生成 32 位 hex
     */
    @jakarta.persistence.PrePersist
    void generateId() {
        if (id == null || id.isBlank()) {
            id = java.util.UUID.randomUUID().toString().replace("-", "");
        }
    }

    /**
     * 操作用户ID
     */
    @Column(name = "user_id", length = 64)
    public String userId;

    /**
     * 操作用户名
     */
    @Column(name = "username", length = 100)
    public String username;

    /**
     * 操作类型: CREATE, READ, UPDATE, DELETE, LOGIN, LOGOUT, EXPORT, IMPORT
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 50)
    public AuditAction action;

    /**
     * 资源类型: game, environment, api_key, experiment, risk_rule, user, system
     */
    @Column(name = "resource_type", nullable = false, length = 50)
    public String resourceType;

    /**
     * 资源ID
     */
    @Column(name = "resource_id", length = 64)
    public String resourceId;

    /**
     * 资源名称
     */
    @Column(name = "resource_name", length = 200)
    public String resourceName;

    /**
     * 操作详情（JSON格式）
     */
    @Column(name = "details", columnDefinition = "TEXT")
    public String details;

    /**
     * 旧值（JSON格式）
     */
    @Column(name = "old_value", columnDefinition = "TEXT")
    public String oldValue;

    /**
     * 新值（JSON格式）
     */
    @Column(name = "new_value", columnDefinition = "TEXT")
    public String newValue;

    /**
     * 操作状态: SUCCESS, FAILURE, PARTIAL
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    public AuditStatus status = AuditStatus.SUCCESS;

    /**
     * 错误信息
     */
    @Column(name = "error_message", columnDefinition = "TEXT")
    public String errorMessage;

    /**
     * 客户端IP地址
     */
    @Column(name = "ip_address", length = 45)
    public String ipAddress;

    /**
     * 用户代理
     */
    @Column(name = "user_agent", length = 500)
    public String userAgent;

    /**
     * 请求ID
     */
    @Column(name = "request_id", length = 100)
    public String requestId;

    /**
     * 会话ID
     */
    @Column(name = "session_id", length = 100)
    public String sessionId;

    /**
     * 操作耗时（毫秒）
     */
    @Column(name = "duration_ms")
    public Long durationMs;

    /**
     * 游戏ID（如果适用）
     */
    @Column(name = "game_id", length = 32)
    public String gameId;

    /**
     * 环境（如果适用）
     */
    @Column(name = "environment", length = 20)
    public String environment;

    /**
     * 审计日志创建时间
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    public LocalDateTime createdAt;

    /**
     * 操作类型枚举
     */
    public enum AuditAction {
        // CRUD操作
        CREATE,
        READ,
        UPDATE,
        DELETE,
        
        // 认证操作
        LOGIN,
        LOGOUT,
        LOGIN_FAILED,
        PASSWORD_CHANGE,
        PASSWORD_RESET,
        
        // 数据操作
        EXPORT,
        IMPORT,
        ARCHIVE,
        RESTORE,
        
        // 配置操作
        CONFIGURE,
        ENABLE,
        DISABLE,
        
        // 权限操作
        GRANT_ROLE,
        REVOKE_ROLE,
        GRANT_PERMISSION,
        REVOKE_PERMISSION,

        // 安全操作
        ACTIVATE,
        DEACTIVATE,
        BLOCK,
        UNBLOCK,
        SECURITY_ALERT,
        GRANT,

        // 系统操作
        SYSTEM_BACKUP,
        SYSTEM_RESTORE,
        SYSTEM_MAINTENANCE
    }

    /**
     * 审计结果枚举
     */
    public enum AuditResult {
        SUCCESS,
        FAILURE,
        PARTIAL,
        SKIPPED
    }

    /**
     * 审计状态枚举
     */
    public enum AuditStatus {
        SUCCESS,
        FAILURE,
        PARTIAL,
        DENIED
    }

    // 业务方法
    public boolean isSuccess() {
        return status == AuditStatus.SUCCESS;
    }

    public boolean isFailure() {
        return status == AuditStatus.FAILURE;
    }

    public boolean isAuthAction() {
        return action == AuditAction.LOGIN ||
               action == AuditAction.LOGOUT ||
               action == AuditAction.LOGIN_FAILED ||
               action == AuditAction.PASSWORD_CHANGE ||
               action == AuditAction.PASSWORD_RESET;
    }

    public boolean isDataAction() {
        return action == AuditAction.CREATE ||
               action == AuditAction.UPDATE ||
               action == AuditAction.DELETE ||
               action == AuditAction.EXPORT ||
               action == AuditAction.IMPORT;
    }

    public String getActionDescription() {
        return switch (action) {
            case CREATE -> "创建";
            case READ -> "读取";
            case UPDATE -> "更新";
            case DELETE -> "删除";
            case LOGIN -> "登录";
            case LOGOUT -> "登出";
            case LOGIN_FAILED -> "登录失败";
            case PASSWORD_CHANGE -> "修改密码";
            case PASSWORD_RESET -> "重置密码";
            case EXPORT -> "导出";
            case IMPORT -> "导入";
            case ARCHIVE -> "归档";
            case RESTORE -> "恢复";
            case CONFIGURE -> "配置";
            case ENABLE -> "启用";
            case DISABLE -> "禁用";
            case GRANT_ROLE -> "授予角色";
            case REVOKE_ROLE -> "撤销角色";
            case GRANT_PERMISSION -> "授予权限";
            case REVOKE_PERMISSION -> "撤销权限";
            case ACTIVATE -> "激活";
            case DEACTIVATE -> "停用";
            case BLOCK -> "封禁";
            case UNBLOCK -> "解封";
            case SECURITY_ALERT -> "安全告警";
            case GRANT -> "授权";
            case SYSTEM_BACKUP -> "系统备份";
            case SYSTEM_RESTORE -> "系统恢复";
            case SYSTEM_MAINTENANCE -> "系统维护";
        };
    }
}