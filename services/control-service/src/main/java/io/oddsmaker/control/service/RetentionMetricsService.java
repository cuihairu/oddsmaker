package io.oddsmaker.control.service;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
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

    public Map<String, Object> trend(String gameId, String environment, String granularity, Integer days,
                                     String segmentId) {
        String g = normalizeGranularity(granularity);
        int d = clampDays(days);
        Map<String, Object> resp = base(gameId, environment, g, d);
        boolean hasSegment = segmentId != null && !segmentId.isBlank();
        if (hasSegment) {
            resp.put("segmentId", segmentId);
        }
        if (!client.isAvailable()) {
            resp.put("available", false);
            return resp;
        }
        LocalDate since = LocalDate.now(ZoneOffset.UTC).minusDays(d);
        String bucket = RetentionMetricsAssembler.bucketFunction(g);
        List<Map<String, Object>> rows = hasSegment
                ? segmentTrend(gameId, environment, bucket, since, segmentId)
                : aggregateTrend(gameId, environment, bucket, since);
        List<Map<String, Object>> points = RetentionMetricsAssembler.toTrendPoints(rows);
        resp.put("points", points);
        resp.put("summary", RetentionMetricsAssembler.toSummary(points, 30));
        return resp;
    }

    /** 常规路径：Flink 预聚合的 retention_daily（无主体维度，不支持分群过滤） */
    private List<Map<String, Object>> aggregateTrend(String gameId, String environment,
                                                     String bucket, LocalDate since) {
        String cohortExpr = bucket.isEmpty() ? "cohort_date" : bucket + "(cohort_date)";
        String sql = "SELECT " + cohortExpr + " AS cohort, d AS d, sum(users) AS users "
                + "FROM retention_daily WHERE game_id = ?" + envFilter(environment)
                + " AND cohort_date >= ? AND d IN (0, 1, 7, 30) GROUP BY cohort, d ORDER BY cohort, d";
        return environment == null || environment.isBlank()
                ? client.query(sql, gameId, since)
                : client.query(sql, gameId, environment, since);
    }

    /**
     * 分群路径（P7-2）：retention_daily 无主体维度，改从 events 实时计算。
     * cohort = 主体首次出现日（与 v_user_first_seen 口径一致，但保留 player>user>device 主体表达式，
     * 与分群物化口径对齐）；dX = 注册后第 X 天有事件。输出列形与 retention_daily 一致，复用同一 assembler。
     */
    private List<Map<String, Object>> segmentTrend(String gameId, String environment,
                                                   String bucket, LocalDate since, String segmentId) {
        String subjectExpr = SegmentService.SUBJECT_PLAYER;
        String seg = SegmentService.segmentFilterFragment(subjectExpr);
        String envF = envFilter(environment);
        String cohortSub = "SELECT " + subjectExpr + " AS subject_id, min(event_date) AS cohort_date "
                + "FROM events WHERE game_id = ?" + envF + seg + " GROUP BY subject_id";
        String activeSub = "SELECT DISTINCT " + subjectExpr + " AS subject_id, event_date "
                + "FROM events WHERE game_id = ?" + envF + seg;
        String cohortExpr = bucket.isEmpty() ? "f.cohort_date" : bucket + "(f.cohort_date)";
        String sql = "SELECT " + cohortExpr + " AS cohort, d AS d, "
                + "uniqExactIf(f.subject_id, d = 0 OR e.subject_id != '') AS users "
                + "FROM (" + cohortSub + ") AS f ARRAY JOIN [0, 1, 7, 30] AS d "
                + "LEFT JOIN (" + activeSub + ") AS e "
                + "ON e.subject_id = f.subject_id AND dateDiff('day', f.cohort_date, e.event_date) = d "
                + "WHERE f.cohort_date >= ? GROUP BY cohort, d ORDER BY cohort, d";
        boolean envBlank = environment == null || environment.isBlank();
        // 占位符依 SQL 顺序：cohort 子查询 → 活跃子查询 → 外层 since
        List<Object> args = new ArrayList<>(List.of(gameId));
        if (!envBlank) args.add(environment);
        args.addAll(List.of(segmentId, gameId));
        args.add(gameId);
        if (!envBlank) args.add(environment);
        args.addAll(List.of(segmentId, gameId));
        args.add(since);
        return client.query(sql, args.toArray());
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
