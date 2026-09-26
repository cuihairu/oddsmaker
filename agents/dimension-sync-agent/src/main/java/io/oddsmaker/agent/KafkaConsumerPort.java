package io.oddsmaker.agent;

import java.util.List;
import java.util.Map;

/**
 * Kafka 消费端口抽象：{@link KafkaSource} 只依赖本接口（离线可测——假端口注入），
 * 真实 broker 路径由 {@link KafkaConsumerAdapter}（kafka-clients）实现。
 */
public interface KafkaConsumerPort extends AutoCloseable {

    /**
     * 手动指派订阅并定位起点：startOffsets 为分区 → 下一条待消费 offset；
     * 空表（无断点）时适配器按 earliest 指派该 topic 全部分区，
     * 断点未覆盖到的分区同样按 earliest 定位（topic 扩分区后能追上）。
     */
    void assign(Map<Integer, Long> startOffsets) throws Exception;

    /** 拉一批记录（可能为空）；阻塞语义由实现决定（超时返回空）。 */
    List<PortRecord> poll(long timeoutMs) throws Exception;

    /** 消费记录：offset 定位用（成功处理后写 next offset = offset + 1）。 */
    record PortRecord(int partition, long offset, byte[] value) {}
}
