package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 关卡进度追踪配置
 * 定义关卡通过率、失败率、尝试次数等指标的追踪配置
 */
@Entity
@Table(name = "level_progressions")
public class LevelProgressionEntity {

    @Id
    @Column(length = 32)
    public String id;

    @Column(name = "game_id", nullable = false, length = 32)
    public String gameId;

    @Column(nullable = false, length = 100)
    public String name;

    @Column(name = "display_name", length = 200)
    public String displayName;

    @Column(length = 1000)
    public String description;

    // 关卡配置
    @Column(name = "level_identifier", length = 100)
    public String levelIdentifier;  // 关卡标识字段：level_id, stage_id

    @Column(name = "level_start_event", length = 100)
    public String levelStartEvent = "level_start";  // 关卡开始事件

    @Column(name = "level_complete_event", length = 100)
    public String levelCompleteEvent = "level_complete";  // 关卡完成事件

    @Column(name = "level_fail_event", length = 100)
    public String levelFailEvent = "level_fail";  // 关卡失败事件

    // 追踪指标
    @Column(name = "track_pass_rate")
    public Boolean trackPassRate = true;  // 追踪通过率

    @Column(name = "track_fail_rate")
    public Boolean trackFailRate = true;  // 追踪失败率

    @Column(name = "track_retry_count")
    public Boolean trackRetryCount = true;  // 追踪重试次数

    @Column(name = "track_completion_time")
    public Boolean trackCompletionTime = true;  // 追踪完成时间

    @Column(name = "track_first_completion")
    public Boolean trackFirstCompletion = true;  // 追踪首次完成

    // 异常检测
    @Column(name = "enable_anomaly_detection")
    public Boolean enableAnomalyDetection = true;  // 启用异常检测

    @Column(name = "anomaly_threshold")
    public Double anomalyThreshold = 2.0;  // 异常阈值（标准差倍数）

    @Column(name = "min_attempts_for_analysis")
    public Integer minAttemptsForAnalysis = 100;  // 最小分析尝试次数

    // 分组
    @Column(name = "group_by_dimensions", columnDefinition = "TEXT")
    public String groupByDimensions;  // JSON：["level_id", "difficulty"]

    // 难度分析
    @Column(name = "enable_difficulty_analysis")
    public Boolean enableDifficultyAnalysis = true;  // 启用难度分析

    @Column(name = "difficulty_metric", length = 50)
    public String difficultyMetric = "pass_rate";  // 难度度量

    // 状态
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public ProgressionStatus status = ProgressionStatus.ACTIVE;

    @Column(name = "enable_auto_calc")
    public Boolean enableAutoCalc = true;

    @Column(name = "calc_frequency")
    public String calcFrequency = "daily";

    @Column(name = "last_calculated_at")
    public LocalDateTime lastCalculatedAt;

    @Column(name = "result_table", length = 100)
    public String resultTable;

    // 时间戳
    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    public LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    public LocalDateTime deletedAt;

    @Column(name = "created_by", length = 64)
    public String createdBy;

    // 关联关系
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "game_id", insertable = false, updatable = false)
    public GameEntity game;

    public enum ProgressionStatus {
        ACTIVE,        // 活跃
        PAUSED,        // 暂停
        ARCHIVED       // 已归档
    }

    // 业务方法
    public boolean isActive() {
        return status == ProgressionStatus.ACTIVE && deletedAt == null;
    }

    public boolean isAutoCalcEnabled() {
        return Boolean.TRUE.equals(enableAutoCalc);
    }

    public boolean tracksCompletionTime() {
        return Boolean.TRUE.equals(trackCompletionTime);
    }

    public boolean hasAnomalyDetection() {
        return Boolean.TRUE.equals(enableAnomalyDetection);
    }
}
