package io.oddsmaker.agent;

import java.util.List;

/**
 * 维度数据源抽象：JDBC（mysql/postgres 增量查询）与 CSV（文件目录扫描）各一个实现。
 * poll 返回变更列表与「推送成功后才生效」的 next checkpoint——实现自身不落盘。
 */
public interface DimensionSource {

    /** 数据源名（进 status.source_key 的缺省与日志） */
    String name();

    String type();

    PollResult poll(Checkpoint current) throws Exception;

    /** 一次拉取的结果：变更 + 若全部推送成功则应生效的 next checkpoint。 */
    record PollResult(List<DimensionChange> changes, Checkpoint next) {
        public static PollResult of(List<DimensionChange> changes, Checkpoint next) {
            return new PollResult(changes, next);
        }
    }
}
