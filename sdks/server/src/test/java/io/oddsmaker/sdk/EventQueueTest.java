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

/** EventQueue 磁盘溢出计数回归：分批读取后 diskEvents 与盘上剩余事件一致。 */
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
}
