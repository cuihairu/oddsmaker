package io.oddsmaker.control.api;

import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.LtvForecastService;
import io.oddsmaker.control.service.OnlineMetricsService;
import io.oddsmaker.control.service.PaymentFunnelService;
import io.oddsmaker.control.service.RetentionMetricsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 报表看板 Controller 测试：权限门卫与委托（留存/付费漏斗/在线/pLTV）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("报表看板 Controller 测试")
class MetricsControllersTest {

    @Mock
    private AccessGuard accessGuard;

    @Mock
    private RetentionMetricsService retentionService;

    @InjectMocks
    private RetentionMetricsController retentionController;

    @Mock
    private PaymentFunnelService funnelService;

    @InjectMocks
    private PaymentFunnelController funnelController;

    @Mock
    private OnlineMetricsService onlineService;

    @InjectMocks
    private OnlineMetricsController onlineController;

    @Mock
    private LtvForecastService ltvService;

    @InjectMocks
    private LtvForecastController ltvController;

    @Test
    @DisplayName("留存趋势：game:read 鉴权 + 参数透传")
    void retentionTrend() {
        Map<String, Object> resp = Map.of("available", true);
        when(retentionService.trend("g", "prod", "week", 30)).thenReturn(resp);

        assertEquals(resp, retentionController.trend("g", "prod", "week", 30).getBody());
        verify(accessGuard).requireGamePermission("g", "game:read");
    }

    @Test
    @DisplayName("付费漏斗：game:read 鉴权 + 委托")
    void paymentFunnel() {
        Map<String, Object> resp = Map.of("funnel", Map.of());
        when(funnelService.funnel("g", null, 90)).thenReturn(resp);

        assertEquals(resp, funnelController.funnel("g", null, 90).getBody());
        verify(accessGuard).requireGamePermission("g", "game:read");
    }

    @Test
    @DisplayName("在线监控：game:read 鉴权 + 委托")
    void onlineOverview() {
        Map<String, Object> resp = Map.of("online", 12L);
        when(onlineService.overview("g", null, 5)).thenReturn(resp);

        assertEquals(resp, onlineController.overview("g", null, 5).getBody());
        verify(accessGuard).requireGamePermission("g", "game:read");
    }

    @Test
    @DisplayName("pLTV：game:read 鉴权 + 委托")
    void pltv() {
        Map<String, Object> resp = Map.of("multiplier", 2.2);
        when(ltvService.pltv("g", "prod", 90)).thenReturn(resp);

        assertEquals(resp, ltvController.pltv("g", "prod", 90).getBody());
        verify(accessGuard).requireGamePermission("g", "game:read");
    }
}
