package io.oddsmaker.agent;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewPartitions;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
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
 * kafka source 真实 broker 端到端（需 broker：docker-compose 的 kafka 服务，宿主 29092）。
 * <b>broker 不可达时整类 SKIP（Assumption 失败），不算失败也不误报绿</b>；
 * 离线语义（位点推进/drain/坏消息的纯逻辑）另见 {@link KafkaSourceTest} 假端口用例。
 *
 * <p>覆盖上一批 javadoc 点名的核对点：earliest 起步、断点 seek 续传（checkpoint 自管
 * vs broker group offset）、运行中扩分区（探针证实 partitionsFor 每次强制刷新该 topic
 * 元数据，同实例 t+0 即感知）、坏消息跳过但 offset 前进、drain 上限 100 轮。
 */
class KafkaSourceBrokerE2ETest {

    private static final String BOOTSTRAP =
            System.getProperty("oddsmaker.kafka.e2e.bootstrap", "localhost:29092");

    private static final List<String> topics = new ArrayList<>();
    private static KafkaProducer<byte[], byte[]> producer;

    @BeforeAll
    static void requireBroker() {
        Assumptions.assumeTrue(brokerReachable(),
                "kafka broker 不可达（" + BOOTSTRAP + "），端到端用例跳过——docker compose 起 kafka 服务后重跑");
        producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName(),
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName(),
                ProducerConfig.LINGER_MS_CONFIG, "5",
                ProducerConfig.BATCH_SIZE_CONFIG, "131072"));
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

    @AfterAll
    static void cleanup() throws Exception {
        if (producer != null) {
            producer.close();
        }
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

    // ── 工具 ──────────────────────────────────────────────────────

    private static String newTopic(int partitions) throws Exception {
        String name = "e2e-dimsync-" + UUID.randomUUID().toString().substring(0, 8);
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP))) {
            admin.createTopics(List.of(new NewTopic(name, partitions, (short) 1))).all()
                    .get(10, TimeUnit.SECONDS);
        }
        topics.add(name);
        return name;
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static void produce(String topic, Integer partition, String json) {
        producer.send(new ProducerRecord<>(topic, partition, null, bytes(json)));
    }

    private static void flush() {
        producer.flush();
    }

    private static AgentConfig cfg(String topic) {
        AgentConfig cfg = new AgentConfig();
        cfg.dimType = "item";
        cfg.kafkaBootstrap = BOOTSTRAP;
        cfg.kafkaTopic = topic;
        // 生产默认 3s：冷启动首轮 poll 含 position 校验 + fetch，过短会空轮提前收（已实测）
        cfg.kafkaPollTimeoutMs = 3000;
        return cfg;
    }

    private static KafkaSource source(String topic) {
        // group id 与 topic 绑定（断点续传用例在同名 group 上布 group-offset 假位点做对照）
        String group = "e2e-grp-" + topic;
        return new KafkaSource(cfg(topic), new KafkaConsumerAdapter(BOOTSTRAP, group, topic));
    }

    private static String groupOf(String topic) {
        return "e2e-grp-" + topic;
    }

    private static String json(String resourceId) {
        return "{\"resource_id\":\"" + resourceId + "\",\"version_ts\":1735689605000}";
    }

    private static Checkpoint cursor(String k) {
        Checkpoint cp = new Checkpoint();
        cp.cursor = k;
        return cp;
    }

    // ── 用例 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("① earliest 起步：无断点时从分区 0 消费全部历史记录，cursor 记 next-offset")
    void earliestStartWhenNoCheckpoint() throws Exception {
        String topic = newTopic(1);
        for (int i = 0; i < 5; i++) {
            produce(topic, null, json("m" + i));
        }
        flush();

        KafkaSource s = source(topic);
        DimensionSource.PollResult r = s.poll(new Checkpoint());

        assertEquals(5, r.changes().size());
        assertEquals("m0", r.changes().get(0).resourceId);   // 单分区保序：从 0 起
        assertEquals("m4", r.changes().get(4).resourceId);
        assertEquals("k:0=5", r.next().cursor);
        assertEquals(1735689605000L, r.next().lastEventTs);
        assertEquals("kafka:" + BOOTSTRAP + "/" + topic, s.name());
    }

    @Test
    @DisplayName("② 断点 seek 续传：checkpoint cursor 决定起点（broker group offset 仅作摆设对照），旧断点可重放")
    void checkpointCursorBeatsGroupOffsetAndResumes() throws Exception {
        String topic = newTopic(1);
        for (int i = 0; i < 10; i++) {
            produce(topic, null, json("m" + i));
        }
        flush();

        // 对照组：在同名 consumer group 上故意提交假位点 9——assign+seek 模式根本不读它
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP))) {
            admin.alterConsumerGroupOffsets(groupOf(topic),
                    Map.of(new TopicPartition(topic, 0), new OffsetAndMetadata(9L)))
                    .all().get(10, TimeUnit.SECONDS);
        }

        KafkaSource s = source(topic);
        DimensionSource.PollResult r = s.poll(cursor("k:0=7"));
        assertEquals(3, r.changes().size());                 // 7/8/9 三条，而非从 9 起的 1 条
        assertEquals("m7", r.changes().get(0).resourceId);
        assertEquals("k:0=10", r.next().cursor);

        // 旧断点重放（ReplacingMergeTree 幂等的前提）：同 source 换旧 checkpoint，记录如数重达
        DimensionSource.PollResult replay = s.poll(cursor("k:0=3"));
        assertEquals(7, replay.changes().size());
        assertEquals("m3", replay.changes().get(0).resourceId);
    }

    @Test
    @DisplayName("③ cursor-initial：无断点时按 '0=N' 起步；残缺值（earliest）无害回落空起点")
    void cursorInitialUsedWhenNoCheckpoint() throws Exception {
        String topic = newTopic(1);
        for (int i = 0; i < 10; i++) {
            produce(topic, null, json("m" + i));
        }
        flush();

        AgentConfig c = cfg(topic);
        c.kafkaCursorInitial = "0=6";
        KafkaSource s = new KafkaSource(c, new KafkaConsumerAdapter(BOOTSTRAP, groupOf(topic), topic));
        DimensionSource.PollResult r = s.poll(new Checkpoint());
        assertEquals(4, r.changes().size());
        assertEquals("m6", r.changes().get(0).resourceId);
        assertEquals("k:0=10", r.next().cursor);

        AgentConfig bad = cfg(topic);
        bad.kafkaCursorInitial = "earliest";
        KafkaSource s2 = new KafkaSource(bad, new KafkaConsumerAdapter(BOOTSTRAP, groupOf(topic), topic));
        DimensionSource.PollResult r2 = s2.poll(new Checkpoint());
        assertEquals(10, r2.changes().size());
        assertEquals("k:0=10", r2.next().cursor);
    }

    @Test
    @DisplayName("④ 运行中扩分区：同实例下一轮 assign 感知新分区，未覆盖分区 earliest、cursor 合并三分区位点")
    void partitionExpansionMidRun() throws Exception {
        String topic = newTopic(1);
        for (int i = 0; i < 6; i++) {
            produce(topic, null, json("p0-" + i));
        }
        flush();

        KafkaSource s = source(topic);
        DimensionSource.PollResult first = s.poll(new Checkpoint());
        assertEquals(6, first.changes().size());
        assertEquals("k:0=6", first.next().cursor);

        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP))) {
            admin.createPartitions(Map.of(topic, NewPartitions.increaseTo(3))).all().get(10, TimeUnit.SECONDS);
        }
        // 新分区各有独立日志起点（earliest=0），显式投递定位到新分区
        produce(topic, 1, json("p1-a"));
        produce(topic, 1, json("p1-b"));
        produce(topic, 2, json("p2-a"));
        flush();

        // 元数据最终一致：默认 metadata.max.age=5min 对长驻 consumer 过迟钝（实测 ≥36s 陈旧），
        // adapter 已压到 10s → 空轮重试至感知（生产语义同款：checkpoint 不动，下一轮 poll 重试）
        Checkpoint cp = first.next();
        DimensionSource.PollResult r = s.poll(cp);
        for (int attempt = 0; attempt < 20 && r.changes().isEmpty(); attempt++) {
            Thread.sleep(1000);
            r = s.poll(cp);
        }
        assertEquals(3, r.changes().size());
        // 跨分区无投递顺序保证（ConsumerRecords 按分区分组），按集合核对
        assertEquals(java.util.Set.of("p1-a", "p1-b", "p2-a"),
                r.changes().stream().map(c -> c.resourceId).collect(java.util.stream.Collectors.toSet()));
        assertEquals("k:0=6;1=2;2=1", r.next().cursor);     // 已消费分区不动 + 新分区各自 next-offset

        // 再来一轮：三分区位点全覆盖，无重复无遗漏
        DimensionSource.PollResult settled = s.poll(r.next());
        assertTrue(settled.changes().isEmpty());
        assertEquals("k:0=6;1=2;2=1", settled.next().cursor);
    }

    @Test
    @DisplayName("⑤ 坏消息跳过但 offset 照常前进（毒丸不卡位点）；可用消息照常成变更")
    void poisonMessagesSkippedOffsetsAdvance() throws Exception {
        String topic = newTopic(1);
        produce(topic, null, "not-json{");
        produce(topic, null, "{\"no_resource_id\":\"x\"}");
        produce(topic, null, json("good-1"));
        flush();

        KafkaSource s = source(topic);
        DimensionSource.PollResult r = s.poll(new Checkpoint());
        assertEquals(1, r.changes().size());
        assertEquals("good-1", r.changes().get(0).resourceId);
        assertEquals("k:0=3", r.next().cursor);              // 前两条同样推进
    }

    @Test
    @DisplayName("⑥ drain 上限 100 轮真实路径：总量 > 100轮×max.poll.records 必然分批，余量下轮续传无丢失")
    void drainCapAtOneHundredRounds() throws Exception {
        String topic = newTopic(1);
        int total = 100_200;                                  // > 100 轮 × 1000，上限必然触发
        for (int i = 0; i < total; i++) {
            produce(topic, null, json("r" + i));
        }
        flush();

        KafkaSource s = source(topic);
        DimensionSource.PollResult first = s.poll(new Checkpoint());
        int firstSize = first.changes().size();
        // 真实 fetch 每轮批大小不定（实测 100 轮约 968 条/轮），故断上限界而非精确 100,000；
        // total > 上限 → 首批数学上不可能一次吃光
        assertTrue(firstSize > 0 && firstSize <= 100_000 && firstSize < total);
        assertEquals("r0", first.changes().get(0).resourceId);
        assertEquals("k:0=" + firstSize, first.next().cursor);   // 单分区连续无跳

        // 续传直至吃完：位点严格衔接，无重复无遗漏
        Checkpoint cp = first.next();
        int seen = firstSize;
        for (int guard = 0; guard < 5 && !cp.cursor.equals("k:0=" + total); guard++) {
            DimensionSource.PollResult next = s.poll(cp);
            seen += next.changes().size();
            cp = next.next();
        }
        assertEquals(total, seen);
        assertEquals("k:0=100200", cp.cursor);
    }
}
