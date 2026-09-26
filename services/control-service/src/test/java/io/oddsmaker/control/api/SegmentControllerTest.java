package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.SegmentEntity;
import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.AuditLogService;
import io.oddsmaker.control.service.SegmentService;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户分群 Controller 测试：权限门卫（读/写分工）与委托。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("用户分群 Controller 测试")
class SegmentControllerTest {

    @Mock
    private SegmentService segmentService;

    @Mock
    private AccessGuard accessGuard;

    @Mock
    private AuditLogService auditLog;

    @InjectMocks
    private SegmentController controller;

    private SegmentEntity entity() {
        SegmentEntity e = new SegmentEntity();
        e.id = "seg123";
        e.gameId = "game_a";
        e.name = "whales";
        e.environment = "prod";
        return e;
    }

    @Test
    @DisplayName("create：segment:manage 门卫 + 审计")
    void createGuardsAndAudits() {
        when(segmentService.create(eq("game_a"), eq("whales"), eq("鲸鱼"), isNull(), isNull(),
                isNull(), eq("{\"conditions\":[]}"))).thenReturn(entity());

        SegmentController.CreateSegmentRequest req = new SegmentController.CreateSegmentRequest();
        req.name = "whales";
        req.displayName = "鲸鱼";
        req.definition = "{\"conditions\":[]}";

        SegmentEntity resp = controller.create("game_a", req).getBody();

        assertEquals("seg123", resp.id);
        verify(accessGuard).requireGamePermission("game_a", "segment:manage");
        verify(auditLog).logCreate(eq("segment"), eq("seg123"), eq("whales"), isNull(), isNull(),
                isNull(), any(Map.class));
    }

    @Test
    @DisplayName("get/list/members：segment:read 门卫（按实体归属游戏）")
    void readGuardsByEntityGame() {
        when(segmentService.get("seg123")).thenReturn(entity());
        when(segmentService.listByGame("game_a")).thenReturn(List.of(entity()));
        when(segmentService.members("seg123", 10)).thenReturn(List.of("p1"));

        controller.get("seg123");
        controller.list("game_a");
        Map<String, Object> members = controller.members("seg123", 10).getBody();

        // get/list/members 三次读门卫均按 game_a（get/members 先取实体拿归属游戏）
        verify(accessGuard, org.mockito.Mockito.times(3)).requireGamePermission("game_a", "segment:read");
        assertEquals(List.of("p1"), members.get("members"));
        assertEquals(0L, members.get("memberCount"));
    }

    @Test
    @DisplayName("compute：segment:manage 门卫 + 结果透传")
    void computeGuardsAndReturns() {
        when(segmentService.get("seg123")).thenReturn(entity());
        when(segmentService.compute("seg123")).thenReturn(Map.of("memberCount", 42L));

        Map<String, Object> resp = controller.compute("seg123").getBody();

        verify(accessGuard).requireGamePermission("game_a", "segment:manage");
        assertEquals(42L, resp.get("memberCount"));
    }

    @Test
    @DisplayName("delete：软删成功审计；get 前置校验同样走 manage")
    void deleteGuardsAndAudits() {
        when(segmentService.get("seg123")).thenReturn(entity());
        when(segmentService.delete("seg123")).thenReturn(true);

        Map<String, Object> resp = controller.delete("seg123").getBody();

        assertEquals(true, resp.get("deleted"));
        verify(accessGuard).requireGamePermission("game_a", "segment:manage");
        verify(auditLog).logDelete(eq("segment"), eq("seg123"), eq("whales"), isNull(), isNull(), isNull());
    }

    @Test
    @DisplayName("update：segment:manage 门卫（按实体归属游戏）+ 审计")
    void updateGuardsAndAudits() {
        when(segmentService.get("seg123")).thenReturn(entity());
        when(segmentService.update(eq("seg123"), any(), any(), any(), any())).thenReturn(entity());

        SegmentController.UpdateSegmentRequest req = new SegmentController.UpdateSegmentRequest();
        req.displayName = "新名称";

        SegmentEntity resp = controller.update("seg123", req).getBody();

        assertEquals("seg123", resp.id);
        verify(accessGuard).requireGamePermission("game_a", "segment:manage");
        verify(auditLog).logUpdate(eq("segment"), eq("seg123"), eq("whales"),
                isNull(), isNull(), isNull(), isNull());
    }

    @Test
    @DisplayName("delete：服务返回 false 时 deleted=false 且不审计")
    void deleteNotRemovedSkipsAudit() {
        when(segmentService.get("seg123")).thenReturn(entity());
        when(segmentService.delete("seg123")).thenReturn(false);

        Map<String, Object> resp = controller.delete("seg123").getBody();

        assertEquals(false, resp.get("deleted"));
        verify(auditLog, never()).logDelete(any(), any(), any(), any(), any(), any());
    }
}
