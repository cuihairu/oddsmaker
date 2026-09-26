package io.oddsmaker.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Kafka 消息反序列化：JSON 对象 → 控制列 + attributes，语义与 JDBC/CSV 列一致。 */
class KafkaRecordMapperTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("平铺 JSON：控制列映射 + 其余字段进 attributes；数值/布尔转文本")
    void flatJsonMapsControlColumnsAndAttributes() throws Exception {
        DimensionChange c = KafkaRecordMapper.map(bytes("""
                {"dim_type":"item","resource_id":"sword_01","name":"铁剑","rarity":"sr",
                 "level":5,"active":true,"op":"upsert","version_ts":1735689605000}
                """), "item", 999L);

        assertEquals("sword_01", c.resourceId);
        assertEquals("item", c.dimType);
        assertEquals("upsert", c.op);
        assertEquals(1735689605000L, c.versionTs);
        assertEquals("铁剑", c.attributes.get("name"));
        assertEquals("5", c.attributes.get("level"));
        assertEquals("true", c.attributes.get("active"));
    }

    @Test
    @DisplayName("嵌套对象/数组序列化为紧凑 JSON 文本保留在 attributes")
    void nestedValuesKeptAsJsonText() throws Exception {
        DimensionChange c = KafkaRecordMapper.map(bytes("""
                {"id":"chest_01","drop":{"gold":10,"items":["a","b"]},"tags":[1,2]}
                """), "item", 999L);

        assertEquals("chest_01", c.resourceId);
        assertEquals("{\"gold\":10,\"items\":[\"a\",\"b\"]}", c.attributes.get("drop"));
        assertEquals("[1,2]", c.attributes.get("tags"));
    }

    @Test
    @DisplayName("null 字段保留 null；ISO version_ts 兼容；缺省 dim_type 回落默认值")
    void nullFieldsIsoTsAndDefaultDimType() throws Exception {
        DimensionChange c = KafkaRecordMapper.map(bytes("""
                {"id":"lv_1","description":null,"version_ts":"2026-01-01T00:00:05Z"}
                """), "level", 999L);

        assertEquals("lv_1", c.resourceId);
        assertEquals("level", c.dimType);   // id 不带 dim_type → 配置默认
        assertEquals(null, c.attributes.get("description"));
        assertEquals(java.time.Instant.parse("2026-01-01T00:00:05Z").toEpochMilli(), c.versionTs);
    }

    @Test
    @DisplayName("空消息体 / 非 JSON / 非对象根：IOException（调用方记日志跳过）")
    void badMessagesThrowIoException() {
        assertThrows(IOException.class, () -> KafkaRecordMapper.map(null, "item", 1L));
        assertThrows(IOException.class, () -> KafkaRecordMapper.map(new byte[0], "item", 1L));
        IOException e = assertThrows(IOException.class,
                () -> KafkaRecordMapper.map(bytes("not-json{"), "item", 1L));
        assertTrue(e.getMessage().contains("JSON 解析失败"));
        assertThrows(IOException.class, () -> KafkaRecordMapper.map(bytes("[1,2]"), "item", 1L));
        assertThrows(IOException.class, () -> KafkaRecordMapper.map(bytes("\"scalar\""), "item", 1L));
    }
}
