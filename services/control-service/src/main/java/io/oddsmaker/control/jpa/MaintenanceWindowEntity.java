package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 维护窗口实体
 * 管理计划维护和紧急维护
 */
@Entity
@Table(name = "maintenance_windows")
public class MaintenanceWindowEntity {

    /**
     * 维护类型
     */
    public enum MaintenanceType {
        SCHEDULED,       // 计划维护
        EMERGENCY,       // 紧急维护
        ROLLING,         // 滚动维护
        PATCHING         // 补丁维护
    }

    /**
     * 维护状态
     */
    public enum MaintenanceStatus {
        SCHEDULED,       // 已计划
        PENDING,         // 待开始
        IN_PROGRESS,     // 进行中
        PAUSED,          // 已暂停
        COMPLETED,       // 已完成
        CANCELLED,       // 已取消
        EXTENDED         // 已延期
    }

    /**
     * 影响范围
     */
    public enum ImpactScope {
        GLOBAL,          // 全局影响
        GAME,            // 游戏级别
        ENVIRONMENT,     // 环境级别
        SERVICE,         // 服务级别
        REGION           // 区域级别
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public String id;

    @Column(name = "maintenance_type", nullable = false)
    @Enumerated(EnumType.STRING)
    public MaintenanceType maintenanceType = MaintenanceType.SCHEDULED;

    @Column(name = "maintenance_status", nullable = false)
    @Enumerated(EnumType.STRING)
    public MaintenanceStatus maintenanceStatus = MaintenanceStatus.SCHEDULED;

    @Column(name = "title", nullable = false, length = 200)
    public String title;  // 维护标题

    @Column(name = "description", columnDefinition = "TEXT")
    public String description;  // 维护描述

    @Column(name = "impact_scope", nullable = false)
    @Enumerated(EnumType.STRING)
    public ImpactScope impactScope = ImpactScope.GLOBAL;

    @Column(name = "game_id")
    public String gameId;  // 游戏ID（游戏级别维护时使用）

    @Column(name = "environment_id")
    public String environmentId;  // 环境ID（环境级别维护时使用）

    @Column(name = "service", length = 100)
    public String service;  // 服务名称（服务级别维护时使用）

    @Column(name = "region", length = 50)
    public String region;  // 区域（区域级别维护时使用）

    @Column(name = "scheduled_start", nullable = false)
    public LocalDateTime scheduledStart;  // 计划开始时间

    @Column(name = "scheduled_end", nullable = false)
    public LocalDateTime scheduledEnd;  // 计划结束时间

    @Column(name = "actual_start")
    public LocalDateTime actualStart;  // 实际开始时间

    @Column(name = "actual_end")
    public LocalDateTime actualEnd;  // 实际结束时间

    @Column(name = "extended_until")
    public LocalDateTime extendedUntil;  // 延期到

    @Column(name = "estimated_duration_minutes")
    public Integer estimatedDurationMinutes;  // 预计持续时长（分钟）

    @Column(name = "progress_percent", columnDefinition = "INTEGER")
    public Integer progressPercent = 0;  // 进度百分比

    @Column(name = "affected_services", columnDefinition = "TEXT")
    public String affectedServices;  // JSON格式的受影响服务列表

    @Column(name = "notification_sent", columnDefinition = "BOOLEAN")
    public Boolean notificationSent = false;  // 是否已发送通知

    @Column(name = "notification_sent_at")
    public LocalDateTime notificationSentAt;  // 通知发送时间

    @Column(name = "end_notification_sent", columnDefinition = "BOOLEAN")
    public Boolean endNotificationSent = false;  // 是否已发送"即将结束"通知（V0.9.5）

    @Column(name = "notification_channels", columnDefinition = "TEXT")
    public String notificationChannels;  // JSON格式的通知渠道

    @Column(name = "maintenance_tasks", columnDefinition = "TEXT")
    public String maintenanceTasks;  // JSON格式的维护任务列表

