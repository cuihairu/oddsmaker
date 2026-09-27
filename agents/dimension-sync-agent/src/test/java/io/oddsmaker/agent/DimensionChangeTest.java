package io.oddsmaker.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 维度变更中间格式的默认值与属性收集契约（所有 source 都产出它，下游只认这一种）。
 */
class DimensionChangeTest {

    @Test
    @DisplayName("无参构造（Jackson 反序列化用）：op 默认 upsert、attributes 为空可变映射")
    void noArgConstructorDefaults() {
        DimensionChange c = new DimensionChange();
        assertEquals("upsert", c.op);
        assertNotNull(c.attributes);
        assertTrue(c.attributes.isEmpty());
        // 可变：反序列化后仍可追加属性（source 侧 mapRow 即依赖这点）
        c.attr("name", "sword");
        assertEquals("sword", c.attributes.get("name"));
    }

    @Test
    @DisplayName("attr：null / 空白键与 null 值一律丢弃（不产出脏 attributes），其余链式收集")
    void attrFiltersNullAndBlankKeysAndNullValues() {
        DimensionChange c = new DimensionChange("item", "delete", "sword_01", 1000L);
        c.attr(null, "v").attr("  ", "v").attr("weight", null).attr("rarity", "epic");
        assertEquals(1, c.attributes.size());
        assertEquals("epic", c.attributes.get("rarity"));
        assertEquals("delete", c.op);   // 显式 op 不被默认值覆盖
    }
}
