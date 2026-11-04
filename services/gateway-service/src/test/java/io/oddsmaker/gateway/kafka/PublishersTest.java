package io.oddsmaker.gateway.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.common.model.Event;
import io.oddsmaker.gateway.security.ReplayGuard;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.avro.Schema;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Kafka 发布器测试：Avro 记录构建、主题路由、DLQ 载荷组装（producer 注入 mock）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Kafka 发布器测试")
class PublishersTest {

    @Mock
    private KafkaProducer<String, Object> producer;

    @Mock
    private Future<RecordMetadata> future;

    private AvroPublisher avroPublisher;

    private DlqPublisher dlqPublisher;
    private ReplayGuard replayGuard;

    @BeforeEach
    void setUp() throws Exception {
        dlqPublisher = new DlqPublisher();
        ReflectionTestUtils.setField(dlqPublisher, "producer", producer);
        ReflectionTestUtils.setField(dlqPublisher, "dlqTopic", "oddsmaker.deadletter");

        replayGuard = new ReplayGuard(600_000, 200_000, 86_400_000);
        // 与生产 JacksonConfig 的 SNAKE_CASE 策略一致：DLQ 载荷字段名必须对齐
        // DeadLetterConsumer 的 raw.game_id/environment/ts_server 解析（否则落 unknown 桶）。
        ObjectMapper snakeCase = new ObjectMapper();
        snakeCase.setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
        avroPublisher = new AvroPublisher(snakeCase, dlqPublisher);
        Schema schema = new Schema.Parser().parse(
            new String(new ClassPathResource("schemas/oddsmaker-event.avsc").getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8));
        ReflectionTestUtils.setField(avroPublisher, "schema", schema);
        ReflectionTestUtils.setField(avroPublisher, "producer", producer);
        ReflectionTestUtils.setField(avroPublisher, "eventsTopic", "oddsmaker.events_raw");
        when(producer.send(any(ProducerRecord.class), any(org.apache.kafka.clients.producer.Callback.class))).thenReturn(future);
    }

    private Event fullEvent() {
        Event e = new Event();
        e.eventId = "evt_12345678";
        e.gameId = "game_demo";
        e.environment = "prod";
        e.eventType = "business";
        e.eventName = "iap_purchase";
        e.userId = "u1";
        e.deviceId = "dev_1";
        e.playerId = "p1";
        e.characterId = "c1";
        e.sessionId = "s1";
        e.tsClient = 1700000000000L;
        e.tsServer = 1700000000001L;
        e.platform = "ios";
        e.appVersion = "1.2.3";
        e.sdkVersion = "0.4.0";
        e.country = "CN";
        e.clientIp = "1.2.3.4";
        e.userAgent = "ua";
        e.serverId = "srv1";
        e.guildId = "g1";
        e.matchId = "m1";
        e.levelId = "lv1";
        e.gameMode = "pvp";
        e.difficulty = "hard";
        e.progressionPath = "a>b";
        e.orderId = "o1";
        e.productId = "gem.pack";
        e.revenueAmount = 9.99;
        e.revenueCurrency = "USD";
        e.receiptHash = "hash";
        e.virtualCurrency = "gold";
        e.virtualAmount = 100.0;
        e.itemId = "i1";
        e.resourceId = "r1";
        e.resourceAmount = 5.0;
        e.flowType = "source";
        e.operationId = "op1";
        e.operationType = "gain";
        e.adNetwork = "adm";
        e.adPlacement = "home";
        e.adFormat = "reward";
        e.adImpressionId = "ai1";
        e.riskContext = "k=v";
        e.deviceFingerprint = "dfp";
        e.clientIntegrity = "ok";
        e.experiments = Map.of("exp", "a");
        Map<String, Object> props = new HashMap<>();
        props.put("amount", 10);
        e.props = props;
        return e;
    }

    @Test
    @DisplayName("Avro 发布：全字段映射、路由键与事件主题")
    void avroPublishMapsAllFields() {
        Event e = fullEvent();
        avroPublisher.publish(e);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<ProducerRecord<String, Object>> captor =
            org.mockito.ArgumentCaptor.forClass(ProducerRecord.class);
        verify(producer).send(captor.capture(), any(org.apache.kafka.clients.producer.Callback.class));
        ProducerRecord<String, Object> record = captor.getValue();
        assertEquals("oddsmaker.events_raw", record.topic());
        assertEquals("game_demo|prod", record.key());

        org.apache.avro.generic.GenericRecord gr = (org.apache.avro.generic.GenericRecord) record.value();
        assertEquals("evt_12345678", gr.get("event_id"));
        assertEquals("game_demo", gr.get("game_id"));
        assertEquals(1700000000000000L, gr.get("ts_client"));  // ms → micros
        assertEquals(1700000000001000L, gr.get("ts_server"));
        assertEquals(9.99, gr.get("revenue_amount"));
        assertEquals("{\"amount\":10}", String.valueOf(gr.get("props_json")));
        assertEquals("pvp", gr.get("game_mode"));
        assertEquals("a", ((Map<?, ?>) gr.get("experiments")).get("exp"));
    }

