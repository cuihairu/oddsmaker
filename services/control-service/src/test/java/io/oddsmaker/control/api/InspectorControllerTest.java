package io.oddsmaker.control.api;

import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.InspectorProxyService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 实时事件检视 Controller 测试：权限门卫与参数委托。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("实时事件检视 Controller 测试")
class InspectorControllerTest {

    @Mock
    private AccessGuard accessGuard;

    @Mock
    private InspectorProxyService proxyService;

    @InjectMocks
    private InspectorController controller;

    @Test
    @DisplayName("recent：game:read 权限门卫 + 参数透传代理")
    void recentDelegatesWithPermissionGuard() {
        when(proxyService.recent("game_a", "prod", "rejected", 25))
                .thenReturn(Map.of("available", true, "count", 1));

        Map<String, Object> resp = controller.recent("game_a", "prod", "rejected", 25).getBody();

        verify(accessGuard).requireGamePermission("game_a", "game:read");
        assertEquals(1, resp.get("count"));
        assertEquals(true, resp.get("available"));
    }

    @Test
    @DisplayName("recent：缺省 outcome/limit 以 null 透传")
    void recentPassesNullDefaults() {
        when(proxyService.recent("game_a", "dev", null, null))
                .thenReturn(Map.of("available", false));

        Map<String, Object> resp = controller.recent("game_a", "dev", null, null).getBody();

        verify(accessGuard).requireGamePermission("game_a", "game:read");
        assertEquals(false, resp.get("available"));
    }
}
