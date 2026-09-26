package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.MlArtifactEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能化预测服务（ClickHouse 数据源）：
 * - 流失预测：v_user_features_30d 特征 → 打分 → 归档 predictions（type=churn）；
 * - 风险评分模型：risk_events 30 天严重度加权聚合 → 打分 → 归档 predictions（type=risk_model）；
 * - pLTV：未成熟用户（注册 7-30 天）D7 收入 × D7→D30 乘数 → 归档 predictions（type=pltv）；
 * - 付费倾向：v_user_features_30d 特征 → 打分 → 归档 predictions（type=propensity）。
 *
 * <p>打分算子解析（P4.4 收尾，与 ml/ 训练产物打通）：注册且校验通过的产物
 * （MlArtifactRegistry）优先——线性模型 sigmoid(intercept + Σ coef×feature)，
 * pltv 用产物乘数；产物缺失、特征口径不匹配或产物损坏时回落启发式
 * （ChurnScorer / RiskScorer / LtvForecastAssembler / PropensityScorer）。两条路径可区分：
 * 返回体 path 字段（model / heuristic）+ predictions 落库的 model_id / model_version
 * （产物路径为注册 id + 产物版本，启发式为 heuristic_churn_v1 / rule_aggregate_v1 /
 * cohort_ratio_v1 / heuristic_propensity_v1 + v1）。
 * ClickHouse 未配置时统一降级 available=false，不伪造结果。
 */
@Service
public class PredictionMetricsService {

    private static final Logger logger = LoggerFactory.getLogger(PredictionMetricsService.class);

    private static final int REFRESH_LIMIT = 500;
    private static final int TOP_LIMIT = 100;
    private static final int PREDICTION_TTL_DAYS = 30;
    static final String CHURN_HEURISTIC_MODEL = "heuristic_churn_v1";
    static final String RISK_HEURISTIC_MODEL = "rule_aggregate_v1";
    static final String PLTV_HEURISTIC_MODEL = "cohort_ratio_v1";
    static final String PROPENSITY_HEURISTIC_MODEL = "heuristic_propensity_v1";
    static final String HEURISTIC_VERSION = "v1";

    /** churn 特征口径（顺序对齐 ml 产物 feature_names：CHURN_FEATURES） */
    static final List<String> CHURN_FEATURES = List.of(
            "days_inactive_30d", "session_count_30d", "event_count_30d", "revenue_total_30d");
    /** propensity 特征口径与 churn 相同（同视图同列序，标签不同） */
    static final List<String> PROPENSITY_FEATURES = CHURN_FEATURES;
    /** risk 特征口径（顺序对齐 ml 产物 RISK_FEATURES） */
    static final List<String> RISK_FEATURES = List.of(
            "critical_30d", "high_30d", "medium_30d", "low_30d", "distinct_rules_30d");

    /** churn/propensity 共用的 30 天特征查询（口径 = v_user_features_30d） */
    private static final String USER_FEATURES_SQL = "SELECT user_id AS user_id, "
            + "days_inactive_30d AS days_inactive, session_count_30d AS session_count, "
            + "event_count_30d AS event_count, revenue_total_30d AS revenue_total "
            + "FROM v_user_features_30d WHERE game_id = ?%s"
            + " AND days_inactive_30d >= 3 ORDER BY days_inactive_30d DESC LIMIT " + REFRESH_LIMIT;

    private final ClickHouseClient client;
    private final MlArtifactRegistry registry;

    public PredictionMetricsService(ClickHouseClient client, MlArtifactRegistry registry) {
        this.client = client;
        this.registry = registry;
    }

    // ========== 流失预测 ==========

