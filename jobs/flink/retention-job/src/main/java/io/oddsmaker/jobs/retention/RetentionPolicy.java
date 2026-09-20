package io.oddsmaker.jobs.retention;

import java.util.ArrayList;
import java.util.List;

/**
 * 留存判定策略（纯逻辑，可单测）。
 *
 * - N-Day 留存：恰好第 N 天（cohort 日 + N）活跃才算，业界经典口径；
 * - Rolling 留存：首日之后第 N 天及以后任意一天活跃即算（无界口径），
 *   当用户最后一次活跃日推过 N 阈值时补记，后续不重复计数。
 */
public final class RetentionPolicy implements java.io.Serializable {

    private final int[] nDays;
    private final int[] rollingDays;

    public RetentionPolicy(int[] nDays, int[] rollingDays) {
        this.nDays = sortedPositive(nDays);
        this.rollingDays = sortedPositive(rollingDays);
    }

    /** 默认策略：N-Day {1,7,30}，Rolling {1,3,7,14,30} */
    /** 从 "1,7,30" 逗号串解析 */
    public static int[] parseDays(String csv) {
        if (csv == null || csv.isBlank()) {
            return new int[0];
        }
        List<Integer> out = new ArrayList<>();
        for (String part : csv.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            try {
                int v = Integer.parseInt(trimmed);
                if (v > 0) out.add(v);
            } catch (NumberFormatException ignored) {
            }
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    public int[] nDays() { return nDays.clone(); }

    public int[] rollingDays() { return rollingDays.clone(); }

    /**
     * N-Day 命中：activeDay 相对 cohortDay 的偏移恰等于某个配置 N 时返回该 N。
     *
     * @return 命中的 N；未命中返回 -1
     */
    public int nDayHit(long cohortDay, long activeDay) {
        int offset = (int) (activeDay - cohortDay);
        for (int n : nDays) {
            if (n == offset) return n;
        }
        return -1;
    }

    /**
     * Rolling 补记：用户最后活跃日从 prevLastDay 推进到 newLastDay 时，
     * 返回本次新跨越的 N 阈值列表（prev < N <= new，相对 cohortDay）。
     */
    public List<Integer> rollingCrossed(long cohortDay, long prevLastDay, long newLastDay) {
        List<Integer> out = new ArrayList<>();
        for (int n : rollingDays) {
            long prevOffset = prevLastDay - cohortDay;
            long newOffset = newLastDay - cohortDay;
            if (newOffset >= n && prevOffset < n) {
                out.add(n);
            }
        }
        return out;
    }

    private static int[] sortedPositive(int[] days) {
        List<Integer> out = new ArrayList<>();
        for (int d : days) {
            if (d > 0) out.add(d);
        }
        return out.stream().distinct().sorted().mapToInt(Integer::intValue).toArray();
    }
}