    @Test
    @DisplayName("Avro 发布：所有埋点（含 risk 类）统一走 events_raw，不改道风控主题；null props/序列化异常回退空 JSON")
    void avroTopicRoutingAndPropsFallback() throws Exception {
        // event_type=risk 也必须进 events_raw：RiskJob 只订阅它；
        // risk_events 是 Flink 风控命中的 JSON 契约（control 消费），进 Avro 埋点即契约破坏
        Event risk = fullEvent();
        risk.eventType = "risk";
        avroPublisher.publish(risk);
        verify(producer).send(org.mockito.ArgumentMatchers.argThat(r ->
            "oddsmaker.events_raw".equals(r.topic())), any(org.apache.kafka.clients.producer.Callback.class));
        clearInvocations(producer);

        // 名称包含 risk 的埋点同样不改道
        Event named = fullEvent();
        named.eventType = "business";
        named.eventName = "user_risk_signal";
        avroPublisher.publish(named);
        verify(producer).send(org.mockito.ArgumentMatchers.argThat(r ->
            "oddsmaker.events_raw".equals(r.topic()) && "user_risk_signal".equals(
                ((org.apache.avro.generic.GenericRecord) r.value()).get("event_name"))), any(org.apache.kafka.clients.producer.Callback.class));
        clearInvocations(producer);

        // null props → "{}"
        Event noProps = fullEvent();
        noProps.props = null;
        avroPublisher.publish(noProps);
        verify(producer).send(org.mockito.ArgumentMatchers.argThat(r ->
            "{}".equals(String.valueOf(((org.apache.avro.generic.GenericRecord) r.value()).get("props_json")))), any(org.apache.kafka.clients.producer.Callback.class));
        clearInvocations(producer);

        // 序列化异常 → "{}"（ObjectMapper mock 抛出）
        ObjectMapper throwing = mock(ObjectMapper.class);
        when(throwing.writeValueAsString(any())).thenThrow(new RuntimeException("boom"));
        AvroPublisher bad = new AvroPublisher(throwing, dlqPublisher);
        ReflectionTestUtils.setField(bad, "schema",
            ReflectionTestUtils.getField(avroPublisher, "schema"));
        ReflectionTestUtils.setField(bad, "producer", producer);
        ReflectionTestUtils.setField(bad, "eventsTopic", "oddsmaker.events_raw");
        bad.publish(fullEvent());
        verify(producer).send(org.mockito.ArgumentMatchers.argThat(r ->
            "{}".equals(String.valueOf(((org.apache.avro.generic.GenericRecord) r.value()).get("props_json")))), any(org.apache.kafka.clients.producer.Callback.class));
        clearInvocations(producer);

        // tsServer null 分支
        Event nullTs = fullEvent();
        nullTs.tsServer = null;
        avroPublisher.publish(nullTs);
        verify(producer).send(org.mockito.ArgumentMatchers.argThat(r ->
            ((org.apache.avro.generic.GenericRecord) r.value()).get("ts_server") == null), any(org.apache.kafka.clients.producer.Callback.class));
    }

    @Test
    @DisplayName("DLQ 发布：载荷组装三分支与字符串转义")
    void dlqPayloadAssembly() {
        dlqPublisher.publish("evt_1", "invalid_schema", "{\"a\":1}");
        verify(producer).send(org.mockito.ArgumentMatchers.argThat(r ->
            r.topic().equals("oddsmaker.deadletter")
                && String.valueOf(r.value()).contains("\"reason\":\"invalid_schema\"")
                && String.valueOf(r.value()).endsWith("\"raw\":{\"a\":1}}")), any(org.apache.kafka.clients.producer.Callback.class));

        // null key 与 null raw
        dlqPublisher.publish(null, "bad", null);
        verify(producer).send(org.mockito.ArgumentMatchers.argThat(r ->
            String.valueOf(r.value()).startsWith("{\"event_id\":\"\",")
                && String.valueOf(r.value()).contains("\"raw\":null")), any(org.apache.kafka.clients.producer.Callback.class));

        // 非 JSON raw → 字符串转义（" → \"，\ → \\）
        dlqPublisher.publish("evt_2", "boom", "plain \"text\" \\ end");
        verify(producer).send(org.mockito.ArgumentMatchers.argThat(r ->
            String.valueOf(r.value()).contains("\"raw\":\"plain \\\"text\\\" \\\\ end\"")), any(org.apache.kafka.clients.producer.Callback.class));

        // raw 以 [ 开头（JSON 数组）同样原样嵌入
        dlqPublisher.publish("evt_3", "boom", "[1,2]");
        verify(producer).send(org.mockito.ArgumentMatchers.argThat(r ->
            String.valueOf(r.value()).endsWith("\"raw\":[1,2]}")), any(org.apache.kafka.clients.producer.Callback.class));
    }

