package io.oddsmaker.control.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Crash/Error 指标行映射（纯逻辑，便于脱离 ClickHouse 单测）。
 * 输入为 v_crash_top_groups / v_crash_by_day / v_crash_rate_by_version 聚合行，
 * 输出 camelCase 结构供大屏与列表展示。
 */
public final class CrashMetricsAssembler {

    private CrashMetricsAssembler() {
    }

    /** Top 崩溃分组：行 (crash_group, sample_message, occurrences, affected_devices, first_seen, last_seen) */
    public static List<Map<String, Object>> toTopGroups(List<Map<String, Object>> rows, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            String group = RiskMetricsAssembler.asString(row.get("crash_group"));
            m.put("crashGroup", group.isEmpty() ? "unknown" : group);
            m.put("sampleMessage", RiskMetricsAssembler.asString(row.get("sample_message")));
            m.put("occurrences", RiskMetricsAssembler.asLong(row.get("occurrences")));
            m.put("affectedDevices", RiskMetricsAssembler.asLong(row.get("affected_devices")));
            m.put("firstSeen", RetentionMetricsAssembler.asDate(row.get("first_seen")));
            m.put("lastSeen", RetentionMetricsAssembler.asDate(row.get("last_seen")));
            out.add(m);
        }
        out.sort((a, b) -> Long.compare(
                RiskMetricsAssembler.asLong(b.get("occurrences")), RiskMetricsAssembler.asLong(a.get("occurrences"))));
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    /** 崩溃趋势：行 (bucket, crashes, affected_devices) → [{date, crashes, affectedDevices}]（升序） */
    public static List<Map<String, Object>> toTrend(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> byDate = new TreeMap<>();
        for (Map<String, Object> row : rows) {
            String date = RetentionMetricsAssembler.asDate(row.get("bucket"));
            Map<String, Object> point = byDate.computeIfAbsent(date, d -> {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("date", d);
                p.put("crashes", 0L);
                p.put("affectedDevices", 0L);
                return p;
            });
            point.put("crashes", Math.max(RiskMetricsAssembler.asLong(point.get("crashes")),
                    RiskMetricsAssembler.asLong(row.get("crashes"))));
            point.put("affectedDevices", Math.max(RiskMetricsAssembler.asLong(point.get("affectedDevices")),
                    RiskMetricsAssembler.asLong(row.get("affected_devices"))));
        }
        return new ArrayList<>(byDate.values());
    }

    /** 版本崩溃率：行 (app_version, event_date, crash_devices, active_devices, crash_rate)
     * → 按版本聚合 [{appVersion, lastDate, crashDevices, activeDevices, crashRate}]（最近一天的口径，崩溃率取窗口均值） */
    public static List<Map<String, Object>> toVersionRates(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> byVersion = new LinkedHashMap<>();
        Map<String, Double> rateSum = new LinkedHashMap<>();
        Map<String, Integer> rateCount = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String version = RiskMetricsAssembler.normalizeKey(row.get("app_version"));
            String key = version == null ? "unknown" : version;
            String date = RetentionMetricsAssembler.asDate(row.get("event_date"));
            Map<String, Object> v = byVersion.computeIfAbsent(key, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("appVersion", k);
                m.put("lastDate", "");
                m.put("crashDevices", 0L);
                m.put("activeDevices", 0L);
                return m;
            });
            if (date.compareTo(String.valueOf(v.get("lastDate"))) > 0) {
                v.put("lastDate", date);
                v.put("crashDevices", RiskMetricsAssembler.asLong(row.get("crash_devices")));
                v.put("activeDevices", RiskMetricsAssembler.asLong(row.get("active_devices")));
            }
            rateSum.merge(key, RiskMetricsAssembler.asDouble(row.get("crash_rate")), Double::sum);
            rateCount.merge(key, 1, Integer::sum);
        }
        for (Map.Entry<String, Map<String, Object>> entry : byVersion.entrySet()) {
            double avg = rateCount.getOrDefault(entry.getKey(), 0) > 0
                    ? rateSum.get(entry.getKey()) / rateCount.get(entry.getKey())
                    : 0.0;
            entry.getValue().put("avgCrashRate", RetentionMetricsAssembler.round4(avg));
        }
        return new ArrayList<>(byVersion.values());
    }
}
