package io.oddsmaker.agent;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
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
 * {@link KafkaSource} 位点簿记缺陷（本轮无数据的分区曾在 cursor 丢条目）。
 * SASL/SCRAM 鉴权与多地址 bootstrap 见 KafkaSourceSaslBrokerE2ETest /
 * KafkaSourceMultiBrokerE2ETest。未覆盖：TLS(SSL) 证书链、长稳与性能压测。
 *
 * <p>位点不向 broker 提交（enable.auto.commit=false 且无 commit 调用）——
 * checkpoint.json 是唯一位点事实源，重启后 assign+seek 精确恢复。
 */
public final class KafkaConsumerAdapter implements KafkaConsumerPort, AutoCloseable {

    private final KafkaConsumer<byte[], byte[]> consumer;
    private final String topic;

    public KafkaConsumerAdapter(String bootstrapServers, String groupId, String topic) {
        this(bootstrapServers, groupId, topic, null, null, null, null);
    }

    /**
     * @param securityProtocol PLAINTEXT/SSL/SASL_PLAINTEXT/SASL_SSL（null 视为 PLAINTEXT，向后兼容）
     * @param saslMechanism    SCRAM 机制（SASL_* 协议时必填）
     * @param username         SASL 账号（SASL_* 协议时必填）
     * @param password         SASL 密码（SASL_* 协议时必填）
     */
    public KafkaConsumerAdapter(String bootstrapServers, String groupId, String topic,
                                String securityProtocol, String saslMechanism,
                                String username, String password) {
        this.topic = topic;
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "1000");
        // 扩分区感知上界：长驻 consumer 的分区元数据默认 5 分钟才刷新（实测 36s 仍陈旧），
        // 压到 1s——轻量 metadata 轮询，topic 扩分区后下一两个 poll 周期内追上
        props.put(ConsumerConfig.METADATA_MAX_AGE_CONFIG, "1000");
        applySecurityProps(props, securityProtocol, saslMechanism, username, password);
        this.consumer = new KafkaConsumer<>(props);
    }

    /** 鉴权 props 构建独立可测（不建 consumer）：SASL_* 时注入 SCRAM LoginModule JAAS 行。 */
    static void applySecurityProps(Map<String, Object> props, String securityProtocol,
                                   String saslMechanism, String username, String password) {
        String protocol = securityProtocol == null || securityProtocol.isBlank()
                ? "PLAINTEXT" : securityProtocol;
        props.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, protocol);
        if (!protocol.startsWith("SASL")) {
            return;  // PLAINTEXT 明文 / SSL 证书模式：无账号凭证 props
        }
        props.put(SaslConfigs.SASL_MECHANISM, saslMechanism);
        // SCRAM 凭证经 JAAS 注入；" 与 \ 按 JAAS 语法转义防注入
        props.put(SaslConfigs.SASL_JAAS_CONFIG,
                "org.apache.kafka.common.security.scram.ScramLoginModule required "
                        + "username=\"" + jaasEscape(username) + "\" "
                        + "password=\"" + jaasEscape(password) + "\";");
    }

    private static String jaasEscape(String v) {
        return v.replace("\\", "\\\\").replace("\"", "\\\"");
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
