package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 统一身份实体
 * 将设备、用户、玩家、角色等不同标识符合并为一个统一身份
 */
@Entity
@Table(name = "identities")
public class IdentityEntity {

    @Id
    @Column(length = 32)
    public String id;

    @Column(name = "game_id", nullable = false, length = 32)
    public String gameId;

    @Column(name = "environment_id", length = 32)
    public String environmentId;  // null表示跨环境

    // 主标识类型
    @Enumerated(EnumType.STRING)
    @Column(name = "primary_identity_type", nullable = false)
    public IdentityType primaryIdentityType = IdentityType.DEVICE;

    // 主要ID
    @Column(name = "primary_id", nullable = false, length = 200)
    public String primaryId;  // 根据类型存储：device_id, user_id, player_id

    // 用户信息
    @Column(name = "user_id", length = 100)
    public String userId;  // 账号ID（如登录后绑定）

    @Column(name = "player_id", length = 100)
    public String playerId;  // 玩家ID（游戏内角色）

    @Column(name = "character_id", length = 100)
    public String characterId;  // 角色ID（支持多角色）

    // 设备信息
    @Column(name = "device_id", length = 200)
    public String deviceId;

    @Column(name = "device_type", length = 50)
    public String deviceType;  // ios, android, web, pc

    // 状态
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public IdentityStatus status = IdentityStatus.ACTIVE;

    // 统计
    @Column(name = "first_seen_at")
    public LocalDateTime firstSeenAt;  // 首次出现时间

    @Column(name = "last_seen_at")
    public LocalDateTime lastSeenAt;  // 最后出现时间

    @Column(name = "session_count")
    public Integer sessionCount = 0;  // 会话数

    @Column(name = "event_count")
    public Long eventCount = 0L;  // 事件数

    // 合并信息
    @Column(name = "merged_from", columnDefinition = "TEXT")
    public String mergedFrom;  // JSON数组：合并来源ID列表

    @Column(name = "merge_reason", length = 200)
    public String mergeReason;  // 合并原因

    @Column(name = "confidence_score")
    public Double confidenceScore = 1.0;  // 合并置信度（0-1）

    // 属性
    @Column(name = "attributes", columnDefinition = "TEXT")
    public String attributes;  // JSON格式的额外属性

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
    @JoinColumn(name = "game_id", insertable = false, updatable = false)
    public GameEntity game;

    @OneToMany(mappedBy = "identity", fetch = FetchType.LAZY)
    public List<IdentityLinkEntity> links;

    public enum IdentityType {
        DEVICE,       // 设备为主
        USER,         // 用户为主
        PLAYER,       // 玩家为主
        CHARACTER,    // 角色为主
        MERGED        // 已合并
    }

    public enum IdentityStatus {
        ACTIVE,       // 活跃
        INACTIVE,     // 非活跃
        MERGED,       // 已合并
        DELETED       // 已删除
    }

    // 业务方法
    public boolean isActive() {
        return status == IdentityStatus.ACTIVE && deletedAt == null;
    }

    public boolean isMerged() {
        return status == IdentityStatus.MERGED;
    }

    public boolean hasUserId() {
        return userId != null && !userId.isEmpty();
    }

    public boolean hasPlayerId() {
        return playerId != null && !playerId.isEmpty();
    }

    public boolean isBoundToUser() {
        return hasUserId();
    }

    public long getDaysSinceFirstSeen() {
        if (firstSeenAt == null) return 0;
        return java.time.temporal.ChronoUnit.DAYS.between(firstSeenAt, LocalDateTime.now());
    }

    public long getDaysSinceLastSeen() {
        if (lastSeenAt == null) return 0;
        return java.time.temporal.ChronoUnit.DAYS.between(lastSeenAt, LocalDateTime.now());
    }

    public boolean isInactive(int inactiveDays) {
        return getDaysSinceLastSeen() > inactiveDays;
    }

    public String getDisplayId() {
        return switch (primaryIdentityType) {
            case USER -> userId != null ? userId : primaryId;
            case PLAYER -> playerId != null ? playerId : primaryId;
            case CHARACTER -> characterId != null ? characterId : primaryId;
            default -> deviceId != null ? deviceId : primaryId;
        };
    }

    public void recordActivity() {
        lastSeenAt = LocalDateTime.now();
        eventCount = (eventCount != null ? eventCount : 0) + 1;
    }

    public void incrementSessionCount() {
        sessionCount = (sessionCount != null ? sessionCount : 0) + 1;
        lastSeenAt = LocalDateTime.now();
    }
}
