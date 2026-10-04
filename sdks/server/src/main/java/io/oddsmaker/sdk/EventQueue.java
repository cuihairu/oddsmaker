package io.oddsmaker.sdk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 事件队列：内存为主、磁盘溢出兜底（Memory→Disk Queue）。
 *
 * <p>写入：事件 JSON 行进内存 deque；内存达到 {@code maxMemoryEvents} 上限时把最旧一半
 * 溢写为磁盘 NDJSON 文件（{@code spill-<seq>.ndjson}，seq 单调递增）。
 *
 * <p>读取：先内存 FIFO，内存空后按文件名 seq 升序读最旧溢写文件（整读即删，失败时
 * re-offer 重新溢写为最新文件——跨文件严格序在失败重排场景不保证，见 README）。
 *
 * <p>磁盘上限 {@code maxDiskEvents}：超限删最旧文件并计入 dropped（进程内计数，不持久化）。
 *
 * <p>线程模型：所有方法在实例锁内执行；文件 IO 持锁进行（队列操作低频，可接受）。
 */
final class EventQueue {

    private static final Pattern SPILL_FILE = Pattern.compile("spill-(\\d+)\\.ndjson");

    private final Path queueDir;
    private final int maxMemoryEvents;
    private final long maxDiskEvents;
    private final java.util.function.BiConsumer<String, String> warn;

    private final ArrayDeque<String> memory = new ArrayDeque<>();
    private long spillSeq = 0;      // 文件名单调递增（进程内）
    private long diskEvents = 0;    // 当前磁盘滞留事件数（近似）
    private long dropped = 0;       // 磁盘超限丢弃计数

    EventQueue(Path queueDir, int maxMemoryEvents, long maxDiskEvents,
               java.util.function.BiConsumer<String, String> warn) {
        this.queueDir = queueDir;
        this.maxMemoryEvents = Math.max(1, maxMemoryEvents);
        this.maxDiskEvents = Math.max(1, maxDiskEvents);
        this.warn = warn;
    }

    /** 入队一条事件 JSON 行。失败（磁盘 IO 异常）时丢该条并不中断调用方。 */
    synchronized void enqueue(String eventJson) {
        memory.addLast(eventJson);
        if (memory.size() > maxMemoryEvents) {
            spillOldest();
        }
    }

    /** 关停兜底：把内存残留全部溢写磁盘（重启续传的最后一段保障），返回落盘条数。 */
    synchronized int spillAll() {
        if (memory.isEmpty()) {
            return 0;
        }
        List<String> all = new ArrayList<>(memory);
        memory.clear();
        try {
            Files.createDirectories(queueDir);
            Path file = queueDir.resolve("spill-" + spillSeq + ".ndjson");
            Files.write(file, all, StandardCharsets.UTF_8);
            spillSeq++;
            diskEvents += all.size();
            enforceDiskCap();
            return all.size();
        } catch (IOException e) {
            dropped += all.size();
            warn.accept("spill-all-failed", "dropped " + all.size()
                + " events on close, disk spill error: " + e.getMessage());
            return 0;
        }
    }

    /** 失败批次重新入队：置于内存队首以尽快重发；内存不足时溢写磁盘。 */
    synchronized void reoffer(List<String> eventJsons) {
        for (int i = eventJsons.size() - 1; i >= 0; i--) {
            memory.addFirst(eventJsons.get(i));
        }
        while (memory.size() > maxMemoryEvents) {
            spillOldest();
        }
    }

    /** 取出最多 batchSize 条（内存优先，内存空后读最旧溢写文件）。 */
    synchronized List<String> take(int batchSize) {
        List<String> batch = new ArrayList<>(Math.min(batchSize, memory.size() + 8));
        while (batch.size() < batchSize && !memory.isEmpty()) {
            batch.add(memory.pollFirst());
        }
        while (batch.size() < batchSize && memory.isEmpty() && hasSpillFiles()) {
            batch.addAll(readOldestSpillFile(batchSize - batch.size()));
        }
        return batch;
    }

    synchronized boolean isEmpty() {
        return memory.isEmpty() && !hasSpillFiles();
    }

    synchronized int memorySize() {
        return memory.size();
    }

    synchronized long droppedCount() {
        return dropped;
    }

    private void spillOldest() {
        int move = Math.max(1, maxMemoryEvents / 2);
        List<String> head = new ArrayList<>(move);
        while (head.size() < move && !memory.isEmpty()) {
            head.add(memory.pollFirst());
        }
        try {
            Files.createDirectories(queueDir);
            Path file = queueDir.resolve("spill-" + spillSeq + ".ndjson");
            Files.write(file, head, StandardCharsets.UTF_8);
            spillSeq++;
            diskEvents += head.size();
            enforceDiskCap();
        } catch (IOException e) {
            // 溢写失败：这批事件无处可去只能丢弃；内存缓冲机制不能因 IO 阻塞业务线程
            dropped += head.size();
            warn.accept("spill-failed", "dropped " + head.size()
                + " events, disk spill error: " + e.getMessage());
        }
    }

    private boolean hasSpillFiles() {
        if (!Files.isDirectory(queueDir)) {
            return false;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(queueDir, "spill-*.ndjson")) {
            return ds.iterator().hasNext();
        } catch (IOException e) {
            return false;
        }
    }

    private List<String> readOldestSpillFile(int maxLines) {
        Path oldest = oldestSpillFile();
        if (oldest == null) {
            return List.of();
        }
        try {
            List<String> lines = Files.readAllLines(oldest, StandardCharsets.UTF_8);
            Files.deleteIfExists(oldest);
            diskEvents -= lines.size();
            if (lines.size() <= maxLines) {
                return lines;
            }
            // 单文件超出批大小：余量写回新文件（seq 最大，仍在队尾语义）
            List<String> take = lines.subList(0, maxLines);
            List<String> rest = new ArrayList<>(lines.subList(maxLines, lines.size()));
            try {
                Files.createDirectories(queueDir);
                Path file = queueDir.resolve("spill-" + spillSeq + ".ndjson");
                Files.write(file, rest, StandardCharsets.UTF_8);
                spillSeq++;
            } catch (IOException e) {
                dropped += rest.size();
                warn.accept("spill-retry-write-failed",
                    "dropped " + rest.size() + " events: " + e.getMessage());
            }
            return new ArrayList<>(take);
        } catch (IOException e) {
            warn.accept("spill-read-failed", "skip unreadable spill file: " + oldest.getFileName());
            try {
                Files.deleteIfExists(oldest);
            } catch (IOException ignored) {
                // 删除失败留待下次清理，不阻塞发送
            }
            return List.of();
        }
    }

    private Path oldestSpillFile() {
        try (Stream<Path> files = Files.list(queueDir)) {
            return files
                .filter(p -> SPILL_FILE.matcher(p.getFileName().toString()).matches())
                .min(Comparator.comparingLong(
                    p -> Long.parseLong(SPILL_FILE.matcher(p.getFileName().toString())
                        .results().findFirst().orElseThrow().group(1))))
                .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private void enforceDiskCap() {
        while (diskEvents > maxDiskEvents) {
            Path oldest = oldestSpillFile();
            if (oldest == null) {
                diskEvents = 0;
                return;
            }
            try {
                long lines = Files.readAllLines(oldest, StandardCharsets.UTF_8).size();
                Files.deleteIfExists(oldest);
                diskEvents -= lines;
                dropped += lines;
                warn.accept("disk-cap", "dropped " + lines + " oldest events (disk cap)");
            } catch (IOException e) {
                return; // 删不掉就先留着，下轮再试
            }
        }
    }
}
