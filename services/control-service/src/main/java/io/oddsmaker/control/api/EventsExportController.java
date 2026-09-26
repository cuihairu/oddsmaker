package io.oddsmaker.control.api;

import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.AuditLogService;
import io.oddsmaker.control.service.EventsExportService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 全量原始数据导出 API（P7-4）。
 *
 * events 按日分区导出 JSONL（gzip 可选）到导出目录，供 Superset/Metabase 下钻。
 * 门卫与 ExportController 一致：export:execute。
 */
@RestController
@RequestMapping("/api")
public class EventsExportController {

    private final EventsExportService eventsExportService;
    private final AccessGuard accessGuard;
    private final AuditLogService auditLog;

    public EventsExportController(EventsExportService eventsExportService, AccessGuard accessGuard, AuditLogService auditLog) {
        this.eventsExportService = eventsExportService;
        this.accessGuard = accessGuard;
        this.auditLog = auditLog;
    }

    public static class ExportDayRequest {
        public String environment;
        public String date;
        public Boolean compress;
    }

    @PostMapping("/games/{gameId}/events-export")
    public ResponseEntity<Map<String, Object>> exportDay(
            @PathVariable String gameId, @RequestBody ExportDayRequest request) {
        accessGuard.requireGamePermission(gameId, "export:execute");
        Map<String, Object> result = eventsExportService.exportDay(
                gameId, request.environment, request.date, Boolean.TRUE.equals(request.compress));
        auditLog.logCreate("events_export", gameId + "/" + request.environment + "/" + request.date,
                "events-" + request.date, null, null, null, result);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/games/{gameId}/events-export")
    public ResponseEntity<List<Map<String, Object>>> listDays(
            @PathVariable String gameId,
            @RequestParam(value = "environment", required = false) String environment) {
        accessGuard.requireGamePermission(gameId, "export:execute");
        return ResponseEntity.ok(eventsExportService.listDays(gameId, environment));
    }
}
