package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 标准事件类型定义
 * 定义游戏分析中的标准事件类型及其结构
 */
@Entity
@Table(name = "standard_event_types")
public class StandardEventTypeEntity {

    @Id
    @Column(length = 32)
    public String id;

    @Column(nullable = false, length = 50, unique = true)
    public String code;  // 事件类型代码：session, user, business, resource, progression, design, error, ad, risk

    @Column(nullable = false, length = 200)
    public String name;  // 事件类型名称

    @Column(length = 1000)
    public String description;  // 描述

    @Column(name = "category", length = 50)
    public String category;  // 分类：lifecycle, engagement, monetization, progression, system

    @Column(name = "display_order")
    public Integer displayOrder = 0;  // 显示顺序

    // 是否为系统核心事件
    @Column(name = "is_core")
    public Boolean isCore = false;

    // 统计相关
    @Column(name = "enable_aggregation")
    public Boolean enableAggregation = true;  // 是否启用聚合

    @Column(name = "aggregation_window")
    public String aggregationWindow = "1d";  // 聚合窗口：1h, 1d, 7d, 30d

    // 保留策略
    @Column(name = "retention_days")
    public Integer retentionDays = 90;  // 数据保留天数

    // 示例事件
    @Column(name = "example_events", columnDefinition = "TEXT")
    public String exampleEvents;  // JSON数组：示例事件列表

    // 必需字段
    @Column(name = "required_fields", columnDefinition = "TEXT")
    public String requiredFields;  // JSON数组：必需字段列表

    // 可选字段
    @Column(name = "optional_fields", columnDefinition = "TEXT")
    public String optionalFields;  // JSON数组：可选字段列表

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
    @OneToMany(mappedBy = "eventType", fetch = FetchType.LAZY)
    public java.util.List<StandardEventEntity> standardEvents;

    public enum EventStatus {
        ACTIVE,       // 活跃
        DEPRECATED,   // 已弃用
        HIDDEN        // 隐藏
    }

    // 业务方法
    public boolean isActive() {
        return status == EventStatus.ACTIVE && deletedAt == null;
    }

    public boolean isCoreEvent() {
        return Boolean.TRUE.equals(isCore);
    }

    public boolean supportsAggregation() {
        return Boolean.TRUE.equals(enableAggregation);
    }

    public String getCategory() {
        if (category != null) return category;

        // 根据代码推断分类
        return switch (code) {
            case "session", "user" -> "lifecycle";
            case "business" -> "monetization";
            case "progression", "design" -> "progression";
            case "error", "risk" -> "system";
            default -> "engagement";
        };
    }
}