    /** 重算流失分并归档：30 天特征 → 打分（产物优先，启发式回落）→ 写 predictions */
    public Map<String, Object> refreshChurn(String gameId, String environment) {
        Map<String, Object> resp = base(gameId, environment, "churn");
        if (!client.isAvailable()) {
            resp.put("available", false);
            return resp;
        }
        ModelBundle model = resolveModel(gameId, "churn", CHURN_FEATURES);
        String modelId = model != null ? model.artifact.id : CHURN_HEURISTIC_MODEL;
        String modelVersion = model != null ? model.artifact.modelVersion : HEURISTIC_VERSION;
        resp.put("path", model != null ? "model" : "heuristic");
        resp.put("model", modelId);
        if (model != null) {
            resp.put("modelVersion", modelVersion);
        }

        String sql = String.format(USER_FEATURES_SQL, envFilter(environment));
        List<Map<String, Object>> rows = environment == null || environment.isBlank()
                ? client.query(sql, gameId)
                : client.query(sql, gameId, environment);

        long high = 0, medium = 0, low = 0;
        double scoreSum = 0;
        for (Map<String, Object> row : rows) {
            String userId = RiskMetricsAssembler.asString(row.get("user_id"));
            if (userId.isEmpty()) {
                continue;
            }
            double score;
            if (model != null) {
                double[] features = {
                        RiskMetricsAssembler.asDouble(row.get("days_inactive")),
                        RiskMetricsAssembler.asDouble(row.get("session_count")),
                        RiskMetricsAssembler.asDouble(row.get("event_count")),
                        RiskMetricsAssembler.asDouble(row.get("revenue_total"))};
                score = MlArtifactScorer.score(model.coefficients, model.artifact.intercept, features);
            } else {
                ChurnScorer.Scored scored = ChurnScorer.score(
                        RiskMetricsAssembler.asLong(row.get("days_inactive")),
                        RiskMetricsAssembler.asLong(row.get("session_count")),
                        RiskMetricsAssembler.asDouble(row.get("revenue_total")));
                score = scored.score();
            }
            writePrediction(gameId, environment, userId, modelId, modelVersion, "churn", score);
            scoreSum += score;
            String level = score >= ChurnScorer.HIGH ? "high" : score >= ChurnScorer.MEDIUM ? "medium" : "low";
            switch (level) {
                case "high" -> high++;
                case "medium" -> medium++;
                default -> low++;
            }
        }
        resp.put("scored", rows.size());
        resp.put("high", high);
        resp.put("medium", medium);
        resp.put("low", low);
        resp.put("avgScore", rows.isEmpty() ? 0.0 : RetentionMetricsAssembler.round4(scoreSum / rows.size()));
        logger.info("Churn refresh: game={} path={} scored={} high={}", gameId, resp.get("path"), rows.size(), high);
        return resp;
    }

    /** 高流失风险用户（归档的最近预测 + 当前特征） */
    public Map<String, Object> topChurn(String gameId, String environment, Integer limit) {
        Map<String, Object> resp = base(gameId, environment, "churn");
        if (!client.isAvailable()) {
            resp.put("available", false);
            return resp;
        }
        int n = clampLimit(limit);
        String sql = "SELECT p.user_id AS user_id, p.score AS score, p.predicted_at AS predicted_at, "
                + "f.days_inactive_30d AS days_inactive, f.session_count_30d AS session_count, "
                + "f.revenue_total_30d AS revenue_total "
                + "FROM predictions FINAL AS p "
                + "ANY LEFT JOIN v_user_features_30d AS f "
                + "ON p.game_id = f.game_id AND p.environment = f.environment AND p.user_id = f.user_id "
                + "WHERE p.prediction_type = 'churn' AND p.game_id = ?" + envFilter(environment)
                + " AND p.expires_at > now() ORDER BY p.score DESC LIMIT " + n;
        List<Map<String, Object>> rows = environment == null || environment.isBlank()
                ? client.query(sql, gameId)
                : client.query(sql, gameId, environment);
        resp.put("users", toUsers(rows));
        return resp;
    }

    // ========== 付费倾向（propensity） ==========

