package io.oddsmaker.control.dto;

import io.oddsmaker.control.jpa.StorageProfileEntity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 存储路由配置数据传输对象。
 */
public class StorageProfileDTO {

    public String id;

    @NotBlank(message = "存储配置名称不能为空")
    @Size(max = 100, message = "存储配置名称不能超过100个字符")
    public String name;

    @Size(max = 150, message = "显示名称不能超过150个字符")
    public String displayName;

    @Size(max = 500, message = "描述不能超过500个字符")
    public String description;

    public StorageProfileEntity.IsolationStrategy isolationStrategy;

    @Size(max = 100, message = "Kafka集群名称不能超过100个字符")
    public String kafkaCluster;

    @Size(max = 100, message = "ClickHouse集群名称不能超过100个字符")
    public String clickhouseCluster;

    @Size(max = 100, message = "Redis集群名称不能超过100个字符")
    public String redisCluster;

    @Size(max = 200, message = "归档桶名称不能超过200个字符")
    public String archiveBucket;

    public Boolean active;

    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
    public LocalDateTime deletedAt;

    // 统计信息（只读）
    public Integer totalEnvironments;

    public StorageProfileDTO() {}

    public StorageProfileDTO(StorageProfileEntity entity) {
        this.id = entity.id;
        this.name = entity.name;
        this.displayName = entity.displayName;
        this.description = entity.description;
        this.isolationStrategy = entity.isolationStrategy;
        this.kafkaCluster = entity.kafkaCluster;
        this.clickhouseCluster = entity.clickhouseCluster;
        this.redisCluster = entity.redisCluster;
        this.archiveBucket = entity.archiveBucket;
        this.active = entity.active;
        this.createdAt = entity.createdAt;
        this.updatedAt = entity.updatedAt;
        this.deletedAt = entity.deletedAt;
    }

    /**
     * 转换为实体对象
     */
    public StorageProfileEntity toEntity() {
        StorageProfileEntity entity = new StorageProfileEntity();
        entity.id = this.id;
        entity.name = this.name;
        entity.displayName = this.displayName != null ? this.displayName : this.name;
        entity.description = this.description;
        entity.isolationStrategy = this.isolationStrategy != null ? this.isolationStrategy : StorageProfileEntity.IsolationStrategy.SHARED;
        entity.kafkaCluster = this.kafkaCluster;
        entity.clickhouseCluster = this.clickhouseCluster;
        entity.redisCluster = this.redisCluster;
        entity.archiveBucket = this.archiveBucket;
        entity.active = this.active != null ? this.active : Boolean.TRUE;
        return entity;
    }

    /**
     * 更新实体对象
     */
    public void updateEntity(StorageProfileEntity entity) {
        if (this.name != null) entity.name = this.name;
        if (this.displayName != null) entity.displayName = this.displayName;
        if (this.description != null) entity.description = this.description;
        if (this.isolationStrategy != null) entity.isolationStrategy = this.isolationStrategy;
        if (this.kafkaCluster != null) entity.kafkaCluster = this.kafkaCluster;
        if (this.clickhouseCluster != null) entity.clickhouseCluster = this.clickhouseCluster;
        if (this.redisCluster != null) entity.redisCluster = this.redisCluster;
        if (this.archiveBucket != null) entity.archiveBucket = this.archiveBucket;
        if (this.active != null) entity.active = this.active;
    }

    // 业务方法

    public boolean isActive() {
        return Boolean.TRUE.equals(active) && deletedAt == null;
    }

    public boolean isDedicated() {
        return isolationStrategy == StorageProfileEntity.IsolationStrategy.DEDICATED;
    }

    public boolean isProdIsolated() {
        return isolationStrategy == StorageProfileEntity.IsolationStrategy.PROD_ISOLATED;
    }

    public boolean isShared() {
        return isolationStrategy == StorageProfileEntity.IsolationStrategy.SHARED;
    }

    public String getDisplayName() {
        return displayName != null && !displayName.trim().isEmpty() ? displayName : name;
    }
}
