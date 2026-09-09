package io.oddsmaker.control.service;

import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实时在线监控（ClickHouse 数据源：events）。
 * 在线口径：近 N 分钟（默认 5）内有事件上报的独立主体数（player_id > user_id > device_id 兜底）；
 * 支持按平台/版本/渠道（attribution.channel 缺省回退 platform）分组与分钟趋势。
 * ClickHouse 未配置时统一返回 available=false 的空数据。
 */
@Service
public class OnlineMetricsService {

    private static final int DEFAULT_MINUTES = 5;
    private static final int MAX_MINUTES = 60;
    private static final int BREAKDOWN_LIMIT = 50;

    /** 主体表达式：player 优先，user 次之，device 兜底 */
    private static final String SUBJECT =
            "if(player_id != '', player_id, if(user_id != '', user_id, device_id))";

    /** 分组维度白名单（SQL 表达式，防注入） */
    private static final Map<String, String> DIMENSIONS = Map.of(
            "platform", "if(platform = '', 'unknown', platform)",
            "appVersion", "if(app_version = '', 'unknown', app_version)",
            "channel", "if(attribution['channel'] != '', attribution['channel'],"
                    + " if(platform = '', 'unknown', platform))");

    private final ClickHouseClient client;

    public OnlineMetricsService(ClickHouseClient client) {
        this.client = client;
    }

    public Map<String, Object> overview(String gameId, String environment, Integer minutes) {
        int m = clampMinutes(minutes);
        int trendMinutes = Math.max(60, m);
        Map<String, Object> resp = base(gameId, environment, m, trendMinutes);
        if (!client.isAvailable()) {
            resp.put("available", false);
            return resp;
        }

        String totalSql = "SELECT uniqExact(" + SUBJECT + ") AS online "
                + "FROM events WHERE game_id = ?" + envFilter(environment) + " AND ts_server >= ?";
        resp.put("online", OnlineMetricsAssembler.toTotal(query(totalSql, gameId, environment, minutesAgo(m))));

        String dimSql = "SELECT %s AS dim, uniqExact(" + SUBJECT + ") AS online "
                + "FROM events WHERE game_id = ?" + envFilter(environment)
                + " AND ts_server >= ? GROUP BY dim ORDER BY online DESC LIMIT " + BREAKDOWN_LIMIT;
        for (Map.Entry<String, String> dimension : DIMENSIONS.entrySet()) {
            List<Map<String, Object>> rows = query(String.format(dimSql, dimension.getValue()),
                    gameId, environment, minutesAgo(m));
            resp.put("by" + Character.toUpperCase(dimension.getKey().charAt(0)) + dimension.getKey().substring(1),
                    OnlineMetricsAssembler.toDimensionBreakdown(rows));
        }

        String trendSql = "SELECT toStartOfMinute(ts_server) AS bucket, uniqExact(" + SUBJECT + ") AS online "
                + "FROM events WHERE game_id = ?" + envFilter(environment)
                + " AND ts_server >= ? GROUP BY bucket ORDER BY bucket";
        resp.put("trend", OnlineMetricsAssembler.toTrendPoints(
                query(trendSql, gameId, environment, minutesAgo(trendMinutes))));
        return resp;
    }

    private List<Map<String, Object>> query(String sql, String gameId, String environment, Timestamp since) {
        return environment == null || environment.isBlank()
                ? client.query(sql, gameId, since)
                : client.query(sql, gameId, environment, since);
    }

    static int clampMinutes(Integer minutes) {
        if (minutes == null || minutes <= 0) {
            return DEFAULT_MINUTES;
        }
        return Math.min(minutes, MAX_MINUTES);
    }

    static Timestamp minutesAgo(int minutes) {
        return Timestamp.from(Instant.now().minusSeconds(minutes * 60L));
    }

    static String envFilter(String environment) {
        return environment == null || environment.isBlank() ? "" : " AND environment = ?";
    }

    static Map<String, Object> base(String gameId, String environment, int minutes, int trendMinutes) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("gameId", gameId);
        m.put("environment", environment);
        m.put("minutes", minutes);
        m.put("trendMinutes", trendMinutes);
        m.put("available", true);
        return m;
    }
}
