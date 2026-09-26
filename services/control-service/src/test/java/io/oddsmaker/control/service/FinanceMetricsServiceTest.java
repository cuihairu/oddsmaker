package io.oddsmaker.control.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.sql.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 财务指标服务测试：CH 降级、日/月粒度查询合并、CSV 导出前置校验。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("财务指标服务测试")
class FinanceMetricsServiceTest {

    @Mock
    private ClickHouseClient client;

    private FinanceMetricsService service;

    @BeforeEach
    void setUp() {
        service = new FinanceMetricsService(client);
    }

    @Test
    @DisplayName("CH 未配置降级 available=false，导出直接拒绝")
    void degradesAndExportRejected() {
        when(client.isAvailable()).thenReturn(false);
        Map<String, Object> resp = service.report("g", null, "day", 30, null);
        assertEquals(false, resp.get("available"));
        verify(client, never()).query(anyString(), any(Object[].class));
        assertThrows(IllegalStateException.class, () -> service.exportCsv("g", null, "day", 30, null));
    }

    @Test
    @DisplayName("报表：活跃/收入行与新增行合并输出指标与汇总")
    void reportMergesRows() {
        when(client.isAvailable()).thenReturn(true);
        when(client.query(contains("uniqExact"), any(Object[].class)))
            .thenReturn(List.of(Map.of(
                "stat_date", Date.valueOf("2026-09-08"), "dau", 100L,
                "revenue", new BigDecimal("250.00"), "payers", 10L, "orders", 15L)));
        when(client.query(contains("v_user_first_seen"), any(Object[].class)))
            .thenReturn(List.of(Map.of("stat_date", Date.valueOf("2026-09-08"), "new_users", 40L)));

        Map<String, Object> resp = service.report("g", null, "day", 30, null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) resp.get("rows");
        assertEquals(2.5, rows.get(0).get("arpu"));
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) resp.get("summary");
        assertEquals(1000.0 * 0 + 250.0, summary.get("totalRevenue"));
        assertEquals(15L, summary.get("totalOrders"));
    }

    @Test
    @DisplayName("月粒度应用 toStartOfMonth；CSV 输出表头与数据行")
    void monthGranularityAndCsv() {
        when(client.isAvailable()).thenReturn(true);
        when(client.query(contains("toStartOfMonth"), any(Object[].class))).thenReturn(List.of());
        when(client.query(contains("v_user_first_seen"), any(Object[].class))).thenReturn(List.of());

        Map<String, Object> resp = service.report("g", "prod", "month", 180, null);
        assertEquals("month", resp.get("granularity"));

        String csv = service.exportCsv("g", "prod", "month", 180, null);
        assertEquals("stat_date,new_users,dau,revenue,payers,orders,arpu,arppu,payment_rate", csv.split("\n")[0]);
    }

    @Test
    @DisplayName("粒度归一与窗口夹取")
    void normalizesAndClamps() {
        assertEquals("month", FinanceMetricsService.normalizeGranularity("month"));
        assertEquals("day", FinanceMetricsService.normalizeGranularity("weekly"));
        assertEquals(90, FinanceMetricsService.clampDays(null));
        assertEquals(730, FinanceMetricsService.clampDays(99999));
        assertEquals("event_date", FinanceMetricsService.bucketApply("", "event_date"));
        assertEquals("toStartOfMonth(cohort_date)", FinanceMetricsService.bucketApply("toStartOfMonth", "cohort_date"));
    }

    @Test
    @DisplayName("环境过滤：blank 环境等同不过滤（isBlank 侧）；clampDays 0/负数回落默认")
    void blankEnvironmentAndNonPositiveDays() {
        when(client.isAvailable()).thenReturn(true);
        when(client.query(anyString(), any(Object[].class))).thenReturn(List.of());
        service.report("g", "   ", "day", 30, null);
        // blank 环境：无 environment 参数位，varargs 只带 gameId + since
        verify(client).query(contains("uniqExact"), org.mockito.ArgumentMatchers.eq("g"), any());
        verify(client).query(contains("v_user_first_seen"), org.mockito.ArgumentMatchers.eq("g"), any());
        assertEquals(90, FinanceMetricsService.clampDays(0));
        assertEquals(90, FinanceMetricsService.clampDays(-7));
    }

    @Test
    @DisplayName("分群过滤：activity 走主体口径子查询、new_users 走 user_id 子查询")
    void segmentFilterInjectsBothQueries() {
        when(client.isAvailable()).thenReturn(true);
        when(client.query(contains("uniqExact"), any(Object[].class))).thenReturn(List.of());
        when(client.query(contains("v_user_first_seen"), any(Object[].class))).thenReturn(List.of());

        Map<String, Object> resp = service.report("g", "prod", "day", 30, "seg1");

        assertEquals("seg1", resp.get("segmentId"));
        // activity：主体口径成员过滤；new_users：user_id 键成员过滤
        verify(client).query(contains("if(player_id != '', player_id"), any(Object[].class));
        verify(client).query(contains("AND user_id IN (SELECT subject_id FROM segment_members"), any(Object[].class));
        // 两查询参数均为 (g, prod, since, seg1, g)
        org.mockito.ArgumentCaptor<Object[]> activityArgs = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(client).query(contains("uniqExact"), (Object[]) activityArgs.capture());
        Object[] aa = activityArgs.getValue();
        assertEquals(5, aa.length);
        assertEquals("g", aa[0]);
        assertEquals("prod", aa[1]);
        assertEquals("seg1", aa[3]);
        assertEquals("g", aa[4]);
        org.mockito.ArgumentCaptor<Object[]> newArgs = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(client).query(contains("v_user_first_seen"), (Object[]) newArgs.capture());
        Object[] na = newArgs.getValue();
        assertEquals(5, na.length);
        assertEquals("seg1", na[3]);
    }

}
