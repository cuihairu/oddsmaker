package io.oddsmaker.gateway.kafka;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.Properties;

@Component
public class DlqPublisher {
    private static final Logger log = LoggerFactory.getLogger(DlqPublisher.class);

    @Value("${oddsmaker.kafka.bootstrap}")
    private String bootstrap;

    @Value("${oddsmaker.kafka.dlq-topic:oddsmaker.deadletter}")
    private String dlqTopic;

    private KafkaProducer<String, String> producer;

    @Autowired(required = false)
    private MeterRegistry meters;

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
        producer.send(new ProducerRecord<>(dlqTopic, key, payload), (metadata, exception) -> {
            if (exception != null) {
                log.error("DLQ send failed: key={} reason={}", key, reason, exception);
                if (meters != null) {
                    meters.counter("oddsmaker.dlq.publish.failures", "reason", reason).increment();
                }
            }
        });
    }

    private String stringify(String raw) {
        // ensure raw is quoted as JSON string if not starts with '{' or '['
        String s = raw.trim();
        if (s.startsWith("{") || s.startsWith("[")) return s;
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
