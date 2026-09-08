package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 玩家充值流水（游戏服上报，控制面查询）。
 * (gameId, orderId) 唯一：同一渠道订单重复上报幂等吸收。
 */
@Entity
@Table(name = "player_payments",
    uniqueConstraints = @UniqueConstraint(name = "uk_pp_game_order", columnNames = {"game_id", "order_id"}),
    indexes = @Index(name = "idx_pp_player", columnList = "game_id, player_id"))
public class PlayerPaymentEntity {

    @Id
    @Column(length = 48)
    public String id;

    @Column(name = "game_id", nullable = false, length = 32)
    public String gameId;

    @Column(name = "environment_id", length = 64)
    public String environmentId;

    @Column(name = "player_id", nullable = false, length = 128)
    public String playerId;

    /** 渠道订单号（商店收据/订单ID） */
    @Column(name = "order_id", nullable = false, length = 128)
    public String orderId;

    /** 支付渠道：iap_apple / iap_google / stripe / ... */
    @Column(length = 30)
    public String platform;

    @Column(name = "product_id", length = 100)
    public String productId;

    /** 实付金额（渠道结算货币） */
    @Column(nullable = false, precision = 18, scale = 4)
    public BigDecimal amount;

    @Column(length = 10)
    public String currency;

    /** PENDING / COMPLETED / REFUNDED / FAILED；仅 COMPLETED 计入累计充值 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    public Status status = Status.COMPLETED;

    @Column(name = "paid_at", nullable = false)
    public LocalDateTime paidAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    /** 上报幂等标记（不落库）：重复订单上报返回既有记录时为 true */
    @Transient
    public boolean duplicate;

    public enum Status {
        PENDING,
        COMPLETED,
        REFUNDED,
        FAILED
    }
}
