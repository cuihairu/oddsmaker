package io.oddsmaker.jobs.enrich;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EventsEnrichJobTest {

    // 模拟 oddsmaker-event.avsc 的字段形态：string 必填 + [null,long] / [null,double] 可选
    private static org.apache.avro.Schema eventSchema() {
        var schema = org.apache.avro.Schema.createRecord("TestRecord", null, null, false);
        var fields = new java.util.ArrayList<org.apache.avro.Schema.Field>();
        fields.add(new org.apache.avro.Schema.Field("event_id",
            org.apache.avro.Schema.create(org.apache.avro.Schema.Type.STRING)));
        fields.add(new org.apache.avro.Schema.Field("game_id",
            org.apache.avro.Schema.create(org.apache.avro.Schema.Type.STRING)));
        fields.add(new org.apache.avro.Schema.Field("ts_client",
            org.apache.avro.Schema.createUnion(
                org.apache.avro.Schema.create(org.apache.avro.Schema.Type.NULL),
                org.apache.avro.Schema.create(org.apache.avro.Schema.Type.LONG))));
        fields.add(new org.apache.avro.Schema.Field("revenue_amount",
            org.apache.avro.Schema.createUnion(
                org.apache.avro.Schema.create(org.apache.avro.Schema.Type.NULL),
                org.apache.avro.Schema.create(org.apache.avro.Schema.Type.DOUBLE))));
        schema.setFields(fields);
        return schema;
    }

    @Test
    void fromMissingFieldsYieldsNull() {
        var record = new org.apache.avro.generic.GenericData.Record(eventSchema());
        RawEvent e = RawEvent.from(record);
        assertNull(e.event_id);
        assertNull(e.ts_client);
        assertNull(e.revenue_amount);
        assertNull(e.props_json);
    }

    @Test
    void fromReadsKnownFieldsAndIgnoresUnknown() {
        var record = new org.apache.avro.generic.GenericData.Record(eventSchema());
        record.put("event_id", "evt_1");
        record.put("game_id", "g1");
        record.put("ts_client", 12345L);
        record.put("revenue_amount", 12.5);
        RawEvent e = RawEvent.from(record);
        assertEquals("evt_1", e.event_id);
        assertEquals("g1", e.game_id);
        assertEquals(12345L, e.ts_client);
        assertEquals(12.5, e.revenue_amount);
        assertNull(e.ts_server);
    }

    @Test
    void toDlqJsonReturnsValidJson() {
        // DLQ 最小载荷只含 event_id + reason（与 Gateway DlqPublisher 结构一致）
        RawEvent e = new RawEvent();
        e.event_id = "test_event_123";
        assertEquals("{\"event_id\":\"test_event_123\",\"reason\":\"invalid_schema\"}",
            RawEvent.toDlqJson(e, "invalid_schema"));
    }

    @Test
    void toDlqJsonHandlesNullEvent() {
        assertEquals("{\"event_id\":\"\",\"reason\":\"invalid_schema\"}",
            RawEvent.toDlqJson(null, "invalid_schema"));
    }

    private static org.apache.avro.Schema schemaWithExperiments() {
        var schema = org.apache.avro.Schema.createRecord("TestRecord", null, null, false);
        var fields = new java.util.ArrayList<org.apache.avro.Schema.Field>();
        fields.add(new org.apache.avro.Schema.Field("event_id",
            org.apache.avro.Schema.create(org.apache.avro.Schema.Type.STRING)));
        fields.add(new org.apache.avro.Schema.Field("experiments",
            org.apache.avro.Schema.createUnion(
                org.apache.avro.Schema.create(org.apache.avro.Schema.Type.NULL),
                org.apache.avro.Schema.createMap(
                    org.apache.avro.Schema.create(org.apache.avro.Schema.Type.STRING)))));
        schema.setFields(fields);
        return schema;
    }

    @Test
    void mpReadsAvroMapAndSkipsNullEntries() {
        var record = new org.apache.avro.generic.GenericData.Record(schemaWithExperiments());
        var m = new java.util.HashMap<CharSequence, CharSequence>();
        m.put("exp_1", "control");
        m.put("exp_2", "treatment");
        m.put("exp_3", null);
        record.put("event_id", "evt_1");
        record.put("experiments", m);
        var parsed = RawEvent.mp(record, "experiments");
        assertEquals(java.util.Map.of("exp_1", "control", "exp_2", "treatment"), parsed);
    }

    @Test
    void mpYieldsNullOnMissingFieldOrEmptyMap() {
        // schema 不含 experiments 字段：不抛 AvroRuntimeException，返回 null
        var missing = new org.apache.avro.generic.GenericData.Record(eventSchema());
        missing.put("event_id", "evt_1");
        assertNull(RawEvent.mp(missing, "experiments"));

        var record = new org.apache.avro.generic.GenericData.Record(schemaWithExperiments());
        record.put("experiments", new java.util.HashMap<CharSequence, CharSequence>());
        assertNull(RawEvent.mp(record, "experiments"));
    }

    @Test
    void mapLiteralFormatsClickHouseMapSyntax() {
        assertEquals("{}", EventsEnrichJob.mapLiteral(null));
        assertEquals("{}", EventsEnrichJob.mapLiteral(java.util.Map.of()));
        assertEquals("{'exp_1':'control'}", EventsEnrichJob.mapLiteral(java.util.Map.of("exp_1", "control")));
        var ordered = new java.util.LinkedHashMap<String, String>();
        ordered.put("a", "1");
        ordered.put("b", "2");
        assertEquals("{'a':'1','b':'2'}", EventsEnrichJob.mapLiteral(ordered));
    }

    @Test
    void mapLiteralEscapesQuotesAndBackslashesAndSkipsNulls() {
        var m = new java.util.LinkedHashMap<String, String>();
        m.put("it's", "va\\lue");
        m.put("nullKey", null);
        m.put("k2", "v2");
        assertEquals("{'it\\'s':'va\\\\lue','k2':'v2'}", EventsEnrichJob.mapLiteral(m));
    }

    @Test
    void mapLiteralAllNullEntriesYieldsEmptyMap() {
        var m = new java.util.LinkedHashMap<String, String>();
        m.put("a", null);
        assertEquals("{}", EventsEnrichJob.mapLiteral(m));
    }
}
