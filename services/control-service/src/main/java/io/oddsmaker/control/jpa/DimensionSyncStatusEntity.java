package io.oddsmaker.control.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 维度同步状态实体（P4 横向）
 * 由维度同步 Agent 在每次成功推送后上报（最后写入胜出），记录同步位点与心跳，
 * 供 /api/dimensions/sync-status 查询每个游戏的同步延迟。设计详见 docs/zh/reference/dimension-sync.md。
 */
@Entity
@Table(name = "dimension_sync_status")
public class DimensionSyncStatusEntity {

    @Id
    @Column(length = 64)
    public String id;

    @Column(name = "game_id", nullable = false, length = 32)
    public String gameId;

    @Column(nullable = false, length = 100)
    public String environment;

    /** 源标识：同一游戏同一环境可有多条同步源（如 items-mysql / levels-csv） */
    @Column(name = "source_key", nullable = false, length = 100)
    public String sourceKey;

    /** 源类型：mysql / postgres / csv */
    @Column(name = "source_type", nullable = false, length = 20)
    public String sourceType;

    /** 同步位点（增量查询水位：自增 ID 或时间戳，Agent 侧 checkpoint 的服务端副本） */
    @Column(columnDefinition = "TEXT")
    public String cursor;

    /** 源头最新一条变更的版本时间（version_ts，用于判断源头新鲜度） */
    @Column(name = "last_event_ts")
    public LocalDateTime lastEventTs;

    /** Agent 最近一次成功推送时间（服务端收到上报时打点，即心跳） */
    @Column(name = "last_push_at")
    public LocalDateTime lastPushAt;

    @Column(name = "pushed_count", nullable = false)
    public Long pushedCount = 0L;

    @Column(name = "error_count", nullable = false)
    public Long errorCount = 0L;

    @Column(name = "last_error", columnDefinition = "TEXT")
    public String lastError;

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @Column(nullable = false)
    public LocalDateTime updatedAt;
}
