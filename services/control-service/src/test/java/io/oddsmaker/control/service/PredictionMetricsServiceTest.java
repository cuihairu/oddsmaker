package io.oddsmaker.control.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 预测服务测试：CH 降级、流失打分归档、风险模型分归档、榜单查询。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("预测服务测试")
class PredictionMetricsServiceTest {

    @Mock
    private ClickHouseClient client;

    private PredictionMetricsService service;

    @BeforeEach
    void setUp() {
        service = new PredictionMetricsService(client);
    }

    @Test
    @DisplayName("CH 未配置时统一降级 available=false 且不查询")
    void degradesWhenUnavailable() {
        when(client.isAvailable()).thenReturn(false);

        assertFalse((Boolean) service.refreshChurn("g", null).get("available"));
        assertFalse((Boolean) service.topChurn("g", null, null).get("available"));
        assertFalse((Boolean) service.refreshRiskScore("g", null).get("available"));
        assertFalse((Boolean) service.topRiskScore("g", null, null).get("available"));
        verify(client, never()).query(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("流失重算：打分分级统计并逐条归档 predictions")
    void refreshChurnScoresAndArchives() {
        when(client.isAvailable()).thenReturn(true);
        when(client.query(contains("v_user_features_30d"), any(Object[].class)))
            .thenReturn(List.of(
                Map.of("user_id", "u1", "days_inactive", 20L, "session_count", 1L, "revenue_total", 0.0),
                Map.of("user_id", "u2", "days_inactive", 8L, "session_count", 5L, "revenue_total", 10.0),
                Map.of("user_id", "", "days_inactive", 30L, "session_count", 0L, "revenue_total", 0.0)));

        Map<String, Object> resp = service.refreshChurn("g", "prod");

        assertEquals(3, resp.get("scored"));
        assertEquals(1L, resp.get("high"));
        assertEquals("heuristic_churn_v1", resp.get("model"));
        // 空用户被跳过：仅 2 条写入
        verify(client, org.mockito.Mockito.times(2))
            .update(contains("INSERT INTO predictions"), any(Object[].class));
    }

    @Test
    @DisplayName("风险模型重算：严重度加权归档")
    void refreshRiskScoreArchives() {
        when(client.isAvailable()).thenReturn(true);
        when(client.query(contains("risk_events"), any(Object[].class)))
            .thenReturn(List.of(Map.of(
                "subject_id", "player_9", "c_critical", 2L, "c_high", 1L, "c_medium", 0L, "c_low", 0L)));

        Map<String, Object> resp = service.refreshRiskScore("g", null);

        assertEquals(1, resp.get("scored"));
        assertEquals(0L, resp.get("high"));
        assertEquals(1L, resp.get("medium"));
        verify(client).update(contains("INSERT INTO predictions"), any(Object[].class));
    }

    @Test
    @DisplayName("流失榜单：predictions FINAL 联特征输出 camelCase")
    void topChurnMapsRows() {
        when(client.isAvailable()).thenReturn(true);
        when(client.query(contains("predictions FINAL"), any(Object[].class)))
            .thenReturn(List.of(Map.of(
                "user_id", "u1", "score", 0.87f, "predicted_at", Timestamp.valueOf("2026-09-09 08:00:00"),
                "days_inactive", 20L, "session_count", 1L, "revenue_total", 0.0)));

        Map<String, Object> resp = service.topChurn("g", null, 10);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> users = (List<Map<String, Object>>) resp.get("users");
        assertEquals(1, users.size());
        assertEquals("u1", users.get(0).get("userId"));
        assertEquals(0.87, (double) users.get(0).get("score"), 1e-6);
        assertEquals(20L, users.get(0).get("daysInactive"));
        assertTrue(String.valueOf(users.get(0).get("predictedAt")).startsWith("2026-09-09T"));
    }

    @Test
    @DisplayName("写入失败不阻断批量打分")
    void writeFailureTolerated() {
        when(client.isAvailable()).thenReturn(true);
        when(client.query(contains("v_user_features_30d"), any(Object[].class)))
            .thenReturn(List.of(
                Map.of("user_id", "u1", "days_inactive", 20L, "session_count", 0L, "revenue_total", 0.0),
                Map.of("user_id", "u2", "days_inactive", 20L, "session_count", 0L, "revenue_total", 0.0)));
        lenient().when(client.update(contains("INSERT INTO predictions"), any(Object[].class)))
            .thenThrow(new RuntimeException("ch down"))
            .thenReturn(1);

        Map<String, Object> resp = service.refreshChurn("g", null);

        assertEquals(2, resp.get("scored"));  // 两条都处理完，未中断
        assertTrue((Boolean) resp.get("available"));
    }

    @Test
    @DisplayName("风险模型榜单与环境过滤查询")
    void topRiskScoreAndEnvBranches() {
        when(client.isAvailable()).thenReturn(true);
        when(client.query(contains("risk_events"), any(Object[].class))).thenReturn(List.of());
        when(client.query(contains("predictions FINAL"), any(Object[].class)))
            .thenReturn(List.of(Map.of("user_id", "p1", "score", 0.9f,
                "predicted_at", Timestamp.valueOf("2026-09-09 08:00:00"))));

        service.refreshRiskScore("g", "prod");
        verify(client).query(contains("AND environment = ?"), eq("g"), eq("prod"), any(Timestamp.class));

        Map<String, Object> top = service.topRiskScore("g", null, 10);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> users = (List<Map<String, Object>>) top.get("users");
        assertEquals("p1", users.get(0).get("userId"));
        assertFalse(users.get(0).containsKey("daysInactive"));  // 风险榜单无特征列
    }

    @Test
    @DisplayName("流失榜单环境过滤")
    void topChurnEnvBranch() {
        when(client.isAvailable()).thenReturn(true);
        when(client.query(contains("predictions FINAL"), any(Object[].class))).thenReturn(List.of());

        service.topChurn("g", "prod", 10);
        verify(client).query(contains("AND environment = ?"), eq("g"), eq("prod"));
    }

    @Test
    @DisplayName("流失重算无环境分支：两参数查询")
    void refreshChurnWithoutEnv() {
        when(client.isAvailable()).thenReturn(true);
        when(client.query(contains("v_user_features_30d"), any(Object[].class))).thenReturn(List.of());

        Map<String, Object> resp = service.refreshChurn("g", null);
        assertEquals(0, resp.get("scored"));
        assertEquals(0.0, resp.get("avgScore"));
    }

    @Test
    @DisplayName("limit 夹取：缺省 100，上限 500")
    void limitClamped() {
        assertEquals(100, PredictionMetricsService.clampLimit(null));
        assertEquals(10, PredictionMetricsService.clampLimit(10));
        assertEquals(500, PredictionMetricsService.clampLimit(9999));
    }
}
