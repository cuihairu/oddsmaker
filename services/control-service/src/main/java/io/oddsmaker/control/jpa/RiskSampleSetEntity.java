package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 策略实验室样本集实体（V0.4）
 * 命名留档一批样本事件，供试算回放反复载入对比（改规则 → 重放同一批样本）。
 * samples 为 JSON 数组字符串，结构校验与 dry-run 同款；留档不可改，删除重建。
 */
@Entity
@Table(name = "risk_sample_sets")
public class RiskSampleSetEntity {

    @Id
    @Column(length = 32)
    public String id;

    @Column(name = "game_id", nullable = false, length = 32)
    public String gameId;

    @Column(nullable = false, length = 100)
    public String name;

    @Column(length = 500)
    public String description;

    /** 样本事件 JSON 数组（1~500 条，amount/features 须可数值化） */
    @Column(nullable = false, columnDefinition = "TEXT")
    public String samples;

    @Column(name = "sample_count", nullable = false)
    public Integer sampleCount;

    @Column(name = "created_by", length = 64)
    public String createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    public LocalDateTime updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "game_id", insertable = false, updatable = false)
    public GameEntity game;
}
