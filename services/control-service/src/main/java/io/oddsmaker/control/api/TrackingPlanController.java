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
 * 追踪计划管理API
 */
@RestController
@RequestMapping("/api/games/{gameId}/tracking-plans")
@Tag(name = "Tracking Plans", description = "Tracking Plan and Event Definition Management")
public class TrackingPlanController {

    @Autowired
    private TrackingPlanService trackingPlanService;

    // ========== 追踪计划管理 ==========

    @PostMapping
    @Operation(summary = "Create tracking plan", description = "Create a new tracking plan for a game")
    public ResponseEntity<ApiResponse<TrackingPlanDTO>> createTrackingPlan(
            @PathVariable String gameId,
            @RequestBody TrackingPlanDTO dto) {
        dto.gameId = gameId;
        TrackingPlanDTO result = trackingPlanService.createTrackingPlan(gameId, dto);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/{trackingPlanId}")
    @Operation(summary = "Get tracking plan", description = "Get tracking plan by ID")
    public ResponseEntity<ApiResponse<TrackingPlanDTO>> getTrackingPlan(
            @PathVariable String gameId,
            @PathVariable String trackingPlanId) {
        return trackingPlanService.getTrackingPlan(trackingPlanId)
            .map(dto -> ResponseEntity.ok(ApiResponse.success(dto)))
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    @Operation(summary = "List tracking plans", description = "List all tracking plans for a game")
    public ResponseEntity<ApiResponse<List<TrackingPlanDTO>>> listTrackingPlans(
            @PathVariable String gameId) {
        List<TrackingPlanDTO> plans = trackingPlanService.listTrackingPlans(gameId);
        return ResponseEntity.ok(ApiResponse.success(plans));
    }

    @GetMapping("/active")
    @Operation(summary = "Get active tracking plans", description = "Get active tracking plans for a game")
    public ResponseEntity<ApiResponse<List<TrackingPlanDTO>>> getActiveTrackingPlans(
            @PathVariable String gameId) {
        List<TrackingPlanDTO> plans = trackingPlanService.getActiveTrackingPlans(gameId);
        return ResponseEntity.ok(ApiResponse.success(plans));
    }

    @GetMapping("/environment/{environmentId}")
    @Operation(summary = "Get tracking plans by environment", description = "Get tracking plans for a specific environment")
    public ResponseEntity<ApiResponse<List<TrackingPlanDTO>>> getTrackingPlansForEnvironment(
            @PathVariable String gameId,
            @PathVariable String environmentId) {
        List<TrackingPlanDTO> plans = trackingPlanService.getTrackingPlansForEnvironment(gameId, environmentId);
        return ResponseEntity.ok(ApiResponse.success(plans));
    }

    @PutMapping("/{trackingPlanId}")
    @Operation(summary = "Update tracking plan", description = "Update tracking plan (draft only)")
    public ResponseEntity<ApiResponse<TrackingPlanDTO>> updateTrackingPlan(
            @PathVariable String gameId,
            @PathVariable String trackingPlanId,
            @RequestBody TrackingPlanDTO dto) {
        TrackingPlanDTO result = trackingPlanService.updateTrackingPlan(trackingPlanId, dto);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/{trackingPlanId}/activate")
    @Operation(summary = "Activate tracking plan", description = "Activate a draft tracking plan")
    public ResponseEntity<ApiResponse<TrackingPlanDTO>> activateTrackingPlan(
            @PathVariable String gameId,
            @PathVariable String trackingPlanId,
            @Parameter(description = "User ID performing the activation")
            @RequestParam(defaultValue = "system") String userId) {
        TrackingPlanDTO result = trackingPlanService.activateTrackingPlan(trackingPlanId, userId);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/{trackingPlanId}/deactivate")
    @Operation(summary = "Deactivate tracking plan", description = "Deactivate an active tracking plan")
    public ResponseEntity<ApiResponse<TrackingPlanDTO>> deactivateTrackingPlan(
            @PathVariable String gameId,
            @PathVariable String trackingPlanId) {
        TrackingPlanDTO result = trackingPlanService.deactivateTrackingPlan(trackingPlanId);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @DeleteMapping("/{trackingPlanId}")
    @Operation(summary = "Delete tracking plan", description = "Delete tracking plan (draft only)")
    public ResponseEntity<ApiResponse<Void>> deleteTrackingPlan(
            @PathVariable String gameId,
            @PathVariable String trackingPlanId) {
        trackingPlanService.deleteTrackingPlan(trackingPlanId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // ========== 事件定义管理 ==========

    @PostMapping("/{trackingPlanId}/events")
    @Operation(summary = "Create event definition", description = "Create a new event definition in tracking plan")
    public ResponseEntity<ApiResponse<EventDefinitionDTO>> createEventDefinition(
            @PathVariable String gameId,
            @PathVariable String trackingPlanId,
            @RequestBody EventDefinitionDTO dto) {
        dto.trackingPlanId = trackingPlanId;
        EventDefinitionDTO result = trackingPlanService.createEventDefinition(trackingPlanId, dto);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/{trackingPlanId}/events")
    @Operation(summary = "List event definitions", description = "List all event definitions in tracking plan")
    public ResponseEntity<ApiResponse<List<EventDefinitionDTO>>> listEventDefinitions(
            @PathVariable String gameId,
            @PathVariable String trackingPlanId) {
        List<EventDefinitionDTO> events = trackingPlanService.listEventDefinitions(trackingPlanId);
        return ResponseEntity.ok(ApiResponse.success(events));
    }

    @GetMapping("/{trackingPlanId}/events/{eventDefinitionId}")
    @Operation(summary = "Get event definition", description = "Get event definition by ID")
    public ResponseEntity<ApiResponse<EventDefinitionDTO>> getEventDefinition(
            @PathVariable String gameId,
            @PathVariable String trackingPlanId,
            @PathVariable String eventDefinitionId) {
        return trackingPlanService.getEventDefinition(eventDefinitionId)
            .map(dto -> ResponseEntity.ok(ApiResponse.success(dto)))
            .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{trackingPlanId}/events/{eventDefinitionId}")
    @Operation(summary = "Update event definition", description = "Update event definition")
    public ResponseEntity<ApiResponse<EventDefinitionDTO>> updateEventDefinition(
            @PathVariable String gameId,
            @PathVariable String trackingPlanId,
            @PathVariable String eventDefinitionId,
            @RequestBody EventDefinitionDTO dto) {
        EventDefinitionDTO result = trackingPlanService.updateEventDefinition(eventDefinitionId, dto);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @DeleteMapping("/{trackingPlanId}/events/{eventDefinitionId}")
    @Operation(summary = "Delete event definition", description = "Delete event definition from tracking plan")
    public ResponseEntity<ApiResponse<Void>> deleteEventDefinition(
            @PathVariable String gameId,
            @PathVariable String trackingPlanId,
            @PathVariable String eventDefinitionId) {
        trackingPlanService.deleteEventDefinition(eventDefinitionId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // ========== 属性定义管理 ==========

    @PostMapping("/{trackingPlanId}/events/{eventDefinitionId}/properties")
    @Operation(summary = "Create property definition", description = "Create a new property definition for event")
    public ResponseEntity<ApiResponse<EventPropertyDefinitionDTO>> createPropertyDefinition(
            @PathVariable String gameId,
            @PathVariable String trackingPlanId,
            @PathVariable String eventDefinitionId,
            @RequestBody EventPropertyDefinitionDTO dto) {
        dto.eventDefinitionId = eventDefinitionId;
        EventPropertyDefinitionDTO result = trackingPlanService.createPropertyDefinition(eventDefinitionId, dto);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/{trackingPlanId}/events/{eventDefinitionId}/properties")
    @Operation(summary = "List property definitions", description = "List all property definitions for event")
    public ResponseEntity<ApiResponse<List<EventPropertyDefinitionDTO>>> listPropertyDefinitions(
            @PathVariable String gameId,
            @PathVariable String trackingPlanId,
            @PathVariable String eventDefinitionId) {
        List<EventPropertyDefinitionDTO> properties = trackingPlanService.listPropertyDefinitions(eventDefinitionId);
        return ResponseEntity.ok(ApiResponse.success(properties));
    }
}
