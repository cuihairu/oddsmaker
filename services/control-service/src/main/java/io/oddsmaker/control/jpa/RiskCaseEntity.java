package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 风控案例实体
 * 记录每次风控规则触发的案例详情
 */
@Entity
@Table(name = "risk_cases")
public class RiskCaseEntity {

    @Id
    @Column(length = 32)
    public String id;

    @Column(name = "risk_rule_id", nullable = false, length = 32)
    public String riskRuleId;

    @Column(name = "game_id", nullable = false, length = 32)
    public String gameId;

    @Column(name = "environment_id", length = 32)
    public String environmentId;

    // 案例信息
    @Column(nullable = false, length = 100)
    public String caseNumber;  // 案例编号：CASE_YYYYMMDD_序列号

    // 关联实体
    @Column(name = "target_type", nullable = false, length = 50)
    public String targetType;  // 目标类型：user_id, device_id, player_id, ip

    @Column(name = "target_id", nullable = false, length = 200)
    public String targetId;  // 目标ID

    @Column(name = "target_name", length = 200)
    public String targetName;  // 目标名称

    // 触发信息
    @Column(name = "trigger_event_id", length = 100)
    public String triggerEventId;  // 触发事件ID

    @Column(name = "trigger_event_type", length = 50)
    public String triggerEventType;  // 触发事件类型

    @Column(name = "trigger_event_name", length = 100)
    public String triggerEventName;  // 触发事件名称

    // 风险评估
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public RiskLevel riskLevel = RiskLevel.MEDIUM;

    @Column(name = "risk_score")
    public Integer riskScore = 50;  // 风险评分

    // 判定状态（B6 §5.2 判定状态机）：OPEN → REVIEW|ALERT|MARK → THROTTLE|BLOCK → RESOLVED，
    // 分带单向推进 + 同值幂等（V0.9.19 迁移同口径）；流转合法性见 canTransition/transitionTo，
    // 合法/非法表入 RiskDecisionStateMachineTest
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    public DecisionStatus status = DecisionStatus.OPEN;

    // 处置动作
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public ActionType actionTaken = ActionType.ALERT;

    @Column(name = "action_description", length = 500)
    public String actionDescription;  // 动作描述

    // 执行状态
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public ExecutionStatus executionStatus = ExecutionStatus.PENDING;

    @Column(name = "executed_at")
    public LocalDateTime executedAt;  // 执行时间

    @Column(name = "execution_error", columnDefinition = "TEXT")
    public String executionError;  // 执行错误信息

    // 证据信息
    @Column(name = "evidence_data", columnDefinition = "TEXT")
    public String evidenceData;  // JSON格式的证据数据

    @Column(name = "context_data", columnDefinition = "TEXT")
    public String contextData;  // JSON格式的上下文数据

    // 审核信息
    @Column(name = "review_status")
    public String reviewStatus;  // 审核状态：pending, reviewing, approved, rejected

    @Column(name = "reviewed_by", length = 64)
    public String reviewedBy;  // 审核人

    @Column(name = "reviewed_at")
    public LocalDateTime reviewedAt;  // 审核时间

    @Column(name = "review_notes", columnDefinition = "TEXT")
    public String reviewNotes;  // 审核备注

    @Column(name = "disposition", length = 50)
    public String disposition;  // 处置结果：confirmed_benign, confirmed_fraud, inconclusive

    // 解除封禁
    @Column(name = "unblocked_at")
    public LocalDateTime unblockedAt;  // 解除封禁时间

    @Column(name = "unblocked_by", length = 64)
    public String unblockedBy;  // 解除人

    @Column(name = "unblock_reason", length = 500)
    public String unblockReason;  // 解除原因

    // 时间戳
    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    public LocalDateTime updatedAt;

    @Column(name = "resolved_at")
    public LocalDateTime resolvedAt;  // 解决时间

