package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 角色定义：一组权限的集合
 * 角色可以分配给用户，继承其所有权限
 */
@Entity
@Table(name = "roles")
public class RoleEntity {

    @Id
    @Column(length = 32)
    public String id;

    @Column(nullable = false, length = 100, unique = true)
    public String code;  // 角色代码，如 "operator", "game_admin", "analyst"

    @Column(nullable = false, length = 200)
    public String name;  // 角色名称

    @Column(length = 1000)
    public String description;  // 角色描述

    // 角色类型
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public RoleType type = RoleType.CUSTOM;  // 角色类型

    // 适用范围
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public RoleScope scope = RoleScope.GAME;  // 角色适用范围

    // 角色层级（用于权限继承）
    @Column(name = "parent_role_id", length = 32)
    public String parentRoleId;  // 父角色ID

    @Column(name = "level")
    public Integer level = 0;  // 角色层级：0=最高级

    // 继承设置
    @Column(name = "inherit_permissions")
    public Boolean inheritPermissions = true;  // 是否继承父角色权限

    // 预设权限（JSON格式，用于快速配置）
    @Column(name = "preset_permissions", columnDefinition = "TEXT")
    public String presetPermissions;  // JSON数组：权限ID列表

    // 状态
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public RoleStatus status = RoleStatus.ACTIVE;

    @Column(name = "is_system")
    public Boolean isSystem = false;  // 是否为系统角色（不可删除）

    @Column(name = "is_default")
    public Boolean isDefault = false;  // 是否为默认角色

    // 统计
    @Column(name = "user_count")
    public Integer userCount = 0;  // 拥有此角色的用户数

    // 时间戳
    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    public LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    public LocalDateTime deletedAt;

    @Column(name = "created_by", length = 64)
    public String createdBy;

    // 关联关系
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_role_id", insertable = false, updatable = false)
    public RoleEntity parentRole;

    @OneToMany(mappedBy = "parentRole", fetch = FetchType.LAZY)
    public List<RoleEntity> childRoles;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "role_permissions",
        joinColumns = @JoinColumn(name = "role_id"),
        inverseJoinColumns = @JoinColumn(name = "permission_id")
    )
    public List<PermissionEntity> permissions;

    public enum RoleType {
        // 系统内置角色
        OPERATOR,              // 公司级运营管理员
        GAME_ADMIN,            // 游戏管理员
        ANALYST,               // 数据分析师
        VIEWER,                // 观察者
        DEVELOPER,             // 开发者
        QA,                    // 测试
        MARKETING,             // 市场
        FINANCE,               // 财务

        // 自定义角色
        CUSTOM                 // 自定义角色
    }

    public enum RoleScope {
        GLOBAL,                // 全局角色：可应用于公司级权限
        GAME,                  // 游戏角色：可应用于游戏级权限
        ENVIRONMENT            // 环境角色：仅应用于环境级权限
    }

    public enum RoleStatus {
        ACTIVE,                // 活跃
        DISABLED,              // 已禁用
        DEPRECATED             // 已弃用
    }

    // 业务方法
    public boolean isActive() {
        return status == RoleStatus.ACTIVE && deletedAt == null;
    }

    public boolean isSystemRole() {
        return Boolean.TRUE.equals(isSystem) || type != RoleType.CUSTOM;
    }

    public boolean isDefaultRole() {
        return Boolean.TRUE.equals(isDefault);
    }

    public boolean canAssignTo(UserRoleEntity.PermissionScope scope) {
        return this.scope == RoleScope.GLOBAL ||
               (this.scope == RoleScope.GAME && scope != UserRoleEntity.PermissionScope.GLOBAL) ||
               (this.scope == RoleScope.ENVIRONMENT && scope == UserRoleEntity.PermissionScope.ENVIRONMENT);
    }

    public boolean hasHigherLevelThan(RoleEntity other) {
        if (other == null) return true;
        Integer thisLevel = this.level != null ? this.level : 0;
        Integer otherLevel = other.level != null ? other.level : 0;
        return thisLevel < otherLevel;  // 级别数字越小权限越高
    }

    public boolean isAdminRole() {
        return type == RoleType.OPERATOR || type == RoleType.GAME_ADMIN;
    }

    public boolean canManageUsers() {
        return type == RoleType.OPERATOR;
    }

    public boolean canViewAnalytics() {
        return type == RoleType.OPERATOR ||
               type == RoleType.GAME_ADMIN ||
               type == RoleType.ANALYST ||
               type == RoleType.MARKETING ||
               type == RoleType.FINANCE;
    }

    public boolean canEditSettings() {
        return type == RoleType.OPERATOR ||
               type == RoleType.GAME_ADMIN ||
               type == RoleType.DEVELOPER;
    }

    public boolean canAccessProduction() {
        return type == RoleType.OPERATOR ||
               type == RoleType.GAME_ADMIN;
    }
}
