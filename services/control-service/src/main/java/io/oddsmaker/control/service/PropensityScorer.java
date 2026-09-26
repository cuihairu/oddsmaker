package io.oddsmaker.control.service;

import java.util.List;

/**
 * 付费倾向启发式打分（纯逻辑，便于单测；propensity 模型产物在场时优先用模型分）。
 * 历史付费为主因子 + 30 天会话正向 + 不活跃天数衰减，分数语义 = 付费概率。
 *
 * <p>公式与 ml/（oddsmaker_ml.models_propensity.heuristic_propensity_scores）
 * 逐字互为镜像，双端 golden 用例锁定同输入同结果：
 * score = 0.12 + 0.55×paid + 0.004×min(sessions,50) − 0.004×min(days_inactive,30)，
 * clamp [0.01, 0.95]。
 */
public final class PropensityScorer {

    public static final double HIGH = 0.5;
    public static final double MEDIUM = 0.25;

    private PropensityScorer() {
    }

    /** 打分结果：0-1 分 + 分级 + 可解释原因 */
    public record Scored(double score, String level, List<String> reasons) {}

    /**
     * @param daysInactive 距最近活跃天数（30 天窗口内）
     * @param sessions30d  30 天会话数
     * @param revenue30d   30 天累计充值（>0 视为历史付费）
     */
    public static Scored score(long daysInactive, long sessions30d, double revenue30d) {
        double d = Math.min(Math.max(daysInactive, 0), 30);
        double s = Math.min(Math.max(sessions30d, 0), 50);
        double score = 0.12 + (revenue30d > 0 ? 0.55 : 0.0) + 0.004 * s - 0.004 * d;
        score = Math.max(0.01, Math.min(0.95, score));

        List<String> reasons;
        if (revenue30d > 0) {
            reasons = List.of("历史有付费");
        } else {
            reasons = List.of("无付费记录");
        }
        String level = score >= HIGH ? "high" : score >= MEDIUM ? "medium" : "low";
        return new Scored(score, level, reasons);
    }
}
