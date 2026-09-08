package io.oddsmaker.control.service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 风控大屏指标行映射（纯逻辑，便于脱离 ClickHouse 单测）。
 * 输入为 JdbcTemplate#queryForList 的行（LinkedCaseInsensitiveMap，键为 SQL 别名），
 * 输出为直接面向大屏的 camelCase 结构。
 */
public final class RiskMetricsAssembler {

    private static final List<String> SEVERITIES = List.of("critical", "high", "medium", "low");

    private RiskMetricsAssembler() {
    }

    /** 时间桶函数白名单：短窗按小时、长窗按天 */
    public static String bucketFunction(int hours) {
        return hours <= 48 ? "toStartOfHour" : "toStartOfDay";
    }

    /**
     * 趋势透视：行 (bucket, severity, c) → 按桶聚合的
     * [{ts, total, critical, high, medium, low}]，缺失严重等级补 0，桶按时间升序。
     */
    public static List<Map<String, Object>> pivotTrend(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> byBucket = new TreeMap<>();
        for (Map<String, Object> row : rows) {
            String bucket = toIso(row.get("bucket"));
            Map<String, Object> point = byBucket.computeIfAbsent(bucket, b -> {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("ts", b);
                p.put("total", 0L);
                for (String sev : SEVERITIES) {
                    p.put(sev, 0L);
                }
                return p;
            });
            long c = asLong(row.get("c"));
            point.put("total", asLong(point.get("total")) + c);
            String severity = normalizeKey(row.get("severity"));
            if (severity != null && point.containsKey(severity)) {
                point.put(severity, asLong(point.get(severity)) + c);
            }
        }
        return new ArrayList<>(byBucket.values());
    }

    /** 规则命中：行 (rule_id, risk_type, hits, subjects, avg_score, last_hit_at) */
    public static List<Map<String, Object>> toRuleHits(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            String ruleId = asString(row.get("rule_id"));
            m.put("ruleId", ruleId.isEmpty() ? "unknown" : ruleId);
            m.put("riskType", asString(row.get("risk_type")));
            m.put("hits", asLong(row.get("hits")));
            m.put("subjects", asLong(row.get("subjects")));
            m.put("avgScore", asDouble(row.get("avg_score")));
            m.put("lastHitAt", toIso(row.get("last_hit_at")));
            out.add(m);
        }
        return out;
    }

    /** 等级/类型分布：行 (dimension, c) → [{key, count}]（按 count 降序） */
    public static List<Map<String, Object>> toBreakdown(List<Map<String, Object>> rows, String keyField) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            String key = normalizeKey(row.get(keyField));
            m.put(keyField, key == null ? "unknown" : key);
            m.put("count", asLong(row.get("c")));
            out.add(m);
        }
        out.sort((a, b) -> Long.compare(asLong(b.get("count")), asLong(a.get("count"))));
        return out;
    }

    /** 处置动作明细：行 (ts, risk_event_id, rule_id, action, state, subject_type, subject_id, severity) */
    public static List<Map<String, Object>> toRecentActions(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ts", toIso(row.get("ts")));
            m.put("riskEventId", asString(row.get("risk_event_id")));
            m.put("riskCaseId", asString(row.get("risk_case_id")));
            m.put("ruleId", asString(row.get("rule_id")));
            m.put("action", asString(row.get("action")));
            m.put("state", asString(row.get("state")));
            m.put("subjectType", asString(row.get("subject_type")));
            m.put("subjectId", asString(row.get("subject_id")));
            m.put("severity", asString(row.get("severity")));
            out.add(m);
        }
        return out;
    }

    public static long sumCounts(List<Map<String, Object>> breakdown) {
        long total = 0;
        for (Map<String, Object> item : breakdown) {
            total += asLong(item.get("count"));
        }
        return total;
    }

    // ===== 类型归一 =====

    static long asLong(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    static double asDouble(Object v) {
        return v instanceof Number n ? n.doubleValue() : 0.0;
    }

    static String asString(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    static String normalizeKey(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim().toLowerCase();
        return s.isEmpty() ? null : s;
    }

    static String toIso(Object v) {
        if (v instanceof Timestamp t) {
            return t.toInstant().toString();
        }
        if (v instanceof LocalDateTime ldt) {
            return ldt.toInstant(ZoneOffset.UTC).toString();
        }
        if (v instanceof OffsetDateTime odt) {
            return odt.toInstant().toString();
        }
        if (v instanceof java.util.Date d) {
            return d.toInstant().toString();
        }
        return v == null ? "" : String.valueOf(v);
    }
}
