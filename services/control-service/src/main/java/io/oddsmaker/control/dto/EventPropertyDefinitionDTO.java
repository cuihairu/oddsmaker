package io.oddsmaker.control.dto;

import io.oddsmaker.control.jpa.EventPropertyDefinitionEntity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 事件属性定义数据传输对象
 */
public class EventPropertyDefinitionDTO {

    public String id;
    public String eventDefinitionId;

    @NotBlank(message = "属性名称不能为空")
    @Size(max = 100, message = "属性名称不能超过100个字符")
    public String propertyName;

    @Size(max = 200, message = "显示名称不能超过200个字符")
    public String displayName;

    @Size(max = 500, message = "描述不能超过500个字符")
    public String description;

    public EventPropertyDefinitionEntity.PropertyType type;
    public String arrayElementType;
    public Boolean required;
    public String defaultValue;
    public String allowedValues;
    public Double minValue;
    public Double maxValue;
    public Integer minLength;
    public Integer maxLength;
    public String regexPattern;
    public Boolean isPii;
    public String piiType;
    public Boolean isIndexed;
    public String propertyGroup;
    public Integer displayOrder;
    public EventPropertyDefinitionEntity.PropertyStatus status;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;

    public EventPropertyDefinitionDTO() {}

    public EventPropertyDefinitionDTO(EventPropertyDefinitionEntity entity) {
        this.id = entity.id;
        this.eventDefinitionId = entity.eventDefinitionId;
        this.propertyName = entity.propertyName;
        this.displayName = entity.displayName;
        this.description = entity.description;
        this.type = entity.type;
        this.arrayElementType = entity.arrayElementType;
        this.required = entity.required;
        this.defaultValue = entity.defaultValue;
        this.allowedValues = entity.allowedValues;
        this.minValue = entity.minValue;
        this.maxValue = entity.maxValue;
        this.minLength = entity.minLength;
        this.maxLength = entity.maxLength;
        this.regexPattern = entity.regexPattern;
        this.isPii = entity.isPii;
        this.piiType = entity.piiType;
        this.isIndexed = entity.isIndexed;
        this.propertyGroup = entity.propertyGroup;
        this.displayOrder = entity.displayOrder;
        this.status = entity.status;
        this.createdAt = entity.createdAt;
        this.updatedAt = entity.updatedAt;
    }

    public EventPropertyDefinitionEntity toEntity() {
        EventPropertyDefinitionEntity entity = new EventPropertyDefinitionEntity();
        entity.id = this.id;
        entity.eventDefinitionId = this.eventDefinitionId;
        entity.propertyName = this.propertyName;
        entity.displayName = this.displayName;
        entity.description = this.description;
        entity.type = this.type != null ? this.type : EventPropertyDefinitionEntity.PropertyType.STRING;
        entity.arrayElementType = this.arrayElementType;
        entity.required = this.required != null ? this.required : false;
        entity.defaultValue = this.defaultValue;
        entity.allowedValues = this.allowedValues;
        entity.minValue = this.minValue;
        entity.maxValue = this.maxValue;
        entity.minLength = this.minLength;
        entity.maxLength = this.maxLength;
        entity.regexPattern = this.regexPattern;
        entity.isPii = this.isPii != null ? this.isPii : false;
        entity.piiType = this.piiType;
        entity.isIndexed = this.isIndexed != null ? this.isIndexed : true;
        entity.propertyGroup = this.propertyGroup;
        entity.displayOrder = this.displayOrder != null ? this.displayOrder : 0;
        entity.status = this.status != null ? this.status : EventPropertyDefinitionEntity.PropertyStatus.ACTIVE;
        return entity;
    }

    public void updateEntity(EventPropertyDefinitionEntity entity) {
        if (this.displayName != null) entity.displayName = this.displayName;
        if (this.description != null) entity.description = this.description;
        if (this.type != null) entity.type = this.type;
        if (this.arrayElementType != null) entity.arrayElementType = this.arrayElementType;
        if (this.required != null) entity.required = this.required;
        if (this.defaultValue != null) entity.defaultValue = this.defaultValue;
        if (this.allowedValues != null) entity.allowedValues = this.allowedValues;
        if (this.minValue != null) entity.minValue = this.minValue;
        if (this.maxValue != null) entity.maxValue = this.maxValue;
        if (this.minLength != null) entity.minLength = this.minLength;
        if (this.maxLength != null) entity.maxLength = this.maxLength;
        if (this.regexPattern != null) entity.regexPattern = this.regexPattern;
        if (this.isPii != null) entity.isPii = this.isPii;
        if (this.piiType != null) entity.piiType = this.piiType;
        if (this.isIndexed != null) entity.isIndexed = this.isIndexed;
        if (this.propertyGroup != null) entity.propertyGroup = this.propertyGroup;
        if (this.displayOrder != null) entity.displayOrder = this.displayOrder;
        if (this.status != null) entity.status = this.status;
    }

    public boolean isActive() {
        return status == EventPropertyDefinitionEntity.PropertyStatus.ACTIVE;
    }

    public boolean isSensitive() {
        return Boolean.TRUE.equals(isPii);
    }

    public boolean hasValidation() {
        return minValue != null || maxValue != null ||
               minLength != null || maxLength != null ||
               regexPattern != null || allowedValues != null;
    }
}
