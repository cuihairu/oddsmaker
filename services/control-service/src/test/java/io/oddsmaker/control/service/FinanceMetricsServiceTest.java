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
        Map<String, Object> resp = service.report("g", null, "day", 30);
        assertEquals(false, resp.get("available"));
        verify(client, never()).query(anyString(), any(Object[].class));
        assertThrows(IllegalStateException.class, () -> service.exportCsv("g", null, "day", 30));
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

        Map<String, Object> resp = service.report("g", null, "day", 30);

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

        Map<String, Object> resp = service.report("g", "prod", "month", 180);
        assertEquals("month", resp.get("granularity"));

        String csv = service.exportCsv("g", "prod", "month", 180);
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
}