    /**
     * 付费倾向批量预测：30 天特征 → 打分（产物优先，PropensityScorer 启发式回落）
     * → 归档 predictions（type=propensity）。特征口径与 churn 相同（v_user_features_30d），
     * 标签语义不同：付费倾向 = 未来 14 天内有付费事件的概率。
     */
    public Map<String, Object> refreshPropensity(String gameId, String environment) {
        Map<String, Object> resp = base(gameId, environment, "propensity");
        if (!client.isAvailable()) {
            resp.put("available", false);
            return resp;
        }
        ModelBundle model = resolveModel(gameId, "propensity", PROPENSITY_FEATURES);
        String modelId = model != null ? model.artifact.id : PROPENSITY_HEURISTIC_MODEL;
        String modelVersion = model != null ? model.artifact.modelVersion : HEURISTIC_VERSION;
        resp.put("path", model != null ? "model" : "heuristic");
        resp.put("model", modelId);
        if (model != null) {
            resp.put("modelVersion", modelVersion);
        }

        String sql = String.format(USER_FEATURES_SQL, envFilter(environment));
        List<Map<String, Object>> rows = environment == null || environment.isBlank()
                ? client.query(sql, gameId)
                : client.query(sql, gameId, environment);

        long high = 0, medium = 0, low = 0;
        double scoreSum = 0;
        for (Map<String, Object> row : rows) {
            String userId = RiskMetricsAssembler.asString(row.get("user_id"));
            if (userId.isEmpty()) {
                continue;
            }
            double score;
            if (model != null) {
                double[] features = {
                        RiskMetricsAssembler.asDouble(row.get("days_inactive")),
                        RiskMetricsAssembler.asDouble(row.get("session_count")),
                        RiskMetricsAssembler.asDouble(row.get("event_count")),
                        RiskMetricsAssembler.asDouble(row.get("revenue_total"))};
                score = MlArtifactScorer.score(model.coefficients, model.artifact.intercept, features);
            } else {
                PropensityScorer.Scored scored = PropensityScorer.score(
                        RiskMetricsAssembler.asLong(row.get("days_inactive")),
                        RiskMetricsAssembler.asLong(row.get("session_count")),
                        RiskMetricsAssembler.asDouble(row.get("revenue_total")));
                score = scored.score();
            }
            writePrediction(gameId, environment, userId, modelId, modelVersion, "propensity", score);
            scoreSum += score;
            String level = score >= PropensityScorer.HIGH ? "high"
                    : score >= PropensityScorer.MEDIUM ? "medium" : "low";
            switch (level) {
                case "high" -> high++;
                case "medium" -> medium++;
                default -> low++;
            }
        }
        resp.put("scored", rows.size());
        resp.put("high", high);
        resp.put("medium", medium);
        resp.put("low", low);
        resp.put("avgScore", rows.isEmpty() ? 0.0 : RetentionMetricsAssembler.round4(scoreSum / rows.size()));
        logger.info("Propensity refresh: game={} path={} scored={} high={}",
                gameId, resp.get("path"), rows.size(), high);
        return resp;
    }

    /** 高付费倾向用户 */
    public Map<String, Object> topPropensity(String gameId, String environment, Integer limit) {
        return topByType(gameId, environment, limit, "propensity");
    }

    // ========== 风险评分模型 ==========

    /** 重算主体模型风险分：risk_events 30 天聚合 → 打分（产物优先，启发式回落）→ 归档 */
    public Map<String, Object> refreshRiskScore(String gameId, String environment) {
        Map<String, Object> resp = base(gameId, environment, "risk_model");
        if (!client.isAvailable()) {
            resp.put("available", false);
            return resp;
        }
        ModelBundle model = resolveModel(gameId, "risk", RISK_FEATURES);
        String modelId = model != null ? model.artifact.id : RISK_HEURISTIC_MODEL;
        String modelVersion = model != null ? model.artifact.modelVersion : HEURISTIC_VERSION;
        resp.put("path", model != null ? "model" : "heuristic");
        resp.put("model", modelId);
        if (model != null) {
            resp.put("modelVersion", modelVersion);
        }

        String sql = "SELECT subject_id AS subject_id, "
                + "countIf(severity = 'CRITICAL') AS c_critical, countIf(severity = 'HIGH') AS c_high, "
                + "countIf(severity = 'MEDIUM') AS c_medium, countIf(severity = 'LOW') AS c_low, "
                + "uniqExact(rule_id) AS distinct_rules "
                + "FROM risk_events WHERE game_id = ?" + envFilter(environment)
                + " AND ts >= ? GROUP BY subject_id ORDER BY count() DESC LIMIT 200";
        Timestamp since = Timestamp.from(Instant.now().minusSeconds(30 * 24 * 3600L));
        List<Map<String, Object>> rows = environment == null || environment.isBlank()
                ? client.query(sql, gameId, since)
                : client.query(sql, gameId, environment, since);

        long high = 0, medium = 0, low = 0;
        for (Map<String, Object> row : rows) {
            String subjectId = RiskMetricsAssembler.asString(row.get("subject_id"));
            if (subjectId.isEmpty()) {
                continue;
            }
            double score;
            if (model != null) {
                double[] features = {
                        RiskMetricsAssembler.asDouble(row.get("c_critical")),
                        RiskMetricsAssembler.asDouble(row.get("c_high")),
                        RiskMetricsAssembler.asDouble(row.get("c_medium")),
                        RiskMetricsAssembler.asDouble(row.get("c_low")),
                        RiskMetricsAssembler.asDouble(row.get("distinct_rules"))};
                score = MlArtifactScorer.score(model.coefficients, model.artifact.intercept, features);
            } else {
                RiskScorer.Scored scored = RiskScorer.score(
                        RiskMetricsAssembler.asLong(row.get("c_critical")),
                        RiskMetricsAssembler.asLong(row.get("c_high")),
                        RiskMetricsAssembler.asLong(row.get("c_medium")),
                        RiskMetricsAssembler.asLong(row.get("c_low")));
                score = scored.score();
            }
            writePrediction(gameId, environment, subjectId, modelId, modelVersion, "risk_model", score);
            String level = score >= 0.7 ? "high" : score >= 0.2 ? "medium" : "low";
            switch (level) {
                case "high" -> high++;
                case "medium" -> medium++;
                default -> low++;
            }
        }
        resp.put("scored", rows.size());
        resp.put("high", high);
        resp.put("medium", medium);
        resp.put("low", low);
        resp.put("model", modelId);
        return resp;
    }

