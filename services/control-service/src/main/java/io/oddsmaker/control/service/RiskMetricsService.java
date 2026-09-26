package io.oddsmaker.control.service;

import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 风控大屏指标（ClickHouse 数据源）：
 * 风险趋势（risk_events 按时间桶×严重等级）、规则命中（rule_id 聚合）、
 * 严重等级分布、处置状态（risk_actions 按 action/state + 最近处置明细）。
 * ClickHouse 未配置时统一返回 available=false 的空数据。
 * P7-2：segmentId 非空时按分群成员过滤（risk_events/risk_actions 的 subject_id 键，
 * 即 user_id 优先回退 device_id；与 player/device 主体分群成员不匹配时为空集属预期口径）。
 */
@Service
public class RiskMetricsService {

    private static final int DEFAULT_HOURS = 24;
    private static final int MAX_HOURS = 24 * 90;
    private static final int RULE_HITS_LIMIT = 100;
    private static final int RECENT_ACTIONS_LIMIT = 20;

    private final ClickHouseClient client;

    public RiskMetricsService(ClickHouseClient client) {
        this.client = client;
    }

    /** 分群成员过滤片段（subject_id 键）+ 对应参数追加，参数依 SQL 顺序接在 (game_id[, env], ts) 之后 */
    private static String segmentFilter(String segmentId) {
        return segmentId == null || segmentId.isBlank() ? "" : SegmentService.segmentFilterFragment("subject_id");
    }

    private static Object[] args(String gameId, String environment, Timestamp since, String segmentId) {
        java.util.List<Object> a = new java.util.ArrayList<>(java.util.List.of(gameId));
        if (environment != null && !environment.isBlank()) a.add(environment);
        a.add(since);
        if (segmentId != null && !segmentId.isBlank()) a.addAll(java.util.List.of(segmentId, gameId));
        return a.toArray();
    }

    public Map<String, Object> trend(String gameId, String environment, Integer hours, String segmentId) {
        int h = clampHours(hours);
        if (!client.isAvailable()) {
            return unavailable(gameId, environment, h, segmentId);
        }
        String bucketFn = RiskMetricsAssembler.bucketFunction(h);
        String sql = "SELECT " + bucketFn + "(ts) AS bucket, severity AS severity, count() AS c "
                + "FROM risk_events WHERE game_id = ?" + envFilter(environment)
                + " AND ts >= ?" + segmentFilter(segmentId) + " GROUP BY bucket, severity ORDER BY bucket";
        List<Map<String, Object>> rows = client.query(sql, args(gameId, environment, since(h), segmentId));
        Map<String, Object> resp = base(gameId, environment, h, segmentId);
        resp.put("points", RiskMetricsAssembler.pivotTrend(rows));
        return resp;
    }

    public Map<String, Object> ruleHits(String gameId, String environment, Integer hours, String segmentId) {
        int h = clampHours(hours);
        if (!client.isAvailable()) {
            return unavailable(gameId, environment, h, segmentId);
        }
        String sql = "SELECT rule_id AS rule_id, any(risk_type) AS risk_type, count() AS hits, "
                + "uniqExact(subject_id) AS subjects, avg(score) AS avg_score, max(ts) AS last_hit_at "
                + "FROM risk_events WHERE game_id = ?" + envFilter(environment)
                + " AND ts >= ?" + segmentFilter(segmentId)
                + " GROUP BY rule_id ORDER BY hits DESC LIMIT " + RULE_HITS_LIMIT;
        List<Map<String, Object>> rows = client.query(sql, args(gameId, environment, since(h), segmentId));
        Map<String, Object> resp = base(gameId, environment, h, segmentId);
        resp.put("rules", RiskMetricsAssembler.toRuleHits(rows));
        return resp;
    }

