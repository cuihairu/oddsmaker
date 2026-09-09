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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 留存趋势报表服务测试：CH 降级、粒度 bucket、环境过滤参数与汇总输出。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("留存趋势报表服务测试")
class RetentionMetricsServiceTest {

    @Mock
    private ClickHouseClient client;

    private RetentionMetricsService service;

    @BeforeEach
    void setUp() {
        service = new RetentionMetricsService(client);
    }

    @Test
    @DisplayName("CH 未配置降级 available=false")
    void degrades() {
        when(client.isAvailable()).thenReturn(false);
        Map<String, Object> resp = service.trend("g", null, "day", 30);
        assertEquals(false, resp.get("available"));
        verify(client, never()).query(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("日粒度直查 retention_daily 并输出趋势与汇总")
    void trendDayGranularity() {
        when(client.isAvailable()).thenReturn(true);
        when(client.query(contains("FROM retention_daily"), eq("g"), any()))
            .thenReturn(List.of(
                Map.of("cohort", Date.valueOf("2026-09-01"), "d", 0, "users", 100L),
                Map.of("cohort", Date.valueOf("2026-09-01"), "d", 1, "users", 40L)));

        Map<String, Object> resp = service.trend("g", null, "day", 30);

        assertEquals(true, resp.get("available"));
        assertEquals("day", resp.get("granularity"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> points = (List<Map<String, Object>>) resp.get("points");
        assertEquals(0.4, points.get(0).get("d1Rate"));
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) resp.get("summary");
        assertEquals(100L, summary.get("totalNewUsers"));
    }

    @Test
    @DisplayName("周/月粒度应用对应 bucket 函数；环境过滤追加参数")
    void weekMonthBucketsAndEnvFilter() {
        when(client.isAvailable()).thenReturn(true);
        when(client.query(contains("toMonday"), any(Object[].class))).thenReturn(List.of());
        when(client.query(contains("toStartOfMonth"), any(Object[].class))).thenReturn(List.of());

        service.trend("g", "prod", "week", 30);
        verify(client).query(contains("toMonday"), eq("g"), eq("prod"), any());

        service.trend("g", "prod", "month", 30);
        verify(client).query(contains("toStartOfMonth"), eq("g"), eq("prod"), any());
    }

    @Test
    @DisplayName("非法粒度归一为 day；窗口天数夹取")
    void normalizesGranularityAndClampsDays() {
        assertEquals("day", RetentionMetricsService.normalizeGranularity("weekly!"));
        assertEquals("week", RetentionMetricsService.normalizeGranularity("week"));
        assertEquals(90, RetentionMetricsService.clampDays(null));
        assertEquals(730, RetentionMetricsService.clampDays(99999));
    }
}
