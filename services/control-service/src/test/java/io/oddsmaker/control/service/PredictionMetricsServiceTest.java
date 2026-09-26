package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.MlArtifactEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 预测服务测试：CH 降级、流失打分归档、风险模型分归档、pLTV 链路、榜单查询；
 * 以及 ml 产物在场时「模型优先、缺失/不匹配回落启发式」的双路径语义。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("预测服务测试")
class PredictionMetricsServiceTest {

    @Mock
    private ClickHouseClient client;

    @Mock
    private MlArtifactRegistry registry;

    private PredictionMetricsService service;

    @BeforeEach
    void setUp() {
        service = new PredictionMetricsService(client, registry);
        // 未注册产物 → 启发式路径（默认行为与 P6 语义完全一致）
        lenient().when(registry.resolveActive(anyString(), anyString())).thenReturn(Optional.empty());
    }

    private static final String CHURN_FEATURES_JSON =
            "[\"days_inactive_30d\",\"session_count_30d\",\"event_count_30d\",\"revenue_total_30d\"]";
    private static final String RISK_FEATURES_JSON =
            "[\"critical_30d\",\"high_30d\",\"medium_30d\",\"low_30d\",\"distinct_rules_30d\"]";

    private static MlArtifactEntity artifact(String type, String featureNamesJson,
                                             String coefficientsJson, Double intercept, Double multiplier) {
        MlArtifactEntity a = new MlArtifactEntity();
        a.id = "mla_" + type + "_test";
        a.gameId = "g";
        a.modelType = type;
        a.modelVersion = "v0.1.0";
        a.featureNames = featureNamesJson;
        a.coefficients = coefficientsJson;
        a.intercept = intercept;
        a.multiplier = multiplier;
        return a;
    }