    /** 高模型风险分主体 */
    public Map<String, Object> topRiskScore(String gameId, String environment, Integer limit) {
        return topByType(gameId, environment, limit, "risk_model");
    }

    // ========== pLTV ==========

    /**
     * pLTV 批量预测：未成熟用户（注册 7-30 天）的 D7 收入 × D7→D30 乘数。
     * 乘数来源：注册的 pltv 产物优先；无产物回落 LtvForecastAssembler
     * （成熟 cohort 比值等权均值，与 ml 启发式基线同口径）。
     */
    public Map<String, Object> refreshPltv(String gameId, String environment) {
        Map<String, Object> resp = base(gameId, environment, "pltv");
        if (!client.isAvailable()) {
            resp.put("available", false);
            return resp;
        }

        double multiplier;
        String modelId;
        String modelVersion;
        MlArtifactEntity artifact = registry.resolveActive(gameId, "pltv").orElse(null);
        if (artifact != null && artifact.multiplier != null && artifact.multiplier > 0) {
            multiplier = artifact.multiplier;
            modelId = artifact.id;
            modelVersion = artifact.modelVersion;
            resp.put("path", "model");
        } else {
            List<Map<String, Object>> ltvRows = client.query(
                    "SELECT cohort_date AS cohort, age_day AS age_day, revenue AS revenue "
                            + "FROM v_ltv_by_cohort_day WHERE game_id = ?" + envFilter(environment),
                    environment == null || environment.isBlank()
                            ? new Object[]{gameId} : new Object[]{gameId, environment});
            List<Map<String, Object>> cohortRows = client.query(
                    "SELECT cohort_date AS cohort, count() AS cohort_size "
                            + "FROM v_user_first_seen WHERE game_id = ?" + envFilter(environment)
                            + " GROUP BY cohort_date",
                    environment == null || environment.isBlank()
                            ? new Object[]{gameId} : new Object[]{gameId, environment});
            multiplier = RiskMetricsAssembler.asDouble(
                    LtvForecastAssembler.forecast(ltvRows, cohortRows, LocalDate.now().toString())
                            .get("multiplier"));
            modelId = PLTV_HEURISTIC_MODEL;
            modelVersion = HEURISTIC_VERSION;
            resp.put("path", "heuristic");
            if (multiplier <= 0) {
                // 诚实降级：无成熟 cohort 时乘数不可估，不写回任何预测
                resp.put("scored", 0);
                resp.put("model", modelId);
                resp.put("reason", "无成熟 cohort，D7→D30 乘数不可估，未写回");
                return resp;
            }
        }
        resp.put("model", modelId);
        if ("model".equals(resp.get("path"))) {
            resp.put("modelVersion", modelVersion);
        }
        resp.put("multiplier", RetentionMetricsAssembler.round4(multiplier));

        String sql = "SELECT f.user_id AS user_id, "
                + "sumIf(e.revenue_amount, e.revenue_amount > 0 AND e.event_date >= f.first_seen "
                + "AND e.event_date <= f.first_seen + 6) AS d7_revenue "
                + "FROM (SELECT game_id, environment, user_id, min(event_date) AS first_seen "
                + "FROM events WHERE game_id = ?" + envFilter(environment) + " AND user_id != '' "
                + "GROUP BY game_id, environment, user_id "
                + "HAVING first_seen <= today() - 7 AND first_seen > today() - 30) AS f "
                + "INNER JOIN events AS e ON e.game_id = f.game_id AND e.environment = f.environment "
                + "AND e.user_id = f.user_id "
                + "GROUP BY f.user_id LIMIT " + REFRESH_LIMIT;
        List<Map<String, Object>> rows = environment == null || environment.isBlank()
                ? client.query(sql, gameId)
                : client.query(sql, gameId, environment);

        for (Map<String, Object> row : rows) {
            String userId = RiskMetricsAssembler.asString(row.get("user_id"));
            if (userId.isEmpty()) {
                continue;
            }
            double d7Revenue = RiskMetricsAssembler.asDouble(row.get("d7_revenue"));
            writePrediction(gameId, environment, userId, modelId, modelVersion, "pltv",
                    RetentionMetricsAssembler.round4(d7Revenue * multiplier));
        }
        resp.put("scored", rows.size());
        logger.info("pLTV refresh: game={} path={} scored={} multiplier={}",
                gameId, resp.get("path"), rows.size(), resp.get("multiplier"));
        return resp;
    }

