package io.oddsmaker.jobs.retention;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 留存策略测试：N-Day 精确命中 + Rolling 阈值跨越。
 */
@DisplayName("留存策略测试")
class RetentionPolicyTest {

    // 显式默认向量（defaults() 工厂已删）：N={1,7,30}，Rolling={1,3,7,14,30}
    private final RetentionPolicy policy = new RetentionPolicy(new int[]{1, 7, 30}, new int[]{1, 3, 7, 14, 30});

    @Test
    @DisplayName("N-Day：恰好第 N 天命中")
    void nDayExactHit() {
        assertEquals(1, policy.nDayHit(100, 101));
        assertEquals(7, policy.nDayHit(100, 107));
        assertEquals(30, policy.nDayHit(100, 130));
    }

    @Test
    @DisplayName("N-Day：非配置偏移不命中")
    void nDayMiss() {
        assertEquals(-1, policy.nDayHit(100, 102));
        assertEquals(-1, policy.nDayHit(100, 108));
        assertEquals(-1, policy.nDayHit(100, 100));  // D0 由首次事件处理，不走 nDayHit
        assertEquals(-1, policy.nDayHit(100, 99));   // 乱序回退不命中
    }

    @Test
    @DisplayName("Rolling：跨过多个阈值时全部补记")
    void rollingCrossesMultipleThresholds() {
        // 首日 100，之前最后活跃 101（跨过 1），新活跃日 105（再跨 3）
        List<Integer> crossed = policy.rollingCrossed(100, 101, 105);
        assertEquals(List.of(3), crossed);  // 1 在之前已跨过
    }

    @Test
    @DisplayName("Rolling：首次大跨度活跃一次跨过多阈值")
    void rollingFirstBigJump() {
        // 首日 100 → 首日当天活跃（last=100），次日直接跳到 108：跨过 1,3,7
        List<Integer> crossed = policy.rollingCrossed(100, 100, 108);
        assertEquals(List.of(1, 3, 7), crossed);
    }

    @Test
    @DisplayName("Rolling：推进但未跨过新阈值时不补记")
    void rollingNoNewThreshold() {
        // 102→104 跨过 D3（104-100>=3 且 102-100<3）
        assertEquals(List.of(3), policy.rollingCrossed(100, 102, 104));
        // 101→102：跨过 D2？默认无 D2 阈值；已跨 D1 不重复
        assertTrue(policy.rollingCrossed(100, 101, 102).isEmpty());
        // 同日重复活跃不推进
        assertTrue(policy.rollingCrossed(100, 100, 100).isEmpty());
    }

    @Test
    @DisplayName("Rolling：大跳到 31 天跨过全部默认阈值")
    void rollingFullJump() {
        List<Integer> crossed = policy.rollingCrossed(100, 100, 131);
        assertEquals(List.of(1, 3, 7, 14, 30), crossed);
    }

    @Test
    @DisplayName("自定义 N 配置生效")
    void customNDays() {
        RetentionPolicy custom = new RetentionPolicy(
            RetentionPolicy.parseDays("2, 5"), RetentionPolicy.parseDays("4"));
        assertEquals(2, custom.nDayHit(0, 2));
        assertEquals(5, custom.nDayHit(0, 5));
        assertEquals(-1, custom.nDayHit(0, 7));
        assertEquals(List.of(4), custom.rollingCrossed(0, 0, 5));
    }

    @Test
    @DisplayName("非法配置被过滤为空")
    void parseDaysFiltersInvalid() {
        assertEquals(0, RetentionPolicy.parseDays(null).length);
        assertEquals(0, RetentionPolicy.parseDays("  ").length);
        assertEquals(2, RetentionPolicy.parseDays("0,3,x,,7").length);  // 仅 3,7
        assertEquals(1, RetentionPolicy.parseDays("-2,5").length);      // 仅 5
    }

    @Test
    @DisplayName("getter 返回排序去重副本（改动不影响内部状态）")
    void gettersReturnDefensiveSortedCopy() {
        assertArrayEquals(new int[]{1, 7, 30}, policy.nDays());
        assertArrayEquals(new int[]{1, 3, 7, 14, 30}, policy.rollingDays());
        RetentionPolicy messy = new RetentionPolicy(new int[]{7, 1, 7, -3}, new int[]{5, 3, 5});
        assertArrayEquals(new int[]{1, 7}, messy.nDays());
        assertArrayEquals(new int[]{3, 5}, messy.rollingDays());
        int[] copy = messy.nDays();
        copy[0] = 99;
        assertArrayEquals(new int[]{1, 7}, messy.nDays());   // clone 防御
    }
}
