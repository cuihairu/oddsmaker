package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.FunnelConfigEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 可配置漏斗结果服务测试：CH 降级、聚合组装（总体/逐步转化/流失）、环境过滤、归属校验。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("可配置漏斗结果服务测试")
class FunnelResultsServiceTest {

    @Mock
    private ClickHouseClient client;

    @Mock
    private FunnelConfigService funnelConfigService;

    private FunnelResultsService service;

    @BeforeEach
    void setUp() {
        service = new FunnelResultsService(client, funnelConfigService);
    }

    private static FunnelConfigEntity funnel(String id, String gameId) {
        FunnelConfigEntity f = new FunnelConfigEntity();
        f.id = id;
        f.gameId = gameId;
        f.name = "signup-funnel";
        f.type = FunnelConfigEntity.FunnelType.SEQUENTIAL;
        return f;
    }

    @Test
    @DisplayName("CH 未配置降级 available=false，零查询")
    void degrades() {
        when(funnelConfigService.findById("f1")).thenReturn(funnel("f1", "g"));
        when(client.isAvailable()).thenReturn(false);

        Map<String, Object> resp = service.results("f1", "g", null, 90);

        assertEquals(Boolean.FALSE, resp.get("available"));
        assertEquals("signup-funnel", resp.get("funnelName"));
        assertEquals("SEQUENTIAL", resp.get("funnelType"));
        verify(client, never()).query(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("归属校验：funnelId 不属于该游戏抛 IAE")
    void rejectsForeignGame() {
        when(funnelConfigService.findById("f1")).thenReturn(funnel("f1", "other"));

        assertThrows(IllegalArgumentException.class,
                () -> service.results("f1", "g", null, 90));
        verify(client, never()).query(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("聚合组装：两步行 → overall/stepRate/dropOff，首步基准 100%")
    void assemblesSteps() {
        when(funnelConfigService.findById("f1")).thenReturn(funnel("f1", "g"));
        when(client.isAvailable()).thenReturn(true);
        when(client.query(anyString(), any(Object[].class))).thenReturn(List.of(
                Map.of("step", 1, "step_name", "Install", "users", 100L),
                Map.of("step", 2, "step_name", "Pay", "users", 20L)));

        Map<String, Object> resp = service.results("f1", "g", null, 30);

        assertEquals(Boolean.TRUE, resp.get("available"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) resp.get("steps");
        assertEquals(2, steps.size());

        assertEquals("Install", steps.get(0).get("stepName"));
        assertEquals(100L, steps.get(0).get("users"));
        assertEquals(1.0, steps.get(0).get("overallRate"));
        assertNull(steps.get(0).get("stepRate"));
        assertEquals(0L, steps.get(0).get("dropOff"));

        assertEquals("Pay", steps.get(1).get("stepName"));
        assertEquals(20L, steps.get(1).get("users"));
        assertEquals(0.2, (double) steps.get(1).get("overallRate"), 1e-9);
        assertEquals(0.2, (double) steps.get(1).get("stepRate"), 1e-9);
        assertEquals(80L, steps.get(1).get("dropOff"));

        @SuppressWarnings("unchecked")
        Map<String, Object> overall = (Map<String, Object>) resp.get("overall");
        assertEquals(100L, overall.get("firstUsers"));
        assertEquals(20L, overall.get("lastUsers"));
        assertEquals(0.2, (double) overall.get("rate"), 1e-9);
    }

    @Test
    @DisplayName("环境过滤：environment 非空走四参绑定，空白走三参")
    void environmentFilterVariants() {
        when(funnelConfigService.findById("f1")).thenReturn(funnel("f1", "g"));
        when(client.isAvailable()).thenReturn(true);
        when(client.query(anyString(), any(Object[].class))).thenReturn(List.of());

        service.results("f1", "g", "prod", 30);
        verify(client).query(contains("environment = ?"), eq("f1"), eq("g"), eq("prod"), any());

        service.results("f1", "g", "  ", 30);
        verify(client).query(contains("event_date >="), eq("f1"), eq("g"), any());
    }

    @Test
    @DisplayName("空 CH 行：steps=[]、overall=null")
    void emptyRows() {
        when(funnelConfigService.findById("f1")).thenReturn(funnel("f1", "g"));
        when(client.isAvailable()).thenReturn(true);
        when(client.query(anyString(), any(Object[].class))).thenReturn(List.of());

        Map<String, Object> resp = service.results("f1", "g", null, 90);

        assertTrue(((List<?>) resp.get("steps")).isEmpty());
        assertNull(resp.get("overall"));
    }

    @Test
    @DisplayName("clampDays：null/0 → 90 默认，超大值 → 365 上限")
    void clampDaysMatrix() {
        assertEquals(90, FunnelResultsService.clampDays(null));
        assertEquals(90, FunnelResultsService.clampDays(0));
        assertEquals(30, FunnelResultsService.clampDays(30));
        assertEquals(365, FunnelResultsService.clampDays(10000));
    }

    @Test
    @DisplayName("查询参数含窗口起点日期")
    void queryBindsSinceDate() {
        when(funnelConfigService.findById("f1")).thenReturn(funnel("f1", "g"));
        when(client.isAvailable()).thenReturn(true);
        when(client.query(anyString(), any(Object[].class))).thenReturn(List.of());

        service.results("f1", "g", null, 30);

        LocalDate expectedSince = LocalDate.now().minusDays(30);
        verify(client).query(anyString(), eq("f1"), eq("g"), eq(expectedSince));
    }

    @Test
    @DisplayName("对侧：funnel.type null 透传；首步零用户的 overallRate/stepRate/rate 回落侧")
    void funnelNullTypeAndZeroFirstUsersSides() {
        // 45 行 type null 侧
        FunnelConfigEntity noType = funnel("f1", "g");
        noType.type = null;
        when(funnelConfigService.findById("f1")).thenReturn(noType);
        when(client.isAvailable()).thenReturn(false);
        Map<String, Object> degraded = service.results("f1", "g", null, 90);
        assertNull(degraded.get("funnelType"));

        // 89/90/105 行：首步 0 用户 → overallRate 0.0、stepRate null、总转化 0.0
        Map<String, Object> resp = new java.util.HashMap<>();
        FunnelResultsService.assembleSteps(resp, List.of(
            Map.of("step", 1, "users", 0L, "step_name", "s1"),
            Map.of("step", 2, "users", 5L, "step_name", "s2")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) resp.get("steps");
        assertEquals(0.0, steps.get(1).get("overallRate"));
        assertNull(steps.get(1).get("stepRate"));
        assertEquals(0L, steps.get(1).get("dropOff"));   // Math.max(0, 0-5) = 0
        @SuppressWarnings("unchecked")
        Map<String, Object> overall = (Map<String, Object>) resp.get("overall");
        assertEquals(0.0, overall.get("rate"));
        assertEquals(0L, overall.get("firstUsers"));
    }

}
