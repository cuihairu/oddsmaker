package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 权限定义：系统中所有可能的权限项
 * 权限按照资源和操作分类
 */
@Entity
@Table(name = "permissions")
public class PermissionEntity {

    @Id
    @Column(length = 32)
    public String id;

    @Column(nullable = false, length = 100, unique = true)
    public String code;  // 权限代码，如 "game.read", "event.write"

    @Column(nullable = false, length = 200)
    public String name;  // 权限名称

    @Column(length = 500)
    public String description;  // 权限描述

    // 资源分类
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public Resource resource;  // 资源类型

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public Operation operation;  // 操作类型

    // 权限范围
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public PermissionScope applicableScope = PermissionScope.ALL;  // 适用范围

    // 分组
    @Column(name = "category", length = 100)
    public String category;  // 权限分类，如 "game_management", "analytics"

    @Column(name = "display_order")
    public Integer displayOrder = 0;  // 显示顺序

    // 状态
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public PermissionStatus status = PermissionStatus.ACTIVE;

    @Column(name = "is_system")
    public Boolean isSystem = false;  // 是否为系统权限（不可删除）

    // 时间戳
    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    public LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    public LocalDateTime deletedAt;

    // 关联关系
    @ManyToMany(mappedBy = "permissions")
    public List<RoleEntity> roles;

    public enum Resource {
        // 游戏管理
        GAME,                    // 游戏
        ENVIRONMENT,            // 环境
        API_KEY,                // API密钥

        // 追踪计划
        TRACKING_PLAN,          // 追踪计划
        EVENT_DEFINITION,       // 事件定义

        // 数据管理
        EVENTS_DATA,            // 事件数据
        ANALYTICS,              // 分析功能

        // 策略管理
        PRIVACY_POLICY,         // 隐私策略
        SAMPLING_POLICY,        // 采样策略
        RATE_LIMIT_POLICY,      // 限流策略

        // 用户管理
        USER,                   // 用户
        ROLE,                   // 角色
        PERMISSION,             // 权限

        // 系统管理
        SYSTEM,                 // 系统配置
        AUDIT_LOG,              // 审计日志
        STORAGE_PROFILE         // 存储策略
    }

    public enum Operation {
        READ,                   // 读取
        WRITE,                  // 写入
        DELETE,                 // 删除
        ADMIN,                  // 管理
        APPROVE,                // 审批
        EXPORT                  // 导出
    }

    public enum PermissionScope {
        ALL,                    // 适用于所有范围
        GLOBAL_ONLY,            // 仅全局范围
        GAME_AND_BELOW          // 游戏及以下范围
    }

    public enum PermissionStatus {
        ACTIVE,                 // 活跃
        DISABLED,               // 已禁用
        DEPRECATED              // 已弃用
    }

    // 业务方法
    public boolean isActive() {
        return status == PermissionStatus.ACTIVE && deletedAt == null;
    }

    public boolean isSystemPermission() {
        return Boolean.TRUE.equals(isSystem);
    }

    public boolean isApplicableTo(PermissionScope scope) {
        return applicableScope == PermissionScope.ALL ||
               applicableScope == scope;
    }

    public boolean isReadOnly() {
        return operation == Operation.READ;
    }

    public boolean isDestructive() {
        return operation == Operation.DELETE || operation == Operation.ADMIN;
    }

    public String getFullName() {
        return resource.name().toLowerCase() + "." + operation.name().toLowerCase();
    }
}
