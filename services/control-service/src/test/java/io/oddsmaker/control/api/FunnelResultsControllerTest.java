package io.oddsmaker.control.api;

import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.FunnelResultsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 可配置漏斗结果 Controller 测试：game:read 行内鉴权与参数透传。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("可配置漏斗结果 Controller 测试")
class FunnelResultsControllerTest {

    @Mock
    private FunnelResultsService service;

    @Mock
    private AccessGuard accessGuard;

    @InjectMocks
    private FunnelResultsController controller;

    @Test
    @DisplayName("results：game:read 鉴权 + 参数透传 + 委托返回")
    void resultsDelegatesAndAuthorizes() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("funnelId", "f1");
        body.put("available", true);
        when(service.results("f1", "g", "prod", 30)).thenReturn(body);

        Map<String, Object> out = controller.results("f1", "g", "prod", 30).getBody();

        assertNotNull(out);
        assertEquals("f1", out.get("funnelId"));
        verify(accessGuard).requireGamePermission("g", "game:read");
    }

    @Test
    @DisplayName("results：environment/days 缺省时以 null 透传")
    void resultsNullOptionalParams() {
        when(service.results("f1", "g", null, null)).thenReturn(Map.of("available", Boolean.FALSE));

        Map<String, Object> out = controller.results("f1", "g", null, null).getBody();

        assertNotNull(out);
        assertEquals(Boolean.FALSE, out.get("available"));
        verify(service).results("f1", "g", null, null);
        verify(accessGuard).requireGamePermission("g", "game:read");
    }

    @Test
    @DisplayName("results：返回体为 service 原样引用（无二次包装）")
    void resultsPassthrough() {
        Map<String, Object> body = Map.of("steps", List.of(Map.of("step", 1)));
        when(service.results("f2", "g2", null, 90)).thenReturn(body);

        assertEquals(body, controller.results("f2", "g2", null, 90).getBody());
    }
}
