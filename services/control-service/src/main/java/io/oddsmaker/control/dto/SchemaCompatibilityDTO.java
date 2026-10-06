package io.oddsmaker.control.dto;

import io.oddsmaker.control.jpa.TrackingPlanEntity;

import java.util.List;

/**
 * B7 EventSchema 版本兼容检查结果（计划书 §2.3）：
 * draft 事件集与同 game+environmentId 基线 ACTIVE 版本的事件集 diff——
 * addedEvents=新版本新增、removedEvents=新版本下线（BACKWARD 违例）、
 * changedEvents=同名事件的类型/必填/重要性变化（信息项，不参与判兼容）。
 */
public class SchemaCompatibilityDTO {

    public String schemaId;                      // 待检查（draft）Schema id
    public TrackingPlanEntity.Compatibility mode; // 检查所用兼容策略
    public String baselineId;                    // 基线 ACTIVE 版本 id（无基线=null）
    public boolean compatible;                   // 按策略判定是否兼容
    public List<String> addedEvents;
    public List<String> removedEvents;
    public List<String> changedEvents;
}
