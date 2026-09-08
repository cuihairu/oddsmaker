package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 玩家登录日志（游戏服上报，控制面查询）。
 */
@Entity
@Table(name = "player_login_logs",
    indexes = {
        @Index(name = "idx_pll_player", columnList = "game_id, player_id, login_at"),
        @Index(name = "idx_pll_device", columnList = "device_id")
    })
public class PlayerLoginLogEntity {

    @Id
    @Column(length = 48)
    public String id;

    @Column(name = "game_id", nullable = false, length = 32)
    public String gameId;

    @Column(name = "environment_id", length = 64)
    public String environmentId;

    @Column(name = "player_id", nullable = false, length = 128)
    public String playerId;

    @Column(name = "device_id", length = 200)
    public String deviceId;

    /** ios / android / web / pc */
    @Column(name = "device_type", length = 50)
    public String deviceType;

    @Column(length = 50)
    public String platform;

    @Column(name = "app_version", length = 50)
    public String appVersion;

    @Column(name = "ip_address", length = 50)
    public String ipAddress;

    @Column(length = 10)
    public String country;

    @Column(name = "login_at", nullable = false)
    public LocalDateTime loginAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;
}
