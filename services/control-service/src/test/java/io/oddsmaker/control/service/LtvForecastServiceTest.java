package io.oddsmaker.control.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * pLTV 服务测试：CH 降级与乘数拟合输出。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("pLTV 服务测试")
class LtvForecastServiceTest {

    @Mock
    private ClickHouseClient client;

    private LtvForecastService service;

    @BeforeEach
    void setUp() {
        service = new LtvForecastService(client);
    }

    @Test
    @DisplayName("环境过滤：两条查询均携带 environment 参数")
    void envFilterAppendsParameter() {
        when(client.isAvailable()).thenReturn(true);
        when(client.query(contains("v_user_first_seen"), any(Object[].class))).thenReturn(List.of());
        when(client.query(contains("v_ltv_by_cohort_day"), any(Object[].class))).thenReturn(List.of());

        Map<String, Object> resp = service.pltv("g", "prod", 90);

        assertEquals(true, resp.get("available"));
        verify(client).query(contains("v_user_first_seen"), eq("g"), eq("prod"), any());
        verify(client).query(contains("v_ltv_by_cohort_day"), eq("g"), eq("prod"), any());
    }

    @Test
    @DisplayName("CH 未配置降级 available=false")
    void degrades() {
        when(client.isAvailable()).thenReturn(false);
        Map<String, Object> resp = service.pltv("g", null, 90);
        assertFalse((Boolean) resp.get("available"));
        verify(client, never()).query(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("pltv：cohort 规模与 LTV 曲线查询合并输出乘数")
    void pltvQueriesAndForecasts() {
        when(client.isAvailable()).thenReturn(true);
        when(client.query(contains("v_user_first_seen"), any(Object[].class)))
            .thenReturn(List.of(Map.of("cohort", Date.valueOf("2026-09-01"), "cohort_size", 40L)));
        when(client.query(contains("v_ltv_by_cohort_day"), any(Object[].class)))
            .thenReturn(List.of(
                Map.of("cohort", Date.valueOf("2026-09-01"), "age_day", 0, "revenue", 30.0),
                Map.of("cohort", Date.valueOf("2026-09-01"), "age_day", 5, "revenue", 20.0)));

        Map<String, Object> resp = service.pltv("g", null, 90);

        assertEquals(0.0, resp.get("multiplier"));  // 无成熟 cohort
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> points = (List<Map<String, Object>>) resp.get("points");
        assertEquals(1, points.size());
        assertEquals(1.25, points.get(0).get("obsD7Arpu"));
        assertEquals(90, LtvForecastService.clampDays(null));
        assertEquals(730, LtvForecastService.clampDays(9999));
    }
}
