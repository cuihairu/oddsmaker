package io.oddsmaker.control.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 财务报表行映射与 CSV 生成（纯逻辑，便于脱离 ClickHouse 单测）。
 * 输入为 events 活跃/收入聚合行 (stat_date, dau, revenue, payers, orders)
 * 与 v_user_first_seen 新增行 (stat_date, new_users)；输出合并指标行与 CSV 文本。
 */
public final class FinanceMetricsAssembler {

    private static final List<String> CSV_HEADER = List.of(
            "stat_date", "new_users", "dau", "revenue", "payers", "orders",
            "arpu", "arppu", "payment_rate");

    private FinanceMetricsAssembler() {
    }

    /**
     * 按统计周期合并：新增行与活跃/收入行对齐 →
     * [{statDate, newUsers, dau, revenue, payers, orders, arpu, arppu, paymentRate}]（升序）。
     * ARPU = 收入/DAU；ARPPU = 收入/付费主体数；付费率 = 付费主体数/DAU。
     */
    public static List<Map<String, Object>> toRows(List<Map<String, Object>> activityRows,
                                                   List<Map<String, Object>> newUserRows) {
        Map<String, Map<String, Object>> byDate = new TreeMap<>();
        for (Map<String, Object> row : activityRows) {
            String date = RetentionMetricsAssembler.asDate(row.get("stat_date"));
            if (date.isEmpty()) {
                continue;
            }
            Map<String, Object> r = byDate.computeIfAbsent(date, newRow);
            r.put("dau", RiskMetricsAssembler.asLong(row.get("dau")));
            r.put("revenue", round2(RiskMetricsAssembler.asDouble(row.get("revenue"))));
            r.put("payers", RiskMetricsAssembler.asLong(row.get("payers")));
            r.put("orders", RiskMetricsAssembler.asLong(row.get("orders")));
        }
        for (Map<String, Object> row : newUserRows) {
            String date = RetentionMetricsAssembler.asDate(row.get("stat_date"));
            if (date.isEmpty()) {
                continue;
            }
            byDate.computeIfAbsent(date, newRow).put("newUsers", RiskMetricsAssembler.asLong(row.get("new_users")));
        }
        for (Map<String, Object> r : byDate.values()) {
            long dau = RiskMetricsAssembler.asLong(r.get("dau"));
            long payers = RiskMetricsAssembler.asLong(r.get("payers"));
            double revenue = RiskMetricsAssembler.asDouble(r.get("revenue"));
            r.put("arpu", round2(dau > 0 ? revenue / dau : 0.0));
            r.put("arppu", round2(payers > 0 ? revenue / payers : 0.0));
            r.put("paymentRate", dau > 0 ? RetentionMetricsAssembler.round4(payers / (double) dau) : 0.0);
        }
        return new ArrayList<>(byDate.values());
    }

    /** 窗口汇总：累计新增/收入/订单 + 加权 ARPU 与整体付费率 */
    public static Map<String, Object> toSummary(List<Map<String, Object>> rows) {
        long newUsers = 0, dauSum = 0, payersSum = 0, orders = 0;
        double revenue = 0;
        int periods = 0;
        for (Map<String, Object> r : rows) {
            newUsers += RiskMetricsAssembler.asLong(r.get("newUsers"));
            dauSum += RiskMetricsAssembler.asLong(r.get("dau"));
            payersSum += RiskMetricsAssembler.asLong(r.get("payers"));
            orders += RiskMetricsAssembler.asLong(r.get("orders"));
            revenue += RiskMetricsAssembler.asDouble(r.get("revenue"));
            periods++;
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("periods", periods);
        summary.put("totalNewUsers", newUsers);
        summary.put("avgDau", periods > 0 ? dauSum / (double) periods : 0.0);
        summary.put("totalRevenue", round2(revenue));
        summary.put("totalOrders", orders);
        summary.put("arpu", dauSum > 0 ? round2(revenue / dauSum) : 0.0);
        summary.put("arppu", payersSum > 0 ? round2(revenue / payersSum) : 0.0);
        summary.put("paymentRate", dauSum > 0 ? RetentionMetricsAssembler.round4(payersSum / (double) dauSum) : 0.0);
        return summary;
    }

    /** RFC 4180 CSV：表头 + 数据行（逗号/引号/换行转义） */
    public static String toCsv(String gameId, String granularity, List<Map<String, Object>> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(",", CSV_HEADER)).append('\n');
        for (Map<String, Object> r : rows) {
            List<String> cells = List.of(
                    csvCell(r.get("statDate")),
                    csvCell(r.get("newUsers")),
                    csvCell(r.get("dau")),
                    csvCell(r.get("revenue")),
                    csvCell(r.get("payers")),
                    csvCell(r.get("orders")),
                    csvCell(r.get("arpu")),
                    csvCell(r.get("arppu")),
                    csvCell(r.get("paymentRate")));
            sb.append(String.join(",", cells)).append('\n');
        }
        return sb.toString();
    }

    private static final java.util.function.Function<String, Map<String, Object>> newRow = date -> {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("statDate", date);
        r.put("newUsers", 0L);
        r.put("dau", 0L);
        r.put("revenue", 0.0);
        r.put("payers", 0L);
        r.put("orders", 0L);
        return r;
    };

    static String csvCell(Object value) {
        if (value == null) {
            return "";
        }
        String s = String.valueOf(value);
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }

    static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
