package io.oddsmaker.jobs.enrich;

import io.apicurio.registry.serde.avro.AvroKafkaDeserializer;
import org.apache.avro.generic.GenericRecord;
import org.apache.flink.api.common.serialization.DeserializationSchema;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.connector.source.SourceSplit;
import org.apache.flink.connector.kafka.source.reader.deserializer.KafkaRecordDeserializationSchema;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.flink.util.Collector;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class ApicurioAvroFlinkDeserializer implements KafkaRecordDeserializationSchema<GenericRecord> {
    private final String registryUrl;
    private transient AvroKafkaDeserializer deser;

    public ApicurioAvroFlinkDeserializer(String registryUrl) {
        this.registryUrl = registryUrl;
    }

    @Override
    public void deserialize(ConsumerRecord<byte[], byte[]> record, Collector<GenericRecord> out) throws IOException {
        if (deser == null) init();
        Object obj = deser.deserialize(record.topic(), record.headers(), record.value());
        if (obj instanceof GenericRecord) {
            out.collect((GenericRecord) obj);
        }
    }

    private void init() {
        deser = new AvroKafkaDeserializer();
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("apicurio.registry.url", registryUrl);
        cfg.put("apicurio.registry.find-latest", true);
        cfg.put("apicurio.registry.auto-register", false);
        deser.configure(cfg, false);
    }

    @Override
    public TypeInformation<GenericRecord> getProducedType() {
        return TypeInformation.of(GenericRecord.class);
    }
}
