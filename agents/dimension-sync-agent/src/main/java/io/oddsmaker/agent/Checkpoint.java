package io.oddsmaker.agent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 断点（本地 checkpoint.json 的内存态）：增量位点 + 已处理文件 + 累计计数。
 * 拉取产生的 next checkpoint 只在推送成功后由 {@link CheckpointStore} 原子落盘，
 * 失败重 poll 会重放同一窗口——下游 item_dim/level_dim 是 ReplacingMergeTree(version_ts)，重复写入幂等。
 */
public final class Checkpoint {

    /** 增量水位（CursorCodec 编码：n:<millis> / t:<ISO instant> / s:<raw>）；JDBC source 用 */
    public String cursor;

    /** 已完整处理的 CSV 文件名 → 行数；文件粒度断点（同名文件不重读，换内容请用新文件名） */
    public Map<String, Long> files = new LinkedHashMap<>();

    /** 已成功推送的源头最新 version_ts（epoch millis），供同步状态上报 */
    public Long lastEventTs;

    public long pushedCount;
    public long errorCount;
    public String lastError;

    public Checkpoint copy() {
        Checkpoint c = new Checkpoint();
        c.cursor = cursor;
        c.files = new LinkedHashMap<>(files);
        c.lastEventTs = lastEventTs;
        c.pushedCount = pushedCount;
        c.errorCount = errorCount;
        c.lastError = lastError;
        return c;
    }
}
