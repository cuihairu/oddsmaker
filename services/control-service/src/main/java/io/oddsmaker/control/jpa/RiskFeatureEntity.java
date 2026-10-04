package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 风控特征实体（B5 Feature 层，计划书 §5.1）
 * risk-job 特征作业分支按窗口聚合产出，规则评估从本表取值（事件→特征→规则三段解耦）。
 * scope_key 编码主体：PLAYER:&lt;user_id&gt; / DEVICE:&lt;device_id&gt; / IP:&lt;client_ip&gt;。
 */
@Entity
@Table(name = "risk_features")
public class RiskFeatureEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "game_id", nullable = false, length = 32)
    public String gameId;

    @Column(nullable = false, length = 100)
    public String environment;

    @Column(name = "scope_key", nullable = false, length = 256)
    public String scopeKey;

    @Column(name = "feature_name", nullable = false, length = 100)
    public String featureName;

    @Column(name = "window_start", nullable = false)
    public LocalDateTime windowStart;

    @Column(name = "window_end", nullable = false)
    public LocalDateTime windowEnd;

    /** 特征值：计数/求和/比率统一按 double 存 */
    @Column(nullable = false)
    public double value;

    /** 本行产出（upsert）时刻 */
    @Column(name = "as_of", nullable = false)
    public LocalDateTime asOf;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;
}
