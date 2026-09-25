package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.SegmentEntity;
import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.AuditLogService;
import io.oddsmaker.control.service.SegmentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户分群 API（P7-2 可复用用户分群）。
 *
 * 定义 → 物化（ClickHouse segment_members）→ 报表过滤 / LiveOps 定向。
 * 读：segment:read；写：segment:manage。
 */
@RestController
@RequestMapping("/api")
public class SegmentController {

    private final SegmentService segmentService;
    private final AccessGuard accessGuard;
    private final AuditLogService auditLog;

    public SegmentController(SegmentService segmentService, AccessGuard accessGuard, AuditLogService auditLog) {
        this.segmentService = segmentService;
        this.accessGuard = accessGuard;
        this.auditLog = auditLog;
    }

    public static class CreateSegmentRequest {
        public String gameId;
        public String name;
        public String displayName;
        public String description;
        public String environment;
        public String subject;
        public String definition;
    }

    public static class UpdateSegmentRequest {
        public String displayName;
        public String description;
        public String status;
        public String definition;
    }

    @PostMapping("/games/{gameId}/segments")
    public ResponseEntity<SegmentEntity> create(
            @PathVariable String gameId, @RequestBody CreateSegmentRequest request) {
        accessGuard.requireGamePermission(gameId, "segment:manage");
        SegmentEntity created = segmentService.create(gameId, request.name, request.displayName,
                request.description, request.environment, request.subject, request.definition);
        auditLog.logCreate("segment", created.id, created.name, null, null, null,
                Map.of("gameId", gameId, "environment", created.environment));
        return ResponseEntity.ok(created);
    }

    @GetMapping("/games/{gameId}/segments")
    public ResponseEntity<List<SegmentEntity>> list(@PathVariable String gameId) {
        accessGuard.requireGamePermission(gameId, "segment:read");
        return ResponseEntity.ok(segmentService.listByGame(gameId));
    }

    @GetMapping("/segments/{segmentId}")
    public ResponseEntity<SegmentEntity> get(@PathVariable String segmentId) {
        SegmentEntity entity = segmentService.get(segmentId);
        accessGuard.requireGamePermission(entity.gameId, "segment:read");
        return ResponseEntity.ok(entity);
    }

    @PutMapping("/segments/{segmentId}")
    public ResponseEntity<SegmentEntity> update(
            @PathVariable String segmentId, @RequestBody UpdateSegmentRequest request) {
        SegmentEntity existing = segmentService.get(segmentId);
        accessGuard.requireGamePermission(existing.gameId, "segment:manage");
        SegmentEntity updated = segmentService.update(segmentId, request.displayName,
                request.description, request.status, request.definition);
        auditLog.logUpdate("segment", updated.id, updated.name, null, null, null, null);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/segments/{segmentId}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String segmentId) {
        SegmentEntity existing = segmentService.get(segmentId);
        accessGuard.requireGamePermission(existing.gameId, "segment:manage");
        boolean removed = segmentService.delete(segmentId);
        if (removed) {
            auditLog.logDelete("segment", segmentId, existing.name, null, null, null);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("deleted", removed);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/segments/{segmentId}/compute")
    public ResponseEntity<Map<String, Object>> compute(@PathVariable String segmentId) {
        SegmentEntity existing = segmentService.get(segmentId);
        accessGuard.requireGamePermission(existing.gameId, "segment:manage");
        Map<String, Object> result = segmentService.compute(segmentId);
        auditLog.logUpdate("segment", segmentId, existing.name, null, null, null, result);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/segments/{segmentId}/members")
    public ResponseEntity<Map<String, Object>> members(
            @PathVariable String segmentId, @RequestParam(required = false) Integer limit) {
        SegmentEntity existing = segmentService.get(segmentId);
        accessGuard.requireGamePermission(existing.gameId, "segment:read");
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("segmentId", segmentId);
        resp.put("memberCount", existing.memberCount);
        resp.put("members", segmentService.members(segmentId, limit == null ? 100 : limit));
        return ResponseEntity.ok(resp);
    }
}
