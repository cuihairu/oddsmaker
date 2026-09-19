package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 同期群（Cohort）实体
 * 用于用户行为分析和留存分析
 */
@Entity
@Table(name = "cohorts")
public class CohortEntity {

    @Id
    @Column(length = 32)
    public String id;

    // 基本信息
    @Column(name = "game_id", nullable = false, length = 32)
    public String gameId;

    @Column(name = "environment_id", length = 32)
    public String environmentId;

    @Column(nullable = false, length = 100)
    public String name;  // 同期群名称

    @Column(name = "display_name", length = 200)
    public String displayName;  // 显示名称

    @Column(columnDefinition = "TEXT")
    public String description;  // 描述

    // 同期群类型
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public CohortType cohortType = CohortType.ACQUISITION;  // 同期群类型

    // 时间配置
    @Column(name = "start_date")
    public LocalDate startDate;  // 开始日期

    @Column(name = "end_date")
    public LocalDate endDate;  // 结束日期

    @Column(name = "time_unit", length = 20)
    public String timeUnit = "day";  // 时间单位：day, week, month

    @Column(name = "cohort_size")
    public Integer cohortSize = 1;  // 同期群大小（时间窗口）

    // 行为定义
    @Column(name = "behavior_definition", columnDefinition = "TEXT")
    public String behaviorDefinition;  // 行为定义（JSON格式）

    @Column(name = "inclusion_criteria", columnDefinition = "TEXT")
    public String inclusionCriteria;  // 包含条件

    @Column(name = "exclusion_criteria", columnDefinition = "TEXT")
    public String exclusionCriteria;  // 排除条件

    // 分析配置
    @Column(name = "analysis_type", length = 50)
    public String analysisType = "retention";  // 分析类型：retention, engagement, revenue, churn

    @Column(name = "metric_type", length = 50)
    public String metricType = "return_rate";  // 指标类型：return_rate, session_count, revenue, etc.

    @Column(name = "retention_periods", columnDefinition = "TEXT")
    public String retentionPeriods;  // 留存周期（JSON数组）：[1,7,30,90]

    @Column(name = "comparison_cohorts", columnDefinition = "TEXT")
    public String comparisonCohorts;  // 对比同期群（JSON数组）

    // 结果数据
    @Column(name = "cohort_count")
    public Long cohortCount = 0L;  // 同期群用户数

    @Column(name = "result_data", columnDefinition = "TEXT")
    public String resultData;  // 结果数据（JSON格式）

    @Column(name = "result_summary", columnDefinition = "TEXT")
    public String resultSummary;  // 结果摘要

    // 状态
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public CohortStatus status = CohortStatus.PENDING;

    @Column(name = "calculated_at")
    public LocalDateTime calculatedAt;  // 计算时间

    // 统计信息
    @Column(name = "total_calculations")
    public Long totalCalculations = 0L;  // 总计算次数

    @Column(name = "last_calculation_time_ms")
    public Long lastCalculationTimeMs;  // 最后计算时间（毫秒）

    // 操作信息
    @Column(name = "created_by", length = 64)
    public String createdBy;  // 创建人

    @Column(name = "updated_by", length = 64)
    public String updatedBy;  // 更新人

    // 时间戳
    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    public LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    public LocalDateTime deletedAt;

    public enum CohortType {
        ACQUISITION,       // 获客同期群（按首次访问时间分组）
        BEHAVIORAL,        // 行为同期群（按特定行为分组）
        CUSTOM,            // 自定义同期群
        SEGMENTED          // 细分同期群
    }

    public enum CohortStatus {
        PENDING,           // 待计算
        CALCULATING,       // 计算中
        COMPLETED,         // 已完成
        FAILED,            // 失败
        ARCHIVED           // 已归档
    }

    // 业务方法
    public boolean isActive() {
        return deletedAt == null && status != CohortStatus.ARCHIVED;
    }

    public boolean isPending() {
        return status == CohortStatus.PENDING;
    }

    public boolean isCalculating() {
        return status == CohortStatus.CALCULATING;
    }

    public boolean isCompleted() {
        return status == CohortStatus.COMPLETED;
    }

    public boolean hasResults() {
        return isCompleted() && resultData != null && !resultData.isEmpty();
    }

    public void markAsCalculating() {
        this.status = CohortStatus.CALCULATING;
    }

    public void markAsCompleted(Long cohortCount, String resultData, String resultSummary, Long calculationTime) {
        this.status = CohortStatus.COMPLETED;
        this.cohortCount = cohortCount;
        this.resultData = resultData;
        this.resultSummary = resultSummary;
        this.lastCalculationTimeMs = calculationTime;
        this.calculatedAt = LocalDateTime.now();
        this.totalCalculations = (this.totalCalculations == null ? 0 : this.totalCalculations) + 1;
    }

    public void markAsFailed(String reason) {
        this.status = CohortStatus.FAILED;
        this.resultSummary = reason;  // 失败原因落摘要，详情/列表可见而非只留日志
    }

    public List<Integer> getRetentionPeriods() {
        if (retentionPeriods == null || retentionPeriods.isEmpty()) {
            return Arrays.asList(1, 7, 14, 30, 60, 90);  // 默认周期
        }

        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(retentionPeriods, List.class);
        } catch (Exception e) {
            return Arrays.asList(1, 7, 14, 30, 60, 90);
        }
    }

    public String getCohortDescription() {
        return String.format("[%s] %s - %s (%s)",
            cohortType.name().toLowerCase(),
            displayName != null ? displayName : name,
            startDate != null ? startDate.toString() : "no-date",
            timeUnit
        );
    }

    public boolean isAcquisitionCohort() {
        return cohortType == CohortType.ACQUISITION;
    }

    public boolean isBehavioralCohort() {
        return cohortType == CohortType.BEHAVIORAL;
    }
}
