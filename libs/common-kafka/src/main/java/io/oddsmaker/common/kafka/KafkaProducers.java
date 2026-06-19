package io.oddsmaker.common.kafka;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import java.util.Properties;

public class KafkaProducers {
    public static Producer<byte[], byte[]> newBytesProducer(String bootstrap) {
        Properties p = new Properties();
        p.put("bootstrap.servers", bootstrap);
        p.put("acks", "all");
        p.put("retries", 3);
        p.put("linger.ms", 5);
        p.put("batch.size", 64_000);
        p.put("key.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer");
        p.put("value.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer");
        return new KafkaProducer<>(p);
    }
}
