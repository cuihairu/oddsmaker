package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 事件属性定义：定义事件中每个属性的规格
 * 包含属性名称、类型、是否必需、默认值、验证规则等
 */
@Entity
@Table(name = "event_property_definitions")
public class EventPropertyDefinitionEntity {

    @Id
    @Column(length = 32)
    public String id;

    @Column(name = "event_definition_id", nullable = false, length = 32)
    public String eventDefinitionId;

    @Column(name = "property_name", nullable = false, length = 100)
    public String propertyName;    // 属性名称

    @Column(name = "display_name", length = 200)
    public String displayName;   // 显示名称

    @Column(name = "description", length = 500)
    public String description;    // 属性描述

    // 类型定义
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public PropertyType type = PropertyType.STRING;  // 属性数据类型

    @Column(name = "array_element_type", length = 50)
    public String arrayElementType;  // 数组元素类型（当type=ARRAY时）

    // 约束条件
    @Column(nullable = false)
    public Boolean required = false;  // 是否必需

    @Column(length = 50)
    public String defaultValue;       // 默认值

    @Column(name = "allowed_values", columnDefinition = "TEXT")
    public String allowedValues;      // 允许的值列表（JSON数组）

    // 验证规则
    @Column(name = "min_value")
    public Double minValue;          // 最小值（数值类型）

    @Column(name = "max_value")
    public Double maxValue;          // 最大值（数值类型）

    @Column(name = "min_length")
    public Integer minLength;        // 最小长度（字符串类型）

    @Column(name = "max_length")
    public Integer maxLength;        // 最大长度（字符串类型）

    @Column(name = "regex_pattern", length = 500)
    public String regexPattern;      // 正则表达式验证

    // 语义信息
    @Column(name = "is_pii")
    public Boolean isPii = false;     // 是否为PII数据

    @Column(name = "pii_type", length = 50)
    public String piiType;           // PII类型：email, phone, username等

    @Column(name = "is_indexed")
    public Boolean isIndexed = true;  // 是否需要索引

    // 分组
    @Column(name = "property_group", length = 100)
    public String propertyGroup;     // 属性分组，如 "context", "params"

    @Column(name = "display_order")
    public Integer displayOrder = 0; // 显示顺序

    // 状态
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public PropertyStatus status = PropertyStatus.ACTIVE;

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
    @JoinColumn(name = "event_definition_id", insertable = false, updatable = false)
    public EventDefinitionEntity eventDefinition;

    public enum PropertyType {
        STRING,       // 字符串
        INTEGER,      // 整数
        FLOAT,        // 浮点数
        BOOLEAN,      // 布尔值
        ARRAY,        // 数组
        OBJECT,       // 对象（JSON）
        DATETIME,     // 日期时间
        ENUM,         // 枚举
        UNKNOWN       // 未知类型
    }

    public enum PropertyStatus {
        ACTIVE,       // 活跃
        DISABLED,     // 已禁用
        DEPRECATED    // 已弃用
    }

    // 业务方法
    public boolean isActive() {
        return status == PropertyStatus.ACTIVE && deletedAt == null;
    }

    public boolean isNumeric() {
        return type == PropertyType.INTEGER || type == PropertyType.FLOAT;
    }

    public boolean isString() {
        return type == PropertyType.STRING || type == PropertyType.ENUM;
    }

    public boolean hasValidation() {
        return minValue != null || maxValue != null ||
               minLength != null || maxLength != null ||
               regexPattern != null || allowedValues != null;
    }

    public String getValidationDescription() {
        StringBuilder sb = new StringBuilder();
        if (required) sb.append("Required");
        if (minValue != null) sb.append(", Min=").append(minValue);
        if (maxValue != null) sb.append(", Max=").append(maxValue);
        if (minLength != null) sb.append(", MinLen=").append(minLength);
        if (maxLength != null) sb.append(", MaxLen=").append(maxLength);
        if (regexPattern != null) sb.append(", Pattern");
        if (allowedValues != null) sb.append(", Enum");
        return sb.toString();
    }

    public boolean isSensitive() {
        return isPii != null && isPii;
    }
}
