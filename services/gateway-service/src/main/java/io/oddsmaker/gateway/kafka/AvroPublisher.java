package io.oddsmaker.gateway.kafka;

import io.oddsmaker.common.model.Event;
import io.oddsmaker.gateway.config.TrustPolicy;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Future;

/**
 * Avro event publisher with database-per-game routing support.
 */
@Component
public class AvroPublisher {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AvroPublisher.class);

    @Value("${oddsmaker.kafka.bootstrap}")
    private String bootstrap;

    @Value("${oddsmaker.kafka.topic.events:oddsmaker.events_raw}")
    private String eventsTopic;

    @Value("${oddsmaker.kafka.registry-url}")
    private String registryUrl;

    @Value("${oddsmaker.kafka.producer.linger-ms:5}")
    private int lingerMs;

    @Value("${oddsmaker.kafka.producer.batch-size:65536}")
    private int batchSize;

    @Value("classpath:schemas/oddsmaker-event.avsc")
    private Resource avroSchemaRes;

    private KafkaProducer<String, Object> producer;
    private Schema schema;
    private final ObjectMapper om;
    private final DlqPublisher dlq;
    public AvroPublisher(ObjectMapper om, DlqPublisher dlq) {
        this.om = om;
        this.dlq = dlq;
    }

    @PostConstruct
    public void init() throws Exception {
        try (InputStream is = avroSchemaRes.getInputStream()) {
            String s = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            schema = new Schema.Parser().parse(s);
        }
        Properties p = new Properties();
        p.put("bootstrap.servers", bootstrap);
        p.put("acks", "all");
        p.put("linger.ms", Integer.toString(lingerMs));
        p.put("batch.size", Integer.toString(batchSize));
        p.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        // Apicurio Avro 序列化器
        p.put("value.serializer", "io.apicurio.registry.serde.avro.AvroKafkaSerializer");
        p.put("apicurio.registry.url", registryUrl);
        p.put("apicurio.registry.auto-register", true);
        p.put("apicurio.registry.find-latest", true);
        // subject 策略: topic-value
        p.put("apicurio.registry.serde.subject","topic-value");
        producer = new KafkaProducer<>(p);
    }

    /**
     * Publish event to events_raw.
     * Key format: "game_id|environment" for routing partitioning.
     *
     * 契约（勿改道）：所有客户端埋点——含 event_type=risk——统一进 events_raw。
     * 之前 risk 类埋点被改道到 oddsmaker.risk_events，造成双重断裂：
     * 1) RiskJob 只订阅 events_raw，风控引擎收不到这批埋点；
     * 2) risk_events 的读者 control RiskEventConsumer 按 Flink 风控命中的 JSON
     *    契约解析，Avro 埋点进来每条报反序列化错误并被丢弃。
     * oddsmaker.risk_events 归 Flink RiskJob 单一生产者（JSON 风控命中事件）。
     */
    public Future<RecordMetadata> publish(Event e) {
        GenericRecord gr = buildGenericRecord(e);
        String routingKey = buildRoutingKey(e);
        // 异步发送失败（broker 不可达/delivery 超时）会在 Future 上静默完成；若不观察，
        // 事件已被计数 ACCEPTED 却从未进 Kafka（静默丢失，审计 C2026-11-02）。挂失败回调
        // 路由到 DLQ，使发送失败可观测。注意：回调触发时记录已交给 Kafka 且 delivery.timeout.ms
        // 已到期，acks=all 下 TimeoutException 不保证未投递（broker 可能已提交但 ack 丢失/ISR 慢），
        // 故此处不释放幂等位——释放会让 SDK 重试重发可能已入 events_raw 的事件（重复写入）。
        // 与同步 catch 路径（BatchController）不同：同步抛错时记录必然未进 Kafka，才释放。
        // DLQ reason 必须避开 GATEWAY_REASONS：异步路径已被计 ACCEPTED，不经 BatchController.reject()，
        // 若复用 kafka_error，consumer 会按同步拒绝防双计口径跳过，异步故障反而完全不可见。
        return producer.send(new ProducerRecord<>(eventsTopic, routingKey, gr), (metadata, exception) -> {
            if (exception != null) {
                log.warn("Kafka send failed, routed to DLQ: eventId={} topic={}", e.eventId, eventsTopic, exception);
                try {
                    dlq.publish(e.eventId, "kafka_send_failed", om.writeValueAsString(e));
                } catch (Exception inner) {
                    log.error("Failed to route event to DLQ: eventId={}", e.eventId, inner);
                }
            }
        });
    }

    private String buildRoutingKey(Event e) {
        // Routing key format: "game_id|environment" for partitioning
        // This allows downstream consumers to route to appropriate databases
        return e.gameId + "|" + e.environment;
    }

    private GenericRecord buildGenericRecord(Event e) {
        GenericRecord gr = new GenericData.Record(schema);
        gr.put("event_id", e.eventId);
        gr.put("game_id", e.gameId);
        gr.put("environment", e.environment);
        // Event classification
        gr.put("event_type", e.eventType);
        gr.put("event_name", e.eventName);
        // Identity fields
        gr.put("user_id", e.userId);
        gr.put("device_id", e.deviceId);
        gr.put("player_id", e.playerId);
        gr.put("character_id", e.characterId);
        gr.put("session_id", e.sessionId);
        // Timestamp fields
        gr.put("ts_client", e.tsClient * 1000L); // assume ms -> micros
        gr.put("ts_server", e.tsServer == null ? null : e.tsServer * 1000L);
        // Client context
        gr.put("platform", e.platform);
        gr.put("app_version", e.appVersion);
        gr.put("sdk_version", e.sdkVersion);
        gr.put("country", e.country);
        gr.put("client_ip", e.clientIp);
        gr.put("user_agent", e.userAgent);
        // Game context (MMORPG support)
        gr.put("server_id", e.serverId);
        gr.put("guild_id", e.guildId);
        gr.put("match_id", e.matchId);
        gr.put("level_id", e.levelId);
        gr.put("game_mode", e.gameMode);
        gr.put("difficulty", e.difficulty);
        gr.put("progression_path", e.progressionPath);
        // Revenue fields
        gr.put("order_id", e.orderId);
        gr.put("product_id", e.productId);
        gr.put("revenue_amount", e.revenueAmount);
        gr.put("revenue_currency", e.revenueCurrency);
        gr.put("receipt_hash", e.receiptHash);
        // Resource flow fields
        gr.put("virtual_currency", e.virtualCurrency);
        gr.put("virtual_amount", e.virtualAmount);
        gr.put("item_id", e.itemId);
        gr.put("resource_id", e.resourceId);
        gr.put("resource_amount", e.resourceAmount);
        gr.put("flow_type", e.flowType);
        gr.put("operation_id", e.operationId);
        gr.put("operation_type", e.operationType);
        // Ad fields
        gr.put("ad_network", e.adNetwork);
        gr.put("ad_placement", e.adPlacement);
        gr.put("ad_format", e.adFormat);
        gr.put("ad_impression_id", e.adImpressionId);
        // Risk and diagnostics
        gr.put("risk_context", e.riskContext);
        gr.put("device_fingerprint", e.deviceFingerprint);
        gr.put("client_integrity", e.clientIntegrity);
        // Experiment fields
        gr.put("experiments", e.experiments);
        // Contract v2 fields（TrustPolicy 已回填权威值；缺省兜底保证 Avro int 非空）
        gr.put("event_version", e.eventVersion == null ? TrustPolicy.DEFAULT_EVENT_VERSION : e.eventVersion);
        gr.put("source", e.source);
        gr.put("trust_level", e.trustLevel);
        gr.put("event_origin", e.eventOrigin);
        // Additional properties as JSON
        try {
            gr.put("props_json", e.props == null ? "{}" : om.writeValueAsString(e.props));
        } catch (Exception ex) {
            gr.put("props_json", "{}");
        }
        return gr;
    }

}
