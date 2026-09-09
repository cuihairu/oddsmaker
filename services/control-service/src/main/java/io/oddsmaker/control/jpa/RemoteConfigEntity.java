package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 游戏级远程配置：LiveOps 联动的键值下发。
 * environment_id 为空 = 全环境默认；环境特定配置（同 key）覆盖全环境配置。
 * version 每次更新自增，客户端以聚合版本判断增量拉取。
 */
@Entity
@Table(name = "remote_configs",
    uniqueConstraints = @UniqueConstraint(name = "uk_rc_game_env_key",
        columnNames = {"game_id", "environment_id", "config_key"}),
    indexes = @Index(name = "idx_rc_game_env", columnList = "game_id, environment_id, status"))
public class RemoteConfigEntity {

    @Id
    @Column(length = 48)
    public String id;

    @Column(name = "game_id", nullable = false, length = 32)
    public String gameId;

    /** 空 = 全环境 */
    @Column(name = "environment_id", nullable = false, length = 64)
    public String environmentId = "";

    @Column(name = "config_key", nullable = false, length = 128)
    public String configKey;

    /** JSON 值（对象/数组/标量均可） */
    @Column(name = "config_value", nullable = false, columnDefinition = "TEXT")
    public String configValue;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    public Status status = Status.ACTIVE;

    @Column(nullable = false)
    public long version = 1;

    @Column(length = 500)
    public String description;

    @Column(name = "updated_by", length = 64)
    public String updatedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    public LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    public LocalDateTime deletedAt;

    public enum Status {
        ACTIVE,
        INACTIVE
    }
}
