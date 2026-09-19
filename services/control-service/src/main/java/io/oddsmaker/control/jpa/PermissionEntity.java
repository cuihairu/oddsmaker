package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 权限实体
 * 定义系统中的权限
 */
@Entity
@Table(name = "permissions")
public class PermissionEntity {

    @Id
    @Column(length = 100)
    public String id;

    @Column(nullable = false, unique = true, length = 100)
    public String name;

    @Column(length = 500)
    public String description;

    /**
     * 权限类型: API, DATA, SYSTEM
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    public PermissionType type;

    /**
     * 资源类型: game, environment, api_key, experiment, risk_rule, user, system
     */
    @Column(name = "resource_type", length = 50)
    public String resourceType;

    /**
     * 操作类型: CREATE, READ, UPDATE, DELETE, EXPORT, IMPORT
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false)
    public PermissionAction action;

    /**
     * 权限范围: GLOBAL, GAME, ENVIRONMENT
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false)
    public PermissionScope scope;

    /**
     * 是否启用
     */
    @Column(name = "enabled")
    public Boolean enabled = true;

    /**
     * 是否系统内置权限
     */
    @Column(name = "system")
    public Boolean system = false;

    // 时间戳
    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    public LocalDateTime updatedAt;

    /**
     * 权限类型枚举
     */
    public enum PermissionType {
        API,      // API访问权限
        DATA,     // 数据访问权限
        SYSTEM    // 系统管理权限
    }

    /**
     * 权限操作枚举。
     * EXECUTE/DEPLOY/PREDICT/REVIEW/READ_SENSITIVE/READ_INFRA 六值为 V0.9.8 种子
     * （ml:train、ml:deploy、ml:use、risk:review、audit:sensitive、metrics:infra 等）
     * 已落库的 action 字面量——{@code roles.permissions} 是 EAGER {@code @ManyToMany}，
     * 枚举缺值时加载任意绑定角色即水化炸（IllegalArgumentException），扩枚举即修复存量。
     */
    public enum PermissionAction {
        CREATE,
        READ,
        UPDATE,
        DELETE,
        EXPORT,
        IMPORT,
        MANAGE,
        ADMIN,
        EXECUTE,        // ml:train / integration:trigger / pipeline:execute / export:execute
        DEPLOY,         // ml:deploy
        PREDICT,        // ml:use
        REVIEW,         // risk:review
        READ_SENSITIVE, // audit:sensitive
        READ_INFRA      // metrics:infra
    }

    /**
     * 权限范围枚举
     */
    public enum PermissionScope {
        GLOBAL,       // 全局权限
        GAME,         // 游戏级权限
        ENVIRONMENT   // 环境级权限
    }

    // 业务方法
    public boolean isEnabled() {
        return enabled != null && enabled;
    }

    public boolean isSystem() {
        return system != null && system;
    }

    public boolean isGlobal() {
        return scope == PermissionScope.GLOBAL;
    }

    public boolean isGameScoped() {
        return scope == PermissionScope.GAME;
    }

    public boolean isEnvironmentScoped() {
        return scope == PermissionScope.ENVIRONMENT;
    }

    public String getFullPermission() {
        return resourceType + ":" + action.name().toLowerCase();
    }
}