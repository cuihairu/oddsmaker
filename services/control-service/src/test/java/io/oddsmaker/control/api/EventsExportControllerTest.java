package io.oddsmaker.control.api;

import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.AuditLogService;
import io.oddsmaker.control.service.EventsExportService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 全量原始数据导出 Controller 测试：export:execute 门卫 + 审计 + 委托。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("全量原始数据导出 Controller 测试")
class EventsExportControllerTest {

    @Mock
    private EventsExportService eventsExportService;

    @Mock
    private AccessGuard accessGuard;

    @Mock
    private AuditLogService auditLog;

    @InjectMocks
    private EventsExportController controller;

    @Test
    @DisplayName("exportDay：export:execute 门卫 + 结果入审计")
    void exportDayGuardsAndAudits() {
        Map<String, Object> result = Map.of("rows", 42L, "date", "2026-09-20");
        when(eventsExportService.exportDay("game_a", "prod", "2026-09-20", true)).thenReturn(result);

        EventsExportController.ExportDayRequest req = new EventsExportController.ExportDayRequest();
        req.environment = "prod";
        req.date = "2026-09-20";
        req.compress = true;

        Map<String, Object> resp = controller.exportDay("game_a", req).getBody();

        assertEquals(42L, resp.get("rows"));
        verify(accessGuard).requireGamePermission("game_a", "export:execute");
        verify(auditLog).logCreate(eq("events_export"), eq("game_a/prod/2026-09-20"),
                eq("events-2026-09-20"), any(), any(), any(), eq(result));
    }

    @Test
    @DisplayName("listDays：export:execute 门卫 + 委托")
    void listDaysGuards() {
        when(eventsExportService.listDays("game_a", "prod")).thenReturn(List.of(Map.of("date", "2026-09-20")));

        List<Map<String, Object>> resp = controller.listDays("game_a", "prod").getBody();

        assertEquals(1, resp.size());
        verify(accessGuard).requireGamePermission("game_a", "export:execute");
    }
}
