package io.oddsmaker.control.api;

import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.GameReadinessService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 游戏分析就绪度 Controller 测试：game:read 行内鉴权与参数透传。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("游戏分析就绪度 Controller 测试")
class GameReadinessControllerTest {

    @Mock
    private GameReadinessService service;

    @Mock
    private AccessGuard accessGuard;

    @InjectMocks
    private GameReadinessController controller;

    @Test
    @DisplayName("readiness：game:read 鉴权 + environment 透传 + 委托返回")
    void readinessDelegatesAndAuthorizes() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("gameId", "g");
        body.put("environment", "prod");
        body.put("available", Boolean.TRUE);
        body.put("analysisLastEventAt", "2026-10-18T08:00:00Z");
        when(service.readiness("g", "prod")).thenReturn(body);

        Map<String, Object> out = controller.readiness("g", "prod").getBody();

        assertNotNull(out);
        assertEquals("2026-10-18T08:00:00Z", out.get("analysisLastEventAt"));
        verify(accessGuard).requireGamePermission("g", "game:read");
    }

    @Test
    @DisplayName("readiness：environment 缺省以 null 透传")
    void readinessNullEnvironment() {
        when(service.readiness("g", null)).thenReturn(Map.of("available", Boolean.FALSE));

        Map<String, Object> out = controller.readiness("g", null).getBody();

        assertNotNull(out);
        assertEquals(Boolean.FALSE, out.get("available"));
        verify(service).readiness("g", null);
        verify(accessGuard).requireGamePermission("g", "game:read");
    }
}
