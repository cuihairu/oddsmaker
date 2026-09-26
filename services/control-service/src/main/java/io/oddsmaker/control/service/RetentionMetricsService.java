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
    /** 分群实时回退路径的窗口钳制与查询超时（防 events 大窗口全表扫描）。 */
    private static final int REALTIME_MAX_DAYS = 90;
    private static final int REALTIME_TIMEOUT_SECONDS = 15;

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

    /** 常规路径（无分群）：直接聚合 retention_daily，不按主体过滤 */
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
     * 分群路径：优先走 Flink 预聚合的 retention_daily（带 subject_id 维度），
     * 成员过滤下推 ClickHouse；预聚合无数据（历史 cohort 未带主体维度）时
     * 回退 events 实时计算（窗口钳制 90 天 + 15s 查询超时，防大窗口全表扫描）。
     */
    private List<Map<String, Object>> segmentTrend(String gameId, String environment,
                                                   String bucket, LocalDate since, String segmentId) {
        String seg = SegmentService.segmentFilterFragment(SegmentService.SUBJECT_PLAYER);
        String envF = envFilter(environment);
        String cohortExpr = bucket.isEmpty() ? "cohort_date" : bucket + "(cohort_date)";
        String sql = "SELECT " + cohortExpr + " AS cohort, d AS d, sum(users) AS users "
                + "FROM retention_daily WHERE game_id = ?" + envF
                + " AND cohort_date >= ? AND d IN (0, 1, 7, 30)"
                + " AND subject_id != ''" + seg
                + " GROUP BY cohort, d ORDER BY cohort, d";
        boolean envBlank = environment == null || environment.isBlank();
        // 占位符依 SQL 顺序：game → env → since → 成员子查询 (segmentId, gameId)
        List<Object> args = new ArrayList<>(List.of(gameId));
        if (!envBlank) args.add(environment);
        args.add(since);
        args.addAll(List.of(segmentId, gameId));
        List<Map<String, Object>> rows = client.query(sql, args.toArray());
        if (!rows.isEmpty()) {
            return rows;
        }
        return realtimeSegmentTrend(gameId, environment, bucket, segmentId);
    }

    /**
     * 实时回退（预聚合缺主体维度的历史数据时兜底）：
     * cohort = 主体首次出现日（player>user>device 口径，与分群物化对齐）；dX = 注册后第 X 天有事件。
     * 窗口钳制 90 天并加 max_execution_time，避免大窗口 events 全表扫描拖垮 CH。
     */
    private List<Map<String, Object>> realtimeSegmentTrend(String gameId, String environment,
                                                           String bucket, String segmentId) {
        String subjectExpr = SegmentService.SUBJECT_PLAYER;
        String seg = SegmentService.segmentFilterFragment(subjectExpr);
        String envF = envFilter(environment);
        LocalDate clampedSince = LocalDate.now(ZoneOffset.UTC).minusDays(REALTIME_MAX_DAYS);
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
                + "WHERE f.cohort_date >= ? GROUP BY cohort, d ORDER BY cohort, d "
                + "SETTINGS max_execution_time = " + REALTIME_TIMEOUT_SECONDS;
        boolean envBlank = environment == null || environment.isBlank();
        // 占位符依 SQL 顺序：cohort 子查询 → 活跃子查询 → 外层 since
        List<Object> args = new ArrayList<>(List.of(gameId));
        if (!envBlank) args.add(environment);
        args.addAll(List.of(segmentId, gameId));
        args.add(gameId);
        if (!envBlank) args.add(environment);
        args.addAll(List.of(segmentId, gameId));
        args.add(clampedSince);
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
