package io.oddsmaker.gateway.metrics;

import io.oddsmaker.gateway.metrics.DataQualityCounters.Counter;
import io.oddsmaker.gateway.metrics.DataQualityCounters.WindowSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataQualityCountersTest {

    /** 可控时钟：手动推进驱动窗口翻页。 */
    static class MutableClock extends Clock {
        Instant now = Instant.ofEpochMilli(1_730_000_000_000L);

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final MutableClock clock = new MutableClock();
    private final DataQualityCounters counters = new DataQualityCounters(clock);

    private WindowSnapshot snap(DataQualityCounters counters, String g, String e) {
        List<WindowSnapshot> all = counters.snapshotAndEvict();
        return all.stream().filter(s -> s.gameId.equals(g) && s.environment.equals(e)).findFirst().orElse(null);
    }

    @Test
    @DisplayName("record：同键累计；缺作用域落 unknown 桶（恒等式不因缺字段丢计数）")
    void recordAccumulatesAndUnknownBucket() {
        counters.record("g1", "prod", Counter.RECEIVED);
        counters.record("g1", "prod", Counter.RECEIVED);
        counters.record("g1", "prod", Counter.REJECTED_SCHEMA);
        counters.record(null, "prod", Counter.RECEIVED);
        counters.record("g1", " ", Counter.LATE);

        WindowSnapshot s = snap(counters, "g1", "prod");
        assertEquals(2, s.count(Counter.RECEIVED));
        assertEquals(1, s.count(Counter.REJECTED_SCHEMA));

        WindowSnapshot unknownGame = snap(counters, "unknown", "prod");
        assertEquals(1, unknownGame.count(Counter.RECEIVED));

        WindowSnapshot unknownEnv = snap(counters, "g1", "unknown");
        assertEquals(1, unknownGame.count(Counter.RECEIVED));
        assertEquals(1, unknownEnv.count(Counter.LATE));

        // 窗口起点 = clock 时刻向下取整到 300s
        assertEquals(1_730_000_000_000L / 1000 / 300 * 300, s.windowEpochSec);
    }

    @Test
    @DisplayName("snapshotAndEvict：当前+上一窗口保留，更早窗口清除；快照不清活跃计数（累计重报）")
    void snapshotKeepsTwoWindowsAndEvictsOlder() {
        counters.record("g1", "prod", Counter.RECEIVED);
        long window0 = clock.instant().getEpochSecond() / 300 * 300;

        // 翻一页：旧窗口仍在保留期（keepFrom 边界含上一窗口）
        clock.now = clock.now.plusSeconds(300);
        counters.record("g1", "prod", Counter.ACCEPTED);
        WindowSnapshot old = counters.snapshotAndEvict().stream()
            .filter(s -> s.gameId.equals("g1") && s.windowEpochSec == window0)
            .findFirst().orElse(null);
        assertEquals(1, old.count(Counter.RECEIVED));

        // 再翻一页：W0 超保留期被清；W1（上一窗）与 W2（活跃）保留，计数互不串窗
        clock.now = clock.now.plusSeconds(300);
        counters.record("g1", "prod", Counter.RECEIVED);
        List<WindowSnapshot> all = counters.snapshotAndEvict();
        assertEquals(2, all.size());
        WindowSnapshot w1 = all.stream().filter(s -> s.windowEpochSec == window0 + 300).findFirst().orElse(null);
        WindowSnapshot w2 = all.stream().filter(s -> s.windowEpochSec == window0 + 600).findFirst().orElse(null);
        assertEquals(1, w1.count(Counter.ACCEPTED));
        assertEquals(0, w1.count(Counter.RECEIVED));
        assertEquals(1, w2.count(Counter.RECEIVED));
        // 快照不清活跃计数：W2 再快照一次 RECEIVED 仍是 1（clone 语义，非取出即清）
        assertEquals(1, w2.count(Counter.RECEIVED));
        assertNull(snap(counters, "g1", "nonexistent"));
        assertTrue(counters.snapshotAndEvict().stream()
            .noneMatch(x -> x.windowEpochSec < window0 + 300));
    }

    @Test
    @DisplayName("clear：清空全部计数（@SpringBootTest 共享上下文隔离用）")
    void clearWipesAll() {
        counters.record("g1", "prod", Counter.RECEIVED);
        counters.clear();
        assertTrue(counters.snapshotAndEvict().isEmpty());
    }
}
