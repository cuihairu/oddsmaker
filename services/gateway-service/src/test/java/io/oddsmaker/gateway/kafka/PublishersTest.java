package io.oddsmaker.gateway.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.common.model.Event;
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

    @BeforeEach
    void setUp() throws Exception {
        avroPublisher = new AvroPublisher(new ObjectMapper());
        Schema schema = new Schema.Parser().parse(
            new String(new ClassPathResource("schemas/oddsmaker-event.avsc").getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8));
        ReflectionTestUtils.setField(avroPublisher, "schema", schema);
        ReflectionTestUtils.setField(avroPublisher, "producer", producer);
        ReflectionTestUtils.setField(avroPublisher, "eventsTopic", "oddsmaker.events_raw");
        when(producer.send(any(ProducerRecord.class))).thenReturn(future);

        dlqPublisher = new DlqPublisher();
        ReflectionTestUtils.setField(dlqPublisher, "producer", producer);
        ReflectionTestUtils.setField(dlqPublisher, "dlqTopic", "oddsmaker.deadletter");
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
        verify(producer).send(captor.capture());
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
            "oddsmaker.events_raw".equals(r.topic())));
        clearInvocations(producer);

        // 名称包含 risk 的埋点同样不改道
        Event named = fullEvent();
        named.eventType = "business";
        named.eventName = "user_risk_signal";
        avroPublisher.publish(named);
        verify(producer).send(org.mockito.ArgumentMatchers.argThat(r ->
            "oddsmaker.events_raw".equals(r.topic()) && "user_risk_signal".equals(
                ((org.apache.avro.generic.GenericRecord) r.value()).get("event_name"))));
        clearInvocations(producer);

        // null props → "{}"
        Event noProps = fullEvent();
        noProps.props = null;
        avroPublisher.publish(noProps);
        verify(producer).send(org.mockito.ArgumentMatchers.argThat(r ->
            "{}".equals(String.valueOf(((org.apache.avro.generic.GenericRecord) r.value()).get("props_json")))));
        clearInvocations(producer);

        // 序列化异常 → "{}"（ObjectMapper mock 抛出）
        ObjectMapper throwing = mock(ObjectMapper.class);
        when(throwing.writeValueAsString(any())).thenThrow(new RuntimeException("boom"));
        AvroPublisher bad = new AvroPublisher(throwing);
        ReflectionTestUtils.setField(bad, "schema",
            ReflectionTestUtils.getField(avroPublisher, "schema"));
        ReflectionTestUtils.setField(bad, "producer", producer);
        ReflectionTestUtils.setField(bad, "eventsTopic", "oddsmaker.events_raw");
        bad.publish(fullEvent());
        verify(producer).send(org.mockito.ArgumentMatchers.argThat(r ->
            "{}".equals(String.valueOf(((org.apache.avro.generic.GenericRecord) r.value()).get("props_json")))));
        clearInvocations(producer);

        // tsServer null 分支
        Event nullTs = fullEvent();
        nullTs.tsServer = null;
        avroPublisher.publish(nullTs);
        verify(producer).send(org.mockito.ArgumentMatchers.argThat(r ->
            ((org.apache.avro.generic.GenericRecord) r.value()).get("ts_server") == null));
    }

    @Test
    @DisplayName("DLQ 发布：载荷组装三分支与字符串转义")
    void dlqPayloadAssembly() {
        dlqPublisher.publish("evt_1", "invalid_schema", "{\"a\":1}");
        verify(producer).send(org.mockito.ArgumentMatchers.argThat(r ->
            r.topic().equals("oddsmaker.deadletter")
                && String.valueOf(r.value()).contains("\"reason\":\"invalid_schema\"")
                && String.valueOf(r.value()).endsWith("\"raw\":{\"a\":1}}")));

        // null key 与 null raw
        dlqPublisher.publish(null, "bad", null);
        verify(producer).send(org.mockito.ArgumentMatchers.argThat(r ->
            String.valueOf(r.value()).startsWith("{\"event_id\":\"\",")
                && String.valueOf(r.value()).contains("\"raw\":null")));

        // 非 JSON raw → 字符串转义（" → \"，\ → \\）
        dlqPublisher.publish("evt_2", "boom", "plain \"text\" \\ end");
        verify(producer).send(org.mockito.ArgumentMatchers.argThat(r ->
            String.valueOf(r.value()).contains("\"raw\":\"plain \\\"text\\\" \\\\ end\"")));

        // raw 以 [ 开头（JSON 数组）同样原样嵌入
        dlqPublisher.publish("evt_3", "boom", "[1,2]");
        verify(producer).send(org.mockito.ArgumentMatchers.argThat(r ->
            String.valueOf(r.value()).endsWith("\"raw\":[1,2]}")));
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
        AvroPublisher avro = new AvroPublisher(new ObjectMapper());
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
