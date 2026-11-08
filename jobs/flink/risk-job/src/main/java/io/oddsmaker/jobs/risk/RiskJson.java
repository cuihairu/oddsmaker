package io.oddsmaker.jobs.risk;

import java.util.Map;

/**
 * RiskJob 序列化辅助：RiskHit → risk_events 主题 JSON 契约（toJson）与 JSON 字面量转义。
 * 原内联于 RiskJob（拆分 LAY-001）。toJson 供 Kafka sink 与 §5.4 端到端共用。
 */
final class RiskJson {

    private RiskJson() {}

    /** JSON 字符串字面量转义（ruleId/reason/evidence 值防御性转义）。 */
    static String jsonEscape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        // 其余控制字符（0x00-0x1F 未列出的）用 unicode-escape 转义
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    static String toJson(RiskJob.RiskHit h) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"game_id\":\"").append(h.gameId).append("\"");
        sb.append(",\"environment\":\"").append(h.environment).append("\"");
        sb.append(",\"ts\":").append(h.ts.getTime());
        sb.append(",\"risk_event_id\":\"").append(h.riskEventId).append("\"");
        sb.append(",\"source_event_id\":\"").append(h.sourceEventId).append("\"");
        sb.append(",\"rule_id\":\"").append(h.ruleId).append("\"");
        sb.append(",\"risk_type\":\"").append(h.riskType).append("\"");
        sb.append(",\"severity\":\"").append(h.severity).append("\"");
        sb.append(",\"subject_type\":\"").append(h.subjectType).append("\"");
        sb.append(",\"subject_id\":\"").append(h.subjectId).append("\"");
        sb.append(",\"score\":").append(h.score);
        sb.append(",\"action\":\"").append(h.action).append("\"");
        sb.append(",\"trust_level\":\"").append(h.trustLevel == null ? "" : h.trustLevel).append("\"");
        sb.append(",\"reason\":\"").append(jsonEscape(h.reason)).append("\"");
        sb.append(",\"evidence\":{");
        boolean first = true;
        for (Map.Entry<String, String> e : h.evidence.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(jsonEscape(e.getKey())).append("\":\"")
              .append(jsonEscape(e.getValue())).append("\"");
            first = false;
        }
        sb.append("}}");
        return sb.toString();
    }
}
