package io.oddsmaker.control.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 付费漏斗行映射（纯逻辑，便于脱离 ClickHouse 单测）。
 * 输入为按 cohort 的注册行 (cohort, registered, first_pay, second_pay)
 * 与月留存行 (cohort, retained_30)；输出合并趋势与总体漏斗（含逐步转化率）。
 */
public final class PaymentFunnelAssembler {

    private static final List<String> STEPS = List.of("registered", "firstPay", "secondPay", "retained30");
    private static final List<String> STEP_LABELS = List.of("注册", "首充", "二充", "月留存");

    private PaymentFunnelAssembler() {
    }

    /**
     * 漏斗步骤定义（camelCase key 与显示名）。
     */
    public static List<Map<String, Object>> stepMetadata() {
        List<Map<String, Object>> steps = new ArrayList<>();
        for (int i = 0; i < STEPS.size(); i++) {
            steps.add(Map.of("key", STEPS.get(i), "label", STEP_LABELS.get(i)));
        }
        return steps;
    }

    /**
     * cohort 合并：注册/付费行与月留存行按 cohort 对齐 →
     * [{cohort, registered, firstPay, secondPay, retained30, firstPayRate, secondPayRate, retained30Rate, mature}]
     */
    public static List<Map<String, Object>> toCohortPoints(List<Map<String, Object>> funnelRows,
                                                           List<Map<String, Object>> retainedRows,
                                                           String matureCutoff) {
        Map<String, Map<String, Object>> byCohort = new TreeMap<>();
        for (Map<String, Object> row : funnelRows) {
            String cohort = RetentionMetricsAssembler.asDate(row.get("cohort"));
            if (cohort.isEmpty()) {
                continue;
            }
            Map<String, Object> point = byCohort.computeIfAbsent(cohort, newPoint);
            point.put("registered", RiskMetricsAssembler.asLong(row.get("registered")));
            point.put("firstPay", RiskMetricsAssembler.asLong(row.get("first_pay")));
            point.put("secondPay", RiskMetricsAssembler.asLong(row.get("second_pay")));
        }
        for (Map<String, Object> row : retainedRows) {
            String cohort = RetentionMetricsAssembler.asDate(row.get("cohort"));
            if (cohort.isEmpty()) {
                continue;
            }
            byCohort.computeIfAbsent(cohort, newPoint)
                    .put("retained30", RiskMetricsAssembler.asLong(row.get("retained_30")));
        }
        for (Map<String, Object> point : byCohort.values()) {
            long registered = RiskMetricsAssembler.asLong(point.get("registered"));
            point.put("firstPayRate", rate(point.get("firstPay"), registered));
            point.put("secondPayRate", rate(point.get("secondPay"), registered));
            point.put("retained30Rate", rate(point.get("retained30"), registered));
            point.put("mature", String.valueOf(point.get("cohort")).compareTo(matureCutoff) <= 0);
        }
        return new ArrayList<>(byCohort.values());
    }

    /** 总体漏斗：窗口内合计（月留存仅成熟 cohort 计入分母），含逐步与整体转化率 */
    public static Map<String, Object> toFunnel(List<Map<String, Object>> points) {
        long registered = 0, firstPay = 0, secondPay = 0, retained30 = 0, matureRegistered = 0;
        for (Map<String, Object> point : points) {
            registered += RiskMetricsAssembler.asLong(point.get("registered"));
            firstPay += RiskMetricsAssembler.asLong(point.get("firstPay"));
            secondPay += RiskMetricsAssembler.asLong(point.get("secondPay"));
            if (Boolean.TRUE.equals(point.get("mature"))) {
                retained30 += RiskMetricsAssembler.asLong(point.get("retained30"));
                matureRegistered += RiskMetricsAssembler.asLong(point.get("registered"));
            }
        }
        Map<String, Object> funnel = new LinkedHashMap<>();
        funnel.put("registered", registered);
        funnel.put("firstPay", firstPay);
        funnel.put("secondPay", secondPay);
        funnel.put("retained30", retained30);
        funnel.put("retained30Base", matureRegistered);
        funnel.put("firstPayRate", rate(firstPay, registered));
        funnel.put("secondPayRate", rate(secondPay, registered));
        funnel.put("retained30Rate", rate(retained30, matureRegistered));
        funnel.put("firstToSecondRate", rate(secondPay, firstPay));
        return funnel;
    }

    private static final java.util.function.Function<String, Map<String, Object>> newPoint = cohort -> {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("cohort", cohort);
        p.put("registered", 0L);
        p.put("firstPay", 0L);
        p.put("secondPay", 0L);
        p.put("retained30", 0L);
        return p;
    };

    static double rate(Object numerator, long denominator) {
        if (denominator <= 0) {
            return 0.0;
        }
        return RetentionMetricsAssembler.round4(RiskMetricsAssembler.asLong(numerator) / (double) denominator);
    }
}
