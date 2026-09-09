package io.oddsmaker.control.service;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Crash/Error 监控指标（ClickHouse 数据源：events + v_crash_* 视图）。
 * 崩溃分组（crash_hash 聚合，Gateway 注入）、按日趋势、按版本崩溃率。
 * ClickHouse 未配置时统一返回 available=false 的空数据。
 */
@Service
public class CrashMetricsService {

    private static final int DEFAULT_DAYS = 14;
    private static final int MAX_DAYS = 180;
    private static final int TOP_GROUPS_LIMIT = 50;

    private final ClickHouseClient client;

    public CrashMetricsService(ClickHouseClient client) {
        this.client = client;
    }

    /** Top 崩溃分组：crash_hash（Gateway 注入，缺省回退 event_name）聚合排行 */
    public Map<String, Object> topGroups(String gameId, String environment, Integer days) {
        int d = clampDays(days);
        Map<String, Object> resp = base(gameId, environment, d);
        if (!client.isAvailable()) {
            resp.put("available", false);
            return resp;
        }
        String sql = "SELECT crash_group, sample_message, occurrences, affected_devices, first_seen, last_seen "
                + "FROM (SELECT coalesce(nullIf(JSONExtractString(props_json, 'crash_hash'), ''), event_name) "
                + "AS crash_group, any(JSONExtractString(props_json, 'crash_message')) AS sample_message, "
                + "count() AS occurrences, uniqExact(device_id) AS affected_devices, "
                + "min(event_date) AS first_seen, max(event_date) AS last_seen "
                + "FROM events WHERE event_type = 'error' AND game_id = ?" + envFilter(environment)
                + " AND event_date >= ? GROUP BY crash_group ORDER BY occurrences DESC) LIMIT " + TOP_GROUPS_LIMIT;
        LocalDate since = LocalDate.now().minusDays(d);
        List<Map<String, Object>> rows = environment == null || environment.isBlank()
                ? client.query(sql, gameId, since)
                : client.query(sql, gameId, environment, since);
        resp.put("groups", CrashMetricsAssembler.toTopGroups(rows, TOP_GROUPS_LIMIT));
        return resp;
    }

    /** 按日崩溃趋势：崩溃次数与影响设备数 */
    public Map<String, Object> trend(String gameId, String environment, Integer days) {
        int d = clampDays(days);
        Map<String, Object> resp = base(gameId, environment, d);
        if (!client.isAvailable()) {
            resp.put("available", false);
            return resp;
        }
        String sql = "SELECT event_date AS bucket, count() AS crashes, "
                + "uniqExact(device_id) AS affected_devices "
                + "FROM events WHERE event_type = 'error' AND game_id = ?" + envFilter(environment)
                + " AND event_date >= ? GROUP BY bucket ORDER BY bucket";
        LocalDate since = LocalDate.now().minusDays(d);
        List<Map<String, Object>> rows = environment == null || environment.isBlank()
                ? client.query(sql, gameId, since)
                : client.query(sql, gameId, environment, since);
        resp.put("points", CrashMetricsAssembler.toTrend(rows));
        return resp;
    }

    /** 按版本崩溃率：崩溃设备数 / 活跃设备数 */
    public Map<String, Object> rateByVersion(String gameId, String environment, Integer days) {
        int d = clampDays(days);
        Map<String, Object> resp = base(gameId, environment, d);
        if (!client.isAvailable()) {
            resp.put("available", false);
            return resp;
        }
        LocalDate since = LocalDate.now().minusDays(d);
        String sql = "SELECT app_version AS app_version, event_date AS event_date, "
                + "crash_devices AS crash_devices, active_devices AS active_devices, crash_rate AS crash_rate "
                + "FROM (SELECT game_id, environment, event_date, app_version, "
                + "uniqExact(device_id) AS crash_devices "
                + "FROM events WHERE event_type = 'error' AND game_id = ?" + envFilter(environment)
                + " AND event_date >= ? GROUP BY game_id, environment, event_date, app_version) AS crash "
                + "INNER JOIN (SELECT game_id, environment, event_date, app_version, "
                + "uniqExact(device_id) AS active_devices FROM events WHERE game_id = ?"
                + envFilter(environment) + " AND event_date >= ? "
                + "GROUP BY game_id, environment, event_date, app_version) AS active "
                + "ON crash.game_id = active.game_id AND crash.environment = active.environment "
                + "AND crash.event_date = active.event_date AND crash.app_version = active.app_version";
        List<Map<String, Object>> rows;
        if (environment == null || environment.isBlank()) {
            rows = client.query(sql, gameId, since, gameId, since);
        } else {
            rows = client.query(sql, gameId, environment, since, gameId, environment, since);
        }
        resp.put("versions", CrashMetricsAssembler.toVersionRates(rows));
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

    static Map<String, Object> base(String gameId, String environment, int days) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("gameId", gameId);
        m.put("environment", environment);
        m.put("days", days);
        m.put("available", true);
        return m;
    }
}
