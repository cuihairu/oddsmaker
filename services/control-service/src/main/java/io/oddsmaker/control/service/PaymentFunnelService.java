package io.oddsmaker.control.service;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 付费转化漏斗（ClickHouse 数据源：events + v_user_first_seen）。
 * 窗口期内新增（首次出现）用户的注册 → 首充 → 二充 → 月留存（注册后 1-30 天内活跃）转化。
 * ClickHouse 未配置时统一返回 available=false 的空数据。
 */
@Service
public class PaymentFunnelService {

    private static final int DEFAULT_DAYS = 90;
    private static final int MAX_DAYS = 365;

    private final ClickHouseClient client;

    public PaymentFunnelService(ClickHouseClient client) {
        this.client = client;
    }

    public Map<String, Object> funnel(String gameId, String environment, Integer days) {
        int d = clampDays(days);
        Map<String, Object> resp = base(gameId, environment, d);
        if (!client.isAvailable()) {
            resp.put("available", false);
            return resp;
        }
        LocalDate since = LocalDate.now().minusDays(d);
        // 月留存窗口已关闭的 cohort 截止日（30 天窗口 + 1 天缓冲）
        String matureCutoff = LocalDate.now().minusDays(31).toString();

        // 注册/首充/二充：按 cohort 聚合（首充=付费事件≥1，二充=≥2）
        String funnelSql = "SELECT f.cohort_date AS cohort, uniqExact(f.user_id) AS registered, "
                + "uniqExactIf(f.user_id, p.pay_events >= 1) AS first_pay, "
                + "uniqExactIf(f.user_id, p.pay_events >= 2) AS second_pay "
                + "FROM v_user_first_seen AS f "
                + "LEFT JOIN (SELECT game_id, environment, user_id, count() AS pay_events FROM events "
                + "WHERE game_id = ? AND user_id != '' AND revenue_amount > 0" + envFilter(environment)
                + " GROUP BY game_id, environment, user_id) AS p "
                + "ON f.game_id = p.game_id AND f.environment = p.environment AND f.user_id = p.user_id "
                + "WHERE f.game_id = ?" + envFilter(environment) + " AND f.cohort_date >= ? "
                + "GROUP BY cohort ORDER BY cohort";
        List<Map<String, Object>> funnelRows = environment == null || environment.isBlank()
                ? client.query(funnelSql, gameId, gameId, since)
                : client.query(funnelSql, gameId, environment, gameId, environment, since);

        // 月留存：注册后 1-30 天内活跃过的用户
        String retainedSql = "SELECT f.cohort_date AS cohort, uniqExact(f.user_id) AS retained_30 "
                + "FROM events AS e "
                + "INNER JOIN v_user_first_seen AS f "
                + "ON e.game_id = f.game_id AND e.environment = f.environment AND e.user_id = f.user_id "
                + "WHERE f.game_id = ?" + envFilter(environment)
                + " AND f.cohort_date >= ? AND dateDiff('day', f.cohort_date, e.event_date) BETWEEN 1 AND 30 "
                + "GROUP BY cohort ORDER BY cohort";
        List<Map<String, Object>> retainedRows = environment == null || environment.isBlank()
                ? client.query(retainedSql, gameId, since)
                : client.query(retainedSql, gameId, environment, since);

        List<Map<String, Object>> points =
                PaymentFunnelAssembler.toCohortPoints(funnelRows, retainedRows, matureCutoff);
        resp.put("steps", PaymentFunnelAssembler.stepMetadata());
        resp.put("funnel", PaymentFunnelAssembler.toFunnel(points));
        resp.put("points", points);
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
