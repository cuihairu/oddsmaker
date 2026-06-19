package io.oddsmaker.control.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 存储路由配置。
 * 逻辑环境通过 storage profile 绑定到具体的数据面资源。
 */
@Entity
@Table(name = "storage_profiles")
public class StorageProfileEntity {

    @Id
    @Column(length = 64)
    public String id;

    @Column(nullable = false, unique = true, length = 100)
    public String name;

    @Column(name = "display_name", length = 150)
    public String displayName;

    @Column(length = 500)
    public String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "isolation_strategy", nullable = false, length = 32)
    public IsolationStrategy isolationStrategy = IsolationStrategy.SHARED;

    @Column(name = "kafka_cluster", length = 100)
    public String kafkaCluster;

    @Column(name = "clickhouse_cluster", length = 100)
    public String clickhouseCluster;

    @Column(name = "redis_cluster", length = 100)
    public String redisCluster;

    @Column(name = "archive_bucket", length = 200)
    public String archiveBucket;

    @Column(name = "is_active", nullable = false)
    public Boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    public LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    public LocalDateTime deletedAt;

    @OneToMany(mappedBy = "storageProfile", fetch = FetchType.LAZY)
    public List<GameEnvironmentEntity> environments;

    public enum IsolationStrategy {
        SHARED,
        PROD_ISOLATED,
        DEDICATED
    }

    public boolean isActive() {
        return Boolean.TRUE.equals(active) && deletedAt == null;
    }

    public boolean isDedicated() {
        return isolationStrategy == IsolationStrategy.DEDICATED;
    }
}
