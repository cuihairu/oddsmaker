package io.oddsmaker.control.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 主体风险评分模型（纯逻辑，便于单测）：聚合 risk_events 命中的
 * 严重度加权次数归一为 0-1 模型分，作为规则分（risk_scores）之外的模型化补充。
 */
public final class RiskScorer {

    /** 满分对应加权命中量：10 次 CRITICAL 或等价组合 */
    public static final double FULL_SCALE_WEIGHTED = 40.0;

    private RiskScorer() {
    }

    public record Scored(double score, String level, List<String> reasons) {}

    public static Scored score(long critical, long high, long medium, long low) {
        List<String> reasons = new ArrayList<>();
        double weighted = critical * 4 + high * 3 + medium * 2 + low * 1;
        if (critical > 0) {
            reasons.add("CRITICAL 命中 " + critical + " 次");
        }
        if (high > 0) {
            reasons.add("HIGH 命中 " + high + " 次");
        }
        if (medium > 0) {
            reasons.add("MEDIUM 命中 " + medium + " 次");
        }
        if (low > 0) {
            reasons.add("LOW 命中 " + low + " 次");
        }
        double score = Math.min(weighted / FULL_SCALE_WEIGHTED, 1.0);
        String level = score >= 0.7 ? "high" : score >= 0.2 ? "medium" : "low";
        return new Scored(RetentionMetricsAssembler.round4(score), level, reasons);
    }
}
