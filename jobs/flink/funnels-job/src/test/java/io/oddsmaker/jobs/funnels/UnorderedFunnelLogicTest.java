package io.oddsmaker.jobs.funnels;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 无序漏斗判定测试。
 */
@DisplayName("无序漏斗判定测试")
class UnorderedFunnelLogicTest {

    private static Map<Integer, Long> steps(long... tsPerStep) {
        Map<Integer, Long> m = new HashMap<>();
        for (int i = 0; i < tsPerStep.length; i++) {
            if (tsPerStep[i] >= 0) m.put(i, tsPerStep[i]);  // -1 表示该步未完成
        }
        return m;
    }

    @Test
    @DisplayName("全部步骤完成后判定转化（任意顺序录入）")
    void allStepsDoneInAnyOrder() {
        Map<Integer, Long> state = new HashMap<>();
        state.put(2, 3_000L);   // 最后一步先来
        assertFalse(UnorderedFunnelLogic.allStepsDone(3, state));
        state.put(0, 1_000L);
        assertFalse(UnorderedFunnelLogic.allStepsDone(3, state));
        state.put(1, 2_000L);
        assertTrue(UnorderedFunnelLogic.allStepsDone(3, state));
        assertFalse(UnorderedFunnelLogic.allStepsDone(0, state));   // 无步骤恒未完成
    }

    @Test
    @DisplayName("窗口内完成判转化")
    void convertedWithinWindow() {
        Map<Integer, Long> state = steps(0L, 500L, 3_600_000L);  // 跨度 1 小时
        long window = 2 * 3_600_000L;
        assertTrue(UnorderedFunnelLogic.converted(3, state, window));
    }

    @Test
    @DisplayName("跨度超窗不判转化")
    void notConvertedBeyondWindow() {
        Map<Integer, Long> state = steps(0L, 500L, 3_600_000L);
        long window = 1_800_000L;  // 30 分钟
        assertFalse(UnorderedFunnelLogic.converted(3, state, window));
    }

    @Test
    @DisplayName("缺步不判转化")
    void notConvertedWithMissingStep() {
        Map<Integer, Long> state = steps(0L, -1L, 3_600_000L);
        assertFalse(UnorderedFunnelLogic.converted(3, state, 10 * 3_600_000L));
    }

    @Test
    @DisplayName("跨度计算：最晚减最早（与步骤顺序无关）")
    void spanIsMaxMinusMin() {
        assertEquals(3_600_000L, UnorderedFunnelLogic.spanMs(steps(3_600_000L, 0L, 1_800_000L)));
        assertEquals(0L, UnorderedFunnelLogic.spanMs(steps(5_000L, 5_000L, 5_000L)));
        assertEquals(0L, UnorderedFunnelLogic.spanMs(new HashMap<>()));
    }

    @Test
    @DisplayName("边界：跨度恰好等于窗口判转化（闭区间）")
    void boundaryExactlyAtWindow() {
        Map<Integer, Long> state = steps(0L, 1_800_000L, 3_600_000L);
        assertTrue(UnorderedFunnelLogic.converted(3, state, 3_600_000L));
    }
}
