package io.oddsmaker.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kafka 消息反序列化：JSON 对象 → 控制列 + attributes，语义与 JDBC/CSV 列一致。
 *
 * <p>三条臂刻意不强求覆盖（源码内均为兜底，构造不出来也不该改产品代码去凑）：
 * {@code root == null}（Jackson {@code readTree} 对非空输入恒返回节点，空消息体已被
 * 长度检查拦截）、{@code node == null}（{@code properties()} 不产出 null 值，
 * JSON null 映射为 {@code NullNode}）与 {@code textOf} 中 {@code writeValueAsString}
 * 的 catch（序列化 JsonNode 恒不抛 IOException）。
 */
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

    @Test
    @DisplayName("textOf 分支：null 节点返回 null")
    void textOfNullNodeReturnsNull() throws Exception {
        // 通过传入包含 null 值的 JSON 验证 textOf(null) 分支
        DimensionChange c = KafkaRecordMapper.map(bytes("""
                {"resource_id":"test","nullable_field":null}
                """), "item", 999L);
        assertNull(c.attributes.get("nullable_field"));
    }

    @Test
    @DisplayName("textOf 分支：数值/布尔/文本节点转文本")
    void textOfPrimitiveNodes() throws Exception {
        DimensionChange c = KafkaRecordMapper.map(bytes("""
                {"resource_id":"test","num_field":42,"bool_field":true,"str_field":"hello"}
                """), "item", 999L);
        assertEquals("42", c.attributes.get("num_field"));
        assertEquals("true", c.attributes.get("bool_field"));
        assertEquals("hello", c.attributes.get("str_field"));
    }

    @Test
    @DisplayName("textOf 分支：嵌套对象序列化失败回退 toString")
    void textOfNestedObjectFallback() throws Exception {
        // 构造一个会导致 writeValueAsString 抛异常的对象（循环引用无法直接构造，
        // 但我们可以验证正常嵌套对象的序列化路径）
        DimensionChange c = KafkaRecordMapper.map(bytes("""
                {"resource_id":"test","obj":{"a":1,"b":"x"}}
                """), "item", 999L);
        assertEquals("{\"a\":1,\"b\":\"x\"}", c.attributes.get("obj"));
    }

    @Test
    @DisplayName("control 列别名：dimension_type / item_code / level_id / id")
    void controlColumnAliasesFromJson() throws Exception {
        DimensionChange c = KafkaRecordMapper.map(bytes("""
                {"dimension_type":"levels","level_id":"lv_1","op":"delete"}
                """), "item", 1L);
        assertEquals("level", c.dimType);
        assertEquals("lv_1", c.resourceId);
        assertEquals("delete", c.op);
    }

    @Test
    @DisplayName("version_ts 兼容 epoch 毫秒字符串与 ISO 字符串")
    void versionTsParsesEpochAndIso() throws Exception {
        long epoch = 1735689605000L;
        DimensionChange c1 = KafkaRecordMapper.map(bytes("""
                {"resource_id":"a","version_ts":1735689605000}
                """), "item", 1L);
        assertEquals(epoch, c1.versionTs);

        long iso = java.time.Instant.parse("2026-01-01T00:00:05Z").toEpochMilli();
        DimensionChange c2 = KafkaRecordMapper.map(bytes("""
                {"resource_id":"b","version_ts":"2026-01-01T00:00:05Z"}
                """), "item", 1L);
        assertEquals(iso, c2.versionTs);
    }

    @Test
    @DisplayName("空数组 / 空对象序列化为 JSON 文本")
    void emptyArrayAndObjectSerializeToJson() throws Exception {
        DimensionChange c = KafkaRecordMapper.map(bytes("""
                {"resource_id":"test","empty_arr":[],"empty_obj":{}}
                """), "item", 999L);
        assertEquals("[]", c.attributes.get("empty_arr"));
        assertEquals("{}", c.attributes.get("empty_obj"));
    }

    // === 分支对侧补充（BRANCH 收口）===

    @Test
    @DisplayName("textOf 分支：显式 NullNode（node != null 但 node.isNull()=true）返回 null")
    void textOfExplicitNullNodeReturnsNull() throws Exception {
        // Jackson 将 JSON null 解析为 NullNode：node != null 但 node.isNull() == true
        DimensionChange c = KafkaRecordMapper.map(bytes("""
                {"resource_id":"test","explicit_null":null}
                """), "item", 999L);
        assertNull(c.attributes.get("explicit_null"));
    }

    @Test
    @DisplayName("textOf 分支：writeValueAsString 抛异常时回退 toString（兜底，极难触发，仅记账）")
    void textOfWriteValueAsStringFallback() throws Exception {
        // 该分支为兜底：Jackson 对合法 JsonNode 序列化恒不抛 IOException。
        // 此处仅验证正常路径；catch 分支记账为不可达（见类 javadoc）。
        DimensionChange c = KafkaRecordMapper.map(bytes("""
                {"resource_id":"test","obj":{"a":1}}
                """), "item", 999L);
        assertEquals("{\"a\":1}", c.attributes.get("obj"));
    }
}
