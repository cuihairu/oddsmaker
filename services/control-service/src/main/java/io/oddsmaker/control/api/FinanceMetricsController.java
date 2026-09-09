package io.oddsmaker.control.api;

import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.AuditLogService;
import io.oddsmaker.control.service.FinanceMetricsService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 财务核心指标 API（ClickHouse 数据源：events + v_user_first_seen）。
 * - /{gameId}/report 按日/月指标报表（JSON）：新增/DAU/收入/付费/订单 + ARPU/ARPPU/付费率
 * - /{gameId}/export CSV 导出（UTF-8 BOM，Excel 友好），导出动作全量审计。
 */
@RestController
@RequestMapping("/api/finance-metrics")
public class FinanceMetricsController {

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private final FinanceMetricsService financeMetricsService;
    private final AccessGuard accessGuard;
    private final AuditLogService auditLog;

    public FinanceMetricsController(FinanceMetricsService financeMetricsService,
                                    AccessGuard accessGuard,
                                    AuditLogService auditLog) {
        this.financeMetricsService = financeMetricsService;
        this.accessGuard = accessGuard;
        this.auditLog = auditLog;
    }

    @GetMapping("/{gameId}/report")
    public ResponseEntity<Map<String, Object>> report(
            @PathVariable String gameId,
            @RequestParam(value = "environment", required = false) String environment,
            @RequestParam(value = "granularity", defaultValue = "day") String granularity,
            @RequestParam(value = "days", required = false) Integer days) {
        accessGuard.requireGamePermission(gameId, "game:read");
        return ResponseEntity.ok(financeMetricsService.report(gameId, environment, granularity, days));
    }

    @GetMapping("/{gameId}/export")
    public ResponseEntity<byte[]> export(
            @PathVariable String gameId,
            @RequestParam(value = "environment", required = false) String environment,
            @RequestParam(value = "granularity", defaultValue = "day") String granularity,
            @RequestParam(value = "days", required = false) Integer days) {
        accessGuard.requireGamePermission(gameId, "game:read");
        String csv = financeMetricsService.exportCsv(gameId, environment, granularity, days);
        auditLog.logDataExport("finance_export", gameId,
                "finance_" + gameId + "_" + granularity + ".csv",
                currentOperator(), currentOperator(), null);

        byte[] csvBytes = csv.getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[UTF8_BOM.length + csvBytes.length];
        System.arraycopy(UTF8_BOM, 0, body, 0, UTF8_BOM.length);
        System.arraycopy(csvBytes, 0, body, UTF8_BOM.length, csvBytes.length);

        String fileName = "finance_" + gameId + "_"
                + DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(LocalDateTime.now()) + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(body);
    }

    private String currentOperator() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        return auth != null ? auth.getName() : "api";
    }
}
