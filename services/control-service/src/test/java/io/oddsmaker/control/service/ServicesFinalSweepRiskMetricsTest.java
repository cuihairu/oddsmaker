package io.oddsmaker.control.service;

import io.oddsmaker.control.service.ClickHouseClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Service 层覆盖率收尾测试（LAY-006 拆分）：风控指标环境过滤查询委托。与 RiskMetricsServiceTest / RiskServicesDeepTest 等互补。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("风控指标 Service 深度测试（环境过滤查询）")
class ServicesFinalSweepRiskMetricsTest {

    @Mock
    private ClickHouseClient clickHouseClient;

    @InjectMocks
    private RiskMetricsService riskMetricsService;

    @Test
    @DisplayName("风控指标：ruleHits/severity/actions 带 environment 走四参查询")
    void riskMetricsEnvFilteredQueries() {
        lenient().when(clickHouseClient.isAvailable()).thenReturn(true);
        lenient().when(clickHouseClient.query(anyString(), any(), any(), any(Timestamp.class)))
            .thenReturn(List.of());

        Map<String, Object> hits = riskMetricsService.ruleHits("g", "prod", 24, null);
        Map<String, Object> severity = riskMetricsService.severity("g", "prod", 24, null);
        Map<String, Object> actions = riskMetricsService.actions("g", "prod", 24, null);

        assertEquals(Boolean.TRUE, hits.get("available"));
        assertTrue(((List<?>) hits.get("rules")).isEmpty());
        assertEquals(0L, severity.get("total"));
        assertTrue(((List<?>) actions.get("recent")).isEmpty());

        verify(clickHouseClient).query(contains("GROUP BY rule_id"),
            eq("g"), eq("prod"), any(Timestamp.class));
        verify(clickHouseClient).query(contains("GROUP BY severity"),
            eq("g"), eq("prod"), any(Timestamp.class));
        verify(clickHouseClient).query(contains("GROUP BY risk_type"),
            eq("g"), eq("prod"), any(Timestamp.class));
        verify(clickHouseClient).query(contains("GROUP BY action"),
            eq("g"), eq("prod"), any(Timestamp.class));
        verify(clickHouseClient).query(contains("GROUP BY state"),
            eq("g"), eq("prod"), any(Timestamp.class));
        verify(clickHouseClient).query(contains("ORDER BY ts DESC"),
            eq("g"), eq("prod"), any(Timestamp.class));
    }

}
