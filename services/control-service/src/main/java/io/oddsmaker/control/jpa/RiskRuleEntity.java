package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 风控规则实体
 * 定义实时风控检测规则
 */
@Entity
@Table(name = "risk_rules")
public class RiskRuleEntity {

    @Id
    @Column(length = 32)
    public String id;

    @Column(name = "game_id", nullable = false, length = 32)
    public String gameId;

    @Column(name = "environment_id", length = 32)
    public String environmentId;  // null表示全局规则

    @Column(nullable = false, length = 100)
    public String name;

    @Column(name = "display_name", length = 200)
    public String displayName;

    @Column(length = 1000)
    public String description;

    // 规则分类
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public RuleCategory category = RuleCategory.BEHAVIOR;  // 规则分类

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public RuleType ruleType = RuleType.THRESHOLD;  // 规则类型

    // 规则条件（JSON格式）
    @Column(name = "rule_conditions", columnDefinition = "TEXT")
    public String ruleConditions;  // {"event_type": "purchase", "amount": ">1000", "frequency": ">10/hour"}

    // 风险等级
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public RiskLevel riskLevel = RiskLevel.MEDIUM;  // 触发后的风险等级

    @Column(name = "risk_score")
    public Integer riskScore = 50;  // 风险评分（0-100）

    // 处置动作
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public ActionType actionType = ActionType.ALERT;  // 处置动作类型

    @Column(name = "action_params", columnDefinition = "TEXT")
    public String actionParams;  // JSON格式的动作参数

    // 执行配置
    @Column(name = "enable_auto_block")
    public Boolean enableAutoBlock = false;  // 启用自动封禁

    @Column(name = "block_duration")
    public Integer blockDuration;  // 封禁时长（分钟）

    @Column(name = "enable_webhook")
    public Boolean enableWebhook = false;  // 启用Webhook通知

    @Column(name = "webhook_url", length = 500)
    public String webhookUrl;  // Webhook URL

    @Column(name = "enable_review_queue")
    public Boolean enableReviewQueue = true;  // 加入审核队列

    // 阈值配置
    @Column(name = "trigger_threshold")
    public Integer triggerThreshold = 1;  // 触发阈值（几次触发后执行）

    @Column(name = "time_window_minutes")
    public Integer timeWindowMinutes = 60;  // 时间窗口（分钟）

    @Column(name = "cooldown_minutes")
    public Integer cooldownMinutes = 0;  // 冷却时间（分钟）

    // 优先级
    @Column(name = "priority")
    public Integer priority = 0;  // 优先级（数字越大优先级越高）

    // 状态
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public RuleStatus status = RuleStatus.ACTIVE;

    // 统计
    @Column(name = "total_triggered_count")
    public Long totalTriggeredCount = 0L;  // 总触发次数

    @Column(name = "total_blocked_count")
    public Long totalBlockedCount = 0L;  // 总拦截次数

    @Column(name = "total_review_count")
    public Long totalReviewCount = 0L;  // 总审核次数

    @Column(name = "last_triggered_at")
    public LocalDateTime lastTriggeredAt;  // 最后触发时间

    // 版本控制
    @Column(name = "version", length = 20)
    public String version = "1.0";

    @Column(name = "parent_rule_id", length = 32)
    public String parentRuleId;  // 父规则ID（用于规则继承）

    // 测试模式
    @Column(name = "test_mode")
    public Boolean testMode = false;  // 测试模式（仅记录不执行）

    // 时间戳
    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    public LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    public LocalDateTime deletedAt;

    @Column(name = "activated_at")
    public LocalDateTime activatedAt;  // 激活时间

    @Column(name = "created_by", length = 64)
    public String createdBy;

    // 关联关系
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "game_id", insertable = false, updatable = false)
    public GameEntity game;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "environment_id", insertable = false, updatable = false)
    public GameEnvironmentEntity environment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_rule_id", insertable = false, updatable = false)
    public RiskRuleEntity parentRule;

    @OneToMany(mappedBy = "riskRule", fetch = FetchType.LAZY)
    public List<RiskCaseEntity> cases;

    public enum RuleCategory {
        BEHAVIOR,          // 行为异常
        PAYMENT,           // 支付异常
        ACCOUNT,           // 账号异常
        DEVICE,            // 设备异常
        NETWORK,           // 网络异常
        AUTOMATION,        // 自动化脚本
        COLLUSION,         // 作弊联盟
        ECONOMY            // 经济异常
    }

    public enum RuleType {
        THRESHOLD,         // 阈值规则
        FREQUENCY,         // 频次规则
        PATTERN,           // 模式规则
        VELOCITY,          // 速度规则
        RATIO,             // 比例规则
        ANOMALY,           // 异常检测
        MACHINE_LEARNING   // 机器学习
    }

    public enum RiskLevel {
        LOW,               // 低风险
        MEDIUM,            // 中风险
        HIGH,              // 高风险
        CRITICAL           // 严重风险
    }

    public enum ActionType {
        IGNORE,            // 忽略
        ALERT,             // 告警
        LOG_ONLY,          // 仅记录
        CHALLENGE,         // 挑战（验证码等）
        THROTTLE,          // 限流
        BLOCK,             // 封禁
        REVIEW,            // 人工审核
        WEBHOOK            // Webhook回调
    }

    public enum RuleStatus {
        DRAFT,             // 草稿
        ACTIVE,            // 活跃
        PAUSED,            // 暂停
        ARCHIVED,          // 已归档
        DEPRECATED         // 已弃用
    }

    // 业务方法
    public boolean isActive() {
        return status == RuleStatus.ACTIVE && deletedAt == null;
    }

    public boolean isGlobal() {
        return environmentId == null;
    }

    public boolean isAutoBlockEnabled() {
        return Boolean.TRUE.equals(enableAutoBlock) && actionType == ActionType.BLOCK;
    }

    public boolean isInTestMode() {
        return Boolean.TRUE.equals(testMode);
    }

    public boolean needsReview() {
        return Boolean.TRUE.equals(enableReviewQueue) || actionType == ActionType.REVIEW;
    }

    public boolean isHighRisk() {
        return riskLevel == RiskLevel.HIGH || riskLevel == RiskLevel.CRITICAL;
    }

    public boolean isCriticalRisk() {
        return riskLevel == RiskLevel.CRITICAL;
    }

    public boolean isInCooldown(LocalDateTime lastExecution) {
        if (cooldownMinutes == null || cooldownMinutes <= 0) return false;
        if (lastExecution == null) return false;
        return lastExecution.plusMinutes(cooldownMinutes).isAfter(LocalDateTime.now());
    }

    public String getActionDescription() {
        String action = actionType.name().toLowerCase();
        if (actionType == ActionType.BLOCK && blockDuration != null) {
            return action + " for " + blockDuration + " minutes";
        }
        return action;
    }
}
