package io.oddsmaker.gateway.kafka;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.Properties;

@Component
public class DlqPublisher {
    @Value("${oddsmaker.kafka.bootstrap}")
    private String bootstrap;

    @Value("${oddsmaker.kafka.dlqTopic:oddsmaker.deadletter}")
    private String dlqTopic;

    private KafkaProducer<String, String> producer;

    @PostConstruct
    public void init() {
        Properties p = new Properties();
        p.put("bootstrap.servers", bootstrap);
        p.put("acks", "1");
        p.put("linger.ms", "5");
        p.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        p.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        producer = new KafkaProducer<>(p);
    }

    public void publish(String key, String reason, String raw) {
        String payload = String.format("{\"event_id\":\"%s\",\"reason\":\"%s\",\"raw\":%s}",
                key == null ? "" : key,
                reason,
                raw == null ? "null" : stringify(raw));
        producer.send(new ProducerRecord<>(dlqTopic, key, payload));
    }

    private String stringify(String raw) {
        // ensure raw is quoted as JSON string if not starts with '{' or '['
        String s = raw.trim();
        if (s.startsWith("{") || s.startsWith("[")) return s;
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
