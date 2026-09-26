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
        assertFalse((Boolean) service.topGroups("g", null, null, null).get("available"));
        assertFalse((Boolean) service.trend("g", null, null, null).get("available"));
        assertFalse((Boolean) service.rateByVersion("g", null, null, null).get("available"));
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

        Map<String, Object> resp = service.topGroups("g", "prod", 14, null);
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

        Map<String, Object> resp = service.trend("g", null, 7, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> points = (List<Map<String, Object>>) resp.get("points");
        assertEquals(12L, points.get(0).get("crashes"));
    }

    @Test
    @DisplayName("环境过滤：查询携带 environment 参数")
    void envFilterAppendsParameter() {
        when(client.isAvailable()).thenReturn(true);
        when(client.query(anyString(), any(Object[].class))).thenReturn(List.of());

        service.trend("g", "prod", 14, null);
        verify(client).query(contains("event_type = 'error'"), eq("g"), eq("prod"), any());

        service.trend("g", "prod", 14, null);  // 再次调用走同分支（趋势查询聚合行）
        verify(client, org.mockito.Mockito.times(2))
            .query(contains("GROUP BY bucket"), eq("g"), eq("prod"), any());
    }

    @Test
    @DisplayName("Top 分组与版本率的环境过滤参数")
    void envFilterBranches() {
        when(client.isAvailable()).thenReturn(true);
        when(client.query(anyString(), any(Object[].class))).thenReturn(List.of());

        service.topGroups("g", "prod", 14, null);
        service.rateByVersion("g", "prod", 14, null);

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

        service.rateByVersion("g", null, 999, null);
        // 双子查询：crash（error）与 active（全事件）各一次带参
        verify(client, org.mockito.Mockito.times(1))
            .query(contains("AS crash_devices"), any(Object[].class));
        assertEquals(14, CrashMetricsService.clampDays(null));
        assertEquals(180, CrashMetricsService.clampDays(999));
    }

    @Test
    @DisplayName("环境过滤：blank 环境三个接口均走无过滤分支（isBlank 侧）")
    void blankEnvironmentSkipsEnvFilter() {
        when(client.isAvailable()).thenReturn(true);
        when(client.query(anyString(), any(Object[].class))).thenReturn(List.of());
        service.topGroups("g", "   ", 14, null);
        service.trend("g", "   ", 14, null);
        service.rateByVersion("g", "   ", 14, null);
        // 三个接口在 blank 环境下均不携带 environment 参数位
        verify(client).query(contains("occurrences DESC"), org.mockito.ArgumentMatchers.eq("g"), any());
        verify(client).query(contains("GROUP BY bucket"), org.mockito.ArgumentMatchers.eq("g"), any());
        verify(client).query(contains("AS active_devices"),
            org.mockito.ArgumentMatchers.eq("g"), any(), org.mockito.ArgumentMatchers.eq("g"), any());
    }

    @Test
    @DisplayName("分群过滤：三接口注入主体口径成员过滤，参数依 (g, prod, since, seg, g) 顺序")
    void segmentFilterInjectsMembershipSubquery() {
        when(client.isAvailable()).thenReturn(true);
        when(client.query(anyString(), any(Object[].class))).thenReturn(List.of());

        Map<String, Object> top = service.topGroups("g", "prod", 14, "seg1");
        assertEquals("seg1", top.get("segmentId"));
        Map<String, Object> trendResp = service.trend("g", "prod", 14, "seg1");
        assertEquals("seg1", trendResp.get("segmentId"));
        // rateByVersion：分子/分母两子查询都过滤
        Map<String, Object> rates = service.rateByVersion("g", "prod", 14, "seg1");
        assertEquals("seg1", rates.get("segmentId"));

        // 主体口径片段（player>user>device）出现在 top/趋势/版本率 SQL 中
        verify(client, org.mockito.Mockito.times(3))
            .query(contains("if(player_id != '', player_id"), any(Object[].class));
        verify(client, org.mockito.Mockito.times(3))
            .query(contains("IN (SELECT subject_id FROM segment_members"), any(Object[].class));

        // topGroups / trend：单子查询参数位 (g, prod, since, seg1, g)
        org.mockito.ArgumentCaptor<Object[]> single = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(client).query(contains("occurrences DESC"), (Object[]) single.capture());
        assertEquals(5, single.getValue().length);
        assertEquals("seg1", single.getValue()[3]);
        assertEquals("g", single.getValue()[4]);
        org.mockito.ArgumentCaptor<Object[]> trendArgs = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(client).query(contains("GROUP BY bucket"), (Object[]) trendArgs.capture());
        assertEquals(5, trendArgs.getValue().length);

        // 版本率参数 (g, prod, since, seg1, g) × 2
        org.mockito.ArgumentCaptor<Object[]> rateArgs = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(client).query(contains("AS active_devices"), (Object[]) rateArgs.capture());
        Object[] ra = rateArgs.getValue();
        assertEquals(10, ra.length);
        assertEquals("seg1", ra[3]);
        assertEquals("g", ra[4]);
        assertEquals("g", ra[5]);
        assertEquals("seg1", ra[8]);
        assertEquals("g", ra[9]);
    }

}