    public Map<String, Object> severity(String gameId, String environment, Integer hours, String segmentId) {
        int h = clampHours(hours);
        if (!client.isAvailable()) {
            return unavailable(gameId, environment, h, segmentId);
        }
        String seg = segmentFilter(segmentId);
        String sql = "SELECT severity AS severity, count() AS c "
                + "FROM risk_events WHERE game_id = ?" + envFilter(environment)
                + " AND ts >= ?" + seg + " GROUP BY severity";
        List<Map<String, Object>> bySeverity = client.query(sql, args(gameId, environment, since(h), segmentId));

        String typeSql = "SELECT risk_type AS risk_type, count() AS c "
                + "FROM risk_events WHERE game_id = ?" + envFilter(environment)
                + " AND ts >= ?" + seg + " GROUP BY risk_type";
        List<Map<String, Object>> byType = client.query(typeSql, args(gameId, environment, since(h), segmentId));

        List<Map<String, Object>> severityBreakdown = RiskMetricsAssembler.toBreakdown(bySeverity, "severity");
        Map<String, Object> resp = base(gameId, environment, h, segmentId);
        resp.put("total", RiskMetricsAssembler.sumCounts(severityBreakdown));
        resp.put("bySeverity", severityBreakdown);
        resp.put("byType", RiskMetricsAssembler.toBreakdown(byType, "riskType"));
        return resp;
    }

    public Map<String, Object> actions(String gameId, String environment, Integer hours, String segmentId) {
        int h = clampHours(hours);
        if (!client.isAvailable()) {
            return unavailable(gameId, environment, h, segmentId);
        }
        String seg = segmentFilter(segmentId);
        String actionSql = "SELECT action AS action, count() AS c "
                + "FROM risk_actions WHERE game_id = ?" + envFilter(environment)
                + " AND ts >= ?" + seg + " GROUP BY action";
        List<Map<String, Object>> byAction = client.query(actionSql, args(gameId, environment, since(h), segmentId));

        String stateSql = "SELECT state AS state, count() AS c "
                + "FROM risk_actions WHERE game_id = ?" + envFilter(environment)
                + " AND ts >= ?" + seg + " GROUP BY state";
        List<Map<String, Object>> byState = client.query(stateSql, args(gameId, environment, since(h), segmentId));

        String recentSql = "SELECT ts AS ts, risk_event_id AS risk_event_id, risk_case_id AS risk_case_id, "
                + "rule_id AS rule_id, action AS action, state AS state, subject_type AS subject_type, "
                + "subject_id AS subject_id, severity AS severity "
                + "FROM risk_actions WHERE game_id = ?" + envFilter(environment)
                + " AND ts >= ?" + seg + " ORDER BY ts DESC LIMIT " + RECENT_ACTIONS_LIMIT;
        List<Map<String, Object>> recent = client.query(recentSql, args(gameId, environment, since(h), segmentId));

        Map<String, Object> resp = base(gameId, environment, h, segmentId);
        resp.put("byAction", RiskMetricsAssembler.toBreakdown(byAction, "action"));
        resp.put("byState", RiskMetricsAssembler.toBreakdown(byState, "state"));
        resp.put("recent", RiskMetricsAssembler.toRecentActions(recent));
        return resp;
    }

    private static String envFilter(String environment) {
        return environment == null || environment.isBlank() ? "" : " AND environment = ?";
    }

    private static Timestamp since(int hours) {
        return Timestamp.from(Instant.now().minusSeconds(hours * 3600L));
    }

    private static int clampHours(Integer hours) {
        if (hours == null || hours <= 0) {
            return DEFAULT_HOURS;
        }
        return Math.min(hours, MAX_HOURS);
    }

    private static Map<String, Object> base(String gameId, String environment, int hours, String segmentId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("gameId", gameId);
        m.put("environment", environment);
        m.put("hours", hours);
        if (segmentId != null && !segmentId.isBlank()) {
            m.put("segmentId", segmentId);
        }
        m.put("available", true);
        return m;
    }

    private static Map<String, Object> unavailable(String gameId, String environment, int hours, String segmentId) {
        Map<String, Object> m = base(gameId, environment, hours, segmentId);
        m.put("available", false);
        return m;
    }
}
