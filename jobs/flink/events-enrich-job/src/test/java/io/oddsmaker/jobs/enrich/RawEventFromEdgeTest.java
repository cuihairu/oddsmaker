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
}
