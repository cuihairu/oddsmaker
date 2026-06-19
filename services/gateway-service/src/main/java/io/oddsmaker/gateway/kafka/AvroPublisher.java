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

import javax.annotation.PostConstruct;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.Future;

@Component
public class AvroPublisher {
    @Value("${oddsmaker.kafka.bootstrap}")
    private String bootstrap;

    @Value("${oddsmaker.kafka.topic}")
    private String topic;

    @Value("${oddsmaker.kafka.registryUrl}")
    private String registryUrl;

    @Value("${oddsmaker.kafka.producer.lingerMs:5}")
    private int lingerMs;

    @Value("${oddsmaker.kafka.producer.batchSize:65536}")
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

    public Future<RecordMetadata> publish(Event e) {
        GenericRecord gr = new GenericData.Record(schema);
        gr.put("event_id", e.eventId);
        gr.put("game_id", e.gameId);
        gr.put("environment", e.environment);
        gr.put("event_type", e.eventType);
        gr.put("event_name", e.eventName);
        gr.put("user_id", e.userId);
        gr.put("device_id", e.deviceId);
        gr.put("session_id", e.sessionId);
        gr.put("ts_client", e.tsClient * 1000L); // assume ms -> micros
        gr.put("ts_server", e.tsServer == null ? null : e.tsServer * 1000L);
        gr.put("platform", e.platform);
        gr.put("app_version", e.appVersion);
        gr.put("country", e.country);
        gr.put("client_ip", e.clientIp);
        gr.put("user_agent", e.userAgent);
        gr.put("revenue_amount", e.revenueAmount);
        gr.put("revenue_currency", e.revenueCurrency);
        try {
            gr.put("props_json", e.props == null ? "{}" : om.writeValueAsString(e.props));
        } catch (Exception ex) {
            gr.put("props_json", "{}");
        }
        return producer.send(new ProducerRecord<>(topic, e.gameId + "|" + e.environment, gr));
    }
}
