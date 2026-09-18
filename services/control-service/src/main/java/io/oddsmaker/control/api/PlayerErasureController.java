package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.PlayerErasureRequestEntity;
import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.PlayerErasureService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 玩家数据删除请求 API（GDPR erasure，敏感操作全程审计）。
 * 创建/列表/详情/取消：/api/privacy/erasure-requests[...]。
 * 清洗由后台 sweep 异步执行（PG 硬删 + ClickHouse mutation），进度看 execution_summary。
 */
@RestController
public class PlayerErasureController {

    private final PlayerErasureService playerErasureService;
    private final AccessGuard accessGuard;

    public PlayerErasureController(PlayerErasureService playerErasureService, AccessGuard accessGuard) {
        this.playerErasureService = playerErasureService;
        this.accessGuard = accessGuard;
    }

    /** 创建删除请求：{gameId, requestType(PLAYER_ID/USER_ID/DEVICE_ID), requestValue, scheduledFor?(ISO, null=立即)} */
    @PostMapping("/api/privacy/erasure-requests")
    public ResponseEntity<?> create(@RequestBody CreateRequest request) {
        try {
            accessGuard.requireGamePermission(request.gameId, "privacy:manage");
            LocalDateTime scheduledFor = null;
            if (request.scheduledFor != null && !request.scheduledFor.isBlank()) {
                try {
                    scheduledFor = LocalDateTime.parse(request.scheduledFor);
                } catch (DateTimeParseException e) {
                    return ResponseEntity.badRequest().body("scheduledFor must be ISO-8601 datetime");
                }
            }
            PlayerErasureRequestEntity req = playerErasureService.create(
                request.gameId, request.requestType, request.requestValue,
                scheduledFor, currentOperator());
            return ResponseEntity.ok(req);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /** 删除请求列表（按创建倒序） */
    @GetMapping("/api/privacy/erasure-requests")
    public ResponseEntity<?> list(@RequestParam(required = false) String gameId) {
        if (gameId == null || gameId.isBlank()) {
            return ResponseEntity.badRequest().body("gameId is required");
        }
        accessGuard.requireGamePermission(gameId, "privacy:read");
        List<PlayerErasureRequestEntity> requests = playerErasureService.list(gameId);
        return ResponseEntity.ok(requests);
    }

    @GetMapping("/api/privacy/erasure-requests/{requestId}")
    public ResponseEntity<?> get(@PathVariable String requestId) {
        try {
            PlayerErasureRequestEntity req = playerErasureService.get(requestId);
            accessGuard.requireGamePermission(req.gameId, "privacy:read");
            return ResponseEntity.ok(req);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /** 取消：仅 PENDING 可取消，其余状态 409 */
    @PostMapping("/api/privacy/erasure-requests/{requestId}/cancel")
    public ResponseEntity<?> cancel(@PathVariable String requestId) {
        PlayerErasureRequestEntity req;
        try {
            req = playerErasureService.get(requestId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
        accessGuard.requireGamePermission(req.gameId, "privacy:manage");
        try {
            return ResponseEntity.ok(playerErasureService.cancel(requestId, currentOperator()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(e.getMessage());
        }
    }

    private String currentOperator() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
            .getContext().getAuthentication();
        return auth != null ? auth.getName() : "api";
    }

    // Request DTO

    public static class CreateRequest {
        public String gameId;
        public String requestType;
        public String requestValue;
        public String scheduledFor;
    }
}
