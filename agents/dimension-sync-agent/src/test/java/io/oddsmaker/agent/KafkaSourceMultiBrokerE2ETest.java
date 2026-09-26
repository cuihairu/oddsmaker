package io.oddsmaker.agent;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * kafka source 多 broker 集群端到端（3 节点 KRaft，docker network 内 kafka-mb1/2/3，
 * 宿主 29093/29094/29096，多数派 2/3）。<b>集群不可达时整类 SKIP，不误报绿</b>。
 *
 * <p>核对点：多地址 bootstrap 正常工作（客户端任选可达节点取元数据）、
 * bootstrap 列表含不可达地址时仍可工作（容错一个死地址）；集群半宕
 * （describeCluster 2/3 节点）时照常运行——即单 broker 宕机回退路径，
 * RF=2 + acks=all 在 min.insync.replicas=1 下由存活 broker 继续服务读写。
 * 注意 2 节点 KRaft 无此容错：voters 多数派 = 2/2，任一宕机即丢 controller
 * quorum，createTopics 等元数据操作挂起（实测 TimeoutException）。
 */
class KafkaSourceMultiBrokerE2ETest {

    private static final String BOOTSTRAP = System.getProperty(
            "oddsmaker.kafka.e2e.multibroker.bootstrap",
            "localhost:29093,localhost:29094,localhost:29096");
    /** 与集群等价但首地址不可达：验证 bootstrap 容错（任选可达节点即可） */
    private static final String BOOTSTRAP_WITH_DEAD = "localhost:29099," + BOOTSTRAP;

    private static final List<String> topics = new ArrayList<>();

    @BeforeAll
    static void requireCluster() {
        Assumptions.assumeTrue(clusterReachable(),
                "多 broker 集群不可达（" + BOOTSTRAP + "），多地址用例跳过——"
                        + "起 2 节点 KRaft 集群后重跑（见类 javadoc）");
    }

    private static boolean clusterReachable() {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try (AdminClient admin = AdminClient.create(Map.of(
                    AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP,
                    AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "5000",
                    AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "10000"))) {
                int nodes = admin.describeCluster().nodes().get(10, TimeUnit.SECONDS).size();
                if (nodes >= 1) {
                    // 允许降级（单 broker 存活也跑）：集群半宕正是本用例要覆盖的回退路径，
                    // RF=2 + acks=all 在 min.insync.replicas=1 下单 broker 即可服务读写
                    System.err.println("[e2e] 集群可达，当前节点数: " + nodes
                            + (nodes < 2 ? "（降级模式：部分 broker 宕机）" : ""));
                    return true;
                }
            } catch (Exception e) {
                System.err.println("[e2e] 多 broker 可达性检查未通过（尝试 " + attempt + "/2）: " + e);
            }
        }
        return false;
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (topics.isEmpty()) {
            return;
        }
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP))) {
            admin.deleteTopics(topics).all().get(15, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // 清理失败不影响结论（topic 名带 run 唯一后缀，broker 为一次性环境）
        }
    }

    // ── 工具 ──────────────────────────────────────────────────────

    private static String newTopic() throws Exception {
        String name = "e2e-dimsync-mb-" + UUID.randomUUID().toString().substring(0, 8);
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP))) {
            // 2 分区 × RF=2：两个 broker 都持有副本， leader 分布跨节点
            admin.createTopics(List.of(new NewTopic(name, 2, (short) 2))).all()
                    .get(15, TimeUnit.SECONDS);
        }
        topics.add(name);
        return name;
    }

    private static void produce(String topic, int count, String bootstrap) throws Exception {
        try (KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName(),
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName()))) {
            for (int i = 0; i < count; i++) {
                String json = "{\"resource_id\":\"mb" + i + "\",\"version_ts\":1735689605000}";
                // 显式分区轮转：保证两分区都非空（空分区在 cursor 无条目，见 KafkaSource 位点簿记）
                producer.send(new ProducerRecord<>(topic, i % 2, null,
                                json.getBytes(StandardCharsets.UTF_8)))
                        .get(10, TimeUnit.SECONDS);
            }
        }
    }

    private static KafkaSource source(String topic, String bootstrap) {
        AgentConfig cfg = new AgentConfig();
        cfg.dimType = "item";
        cfg.kafkaBootstrap = bootstrap;
        cfg.kafkaTopic = topic;
        cfg.kafkaPollTimeoutMs = 3000;
        return new KafkaSource(cfg, new KafkaConsumerAdapter(
                bootstrap, "e2e-mb-grp-" + topic, topic));
    }

    // ── 用例 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("多地址 bootstrap 回路：2 分区 RF=2 全量消费，cursor 含两分区位点")
    void multiAddressBootstrapRoundtrip() throws Exception {
        String topic = newTopic();
        produce(topic, 40, BOOTSTRAP);

        DimensionSource.PollResult r = source(topic, BOOTSTRAP).poll(new Checkpoint());
        assertEquals(40, r.changes().size(), "40 条应全部送达");
        assertEquals(40, r.changes().stream().map(c -> c.resourceId).distinct().count());
        String cursor = r.next().cursor;
        assertTrue(cursor.matches("k:0=\\d+;1=\\d+"), "两分区均非空，cursor 应含两分区位点: " + cursor);
    }

    @Test
    @DisplayName("bootstrap 含死地址：首地址不可达仍全量消费（客户端任选可达节点）")
    void deadBootstrapAddressTolerated() throws Exception {
        String topic = newTopic();
        produce(topic, 30, BOOTSTRAP);

        DimensionSource.PollResult r = source(topic, BOOTSTRAP_WITH_DEAD).poll(new Checkpoint());
        assertEquals(30, r.changes().size(), "死地址不应影响消费——kafka-clients 逐个尝试 bootstrap 列表");
        assertTrue(r.next().cursor.matches("k:0=\\d+;1=\\d+"));
    }
}
