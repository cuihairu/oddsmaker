package io.oddsmaker.control.dto;

import io.oddsmaker.control.jpa.EventDefinitionEntity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 事件定义数据传输对象
 */
public class EventDefinitionDTO {

    public String id;
    public String trackingPlanId;

    @NotBlank(message = "事件名称不能为空")
    @Size(max = 100, message = "事件名称不能超过100个字符")
    public String eventName;

    @NotBlank(message = "事件类型不能为空")
    @Size(max = 50, message = "事件类型不能超过50个字符")
    public String eventType;

    @Size(max = 200, message = "显示名称不能超过200个字符")
    public String displayName;

    @Size(max = 1000, message = "描述不能超过1000个字符")
    public String description;

    @Size(max = 100, message = "分类不能超过100个字符")
    public String category;

    @Size(max = 100, message = "子分类不能超过100个字符")
    public String subcategory;

    public EventDefinitionEntity.EventIdentity identity;
    public Boolean requireUserId;
    public Boolean requireSessionId;
    public Boolean requirePlayerId;
    public String validationRules;
    public String examplePayload;
    public String docUrl;
    public EventDefinitionEntity.Importance importance;
    public EventDefinitionEntity.DefinitionStatus status;
    public Long usageCount;
    public LocalDateTime lastUsedAt;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
    public String createdBy;

    // 关联数据
    public List<EventPropertyDefinitionDTO> properties;
    public String trackingPlanName;

    public EventDefinitionDTO() {}

    public EventDefinitionDTO(EventDefinitionEntity entity) {
        this.id = entity.id;
        this.trackingPlanId = entity.trackingPlanId;
        this.eventName = entity.eventName;
        this.eventType = entity.eventType;
        this.displayName = entity.displayName;
        this.description = entity.description;
        this.category = entity.category;
        this.subcategory = entity.subcategory;
        this.identity = entity.identity;
        this.requireUserId = entity.requireUserId;
        this.requireSessionId = entity.requireSessionId;
        this.requirePlayerId = entity.requirePlayerId;
        this.validationRules = entity.validationRules;
        this.examplePayload = entity.examplePayload;
        this.docUrl = entity.docUrl;
        this.importance = entity.importance;
        this.status = entity.status;
        this.usageCount = entity.usageCount;
        this.lastUsedAt = entity.lastUsedAt;
        this.createdAt = entity.createdAt;
        this.updatedAt = entity.updatedAt;
        this.createdBy = entity.createdBy;
    }

    public EventDefinitionEntity toEntity() {
        EventDefinitionEntity entity = new EventDefinitionEntity();
        entity.id = this.id;
        entity.trackingPlanId = this.trackingPlanId;
        entity.eventName = this.eventName;
        entity.eventType = this.eventType;
        entity.displayName = this.displayName;
        entity.description = this.description;
        entity.category = this.category;
        entity.subcategory = this.subcategory;
        entity.identity = this.identity != null ? this.identity : EventDefinitionEntity.EventIdentity.DEVICE;
        entity.requireUserId = this.requireUserId != null ? this.requireUserId : false;
        entity.requireSessionId = this.requireSessionId != null ? this.requireSessionId : false;
        entity.requirePlayerId = this.requirePlayerId != null ? this.requirePlayerId : false;
        entity.validationRules = this.validationRules;
        entity.examplePayload = this.examplePayload;
        entity.docUrl = this.docUrl;
        entity.importance = this.importance != null ? this.importance : EventDefinitionEntity.Importance.NORMAL;
        entity.status = this.status != null ? this.status : EventDefinitionEntity.DefinitionStatus.ACTIVE;
        entity.usageCount = this.usageCount != null ? this.usageCount : 0L;
        entity.createdBy = this.createdBy;
        return entity;
    }

    public void updateEntity(EventDefinitionEntity entity) {
        if (this.displayName != null) entity.displayName = this.displayName;
        if (this.description != null) entity.description = this.description;
        if (this.category != null) entity.category = this.category;
        if (this.subcategory != null) entity.subcategory = this.subcategory;
        if (this.identity != null) entity.identity = this.identity;
        if (this.requireUserId != null) entity.requireUserId = this.requireUserId;
        if (this.requireSessionId != null) entity.requireSessionId = this.requireSessionId;
        if (this.requirePlayerId != null) entity.requirePlayerId = this.requirePlayerId;
        if (this.validationRules != null) entity.validationRules = this.validationRules;
        if (this.examplePayload != null) entity.examplePayload = this.examplePayload;
        if (this.docUrl != null) entity.docUrl = this.docUrl;
        if (this.importance != null) entity.importance = this.importance;
        if (this.status != null) entity.status = this.status;
    }

    public boolean isActive() {
        return status == EventDefinitionEntity.DefinitionStatus.ACTIVE;
    }

    public boolean isRequired() {
        return importance == EventDefinitionEntity.Importance.CRITICAL ||
               importance == EventDefinitionEntity.Importance.HIGH;
    }

    public boolean hasRequiredIdentity() {
        return Boolean.TRUE.equals(requireUserId) ||
               Boolean.TRUE.equals(requireSessionId) ||
               Boolean.TRUE.equals(requirePlayerId);
    }
}
