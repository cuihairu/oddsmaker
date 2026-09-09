package io.oddsmaker.control.api;

import io.oddsmaker.control.service.AuditLogService;
import io.oddsmaker.control.service.FinanceMetricsService;
import io.oddsmaker.control.security.AccessGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 财报导出 Controller 测试：报表委托、CSV 下载头（BOM/Content-Disposition）与导出审计。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("财务导出 Controller 测试")
class FinanceMetricsControllerTest {

    @Mock
    private FinanceMetricsService service;

    @Mock
    private AccessGuard accessGuard;

    @Mock
    private AuditLogService auditLog;

    @InjectMocks
    private FinanceMetricsController controller;

    @Test
    @DisplayName("报表：game:read 鉴权 + 委托")
    void reportDelegates() {
        Map<String, Object> resp = Map.of("available", true);
        when(service.report("g", null, "day", 30)).thenReturn(resp);

        assertEquals(resp, controller.report("g", null, "day", 30).getBody());
        verify(accessGuard).requireGamePermission("g", "game:read");

        when(service.report("g", "prod", "month", 90)).thenReturn(resp);
        assertEquals(resp, controller.report("g", "prod", "month", 90).getBody());
    }

    @Test
    @DisplayName("导出：UTF-8 BOM + CSV 头 + 附件名 + EXPORT 审计")
    void exportReturnsCsvWithBomAndAudits() {
        when(service.exportCsv("g", null, "month", 90)).thenReturn("a,b\n1,2\n");

        var response = controller.export("g", null, "month", 90);

        assertEquals(200, response.getStatusCode().value());
        String fileName = response.getHeaders().getContentDisposition().getFilename();
        assertTrue(fileName != null && fileName.startsWith("finance_g_") && fileName.endsWith(".csv"),
                "unexpected filename: " + fileName);
        assertTrue(String.valueOf(response.getHeaders().getFirst("Content-Type")).contains("text/csv"));
        byte[] body = response.getBody();
        assertEquals(0xEF, body[0] & 0xFF);  // BOM
        assertEquals("a,b\n1,2\n", new String(body, 3, body.length - 3, StandardCharsets.UTF_8));
        verify(auditLog).logDataExport(eq("finance_export"), eq("g"), contains("finance_g_month.csv"),
            eq("api"), eq("api"), isNull());
    }
}
