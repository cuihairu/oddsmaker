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
        Map<String, Object> resp = service.trend("g", null, "day", 30, null);
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

        Map<String, Object> resp = service.trend("g", null, "day", 30, null);

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

        service.trend("g", "prod", "week", 30, null);
        verify(client).query(contains("toMonday"), eq("g"), eq("prod"), any());

        service.trend("g", "prod", "month", 30, null);
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

    @Test
    @DisplayName("粒度 null/空白归一为 day")
    void normalizesBlankGranularityToDay() {
        assertEquals("day", RetentionMetricsService.normalizeGranularity(null));
        assertEquals("day", RetentionMetricsService.normalizeGranularity("  "));
    }

    @Test
    @DisplayName("环境过滤：blank 环境等同不过滤（isBlank 侧）；clampDays 0/负数回落默认")
    void blankEnvironmentAndNonPositiveDays() {
        when(client.isAvailable()).thenReturn(true);
        when(client.query(anyString(), any(Object[].class))).thenReturn(List.of());
        service.trend("g", "   ", "day", 30, null);
        verify(client).query(contains("FROM retention_daily"), org.mockito.ArgumentMatchers.eq("g"), any());
        assertEquals(90, RetentionMetricsService.clampDays(0));
        assertEquals(90, RetentionMetricsService.clampDays(-7));
    }

    @Test
    @DisplayName("分群过滤：不走 retention_daily，从 events 实时计算（ARRAY JOIN d0/1/7/30）")
    void segmentTrendComputesFromEvents() {
        when(client.isAvailable()).thenReturn(true);
        when(client.query(contains("ARRAY JOIN [0, 1, 7, 30]"), any(Object[].class)))
            .thenReturn(List.of(
                Map.of("cohort", Date.valueOf("2026-09-01"), "d", 0, "users", 50L),
                Map.of("cohort", Date.valueOf("2026-09-01"), "d", 1, "users", 20L)));

        Map<String, Object> resp = service.trend("g", "prod", "day", 30, "seg1");

        assertEquals("seg1", resp.get("segmentId"));
        verify(client, never()).query(contains("retention_daily"), any(Object[].class));
        // cohort 子查询与活跃子查询都按主体口径过滤分群成员
        verify(client).query(contains("if(player_id != '', player_id"), any(Object[].class));
        verify(client).query(contains("IN (SELECT subject_id FROM segment_members"), any(Object[].class));
        // 参数依 SQL 顺序：cohort(g, prod, seg1, g) → 活跃(g, prod, seg1, g) → since
        org.mockito.ArgumentCaptor<Object[]> args = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(client).query(contains("ARRAY JOIN [0, 1, 7, 30]"), (Object[]) args.capture());
        Object[] a = args.getValue();
        assertEquals(9, a.length);
        assertEquals("g", a[0]);
        assertEquals("prod", a[1]);
        assertEquals("seg1", a[2]);
        assertEquals("g", a[3]);
        assertEquals("g", a[4]);
        assertEquals("prod", a[5]);
        assertEquals("seg1", a[6]);
        assertEquals("g", a[7]);
        // 输出列形与 retention_daily 一致，复用同一 assembler
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> points = (List<Map<String, Object>>) resp.get("points");
        assertEquals(0.4, points.get(0).get("d1Rate"));
    }

}
