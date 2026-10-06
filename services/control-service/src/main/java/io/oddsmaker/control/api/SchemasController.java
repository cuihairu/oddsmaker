package io.oddsmaker.control.api;

import io.oddsmaker.control.dto.*;
import io.oddsmaker.control.service.TrackingPlanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * B7 EventSchema 一等资源 API（计划书 §2.3/§4.4）。
 * 与 TrackingPlanController 同一底层（TrackingPlanService，同一存储），仅按
 * EventSchema 语义命名暴露：activate 语义换成 publish（带兼容门），另提供只读
 * 兼容检查端点。事件/属性定义管理原样镜像，供控制台 schemas 资源组页面单前缀使用。
 */
@RestController
@RequestMapping("/api/games/{gameId}/schemas")
@Tag(name = "Event Schemas", description = "EventSchema versioned resource: publish, compatibility check, event definitions")
public class SchemasController {

    @Autowired
    private TrackingPlanService trackingPlanService;

    // ========== Schema（版本）管理 ==========

    @PostMapping
    @Operation(summary = "Create event schema", description = "Create a new EventSchema version (draft) for a game")
    public ResponseEntity<ApiResponse<TrackingPlanDTO>> createSchema(
            @PathVariable String gameId,
            @RequestBody TrackingPlanDTO dto) {
        dto.gameId = gameId;
        TrackingPlanDTO result = trackingPlanService.createTrackingPlan(gameId, dto);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping
    @Operation(summary = "List event schemas", description = "List all EventSchema versions for a game")
    public ResponseEntity<ApiResponse<List<TrackingPlanDTO>>> listSchemas(
            @PathVariable String gameId) {
        return ResponseEntity.ok(ApiResponse.success(trackingPlanService.listTrackingPlans(gameId)));
    }

    @GetMapping("/active")
    @Operation(summary = "Get active event schemas", description = "Get active EventSchema versions for a game")
    public ResponseEntity<ApiResponse<List<TrackingPlanDTO>>> getActiveSchemas(
            @PathVariable String gameId) {
        return ResponseEntity.ok(ApiResponse.success(trackingPlanService.getActiveTrackingPlans(gameId)));
    }

    @GetMapping("/{schemaId}")
    @Operation(summary = "Get event schema", description = "Get EventSchema version by ID")
    public ResponseEntity<ApiResponse<TrackingPlanDTO>> getSchema(
            @PathVariable String gameId,
            @PathVariable String schemaId) {
        return trackingPlanService.getTrackingPlan(schemaId)
            .map(dto -> ResponseEntity.ok(ApiResponse.success(dto)))
            .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{schemaId}")
    @Operation(summary = "Update event schema", description = "Update EventSchema version (draft only)")
    public ResponseEntity<ApiResponse<TrackingPlanDTO>> updateSchema(
            @PathVariable String gameId,
            @PathVariable String schemaId,
            @RequestBody TrackingPlanDTO dto) {
        return ResponseEntity.ok(ApiResponse.success(trackingPlanService.updateTrackingPlan(schemaId, dto)));
    }

    @PostMapping("/{schemaId}/publish")
    @Operation(summary = "Publish event schema", description = "Activate a draft EventSchema with compatibility gate (mode != NONE runs baseline compat check first)")
    public ResponseEntity<ApiResponse<TrackingPlanDTO>> publishSchema(
            @PathVariable String gameId,
            @PathVariable String schemaId,
            @Parameter(description = "User ID performing the publish")
            @RequestParam(defaultValue = "system") String userId) {
        return ResponseEntity.ok(ApiResponse.success(
                trackingPlanService.publishTrackingPlan(schemaId, userId)));
    }

    @GetMapping("/{schemaId}/compatibility")
    @Operation(summary = "Check event schema compatibility", description = "Diff draft event set against baseline active version (read-only)")
    public ResponseEntity<ApiResponse<SchemaCompatibilityDTO>> checkCompatibility(
            @PathVariable String gameId,
            @PathVariable String schemaId) {
        return ResponseEntity.ok(ApiResponse.success(
                trackingPlanService.compatibilityCheck(schemaId)));
    }

    @PostMapping("/{schemaId}/deactivate")
    @Operation(summary = "Deactivate event schema", description = "Deactivate an active EventSchema version")
    public ResponseEntity<ApiResponse<TrackingPlanDTO>> deactivateSchema(
            @PathVariable String gameId,
            @PathVariable String schemaId) {
        return ResponseEntity.ok(ApiResponse.success(
                trackingPlanService.deactivateTrackingPlan(schemaId)));
    }

    @DeleteMapping("/{schemaId}")
    @Operation(summary = "Delete event schema", description = "Delete EventSchema version (draft only)")
    public ResponseEntity<ApiResponse<Void>> deleteSchema(
            @PathVariable String gameId,
            @PathVariable String schemaId) {
        trackingPlanService.deleteTrackingPlan(schemaId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // ========== 事件定义管理 ==========

    @PostMapping("/{schemaId}/events")
    @Operation(summary = "Create event definition", description = "Create a new event definition in EventSchema")
    public ResponseEntity<ApiResponse<EventDefinitionDTO>> createEventDefinition(
            @PathVariable String gameId,
            @PathVariable String schemaId,
            @RequestBody EventDefinitionDTO dto) {
        dto.trackingPlanId = schemaId;
        return ResponseEntity.ok(ApiResponse.success(
                trackingPlanService.createEventDefinition(schemaId, dto)));
    }

    @GetMapping("/{schemaId}/events")
    @Operation(summary = "List event definitions", description = "List all event definitions in EventSchema")
    public ResponseEntity<ApiResponse<List<EventDefinitionDTO>>> listEventDefinitions(
            @PathVariable String gameId,
            @PathVariable String schemaId) {
        return ResponseEntity.ok(ApiResponse.success(
                trackingPlanService.listEventDefinitions(schemaId)));
    }

    @GetMapping("/{schemaId}/events/{eventDefinitionId}")
    @Operation(summary = "Get event definition", description = "Get event definition by ID")
    public ResponseEntity<ApiResponse<EventDefinitionDTO>> getEventDefinition(
            @PathVariable String gameId,
            @PathVariable String schemaId,
            @PathVariable String eventDefinitionId) {
        return trackingPlanService.getEventDefinition(eventDefinitionId)
            .map(dto -> ResponseEntity.ok(ApiResponse.success(dto)))
            .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{schemaId}/events/{eventDefinitionId}")
    @Operation(summary = "Update event definition", description = "Update event definition")
    public ResponseEntity<ApiResponse<EventDefinitionDTO>> updateEventDefinition(
            @PathVariable String gameId,
            @PathVariable String schemaId,
            @PathVariable String eventDefinitionId,
            @RequestBody EventDefinitionDTO dto) {
        return ResponseEntity.ok(ApiResponse.success(
                trackingPlanService.updateEventDefinition(eventDefinitionId, dto)));
    }

    @DeleteMapping("/{schemaId}/events/{eventDefinitionId}")
    @Operation(summary = "Delete event definition", description = "Delete event definition from EventSchema")
    public ResponseEntity<ApiResponse<Void>> deleteEventDefinition(
            @PathVariable String gameId,
            @PathVariable String schemaId,
            @PathVariable String eventDefinitionId) {
        trackingPlanService.deleteEventDefinition(eventDefinitionId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // ========== 属性定义管理 ==========

    @PostMapping("/{schemaId}/events/{eventDefinitionId}/properties")
    @Operation(summary = "Create property definition", description = "Create a new property definition for event")
    public ResponseEntity<ApiResponse<EventPropertyDefinitionDTO>> createPropertyDefinition(
            @PathVariable String gameId,
            @PathVariable String schemaId,
            @PathVariable String eventDefinitionId,
            @RequestBody EventPropertyDefinitionDTO dto) {
        dto.eventDefinitionId = eventDefinitionId;
        return ResponseEntity.ok(ApiResponse.success(
                trackingPlanService.createPropertyDefinition(eventDefinitionId, dto)));
    }

    @GetMapping("/{schemaId}/events/{eventDefinitionId}/properties")
    @Operation(summary = "List property definitions", description = "List all property definitions for event")
    public ResponseEntity<ApiResponse<List<EventPropertyDefinitionDTO>>> listPropertyDefinitions(
            @PathVariable String gameId,
            @PathVariable String schemaId,
            @PathVariable String eventDefinitionId) {
        return ResponseEntity.ok(ApiResponse.success(
                trackingPlanService.listPropertyDefinitions(eventDefinitionId)));
    }

    @PutMapping("/{schemaId}/events/{eventDefinitionId}/properties/{propertyDefinitionId}")
    @Operation(summary = "Update property definition", description = "Update a property definition (draft schemas only)")
    public ResponseEntity<ApiResponse<EventPropertyDefinitionDTO>> updatePropertyDefinition(
            @PathVariable String gameId,
            @PathVariable String schemaId,
            @PathVariable String eventDefinitionId,
            @PathVariable String propertyDefinitionId,
            @RequestBody EventPropertyDefinitionDTO dto) {
        return ResponseEntity.ok(ApiResponse.success(trackingPlanService.updatePropertyDefinition(
            eventDefinitionId, propertyDefinitionId, dto)));
    }

    @DeleteMapping("/{schemaId}/events/{eventDefinitionId}/properties/{propertyDefinitionId}")
    @Operation(summary = "Delete property definition", description = "Delete a property definition (draft schemas only)")
    public ResponseEntity<ApiResponse<Void>> deletePropertyDefinition(
            @PathVariable String gameId,
            @PathVariable String schemaId,
            @PathVariable String eventDefinitionId,
            @PathVariable String propertyDefinitionId) {
        trackingPlanService.deletePropertyDefinition(eventDefinitionId, propertyDefinitionId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
