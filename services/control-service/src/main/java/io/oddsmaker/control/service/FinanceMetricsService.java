package io.oddsmaker.control.service;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 财务核心指标报表（ClickHouse 数据源：events + v_user_first_seen）。
 * 按日/月输出新增、DAU、收入、付费主体数、订单数与派生 ARPU/ARPPU/付费率；支持 CSV 导出。
 * 主体口径与在线监控一致：player_id > user_id > device_id 兜底。
 * ClickHouse 未配置时统一返回 available=false 的空数据。
 */
@Service
public class FinanceMetricsService {

    private static final int DEFAULT_DAYS = 90;
    private static final int MAX_DAYS = 730;

    /** 主体表达式：player 优先，user 次之，device 兜底 */
    private static final String SUBJECT =
            "if(player_id != '', player_id, if(user_id != '', user_id, device_id))";

    private final ClickHouseClient client;

    public FinanceMetricsService(ClickHouseClient client) {
        this.client = client;
    }

    public Map<String, Object> report(String gameId, String environment, String granularity, Integer days,
                                      String segmentId) {
        String g = normalizeGranularity(granularity);
        int d = clampDays(days);
        Map<String, Object> resp = base(gameId, environment, g, d);
        if (segmentId != null && !segmentId.isBlank()) {
            resp.put("segmentId", segmentId);
        }
        if (!client.isAvailable()) {
            resp.put("available", false);
            return resp;
        }
        List<Map<String, Object>> rows = queryRows(gameId, environment, g, d, segmentId);
        resp.put("rows", rows);
        resp.put("summary", FinanceMetricsAssembler.toSummary(rows));
        return resp;
    }

    /** CSV 导出内容（UTF-8 文本，Controller 负责 BOM 与下载头） */
    public String exportCsv(String gameId, String environment, String granularity, Integer days, String segmentId) {
        String g = normalizeGranularity(granularity);
        int d = clampDays(days);
        if (!client.isAvailable()) {
            throw new IllegalStateException("ClickHouse not configured, finance export unavailable");
        }
        return FinanceMetricsAssembler.toCsv(gameId, g, queryRows(gameId, environment, g, d, segmentId));
    }

    private List<Map<String, Object>> queryRows(String gameId, String environment, String granularity, int days,
                                                String segmentId) {
        LocalDate since = LocalDate.now(ZoneOffset.UTC).minusDays(days);
        String bucket = "month".equals(granularity) ? "toStartOfMonth" : "";
        // P7-2：分群过滤——activity 走 events 主体口径；new_users 键为 user_id（与分群 subject_id 匹配）
        boolean hasSegment = segmentId != null && !segmentId.isBlank();
        String subjectSeg = hasSegment ? SegmentService.segmentFilterFragment(SUBJECT) : "";
        String userIdSeg = hasSegment ? SegmentService.segmentFilterFragment("user_id") : "";

        String activitySql = "SELECT " + bucketApply(bucket, "event_date") + " AS stat_date, "
                + "uniqExact(" + SUBJECT + ") AS dau, "
                + "sumIf(revenue_amount, revenue_amount > 0) AS revenue, "
                + "uniqExactIf(" + SUBJECT + ", revenue_amount > 0) AS payers, "
                + "uniqExactIf(order_id, order_id != '') AS orders "
                + "FROM events WHERE game_id = ?" + envFilter(environment)
                + " AND event_date >= ?" + subjectSeg + " GROUP BY stat_date ORDER BY stat_date";
        List<Object> activityArgs = new ArrayList<>(List.of(gameId));
        if (environment != null && !environment.isBlank()) activityArgs.add(environment);
        activityArgs.add(since);
        if (hasSegment) activityArgs.addAll(List.of(segmentId, gameId));
        List<Map<String, Object>> activityRows = client.query(activitySql, activityArgs.toArray());

        String newUsersSql = "SELECT " + bucketApply(bucket, "cohort_date") + " AS stat_date, "
                + "count() AS new_users "
                + "FROM v_user_first_seen WHERE game_id = ?" + envFilter(environment)
                + " AND cohort_date >= ?" + userIdSeg + " GROUP BY stat_date ORDER BY stat_date";
        List<Object> newUserArgs = new ArrayList<>(List.of(gameId));
        if (environment != null && !environment.isBlank()) newUserArgs.add(environment);
        newUserArgs.add(since);
        if (hasSegment) newUserArgs.addAll(List.of(segmentId, gameId));
        List<Map<String, Object>> newUserRows = client.query(newUsersSql, newUserArgs.toArray());

        return FinanceMetricsAssembler.toRows(activityRows, newUserRows);
    }

    static String bucketApply(String bucket, String column) {
        return bucket.isEmpty() ? column : bucket + "(" + column + ")";
    }

    static String normalizeGranularity(String granularity) {
        return "month".equals(granularity) ? "month" : "day";
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