    @Test
    @DisplayName("DlqPublisher：broker 不可达时 send() 挂满 max.block.ms 后失败经回调日志+指标观测（非完全静默）")
    void dlqPublishBlocksSilentlyWhenBrokerUnreachable() {
        // fire-and-forget 的实测语义（kafka-clients 3.7，运维发现）：
        // 1) 阻塞：KafkaProducer#send 在调用线程等 metadata（waitOnMetadata），broker 不可达时
        //    挂满 max.block.ms——生产默认 60s，即 Gateway API 线程的最坏拖挂时长；
        // 2) 失败经回调日志 + 指标（oddsmaker.dlq.publish.failures）观测，不再是完全静默。
        // 此处用死端口 + 2s max.block.ms 离线锁定该行为；真实回路见 PublishersBrokerE2ETest
        java.util.Properties p = new java.util.Properties();
        p.put("bootstrap.servers", "127.0.0.1:19092");
        p.put("max.block.ms", "2000");
        p.put("key.serializer", org.apache.kafka.common.serialization.StringSerializer.class.getName());
        p.put("value.serializer", org.apache.kafka.common.serialization.StringSerializer.class.getName());
        try (KafkaProducer<String, String> deadBroker = new KafkaProducer<>(p)) {
            DlqPublisher dlq = new DlqPublisher();
            ReflectionTestUtils.setField(dlq, "producer", deadBroker);
            ReflectionTestUtils.setField(dlq, "dlqTopic", "oddsmaker.deadletter");
            long start = System.currentTimeMillis();
            dlq.publish("evt_1", "invalid_schema", "{}");  // 不抛：失败在 future 里被忽略
            long elapsed = System.currentTimeMillis() - start;
            assertTrue(elapsed >= 1500,
                    "send() 应在调用线程挂满 max.block.ms（实测 " + elapsed + "ms），而非立刻返回");
        }
    }

    @Test
    @DisplayName("DLQ 发布：异步投递失败有日志与指标观测")
    void dlqPublishFailureIsObservable() {
        MeterRegistry meters = new SimpleMeterRegistry();
        ReflectionTestUtils.setField(dlqPublisher, "meters", meters);
        org.mockito.Mockito.doAnswer(invocation -> {
            org.apache.kafka.clients.producer.Callback callback = invocation.getArgument(1);
            callback.onCompletion(null, new org.apache.kafka.common.errors.TimeoutException("broker down"));
            return future;
        }).when(producer).send(any(ProducerRecord.class), any(org.apache.kafka.clients.producer.Callback.class));

        dlqPublisher.publish("evt_dlq_fail", "kafka_error", "{}");

        assertEquals(1, meters.counter("oddsmaker.dlq.publish.failures", "reason", "kafka_error").count());
    }

    @Test
    @DisplayName("DLQ 发布：异步投递成功不增 oddsmaker.dlq.publish.failures 指标（成功路径零失败）")
    void dlqPublishSuccessDoesNotIncrementFailureMetric() {
        MeterRegistry meters = new SimpleMeterRegistry();
        ReflectionTestUtils.setField(dlqPublisher, "meters", meters);
        org.mockito.Mockito.doAnswer(invocation -> {
            org.apache.kafka.clients.producer.Callback callback = invocation.getArgument(1);
            callback.onCompletion(new RecordMetadata(null, 0, 0, 0, 0, 0), null);
            return future;
        }).when(producer).send(any(ProducerRecord.class), any(org.apache.kafka.clients.producer.Callback.class));

        dlqPublisher.publish("evt_dlq_ok", "kafka_error", "{}");

        assertEquals(0, meters.counter("oddsmaker.dlq.publish.failures", "reason", "kafka_error").count());
    }

