package io.oddsmaker.control.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 流失风险启发式打分（纯逻辑，便于单测；后续可替换为 ML 模型输出）。
 * 评分维度：不活跃天数（主因子）+ 30 天会话衰减 + 付费用户缓冲。
 */
public final class ChurnScorer {

    public static final int INACTIVITY_THRESHOLD = 14;
    public static final double HIGH = 0.7;
    public static final double MEDIUM = 0.4;

    private ChurnScorer() {
    }

    /** 打分结果：0-1 分 + 分级 + 可解释原因 */
    public record Scored(double score, String level, List<String> reasons) {}

    /**
     * @param daysInactive 距最近活跃天数（30 天窗口内）
     * @param sessions30d  30 天会话数
     * @param revenue30d   30 天累计充值
     */
    public static Scored score(long daysInactive, long sessions30d, double revenue30d) {
        List<String> reasons = new ArrayList<>();
        double inactivity = Math.min(Math.max(daysInactive, 0) / (double) INACTIVITY_THRESHOLD, 1.0);
        double disengagement = 1.0 - Math.min(Math.max(sessions30d, 0) / 30.0, 1.0);
        double score = inactivity * 0.7 + disengagement * 0.3;
        if (revenue30d > 0) {
            score -= 0.1;  // 付费用户流失倾向缓冲
            reasons.add("付费用户权重下调");
        }
        score = Math.max(0.0, Math.min(1.0, score));

        if (daysInactive >= INACTIVITY_THRESHOLD) {
            reasons.add(0, "超过" + INACTIVITY_THRESHOLD + "天未活跃");
        } else if (daysInactive >= 7) {
            reasons.add(0, "连续" + daysInactive + "天未活跃");
        }
        if (sessions30d < 3) {
            reasons.add("30天会话数不足3次");
        }
        String level = score >= HIGH ? "high" : score >= MEDIUM ? "medium" : "low";
        return new Scored(round4(score), level, reasons);
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
