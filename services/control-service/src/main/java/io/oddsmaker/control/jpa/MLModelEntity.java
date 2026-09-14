package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 机器学习模型实体
 * 管理ML模型定义和元数据
 */
@Entity
@Table(name = "ml_models")
public class MLModelEntity {

    /**
     * 模型类型
     */
    public enum ModelType {
        CLASSIFICATION,  // 分类模型
        REGRESSION,       // 回归模型
        CLUSTERING,       // 聚类模型
        ANOMALY_DETECTION, // 异常检测
        RECOMMENDATION,   // 推荐模型
        TIME_SERIES,      // 时间序列模型
        NLP,              // 自然语言处理
        CUSTOM            // 自定义模型
    }

    /**
     * 模型状态
     */
    public enum ModelStatus {
        DRAFT,           // 草稿
        TRAINING,        // 训练中
        EVALUATING,      // 评估中
        DEPLOYED,        // 已部署
        STAGING,         // 预发布环境
        ARCHIVED,        // 已归档
        FAILED           // 失败
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public String id;

    @Column(name = "game_id")
    public String gameId;  // 游戏ID

    @Column(name = "model_name", nullable = false, length = 100)
    public String modelName;  // 模型名称

    @Column(name = "model_type", nullable = false)
    @Enumerated(EnumType.STRING)
    public ModelType modelType;  // 模型类型

    @Column(name = "model_status", nullable = false)
    @Enumerated(EnumType.STRING)
    public ModelStatus modelStatus = ModelStatus.DRAFT;  // 模型状态

    @Column(name = "version", columnDefinition = "INTEGER")
    public Integer version = 1;  // 版本号

    @Column(name = "description", length = 500)
    public String description;  // 描述

    @Column(name = "algorithm", length = 100)
    public String algorithm;  // 算法名称

    @Column(name = "framework", length = 50)
    public String framework;  // 框架（TensorFlow, PyTorch, Scikit-learn等）

    @Column(name = "framework_version", length = 50)
    public String frameworkVersion;  // 框架版本

    @Column(name = "model_config", columnDefinition = "TEXT")
    public String modelConfig;  // JSON格式的模型配置

    @Column(name = "hyperparameters", columnDefinition = "TEXT")
    public String hyperparameters;  // JSON格式的超参数

    @Column(name = "feature_config", columnDefinition = "TEXT")
    public String featureConfig;  // JSON格式的特征配置

    @Column(name = "input_schema", columnDefinition = "TEXT")
    public String inputSchema;  // JSON格式的输入模式

    @Column(name = "output_schema", columnDefinition = "TEXT")
    public String outputSchema;  // JSON格式的输出模式

    @Column(name = "training_config", columnDefinition = "TEXT")
    public String trainingConfig;  // JSON格式的训练配置

    @Column(name = "model_artifact_path", length = 500)
    public String modelArtifactPath;  // 模型文件路径

    @Column(name = "model_size_bytes", columnDefinition = "BIGINT")
    public Long modelSizeBytes;  // 模型大小

    @Column(name = "accuracy_metric", columnDefinition = "DECIMAL(10,6)")
    public Double accuracyMetric;  // 准确率指标

    @Column(name = "precision_metric", columnDefinition = "DECIMAL(10,6)")
    public Double precisionMetric;  // 精确率指标

    @Column(name = "recall_metric", columnDefinition = "DECIMAL(10,6)")
    public Double recallMetric;  // 召回率指标

    @Column(name = "f1_score", columnDefinition = "DECIMAL(10,6)")
    public Double f1Score;  // F1分数

    @Column(name = "auc_score", columnDefinition = "DECIMAL(10,6)")
    public Double aucScore;  // AUC分数

    @Column(name = "custom_metrics", columnDefinition = "TEXT")
    public String customMetrics;  // JSON格式的自定义指标

    @Column(name = "baseline_model_id", length = 32)
    public String baselineModelId;  // 基线模型ID

    @Column(name = "is_ab_test", columnDefinition = "BOOLEAN")
    public Boolean isAbTest = false;  // 是否为A/B测试模型

    @Column(name = "ab_test_config", columnDefinition = "TEXT")
    public String abTestConfig;  // JSON格式的A/B测试配置

    @Column(name = "traffic_split", columnDefinition = "INTEGER")
    public Integer trafficSplit = 0;  // 流量分配（百分比）

    @Column(name = "deployment_config", columnDefinition = "TEXT")
    public String deploymentConfig;  // JSON格式的部署配置

    @Column(name = "serving_endpoint", length = 500)
    public String servingEndpoint;  // 服务端点

    @Column(name = "canaryDeployment", columnDefinition = "BOOLEAN")
    public Boolean canaryDeployment = false;  // 金丝雀部署

    @Column(name = "monitoring_config", columnDefinition = "TEXT")
    public String monitoringConfig;  // JSON格式的监控配置

    @Column(name = "retrain_policy", columnDefinition = "TEXT")
    public String retrainPolicy;  // JSON格式的重训练策略

    @Column(name = "last_trained_at")
    public LocalDateTime lastTrainedAt;  // 最后训练时间

    @Column(name = "last_deployed_at")
    public LocalDateTime lastDeployedAt;  // 最后部署时间

    @Column(name = "last_prediction_at")
    public LocalDateTime lastPredictionAt;  // 最后预测时间

    @Column(name = "prediction_count", columnDefinition = "BIGINT")
    public Long predictionCount = 0L;  // 预测次数

    @Column(name = "created_by", nullable = false, length = 64)
    public String createdBy;  // 创建人

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @Column(name = "updated_at")
    public LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    public LocalDateTime deletedAt;

    // 辅助方法

    public boolean isDeployed() {
        return modelStatus == ModelStatus.DEPLOYED || modelStatus == ModelStatus.STAGING;
    }

    public boolean isTraining() {
        return modelStatus == ModelStatus.TRAINING;
    }

    public boolean isFailed() {
        return modelStatus == ModelStatus.FAILED;
    }

    public boolean isAbTest() {
        return isAbTest != null && isAbTest;
    }

    public boolean isCanaryDeployment() {
        return canaryDeployment != null && canaryDeployment;
    }

    public void deploy() {
        this.modelStatus = ModelStatus.DEPLOYED;
        this.lastDeployedAt = LocalDateTime.now();
    }

    public void startTraining() {
        this.modelStatus = ModelStatus.TRAINING;
    }

    public void completeTraining() {
        this.modelStatus = ModelStatus.EVALUATING;
        this.lastTrainedAt = LocalDateTime.now();
    }

    public void fail() {
        this.modelStatus = ModelStatus.FAILED;
    }

    public void archive() {
        this.modelStatus = ModelStatus.ARCHIVED;
    }

    public void incrementPredictionCount() {
        if (predictionCount == null) {
            predictionCount = 0L;
        }
        this.predictionCount++;
        this.lastPredictionAt = LocalDateTime.now();
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (modelStatus == null) {
            modelStatus = ModelStatus.DRAFT;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
