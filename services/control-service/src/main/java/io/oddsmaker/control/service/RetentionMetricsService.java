package io.oddsmaker.control.service;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 留存趋势报表（ClickHouse 数据源：retention_daily，Flink RetentionJob 输出）。
 * 按天/周/月 cohort 对齐输出新增用户与次留/7留/30留趋势。
 * ClickHouse 未配置时统一返回 available=false 的空数据。
 */
@Service
public class RetentionMetricsService {

    private static final int DEFAULT_DAYS = 90;
    private static final int MAX_DAYS = 730;

    private final ClickHouseClient client;

    public RetentionMetricsService(ClickHouseClient client) {
        this.client = client;
    }

    public Map<String, Object> trend(String gameId, String environment, String granularity, Integer days) {
        String g = normalizeGranularity(granularity);
        int d = clampDays(days);
        Map<String, Object> resp = base(gameId, environment, g, d);
        if (!client.isAvailable()) {
            resp.put("available", false);
            return resp;
        }
        String bucket = RetentionMetricsAssembler.bucketFunction(g);
        String cohortExpr = bucket.isEmpty() ? "cohort_date" : bucket + "(cohort_date)";
        String sql = "SELECT " + cohortExpr + " AS cohort, d AS d, sum(users) AS users "
                + "FROM retention_daily WHERE game_id = ?" + envFilter(environment)
                + " AND cohort_date >= ? AND d IN (0, 1, 7, 30) GROUP BY cohort, d ORDER BY cohort, d";
        LocalDate since = LocalDate.now().minusDays(d);
        List<Map<String, Object>> rows = environment == null || environment.isBlank()
                ? client.query(sql, gameId, since)
                : client.query(sql, gameId, environment, since);
        List<Map<String, Object>> points = RetentionMetricsAssembler.toTrendPoints(rows);
        resp.put("points", points);
        resp.put("summary", RetentionMetricsAssembler.toSummary(points, 30));
        return resp;
    }

    static String normalizeGranularity(String granularity) {
        if (granularity == null || granularity.isBlank()) {
            return "day";
        }
        return switch (granularity) {
            case "week", "month" -> granularity;
            default -> "day";
        };
    }

    static int clampDays(Integer days) {
        if (days == null || days <= 0) {
            return DEFAULT_DAYS;
        }
        return Math.min(days, MAX_DAYS);
    }

    static String envFilter(String environment) {
        return environment == null || environment.isBlank() ? "" : " AND environment = ?";
    }

    static Map<String, Object> base(String gameId, String environment, String granularity, int days) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("gameId", gameId);
        m.put("environment", environment);
        m.put("granularity", granularity);
        m.put("days", days);
        m.put("available", true);
        return m;
    }
}
