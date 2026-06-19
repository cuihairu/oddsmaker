package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 隐私策略：定义PII数据的处理规则
 * 控制哪些字段是PII、如何脱敏、保留多久等
 */
@Entity
@Table(name = "privacy_policies")
public class PrivacyPolicyEntity {

    @Id
    @Column(length = 32)
    public String id;

    @Column(name = "game_id", nullable = false, length = 32)
    public String gameId;

    @Column(name = "environment_id", length = 32)
    public String environmentId;  // null表示全局策略

    @Column(nullable = false, length = 100)
    public String name;

    @Column(name = "display_name", length = 200)
    public String displayName;

    @Column(name = "description", length = 1000)
    public String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public PolicyStatus status = PolicyStatus.ACTIVE;

    // PII处理规则
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public PiiHandling piiHandling = PiiHandling.MASK;  // PII处理方式

    @Column(name = "retention_days")
    public Integer retentionDays;  // 保留天数

    @Column(name = "enable_pii_detection")
    public Boolean enablePiiDetection = true;  // 自动PII检测

    @Column(name = "mask_method", length = 50)
    public String maskMethod = "sha256";  // 脱敏方法：sha256, md5, remove, partial

    @Column(name = "hash_salt", length = 100)
    public String hashSalt;  // 哈希盐值

    // GDPR相关
    @Column(name = "enable_gdpr")
    public Boolean enableGdpr = false;  // 启用GDPR合规

    @Column(name = "data_deletion_days")
    public Integer dataDeletionDays;  // 删除请求后的删除天数

    @Column(name = "right_to_deletion")
    public Boolean rightToDeletion = true;  // 支持删除权

    // 统计
    @Column(name = "total_masked_count")
    public Long totalMaskedCount = 0L;  // 总脱敏记录数

    @Column(name = "last_masked_at")
    public LocalDateTime lastMaskedAt;  // 最后脱敏时间

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
    @JoinColumn(name = "game_id", insertable = false, updatable = false)
    public GameEntity game;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "environment_id", insertable = false, updatable = false)
    public GameEnvironmentEntity environment;

    @OneToMany(mappedBy = "privacyPolicy", fetch = FetchType.LAZY)
    public List<PiiFieldMappingEntity> fieldMappings;

    public enum PolicyStatus {
        ACTIVE,       // 活跃
        DISABLED,     // 已禁用
        ARCHIVED      // 已归档
    }

    public enum PiiHandling {
        NONE,         // 不处理
        MASK,         // 脱敏/哈希
        REMOVE,       // 删除
        ENCRYPT       // 加密
    }

    // 业务方法
    public boolean isActive() {
        return status == PolicyStatus.ACTIVE && deletedAt == null;
    }

    public boolean isGlobal() {
        return environmentId == null;
    }

    public boolean isGdprEnabled() {
        return Boolean.TRUE.equals(enableGdpr);
    }

    public boolean shouldMask() {
        return piiHandling == PiiHandling.MASK || piiHandling == PiiHandling.ENCRYPT;
    }

    public boolean shouldRemove() {
        return piiHandling == PiiHandling.REMOVE;
    }

    public int getEffectiveRetentionDays() {
        return retentionDays != null ? retentionDays : 90;  // 默认90天
    }
}
