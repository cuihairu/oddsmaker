package io.oddsmaker.gateway.inspector;

import io.oddsmaker.common.model.Event;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EventInspectorBuffer（Live Inspector 检视缓冲）单元测试：
 * 有界环形、新→旧读取、outcome 过滤、作用域隔离与 LRU 淘汰、TTL 过期、并发安全。
 *
 * 记账（不可达分支）：
 * - recent() L144 `rec.tsServer < cutoff` 的 break 侧——L138 的 while 已把过期前缀
 *   全部清理（deque 按 addLast 插入序、tsServer 单调不减），迭代器只见未过期记录，
 *   break 为防御性兜底，公开路径不可达；
 * - evictStaleScopes() L178 `scopes.remove(key) != null` 的假侧——scopes 与 lastTouch
 *   在 record/evict/clear 中恒同步增删，lastTouch 不会出现 scopes 缺失的键，
 *   假侧仅理论竞态可致，公开路径不可达。
 */
@DisplayName("实时事件检视缓冲")
class EventInspectorBufferTest {

    private Event event(String id) {
        Event e = new Event();
        e.eventId = id;
        e.eventName = "level_start";
        e.eventType = "progression";
        e.deviceId = "d-" + id;
        e.tsClient = 1730000000000L;
        return e;
    }

    @Test
    @DisplayName("容量有界：超出 perScopeCapacity 淘汰最旧，仅保留最新 N 条")
    void capacityBoundsKeepNewest() {
        EventInspectorBuffer buf = new EventInspectorBuffer(3, 8, 600_000L);
        for (int i = 1; i <= 5; i++) {
            buf.record("game_a", "prod", EventInspectorBuffer.OUTCOME_ACCEPTED, null, null, event("e" + i));
        }
        List<EventInspectorBuffer.InspectorRecord> recs = buf.recent("game_a", "prod", null, 10);
        assertEquals(3, recs.size());
        // 新→旧：最新 e5 在首位，被淘汰的 e1/e2 不在
        assertEquals("e5", recs.get(0).eventId);
        assertEquals("e3", recs.get(2).eventId);
    }

    @Test
    @DisplayName("outcome 过滤：只返回指定结局的记录")
    void outcomeFilter() {
        EventInspectorBuffer buf = new EventInspectorBuffer(10, 8, 600_000L);
        buf.record("g", "prod", EventInspectorBuffer.OUTCOME_ACCEPTED, null, null, event("ok1"));
        buf.record("g", "prod", EventInspectorBuffer.OUTCOME_REJECTED, "invalid_schema", "device_id is missing", event("bad1"));
        buf.record("g", "prod", EventInspectorBuffer.OUTCOME_REJECTED, "pii_blocked", null, event("bad2"));

        List<EventInspectorBuffer.InspectorRecord> rejected = buf.recent("g", "prod", "rejected", 10);
        assertEquals(2, rejected.size());
        assertEquals("bad2", rejected.get(0).eventId);
        assertEquals("invalid_schema", rejected.get(1).reason);
        assertEquals("device_id is missing", rejected.get(1).detail);

        assertEquals(1, buf.recent("g", "prod", "accepted", 10).size());
        assertEquals(3, buf.recent("g", "prod", null, 10).size());
    }

    @Test
    @DisplayName("作用域隔离：不同 (game, env) 队列互不可见；路由字段缺失不入缓冲")
    void scopeIsolationAndBlankScopeSkip() {
        EventInspectorBuffer buf = new EventInspectorBuffer(10, 8, 600_000L);
        buf.record("game_a", "prod", EventInspectorBuffer.OUTCOME_ACCEPTED, null, null, event("a1"));
        buf.record("game_a", "dev", EventInspectorBuffer.OUTCOME_ACCEPTED, null, null, event("a2"));
        buf.record("game_b", "prod", EventInspectorBuffer.OUTCOME_ACCEPTED, null, null, event("b1"));

        assertEquals(1, buf.recent("game_a", "prod", null, 10).size());
        assertEquals(1, buf.recent("game_a", "dev", null, 10).size());
        assertEquals(1, buf.recent("game_b", "prod", null, 10).size());
        assertEquals(3, buf.scopeCount());

        // 缺失路由字段 → 静默跳过（DLQ 已捕获全量）
        buf.record(null, "prod", EventInspectorBuffer.OUTCOME_REJECTED, "invalid_schema", null, event("x1"));
        buf.record("game_a", " ", EventInspectorBuffer.OUTCOME_REJECTED, "invalid_schema", null, event("x2"));
        assertEquals(3, buf.scopeCount());
        assertTrue(buf.recent(null, "prod", null, 10).isEmpty());
    }

