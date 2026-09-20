package io.oddsmaker.control.jpa;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.experiment.ExperimentSplitter;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 功能开关实体
 * 管理功能特性的启用/禁用状态
 */
@Entity
@Table(name = "feature_flags")
public class FeatureFlagEntity {

    private static final ObjectMapper JSON = new ObjectMapper();

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

    /**
     * 名单列（黑白名单）是 JSON 数组文本，如 {@code ["u1","u10"]}——成员判断而非子串匹配，
     * 否则名单含 "u1" 时 "u10" 被误伤（黑名单误拒 / 白名单误放）。
     * 非 JSON 存量文本保守回退子串匹配（不回归既有行为）。
     */
    private static boolean listContains(String json, String value) {
        if (json == null) {
            return false;
        }
        try {
            return JSON.readValue(json, new TypeReference<List<String>>() {}).contains(value);
        } catch (JsonProcessingException e) {
            return json.contains(value);
        }
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
        if (userId != null && listContains(blacklistUsers, userId)) {
            return false;
        }

        // 检查游戏黑名单
        if (gameId != null && listContains(blacklistGames, gameId)) {
            return false;
        }

        if (isEnabled()) {
            // 检查用户白名单
            if (userId != null && whitelistUsers != null && !whitelistUsers.isEmpty()) {
                return listContains(whitelistUsers, userId);
            }

            // 检查游戏白名单
            if (gameId != null && whitelistGames != null && !whitelistGames.isEmpty()) {
                return listContains(whitelistGames, gameId);
            }

            return true;
        }

        if (isConditional() || isStagedRollout()) {
            // 白名单先行：灰度/条件态白名单用户恒可用（行为变更，此前回落 defaultValue）
            if (userId != null && whitelistUsers != null && !whitelistUsers.isEmpty()
                    && listContains(whitelistUsers, userId)) {
                return true;
            }

            if (isConditional()) {
                return evaluateConditions(userId, gameId);
            }

            // STAGED_ROLLOUT：FNV-1a 确定性分桶（与四端 SDK 同源），曝光率 = percentageValue% 可审计复算。
            // 灰度态不叠加 conditions。
            int pct = percentageValue == null ? 0 : percentageValue;
            if (userId == null || userId.isBlank() || pct <= 0) {
                return Boolean.TRUE.equals(defaultValue);
            }
            long bucket = Integer.toUnsignedLong(ExperimentSplitter.hash32(flagKey + ":" + userId)) % 100;
            return bucket < pct;
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
     * 增加上线步骤，并按 rolloutSteps 回写百分比（复用 setPercentage 的钳制与状态联动，
     * 末步 100 自动转 ENABLED）。步骤列表非法/越界或超出步骤数时仅递增不回写。
     */
    public void advanceRollout() {
        if (isStagedRollout() && rolloutSteps != null) {
            this.currentStep++;
            List<Integer> steps = parseRolloutSteps();
            if (steps != null && currentStep >= 1 && currentStep <= steps.size()) {
                setPercentage(steps.get(currentStep - 1));
            }
        }
    }

    /**
     * 解析 rolloutSteps 为整数列表；为空/非法 JSON/含越界值（&lt;0 或 &gt;100）时返回 null（仅递增不回写）。
     */
    private List<Integer> parseRolloutSteps() {
        if (rolloutSteps == null || rolloutSteps.isBlank()) {
            return null;
        }
        try {
            List<Integer> steps = JSON.readValue(rolloutSteps, new TypeReference<List<Integer>>() {});
            for (Integer step : steps) {
                if (step == null || step < 0 || step > 100) {
                    return null;
                }
            }
            return steps;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /**
     * 最小条件求值：conditions 为 JSON 数组、AND 语义，
     * 如 [{"attribute":"game_id","op":"in","value":["g1","g2"]}]。
     * attribute 仅限 user_id/game_id（对齐 check 端点入参）；op 仅限 eq/ne/in/not_in（大小写不敏感）；
     * value 为标量或数组。conditions 空/null/非法 JSON/空数组 → 回落 defaultValue（保守）；
     * 未知 attribute/op/value 为空/入参 null → 该条件按 false（明确不放行，不回落）。
     */
    private boolean evaluateConditions(String userId, String gameId) {
        List<Map<String, Object>> list = parseConditions();
        if (list == null || list.isEmpty()) {
            return Boolean.TRUE.equals(defaultValue);
        }
        for (Map<String, Object> cond : list) {
            if (!matchCondition(cond, userId, gameId)) {
                return false;
            }
        }
        return true;
    }

    /** conditions 列表解析；为空/非法 JSON（含对象而非数组）时返回 null（回落 defaultValue）。 */
    private List<Map<String, Object>> parseConditions() {
        if (conditions == null || conditions.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(conditions, new TypeReference<List<Map<String, Object>>>() {});
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /** 单条件判定：attribute 解析主体、op 大小写不敏感地比对 value（标量或数组，统一转字符串列表）。 */
    private boolean matchCondition(Map<String, Object> cond, String userId, String gameId) {
        if (cond == null) {
            return false;
        }
        String attribute = cond.get("attribute") instanceof String s ? s : null;
        String op = cond.get("op") instanceof String o ? o.trim().toLowerCase() : null;
        if (attribute == null || op == null) {
            return false;
        }
        String actual = switch (attribute) {
            case "user_id" -> userId;
            case "game_id" -> gameId;
            default -> null;
        };
        if (actual == null) {
            return false;
        }
        List<String> expected = toValueList(cond.get("value"));
        if (expected.isEmpty()) {
            return false;
        }
        boolean contains = expected.contains(actual);
        return switch (op) {
            case "eq", "in" -> contains;
            case "ne", "not_in" -> !contains;
            default -> false;
        };
    }

    /** value 归一为字符串列表：String 原样；Number/Boolean 转字符串；数组逐元素展开；其余元素忽略。 */
    private List<String> toValueList(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String s) {
                    out.add(s);
                } else if (item instanceof Number || item instanceof Boolean) {
                    out.add(String.valueOf(item));
                }
            }
        } else if (value instanceof String s) {
            out.add(s);
        } else if (value instanceof Number || value instanceof Boolean) {
            out.add(String.valueOf(value));
        }
        return out;
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
