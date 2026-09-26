package io.oddsmaker.agent;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * kafka-clients 消费适配器（真实 broker 路径）。
 *
 * <p><b>验证状态（2026-09-26，apache/kafka:3.7.0 单节点 KRaft 实测）</b>：五项核对点全绿——
 * earliest 定位、断点 seek 续传（checkpoint 位点优先于 broker group offset，旧断点可重放）、
 * 运行中扩分区追赶（依赖 metadata.max.age=1s + assign 前 listTopics 全刷）、坏消息跳过但
 * offset 前进、drain 上限 100 轮的有界分批与续传无丢失；端到端用例见
 * KafkaSourceBrokerE2ETest（broker 不可达自动 SKIP，不误报）。实测还暴露并修复了
 * {@link KafkaSource} 位点簿记缺陷（本轮无数据的分区曾在 cursor 丢条目）。未覆盖：
 * TLS/SASL 鉴权、多 broker 集群、长稳与性能压测。
 *
 * <p>位点不向 broker 提交（enable.auto.commit=false 且无 commit 调用）——
 * checkpoint.json 是唯一位点事实源，重启后 assign+seek 精确恢复。
 */
public final class KafkaConsumerAdapter implements KafkaConsumerPort, AutoCloseable {

    private final KafkaConsumer<byte[], byte[]> consumer;
    private final String topic;

    public KafkaConsumerAdapter(String bootstrapServers, String groupId, String topic) {
        this.topic = topic;
        this.consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, groupId,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName(),
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false",
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "1000",
                // 扩分区感知上界：长驻 consumer 的分区元数据默认 5 分钟才刷新（实测 36s 仍陈旧），
                // 压到 1s——轻量 metadata 轮询，topic 扩分区后下一两个 poll 周期内追上
                ConsumerConfig.METADATA_MAX_AGE_CONFIG, "1000"));
    }

    @Override
    public void assign(Map<Integer, Long> startOffsets) {
        List<TopicPartition> partitions = new ArrayList<>();
        // 全量元数据刷新（每次 assign 一次，即每 agent poll 一次），配合 1s metadata.max.age
        // 保证运行中扩分区在本轮 assign 即感知；kafka-clients 无公开的单 topic 强刷 API
        consumer.listTopics();
        for (PartitionInfo info : consumer.partitionsFor(topic)) {
            partitions.add(new TopicPartition(topic, info.partition()));
        }
        if (partitions.isEmpty()) {
            throw new IllegalStateException("topic 无可用分区: " + topic);
        }
        consumer.assign(partitions);
        Map<Integer, Long> starts = startOffsets == null ? new TreeMap<>() : startOffsets;
        List<TopicPartition> fromBeginning = new ArrayList<>();
        for (TopicPartition tp : partitions) {
            Long start = starts.get(tp.partition());
            if (start == null) {
                fromBeginning.add(tp);
            } else {
                consumer.seek(tp, start);
            }
        }
        if (!fromBeginning.isEmpty()) {
            consumer.seekToBeginning(fromBeginning);
        }
    }

    @Override
    public List<PortRecord> poll(long timeoutMs) {
        ConsumerRecords<byte[], byte[]> records = consumer.poll(Duration.ofMillis(timeoutMs));
        List<PortRecord> out = new ArrayList<>(records.count());
        for (ConsumerRecord<byte[], byte[]> rec : records) {
            out.add(new PortRecord(rec.partition(), rec.offset(), rec.value()));
        }
        return out;
    }

    @Override
    public void close() {
        consumer.close(Duration.ofSeconds(5));
    }
}
