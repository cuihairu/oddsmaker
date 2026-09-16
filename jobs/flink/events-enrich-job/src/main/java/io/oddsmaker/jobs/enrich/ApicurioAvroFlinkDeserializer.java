package io.oddsmaker.jobs.enrich;

import io.apicurio.registry.serde.avro.AvroKafkaDeserializer;
import org.apache.avro.generic.GenericRecord;
import org.apache.flink.api.common.serialization.DeserializationSchema;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.connector.kafka.source.reader.deserializer.KafkaRecordDeserializationSchema;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.flink.util.Collector;

import java.util.HashMap;
import java.util.Map;

public class ApicurioAvroFlinkDeserializer implements KafkaRecordDeserializationSchema<RawEvent> {
    private final String registryUrl;
    // Kafka Deserializer 接口类型（AvroKafkaDeserializer 即其实现），便于替身注入
    private transient org.apache.kafka.common.serialization.Deserializer<GenericRecord> deser;

    public ApicurioAvroFlinkDeserializer(String registryUrl) {
        this.registryUrl = registryUrl;
    }

    @Override
    public void deserialize(ConsumerRecord<byte[], byte[]> record, Collector<RawEvent> out) throws java.io.IOException {
        if (deser == null) init();
        collectRaw(deser.deserialize(record.topic(), record.headers(), record.value()), out);
    }

    /** Avro GenericRecord → RawEvent；非 record 载荷丢弃。 */
    static void collectRaw(Object obj, Collector<RawEvent> out) {
        if (obj instanceof GenericRecord r) {
            out.collect(RawEvent.from(r));
        }
    }

    private void init() {
        AvroKafkaDeserializer<GenericRecord> apicurio = new AvroKafkaDeserializer<>();
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("apicurio.registry.url", registryUrl);
        cfg.put("apicurio.registry.find-latest", true);
        cfg.put("apicurio.registry.auto-register", false);
        apicurio.configure(cfg, false);
        deser = apicurio;
    }

    @Override
    public TypeInformation<RawEvent> getProducedType() {
        return TypeInformation.of(RawEvent.class);
    }
}
