package io.oddsmaker.control.jpa;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * B10 共享 Feature 摘要（计划书 §5.3，设计定稿 07-b10 §5）。
 * risk-job 特征作业与 risk_features 同源双写：scope×窗口一行，features 为
 * {"特征名@窗口": value} 扁平 JSON 串（窗口编码进键名避免同名互踩）。
 * scope_key 编码沿用 risk_features 约定：PLAYER:&lt;user_id&gt; / DEVICE:&lt;device_id&gt; / IP:&lt;client_ip&gt;。
 */
@Entity
@Table(name = "feature_store")
public class FeatureStoreEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "game_id", length = 32)
    public String gameId;

    @Column(name = "environment", length = 100)
    public String environment;

    @Column(name = "scope_key", length = 256)
    public String scopeKey;

    @Column(name = "window_start")
    public LocalDateTime windowStart;

    @Column(name = "window_end")
    public LocalDateTime windowEnd;

    @Lob
    @Column(columnDefinition = "TEXT")
    public String features;

    @Column(name = "as_of")
    public LocalDateTime asOf;

    @Column(name = "created_at")
    public LocalDateTime createdAt;
}
