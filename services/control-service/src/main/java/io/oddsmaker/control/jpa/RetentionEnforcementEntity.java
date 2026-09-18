package io.oddsmaker.control.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * ClickHouse 表 TTL 对账状态（一行一受管表，sweep 全量 upsert）。
 * 状态机见 {@link Status}；actual/desired 为 null 分别表示"表无 TTL"与"无有效配置"。
 */
@Entity
@Table(name = "retention_enforcements")
public class RetentionEnforcementEntity {

    public enum Status {
        /** 表 TTL 与期望一致 */
        IN_SYNC,
        /** 本轮已下发 MODIFY TTL 修正 */
        UPDATING,
        /** 下发 ALTER 失败（下轮重试） */
        FAILED,
        /** 无有效配置（游戏表空），本轮未动作 */
        SKIPPED_NO_CONFIG,
        /** 有 TTL 但 engine_full 解析失败，拒绝盲改防反复 ALTER */
        UNKNOWN
    }

    @Id
    @Column(name = "ch_table", length = 100)
    public String chTable;

    @Column(name = "ttl_column", nullable = false, length = 100)
    public String ttlColumn;

    @Column(name = "actual_days")
    public Integer actualDays;

    @Column(name = "desired_days")
    public Integer desiredDays;

    @Column(nullable = false, length = 24)
    @Enumerated(EnumType.STRING)
    public Status status;

    @Column(name = "last_checked_at", nullable = false)
    public LocalDateTime lastCheckedAt;

    @Column(name = "last_updated_at")
    public LocalDateTime lastUpdatedAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    public String errorMessage;

    @Column(name = "update_count", nullable = false)
    public int updateCount;
}
