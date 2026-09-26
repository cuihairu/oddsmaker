package io.oddsmaker.control.service;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Crash/Error 监控指标（ClickHouse 数据源：events + v_crash_* 视图）。
 * 崩溃分组（crash_hash 聚合，Gateway 注入）、按日趋势、按版本崩溃率。
 * ClickHouse 未配置时统一返回 available=false 的空数据。
 * P7-2：segmentId 非空时按分群成员过滤（events 主体口径，与在线/财务一致；
 * device 主体分群的成员与主体表达式不匹配时为空集属预期语义）。
 */
@Service
public class CrashMetricsService {

    private static final int DEFAULT_DAYS = 14;
    private static final int MAX_DAYS = 180;
    private static final int TOP_GROUPS_LIMIT = 50;

    /** 主体表达式：与 SegmentService.SUBJECT_PLAYER 一致（player 优先、user 次之、device 兜底） */
    private static final String SUBJECT = SegmentService.SUBJECT_PLAYER;

    private static String segmentFilter(String segmentId) {
        return segmentId == null || segmentId.isBlank() ? "" : SegmentService.segmentFilterFragment(SUBJECT);
    }

    private static void addSegmentArgs(List<Object> args, String environment, LocalDate since,
                                       String segmentId, String gameId) {
        if (environment != null && !environment.isBlank()) args.add(environment);
        args.add(since);
        if (segmentId != null && !segmentId.isBlank()) args.addAll(List.of(segmentId, gameId));
    }

    private final ClickHouseClient client;

    public CrashMetricsService(ClickHouseClient client) {
        this.client = client;
    }

    /** Top 崩溃分组：crash_hash（Gateway 注入，缺省回退 event_name）聚合排行 */
    public Map<String, Object> topGroups(String gameId, String environment, Integer days, String segmentId) {
        int d = clampDays(days);
        Map<String, Object> resp = base(gameId, environment, d, segmentId);
        if (!client.isAvailable()) {
            resp.put("available", false);
            return resp;
        }
        String seg = segmentFilter(segmentId);
        String sql = "SELECT crash_group, sample_message, occurrences, affected_devices, first_seen, last_seen "
                + "FROM (SELECT coalesce(nullIf(JSONExtractString(props_json, 'crash_hash'), ''), event_name) "
                + "AS crash_group, any(JSONExtractString(props_json, 'crash_message')) AS sample_message, "
                + "count() AS occurrences, uniqExact(device_id) AS affected_devices, "
                + "min(event_date) AS first_seen, max(event_date) AS last_seen "
                + "FROM events WHERE event_type = 'error' AND game_id = ?" + envFilter(environment)
                + " AND event_date >= ?" + seg + " GROUP BY crash_group ORDER BY occurrences DESC) LIMIT " + TOP_GROUPS_LIMIT;
        LocalDate since = LocalDate.now(ZoneOffset.UTC).minusDays(d);
        List<Object> args = new ArrayList<>(List.of(gameId));
        addSegmentArgs(args, environment, since, segmentId, gameId);
        resp.put("groups", CrashMetricsAssembler.toTopGroups(client.query(sql, args.toArray()), TOP_GROUPS_LIMIT));
        return resp;
    }

    /** 按日崩溃趋势：崩溃次数与影响设备数 */
    public Map<String, Object> trend(String gameId, String environment, Integer days, String segmentId) {
        int d = clampDays(days);
        Map<String, Object> resp = base(gameId, environment, d, segmentId);
        if (!client.isAvailable()) {
            resp.put("available", false);
            return resp;
        }
        String seg = segmentFilter(segmentId);
        String sql = "SELECT event_date AS bucket, count() AS crashes, "
                + "uniqExact(device_id) AS affected_devices "
                + "FROM events WHERE event_type = 'error' AND game_id = ?" + envFilter(environment)
                + " AND event_date >= ?" + seg + " GROUP BY bucket ORDER BY bucket";
        LocalDate since = LocalDate.now(ZoneOffset.UTC).minusDays(d);
        List<Object> args = new ArrayList<>(List.of(gameId));
        addSegmentArgs(args, environment, since, segmentId, gameId);
        resp.put("points", CrashMetricsAssembler.toTrend(client.query(sql, args.toArray())));
        return resp;
    }

    /** 按版本崩溃率：崩溃设备数 / 活跃设备数 */
    public Map<String, Object> rateByVersion(String gameId, String environment, Integer days, String segmentId) {
        int d = clampDays(days);
        Map<String, Object> resp = base(gameId, environment, d, segmentId);
        if (!client.isAvailable()) {
            resp.put("available", false);
            return resp;
        }
        // 分群过滤同时作用于分子（崩溃）与分母（活跃），崩溃率口径保持在分群内
        String seg = segmentFilter(segmentId);
        LocalDate since = LocalDate.now(ZoneOffset.UTC).minusDays(d);
        String sql = "SELECT app_version AS app_version, event_date AS event_date, "
                + "crash_devices AS crash_devices, active_devices AS active_devices, "
                + "round(crash_devices / active_devices, 6) AS crash_rate "
                + "FROM (SELECT game_id, environment, event_date, app_version, "
                + "uniqExact(device_id) AS crash_devices "
                + "FROM events WHERE event_type = 'error' AND game_id = ?" + envFilter(environment)
                + " AND event_date >= ?" + seg + " GROUP BY game_id, environment, event_date, app_version) AS crash "
                + "INNER JOIN (SELECT game_id, environment, event_date, app_version, "
                + "uniqExact(device_id) AS active_devices FROM events WHERE game_id = ?"
                + envFilter(environment) + " AND event_date >= ?" + seg + " "
                + "GROUP BY game_id, environment, event_date, app_version) AS active "
                + "ON crash.game_id = active.game_id AND crash.environment = active.environment "
                + "AND crash.event_date = active.event_date AND crash.app_version = active.app_version";
        List<Object> args = new ArrayList<>(List.of(gameId));
        addSegmentArgs(args, environment, since, segmentId, gameId);
        args.add(gameId);
        addSegmentArgs(args, environment, since, segmentId, gameId);
        resp.put("versions", CrashMetricsAssembler.toVersionRates(client.query(sql, args.toArray())));
        return resp;
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

    static Map<String, Object> base(String gameId, String environment, int days, String segmentId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("gameId", gameId);
        m.put("environment", environment);
        m.put("days", days);
        if (segmentId != null && !segmentId.isBlank()) {
            m.put("segmentId", segmentId);
        }
        m.put("available", true);
        return m;
    }
}
