package io.oddsmaker.control.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * ml 训练产物注册实体（P4.4 收尾）。
 * 承载 ml/（oddsmaker-ml）训练管线的版本化 JSON 产物：线性模型为
 * feature_names + coefficients + intercept，pltv 为 multiplier；
 * 原始产物全文留档 artifact_json。注册时校验（语义对齐 Python 侧 validate_artifact），
 * 批量打分时校验通过的产物优先于启发式。同 (game_id, model_type, model_version)
 * 重训覆盖（upsert）。
 */
@Entity
@Table(name = "ml_model_artifacts")
public class MlArtifactEntity {

    @Id
    @Column(length = 32)
    public String id;

    @Column(name = "game_id", nullable = false, length = 64)
    public String gameId;

    /** churn / pltv / risk（对齐 ml 产物 model_type，propensity 尚未支持） */
    @Column(name = "model_type", nullable = false, length = 20)
    public String modelType;

    @Column(name = "model_version", nullable = false, length = 40)
    public String modelVersion;

    /** 训练来源：synthetic / clickhouse（产物 source 字段原样） */
    @Column(length = 20)
    public String source;

    /** 产物 trained_at（ISO 字符串原样保存） */
    @Column(name = "trained_at", length = 40)
    public String trainedAt;

    /** JSON 字符串数组，churn/risk 必有 */
    @Column(name = "feature_names", columnDefinition = "TEXT")
    public String featureNames;

    /** JSON 数值数组，churn/risk 必有（顺序对齐 feature_names） */
    @Column(columnDefinition = "TEXT")
    public String coefficients;

    /** churn/risk 线性模型截距 */
    @Column(columnDefinition = "DECIMAL(12,8)")
    public Double intercept;

    /** pltv 的 D7→D30 乘数（必须为正） */
    @Column(columnDefinition = "DECIMAL(12,6)")
    public Double multiplier;

    /** 产物训练指标（JSON：auc/log_loss/brier/pr_auc 或 holdout MAPE） */
    @Column(columnDefinition = "TEXT")
    public String metrics;

    /** 启发式基线对照指标（JSON，产物 heuristic_baseline 字段原样） */
    @Column(name = "heuristic_baseline", columnDefinition = "TEXT")
    public String heuristicBaseline;

    /** 完整产物 JSON 原文留档 */
    @Column(name = "artifact_json", nullable = false, columnDefinition = "TEXT")
    public String artifactJson;

    @Column(nullable = false, length = 20)
    public String status = "ACTIVE";

    @Column(name = "created_by", nullable = false, length = 64)
    public String createdBy;

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = "ACTIVE";
        }
    }
}
