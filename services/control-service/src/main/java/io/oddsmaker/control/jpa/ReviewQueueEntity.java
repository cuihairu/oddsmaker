package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 审核队列实体
 * 管理风险案例的人工审核队列
 */
@Entity
@Table(name = "review_queues")
public class ReviewQueueEntity {

    @Id
    @Column(length = 32)
    public String id;

    // 关联信息
    @Column(name = "risk_case_id", nullable = false, length = 32)
    public String riskCaseId;

    @Column(name = "game_id", nullable = false, length = 32)
    public String gameId;

    @Column(name = "environment_id", length = 32)
    public String environmentId;

    // 案例信息（冗余存储，便于查询）
    @Column(name = "case_number", length = 100)
    public String caseNumber;

    @Column(name = "target_type", length = 50)
    public String targetType;

    @Column(name = "target_id", length = 200)
    public String targetId;

    @Column(name = "target_name", length = 200)
    public String targetName;

    @Column(name = "risk_level", length = 20)
    public String riskLevel;

    @Column(name = "risk_score")
    public Integer riskScore;

    @Column(name = "action_type", length = 20)
    public String actionType;

    // 优先级和分类
    @Column(name = "priority")
    public Integer priority = 50;  // 优先级：1-100，越高越优先

    @Column(name = "queue_type", length = 50)
    public String queueType = "default";  // 队列类型：default, high_priority, fraud, cheating

    @Column(name = "category", length = 50)
    public String category;  // 分类：fraud, cheating, abuse, tos_violation, suspicious

    // 审核状态
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public ReviewStatus reviewStatus = ReviewStatus.PENDING;

    @Column(name = "assigned_to", length = 64)
    public String assignedTo;  // 分配给的审核人

    @Column(name = "assigned_at")
    public LocalDateTime assignedAt;  // 分配时间

    @Column(name = "claimed_by", length = 64)
    public String claimedBy;  // 认领的审核人

    @Column(name = "claimed_at")
    public LocalDateTime claimedAt;  // 认领时间

    // 审核信息
    @Column(name = "reviewed_by", length = 64)
    public String reviewedBy;  // 审核人

    @Column(name = "reviewed_at")
    public LocalDateTime reviewedAt;  // 审核时间

    @Column(name = "review_notes", columnDefinition = "TEXT")
    public String reviewNotes;  // 审核备注

    @Column(name = "disposition", length = 50)
    public String disposition;  // 处置结果：confirmed_fraud, confirmed_benign, inconclusive, needs_investigation

    @Column(name = "resolution", columnDefinition = "TEXT")
    public String resolution;  // 解决方案描述

    // 升级信息
    @Column(name = "escalated")
    public Boolean escalated = false;  // 是否升级

    @Column(name = "escalated_to", length = 64)
    public String escalatedTo;  // 升级给谁

    @Column(name = "escalated_at")
    public LocalDateTime escalatedAt;  // 升级时间

    @Column(name = "escalation_reason", length = 500)
    public String escalationReason;  // 升级原因

    // SLA跟踪
    @Column(name = "sla_due_at")
    public LocalDateTime slaDueAt;  // SLA到期时间

    @Column(name = "sla_breached")
    public Boolean slaBreached = false;  // 是否违反SLA

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
    @JoinColumn(name = "risk_case_id", insertable = false, updatable = false)
    public RiskCaseEntity riskCase;

    public enum ReviewStatus {
        PENDING,           // 待分配
        ASSIGNED,          // 已分配
        CLAIMED,           // 已认领
        IN_REVIEW,         // 审核中
        COMPLETED,         // 已完成
        ESCALATED,         // 已升级
        CANCELLED          // 已取消
    }

    // 业务方法
    public boolean isPending() {
        return reviewStatus == ReviewStatus.PENDING;
    }

    public boolean isAssigned() {
        return reviewStatus == ReviewStatus.ASSIGNED || reviewStatus == ReviewStatus.CLAIMED;
    }

    public boolean isInReview() {
        return reviewStatus == ReviewStatus.IN_REVIEW;
    }

    public boolean isCompleted() {
        return reviewStatus == ReviewStatus.COMPLETED;
    }

    public boolean isEscalated() {
        return reviewStatus == ReviewStatus.ESCALATED;
    }

    public boolean isOverdue() {
        if (slaDueAt == null) return false;
        return LocalDateTime.now().isAfter(slaDueAt) && !isCompleted();
    }

    public long getAgeMinutes() {
        if (createdAt == null) return 0;
        return java.time.temporal.ChronoUnit.MINUTES.between(createdAt, LocalDateTime.now());
    }

    public long getResolutionTimeMinutes() {
        if (resolvedAt == null || createdAt == null) return 0;
        return java.time.temporal.ChronoUnit.MINUTES.between(createdAt, resolvedAt);
    }

    public void assignTo(String reviewer, LocalDateTime slaDueAt) {
        this.reviewStatus = ReviewStatus.ASSIGNED;
        this.assignedTo = reviewer;
        this.assignedAt = LocalDateTime.now();
        this.slaDueAt = slaDueAt;
    }

    public void claim(String reviewer) {
        this.reviewStatus = ReviewStatus.CLAIMED;
        this.claimedBy = reviewer;
        this.claimedAt = LocalDateTime.now();
    }

    public void startReview(String reviewer) {
        this.reviewStatus = ReviewStatus.IN_REVIEW;
        this.reviewedBy = reviewer;
        this.reviewedAt = LocalDateTime.now();
    }

    public void complete(String reviewer, String notes, String disposition, String resolution) {
        this.reviewStatus = ReviewStatus.COMPLETED;
        this.reviewedBy = reviewer;
        this.reviewedAt = LocalDateTime.now();
        this.reviewNotes = notes;
        this.disposition = disposition;
        this.resolution = resolution;
        this.resolvedAt = LocalDateTime.now();
    }

    public void escalate(String escalatedTo, String reason) {
        this.reviewStatus = ReviewStatus.ESCALATED;
        this.escalated = true;
        this.escalatedTo = escalatedTo;
        this.escalationReason = reason;
        this.escalatedAt = LocalDateTime.now();
    }

    public void cancel(String reason) {
        this.reviewStatus = ReviewStatus.CANCELLED;
        this.reviewNotes = reason;
        this.resolvedAt = LocalDateTime.now();
    }

    public String getQueueDescription() {
        return String.format("[%s] %s - %s %s (Priority: %d)",
            reviewStatus.name(),
            caseNumber != null ? caseNumber : riskCaseId,
            targetType,
            targetId,
            priority
        );
    }

    public boolean isHighPriority() {
        return priority != null && priority >= 70;
    }

    public boolean isUrgent() {
        return priority != null && priority >= 90;
    }
}
