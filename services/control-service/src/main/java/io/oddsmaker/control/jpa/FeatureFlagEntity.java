package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 功能开关实体
 * 管理功能特性的启用/禁用状态
 */
@Entity
@Table(name = "feature_flags")
public class FeatureFlagEntity {

    /**
     * 开关状态
     */
    public enum FlagStatus {
        ENABLED,         // 启用
        DISABLED,        // 禁用
        CONDITIONAL,     // 条件启用
        STAGED_ROLLOUT  // 分阶段上线
    }

    /**
     * 开关类型
     */
    public enum FlagType {
        BOOLEAN,         // 布尔开关
        PERCENTAGE,      // 百分比开关
        WHITELIST,       // 白名单开关
        BLACKLIST,       // 黑名单开关
        CONDITIONAL      // 条件开关
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public String id;

    @Column(name = "flag_key", nullable = false, length = 100, unique = true)
    public String flagKey;  // 开关键

    @Column(name = "flag_name", nullable = false, length = 100)
    public String flagName;  // 开关名称

    @Column(name = "flag_status", nullable = false)
    @Enumerated(EnumType.STRING)
    public FlagStatus flagStatus = FlagStatus.DISABLED;

    @Column(name = "flag_type", nullable = false)
    @Enumerated(EnumType.STRING)
    public FlagType flagType = FlagType.BOOLEAN;

    @Column(name = "description", columnDefinition = "TEXT")
    public String description;  // 描述

    @Column(name = "category", length = 100)
    public String category;  // 分类

    @Column(name = "owner", length = 64)
    public String owner;  // 功能负责人

    @Column(name = "tags", columnDefinition = "TEXT")
    public String tags;  // JSON格式的标签

    @Column(name = "default_value", columnDefinition = "BOOLEAN")
    public Boolean defaultValue = false;  // 默认值

    @Column(name = "percentage_value", columnDefinition = "INTEGER")
    public Integer percentageValue = 0;  // 百分比值（0-100）

    @Column(name = "whitelist_users", columnDefinition = "TEXT")
    public String whitelistUsers;  // JSON格式的白名单用户列表

    @Column(name = "blacklist_users", columnDefinition = "TEXT")
    public String blacklistUsers;  // JSON格式的黑名单用户列表

    @Column(name = "whitelist_games", columnDefinition = "TEXT")
    public String whitelistGames;  // JSON格式的白名单游戏列表

    @Column(name = "blacklist_games", columnDefinition = "TEXT")
    public String blacklistGames;  // JSON格式的黑名单游戏列表

    @Column(name = "conditions", columnDefinition = "TEXT")
    public String conditions;  // JSON格式的条件表达式

    @Column(name = "rules", columnDefinition = "TEXT")
    public String rules;  // JSON格式的规则配置

    @Column(name = "dependencies", columnDefinition = "TEXT")
    public String dependencies;  // JSON格式的依赖开关列表

    @Column(name = "rollout_strategy", length = 50)
    public String rolloutStrategy;  // 上线策略

    @Column(name = "rollout_steps", columnDefinition = "TEXT")
    public String rolloutSteps;  // JSON格式的上线步骤

    @Column(name = "current_step", columnDefinition = "INTEGER")
    public Integer currentStep = 0;  // 当前步骤

    @Column(name = "scheduled_enable_at")
    public LocalDateTime scheduledEnableAt;  // 计划启用时间

    @Column(name = "scheduled_disable_at")
    public LocalDateTime scheduledDisableAt;  // 计划禁用时间

    @Column(name = "expiry_date")
    public LocalDateTime expiryDate;  // 过期时间（临时功能）

    @Column(name = "created_by", nullable = false, length = 64)
    public String createdBy;  // 创建人

    @Column(name = "last_modified_by", length = 64)
    public String lastModifiedBy;  // 最后修改人

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @Column(name = "updated_at")
    public LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    public LocalDateTime deletedAt;

    // 辅助方法

    public boolean isEnabled() {
        return flagStatus == FlagStatus.ENABLED;
    }

    public boolean isDisabled() {
        return flagStatus == FlagStatus.DISABLED;
    }

    public boolean isConditional() {
        return flagStatus == FlagStatus.CONDITIONAL;
    }

    public boolean isStagedRollout() {
        return flagStatus == FlagStatus.STAGED_ROLLOUT;
    }

    public boolean isExpired() {
        return expiryDate != null && LocalDateTime.now().isAfter(expiryDate);
    }

    public boolean shouldEnable() {
        if (scheduledEnableAt != null && LocalDateTime.now().isAfter(scheduledEnableAt)) {
            return true;
        }
        if (scheduledDisableAt != null && LocalDateTime.now().isAfter(scheduledDisableAt)) {
            return false;
        }
        return isEnabled();
    }

    /**
     * 检查用户是否可以使用该功能
     */
    public boolean isAvailableForUser(String userId, String gameId) {
        if (isDisabled()) {
            return false;
        }

        if (isExpired()) {
            return false;
        }

        // 检查用户黑名单
        if (userId != null && blacklistUsers != null && blacklistUsers.contains(userId)) {
            return false;
        }

        // 检查游戏黑名单
        if (gameId != null && blacklistGames != null && blacklistGames.contains(gameId)) {
            return false;
        }

        if (isEnabled()) {
            // 检查用户白名单
            if (userId != null && whitelistUsers != null && !whitelistUsers.isEmpty()) {
                return whitelistUsers.contains(userId);
            }

            // 检查游戏白名单
            if (gameId != null && whitelistGames != null && !whitelistGames.isEmpty()) {
                return whitelistGames.contains(gameId);
            }

            return true;
        }

        if (isConditional() || isStagedRollout()) {
            // TODO: 实现条件检查逻辑
            return defaultValue;
        }

        return defaultValue;
    }

    /**
     * 启用功能
     */
    public void enable() {
        this.flagStatus = FlagStatus.ENABLED;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 禁用功能
     */
    public void disable() {
        this.flagStatus = FlagStatus.DISABLED;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 设置百分比
     */
    public void setPercentage(Integer percentage) {
        this.percentageValue = Math.max(0, Math.min(100, percentage));
        this.flagStatus = this.percentageValue == 100 ? FlagStatus.ENABLED :
                         this.percentageValue == 0 ? FlagStatus.DISABLED :
                         FlagStatus.STAGED_ROLLOUT;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 增加上线步骤
     */
    public void advanceRollout() {
        if (isStagedRollout() && rolloutSteps != null) {
            this.currentStep++;
            // TODO: 根据步骤更新百分比
        }
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (flagStatus == null) {
            flagStatus = FlagStatus.DISABLED;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
