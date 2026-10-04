package io.oddsmaker.jobs.enrich;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * INSTR 收口：RawEvent.lng/dbl 的 NumberFormatException 侧——
 * Avro string 字段（CharSequence，非 Number）携带非数字文本时 parse 失败按 null 处理。
 */
@DisplayName("RawEvent.from 数值字段非数字文本容错")
class RawEventFromEdgeTest {

    private static GenericData.Record recordWith(Schema.Field... fields) {
        var schema = Schema.createRecord("T", null, null, false);
        schema.setFields(java.util.Arrays.asList(fields));
        return new GenericData.Record(schema);
    }

    @Test
    @DisplayName("lng/dbl：string 字段非数字文本 → null（parse 失败侧）；数字文本正常解析")
    void nonNumericStringFieldsFallBackToNull() {
        GenericData.Record r = recordWith(
            new Schema.Field("ts_client", Schema.create(Schema.Type.STRING)),
            new Schema.Field("ts_server", Schema.create(Schema.Type.STRING)),
            new Schema.Field("revenue_amount", Schema.create(Schema.Type.STRING)),
            new Schema.Field("virtual_amount", Schema.create(Schema.Type.STRING)));
        r.put("ts_client", "not-a-number");       // lng parse 失败
        r.put("ts_server", "1700000000123");      // 数字文本正常解析
        r.put("revenue_amount", "9.99abc");       // dbl parse 失败
        r.put("virtual_amount", "12.5");          // 数字文本正常解析

        RawEvent e = RawEvent.from(r);
        assertNull(e.ts_client);
        assertEquals(Long.valueOf(1700000000123L), e.ts_server);
        assertNull(e.revenue_amount);
        assertEquals(Double.valueOf(12.5), e.virtual_amount);
    }

    @Test
    @DisplayName("契约 v2 字段：source/trust_level/event_origin 透传 + event_version 数值解析")
    void contractV2FieldsPassThrough() {
        GenericData.Record r = recordWith(
            new Schema.Field("event_version", Schema.create(Schema.Type.INT)),
            new Schema.Field("source", Schema.create(Schema.Type.STRING)),
            new Schema.Field("trust_level", Schema.create(Schema.Type.STRING)),
            new Schema.Field("event_origin", Schema.create(Schema.Type.STRING)));
        r.put("event_version", 1);
        r.put("source", "server");
        r.put("trust_level", "HIGH");
        r.put("event_origin", "server-java/0.1.0");

        RawEvent e = RawEvent.from(r);
        assertEquals(Integer.valueOf(1), e.event_version);
        assertEquals("server", e.source);
        assertEquals("HIGH", e.trust_level);
        assertEquals("server-java/0.1.0", e.event_origin);

        // integer 容错：非数字文本 → null
        GenericData.Record bad = recordWith(
            new Schema.Field("event_version", Schema.create(Schema.Type.STRING)));
        bad.put("event_version", "not-a-number");
        assertNull(RawEvent.from(bad).event_version);
    }

    @Test
    @DisplayName("旧 schema 记录（无 v2 字段列）→ 字段为 null，读取不抛")
    void legacyRecordWithoutV2FieldsReadsAsNull() {
        GenericData.Record r = recordWith(
            new Schema.Field("event_id", Schema.create(Schema.Type.STRING)));
        r.put("event_id", "e1");

        RawEvent e = RawEvent.from(r);
        assertEquals("e1", e.event_id);
        assertNull(e.event_version);
        assertNull(e.source);
        assertNull(e.trust_level);
        assertNull(e.event_origin);
    }
}
