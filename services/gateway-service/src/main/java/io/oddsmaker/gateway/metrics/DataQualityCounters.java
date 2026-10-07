package io.oddsmaker.gateway.metrics;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * B10 数据质量进程内计数器（设计定稿 07-b10 §4.2）。
 *
 * 按 (game_id, environment, 5 分钟窗口) 聚合 LongAdder 语义的 long[] 增量；
 * 快照语义为「自窗口起点以来的累计值」——定时器每 60s 全量重报当前与上一窗口，
 * control 侧按唯一键整行覆盖，乱序与重复投递无害。上报失败下轮自然重试，
 * 故快照不清活跃窗口，只清除已翻页超过一个窗口的旧键（其最新累计值此前已报多轮）。
 */
@Component
public class DataQualityCounters {

    /** 计数维度与 data_quality_metrics 列一一对应（ordinal 即 long[] 下标，勿调整既有顺序）。 */
    public enum Counter {
        RECEIVED, ACCEPTED, SAMPLED_OUT, DUPLICATES, LATE,
        REJECTED_SCHEMA, REJECTED_UNKNOWN_EVENT, REJECTED_INVALID_TIMESTAMP, REJECTED_PII_BLOCKED,
        REJECTED_PAYLOAD_TOO_LARGE, REJECTED_TRUST_ESCALATION, REJECTED_BLOCKED,
        REJECTED_SCOPE_MISMATCH, REJECTED_KAFKA_ERROR
    }

    static final int WINDOW_SEC = 300;

    private final Clock clock;
    /** key = gameId|environment|windowEpochSec。game_id/environment 为业务标识，不含 '|'。 */
    private final ConcurrentHashMap<String, long[]> windows = new ConcurrentHashMap<>();

    public DataQualityCounters() {
        this(Clock.systemUTC());
    }

    /** 测试专用：注入可控时钟驱动窗口翻页。 */
    DataQualityCounters(Clock clock) {
        this.clock = clock;
    }

    /** 计一次事件。gameId/environment 缺失落 "unknown" 桶——观测面不丢计数，恒等式才守得住。 */
    public void record(String gameId, String environment, Counter counter) {
        if (counter == null) {
            return;
        }
        String g = gameId == null || gameId.isBlank() ? "unknown" : gameId;
        String e = environment == null || environment.isBlank() ? "unknown" : environment;
        long window = currentWindow();
        long[] arr = windows.computeIfAbsent(g + "|" + e + "|" + window, k -> new long[Counter.values().length]);
        arr[counter.ordinal()]++;
    }

    /**
     * 当前 + 上一窗口的累计快照（新窗在前不保证，调用方按 key 逐条上报），
     * 并清除更早的窗口键。
     */
    public List<WindowSnapshot> snapshotAndEvict() {
        long keepFrom = currentWindow() - WINDOW_SEC;
        List<WindowSnapshot> out = new ArrayList<>();
        for (Map.Entry<String, long[]> en : windows.entrySet()) {
            String[] parts = en.getKey().split("\\|", 3);
            long window;
            if (parts.length < 3) {
                windows.remove(en.getKey());
                continue;
            }
            try {
                window = Long.parseLong(parts[2]);
            } catch (NumberFormatException nfe) {
                windows.remove(en.getKey());
                continue;
            }
            if (window < keepFrom) {
                windows.remove(en.getKey());
                continue;
            }
            out.add(new WindowSnapshot(parts[0], parts[1], window, en.getValue().clone()));
        }
        return out;
    }

    private long currentWindow() {
        return clock.millis() / 1000L / WINDOW_SEC * WINDOW_SEC;
    }

    /** 清空全部计数。@SpringBootTest 共享上下文的测试隔离用（EventInspectorBuffer.clear 先例），生产无人调用。 */
    public void clear() {
        windows.clear();
    }

    /** 单窗口累计快照。counts 下标即 {@link Counter#ordinal()}。 */
    public static class WindowSnapshot {
        public final String gameId;
        public final String environment;
        public final long windowEpochSec;
        final long[] counts;

        WindowSnapshot(String gameId, String environment, long windowEpochSec, long[] counts) {
            this.gameId = gameId;
            this.environment = environment;
            this.windowEpochSec = windowEpochSec;
            this.counts = counts;
        }

        public long count(Counter counter) {
            return counts[counter.ordinal()];
        }
    }
}
