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
 * <p><b>验证边界（如实标注）</b>：本类需要真实 broker 才能运行，当前仅编译期验证，
 * 未做端到端消费验证（内网无 broker）；配置解析 / 消息反序列化 / 位点推进逻辑
 * 由 {@link KafkaSource} 假端口单测覆盖。接真实 broker 后请先用测试 topic 核对：
 * earliest 定位、扩分区追赶、断点续传三点。
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
                ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "1000"));
    }

    @Override
    public void assign(Map<Integer, Long> startOffsets) {
        List<TopicPartition> partitions = new ArrayList<>();
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
