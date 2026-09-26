package io.oddsmaker.common.kafka;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * KafkaProducers.newBytesProducer 真实 broker 端到端（apache/kafka:3.7.0 宿主 29092）。
 * <b>broker 不可达时端到端用例 SKIP（Assumption 失败），不算失败也不误报绿</b>；
 * 工厂构造本身惰性建连，离线可验证（见 lazyConstructionWithoutBroker）。
 */
class KafkaProducersBrokerE2ETest {

    private static final String BOOTSTRAP =
            System.getProperty("oddsmaker.kafka.e2e.bootstrap", "localhost:29092");

    private static final List<String> topics = new ArrayList<>();

    @AfterAll
    static void cleanup() throws Exception {
        if (topics.isEmpty()) {
            return;
        }
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP))) {
            admin.deleteTopics(topics).all().get(10, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // 清理失败不影响结论（topic 名带 run 唯一后缀，broker 为一次性环境）
        }
    }

    private static boolean brokerReachable() {
        // 两次尝试：KRaft 刚就绪时 describeCluster 偶发超时，避免暂态误判成「broker 不可达」
        for (int attempt = 1; attempt <= 2; attempt++) {
            try (AdminClient admin = AdminClient.create(Map.of(
                    AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP,
                    AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "5000",
                    AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "10000"))) {
                admin.describeCluster().nodes().get(10, TimeUnit.SECONDS);
                return true;
            } catch (Exception e) {
                System.err.println("[e2e] broker 可达性检查未通过（尝试 " + attempt + "/2）: " + e);
            }
        }
        return false;
    }

    @Test
    @DisplayName("newBytesProducer 真实回路：字节 producer 送达 + key/value 逐字节回读")
    void bytesProducerRealBrokerRoundtrip() throws Exception {
        Assumptions.assumeTrue(brokerReachable(),
                "kafka broker 不可达（" + BOOTSTRAP + "），端到端用例跳过——docker 起 kafka 后重跑");

        String topic = "e2e-ck-bytes-" + UUID.randomUUID().toString().substring(0, 8);
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP))) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all()
                    .get(10, TimeUnit.SECONDS);
        }
        topics.add(topic);

        Producer<byte[], byte[]> producer = KafkaProducers.newBytesProducer(BOOTSTRAP);
        try {
            // 三条 kv：含空 key（允许 null key）与多字节 UTF-8，逐字节回读核对 ByteArraySerializer 无损
            producer.send(new ProducerRecord<>(topic,
                    "k1".getBytes(StandardCharsets.UTF_8),
                    "{\"a\":1}".getBytes(StandardCharsets.UTF_8))).get(10, TimeUnit.SECONDS);
            producer.send(new ProducerRecord<>(topic,
                    null,
                    "no-key".getBytes(StandardCharsets.UTF_8))).get(10, TimeUnit.SECONDS);
            producer.send(new ProducerRecord<>(topic,
                    "键-中文".getBytes(StandardCharsets.UTF_8),
                    "值-多字节".getBytes(StandardCharsets.UTF_8))).get(10, TimeUnit.SECONDS);

            try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(Map.of(
                    "bootstrap.servers", BOOTSTRAP,
                    "key.deserializer", ByteArrayDeserializer.class.getName(),
                    "value.deserializer", ByteArrayDeserializer.class.getName(),
                    "enable.auto.commit", "false"))) {
                List<TopicPartition> tps = List.of(new TopicPartition(topic, 0));
                consumer.assign(tps);
                consumer.seekToBeginning(tps);
                List<ConsumerRecord<byte[], byte[]>> out = new ArrayList<>();
                long deadline = System.currentTimeMillis() + 15_000;
                while (out.size() < 3 && System.currentTimeMillis() < deadline) {
                    for (ConsumerRecord<byte[], byte[]> r : consumer.poll(Duration.ofMillis(1000))) {
                        out.add(r);
                    }
                }
                assertEquals(3, out.size(), "三条应全部送达");
                assertArrayEquals("k1".getBytes(StandardCharsets.UTF_8), out.get(0).key());
                assertArrayEquals("{\"a\":1}".getBytes(StandardCharsets.UTF_8), out.get(0).value());
                assertEquals(out.get(0).offset() + 1, out.get(1).offset(), "同分区 offset 单调");
                assertNull(out.get(1).key(), "null key 应以 kafka null key 上 wire");
                assertArrayEquals("no-key".getBytes(StandardCharsets.UTF_8), out.get(1).value());
                assertArrayEquals("键-中文".getBytes(StandardCharsets.UTF_8), out.get(2).key());
                assertArrayEquals("值-多字节".getBytes(StandardCharsets.UTF_8), out.get(2).value());
                for (ConsumerRecord<byte[], byte[]> r : out) {
                    assertEquals(topic, r.topic());
                }
            }
        } finally {
            producer.close(Duration.ofSeconds(5));
        }
    }

    @Test
    @DisplayName("newBytesProducer 惰性建连：broker 不可达也能构造（首个 send 才触达网络）")
    void lazyConstructionWithoutBroker() {
        // 指向死端口：构造不应抛异常——KafkaProducer 只装配序列化器不拨号
        Producer<byte[], byte[]> producer = KafkaProducers.newBytesProducer("127.0.0.1:19092");
        assertNotNull(producer);
        producer.close(Duration.ZERO);
    }
}
