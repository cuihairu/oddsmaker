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
    public Boolean rejectUnknownEvents = true; // 是否拒绝未定义的事件（B7 §2.3 默认收口 true，dev 环境网关豁免）

    // ===== B7 EventSchema 一等资源字段（计划书 §2.3/§4.4） =====

    /** 版本兼容策略：publish 时对同 game+env 基线 ACTIVE 版本做事件集兼容检查（NONE 不检查） */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public Compatibility compatibility = Compatibility.NONE;

    /** 事件级 PII 策略（JSON：{email,phone,ip}），优先级 环境级 Schema > ApiKey > 网关默认（§4.4） */
    @Column(name = "pii_policy", columnDefinition = "TEXT")
    public String piiPolicy;

    /** 该 Schema 事件数据的保留天数覆盖（null=继承游戏/环境配置） */
    @Column(name = "retention_days")
    public Integer retentionDays;

    /** 采样率覆盖（0-1，null=继承环境采样配置） */
    @Column(name = "sampling_rate", precision = 3, scale = 2)
    public java.math.BigDecimal samplingRate;

    /** 归属（负责人/组，审计与协作用） */
    @Column(name = "owner_id", length = 64)
    public String ownerId;

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

    /**
     * 版本兼容策略（B7 §2.3）：publish 时按此对基线版本做事件集兼容检查——
     * BACKWARD=新版本须覆盖旧版本全部事件（removed 为空，否则旧生产者事件被拒收）、
     * FORWARD=新增对旧消费方可见可控（added 为空）、FULL=两者、NONE=不检查。
     */
    public enum Compatibility {
        NONE, BACKWARD, FORWARD, FULL
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
