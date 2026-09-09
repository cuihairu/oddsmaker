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
 * Crash 指标服务测试：CH 降级与三组查询（分组/趋势/版本崩溃率）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Crash 指标服务测试")
class CrashMetricsServiceTest {

    @Mock
    private ClickHouseClient client;

    private CrashMetricsService service;

    @BeforeEach
    void setUp() {
        service = new CrashMetricsService(client);
    }

    @Test
    @DisplayName("CH 未配置三个接口均降级")
    void degrades() {
        when(client.isAvailable()).thenReturn(false);
        assertFalse((Boolean) service.topGroups("g", null, null).get("available"));
        assertFalse((Boolean) service.trend("g", null, null).get("available"));
        assertFalse((Boolean) service.rateByVersion("g", null, null).get("available"));
        verify(client, never()).query(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("Top 分组：crash_hash 聚合行映射输出")
    void topGroupsQueries() {
        when(client.isAvailable()).thenReturn(true);
        when(client.query(contains("crash_hash"), any(Object[].class)))
            .thenReturn(List.of(Map.of(
                "crash_group", "abc123", "sample_message", "NPE",
                "occurrences", 30L, "affected_devices", 9L,
                "first_seen", Date.valueOf("2026-09-02"), "last_seen", Date.valueOf("2026-09-09"))));

        Map<String, Object> resp = service.topGroups("g", "prod", 14);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups = (List<Map<String, Object>>) resp.get("groups");
        assertEquals("abc123", groups.get(0).get("crashGroup"));
        assertEquals(30L, groups.get(0).get("occurrences"));
    }

    @Test
    @DisplayName("趋势：按日崩溃次数与影响设备")
    void trendQueries() {
        when(client.isAvailable()).thenReturn(true);
        when(client.query(contains("event_type = 'error'"), any(Object[].class)))
            .thenReturn(List.of(Map.of("bucket", Date.valueOf("2026-09-08"), "crashes", 12L, "affected_devices", 5L)));

        Map<String, Object> resp = service.trend("g", null, 7);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> points = (List<Map<String, Object>>) resp.get("points");
        assertEquals(12L, points.get(0).get("crashes"));
    }

    @Test
    @DisplayName("环境过滤：查询携带 environment 参数")
    void envFilterAppendsParameter() {
        when(client.isAvailable()).thenReturn(true);
        when(client.query(anyString(), any(Object[].class))).thenReturn(List.of());

        service.trend("g", "prod", 14);
        verify(client).query(contains("event_type = 'error'"), eq("g"), eq("prod"), any());

        service.trend("g", "prod", 14);  // 再次调用走同分支（趋势查询聚合行）
        verify(client, org.mockito.Mockito.times(2))
            .query(contains("GROUP BY bucket"), eq("g"), eq("prod"), any());
    }

    @Test
    @DisplayName("Top 分组与版本率的环境过滤参数")
    void envFilterBranches() {
        when(client.isAvailable()).thenReturn(true);
        when(client.query(anyString(), any(Object[].class))).thenReturn(List.of());

        service.topGroups("g", "prod", 14);
        service.rateByVersion("g", "prod", 14);

        // topGroups：3 参数；rateByVersion：双子查询 6 参数
        verify(client).query(contains("AND environment = ?"), eq("g"), eq("prod"), any());
        verify(client).query(contains("AS active_devices"), eq("g"), eq("prod"), any(),
            eq("g"), eq("prod"), any());
    }

    @Test
    @DisplayName("版本崩溃率：双子查询 JOIN 与天数夹取")
    void rateByVersionAndClamp() {
        when(client.isAvailable()).thenReturn(true);
        when(client.query(anyString(), any(Object[].class))).thenReturn(List.of());

        service.rateByVersion("g", null, 999);
        // 双子查询：crash（error）与 active（全事件）各一次带参
        verify(client, org.mockito.Mockito.times(1))
            .query(contains("AS crash_devices"), any(Object[].class));
        assertEquals(14, CrashMetricsService.clampDays(null));
        assertEquals(180, CrashMetricsService.clampDays(999));
    }
}
