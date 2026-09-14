package io.oddsmaker.control.jpa;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 系统配置实体
 * 管理系统级别的配置项
 */
@Entity
@Table(name = "system_configs")
public class SystemConfigEntity {

    /**
     * 配置类型
     */
    public enum ConfigType {
        SYSTEM,          // 系统配置
        SECURITY,        // 安全配置
        PERFORMANCE,     // 性能配置
        INTEGRATION,     // 集成配置
        NOTIFICATION,    // 通知配置
        RETENTION,       // 保留策略
        LIMIT,           // 限制配置
        FEATURE,         // 功能配置
        CUSTOM           // 自定义配置
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public String id;

    @Column(name = "config_key", nullable = false, length = 100, unique = true)
    public String configKey;  // 配置键

    @Column(name = "config_type", nullable = false)
    @Enumerated(EnumType.STRING)
    public ConfigType configType = ConfigType.CUSTOM;

    @Column(name = "config_value", columnDefinition = "TEXT")
    public String configValue;  // 配置值

    @Column(name = "default_value", columnDefinition = "TEXT")
    public String defaultValue;  // 默认值

    @Column(name = "value_type", length = 20)
    public String valueType;  // 值类型：string, integer, boolean, json

    @Column(name = "description", length = 500)
    public String description;  // 配置描述

    @Column(name = "category", length = 100)
    public String category;  // 配置分类

    @Column(name = "is_sensitive", columnDefinition = "BOOLEAN")
    public Boolean isSensitive = false;  // 是否为敏感信息

    @Column(name = "is_encrypted", columnDefinition = "BOOLEAN")
    public Boolean isEncrypted = false;  // 是否加密存储

    @Column(name = "is_public", columnDefinition = "BOOLEAN")
    public Boolean isPublic = false;  // 是否可公开访问

    @Column(name = "is_readonly", columnDefinition = "BOOLEAN")
    public Boolean isReadonly = false;  // 是否只读

    @Column(name = "requires_restart", columnDefinition = "BOOLEAN")
    public Boolean requiresRestart = false;  // 是否需要重启生效

    @Column(name = "validation_regex", length = 500)
    public String validationRegex;  // 验证正则表达式

    @Column(name = "min_value")
    public String minValue;  // 最小值

    @Column(name = "max_value")
    public String maxValue;  // 最大值

    @Column(name = "allowed_values", columnDefinition = "TEXT")
    public String allowedValues;  // JSON格式的允许值列表

    @Column(name = "version", columnDefinition = "INTEGER")
    public Integer version = 1;  // 配置版本

    @Column(name = "last_modified_by", length = 64)
    public String lastModifiedBy;  // 最后修改人

    @Column(name = "last_modified_at")
    public LocalDateTime lastModifiedAt;  // 最后修改时间

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @Column(name = "deleted_at")
    public LocalDateTime deletedAt;

    // 辅助方法

    public boolean isActive() {
        return deletedAt == null;
    }

    public boolean isBoolean() {
        return "boolean".equals(valueType);
    }

    public boolean isInteger() {
        return "integer".equals(valueType);
    }

    public boolean isString() {
        return "string".equals(valueType);
    }

    public boolean isJson() {
        return "json".equals(valueType);
    }

    public Boolean getBooleanValue() {
        if (isBoolean() && configValue != null) {
            return Boolean.valueOf(configValue);
        }
        return null;
    }

    public Integer getIntegerValue() {
        if (isInteger() && configValue != null) {
            return Integer.valueOf(configValue);
        }
        return null;
    }

    public String getStringValue() {
        if (configValue != null) {
            return isEncrypted ? "[ENCRYPTED]" : configValue;
        }
        return null;
    }

    @JsonIgnore
    public String getRawValue() {
        return configValue;
    }

    public void setStringValue(String value) {
        this.configValue = value;
        this.version++;
        this.lastModifiedAt = LocalDateTime.now();
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (configValue == null && defaultValue != null) {
            configValue = defaultValue;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        if (lastModifiedAt == null) {
            lastModifiedAt = LocalDateTime.now();
        }
    }
}
