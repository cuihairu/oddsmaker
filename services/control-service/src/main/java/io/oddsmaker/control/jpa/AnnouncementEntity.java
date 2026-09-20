package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 游戏公告：创建/审核/定时发布/定时下线全生命周期。
 * environmentId 为空表示全环境生效。
 */
@Entity
@Table(name = "announcements",
    indexes = {
        @Index(name = "idx_ann_game", columnList = "game_id"),
        @Index(name = "idx_ann_status", columnList = "game_id, status")
    })
public class AnnouncementEntity {

    @Id
    @Column(length = 32)
    public String id;

    @Column(name = "game_id", nullable = false, length = 32)
    public String gameId;

    /** 环境ID；null = 全环境 */
    @Column(name = "environment_id", length = 64)
    public String environmentId;

    @Column(nullable = false, length = 200)
    public String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    public String content;

    /** 展示渠道 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    public Channel channel = Channel.LOBBY;

    /** 展示优先级，数值大者优先 */
    @Column(nullable = false)
    public Integer priority = 0;

    /** 生命周期状态 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    public Status status = Status.DRAFT;

    /** 定时发布时间（SCHEDULED 状态必填） */
    @Column(name = "scheduled_at")
    public LocalDateTime scheduledAt;

    /** 定时下线时间；null = 手动下线 */
    @Column(name = "auto_offline_at")
    public LocalDateTime autoOfflineAt;

    @Column(name = "published_at")
    public LocalDateTime publishedAt;

    @Column(name = "offline_at")
    public LocalDateTime offlineAt;

    @Column(name = "created_by", length = 64)
    public String createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    public LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    public LocalDateTime deletedAt;

    public enum Channel {
        LOBBY,    // 大厅公告
        MARQUEE,  // 跑马灯
        PUSH,     // 推送
        ALL       // 全渠道
    }

    public enum Status {
        DRAFT,      // 草稿
        SCHEDULED,  // 定时待发布
        PUBLISHED,  // 已发布
        OFFLINE     // 已下线
    }

    public boolean isActive() {
        return status == Status.PUBLISHED && deletedAt == null;
    }

    /** 当前时间是否在展示窗口内 */
}