    @Column(name = "rollout_plan", columnDefinition = "TEXT")
    public String rolloutPlan;  // JSON格式的上线计划

    @Column(name = "rollback_plan", columnDefinition = "TEXT")
    public String rollbackPlan;  // JSON格式的回滚计划

    @Column(name = "impact_summary", columnDefinition = "TEXT")
    public String impactSummary;  // 影响摘要

    @Column(name = "created_by", nullable = false, length = 64)
    public String createdBy;  // 创建人

    @Column(name = "assigned_to", length = 64)
    public String assignedTo;  // 分配给谁

    @Column(name = "approved_by", length = 64)
    public String approvedBy;  // 批准人

    @Column(name = "approved_at")
    public LocalDateTime approvedAt;  // 批准时间

    @Column(name = "completion_notes", columnDefinition = "TEXT")
    public String completionNotes;  // 完成备注

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @Column(name = "updated_at")
    public LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    public LocalDateTime deletedAt;

    // 辅助方法

    public boolean isScheduled() {
        return maintenanceStatus == MaintenanceStatus.SCHEDULED;
    }

    public boolean isPending() {
        return maintenanceStatus == MaintenanceStatus.PENDING;
    }

    public boolean isInProgress() {
        return maintenanceStatus == MaintenanceStatus.IN_PROGRESS;
    }

    public boolean isCompleted() {
        return maintenanceStatus == MaintenanceStatus.COMPLETED;
    }

    public boolean isActive() {
        return maintenanceStatus == MaintenanceStatus.IN_PROGRESS ||
               maintenanceStatus == MaintenanceStatus.PAUSED;
    }

    public boolean shouldStart() {
        return maintenanceStatus == MaintenanceStatus.PENDING &&
               scheduledStart != null &&
               LocalDateTime.now().isAfter(scheduledStart);
    }

    public boolean shouldEnd() {
        return maintenanceStatus == MaintenanceStatus.IN_PROGRESS &&
               scheduledEnd != null &&
               LocalDateTime.now().isAfter(scheduledEnd);
    }

    public boolean isOverdue() {
        return maintenanceStatus == MaintenanceStatus.IN_PROGRESS &&
               scheduledEnd != null &&
               LocalDateTime.now().isAfter(scheduledEnd);
    }

    public boolean isEmergency() {
        return maintenanceType == MaintenanceType.EMERGENCY;
    }

    public boolean isGlobal() {
        return impactScope == ImpactScope.GLOBAL;
    }

    public boolean affectsGame(String gameId) {
        return isGlobal() || (impactScope == ImpactScope.GAME && this.gameId != null && this.gameId.equals(gameId));
    }

    public void start() {
        this.maintenanceStatus = MaintenanceStatus.IN_PROGRESS;
        this.actualStart = LocalDateTime.now();
    }

    public void complete(String notes) {
        this.maintenanceStatus = MaintenanceStatus.COMPLETED;
        this.actualEnd = LocalDateTime.now();
        this.completionNotes = notes;
        this.progressPercent = 100;
    }

    public void pause() {
        this.maintenanceStatus = MaintenanceStatus.PAUSED;
    }

    public void resume() {
        if (maintenanceStatus == MaintenanceStatus.PAUSED) {
            this.maintenanceStatus = MaintenanceStatus.IN_PROGRESS;
        }
    }

    public void cancel(String reason) {
        this.maintenanceStatus = MaintenanceStatus.CANCELLED;
        this.completionNotes = reason;
    }

    public void extend(LocalDateTime newEndTime) {
        this.maintenanceStatus = MaintenanceStatus.EXTENDED;
        this.extendedUntil = newEndTime;
        this.scheduledEnd = newEndTime;
    }

    public long getActualDurationMinutes() {
        if (actualStart != null && actualEnd != null) {
            return java.time.Duration.between(actualStart, actualEnd).toMinutes();
        }
        if (actualStart != null) {
            return java.time.Duration.between(actualStart, LocalDateTime.now()).toMinutes();
        }
        return 0;
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
