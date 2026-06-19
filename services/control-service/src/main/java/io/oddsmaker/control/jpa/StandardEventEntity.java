package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 标准事件定义
 * 定义每个标准事件类型下的具体事件及其结构
 */
@Entity
@Table(name = "standard_events")
public class StandardEventEntity {

    @Id
    @Column(length = 32)
    public String id;

    @Column(name = "event_type_id", nullable = false, length = 32)
    public String eventTypeId;

    @Column(nullable = false, length = 100)
    public String eventName;  // 事件名称：session_start, level_complete, purchase

    @Column(nullable = false, length = 200)
    public String displayName;  // 显示名称

    @Column(length = 1000)
    public String description;  // 描述

    // 分类和用途
    @Column(name = "subcategory", length = 100)
    public String subcategory;  // 子分类

    @Column(name = "use_case", length = 100)
    public String useCase;  // 使用场景：retention, monetization, engagement

    // 优先级和重要性
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public EventImportance importance = EventImportance.NORMAL;

    @Column(name = "display_order")
    public Integer displayOrder = 0;

    // 字段定义
    @Column(name = "required_fields", columnDefinition = "TEXT")
    public String requiredFields;  // JSON数组：必需字段

    @Column(name = "optional_fields", columnDefinition = "TEXT")
    public String optionalFields;  // JSON数组：可选字段

    @Column(name = "recommended_fields", columnDefinition = "TEXT")
    public String recommendedFields;  // JSON数组：推荐字段

    // 示例数据
    @Column(name = "example_payload", columnDefinition = "TEXT")
    public String examplePayload;  // JSON示例

    // 分析配置
    @Column(name = "enable_funnel")
    public Boolean enableFunnel = false;  // 是否用于漏斗分析

    @Column(name = "enable_retention")
    public Boolean enableRetention = false;  // 是否用于留存分析

    @Column(name = "enable_cohort")
    public Boolean enableCohort = false;  // 是否用于队列分析

    @Column(name = "enable_revenue")
    public Boolean enableRevenue = false;  // 是否涉及收入

    // 状态
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public EventStatus status = EventStatus.ACTIVE;

    // 时间戳
    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    public LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    public LocalDateTime deletedAt;

    // 关联关系
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_type_id", insertable = false, updatable = false)
    public StandardEventTypeEntity eventType;

    @OneToMany(mappedBy = "standardEvent", fetch = FetchType.LAZY)
    public List<EventFieldEntity> fields;

    public enum EventImportance {
        CRITICAL,     // 核心事件，强烈推荐
        HIGH,         // 重要事件
        NORMAL,       // 普通事件
        LOW,          // 可选事件
        DEPRECATED    // 已弃用
    }

    public enum EventStatus {
        ACTIVE,       // 活跃
        DEPRECATED,   // 已弃用
        HIDDEN        // 隐藏
    }

    // 业务方法
    public boolean isActive() {
        return status == EventStatus.ACTIVE && deletedAt == null;
    }

    public boolean isCritical() {
        return importance == EventImportance.CRITICAL;
    }

    public boolean isImportant() {
        return importance == EventImportance.CRITICAL || importance == EventImportance.HIGH;
    }

    public boolean supportsFunnel() {
        return Boolean.TRUE.equals(enableFunnel);
    }

    public boolean supportsRetention() {
        return Boolean.TRUE.equals(enableRetention);
    }

    public boolean supportsCohort() {
        return Boolean.TRUE.equals(enableCohort);
    }

    public boolean isRevenueEvent() {
        return Boolean.TRUE.equals(enableRevenue);
    }

    public String getFullEventName() {
        return eventType != null ? eventType.code + "." + eventName : eventName;
    }
}