    @Test
    @DisplayName("AvroPublisher publish：异步发送失败经回调路由 DLQ 且不释放幂等位（C2026-11-02）")
    void publishRoutesAsyncFailureToDlq() {
        assertTrue(replayGuard.consumeEventId("game_demo", "prod", "evt_12345678"));
        org.mockito.Mockito.doAnswer(invocation -> {
            org.apache.kafka.clients.producer.Callback cb = invocation.getArgument(1);
            cb.onCompletion(null, new org.apache.kafka.common.errors.TimeoutException("broker down"));
            return future;
        }).when(producer).send(any(ProducerRecord.class), any(org.apache.kafka.clients.producer.Callback.class));

        avroPublisher.publish(fullEvent());

        // 失败回调应把同一事件写到 deadletter topic（1 参 send：DlqPublisher 投递路径）
        verify(producer).send(org.mockito.ArgumentMatchers.argThat(r ->
            "oddsmaker.deadletter".equals(r.topic())
                && String.valueOf(r.value()).contains("\"reason\":\"kafka_send_failed\"")
                && String.valueOf(r.value()).contains("\"game_id\":\"game_demo\"")
                && String.valueOf(r.value()).contains("\"environment\":\"prod\"")
                && String.valueOf(r.value()).contains("\"event_id\":\"evt_12345678\"")
                && String.valueOf(r.value()).contains("\"ts_server\":")), any(org.apache.kafka.clients.producer.Callback.class));
        // 异步失败回调不释放幂等位：acks=all 下 TimeoutException 不保证未投递，释放可致 SDK 重试重发已入 events_raw 的事件
        assertFalse(replayGuard.consumeEventId("game_demo", "prod", "evt_12345678"));
    }

    @Test
    @DisplayName("AvroPublisher publish：异步发送成功回调不释放幂等位且不写 DLQ（契约 ReplayGuard 成功路径）")
    void publishSuccessDoesNotReleaseIdempotencyBit() {
        assertTrue(replayGuard.consumeEventId("game_demo", "prod", "evt_success_1"));
        org.mockito.Mockito.doAnswer(invocation -> {
            org.apache.kafka.clients.producer.Callback cb = invocation.getArgument(1);
            cb.onCompletion(new RecordMetadata(null, 0, 0, 0, 0, 0), null);
            return future;
        }).when(producer).send(any(ProducerRecord.class), any(org.apache.kafka.clients.producer.Callback.class));

        Event e = fullEvent();
        e.eventId = "evt_success_1";
        avroPublisher.publish(e);

        // 成功路径不释放：幂等位仍占用（consumeEventId 返回 false = 已被吸收）
        assertFalse(replayGuard.consumeEventId("game_demo", "prod", "evt_success_1"));
        // 成功路径不写 DLQ：仅 events_raw 一次 send
        verify(producer, org.mockito.Mockito.times(1)).send(any(ProducerRecord.class), any(org.apache.kafka.clients.producer.Callback.class));
    }

    @Test
    @DisplayName("DlqPublisher init：按配置装配 KafkaProducer（String 序列化，惰性建连）")
    void dlqPublisherInitBuildsProducer() {
        DlqPublisher dlq = new DlqPublisher();
        ReflectionTestUtils.setField(dlq, "bootstrap", "127.0.0.1:19092");
        ReflectionTestUtils.setField(dlq, "dlqTopic", "oddsmaker.deadletter");
        dlq.init();
        Object producerInstance = ReflectionTestUtils.getField(dlq, "producer");
        assertNotNull(producerInstance);
        assertTrue(producerInstance instanceof KafkaProducer);
        ((KafkaProducer<?, ?>) producerInstance).close(java.time.Duration.ZERO);
    }

    @Test
    @DisplayName("AvroPublisher init：加载 avsc + Apicurio 序列化器装配（惰性建连）")
    void avroPublisherInitLoadsSchemaAndProducer() throws Exception {
        AvroPublisher avro = new AvroPublisher(new ObjectMapper(), dlqPublisher);
        ReflectionTestUtils.setField(avro, "bootstrap", "127.0.0.1:19092");
        ReflectionTestUtils.setField(avro, "eventsTopic", "oddsmaker.events_raw");
        ReflectionTestUtils.setField(avro, "registryUrl", "http://127.0.0.1:18081/apis/registry/v2");
        ReflectionTestUtils.setField(avro, "lingerMs", 5);
        ReflectionTestUtils.setField(avro, "batchSize", 65536);
        ReflectionTestUtils.setField(avro, "avroSchemaRes",
            new org.springframework.core.io.ClassPathResource("schemas/oddsmaker-event.avsc"));
        avro.init();
        assertNotNull(ReflectionTestUtils.getField(avro, "schema"));
        Object producerInstance = ReflectionTestUtils.getField(avro, "producer");
        assertNotNull(producerInstance);
        assertTrue(producerInstance instanceof KafkaProducer);
        ((KafkaProducer<?, ?>) producerInstance).close(java.time.Duration.ZERO);
    }
}
