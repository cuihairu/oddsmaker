package io.oddsmaker.gateway.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.common.model.Event;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kafka 发布器真实 broker 端到端（apache/kafka:3.7.0 宿主 29092 + Apicurio Registry 宿主 18081）。
 * <b>broker 不可达时整类 SKIP（Assumption 失败），不算失败也不误报绿</b>；
 * 离线语义（记录构建/路由键/载荷组装）另见 {@link PublishersTest} mock 用例。
 *
 * <p>核对点：DlqPublisher 真实投递回路（wire 上 key/payload 逐字节精确）、
 * AvroPublisher 经 Apicurio 自动注册 schema 的 Avro 全回路（AvroKafkaDeserializer 消费回读字段）、
 * registry 不可达时序列化在调用线程同步失败（Gateway API 线程不会被静默吞掉）。
 * broker 不可达时 send() 在调用线程挂满 max.block.ms（生产默认 60s）后静默吞掉失败
 * （ApiException 进 FutureFailure，DlqPublisher 忽略 future）——该路径离线可测，
 * 见 PublishersTest#dlqPublishBlocksSilentlyWhenBrokerUnreachable。
 */
class PublishersBrokerE2ETest {

    private static final String BOOTSTRAP =
            System.getProperty("oddsmaker.kafka.e2e.bootstrap", "localhost:29092");
    private static final String REGISTRY_URL =
            System.getProperty("oddsmaker.registry.e2e.url", "http://localhost:18081/apis/registry/v2");

    /** 本机无监听的死端口：连接立刻被拒，用于 registry 不可达路径（不依赖停容器） */
    private static final String DEAD_REGISTRY_URL = "http://127.0.0.1:19081/apis/registry/v2";

    private static final List<String> topics = new ArrayList<>();
    private static final List<KafkaProducer<?, ?>> producers = new ArrayList<>();

