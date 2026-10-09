package io.oddsmaker.control.api;

import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.IdentityMergeService;
import io.oddsmaker.control.service.IdentityService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 身份 API 测试：显式合并端点（privacy:manage 门禁、by 缺省取当前登录用户）
 * 与既有只读端点的 game:read 门禁保持。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("身份 API 测试")
class IdentityControllerTest {

    private static final String GAME = "game_demo";

    @Mock private IdentityService identityService;
    @Mock private IdentityMergeService identityMergeService;
    @Mock private AccessGuard accessGuard;

    private IdentityController controller;

    @BeforeEach
    void setUp() {
        controller = new IdentityController();
        ReflectionTestUtils.setField(controller, "identityService", identityService);
        ReflectionTestUtils.setField(controller, "identityMergeService", identityMergeService);
        ReflectionTestUtils.setField(controller, "accessGuard", accessGuard);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("显式合并：privacy:manage 门禁，by 缺省取当前登录用户")
    void mergeDelegatesWithOperatorFallback() {
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("alice", "n/a", "ROLE_ADMIN"));
        IdentityController.MergeReq req = new IdentityController.MergeReq();
        req.primaryId = "idt_a";
        req.secondaryId = "idt_b";
        req.reason = "同设备确认";
        Map<String, Object> summary = Map.of("identityId", "idt_a", "movedLinks", 1);
        when(identityMergeService.merge(GAME, "idt_a", "idt_b", "同设备确认", "alice")).thenReturn(summary);

        ResponseEntity<Map<String, Object>> out = controller.merge(GAME, req);

        assertEquals(200, out.getStatusCode().value());
        assertSame(summary, out.getBody());
        verify(accessGuard).requireGamePermission(GAME, "privacy:manage");
    }

    @Test
    @DisplayName("显式合并：请求显式携带 by 时优先，无登录上下文也可执行（服务间调用）")
    void mergeUsesExplicitBy() {
        IdentityController.MergeReq req = new IdentityController.MergeReq();
        req.primaryId = "idt_a";
        req.secondaryId = "idt_b";
        req.reason = "工单 123";
        req.by = "ops_bot";
        when(identityMergeService.merge(GAME, "idt_a", "idt_b", "工单 123", "ops_bot"))
                .thenReturn(Map.of("identityId", "idt_a"));

        controller.merge(GAME, req);

        verify(identityMergeService).merge(GAME, "idt_a", "idt_b", "工单 123", "ops_bot");
    }

    @Test
    @DisplayName("只读端点保持 game:read 门禁（by-identifier 反查）")
    void readEndpointsKeepGameReadGuard() {
        when(identityService.findByIdentifier(GAME, "device_id", "dev_a")).thenReturn(List.of());

        controller.findByIdentifier(GAME, "device_id", "dev_a");

        verify(accessGuard).requireGamePermission(GAME, "game:read");
    }
}