    /** 高 pLTV 预测用户 */
    public Map<String, Object> topPltv(String gameId, String environment, Integer limit) {
        return topByType(gameId, environment, limit, "pltv");
    }

    // ========== 辅助 ==========

    /** 榜单通用：predictions FINAL 按 type 取 TOP（score 降序，未过期） */
    private Map<String, Object> topByType(String gameId, String environment, Integer limit, String type) {
        Map<String, Object> resp = base(gameId, environment, type);
        if (!client.isAvailable()) {
            resp.put("available", false);
            return resp;
        }
        int n = clampLimit(limit);
        String sql = "SELECT user_id AS user_id, score AS score, predicted_at AS predicted_at "
                + "FROM predictions FINAL WHERE prediction_type = '" + type + "' AND game_id = ?"
                + envFilter(environment) + " AND expires_at > now() ORDER BY score DESC LIMIT " + n;
        List<Map<String, Object>> rows = environment == null || environment.isBlank()
                ? client.query(sql, gameId)
                : client.query(sql, gameId, environment);
        resp.put("users", toUsers(rows));
        return resp;
    }

    /**
     * 解析游戏某类型的可用产物：存在、特征口径与预期一致、系数完整且长度匹配时
     * 返回可打分的 ModelBundle；否则 null（调用方回落启发式）。
     */
    private ModelBundle resolveModel(String gameId, String type, List<String> expectedFeatures) {
        MlArtifactEntity artifact = registry.resolveActive(gameId, type).orElse(null);
        if (artifact == null) {
            return null;
        }
        try {
            List<String> names = MlArtifactScorer.parseFeatureNames(artifact.featureNames);
            double[] coefficients = MlArtifactScorer.parseCoefficients(artifact.coefficients);
            if (!names.equals(expectedFeatures)
                    || coefficients.length != names.size()
                    || artifact.intercept == null) {
                logger.warn("ml artifact feature mismatch for game={} type={}, falling back to heuristic", gameId, type);
                return null;
            }
            return new ModelBundle(artifact, coefficients);
        } catch (Exception e) {
            logger.warn("ml artifact unreadable for game={} type={}, falling back to heuristic: {}",
                    gameId, type, e.getMessage());
            return null;
        }
    }

    /** 可打分的产物视图：实体 + 按特征序解析好的系数 */
    private record ModelBundle(MlArtifactEntity artifact, double[] coefficients) {}

    private void writePrediction(String gameId, String environment, String userId,
                                 String modelId, String modelVersion, String predictionType, double score) {
        try {
            client.update("INSERT INTO predictions (game_id, environment, user_id, model_id, "
                            + "model_version, prediction_type, score, predicted_at, expires_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, now64(3), ?)",
                    gameId, environment == null || environment.isBlank() ? "" : environment,
                    userId, modelId, modelVersion, predictionType, (float) score,
                    Timestamp.from(Instant.now().plusSeconds(PREDICTION_TTL_DAYS * 24 * 3600L)));
        } catch (Exception e) {
            logger.error("Failed to write prediction: type={} user={}", predictionType, userId, e);
        }
    }

    private static List<Map<String, Object>> toUsers(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userId", RiskMetricsAssembler.asString(row.get("user_id")));
            m.put("score", RiskMetricsAssembler.asDouble(row.get("score")));
            m.put("predictedAt", RiskMetricsAssembler.toIso(row.get("predicted_at")));
            Object daysInactive = row.get("days_inactive");
            if (daysInactive != null) {
                m.put("daysInactive", RiskMetricsAssembler.asLong(daysInactive));
                m.put("sessionCount", RiskMetricsAssembler.asLong(row.get("session_count")));
                m.put("revenue30d", RiskMetricsAssembler.asDouble(row.get("revenue_total")));
            }
            out.add(m);
        }
        return out;
    }

    static int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return TOP_LIMIT;
        }
        return Math.min(limit, 500);
    }

    static String envFilter(String environment) {
        return environment == null || environment.isBlank() ? "" : " AND environment = ?";
    }

    static Map<String, Object> base(String gameId, String environment, String type) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("gameId", gameId);
        m.put("environment", environment);
        m.put("type", type);
        m.put("available", true);
        return m;
    }
}
