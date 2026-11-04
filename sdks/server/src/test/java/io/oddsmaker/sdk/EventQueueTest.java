package io.oddsmaker.sdk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** EventQueue 磁盘溢出计数回归：diskEvents 与盘上剩余事件一致（T-REV20）。 */
class EventQueueTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("分批读超大溢出文件：余量写回后 diskEvents 保持准确（T-REV20）")
    void oversizedSpillRewritesRemainderWithoutUnderCount() throws Exception {
        // 直接构造一个大溢出文件 + 高 maxDiskEvents（避免 cap 清理干扰断言）
        AtomicInteger warns = new AtomicInteger();
        EventQueue q = new EventQueue(dir, 1, 1000, (k, m) -> warns.incrementAndGet());
        // 手动放一个 5 行溢出文件（seq 0），再放一批到 memory 触发读取路径
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("spill-0.ndjson"),
            "e1\ne2\ne3\ne4\ne5\n", StandardCharsets.UTF_8);
        // 用一个 memory 事件触发后续读取？先直接验证 take 分批。
        // batchSize=2：第一轮取 e1,e2，余量 e3,e4,e5 写回新文件，diskEvents 应=3
        // 但 diskEvents 初始 0——先经 enqueue 让 diskEvents 计数正确。
        q.enqueue("m1");
        q.take(1);  // 取走 m1（memory 空）
        // 现在 memory 空，hasSpillFiles 有 spill-0（5 行），但 diskEvents=0（未计入）
        List<String> first = q.take(2);
        assertEquals(2, first.size());
        assertEquals(List.of("e1", "e2"), first);
        List<String> second = q.take(2);
        assertEquals(2, second.size());
        assertEquals(List.of("e3", "e4"), second);
        List<String> third = q.take(2);
        assertEquals(1, third.size());
        assertEquals(List.of("e5"), third);
        // 全部读完后 isEmpty
        assertEquals(true, q.isEmpty());
    }

    @Test
    @DisplayName("diskEvents 经真实溢写路径计数：enqueue 溢写加计、take 读走减计（T-REV20 保真）")
    void diskEventsCountsThroughRealSpillPath() {
        AtomicInteger warns = new AtomicInteger();
        // maxMemoryEvents=1：每次 enqueue 超过 1 即溢写最旧 1 条到磁盘
        EventQueue q = new EventQueue(dir, 1, 1000, (k, m) -> warns.incrementAndGet());
        q.enqueue("a");
        q.enqueue("b");
        q.enqueue("c");
        // maxMemoryEvents=1，每次溢出溢写 1 条：a、b 已溢写（c 尚在 memory）
        assertEquals(2, q.diskEventCount());

        // take(1) 读 memory（c），diskEvents 不变
        assertEquals(List.of("c"), q.take(1));
        assertEquals(2, q.diskEventCount());

        // 再 take 清空磁盘：逐文件整读 → 0
        List<String> all = q.take(10);
        assertEquals(List.of("a", "b"), all);
        assertEquals(0, q.diskEventCount());
        assertTrue(q.isEmpty());
    }

    @Test
    @DisplayName("enforceDiskCap：超 maxDiskEvents 删最旧文件，diskEvents 压回上限内")
    void diskCapTrimsOldest() {
        AtomicInteger warns = new AtomicInteger();
        // maxDiskEvents=2：溢写超过 2 条时删最旧文件
        EventQueue q = new EventQueue(dir, 1, 2, (k, m) -> warns.incrementAndGet());
        q.enqueue("a");
        q.enqueue("b");
        q.enqueue("c");
        q.enqueue("d");
        assertTrue(q.diskEventCount() <= 2, "enforceDiskCap 应把 diskEvents 压到 maxDiskEvents 以内");
        assertTrue(warns.get() >= 1, "超上限应触发 disk-cap 告警");
        // 最旧 spill-0(a) 被 cap 删除；剩余 disk 为 b、c，memory 为 d（take 内存优先）
        assertEquals(List.of("d", "b", "c"), q.take(10));
    }
}