    // 关联关系
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "risk_rule_id", insertable = false, updatable = false)
    public RiskRuleEntity riskRule;

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
        CHALLENGE,         // 挑战
        MARK,              // 打标（B6 判定档，与 DecisionStatus.MARK 同名对应）
        THROTTLE,          // 限流
        BLOCK,             // 封禁
        REVIEW,            // 人工审核
        WEBHOOK            // Webhook
    }

    public enum ExecutionStatus {
        PENDING,           // 待执行
        EXECUTED,          // 已执行
        FAILED,            // 执行失败
        CANCELLED,          // 已取消
        APPEALED           // 已申诉
    }

    /**
     * B6 §5.2 判定状态（Decision）：分层前向——OPEN(0) → 一级判定 REVIEW/ALERT/MARK(1)
     * → 二级判定 THROTTLE/BLOCK(2) → 终态 RESOLVED(3)。
     */
    public enum DecisionStatus {
        OPEN,              // 初始：已建案未判定
        REVIEW,            // 一级判定：转人工审核
        ALERT,             // 一级判定：告警
        MARK,              // 一级判定：打标
        THROTTLE,          // 二级判定：限流
        BLOCK,             // 二级判定：封禁（要求输入事件 trust_level=HIGH）
        RESOLVED;          // 终态：结案

        /** 层级：合法流转须严格升层（RiskDecisionStateMachineTest 覆盖合法/非法表）。 */
        public int tier() {
            return switch (this) {
                case OPEN -> 0;
                case REVIEW, ALERT, MARK -> 1;
                case THROTTLE, BLOCK -> 2;
                case RESOLVED -> 3;
            };
        }
    }

    // 业务方法

    /**
     * 判定状态机合法转移表（B6 §5.2）：
     * <ul>
     *   <li>严格升层：目标 tier 必须大于来源 tier（可跨层判定，如同事件直接 OPEN→BLOCK）；</li>
     *   <li>OPEN 不直达 RESOLVED——没有判定的案子不允许直接结案；</li>
     *   <li>RESOLVED 为终态（tier 最大，天然无后继）；同层改判与任何回退均非法。</li>
     * </ul>
     */
    public static boolean canTransition(DecisionStatus from, DecisionStatus to) {
        if (from == null || to == null) return false;
        if (from.tier() >= to.tier()) return false;
        return !(from == DecisionStatus.OPEN && to == DecisionStatus.RESOLVED);
    }

    /** 按状态机流转；非法流转抛 {@link IllegalStateException}（由调用方决定降级或拒绝）。 */
    public void transitionTo(DecisionStatus target) {
        if (!canTransition(status, target)) {
            throw new IllegalStateException("非法判定流转: " + status + " -> " + target);
        }
        status = target;
    }

    public boolean isPending() {
        return executionStatus == ExecutionStatus.PENDING;
    }

    public boolean isExecuted() {
        return executionStatus == ExecutionStatus.EXECUTED;
    }

    public boolean isFailed() {
        return executionStatus == ExecutionStatus.FAILED;
    }

    public boolean needsReview() {
        return actionTaken == ActionType.REVIEW || actionTaken == ActionType.BLOCK;
    }

    public boolean isHighRisk() {
        return riskLevel == RiskLevel.HIGH || riskLevel == RiskLevel.CRITICAL;
    }

    public boolean isBlocked() {
        return actionTaken == ActionType.BLOCK && executionStatus == ExecutionStatus.EXECUTED;
    }

    public boolean isUnblocked() {
        return unblockedAt != null;
    }

    public boolean isConfirmedFraud() {
        return "confirmed_fraud".equals(disposition);
    }

    public boolean isConfirmedBenign() {
        return "confirmed_benign".equals(disposition);
    }

    public boolean isResolved() {
        return resolvedAt != null || isConfirmedBenign();
    }

    public void markAsFailed(String error) {
        executionStatus = ExecutionStatus.FAILED;
        executionError = error;
        executedAt = LocalDateTime.now();
    }

    public void unblock(String by, String reason) {
        unblockedAt = LocalDateTime.now();
        unblockedBy = by;
        unblockReason = reason;
    }

    public void completeReview(String reviewer, String notes, String disposition) {
        reviewStatus = "completed";
        reviewedBy = reviewer;
        reviewedAt = LocalDateTime.now();
        reviewNotes = notes;
        this.disposition = disposition;
        resolvedAt = LocalDateTime.now();
        // 审核完成即结案：判定状态机同步走到 RESOLVED；
        // 存量行可能仍处 OPEN（无判定），按合法表不强行跳层，保持原状兼容
        if (canTransition(status, DecisionStatus.RESOLVED)) {
            status = DecisionStatus.RESOLVED;
        }
    }

    public String getCaseTitle() {
        return String.format("[%s] %s %s - %s",
            riskLevel.name(),
            targetType,
            targetId,
            actionTaken.name().toLowerCase()
        );
    }

    public long getResolutionTimeMinutes() {
        if (resolvedAt == null || createdAt == null) return 0;
        return java.time.temporal.ChronoUnit.MINUTES.between(createdAt, resolvedAt);
    }
}
