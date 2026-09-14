package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime;

/**
 * 模型训练任务实体
 * 跟踪ML模型训练执行
 */
@Entity
@Table(name = "model_training_jobs")
public class ModelTrainingEntity {

    /**
     * 训练状态
     */
    public enum TrainingStatus {
        PENDING,        // 待执行
        RUNNING,        // 运行中
        COMPLETED,      // 已完成
        FAILED,         // 失败
        CANCELLED,      // 已取消
        TIMEOUT         // 超时
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public String id;

    @Column(name = "model_id", nullable = false)
    public String modelId;  // 模型ID

    @Column(name = "game_id")
    public String gameId;  // 游戏ID

    @Column(name = "training_job_name", nullable = false, length = 100)
    public String trainingJobName;  // 训练任务名称

    @Column(name = "training_status", nullable = false)
    @Enumerated(EnumType.STRING)
    public TrainingStatus trainingStatus = TrainingStatus.PENDING;  // 训练状态

    @Column(name = "triggered_by", length = 64)
    public String triggeredBy;  // 触发人

    @Column(name = "trigger_type", length = 50)
    public String triggerType;  // 触发类型：manual, scheduled, automatic

    @Column(name = "training_config", columnDefinition = "TEXT")
    public String trainingConfig;  // JSON格式的训练配置

    @Column(name = "dataset_config", columnDefinition = "TEXT")
    public String datasetConfig;  // JSON格式的数据集配置

    @Column(name = "hyperparameter_config", columnDefinition = "TEXT")
    public String hyperparameterConfig;  // JSON格式的超参数配置

    @Column(name = "validation_config", columnDefinition = "TEXT")
    public String validationConfig;  // JSON格式的验证配置

    @Column(name = "training_epochs", columnDefinition = "INTEGER")
    public Integer trainingEpochs;  // 训练轮数

    @Column(name = "current_epoch", columnDefinition = "INTEGER")
    public Integer currentEpoch = 0;  // 当前轮数

    @Column(name = "batch_size", columnDefinition = "INTEGER")
    public Integer batchSize;  // 批次大小

    @Column(name = "learning_rate", columnDefinition = "DECIMAL(10,8)")
    @JdbcTypeCode(SqlTypes.NUMERIC)
    public Double learningRate;  // 学习率

    @Column(name = "training_samples", columnDefinition = "BIGINT")
    public Long trainingSamples = 0L;  // 训练样本数

    @Column(name = "validation_samples", columnDefinition = "BIGINT")
    public Long validationSamples = 0L;  // 验证样本数

    @Column(name = "test_samples", columnDefinition = "BIGINT")
    public Long testSamples = 0L;  // 测试样本数

    @Column(name = "training_metrics", columnDefinition = "TEXT")
    public String trainingMetrics;  // JSON格式的训练指标

    @Column(name = "validation_metrics", columnDefinition = "TEXT")
    public String validationMetrics;  // JSON格式的验证指标

    @Column(name = "test_metrics", columnDefinition = "TEXT")
    public String testMetrics;  // JSON格式的测试指标

    @Column(name = "loss_history", columnDefinition = "TEXT")
    public String lossHistory;  // JSON格式的损失历史

    @Column(name = "best_epoch", columnDefinition = "INTEGER")
    public Integer bestEpoch;  // 最佳轮数

    @Column(name = "best_loss", columnDefinition = "DECIMAL(20,10)")
    @JdbcTypeCode(SqlTypes.NUMERIC)
    public Double bestLoss;  // 最佳损失

    @Column(name = "started_at")
    public LocalDateTime startedAt;  // 开始时间

    @Column(name = "completed_at")
    public LocalDateTime completedAt;  // 完成时间

    @Column(name = "duration_ms", columnDefinition = "BIGINT")
    public Long durationMs;  // 执行时长（毫秒）

    @Column(name = "gpu_hours", columnDefinition = "DECIMAL(10,4)")
    @JdbcTypeCode(SqlTypes.NUMERIC)
    public Double gpuHours;  // GPU使用时长

    @Column(name = "cpu_hours", columnDefinition = "DECIMAL(10,4)")
    @JdbcTypeCode(SqlTypes.NUMERIC)
    public Double cpuHours;  // CPU使用时长

    @Column(name = "worker_node", length = 100)
    public String workerNode;  // 工作节点

    @Column(name = "checkpoint_path", length = 500)
    public String checkpointPath;  // 检查点路径

    @Column(name = "artifact_path", length = 500)
    public String artifactPath;  // 模型文件路径

    @Column(name = "error_message", columnDefinition = "TEXT")
    public String errorMessage;  // 错误消息

    @Column(name = "error_stack_trace", columnDefinition = "TEXT")
    public String errorStackTrace;  // 错误堆栈

    @Column(name = "progress_percent", columnDefinition = "INTEGER")
    public Integer progressPercent = 0;  // 进度百分比

    @Column(name = "eta_minutes", columnDefinition = "INTEGER")
    public Integer etaMinutes;  // 预计剩余时间（分钟）

    @Column(name = "logs_path", length = 500)
    public String logsPath;  // 日志路径

    @Column(name = "tensorboard_path", length = 500)
    public String tensorboardPath;  // TensorBoard路径

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @Column(name = "updated_at")
    public LocalDateTime updatedAt;

    // 辅助方法

    public boolean isPending() {
        return trainingStatus == TrainingStatus.PENDING;
    }

    public boolean isRunning() {
        return trainingStatus == TrainingStatus.RUNNING;
    }

    public boolean isCompleted() {
        return trainingStatus == TrainingStatus.COMPLETED;
    }

    public boolean isFailed() {
        return trainingStatus == TrainingStatus.FAILED || trainingStatus == TrainingStatus.TIMEOUT;
    }

    public boolean isCancelled() {
        return trainingStatus == TrainingStatus.CANCELLED;
    }

    public double getProgress() {
        if (trainingEpochs != null && trainingEpochs > 0 && currentEpoch != null) {
            return ((double) currentEpoch) / trainingEpochs * 100;
        }
        return progressPercent != null ? progressPercent : 0;
    }

    public long getDurationMinutes() {
        if (startedAt != null) {
            LocalDateTime end = completedAt != null ? completedAt : LocalDateTime.now();
            return java.time.Duration.between(startedAt, end).toMinutes();
        }
        return 0;
    }

    public void start() {
        this.trainingStatus = TrainingStatus.RUNNING;
        this.startedAt = LocalDateTime.now();
    }

    public void complete() {
        this.trainingStatus = TrainingStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
        if (this.startedAt != null) {
            this.durationMs = java.time.Duration.between(this.startedAt, this.completedAt).toMillis();
        }
        this.progressPercent = 100;
    }

    public void fail(String error) {
        this.trainingStatus = TrainingStatus.FAILED;
        this.completedAt = LocalDateTime.now();
        this.errorMessage = error;
        if (this.startedAt != null) {
            this.durationMs = java.time.Duration.between(this.startedAt, this.completedAt).toMillis();
        }
    }

    public void cancel() {
        this.trainingStatus = TrainingStatus.CANCELLED;
        this.completedAt = LocalDateTime.now();
    }

    public void updateProgress(int epoch, int totalEpochs, double loss) {
        this.currentEpoch = epoch;
        this.trainingEpochs = totalEpochs;
        this.progressPercent = (int) ((epoch * 100.0) / totalEpochs);

        if (bestLoss == null || loss < bestLoss) {
            this.bestLoss = loss;
            this.bestEpoch = epoch;
        }
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (trainingStatus == null) {
            trainingStatus = TrainingStatus.PENDING;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