    @BeforeAll
    static void requireBroker() {
        Assumptions.assumeTrue(brokerReachable(),
                "kafka broker 不可达（" + BOOTSTRAP + "），发布器端到端用例跳过——docker 起 kafka 后重跑");
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

    private static boolean registryReachable() {
        try {
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create(REGISTRY_URL + "/system/info")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    @AfterAll
    static void cleanup() throws Exception {
        for (KafkaProducer<?, ?> p : producers) {
            p.close(Duration.ZERO);
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

    private static String newTopic() throws Exception {
        String name = "e2e-gw-pub-" + UUID.randomUUID().toString().substring(0, 8);
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP))) {
            admin.createTopics(List.of(new NewTopic(name, 1, (short) 1))).all()
                    .get(10, TimeUnit.SECONDS);
        }
        topics.add(name);
        return name;
    }

    /** 独立 topic 从头消费直到凑满 expected 条或 15s 截止（fire-and-forget 的异步送达也覆盖） */
    @SuppressWarnings("unchecked")
    private static <V> List<ConsumerRecord<String, V>> drain(String topic, int expected,
                                                              String valueDeserializer,
                                                              Map<String, Object> extraProps) {
        Map<String, Object> props = new HashMap<>();
        props.put("bootstrap.servers", BOOTSTRAP);
        props.put("key.deserializer", StringDeserializer.class.getName());
        props.put("value.deserializer", valueDeserializer);
        props.put("enable.auto.commit", "false");
        props.putAll(extraProps);
        try (KafkaConsumer<String, V> consumer = new KafkaConsumer<>(props)) {
            List<TopicPartition> tps = consumer.partitionsFor(topic).stream()
                    .map(pi -> new TopicPartition(topic, pi.partition())).toList();
            consumer.assign(tps);
            consumer.seekToBeginning(tps);
            List<ConsumerRecord<String, V>> out = new ArrayList<>();
            long deadline = System.currentTimeMillis() + 15_000;
            while (out.size() < expected && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, V> r : consumer.poll(Duration.ofMillis(1000))) {
                    out.add(r);
                }
            }
            return out;
        }
    }

    private static List<ConsumerRecord<String, String>> consumeStrings(String topic, int expected) {
        return drain(topic, expected, StringDeserializer.class.getName(), Map.of());
    }

    private static List<ConsumerRecord<String, GenericRecord>> consumeAvro(String topic, int expected) {
        return drain(topic, expected, "io.apicurio.registry.serde.avro.AvroKafkaDeserializer",
                Map.of("apicurio.registry.url", REGISTRY_URL));
    }

    private static Event fullEvent() {
        // Avro schema 的 REQUIRED 字段（event_id/game_id/environment/event_name/device_id/ts_client）
        // 在生产链路由 BatchController 前置校验（invalid_schema/invalid_timestamp 拒绝）保证非空——
        // 真实 Avro 序列化器会校验 null（mock 不会），此处必须给全，等价于通过校验的事件
        Event e = new Event();
        e.eventId = "evt_e2e_0001";
        e.gameId = "game_e2e";
        e.environment = "prod";
        e.eventType = "business";
        e.eventName = "iap_purchase";
        e.userId = "u1";
        e.deviceId = "dev_e2e_1";
        e.tsClient = 1700000000000L;
        e.tsServer = 1700000000001L;
        e.platform = "ios";
        e.revenueAmount = 9.99;
        e.revenueCurrency = "USD";
        Map<String, Object> props = new HashMap<>();
        props.put("amount", 10);
        e.props = props;
        return e;
    }

    private static void registerForCleanup(KafkaProducer<?, ?> p) {
        producers.add(p);
        p.close(Duration.ZERO);
    }

    // ── DlqPublisher ──────────────────────────────────────────────

    @Test
    @DisplayName("DLQ 真实投递回路：broker 建连 + wire 载荷/key 逐字节核对")
    void dlqPublishRealBrokerRoundtrip() throws Exception {
        String topic = newTopic();
        DlqPublisher dlq = new DlqPublisher();
        ReflectionTestUtils.setField(dlq, "bootstrap", BOOTSTRAP);
        ReflectionTestUtils.setField(dlq, "dlqTopic", topic);
        dlq.init();  // 真实建连（此前仅 mock/惰性建连覆盖）

        // 三种载荷形态：JSON 原样嵌入 / null key+null raw / 非 JSON 转义为字符串
        dlq.publish("evt_1", "invalid_schema", "{\"a\":1}");
        dlq.publish(null, "bad", null);
        dlq.publish("evt_2", "boom", "plain");

        List<ConsumerRecord<String, String>> records = consumeStrings(topic, 3);
        assertEquals(3, records.size(), "DLQ 三条应全部送达");
        // 同 key 同分区（单分区 topic），offset 单调即可
        assertEquals("{\"event_id\":\"evt_1\",\"reason\":\"invalid_schema\",\"raw\":{\"a\":1}}",
                records.get(0).value());
        assertEquals("evt_1", records.get(0).key());
        assertEquals("{\"event_id\":\"\",\"reason\":\"bad\",\"raw\":null}", records.get(1).value());
        assertTrue(records.get(1).key() == null, "null key 应以 kafka null key 上 wire（非空串）");
        assertEquals("{\"event_id\":\"evt_2\",\"reason\":\"boom\",\"raw\":\"plain\"}", records.get(2).value());
        assertEquals("evt_2", records.get(2).key());
        for (ConsumerRecord<String, String> r : records) {
            assertEquals(topic, r.topic());
        }

        KafkaProducer<?, ?> p = (KafkaProducer<?, ?>) ReflectionTestUtils.getField(dlq, "producer");
        registerForCleanup(p);
    }

    // ── AvroPublisher ─────────────────────────────────────────────

    @Test
    @DisplayName("Avro 全回路：Apicurio 自动注册 schema + AvroKafkaDeserializer 消费回读字段")
    void avroPublishRealBrokerAndRegistryRoundtrip() throws Exception {
        Assumptions.assumeTrue(registryReachable(),
                "apicurio registry 不可达（" + REGISTRY_URL + "），Avro 回路用例跳过——docker 起 registry 后重跑");

        String topic = newTopic();
        AvroPublisher avro = new AvroPublisher(new ObjectMapper());
        ReflectionTestUtils.setField(avro, "bootstrap", BOOTSTRAP);
        ReflectionTestUtils.setField(avro, "eventsTopic", topic);
        ReflectionTestUtils.setField(avro, "registryUrl", REGISTRY_URL);
        ReflectionTestUtils.setField(avro, "lingerMs", 5);
        ReflectionTestUtils.setField(avro, "batchSize", 65536);
        ReflectionTestUtils.setField(avro, "avroSchemaRes",
                new ClassPathResource("schemas/oddsmaker-event.avsc"));
        avro.init();  // 真实建连 + Apicurio serde 装配（auto-register 首发注册 schema）

        Event e = fullEvent();
        e.eventId = "evt_e2e_avro_1";
        // acks=all：送达被 broker 确认才算通过
        avro.publish(e).get(10, TimeUnit.SECONDS);
        e.eventId = "evt_e2e_avro_2";
        avro.publish(e).get(10, TimeUnit.SECONDS);

        List<ConsumerRecord<String, GenericRecord>> records = consumeAvro(topic, 2);
        assertEquals(2, records.size(), "Avro 两条应全部送达");
        assertEquals("game_e2e|prod", records.get(0).key());
        GenericRecord gr = records.get(0).value();
        assertNotNull(gr, "AvroKafkaDeserializer 应还原出 GenericRecord（经 registry 反查 schema）");
        // Avro GenericRecord 的 string 字段回读为 Utf8，经 String.valueOf 归一断言
        assertEquals("evt_e2e_avro_1", String.valueOf(gr.get("event_id")));
        assertEquals("game_e2e", String.valueOf(gr.get("game_id")));
        assertEquals(1700000000000000L, gr.get("ts_client"));   // ms → micros
        assertEquals(1700000000001000L, gr.get("ts_server"));
        assertEquals(9.99, gr.get("revenue_amount"));
        assertEquals("{\"amount\":10}", String.valueOf(gr.get("props_json")));
        assertEquals("evt_e2e_avro_2", String.valueOf(records.get(1).value().get("event_id")));
        assertEquals(topic, records.get(0).topic());

        registerForCleanup((KafkaProducer<?, ?>) ReflectionTestUtils.getField(avro, "producer"));
    }

    @Test
    @DisplayName("registry 不可达：Avro 序列化在调用线程同步抛出（API 线程可见失败）")
    void avroPublishFailsFastWhenRegistryDown() throws Exception {
        String topic = newTopic();
        AvroPublisher avro = new AvroPublisher(new ObjectMapper());
        Schema schema = new Schema.Parser().parse(new String(
                new ClassPathResource("schemas/oddsmaker-event.avsc").getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8));
        ReflectionTestUtils.setField(avro, "schema", schema);
        ReflectionTestUtils.setField(avro, "eventsTopic", topic);
        // broker 可达（metadata 解析通过），value 序列化器指向死端口的 registry：
        // KafkaProducer#doSend 先等 metadata、后串行化 value——序列化发生在调用线程
        Properties p = new Properties();
        p.put("bootstrap.servers", BOOTSTRAP);
        p.put("key.serializer", StringSerializer.class.getName());
        p.put("value.serializer", "io.apicurio.registry.serde.avro.AvroKafkaSerializer");
        p.put("apicurio.registry.url", DEAD_REGISTRY_URL);
        p.put("apicurio.registry.auto-register", true);
        KafkaProducer<String, Object> deadRegistry = new KafkaProducer<>(p);
        ReflectionTestUtils.setField(avro, "producer", deadRegistry);

        // kafka-clients 在调用线程执行 valueSerializer.serialize：registry 连接被拒立即同步抛出
        // （实测 io.apicurio.registry.rest.client.exception.RestClientException），不会进 sender
        // 线程被吞——Gateway 侧表现为 /v1/batch 请求失败，可观测
        long start = System.currentTimeMillis();
        assertThrows(io.apicurio.registry.rest.client.exception.RestClientException.class,
                () -> avro.publish(fullEvent()));
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed < 10_000, "registry 连接被拒应快速失败（实测 " + elapsed + "ms），不该长阻塞");
        registerForCleanup(deadRegistry);
    }
}
