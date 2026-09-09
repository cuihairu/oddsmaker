package io.oddsmaker.control.service;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * pLTV 预测（ClickHouse 数据源：v_ltv_by_cohort_day + v_user_first_seen）。
 * D7→D30 乘数法：成熟 cohort 拟合乘数，未成熟 cohort 以已观测 D7 ARPU 外推 D30。
 * ClickHouse 未配置时统一返回 available=false 的空数据。
 */
@Service
public class LtvForecastService {

    private static final int DEFAULT_DAYS = 90;
    private static final int MAX_DAYS = 730;

    private final ClickHouseClient client;

    public LtvForecastService(ClickHouseClient client) {
        this.client = client;
    }

    public Map<String, Object> pltv(String gameId, String environment, Integer days) {
        int d = clampDays(days);
        Map<String, Object> resp = base(gameId, environment, d);
        if (!client.isAvailable()) {
            resp.put("available", false);
            return resp;
        }
        LocalDate since = LocalDate.now().minusDays(d);

        String sizeSql = "SELECT cohort_date AS cohort, uniqExact(user_id) AS cohort_size "
                + "FROM v_user_first_seen WHERE game_id = ?" + envFilter(environment)
                + " AND cohort_date >= ? GROUP BY cohort";
        List<Map<String, Object>> cohortRows = environment == null || environment.isBlank()
                ? client.query(sizeSql, gameId, since)
                : client.query(sizeSql, gameId, environment, since);

        String ltvSql = "SELECT cohort_date AS cohort, age_day AS age_day, sum(revenue) AS revenue "
                + "FROM v_ltv_by_cohort_day WHERE game_id = ?" + envFilter(environment)
                + " AND cohort_date >= ? GROUP BY cohort, age_day";
        List<Map<String, Object>> ltvRows = environment == null || environment.isBlank()
                ? client.query(ltvSql, gameId, since)
                : client.query(ltvSql, gameId, environment, since);

        resp.putAll(LtvForecastAssembler.forecast(ltvRows, cohortRows, LocalDate.now().toString()));
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
