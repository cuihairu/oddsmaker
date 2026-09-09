package io.oddsmaker.control.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 实时在线指标行映射（纯逻辑，便于脱离 ClickHouse 单测）。
 * 输入为 events 表近窗聚合行；输出在线总数、维度分组与分钟趋势的 camelCase 结构。
 */
public final class OnlineMetricsAssembler {

    private OnlineMetricsAssembler() {
    }

    /** 维度分组：行 (dim, online) → [{key, online}]（按 online 降序，空值归 unknown） */
    public static List<Map<String, Object>> toDimensionBreakdown(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String key = RiskMetricsAssembler.normalizeKey(row.get("dim"));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", key == null ? "unknown" : key);
            m.put("online", RiskMetricsAssembler.asLong(row.get("online")));
            out.add(m);
        }
        out.sort((a, b) -> Long.compare(
                RiskMetricsAssembler.asLong(b.get("online")), RiskMetricsAssembler.asLong(a.get("online"))));
        return out;
    }

    /** 分钟趋势：行 (bucket, online) → [{ts, online}]（按时间升序） */
    public static List<Map<String, Object>> toTrendPoints(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> byBucket = new TreeMap<>();
        for (Map<String, Object> row : rows) {
            String bucket = RiskMetricsAssembler.toIso(row.get("bucket"));
            byBucket.computeIfAbsent(bucket, b -> {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("ts", b);
                p.put("online", 0L);
                return p;
            });
            Map<String, Object> point = byBucket.get(bucket);
            point.put("online", RiskMetricsAssembler.asLong(point.get("online"))
                    + RiskMetricsAssembler.asLong(row.get("online")));
        }
        return new ArrayList<>(byBucket.values());
    }

    /** 单行在线总数 */
    public static long toTotal(List<Map<String, Object>> rows) {
        return rows.isEmpty() ? 0L : RiskMetricsAssembler.asLong(rows.get(0).get("online"));
    }
}
