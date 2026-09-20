package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 自定义报表实体
 * 管理用户创建的自定义报表配置
 */
@Entity
@Table(name = "reports")
public class ReportEntity {

    @Id
    @Column(length = 32)
    public String id;

    // 基本信息
    @Column(name = "game_id", nullable = false, length = 32)
    public String gameId;

    @Column(name = "environment_id", length = 32)
    public String environmentId;

    @Column(nullable = false, length = 100)
    public String name;  // 报表名称

    @Column(name = "display_name", length = 200)
    public String displayName;  // 显示名称

    @Column(columnDefinition = "TEXT")
    public String description;  // 报表描述

    // 报表类型
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public ReportType reportType = ReportType.CUSTOM;  // 报表类型

    @Column(name = "report_category", length = 50)
    public String reportCategory;  // 报表分类：analytics, retention, funnel, revenue, risk

    // 数据源配置
    @Column(name = "data_source", columnDefinition = "TEXT")
    public String dataSource;  // 数据源配置（JSON格式）

    @Column(name = "query_config", columnDefinition = "TEXT")
    public String queryConfig;  // 查询配置（JSON格式）

    // 过滤器和参数
    @Column(name = "filters", columnDefinition = "TEXT")
    public String filters;  // 默认过滤器（JSON格式）

    @Column(name = "parameters", columnDefinition = "TEXT")
    public String parameters;  // 参数定义（JSON格式）

    // 可视化配置
    @Column(name = "visualization", columnDefinition = "TEXT")
    public String visualization;  // 可视化配置（图表类型、轴等）

    @Column(name = "chart_type", length = 50)
    public String chartType;  // 图表类型：line, bar, pie, table, heatmap

    // 分组和聚合
    @Column(name = "group_by", columnDefinition = "TEXT")
    public String groupBy;  // 分组字段（JSON数组）

    @Column(name = "aggregations", columnDefinition = "TEXT")
    public String aggregations;  // 聚合配置（JSON格式）

    // 时间范围
    @Column(name = "default_time_range", length = 50)
    public String defaultTimeRange = "7d";  // 默认时间范围：1h, 24h, 7d, 30d, 90d

    @Column(name = "time_granularity", length = 20)
    public String timeGranularity = "day";  // 时间粒度：minute, hour, day, week, month

    // 导出配置
    @Column(name = "export_formats", columnDefinition = "TEXT")
    public String exportFormats;  // 支持的导出格式（JSON数组）

    // 调度配置
    @Column(name = "schedule_config", columnDefinition = "TEXT")
    public String scheduleConfig;  // 调度配置（cron表达式等）

    @Column(name = "recipients", columnDefinition = "TEXT")
    public String recipients;  // 报表接收人（JSON数组）

    // 权限控制
    @Column(name = "access_control", columnDefinition = "TEXT")
    public String accessControl;  // 访问控制配置

    @Column(name = "is_public")
    public Boolean isPublic = false;  // 是否公开

    // 状态
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public ReportStatus status = ReportStatus.DRAFT;  // 报表状态

    // 统计信息
    @Column(name = "total_runs")
    public Long totalRuns = 0L;  // 总运行次数

    @Column(name = "last_run_at")
    public LocalDateTime lastRunAt;  // 最后运行时间

    @Column(name = "last_run_status")
    public String lastRunStatus;  // 最后运行状态

    // 版本控制
    @Column(name = "version", length = 20)
    public String version = "1.0";

    @Column(name = "parent_report_id", length = 32)
    public String parentReportId;  // 父报表ID（用于版本管理）

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
    public LocalDateTime deletedAt;  // 软删除时间

    // 关联关系
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_report_id", insertable = false, updatable = false)
    public ReportEntity parentReport;

    @OneToMany(mappedBy = "report", fetch = FetchType.LAZY)
    public List<ReportExecutionEntity> executions;

    public enum ReportType {
        CUSTOM,            // 自定义报表
        TEMPLATE,         // 模板报表
        SYSTEM,           // 系统报表
        SCHEDULED,        // 定时报表
        ADHOC             // 临时报表
    }

    public enum ReportStatus {
        DRAFT,            // 草稿
        PUBLISHED,        // 已发布
        SCHEDULED,        // 已调度
        ARCHIVED,         // 已归档
        DELETED           // 已删除
    }

    // 业务方法
    public boolean isActive() {
        return status == ReportStatus.PUBLISHED || status == ReportStatus.SCHEDULED;
    }

    public boolean isDraft() {
        return status == ReportStatus.DRAFT;
    }

    public boolean isScheduled() {
        return status == ReportStatus.SCHEDULED;
    }

    public boolean isPublicAccessible() {
        return Boolean.TRUE.equals(isPublic);
    }

    public void markAsPublished() {
        this.status = ReportStatus.PUBLISHED;
    }

    public void markAsArchived() {
        this.status = ReportStatus.ARCHIVED;
    }

    public void recordRun(String runStatus) {
        this.totalRuns = (this.totalRuns == null ? 0 : this.totalRuns) + 1;
        this.lastRunAt = LocalDateTime.now();
        this.lastRunStatus = runStatus;
    }

    /** 执行开始的状态标记：不增加 totalRuns（完成或失败时才记一次运行）。 */
    public void markRunning() {
        this.lastRunAt = LocalDateTime.now();
        this.lastRunStatus = "running";
    }

    public String getReportDescription() {
        return String.format("[%s] %s - %s (%s)",
            reportType.name().toLowerCase(),
            displayName != null ? displayName : name,
            reportCategory != null ? reportCategory : "general",
            chartType != null ? chartType : "table"
        );
    }

    public Map<String, Object> getVisualizationConfig() {
        if (visualization == null) {
            return Map.of(
                "chartType", chartType != null ? chartType : "table",
                "groupBy", groupBy != null ? groupBy : "[]",
                "aggregations", aggregations != null ? aggregations : "{}"
            );
        }

        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(visualization, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
