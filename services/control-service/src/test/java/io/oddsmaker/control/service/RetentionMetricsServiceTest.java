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
    @DisplayName("分群过滤：优先走 retention_daily 预聚合（subject_id 维度 + 成员子查询下推）")
    void segmentTrendPrefersPreaggregatedDaily() {
        when(client.isAvailable()).thenReturn(true);
        when(client.query(contains("subject_id != ''"), any(Object[].class)))
            .thenReturn(List.of(
                Map.of("cohort", Date.valueOf("2026-09-01"), "d", 0, "users", 50L),
                Map.of("cohort", Date.valueOf("2026-09-01"), "d", 1, "users", 20L)));

        Map<String, Object> resp = service.trend("g", "prod", "day", 30, "seg1");

        assertEquals("seg1", resp.get("segmentId"));
        // 预聚合有数据时不触发 events 实时回退
        verify(client, never()).query(contains("ARRAY JOIN [0, 1, 7, 30]"), any(Object[].class));
        // 成员过滤下推：主体口径 + segment_members 子查询
        verify(client).query(contains("IN (SELECT subject_id FROM segment_members"), any(Object[].class));
        // 参数依 SQL 顺序：(g, prod, since, seg1, g)
        org.mockito.ArgumentCaptor<Object[]> args = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(client).query(contains("subject_id != ''"), (Object[]) args.capture());
        Object[] a = args.getValue();
        assertEquals(5, a.length);
        assertEquals("g", a[0]);
        assertEquals("prod", a[1]);
        assertEquals(java.time.LocalDate.class, a[2].getClass());
        assertEquals("seg1", a[3]);
        assertEquals("g", a[4]);
        // 输出列形与无分群路径一致，复用同一 assembler
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> points = (List<Map<String, Object>>) resp.get("points");
        assertEquals(0.4, points.get(0).get("d1Rate"));
    }

    @Test
    @DisplayName("分群回退：预聚合无数据走 events 实时计算，钳制 90 天 + max_execution_time 限时")
    void segmentTrendFallsBackToRealtimeWithGuards() {
        when(client.isAvailable()).thenReturn(true);
        when(client.query(contains("subject_id != ''"), any(Object[].class))).thenReturn(List.of());
        when(client.query(contains("ARRAY JOIN [0, 1, 7, 30]"), any(Object[].class)))
            .thenReturn(List.of(
                Map.of("cohort", Date.valueOf("2026-09-01"), "d", 0, "users", 50L),
                Map.of("cohort", Date.valueOf("2026-09-01"), "d", 1, "users", 20L)));

        Map<String, Object> resp = service.trend("g", "prod", "day", 365, "seg1");

        assertEquals("seg1", resp.get("segmentId"));
        // 回退 SQL 携带查询超时保护
        verify(client).query(contains("SETTINGS max_execution_time = 15"), any(Object[].class));
        // cohort/活跃子查询都按主体口径过滤成员
        verify(client, org.mockito.Mockito.times(2))
            .query(contains("if(player_id != '', player_id"), any(Object[].class));
        // 参数依 SQL 顺序：cohort(g, prod, seg1, g) → 活跃(g, prod, seg1, g) → 钳制后 since（90 天，与 days=365 无关）
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
        java.time.LocalDate clamped = (java.time.LocalDate) a[8];
        assertTrue(clamped.isAfter(java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(91)));
        assertTrue(clamped.isBefore(java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(89)));
    }

}
