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

    // 业务方法
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
