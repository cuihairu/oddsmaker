package io.oddsmaker.jobs.enrich;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EventsEnrichJobTest {

    @Test
    void fieldReturnsNullForMissingField() {
        // 创建一个带合法字段的模拟 GenericRecord（schema 未设 fields 时 Avro 拒绝实例化）
        var schema = org.apache.avro.Schema.createRecord("TestRecord", null, null, false);
        schema.setFields(java.util.List.of(
            new org.apache.avro.Schema.Field("game_id",
                org.apache.avro.Schema.create(org.apache.avro.Schema.Type.STRING))));
        var record = new org.apache.avro.generic.GenericData.Record(schema);

        Object result = EventsEnrichJob.field(record, "nonexistent");
        assertNull(result);
    }

    @Test
    void strReturnsNullForNull() {
        String result = EventsEnrichJob.str(null);
        assertNull(result);
    }

    @Test
    void strReturnsStringForNonNull() {
        String result = EventsEnrichJob.str("test");
        assertEquals("test", result);
    }

    @Test
    void longOrNullReturnsNullForNonNumeric() {
        Long result = EventsEnrichJob.longOrNull("not_a_number");
        assertNull(result);
    }

    @Test
    void longOrNullReturnsLongForNumeric() {
        Long result = EventsEnrichJob.longOrNull(12345L);
        assertEquals(12345L, result);
    }

    @Test
    void longOrNullReturnsLongForStringNumber() {
        Long result = EventsEnrichJob.longOrNull("12345");
        assertEquals(12345L, result);
    }

    @Test
    void doubleOrNullReturnsNullForNonNumeric() {
        Double result = EventsEnrichJob.doubleOrNull("not_a_number");
        assertNull(result);
    }

    @Test
    void doubleOrNullReturnsDoubleForNumeric() {
        Double result = EventsEnrichJob.doubleOrNull(123.45);
        assertEquals(123.45, result);
    }

    @Test
    void doubleOrNullReturnsDoubleForStringNumber() {
        Double result = EventsEnrichJob.doubleOrNull("123.45");
        assertEquals(123.45, result);
    }

    @Test
    void toDlqJsonReturnsValidJson() {
        // DLQ 最小载荷只含 event_id + reason（与 Gateway DlqPublisher 结构一致）
        var schema = org.apache.avro.Schema.createRecord("TestRecord", null, null, false);
        schema.setFields(java.util.List.of(
            new org.apache.avro.Schema.Field("event_id",
                org.apache.avro.Schema.create(org.apache.avro.Schema.Type.STRING))));
        var record = new org.apache.avro.generic.GenericData.Record(schema);
        record.put("event_id", "test_event_123");

        String json = EventsEnrichJob.toDlqJson(record, "invalid_schema");

        assertNotNull(json);
        assertEquals("{\"event_id\":\"test_event_123\",\"reason\":\"invalid_schema\"}", json);
    }
}