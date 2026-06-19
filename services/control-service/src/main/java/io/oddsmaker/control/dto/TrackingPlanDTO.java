package io.oddsmaker.control.dto;

import io.oddsmaker.control.jpa.TrackingPlanEntity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 追踪计划数据传输对象
 */
public class TrackingPlanDTO {

    public String id;
    public String gameId;

    @NotBlank(message = "计划名称不能为空")
    @Size(max = 100, message = "计划名称不能超过100个字符")
    public String name;

    @Size(max = 200, message = "显示名称不能超过200个字符")
    public String displayName;

    @Size(max = 1000, message = "描述不能超过1000个字符")
    public String description;

    @Size(max = 20, message = "版本号不能超过20个字符")
    public String version;

    public TrackingPlanEntity.PlanStatus status;
    public String environmentId;
    public TrackingPlanEntity.ValidationStrictness strictness;
    public Boolean enableAutoValidation;
    public Boolean rejectUnknownEvents;
    public Integer totalEvents;
    public Integer activeEvents;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
    public LocalDateTime activatedAt;
    public LocalDateTime deactivatedAt;
    public String createdBy;
    public String activatedBy;

    // 关联数据
    public List<EventDefinitionDTO> eventDefinitions;
    public String gameName;
    public String environmentName;

    public TrackingPlanDTO() {}

    public TrackingPlanDTO(TrackingPlanEntity entity) {
        this.id = entity.id;
        this.gameId = entity.gameId;
        this.name = entity.name;
        this.displayName = entity.displayName;
        this.description = entity.description;
        this.version = entity.version;
        this.status = entity.status;
        this.environmentId = entity.environmentId;
        this.strictness = entity.strictness;
        this.enableAutoValidation = entity.enableAutoValidation;
        this.rejectUnknownEvents = entity.rejectUnknownEvents;
        this.totalEvents = entity.totalEvents;
        this.activeEvents = entity.activeEvents;
        this.createdAt = entity.createdAt;
        this.updatedAt = entity.updatedAt;
        this.activatedAt = entity.activatedAt;
        this.deactivatedAt = entity.deactivatedAt;
        this.createdBy = entity.createdBy;
        this.activatedBy = entity.activatedBy;
    }

    public TrackingPlanEntity toEntity() {
        TrackingPlanEntity entity = new TrackingPlanEntity();
        entity.id = this.id;
        entity.gameId = this.gameId;
        entity.name = this.name;
        entity.displayName = this.displayName;
        entity.description = this.description;
        entity.version = this.version;
        entity.status = this.status != null ? this.status : TrackingPlanEntity.PlanStatus.DRAFT;
        entity.environmentId = this.environmentId;
        entity.strictness = this.strictness != null ? this.strictness : TrackingPlanEntity.ValidationStrictness.STRICT;
        entity.enableAutoValidation = this.enableAutoValidation != null ? this.enableAutoValidation : true;
        entity.rejectUnknownEvents = this.rejectUnknownEvents != null ? this.rejectUnknownEvents : false;
        entity.totalEvents = this.totalEvents != null ? this.totalEvents : 0;
        entity.activeEvents = this.activeEvents != null ? this.activeEvents : 0;
        entity.createdBy = this.createdBy;
        return entity;
    }

    public void updateEntity(TrackingPlanEntity entity) {
        if (this.displayName != null) entity.displayName = this.displayName;
        if (this.description != null) entity.description = this.description;
        if (this.version != null) entity.version = this.version;
        if (this.strictness != null) entity.strictness = this.strictness;
        if (this.enableAutoValidation != null) entity.enableAutoValidation = this.enableAutoValidation;
        if (this.rejectUnknownEvents != null) entity.rejectUnknownEvents = this.rejectUnknownEvents;
    }

    public boolean isActive() {
        return status == TrackingPlanEntity.PlanStatus.ACTIVE;
    }

    public boolean isDraft() {
        return status == TrackingPlanEntity.PlanStatus.DRAFT;
    }

    public boolean canEdit() {
        return isDraft();
    }

    public boolean isGlobal() {
        return environmentId == null;
    }
}
