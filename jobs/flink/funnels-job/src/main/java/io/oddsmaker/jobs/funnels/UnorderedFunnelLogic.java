package io.oddsmaker.jobs.funnels;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * 无序漏斗判定逻辑（纯逻辑，可单测）。
 *
 * 语义：任意顺序完成全部步骤即转化；全部步骤的完成时间跨度必须 ≤ 时间窗，
 * 超窗则重置（当前事件作为新一轮起点）。每用户只计一次转化（converted 去重）。
 * 该逻辑由 Flink MapState 驱动，本类只做无状态的判定与推导。
 */
public final class UnorderedFunnelLogic {

    private UnorderedFunnelLogic() {}

    /**
     * 全部步骤是否已完成。
     *
     * @param stepCount 步骤总数
     * @param stepFirstTs 每步首次完成时间（key 为步骤下标）
     */
    public static boolean allStepsDone(int stepCount, Map<Integer, Long> stepFirstTs) {
        if (stepCount <= 0) return false;
        for (int i = 0; i < stepCount; i++) {
            if (stepFirstTs.get(i) == null) return false;
        }
        return true;
    }

    /**
     * 全部步骤完成时间的跨度（最晚 - 最早），毫秒。
     */
    public static long spanMs(Map<Integer, Long> stepFirstTs) {
        if (stepFirstTs.isEmpty()) return 0L;
        TreeSet<Long> ts = new TreeSet<>(stepFirstTs.values());
        return ts.last() - ts.first();
    }

    /**
     * 判定一次事件后是否达成转化。
     *
     * @return true 表示全部步骤已在窗口内完成
     */
    public static boolean converted(int stepCount, Map<Integer, Long> stepFirstTs, long windowMs) {
        return allStepsDone(stepCount, stepFirstTs) && spanMs(stepFirstTs) <= windowMs;
    }

}
