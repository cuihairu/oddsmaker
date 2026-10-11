package io.oddsmaker.agent;

import java.util.List;

/**
 * 维度数据源抽象：JDBC（mysql/postgres 增量查询）与 CSV（文件目录扫描）各一个实现。
 * poll 返回变更列表与「推送成功后才生效」的 next checkpoint——实现自身不落盘。
 */
public interface DimensionSource extends AutoCloseable {

    /** 数据源名（进 status.source_key 的缺省与日志） */
    String name();

    String type();

    PollResult poll(Checkpoint current) throws Exception;

    /** 释放底层资源（Kafka consumer 等）；缺省无资源。Control 托管配置热更新换源时调用。 */
    @Override
    default void close() {
    }

    /** 一次拉取的结果：变更 + 若全部推送成功则应生效的 next checkpoint。 */
    record PollResult(List<DimensionChange> changes, Checkpoint next) {
        public static PollResult of(List<DimensionChange> changes, Checkpoint next) {
            return new PollResult(changes, next);
        }
    }
}
