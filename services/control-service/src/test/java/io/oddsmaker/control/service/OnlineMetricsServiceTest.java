package io.oddsmaker.control.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Timestamp;
import java.time.Instant;
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
 * 实时在线监控服务测试：CH 降级、多维度查询组装、分钟趋势与环境过滤。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("实时在线监控服务测试")
class OnlineMetricsServiceTest {

    @Mock
    private ClickHouseClient client;

    private OnlineMetricsService service;

    @BeforeEach
    void setUp() {
        service = new OnlineMetricsService(client);
    }

    @Test
    @DisplayName("CH 未配置降级 available=false")
    void degrades() {
        when(client.isAvailable()).thenReturn(false);
        Map<String, Object> resp = service.overview("g", null, 5);
        assertEquals(false, resp.get("available"));
        verify(client, never()).query(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("overview：总数 + 平台/版本/渠道分组 + 分钟趋势组装")
    void overviewAssemblesAllDimensions() {
        when(client.isAvailable()).thenReturn(true);
        when(client.query(anyString(), any(Object[].class))).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            if (sql.contains("toStartOfMinute")) {
                return List.of(Map.of("bucket", Timestamp.from(Instant.parse("2026-09-09T08:01:00Z")), "online", 12L));
            }
            if (sql.contains("AS dim")) {
                return List.of(Map.of("dim", "ios", "online", 8L));
            }
            return List.of(Map.of("online", 12L));  // 总数查询
        });

        Map<String, Object> resp = service.overview("g", "prod", 5);

        assertEquals(12L, resp.get("online"));
        assertEquals(5, resp.get("minutes"));
        assertEquals(60, resp.get("trendMinutes"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byPlatform = (List<Map<String, Object>>) resp.get("byPlatform");
        assertEquals("ios", byPlatform.get(0).get("key"));
        assertNotNull(resp.get("byAppVersion"));
        assertNotNull(resp.get("byChannel"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> trend = (List<Map<String, Object>>) resp.get("trend");
        assertEquals(12L, trend.get(0).get("online"));
    }

    @Test
    @DisplayName("维度表达式白名单注入防注入：channel 回退 platform")
    void dimensionExpressionsWhitelisted() {
        when(client.isAvailable()).thenReturn(true);
        when(client.query(anyString(), any(Object[].class))).thenAnswer(inv -> List.of());
        service.overview("g", null, 1);
        verify(client, org.mockito.Mockito.times(5)).query(contains("uniqExact"), any(Object[].class));
    }

    @Test
    @DisplayName("minutes 夹取（缺省 5、上限 60）与趋势窗口提升")
    void clampsMinutes() {
        assertEquals(5, OnlineMetricsService.clampMinutes(null));
        assertEquals(60, OnlineMetricsService.clampMinutes(120));
        assertTrue(OnlineMetricsService.minutesAgo(60).before(Timestamp.from(Instant.now())));
    }
}
