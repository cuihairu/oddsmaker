package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.FunnelConfigEntity;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 可配置漏斗结果查询（ClickHouse 数据源：funnels_configurable，SummingMergeTree）。
 * Flink funnels-job 按 (game, environment, funnel, day, step) 落每步用户行；
 * 此处聚合窗口内每步 users 并按步重算转化率（行级 conversion_rate 恒 100 不可聚合）。
 * 口径：users = 按日去重用户数的跨日累加（同一用户跨天窗口内重复计入）。
 * ClickHouse 未配置时统一返回 available=false 的空数据。
 */
@Service
public class FunnelResultsService {

    private static final int DEFAULT_DAYS = 90;
    private static final int MAX_DAYS = 365;

    private final ClickHouseClient client;
    private final FunnelConfigService funnelConfigService;

    public FunnelResultsService(ClickHouseClient client, FunnelConfigService funnelConfigService) {
        this.client = client;
        this.funnelConfigService = funnelConfigService;
    }

    public Map<String, Object> results(String funnelId, String gameId, String environment, Integer days) {
        // 归属校验：防 funnelId 跨游戏探测（AccessGuard 只按 gameId 鉴权）
        FunnelConfigEntity funnel = funnelConfigService.findById(funnelId);
        if (!funnel.gameId.equals(gameId)) {
            throw new IllegalArgumentException("Funnel " + funnelId + " does not belong to game " + gameId);
        }

        int d = clampDays(days);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("funnelId", funnelId);
        resp.put("funnelName", funnel.name);
        resp.put("funnelType", funnel.type != null ? funnel.type.name() : null);
        resp.put("gameId", gameId);
        resp.put("environment", environment);
        resp.put("days", d);
        resp.put("available", true);
        if (!client.isAvailable()) {
            resp.put("available", false);
            return resp;
        }

        LocalDate since = LocalDate.now().minusDays(d);
        String sql = "SELECT step, anyLast(step_name) AS step_name, sum(users) AS users "
                + "FROM funnels_configurable "
                + "WHERE funnel_id = ? AND game_id = ?" + envFilter(environment) + " AND event_date >= ? "
                + "GROUP BY step ORDER BY step ASC";
        List<Map<String, Object>> rows = environment == null || environment.isBlank()
                ? client.query(sql, funnelId, gameId, since)
                : client.query(sql, funnelId, gameId, environment, since);

        assembleSteps(resp, rows);
        return resp;
    }

    /** 行 → 每步 users/总体转化/上一步转化/流失；空行 → steps=[]、overall=null。 */
    static void assembleSteps(Map<String, Object> resp, List<Map<String, Object>> rows) {
        List<Map<String, Object>> steps = new ArrayList<>();
        long firstUsers = 0;
        long lastUsers = 0;
        long prevUsers = 0;
        boolean any = false;
        for (Map<String, Object> row : rows) {
            long users = ((Number) row.get("users")).longValue();
            int step = ((Number) row.get("step")).intValue();
            boolean first = !any;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("step", step);
            item.put("stepName", row.get("step_name"));
            item.put("users", users);
            if (!any) {
                firstUsers = users;
                item.put("overallRate", 1.0);
                item.put("stepRate", null);
                item.put("dropOff", 0L);
            } else {
                item.put("overallRate", firstUsers > 0 ? (double) users / firstUsers : 0.0);
                item.put("stepRate", prevUsers > 0 ? (double) users / prevUsers : null);
                item.put("dropOff", Math.max(0, prevUsers - users));
            }
            steps.add(item);
            lastUsers = users;
            prevUsers = users;
            any = true;
        }
        resp.put("steps", steps);
        if (!any) {
            resp.put("overall", null);
        } else {
            Map<String, Object> overall = new LinkedHashMap<>();
            overall.put("firstUsers", firstUsers);
            overall.put("lastUsers", lastUsers);
            overall.put("rate", firstUsers > 0 ? (double) lastUsers / firstUsers : 0.0);
            resp.put("overall", overall);
        }
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
}
