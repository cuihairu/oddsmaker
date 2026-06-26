package io.oddsmaker.gateway.kafka;

import io.oddsmaker.common.model.Event;
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
    @Value("${oddsmaker.kafka.bootstrap}")
    private String bootstrap;

    @Value("${oddsmaker.kafka.topic.events:oddsmaker-events}")
    private String eventsTopic;

    @Value("${oddsmaker.kafka.topic.risk:oddsmaker-risk-events}")
    private String riskTopic;

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

    public AvroPublisher(ObjectMapper om) { this.om = om; }

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
     * Publish event to appropriate topic based on event type.
     * Key format: "game_id|environment" for routing partitioning.
     */
    public Future<RecordMetadata> publish(Event e) {
        String topic = selectTopic(e);
        GenericRecord gr = buildGenericRecord(e);
        String routingKey = buildRoutingKey(e);
        return producer.send(new ProducerRecord<>(topic, routingKey, gr));
    }

    private String selectTopic(Event e) {
        // Risk events go to dedicated topic for faster processing
        if ("risk".equals(e.eventType) || (e.eventName != null && e.eventName.toLowerCase().contains("risk"))) {
            return riskTopic;
        }
        return eventsTopic;
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
        // Additional properties as JSON
        try {
            gr.put("props_json", e.props == null ? "{}" : om.writeValueAsString(e.props));
        } catch (Exception ex) {
            gr.put("props_json", "{}");
        }
        return gr;
    }

    /**
     * Build a routing map for database selection.
     * Downstream Flink jobs use this to route events to the correct ClickHouse database.
     *
     * Database naming: game_{game_id}_{environment}
     * Example: game_demo_prod, game_rpg_staging
     */
    public static String targetDatabase(String gameId, String environment) {
        return String.format("game_%s_%s", gameId, environment);
    }
}
