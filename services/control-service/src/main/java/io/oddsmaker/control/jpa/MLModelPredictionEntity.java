package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * ML模型预测记录实体
 * 跟踪模型预测请求和结果
 */
@Entity
@Table(name = "ml_model_predictions")
public class MLModelPredictionEntity {

    /**
     * 预测状态
     */
    public enum PredictionStatus {
        PENDING,        // 待处理
        PROCESSING,     // 处理中
        COMPLETED,      // 已完成
        FAILED,         // 失败
        TIMEOUT,        // 超时
        CACHED          // 缓存命中
    }

    /**
     * 反馈类型
     */
    public enum FeedbackType {
        CORRECT,        // 正确
        INCORRECT,      // 不正确
        PARTIAL,        // 部分正确
        UNKNOWN         // 未知
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public String id;

    @Column(name = "model_id", nullable = false)
    public String modelId;  // 模型ID

    @Column(name = "model_version", columnDefinition = "INTEGER")
    public Integer modelVersion;  // 模型版本

    @Column(name = "game_id")
    public String gameId;  // 游戏ID

    @Column(name = "environment", length = 50)
    public String environment;  // 环境

    @Column(name = "prediction_status", nullable = false)
    @Enumerated(EnumType.STRING)
    public PredictionStatus predictionStatus = PredictionStatus.PENDING;  // 预测状态

    @Column(name = "request_id", length = 64)
    public String requestId;  // 请求ID（用于关联）

    @Column(name = "entity_type", length = 50)
    public String entityType;  // 实体类型（user, event, session等）

    @Column(name = "entity_id", length = 128)
    public String entityId;  // 实体ID

    @Column(name = "input_data", columnDefinition = "TEXT")
    public String inputData;  // JSON格式的输入数据

    @Column(name = "input_features", columnDefinition = "TEXT")
    public String inputFeatures;  // JSON格式的输入特征

    @Column(name = "output_prediction", columnDefinition = "TEXT")
    public String outputPrediction;  // JSON格式的预测输出

    @Column(name = "prediction_class", length = 100)
    public String predictionClass;  // 预测分类

    @Column(name = "prediction_score", columnDefinition = "DECIMAL(10,6)")
    public Double predictionScore;  // 预测分数/置信度

    @Column(name = "prediction_probability", columnDefinition = "DECIMAL(10,6)")
    public Double predictionProbability;  // 预测概率

    @Column(name = "top_predictions", columnDefinition = "TEXT")
    public String topPredictions;  // JSON格式的Top-N预测

    @Column(name = "feature_importance", columnDefinition = "TEXT")
    public String featureImportance;  // JSON格式的特征重要性

    @Column(name = "explanation", columnDefinition = "TEXT")
    public String explanation;  // 预测解释（SHAP/LIME等）

    @Column(name = "latency_ms", columnDefinition = "INTEGER")
    public Integer latencyMs;  // 预测延迟（毫秒）

    @Column(name = "is_ab_test", columnDefinition = "BOOLEAN")
    public Boolean isAbTest = false;  // 是否为A/B测试

    @Column(name = "ab_test_group", length = 20)
    public String abTestGroup;  // A/B测试组（control/treatment）

    @Column(name = "is_canary", columnDefinition = "BOOLEAN")
    public Boolean isCanary = false;  // 是否为金丝雀请求

    @Column(name = "cache_hit", columnDefinition = "BOOLEAN")
    public Boolean cacheHit = false;  // 是否缓存命中

    @Column(name = "feedback_type")
    @Enumerated(EnumType.STRING)
    public FeedbackType feedbackType;  // 反馈类型

    @Column(name = "actual_value", columnDefinition = "TEXT")
    public String actualValue;  // 实际值（用于模型评估）

    @Column(name = "feedback_at")
    public LocalDateTime feedbackAt;  // 反馈时间

    @Column(name = "feedback_by", length = 64)
    public String feedbackBy;  // 反馈人

    @Column(name = "client_ip", length = 45)
    public String clientIp;  // 客户端IP

    @Column(name = "client_id", length = 64)
    public String clientId;  // 客户端ID

    @Column(name = "request_source", length = 50)
    public String requestSource;  // 请求来源（api, stream, batch）

    @Column(name = "batch_id", length = 64)
    public String batchId;  // 批量预测ID

    @Column(name = "metadata", columnDefinition = "TEXT")
    public String metadata;  // JSON格式的附加元数据

    @Column(name = "error_message", columnDefinition = "TEXT")
    public String errorMessage;  // 错误消息

    @Column(name = "error_code", length = 50)
    public String errorCode;  // 错误代码

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @Column(name = "completed_at")
    public LocalDateTime completedAt;

    // 辅助方法

    public boolean isCompleted() {
        return predictionStatus == PredictionStatus.COMPLETED || predictionStatus == PredictionStatus.CACHED;
    }

    public boolean isFailed() {
        return predictionStatus == PredictionStatus.FAILED || predictionStatus == PredictionStatus.TIMEOUT;
    }

    public boolean isPending() {
        return predictionStatus == PredictionStatus.PENDING || predictionStatus == PredictionStatus.PROCESSING;
    }

    public boolean hasFeedback() {
        return feedbackType != null;
    }

    public boolean isCorrect() {
        return feedbackType == FeedbackType.CORRECT;
    }

    public boolean isAbTest() {
        return isAbTest != null && isAbTest;
    }

    public boolean isCanary() {
        return isCanary != null && isCanary;
    }

    public boolean isCacheHit() {
        return cacheHit != null && cacheHit;
    }

    public void complete(String prediction, Double score) {
        this.predictionStatus = PredictionStatus.COMPLETED;
        this.outputPrediction = prediction;
        this.predictionScore = score;
        this.completedAt = LocalDateTime.now();
    }

    public void fail(String error) {
        this.predictionStatus = PredictionStatus.FAILED;
        this.errorMessage = error;
        this.completedAt = LocalDateTime.now();
    }

    public void timeout() {
        this.predictionStatus = PredictionStatus.TIMEOUT;
        this.errorMessage = "Prediction request timed out";
        this.completedAt = LocalDateTime.now();
    }

    public void markCached(String prediction) {
        this.predictionStatus = PredictionStatus.CACHED;
        this.outputPrediction = prediction;
        this.cacheHit = true;
        this.completedAt = LocalDateTime.now();
    }

    public void addFeedback(FeedbackType feedbackType, String actualValue, String feedbackBy) {
        this.feedbackType = feedbackType;
        this.actualValue = actualValue;
        this.feedbackBy = feedbackBy;
        this.feedbackAt = LocalDateTime.now();
    }

    public long getLatencyMs() {
        if (latencyMs != null) {
            return latencyMs;
        }
        if (createdAt != null && completedAt != null) {
            return java.time.Duration.between(createdAt, completedAt).toMillis();
        }
        return 0;
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (predictionStatus == null) {
            predictionStatus = PredictionStatus.PENDING;
        }
    }
}
