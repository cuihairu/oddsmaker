package io.oddsmaker.control.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Date;
import java.time.LocalDate;
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
 * 付费漏斗服务测试：CH 降级、两条查询合并、成熟 cohort 口径。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("付费漏斗服务测试")
class PaymentFunnelServiceTest {

    @Mock
    private ClickHouseClient client;

    private PaymentFunnelService service;

    @BeforeEach
    void setUp() {
        service = new PaymentFunnelService(client);
    }

    @Test
    @DisplayName("CH 未配置降级 available=false")
    void degrades() {
        when(client.isAvailable()).thenReturn(false);
        Map<String, Object> resp = service.funnel("g", null, 90);
        assertEquals(false, resp.get("available"));
        verify(client, never()).query(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("漏斗：注册/付费行与月留存行按 cohort 合并输出步骤与总体")
    void funnelMergesQueries() {
        when(client.isAvailable()).thenReturn(true);
        String matureCohort = LocalDate.now().minusDays(60).toString();
        when(client.query(contains("pay_events"), any(Object[].class)))
            .thenReturn(List.of(Map.of(
                "cohort", Date.valueOf(matureCohort), "registered", 100L,
                "first_pay", 20L, "second_pay", 8L)));
        when(client.query(contains("dateDiff"), any(Object[].class)))
            .thenReturn(List.of(Map.of("cohort", Date.valueOf(matureCohort), "retained_30", 35L)));

        Map<String, Object> resp = service.funnel("g", null, 90);

        @SuppressWarnings("unchecked")
        Map<String, Object> funnel = (Map<String, Object>) resp.get("funnel");
        assertEquals(100L, funnel.get("registered"));
        assertEquals(20L, funnel.get("firstPay"));
        assertEquals(35L, funnel.get("retained30"));
        assertEquals(100L, funnel.get("retained30Base"));
        assertEquals(0.2, funnel.get("firstPayRate"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) resp.get("steps");
        assertEquals(4, steps.size());
    }

    @Test
    @DisplayName("环境过滤：查询携带 environment 参数")
    void envFilterAppendsParameter() {
        when(client.isAvailable()).thenReturn(true);
        when(client.query(contains("pay_events"), any(Object[].class))).thenReturn(List.of());
        when(client.query(contains("dateDiff"), any(Object[].class))).thenReturn(List.of());

        Map<String, Object> resp = service.funnel("g", "prod", 90);

        assertEquals(true, resp.get("available"));
        verify(client).query(contains("AND environment = ?"), eq("g"), eq("prod"), eq("g"), eq("prod"), any());
    }

    @Test
    @DisplayName("窗口天数夹取（缺省 90、上限 365）")
    void clampsDays() {
        assertEquals(90, PaymentFunnelService.clampDays(null));
        assertEquals(365, PaymentFunnelService.clampDays(10000));
    }
}
