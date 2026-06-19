package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 事件定义：追踪计划中的单个事件规格定义
 * 定义事件的名称、类型、必需字段、验证规则等
 */
@Entity
@Table(name = "event_definitions")
public class EventDefinitionEntity {

    @Id
    @Column(length = 32)
    public String id;

    @Column(name = "tracking_plan_id", nullable = false, length = 32)
    public String trackingPlanId;

    @Column(nullable = false, length = 100)
    public String eventName;      // 事件名称，如 "level_complete"

    @Column(name = "event_type", nullable = false, length = 50)
    public String eventType;      // 事件类型：session, user, business, resource, progression, design, error, ad, risk

    @Column(name = "display_name", length = 200)
    public String displayName;   // 显示名称

    @Column(name = "description", length = 1000)
    public String description;    // 事件描述

    // 事件分类
    @Column(name = "category", length = 100)
    public String category;       // 分类，如 "monetization", "engagement"

    @Column(name = "subcategory", length = 100)
    public String subcategory;    // 子分类

    // 标识
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public EventIdentity identity = EventIdentity.DEVICE;  // 主要标识类型

    // 必需字段
    @Column(name = "require_user_id")
    public Boolean requireUserId = false;

    @Column(name = "require_session_id")
    public Boolean requireSessionId = false;

    @Column(name = "require_player_id")
    public Boolean requirePlayerId = false;

    // 验证规则
    @Column(name = "validation_rules", columnDefinition = "TEXT")
    public String validationRules;  // JSON格式的验证规则

    // 示例数据
    @Column(name = "example_payload", columnDefinition = "TEXT")
    public String examplePayload;   // JSON格式的示例事件数据

    // 文档链接
    @Column(name = "doc_url", length = 500)
    public String docUrl;           // 详细文档链接

    // 业务重要性
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public Importance importance = Importance.NORMAL;

    // 状态
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public DefinitionStatus status = DefinitionStatus.ACTIVE;

    // 统计
    @Column(name = "usage_count")
    public Long usageCount = 0L;    // 使用次数统计

    @Column(name = "last_used_at")
    public LocalDateTime lastUsedAt; // 最后使用时间

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
    @JoinColumn(name = "tracking_plan_id", insertable = false, updatable = false)
    public TrackingPlanEntity trackingPlan;

    @OneToMany(mappedBy = "eventDefinition", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    public List<EventPropertyDefinitionEntity> properties;

    public enum EventIdentity {
        ANONYMOUS,    // 仅设备ID
        DEVICE,       // 设备ID优先
        USER,         // 用户ID优先
        PLAYER,       // 玩家ID优先（游戏内角色）
        SESSION       // 会话必需
    }

    public enum Importance {
        CRITICAL,     // 核心事件，缺失严重影响分析
        HIGH,         // 重要事件
        NORMAL,       // 普通事件
        LOW,          // 可选事件
        DEPRECATED    // 已弃用
    }

    public enum DefinitionStatus {
        ACTIVE,       // 活跃
        DISABLED,     // 已禁用
        DEPRECATED    // 已弃用
    }

    // 业务方法
    public boolean isActive() {
        return status == DefinitionStatus.ACTIVE && deletedAt == null;
    }

    public boolean isRequired() {
        return importance == Importance.CRITICAL || importance == Importance.HIGH;
    }

    public boolean isDeprecated() {
        return status == DefinitionStatus.DEPRECATED ||
               importance == Importance.DEPRECATED ||
               deletedAt != null;
    }

    public String getFullEventName() {
        return trackingPlan != null && trackingPlan.game != null
            ? trackingPlan.game.id + "." + eventName
            : eventName;
    }

    public boolean hasRequiredIdentity() {
        return requireUserId || requireSessionId || requirePlayerId;
    }
}
