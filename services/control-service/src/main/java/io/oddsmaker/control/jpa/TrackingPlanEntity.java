package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 追踪计划：定义游戏的事件追踪规范
 * 每个游戏可以有多个版本的追踪计划，支持事件定义的版本管理
 */
@Entity
@Table(name = "tracking_plans")
public class TrackingPlanEntity {

    @Id
    @Column(length = 32)
    public String id;

    @Column(name = "game_id", nullable = false, length = 32)
    public String gameId;

    @Column(nullable = false, length = 100)
    public String name;          // 计划名称，如 "v1.0", "2024-Q1"

    @Column(name = "display_name", length = 200)
    public String displayName;    // 显示名称

    @Column(name = "description", length = 1000)
    public String description;    // 计划描述

    @Column(name = "version", length = 20)
    public String version;        // 版本号，如 "1.0.0"

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public PlanStatus status = PlanStatus.DRAFT;  // 草稿、活跃、已弃用

    // 环境绑定：该追踪计划适用的环境
    // 为null时表示适用于所有环境
    @Column(name = "environment_id", length = 32)
    public String environmentId;

    // 验证严格度
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public ValidationStrictness strictness = ValidationStrictness.STRICT;  // 严格度

    @Column(name = "enable_auto_validation")
    public Boolean enableAutoValidation = true;  // 是否启用自动验证

    @Column(name = "reject_unknown_events")
    public Boolean rejectUnknownEvents = false; // 是否拒绝未定义的事件

    // 统计信息
    @Column(name = "total_events")
    public Integer totalEvents = 0;  // 定义的事件总数

    @Column(name = "active_events")
    public Integer activeEvents = 0;  // 活跃事件数

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

    @Column(name = "deactivated_at")
    public LocalDateTime deactivatedAt;  // 弃用时间

    @Column(name = "created_by", length = 64)
    public String createdBy;  // 创建人

    @Column(name = "activated_by", length = 64)
    public String activatedBy;  // 激活人

    // 关联关系
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "game_id", insertable = false, updatable = false)
    public GameEntity game;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "environment_id", insertable = false, updatable = false)
    public GameEnvironmentEntity environment;

    @OneToMany(mappedBy = "trackingPlan", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    public List<EventDefinitionEntity> eventDefinitions;

    public enum PlanStatus {
        DRAFT,      // 草稿：编辑中
        ACTIVE,     // 活跃：正在使用
        DEPRECATED  // 已弃用：不再使用
    }

    public enum ValidationStrictness {
        OFF,        // 关闭验证
        WARN,       // 仅警告
        STRICT      // 严格模式：拒绝不符合的事件
    }

    // 业务方法
    public boolean isActive() {
        return status == PlanStatus.ACTIVE && deletedAt == null;
    }

    public boolean isDraft() {
        return status == PlanStatus.DRAFT && deletedAt == null;
    }

    public boolean canEdit() {
        return status == PlanStatus.DRAFT && deletedAt == null;
    }

    public boolean isGlobal() {
        return environmentId == null;
    }

    public void activate(String userId) {
        if (status != PlanStatus.DRAFT) {
            throw new IllegalStateException("Only draft plans can be activated");
        }
        this.status = PlanStatus.ACTIVE;
        this.activatedAt = LocalDateTime.now();
        this.activatedBy = userId;
    }

    public void deactivate() {
        if (status != PlanStatus.ACTIVE) {
            throw new IllegalStateException("Only active plans can be deactivated");
        }
        this.status = PlanStatus.DEPRECATED;
        this.deactivatedAt = LocalDateTime.now();
    }

    public String getFullName() {
        return game != null ? game.name + " - " + name : name;
    }
}