    @Test
    @DisplayName("作用域 LRU 淘汰：超过 maxScopes 时淘汰最久未写作用域")
    void scopeLruEviction() throws InterruptedException {
        EventInspectorBuffer buf = new EventInspectorBuffer(10, 2, 600_000L);
        buf.record("g1", "prod", EventInspectorBuffer.OUTCOME_ACCEPTED, null, null, event("e1"));
        Thread.sleep(5); // lastTouch 同毫秒并列会任意淘汰，间隔确保 LRU 确定性
        buf.record("g2", "prod", EventInspectorBuffer.OUTCOME_ACCEPTED, null, null, event("e2"));
        Thread.sleep(5);
        buf.record("g3", "prod", EventInspectorBuffer.OUTCOME_ACCEPTED, null, null, event("e3"));

        assertEquals(2, buf.scopeCount());
        // g1 最旧被淘汰；g2/g3 仍在
        assertTrue(buf.recent("g1", "prod", null, 10).isEmpty());
        assertEquals(1, buf.recent("g2", "prod", null, 10).size());
        assertEquals(1, buf.recent("g3", "prod", null, 10).size());
    }

    @Test
    @DisplayName("TTL 过期：超过 retainMs 的记录读取时被过滤并清理")
    void ttlExpiry() throws InterruptedException {
        EventInspectorBuffer buf = new EventInspectorBuffer(10, 8, 50L);
        buf.record("g", "prod", EventInspectorBuffer.OUTCOME_ACCEPTED, null, null, event("old"));
        Thread.sleep(120); // 超过 retainMs=50ms
        assertTrue(buf.recent("g", "prod", null, 10).isEmpty());

        // 过期后新记录仍正常入队
        buf.record("g", "prod", EventInspectorBuffer.OUTCOME_ACCEPTED, null, null, event("fresh"));
        assertEquals(1, buf.recent("g", "prod", null, 10).size());
    }

    @Test
    @DisplayName("并发写入安全：多线程同时写同一作用域不丢失不越界")
    void concurrentWrites() throws InterruptedException {
        EventInspectorBuffer buf = new EventInspectorBuffer(64, 8, 600_000L);
        int threads = 8;
        int perThread = 32;
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                for (int i = 0; i < perThread; i++) {
                    buf.record("g", "prod", EventInspectorBuffer.OUTCOME_ACCEPTED, null, null,
                        event("t" + Thread.currentThread().getId() + "-" + i));
                }
                done.countDown();
            });
        }
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdownNow();
        // 全部 256 条写入，容量 64 → 恰好保留最新 64 条
        assertEquals(64, buf.recent("g", "prod", null, 300).size());
    }

    @Test
    @DisplayName("路由字段空白串/null 对侧：gameId 空白与 environment 缺失同样静默跳过")
    void blankRoutingFieldsSkipped() {
        EventInspectorBuffer buf = new EventInspectorBuffer(10, 8, 600_000L);
        buf.record("game_a", "prod", EventInspectorBuffer.OUTCOME_ACCEPTED, null, null, event("a1"));
        // gameId 空白串（非 null）与 environment null 都是「无法归属作用域」的对侧臂
        buf.record("  ", "prod", EventInspectorBuffer.OUTCOME_REJECTED, "invalid_schema", null, event("x1"));
        buf.record("game_a", null, EventInspectorBuffer.OUTCOME_REJECTED, "invalid_schema", null, event("x2"));
        assertEquals(1, buf.scopeCount());
        assertEquals(1, buf.recent("game_a", "prod", null, 10).size());
    }

    @Test
    @DisplayName("outcome 空白串（非 null）视为不过滤，与 null 同语义")
    void blankOutcomeMeansNoFilter() {
        EventInspectorBuffer buf = new EventInspectorBuffer(10, 8, 600_000L);
        buf.record("g", "prod", EventInspectorBuffer.OUTCOME_ACCEPTED, null, null, event("ok1"));
        buf.record("g", "prod", EventInspectorBuffer.OUTCOME_REJECTED, "invalid_schema", null, event("bad1"));
        List<EventInspectorBuffer.InspectorRecord> all = buf.recent("g", "prod", "  ", 10);
        assertEquals(2, all.size());
    }

    @Test
    @DisplayName("maxScopes=0 边界：写入即淘汰（淘汰循环耗尽退出，非 break 路径）")
    void zeroMaxScopesEvictsImmediately() {
        EventInspectorBuffer buf = new EventInspectorBuffer(10, 0, 600_000L);
        buf.record("g", "prod", EventInspectorBuffer.OUTCOME_ACCEPTED, null, null, event("e1"));
        // toEvict=1 与 byAge 条目数相等：唯一条目移除后循环耗尽退出（hasNext 假侧）
        assertEquals(0, buf.scopeCount());
        assertTrue(buf.recent("g", "prod", null, 10).isEmpty());
    }
}
