package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 事件字段定义
 * 定义标准事件中每个字段的详细规格
 */
@Entity
@Table(name = "event_fields")
public class EventFieldEntity {

    @Id
    @Column(length = 32)
    public String id;

    @Column(name = "standard_event_id", nullable = false, length = 32)
    public String standardEventId;

    @Column(nullable = false, length = 100)
    public String fieldName;  // 字段名

    @Column(nullable = false, length = 200)
    public String displayName;  // 显示名称

    @Column(length = 1000)
    public String description;  // 描述

    // 类型信息
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public FieldType type = FieldType.STRING;

    @Column(name = "array_element_type", length = 50)
    public String arrayElementType;  // 数组元素类型

    // 约束条件
    @Column(nullable = false)
    public Boolean required = false;

    @Column(length = 50)
    public String defaultValue;  // 默认值

    @Column(name = "allowed_values", columnDefinition = "TEXT")
    public String allowedValues;  // 允许的值（JSON数组）

    @Column(name = "min_value")
    public Double minValue;

    @Column(name = "max_value")
    public Double maxValue;

    @Column(name = "min_length")
    public Integer minLength;

    @Column(name = "max_length")
    public Integer maxLength;

    @Column(name = "regex_pattern", length = 500)
    public String regexPattern;

    // 字段用途
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public FieldPurpose purpose = FieldPurpose.GENERAL;

    @Column(name = "is_dimension")
    public Boolean isDimension = false;  // 是否为维度字段（用于分组）

    @Column(name = "is_metric")
    public Boolean isMetric = false;  // 是否为度量字段（用于聚合）

    // 分组和显示
    @Column(name = "field_group", length = 100)
    public String fieldGroup;  // 字段分组

    @Column(name = "display_order")
    public Integer displayOrder = 0;

    // 状态
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public FieldStatus status = FieldStatus.ACTIVE;

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
    @JoinColumn(name = "standard_event_id", insertable = false, updatable = false)
    public StandardEventEntity standardEvent;

    public enum FieldType {
        STRING,       // 字符串
        INTEGER,      // 整数
        FLOAT,        // 浮点数
        BOOLEAN,      // 布尔值
        ARRAY,        // 数组
        OBJECT,       // 对象
        DATETIME,     // 日期时间
        ENUM,         // 枚举
        JSON,         // JSON
        UUID,         // UUID
        UNKNOWN       // 未知类型
    }

    public enum FieldPurpose {
        GENERAL,      // 通用
        IDENTITY,     // 标识
        TIMING,       // 计时
        REVENUE,      // 收入
        PROGRESSION,  // 进度
        CONTEXT,      // 上下文
        TECHNICAL     // 技术信息
    }

    public enum FieldStatus {
        ACTIVE,       // 活跃
        DEPRECATED,   // 已弃用
        HIDDEN        // 隐藏
    }

    // 业务方法
    public boolean isActive() {
        return status == FieldStatus.ACTIVE && deletedAt == null;
    }

    public boolean isRequired() {
        return Boolean.TRUE.equals(required);
    }

    public boolean isDimension() {
        return Boolean.TRUE.equals(isDimension);
    }

    public boolean isMetric() {
        return Boolean.TRUE.equals(isMetric);
    }

    public boolean isIdentityField() {
        return purpose == FieldPurpose.IDENTITY;
    }

    public boolean isRevenueField() {
        return purpose == FieldPurpose.REVENUE;
    }

    public boolean hasValidation() {
        return minValue != null || maxValue != null ||
               minLength != null || maxLength != null ||
               regexPattern != null || allowedValues != null;
    }

    public boolean isNumeric() {
        return type == FieldType.INTEGER || type == FieldType.FLOAT;
    }

    public boolean isString() {
        return type == FieldType.STRING || type == FieldType.ENUM;
    }
}
