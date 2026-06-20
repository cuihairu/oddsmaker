package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 社交分析实体
 * 记录社交功能使用和影响力数据
 */
@Entity
@Table(name = "social_analytics")
public class SocialAnalyticsEntity {

    /**
     * 社交事件类型
     */
    public enum SocialEventType {
        FRIEND_ADD,     // 添加好友
        FRIEND_REMOVE,  // 删除好友
        GUILD_JOIN,     // 加入公会
        GUILD_LEAVE,    // 离开公会
        GUILD_CREATE,   // 创建公会
        CHAT_MESSAGE,   // 聊天消息
        GIFT_SEND,      // 发送礼物
        GIFT_RECEIVE,   // 接收礼物
        INVITE_SEND,    // 发送邀请
        INVITE_ACCEPT   // 接受邀请
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public String id;

    @Column(name = "game_id", nullable = false)
    public String gameId;

    @Column(name = "environment", length = 50)
    public String environment;

    @Column(name = "analysis_date", nullable = false)
    public LocalDate analysisDate;

    @Column(name = "social_event_type")
    @Enumerated(EnumType.STRING)
    public SocialEventType socialEventType;

    // 好友系统指标
    @Column(name = "total_friendships", columnDefinition = "BIGINT DEFAULT 0")
    public Long totalFriendships = 0L;

    @Column(name = "new_friendships", columnDefinition = "BIGINT DEFAULT 0")
    public Long newFriendships = 0L;

    @Column(name = "avg_friends_per_user", columnDefinition = "DECIMAL(10,2) DEFAULT 0")
    public Double avgFriendsPerUser = 0.0;

    // 公会指标
    @Column(name = "total_guilds", columnDefinition = "BIGINT DEFAULT 0")
    public Long totalGuilds = 0L;

    @Column(name = "new_guilds", columnDefinition = "BIGINT DEFAULT 0")
    public Long newGuilds = 0L;

    @Column(name = "avg_guild_size", columnDefinition = "DECIMAL(10,2) DEFAULT 0")
    public Double avgGuildSize = 0.0;

    @Column(name = "guild_members", columnDefinition = "BIGINT DEFAULT 0")
    public Long guildMembers = 0L;

    // 社交互动
    @Column(name = "chat_messages", columnDefinition = "BIGINT DEFAULT 0")
    public Long chatMessages = 0L;

    @Column(name = "gifts_sent", columnDefinition = "BIGINT DEFAULT 0")
    public Long giftsSent = 0L;

    @Column(name = "invites_sent", columnDefinition = "BIGINT DEFAULT 0")
    public Long invitesSent = 0L;

    @Column(name = "invites_accepted", columnDefinition = "BIGINT DEFAULT 0")
    public Long invitesAccepted = 0L;

    // 病毒系数
    @Column(name = "viral_coefficient", columnDefinition = "DECIMAL(5,4) DEFAULT 0")
    public Double viralCoefficient = 0.0;

    // 社交对留存的影响
    @Column(name = "social_users_retention_d1", columnDefinition = "DECIMAL(5,4) DEFAULT 0")
    public Double socialUsersRetentionD1 = 0.0;

    @Column(name = "social_users_retention_d7", columnDefinition = "DECIMAL(5,4) DEFAULT 0")
    public Double socialUsersRetentionD7 = 0.0;

    @Column(name = "non_social_users_retention_d1", columnDefinition = "DECIMAL(5,4) DEFAULT 0")
    public Double nonSocialUsersRetentionD1 = 0.0;

    @Column(name = "non_social_users_retention_d7", columnDefinition = "DECIMAL(5,4) DEFAULT 0")
    public Double nonSocialUsersRetentionD7 = 0.0;

    // 维度
    @Column(name = "platform", length = 30)
    public String platform;

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