    @Test
    @DisplayName("CH 未配置时统一降级 available=false 且不查询")
    void degradesWhenUnavailable() {
        when(client.isAvailable()).thenReturn(false);

        assertFalse((Boolean) service.refreshChurn("g", null).get("available"));
        assertFalse((Boolean) service.topChurn("g", null, null).get("available"));
        assertFalse((Boolean) service.refreshRiskScore("g", null).get("available"));
        assertFalse((Boolean) service.topRiskScore("g", null, null).get("available"));
        assertFalse((Boolean) service.refreshPltv("g", null).get("available"));
        assertFalse((Boolean) service.topPltv("g", null, null).get("available"));
        assertFalse((Boolean) service.refreshPropensity("g", null).get("available"));
        assertFalse((Boolean) service.topPropensity("g", null, null).get("available"));
        verify(client, never()).query(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("流失重算（启发式回落）：打分分级统计并逐条归档 predictions")
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
        assertEquals("heuristic", resp.get("path"));
        // 空用户被跳过：仅 2 条写入
        verify(client, times(2))
            .update(contains("INSERT INTO predictions"), any(Object[].class));
    }

    @Test
    @DisplayName("流失重算（模型路径）：注册产物在场且特征口径匹配 → LR 打分，model_id/model_version 落库可区分")
    void churnUsesModelWhenArtifactMatches() {
        when(client.isAvailable()).thenReturn(true);
        when(registry.resolveActive("g", "churn")).thenReturn(Optional.of(artifact("churn",
                CHURN_FEATURES_JSON, "[0.8,-0.3,-0.002,-0.01]", -1.5, null)));
        // golden case 与 Python 侧同输入：z = -1.89 → sigmoid = 0.131244469439
        when(client.query(contains("v_user_features_30d"), any(Object[].class)))
            .thenReturn(List.of(Map.of("user_id", "u1", "days_inactive", 2L,
                "session_count", 5L, "event_count", 120L, "revenue_total", 25.0)));

        Map<String, Object> resp = service.refreshChurn("g", "prod");

        assertEquals("model", resp.get("path"));
        assertEquals("mla_churn_test", resp.get("model"));
        assertEquals("v0.1.0", resp.get("modelVersion"));

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(client).update(contains("INSERT INTO predictions"), args.capture());
        Object[] row = args.getValue();
        assertEquals("mla_churn_test", row[3]);
        assertEquals("v0.1.0", row[4]);
        assertEquals("churn", row[5]);
        assertEquals(0.131244469439, ((Number) row[6]).doubleValue(), 1e-6);  // 落库为 float32
    }

    @Test
    @DisplayName("流失重算（特征口径不匹配 → 回落启发式）")
    void churnFallsBackWhenFeatureMismatch() {
        when(client.isAvailable()).thenReturn(true);
        when(registry.resolveActive("g", "churn")).thenReturn(Optional.of(artifact("churn",
                "[\"a\",\"b\",\"c\",\"d\"]", "[1.0,1.0,1.0,1.0]", 0.0, null)));
        when(client.query(contains("v_user_features_30d"), any(Object[].class)))
            .thenReturn(List.of(Map.of("user_id", "u1", "days_inactive", 20L,
                "session_count", 1L, "event_count", 10L, "revenue_total", 0.0)));

        Map<String, Object> resp = service.refreshChurn("g", "prod");

        assertEquals("heuristic", resp.get("path"));
        assertEquals("heuristic_churn_v1", resp.get("model"));
        assertFalse(resp.containsKey("modelVersion"));
    }

    @Test
    @DisplayName("风险模型重算（启发式回落）：严重度加权归档")
    void refreshRiskScoreArchives() {
        when(client.isAvailable()).thenReturn(true);
        when(client.query(contains("risk_events"), any(Object[].class)))
            .thenReturn(List.of(Map.of(
                "subject_id", "player_9", "c_critical", 2L, "c_high", 1L,
                "c_medium", 0L, "c_low", 0L, "distinct_rules", 2L)));

        Map<String, Object> resp = service.refreshRiskScore("g", null);

        assertEquals(1, resp.get("scored"));
        assertEquals(0L, resp.get("high"));
        assertEquals(1L, resp.get("medium"));
        assertEquals("heuristic", resp.get("path"));
        verify(client).update(contains("INSERT INTO predictions"), any(Object[].class));
    }

    @Test
    @DisplayName("风险模型重算（模型路径）：产物系数打分，golden case 与 Python 同结果")
    void riskUsesModelWhenArtifactMatches() {
        when(client.isAvailable()).thenReturn(true);
        when(registry.resolveActive("g", "risk")).thenReturn(Optional.of(artifact("risk",
                RISK_FEATURES_JSON, "[0.5,0.4,0.3,0.2,0.1]", -2.0, null)));
        // golden case：z = 0.2 → sigmoid = 0.549833997312
        when(client.query(contains("risk_events"), any(Object[].class)))
            .thenReturn(List.of(Map.of("subject_id", "s1", "c_critical", 2L, "c_high", 1L,
                "c_medium", 0L, "c_low", 3L, "distinct_rules", 2L)));

        Map<String, Object> resp = service.refreshRiskScore("g", null);

        assertEquals("model", resp.get("path"));
        assertEquals("v0.1.0", resp.get("modelVersion"));
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(client).update(contains("INSERT INTO predictions"), args.capture());
        assertEquals("mla_risk_test", args.getValue()[3]);
        assertEquals("risk_model", args.getValue()[5]);
        assertEquals(0.549833997312, ((Number) args.getValue()[6]).doubleValue(), 1e-6);  // 落库为 float32
    }

    @Test
    @DisplayName("pLTV 重算（模型路径）：未成熟用户 D7 收入 × 产物乘数")
    void pltvUsesModelMultiplier() {
        when(client.isAvailable()).thenReturn(true);
        when(registry.resolveActive("g", "pltv")).thenReturn(Optional.of(artifact("pltv",
                null, null, null, 3.2)));
        // 空用户行被跳过，仅 u1 写入
        when(client.query(contains("today() - 7"), any(Object[].class)))
            .thenReturn(List.of(Map.of("user_id", "u1", "d7_revenue", 100.0),
                Map.of("user_id", "", "d7_revenue", 5.0)));

        Map<String, Object> resp = service.refreshPltv("g", null);

        assertEquals("model", resp.get("path"));
        assertEquals("mla_pltv_test", resp.get("model"));
        assertEquals("v0.1.0", resp.get("modelVersion"));
        assertEquals(2, resp.get("scored"));
        assertEquals(3.2, resp.get("multiplier"));

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(client).update(contains("INSERT INTO predictions"), args.capture());
        assertEquals("u1", args.getValue()[2]);
        assertEquals("pltv", args.getValue()[5]);
        assertEquals(320.0, ((Number) args.getValue()[6]).doubleValue(), 1e-4);
    }

    @Test
    @DisplayName("pLTV 重算（启发式回落）：成熟 cohort 比值均值乘数")
    void pltvFallsBackToCohortRatioHeuristic() {
        when(client.isAvailable()).thenReturn(true);
        when(client.query(contains("v_ltv_by_cohort_day"), any(Object[].class)))
            .thenReturn(List.of(
                // 行为每日增量：累计 D7=100，累计 D30=100+150=250 → 比值 2.5
                Map.of("cohort", "2020-01-01", "age_day", 6L, "revenue", 100.0),
                Map.of("cohort", "2020-01-01", "age_day", 29L, "revenue", 150.0)));
        when(client.query(contains("v_user_first_seen"), any(Object[].class)))
            .thenReturn(List.of(Map.of("cohort", "2020-01-01", "cohort_size", 10L)));
        when(client.query(contains("today() - 7"), any(Object[].class)))
            .thenReturn(List.of(Map.of("user_id", "u9", "d7_revenue", 40.0)));

        Map<String, Object> resp = service.refreshPltv("g", "prod");

        assertEquals("heuristic", resp.get("path"));
        assertEquals(PredictionMetricsService.PLTV_HEURISTIC_MODEL, resp.get("model"));
        assertEquals(2.5, resp.get("multiplier"));
        assertEquals(1, resp.get("scored"));
        verify(client).update(contains("INSERT INTO predictions"), any(Object[].class));
    }

    @Test
    @DisplayName("pLTV 重算（启发式无成熟 cohort）：乘数不可估，不写回并如实说明")
    void pltvHeuristicWithoutMatureCohortsWritesNothing() {
        when(client.isAvailable()).thenReturn(true);
        when(client.query(contains("v_ltv_by_cohort_day"), any(Object[].class)))
            .thenReturn(List.of());
        when(client.query(contains("v_user_first_seen"), any(Object[].class)))
            .thenReturn(List.of());

        Map<String, Object> resp = service.refreshPltv("g", null);

        assertEquals("heuristic", resp.get("path"));
        assertEquals(0, resp.get("scored"));
        assertNotNull(resp.get("reason"));
        verify(client, never()).update(contains("INSERT INTO predictions"), any(Object[].class));
    }

    @Test
    @DisplayName("付费倾向重算（模型路径）：golden case 与 Python 同结果，type=propensity 归档")
    void propensityUsesModelWhenArtifactMatches() {
        when(client.isAvailable()).thenReturn(true);
        when(registry.resolveActive("g", "propensity")).thenReturn(Optional.of(artifact("propensity",
                CHURN_FEATURES_JSON, "[0.5,-0.2,0.001,0.01]", -1.0, null)));
        // golden case 与 Python 侧同输入：z = -0.63 → sigmoid = 0.34751053780725555
        when(client.query(contains("v_user_features_30d"), any(Object[].class)))
            .thenReturn(List.of(Map.of("user_id", "u1", "days_inactive", 2L,
                "session_count", 5L, "event_count", 120L, "revenue_total", 25.0)));

        Map<String, Object> resp = service.refreshPropensity("g", "prod");

        assertEquals("model", resp.get("path"));
        assertEquals("mla_propensity_test", resp.get("model"));
        assertEquals("v0.1.0", resp.get("modelVersion"));

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(client).update(contains("INSERT INTO predictions"), args.capture());
        assertEquals("mla_propensity_test", args.getValue()[3]);
        assertEquals("propensity", args.getValue()[5]);
        assertEquals(0.34751053780725555, ((Number) args.getValue()[6]).doubleValue(), 1e-6);  // 落库为 float32
    }

    @Test
    @DisplayName("付费倾向重算（启发式回落）：PropensityScorer 分级统计并归档")
    void refreshPropensityFallsBackToHeuristic() {
        when(client.isAvailable()).thenReturn(true);
        when(client.query(contains("v_user_features_30d"), any(Object[].class)))
            .thenReturn(List.of(
                Map.of("user_id", "u1", "days_inactive", 10L, "session_count", 20L,
                    "event_count", 200L, "revenue_total", 50.0),
                Map.of("user_id", "u2", "days_inactive", 5L, "session_count", 3L,
                    "event_count", 40L, "revenue_total", 0.0),
                Map.of("user_id", "", "days_inactive", 30L, "session_count", 0L,
                    "event_count", 0L, "revenue_total", 0.0)));

        Map<String, Object> resp = service.refreshPropensity("g", null);

        assertEquals(3, resp.get("scored"));
        assertEquals(1L, resp.get("high"));
        assertEquals("heuristic", resp.get("path"));
        assertEquals(PredictionMetricsService.PROPENSITY_HEURISTIC_MODEL, resp.get("model"));
        assertFalse(resp.containsKey("modelVersion"));
        // 空用户被跳过：仅 2 条写入
        verify(client, times(2)).update(contains("INSERT INTO predictions"), any(Object[].class));
    }

    @Test
    @DisplayName("付费倾向榜单：委托 propensity 类型查询 + 环境过滤")
    void topPropensityDelegatesTypeAndEnv() {
        when(client.isAvailable()).thenReturn(true);
        when(client.query(contains("prediction_type = 'propensity'"), any(Object[].class)))
            .thenReturn(List.of(Map.of("user_id", "p1", "score", 0.9f,
                "predicted_at", Timestamp.valueOf("2026-09-09 08:00:00"))));

        Map<String, Object> top = service.topPropensity("g", "prod", 10);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> users = (List<Map<String, Object>>) top.get("users");
        assertEquals("p1", users.get(0).get("userId"));
        verify(client).query(contains("AND environment = ?"), eq("g"), eq("prod"));
    }

    @Test
    @DisplayName("pLTV 榜单与流失榜单：predictions FINAL 联特征输出 camelCase")
    void topListingsMapRows() {
        when(client.isAvailable()).thenReturn(true);
        when(client.query(contains("prediction_type = 'pltv'"), any(Object[].class)))
            .thenReturn(List.of(Map.of("user_id", "p1", "score", 320.0f,
                "predicted_at", Timestamp.valueOf("2026-09-09 08:00:00"))));
        when(client.query(contains("predictions FINAL AS p"), any(Object[].class)))
            .thenReturn(List.of(Map.of(
                "user_id", "u1", "score", 0.87f, "predicted_at", Timestamp.valueOf("2026-09-09 08:00:00"),
                "days_inactive", 20L, "session_count", 1L, "revenue_total", 0.0)));

        Map<String, Object> pltv = service.topPltv("g", null, 10);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pltvUsers = (List<Map<String, Object>>) pltv.get("users");
        assertEquals("p1", pltvUsers.get(0).get("userId"));
        assertEquals(320.0, (double) pltvUsers.get(0).get("score"), 1e-6);

        Map<String, Object> churn = service.topChurn("g", null, 10);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> users = (List<Map<String, Object>>) churn.get("users");
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
        when(client.query(contains("prediction_type = 'risk_model'"), any(Object[].class)))
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
        when(client.query(contains("predictions FINAL AS p"), any(Object[].class))).thenReturn(List.of());

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

    // ===== 分支对侧补充（BRANCH 收口）=====

    @Test
    @DisplayName("空白环境与 null 等价：入口与 writePrediction 归一化均走无环境支（isBlank 侧）")
    void blankEnvironmentTreatedAsUnset() {
        when(client.isAvailable()).thenReturn(true);
        lenient().when(client.query(anyString(), any(Object[].class))).thenReturn(List.of());

        assertTrue((Boolean) service.refreshChurn("g", " ").get("available"));
        assertTrue((Boolean) service.topChurn("g", " ", null).get("available"));
        assertTrue((Boolean) service.refreshRiskScore("g", "  ").get("available"));
        assertTrue((Boolean) service.topRiskScore("g", " ", null).get("available"));
        assertTrue((Boolean) service.refreshPltv("g", " ").get("available"));
        assertTrue((Boolean) service.topPltv("g", " ", null).get("available"));

        // envFilter(" ") 视为未指定：任何查询 SQL 都不含环境谓词
        verify(client, never()).query(contains("AND environment = ?"), any(Object[].class));
    }

    @Test
    @DisplayName("writePrediction：空白环境归一化为空串落库（三元空串侧 + 有行写入）")
    void writePredictionBlankEnvironmentNormalizesToEmpty() {
        when(client.isAvailable()).thenReturn(true);
        when(client.query(contains("v_user_features_30d"), any(Object[].class))).thenReturn(List.of(
            Map.of("user_id", "u1", "days_inactive", 20L, "session_count", 1L, "revenue_total", 0.0)));

        Map<String, Object> resp = service.refreshChurn("g", "   ");
        assertEquals(1, resp.get("scored"));

        // 空白环境 → INSERT 第二参为 ""（而非 null/原样空白）
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(client).update(contains("INSERT INTO predictions"), args.capture());
        assertEquals("", args.getValue()[1]);
    }

    @Test
    @DisplayName("产物损坏（JSON 非法）→ 回落启发式，不抛异常")
    void corruptArtifactFallsBack() {
        when(client.isAvailable()).thenReturn(true);
        MlArtifactEntity broken = artifact("churn", CHURN_FEATURES_JSON,
                "[1.0,1.0,1.0,1.0]", 0.0, null);
        broken.featureNames = "not-json";
        when(registry.resolveActive("g", "churn")).thenReturn(Optional.of(broken));
        when(client.query(contains("v_user_features_30d"), any(Object[].class))).thenReturn(List.of());

        Map<String, Object> resp = service.refreshChurn("g", "prod");
        assertEquals("heuristic", resp.get("path"));
        assertEquals(0, resp.get("scored"));
    }
}
