package io.oddsmaker.control.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 留存趋势报表行映射（纯逻辑，便于脱离 ClickHouse 单测）。
 * 输入为 retention_daily 聚合行 (cohort, d, users)，d=0 即该 cohort 新增用户数；
 * 输出按周期对齐的趋势点 [{cohort, newUsers, d1, d1Rate, d7, d7Rate, d30, d30Rate}] 与窗口汇总。
 */
public final class RetentionMetricsAssembler {

    /** 输出趋势曲线的留存档位 */
    private static final List<Integer> RETENTION_DAYS = List.of(1, 7, 30);

    private RetentionMetricsAssembler() {
    }

    /** 粒度白名单：day/week/month → ClickHouse cohort 对齐函数 */
    public static String bucketFunction(String granularity) {
        return switch (granularity == null ? "day" : granularity) {
            case "week" -> "toMonday";
            case "month" -> "toStartOfMonth";
            default -> "";
        };
    }

    /**
     * 趋势透视：行 (cohort, d, users) → 按 cohort 升序的
     * [{cohort, newUsers, d1, d1Rate, d7, d7Rate, d30, d30Rate}]，rate = users_dN / newUsers。
     */
    public static List<Map<String, Object>> toTrendPoints(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> byCohort = new TreeMap<>();
        for (Map<String, Object> row : rows) {
            String cohort = asDate(row.get("cohort"));
            int d = (int) RiskMetricsAssembler.asLong(row.get("d"));
            long users = RiskMetricsAssembler.asLong(row.get("users"));
            Map<String, Object> point = byCohort.computeIfAbsent(cohort, c -> {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("cohort", c);
                p.put("newUsers", 0L);
                for (int n : RETENTION_DAYS) {
                    p.put("d" + n, 0L);
                    p.put("d" + n + "Rate", 0.0);
                }
                return p;
            });
            if (d == 0) {
                point.put("newUsers", RiskMetricsAssembler.asLong(point.get("newUsers")) + users);
            } else if (RETENTION_DAYS.contains(d)) {
                point.put("d" + d, RiskMetricsAssembler.asLong(point.get("d" + d)) + users);
            }
        }
        for (Map<String, Object> point : byCohort.values()) {
            long newUsers = RiskMetricsAssembler.asLong(point.get("newUsers"));
            for (int n : RETENTION_DAYS) {
                double rate = newUsers > 0
                        ? RiskMetricsAssembler.asLong(point.get("d" + n)) / (double) newUsers
                        : 0.0;
                point.put("d" + n + "Rate", round4(rate));
            }
        }
        return new ArrayList<>(byCohort.values());
    }

    /**
     * 窗口汇总：对有新增用户的 cohort 求平均留存率与累计新增
     * （d30 仅对成熟 cohort —— 距今 ≥ matureDays —— 计入，避免低估）。
     */
    public static Map<String, Object> toSummary(List<Map<String, Object>> points, int matureDays) {
        long totalNewUsers = 0;
        double sumD1 = 0, sumD7 = 0, sumD30 = 0;
        int cohortsD1 = 0, cohortsD7 = 0, cohortsD30 = 0;
        String cutoff = LocalDate.now().minusDays(matureDays).toString();
        for (Map<String, Object> point : points) {
            long newUsers = RiskMetricsAssembler.asLong(point.get("newUsers"));
            if (newUsers <= 0) {
                continue;
            }
            totalNewUsers += newUsers;
            sumD1 += RiskMetricsAssembler.asDouble(point.get("d1Rate"));
            cohortsD1++;
            sumD7 += RiskMetricsAssembler.asDouble(point.get("d7Rate"));
            cohortsD7++;
            if (String.valueOf(point.get("cohort")).compareTo(cutoff) <= 0) {
                sumD30 += RiskMetricsAssembler.asDouble(point.get("d30Rate"));
                cohortsD30++;
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalNewUsers", totalNewUsers);
        summary.put("cohorts", cohortsD1);
        summary.put("avgD1Rate", round4(cohortsD1 > 0 ? sumD1 / cohortsD1 : 0.0));
        summary.put("avgD7Rate", round4(cohortsD7 > 0 ? sumD7 / cohortsD7 : 0.0));
        summary.put("avgD30Rate", round4(cohortsD30 > 0 ? sumD30 / cohortsD30 : 0.0));
        summary.put("matureCohortsD30", cohortsD30);
        return summary;
    }

    static String asDate(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof java.sql.Date d) {
            return d.toString();
        }
        if (v instanceof LocalDate d) {
            return d.toString();
        }
        if (v instanceof Timestamp t) {
            return t.toLocalDateTime().toLocalDate().toString();
        }
        if (v instanceof LocalDateTime ldt) {
            return ldt.toLocalDate().toString();
        }
        return String.valueOf(v);
    }

    static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
