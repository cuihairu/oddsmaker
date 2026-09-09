package io.oddsmaker.control.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * pLTV（预测 LTV）行映射与乘数拟合（纯逻辑，便于脱离 ClickHouse 单测）。
 * 模型：D7→D30 乘数法——用成熟 cohort（注册满 30 天）的 累计D30收入/累计D7收入 均值作为乘数，
 * 对未成熟 cohort 用已观测的 D7 ARPU 外推 D30 ARPU。
 */
public final class LtvForecastAssembler {

    /** 成熟阈值：注册满 30 天的 cohort 才有完整 D30 观测 */
    public static final int MATURE_DAYS = 30;

    private LtvForecastAssembler() {
    }

    /**
     * 拟合乘数并输出各 cohort 的 pLTV。
     *
     * @param ltvRows      行 (cohort, age_day, revenue)：v_ltv_by_cohort_day 聚合
     * @param cohortRows   行 (cohort, cohort_size)：v_user_first_seen 聚合
     * @param today        当前日期（判定成熟）
     * @return {multiplier, basedOnCohorts, points:[{cohort, cohortSize, ageDays, obsD7Arpu,
     *         obsD30Arpu(成熟才有), predictedD30Arpu, mature}]}
     */
    public static Map<String, Object> forecast(List<Map<String, Object>> ltvRows,
                                               List<Map<String, Object>> cohortRows,
                                               String today) {
        Map<String, TreeMap<Integer, Double>> cumByCohort = cumulate(ltvRows);
        Map<String, Long> sizes = new LinkedHashMap<>();
        for (Map<String, Object> row : cohortRows) {
            String cohort = RetentionMetricsAssembler.asDate(row.get("cohort"));
            if (!cohort.isEmpty()) {
                sizes.put(cohort, RiskMetricsAssembler.asLong(row.get("cohort_size")));
            }
        }

        // 成熟 cohort 拟合乘数
        String matureCutoff = minusDays(today, MATURE_DAYS);
        List<Double> ratios = new ArrayList<>();
        for (Map.Entry<String, TreeMap<Integer, Double>> entry : cumByCohort.entrySet()) {
            if (entry.getKey().compareTo(matureCutoff) > 0) {
                continue;
            }
            double d7 = cumThrough(entry.getValue(), 6);
            double d30 = cumThrough(entry.getValue(), 29);
            if (d7 > 0 && d30 > 0) {
                ratios.add(d30 / d7);
            }
        }
        double multiplier = ratios.isEmpty() ? 0.0
                : ratios.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

        List<Map<String, Object>> points = new ArrayList<>();
        for (Map.Entry<String, Long> entry : sizes.entrySet()) {
            String cohort = entry.getKey();
            long size = entry.getValue();
            if (size <= 0) {
                continue;
            }
            TreeMap<Integer, Double> cum = cumByCohort.getOrDefault(cohort, new TreeMap<>());
            int age = ageDays(cohort, today);
            double d7 = cumThrough(cum, 6);
            double d30 = cumThrough(cum, 29);
            boolean mature = cohort.compareTo(matureCutoff) <= 0;
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("cohort", cohort);
            p.put("cohortSize", size);
            p.put("ageDays", age);
            p.put("obsD7Arpu", round2(d7 / size));
            if (mature) {
                p.put("obsD30Arpu", round2(d30 / size));
            }
            // 未成熟（且已有 ≥7 天观测）用乘数外推；成熟 cohort 预测值即观测值
            p.put("predictedD30Arpu", round2(mature ? d30 / size : d7 / size * multiplier));
            p.put("mature", mature);
            points.add(p);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("multiplier", round2(multiplier));
        out.put("basedOnCohorts", ratios.size());
        out.put("matureDays", MATURE_DAYS);
        out.put("points", points);
        return out;
    }

    /** 按 cohort 累计收入：cohort → (age_day → 截至该天的累计收入) */
    static Map<String, TreeMap<Integer, Double>> cumulate(List<Map<String, Object>> ltvRows) {
        Map<String, TreeMap<Integer, Double>> daily = new LinkedHashMap<>();
        for (Map<String, Object> row : ltvRows) {
            String cohort = RetentionMetricsAssembler.asDate(row.get("cohort"));
            int ageDay = (int) RiskMetricsAssembler.asLong(row.get("age_day"));
            double revenue = RiskMetricsAssembler.asDouble(row.get("revenue"));
            if (cohort.isEmpty() || ageDay < 0) {
                continue;
            }
            daily.computeIfAbsent(cohort, c -> new TreeMap<>())
                    .merge(ageDay, revenue, Double::sum);
        }
        Map<String, TreeMap<Integer, Double>> cum = new LinkedHashMap<>();
        for (Map.Entry<String, TreeMap<Integer, Double>> entry : daily.entrySet()) {
            TreeMap<Integer, Double> running = new TreeMap<>();
            double total = 0;
            for (Map.Entry<Integer, Double> day : entry.getValue().entrySet()) {
                total += day.getValue();
                running.put(day.getKey(), total);
            }
            cum.put(entry.getKey(), running);
        }
        return cum;
    }

    /** 截至第 capDay 天（含）的累计收入 */
    static double cumThrough(TreeMap<Integer, Double> cum, int capDay) {
        Map.Entry<Integer, Double> floor = cum.floorEntry(capDay);
        return floor == null ? 0.0 : floor.getValue();
    }

    static int ageDays(String cohort, String today) {
        try {
            return (int) java.time.temporal.ChronoUnit.DAYS.between(
                    java.time.LocalDate.parse(cohort), java.time.LocalDate.parse(today));
        } catch (Exception e) {
            return 0;
        }
    }

    static String minusDays(String date, int days) {
        return java.time.LocalDate.parse(date).minusDays(days).toString();
    }

    static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
