package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.DashboardEntity;
import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.AuditLogService;
import io.oddsmaker.control.service.DashboardService;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 自定义仪表盘 Controller 测试：权限门卫（读/写分工）与审计。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("自定义仪表盘 Controller 测试")
class DashboardControllerTest {

    @Mock
    private DashboardService dashboardService;

    @Mock
    private AccessGuard accessGuard;

    @Mock
    private AuditLogService auditLog;

    @InjectMocks
    private DashboardController controller;

    private DashboardEntity entity() {
        DashboardEntity e = new DashboardEntity();
        e.id = "dash123";
        e.gameId = "game_a";
        e.name = "ops_daily";
        e.layout = "{\"widgets\":[{\"type\":\"kpi\",\"source\":\"online-overview\"}]}";
        return e;
    }

    @Test
    @DisplayName("create：dashboard:manage 门卫 + 审计")
    void createGuardsAndAudits() {
        when(dashboardService.create(eq("game_a"), eq("ops_daily"), isNull(),
                eq("{\"widgets\":[]}"))).thenReturn(entity());

        DashboardController.CreateDashboardRequest req = new DashboardController.CreateDashboardRequest();
        req.name = "ops_daily";
        req.layout = "{\"widgets\":[]}";

        DashboardEntity resp = controller.create("game_a", req).getBody();

        assertEquals("dash123", resp.id);
        verify(accessGuard).requireGamePermission("game_a", "dashboard:manage");
        verify(auditLog).logCreate(eq("dashboard"), eq("dash123"), eq("ops_daily"), isNull(), isNull(),
                isNull(), any(Map.class));
    }

    @Test
    @DisplayName("get/list：dashboard:read 门卫（get 按实体归属游戏）")
    void readGuardsByEntityGame() {
        when(dashboardService.get("dash123")).thenReturn(entity());
        when(dashboardService.listByGame("game_a")).thenReturn(List.of(entity()));

        controller.get("dash123");
        controller.list("game_a");

        // get（按实体归属游戏）与 list 两次读门卫均落 game_a
        verify(accessGuard, org.mockito.Mockito.times(2)).requireGamePermission("game_a", "dashboard:read");
    }

    @Test
    @DisplayName("update：dashboard:manage 门卫 + 审计")
    void updateGuardsAndAudits() {
        when(dashboardService.get("dash123")).thenReturn(entity());
        when(dashboardService.update(eq("dash123"), isNull(), isNull(), isNull())).thenReturn(entity());

        DashboardController.UpdateDashboardRequest req = new DashboardController.UpdateDashboardRequest();

        DashboardEntity resp = controller.update("dash123", req).getBody();

        assertEquals("dash123", resp.id);
        verify(accessGuard).requireGamePermission("game_a", "dashboard:manage");
        verify(auditLog).logUpdate(eq("dashboard"), eq("dash123"), eq("ops_daily"), isNull(), isNull(),
                isNull(), any(Map.class));
    }

    @Test
    @DisplayName("delete：软删成功审计")
    void deleteGuardsAndAudits() {
        when(dashboardService.get("dash123")).thenReturn(entity());
        when(dashboardService.delete("dash123")).thenReturn(true);

        Map<String, Object> resp = controller.delete("dash123").getBody();

        assertEquals(true, resp.get("deleted"));
        verify(accessGuard).requireGamePermission("game_a", "dashboard:manage");
        verify(auditLog).logDelete(eq("dashboard"), eq("dash123"), eq("ops_daily"), isNull(), isNull(), isNull());
    }

    @Test
    @DisplayName("create：layout null 时审计 widgets 计 0")
    void createWithNullLayoutAuditsZeroWidgets() {
        DashboardEntity e = entity();
        e.layout = null;
        when(dashboardService.create(eq("game_a"), eq("ops_daily"), isNull(), isNull())).thenReturn(e);

        DashboardController.CreateDashboardRequest req = new DashboardController.CreateDashboardRequest();
        req.name = "ops_daily";

        controller.create("game_a", req);

        @SuppressWarnings("rawtypes")
        org.mockito.ArgumentCaptor<Map> meta = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(auditLog).logCreate(eq("dashboard"), eq("dash123"), eq("ops_daily"), isNull(), isNull(),
                isNull(), meta.capture());
        assertEquals(0, meta.getValue().get("widgets"));
    }
}
