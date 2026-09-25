package io.oddsmaker.control.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 用户分群实体（P7-2 可复用用户分群）。
 *
 * 定义（definition JSON）一次、处处可用：报表过滤 / LiveOps 定向。
 * 成员不在本表——物化在 ClickHouse segment_members（主体口径与平台一致）。
 */
@Entity
@Table(name = "segments", indexes = @Index(name = "idx_segments_game", columnList = "game_id"))
public class SegmentEntity {

    public enum SegmentStatus {
        ACTIVE,    // 可用于过滤与定向
        INACTIVE   // 保留定义但不出现在过滤入口
    }

    /** 分群主体：player=player_id>user_id>device_id 兜底；device=纯设备口径 */
    public enum SegmentSubject {
        PLAYER, DEVICE
    }

    @Id
    @Column(length = 64)
    public String id;

    @jakarta.persistence.PrePersist
    void ensureId() {
        if (id == null || id.isEmpty()) {
            id = java.util.UUID.randomUUID().toString().replace("-", "");
        }
    }

    @Column(name = "game_id", nullable = false, length = 32)
    public String gameId;

    /** 物化环境（分群按环境独立计算与使用） */
    @Column(nullable = false, length = 100)
    public String environment;

    @Column(nullable = false, length = 100)
    public String name;

    @Column(name = "display_name", length = 200)
    public String displayName;

    @Column(length = 1000)
    public String description;

    /** SegmentService 校验并规范化的定义 JSON（match/conditions） */
    @Column(nullable = false, columnDefinition = "TEXT")
    public String definition;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    public SegmentSubject subject = SegmentSubject.PLAYER;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    public SegmentStatus status = SegmentStatus.ACTIVE;

    /** 最近一次物化的成员数（0=从未物化） */
    @Column(name = "member_count", nullable = false)
    public long memberCount;

    @Column(name = "last_computed_at")
    public LocalDateTime lastComputedAt;

    @Column(name = "created_by", length = 64)
    public String createdBy;

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    public LocalDateTime updatedAt = LocalDateTime.now();

    @Column(name = "deleted_at")
    public LocalDateTime deletedAt;
}
