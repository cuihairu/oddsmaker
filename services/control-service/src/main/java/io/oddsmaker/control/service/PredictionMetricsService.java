package io.oddsmaker.control.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能化预测服务（ClickHouse 数据源）：
 * - 流失预测：v_user_features_30d 特征 → ChurnScorer 启发式打分 → 归档 predictions（type=churn）；
 * - 风险评分模型：risk_events 30 天严重度加权聚合 → RiskScorer 模型分 → 归档 predictions（type=risk_model）。
 * 打分算子为纯函数可替换为 ML 模型输出；ClickHouse 未配置时统一降级 available=false。
 */
@Service
public class PredictionMetricsService {

    private static final Logger logger = LoggerFactory.getLogger(PredictionMetricsService.class);

    private static final int REFRESH_LIMIT = 500;
    private static final int TOP_LIMIT = 100;
    private static final int PREDICTION_TTL_DAYS = 30;
    private static final String CHURN_MODEL = "heuristic_churn_v1";
    private static final String RISK_MODEL = "rule_aggregate_v1";

    private final ClickHouseClient client;

    public PredictionMetricsService(ClickHouseClient client) {
        this.client = client;
    }

    // ========== 流失预测 ==========

    /** 重算流失分并归档：30 天特征 → 打分 → 写 predictions */
    public Map<String, Object> refreshChurn(String gameId, String environment) {
        Map<String, Object> resp = base(gameId, environment, "churn");
        if (!client.isAvailable()) {
            resp.put("available", false);
            return resp;
        }
        String sql = "SELECT user_id AS user_id, days_inactive_30d AS days_inactive, "
                + "session_count_30d AS session_count, revenue_total_30d AS revenue_total "
                + "FROM v_user_features_30d WHERE game_id = ?" + envFilter(environment)
                + " AND days_inactive_30d >= 3 ORDER BY days_inactive_30d DESC LIMIT " + REFRESH_LIMIT;
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
            ChurnScorer.Scored scored = ChurnScorer.score(
                    RiskMetricsAssembler.asLong(row.get("days_inactive")),
                    RiskMetricsAssembler.asLong(row.get("session_count")),
                    RiskMetricsAssembler.asDouble(row.get("revenue_total")));
            writePrediction(gameId, environment, userId, CHURN_MODEL, "churn", scored.score());
            scoreSum += scored.score();
            switch (scored.level()) {
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
        resp.put("model", CHURN_MODEL);
        logger.info("Churn refresh: game={} scored={} high={}", gameId, rows.size(), high);
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

    // ========== 风险评分模型 ==========

    /** 重算主体模型风险分：risk_events 30 天严重度加权 → 归档 predictions（type=risk_model） */
    public Map<String, Object> refreshRiskScore(String gameId, String environment) {
        Map<String, Object> resp = base(gameId, environment, "risk_model");
        if (!client.isAvailable()) {
            resp.put("available", false);
            return resp;
        }
        String sql = "SELECT subject_id AS subject_id, "
                + "countIf(severity = 'CRITICAL') AS c_critical, countIf(severity = 'HIGH') AS c_high, "
                + "countIf(severity = 'MEDIUM') AS c_medium, countIf(severity = 'LOW') AS c_low "
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
            RiskScorer.Scored scored = RiskScorer.score(
                    RiskMetricsAssembler.asLong(row.get("c_critical")),
                    RiskMetricsAssembler.asLong(row.get("c_high")),
                    RiskMetricsAssembler.asLong(row.get("c_medium")),
                    RiskMetricsAssembler.asLong(row.get("c_low")));
            writePrediction(gameId, environment, subjectId, RISK_MODEL, "risk_model", scored.score());
            switch (scored.level()) {
                case "high" -> high++;
                case "medium" -> medium++;
                default -> low++;
            }
        }
        resp.put("scored", rows.size());
        resp.put("high", high);
        resp.put("medium", medium);
        resp.put("low", low);
        resp.put("model", RISK_MODEL);
        return resp;
    }

    /** 高模型风险分主体 */
    public Map<String, Object> topRiskScore(String gameId, String environment, Integer limit) {
        Map<String, Object> resp = base(gameId, environment, "risk_model");
        if (!client.isAvailable()) {
            resp.put("available", false);
            return resp;
        }
        int n = clampLimit(limit);
        String sql = "SELECT user_id AS user_id, score AS score, predicted_at AS predicted_at "
                + "FROM predictions FINAL WHERE prediction_type = 'risk_model' AND game_id = ?"
                + envFilter(environment) + " AND expires_at > now() ORDER BY score DESC LIMIT " + n;
        List<Map<String, Object>> rows = environment == null || environment.isBlank()
                ? client.query(sql, gameId)
                : client.query(sql, gameId, environment);
        resp.put("users", toUsers(rows));
        return resp;
    }

    // ========== 辅助 ==========

    private void writePrediction(String gameId, String environment, String userId,
                                 String modelId, String predictionType, double score) {
        try {
            client.update("INSERT INTO predictions (game_id, environment, user_id, model_id, "
                    + "model_version, prediction_type, score, predicted_at, expires_at) "
                    + "VALUES (?, ?, ?, ?, 'v1', ?, ?, now64(3), ?)",
                    gameId, environment == null || environment.isBlank() ? "" : environment,
                    userId, modelId, predictionType, (float) score,
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
