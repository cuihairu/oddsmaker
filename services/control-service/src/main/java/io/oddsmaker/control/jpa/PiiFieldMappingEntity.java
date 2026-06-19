package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * PII字段映射：定义哪些字段是PII以及如何处理
 */
@Entity
@Table(name = "pii_field_mappings")
public class PiiFieldMappingEntity {

    @Id
    @Column(length = 32)
    public String id;

    @Column(name = "privacy_policy_id", nullable = false, length = 32)
    public String privacyPolicyId;

    @Column(name = "field_name", nullable = false, length = 100)
    public String fieldName;      // 字段名，如 "user_id", "email"

    @Column(name = "field_type", length = 50)
    public String fieldType;      // 字段类型

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public PiiCategory piiCategory = PiiCategory.PERSONAL;  // PII类别

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public PiiSensitivity piiSensitivity = PiiSensitivity.MEDIUM;  // 敏感度

    // 处理规则
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public PiiHandling handling = PiiHandling.MASK;  // 处理方式

    @Column(name = "mask_pattern", length = 200)
    public String maskPattern;   // 脱敏模式，如 "email", "phone", "***"

    @Column(name = "retention_days")
    public Integer retentionDays;  // 特殊保留天数

    @Column(name = "enable_anonymization")
    public Boolean enableAnonymization = true;  // 启用匿名化

    @Column(name = "anonymization_method", length = 50)
    public String anonymizationMethod = "hash";  // 匿名化方法

    // 状态
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public MappingStatus status = MappingStatus.ACTIVE;

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
    @JoinColumn(name = "privacy_policy_id", insertable = false, updatable = false)
    public PrivacyPolicyEntity privacyPolicy;

    public enum PiiCategory {
        PERSONAL,      // 个人信息
        CONTACT,       // 联系方式
        IDENTITY,      // 身份证明
        FINANCIAL,     // 财务信息
        HEALTH,        // 健康信息
        LOCATION,      // 位置信息
        BEHAVIORAL,    // 行为数据
        TECHNICAL      // 技术信息
    }

    public enum PiiSensitivity {
        LOW,           // 低敏感度
        MEDIUM,        // 中敏感度
        HIGH,          // 高敏感度
        CRITICAL       // 极高敏感度
    }

    public enum PiiHandling {
        NONE,          // 不处理
        MASK,          // 脱敏
        REMOVE,        // 删除
        HASH,          // 哈希
        ENCRYPT        // 加密
    }

    public enum MappingStatus {
        ACTIVE,        // 活跃
        DISABLED,      // 已禁用
        DEPRECATED     // 已弃用
    }

    // 业务方法
    public boolean isActive() {
        return status == MappingStatus.ACTIVE && deletedAt == null;
    }

    public boolean isHighlySensitive() {
        return piiSensitivity == PiiSensitivity.HIGH || piiSensitivity == PiiSensitivity.CRITICAL;
    }

    public boolean requiresEncryption() {
        return handling == PiiHandling.ENCRYPT && isHighlySensitive();
    }

    public int getEffectiveRetentionDays(Integer defaultRetention) {
        return retentionDays != null ? retentionDays : defaultRetention;
    }
}
