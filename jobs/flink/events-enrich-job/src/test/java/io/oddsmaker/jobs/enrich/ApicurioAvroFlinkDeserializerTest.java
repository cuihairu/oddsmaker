package io.oddsmaker.jobs.enrich;

import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.util.Collector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Apicurio Avro 反序列化器")
class ApicurioAvroFlinkDeserializerTest {

    private static <T> Collector<T> sinkTo(List<T> out) {
        return new Collector<>() {
            @Override
            public void collect(T record) {
                out.add(record);
            }

            @Override
            public void close() {
            }
        };
    }

    @Test
    @DisplayName("getProducedType：返回 RawEvent POJO 类型")
    void producedTypeIsRawEvent() {
        ApicurioAvroFlinkDeserializer d = new ApicurioAvroFlinkDeserializer("http://localhost:8081/apis/registry/v2");
        assertEquals(TypeInformation.of(RawEvent.class), d.getProducedType());
    }

    @Test
    @DisplayName("deserialize：init 本地完成，载荷反序列化因无 registry 抛错且不输出")
    void deserializeInitializesThenFailsWithoutRegistry() throws Exception {
        ApicurioAvroFlinkDeserializer d = new ApicurioAvroFlinkDeserializer("http://127.0.0.1:1/apis/registry/v2");
        org.apache.kafka.clients.consumer.ConsumerRecord<byte[], byte[]> rec =
                new org.apache.kafka.clients.consumer.ConsumerRecord<>("t", 0, 0L, null, new byte[0]);
        List<RawEvent> out = new ArrayList<>();
        try {
            d.deserialize(rec, sinkTo(out));
        } catch (Exception expected) {
            // 无 registry 连接：载荷解析必然失败；init()（configure，纯本地）已执行
        }
        assertTrue(out.isEmpty());
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("deserialize：注入替身 Deserializer，GenericRecord → RawEvent 全链路收集")
    void deserializeEndToEndWithStubbedDeserializer() throws Exception {
        var schema = org.apache.avro.Schema.createRecord("T", null, null, false);
        schema.setFields(java.util.List.of(
                new org.apache.avro.Schema.Field("event_id",
                        org.apache.avro.Schema.create(org.apache.avro.Schema.Type.STRING))));
        var record = new org.apache.avro.generic.GenericData.Record(schema);
        record.put("event_id", "evt_42");

        org.apache.kafka.common.serialization.Deserializer<org.apache.avro.generic.GenericRecord> stub =
                (org.apache.kafka.common.serialization.Deserializer<org.apache.avro.generic.GenericRecord>)
                        Proxy.newProxyInstance(
                                ApicurioAvroFlinkDeserializerTest.class.getClassLoader(),
                                new Class<?>[]{org.apache.kafka.common.serialization.Deserializer.class},
                                (Object p, Method m, Object[] a) -> {
                                    if (m.getName().equals("deserialize")) return record;
                                    return null;   // configure/close
                                });
        ApicurioAvroFlinkDeserializer d = new ApicurioAvroFlinkDeserializer("http://127.0.0.1:1/apis/registry/v2");
        Field f = ApicurioAvroFlinkDeserializer.class.getDeclaredField("deser");
        f.setAccessible(true);
        f.set(d, stub);

        List<RawEvent> out = new ArrayList<>();
        org.apache.kafka.clients.consumer.ConsumerRecord<byte[], byte[]> rec =
                new org.apache.kafka.clients.consumer.ConsumerRecord<>("t", 0, 0L, null, new byte[0]);
        d.deserialize(rec, sinkTo(out));
        assertEquals(1, out.size());
        assertEquals("evt_42", out.get(0).event_id);

        // 替身返回非 GenericRecord：丢弃，不收集
        org.apache.kafka.common.serialization.Deserializer<org.apache.avro.generic.GenericRecord> text =
                (org.apache.kafka.common.serialization.Deserializer<org.apache.avro.generic.GenericRecord>)
                        Proxy.newProxyInstance(
                                ApicurioAvroFlinkDeserializerTest.class.getClassLoader(),
                                new Class<?>[]{org.apache.kafka.common.serialization.Deserializer.class},
                                (Object p, Method m, Object[] a) ->
                                        m.getName().equals("deserialize") ? "not-a-record" : null);
        f.set(d, text);
        d.deserialize(rec, sinkTo(out));
        assertEquals(1, out.size());
    }

    @Test
    @DisplayName("collectRaw：GenericRecord 转 RawEvent 收集；其他载荷丢弃")
    void collectRawBranches() {
        var schema = org.apache.avro.Schema.createRecord("T", null, null, false);
        schema.setFields(java.util.List.of(
                new org.apache.avro.Schema.Field("event_id",
                        org.apache.avro.Schema.create(org.apache.avro.Schema.Type.STRING))));
        var record = new org.apache.avro.generic.GenericData.Record(schema);
        record.put("event_id", "evt_9");

        List<RawEvent> out = new ArrayList<>();
        ApicurioAvroFlinkDeserializer.collectRaw(record, sinkTo(out));
        assertEquals(1, out.size());
        assertEquals("evt_9", out.get(0).event_id);

        ApicurioAvroFlinkDeserializer.collectRaw("not-a-record", sinkTo(out));   // 非 GenericRecord 丢弃
        ApicurioAvroFlinkDeserializer.collectRaw(null, sinkTo(out));            // null 丢弃
        assertEquals(1, out.size());

        assertNotNull(new ApicurioAvroFlinkDeserializer("x"));
    }
}
