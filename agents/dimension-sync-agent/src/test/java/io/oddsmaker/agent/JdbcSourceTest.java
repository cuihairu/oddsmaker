package io.oddsmaker.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JDBC 源纯逻辑：行→DimensionChange 映射、dim_type 归一化、version_ts 解析（不触真实 DB）。 */
class JdbcSourceTest {

    private Map<String, String> rowOf(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    @DisplayName("mapRow：控制列抽出，其余进 attributes，语义对齐 dimension-sync-job parseProps")
    void mapRowSplitsControlColumnsAndAttributes() {
        DimensionChange c = JdbcSource.mapRow(rowOf(
                "dim_type", "items", "resource_id", "sword_01", "op", "upsert",
                "version_ts", "1735689605000", "name", "铁剑", "rarity", "sr"), "item", 999L);

        assertEquals("item", c.dimType);           // items→item 归一化
        assertEquals("sword_01", c.resourceId);
        assertEquals("upsert", c.op);
        assertEquals(1735689605000L, c.versionTs);
        assertEquals(2, c.attributes.size());
        assertEquals("铁剑", c.attributes.get("name"));
        assertEquals("sr", c.attributes.get("rarity"));
    }

    @Test
    @DisplayName("控制列别名：dimension_type / item_code / level_id / id；键名大小写不敏感")
    void controlColumnAliases() {
        DimensionChange a = JdbcSource.mapRow(rowOf(
                "DIM_TYPE", "levels", "LEVEL_ID", "lv_1", "op", "delete"), "item", 1L);
        assertEquals("level", a.dimType);
        assertEquals("lv_1", a.resourceId);
        assertEquals("delete", a.op);

        DimensionChange b = JdbcSource.mapRow(rowOf("dimension_type", "levels", "item_code", "lv_2"), "item", 1L);
        assertEquals("level", b.dimType);
        assertEquals("lv_2", b.resourceId);

        DimensionChange d = JdbcSource.mapRow(rowOf("id", "x9"), "item", 1L);
        assertEquals("x9", d.resourceId);
    }

    @Test
    @DisplayName("缺省：op=upsert；version_ts 缺失取 now；dim_type 缺失取默认")
    void defaults() {
        DimensionChange c = JdbcSource.mapRow(rowOf("resource_id", "r1"), "guild", 777L);
        assertEquals("upsert", c.op);
        assertEquals(777L, c.versionTs);
        assertEquals("guild", c.dimType);
        assertTrue(c.attributes.isEmpty());
    }

    @Test
    @DisplayName("version_ts 兼容 ISO-8601 文本；非法值取 now")
    void versionTsParsing() {
        long iso = Instant.parse("2026-01-01T00:00:05Z").toEpochMilli();
        assertEquals(iso, JdbcSource.parseVersionTs("2026-01-01T00:00:05Z", 1L));
        assertEquals(123L, JdbcSource.parseVersionTs("123", 1L));
        assertEquals(123L, JdbcSource.parseVersionTs(" 123 ", 1L));
        assertEquals(42L, JdbcSource.parseVersionTs(null, 42L));
        assertEquals(42L, JdbcSource.parseVersionTs("not-a-ts", 42L));
        assertEquals(42L, JdbcSource.parseVersionTs("2026-13-99 99:99", 42L));
    }

    @Test
    @DisplayName("normalizeDimType：resource/items→item，levels→level，其余小写透传")
    void dimTypeNormalization() {
        assertEquals("item", JdbcSource.normalizeDimType("resource"));
        assertEquals("item", JdbcSource.normalizeDimType("items"));
        assertEquals("level", JdbcSource.normalizeDimType("levels"));
        assertEquals("level", JdbcSource.normalizeDimType("LEVEL"));
        assertEquals("guild", JdbcSource.normalizeDimType("Guild"));
        assertEquals("item", JdbcSource.normalizeDimType(null));
    }

    @Test
    @DisplayName("JdbcSource 元信息取自配置")
    void sourceMetadata() {
        AgentConfig cfg = new AgentConfig();
        cfg.sourceType = "mysql";
        cfg.jdbcUrl = "jdbc:mysql://localhost:3306/game";
        cfg.dimType = "item";
        JdbcSource s = new JdbcSource(cfg);
        assertEquals("mysql", s.type());
        assertTrue(s.name().contains("jdbc:mysql://localhost:3306/game"));
    }
}
