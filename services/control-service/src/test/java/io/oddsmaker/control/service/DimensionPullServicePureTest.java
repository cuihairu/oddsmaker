package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DimensionPullService 纯函数矩阵：断点 URL 拼装、游戏 API 响应解析（数组键探测/断点归一）、
 * 行翻译（控制列/attributes/维度归一/version_ts 三态）、NDJSON 与 maxVersionTs。
 * 事件契约对齐 GatewaySink（dimension_define/dimension + props 内嵌 attributes）与
 * dimension-sync-job parseProps（source_type=pull 新增键，下游忽略不报错）。
 */
@DisplayName("HTTP Pull 纯函数矩阵")
class DimensionPullServicePureTest {

    private static final ObjectMapper OM = new ObjectMapper();

    private static JsonNode row(String json) {
        try {
            return OM.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ===== buildEndpointUrl =====

    @Test
    @DisplayName("断点 URL：无 cursor 只带 limit，cursor 编码后续接 updated_after")
    void endpointUrl() {
        assertEquals("https://api.game-x.com/v1/items?limit=1000",
                DimensionPullService.buildEndpointUrl("https://api.game-x.com/v1/items", null, 1000));
        assertEquals("https://api.game-x.com/v1/items?updated_after=1760000000000&limit=500",
                DimensionPullService.buildEndpointUrl("https://api.game-x.com/v1/items", "1760000000000", 500));
        assertEquals("https://api.game-x.com/v1/items?type=weapon&updated_after=1760000000000&limit=100",
                DimensionPullService.buildEndpointUrl("https://api.game-x.com/v1/items?type=weapon", "1760000000000", 100));
        // cursor 特殊字符 URL 编码（服务端 opaque cursor 不破坏查询串）
        assertEquals("https://api.game-x.com/v1/items?updated_after=a%2Fb%3Dc&limit=10",
                DimensionPullService.buildEndpointUrl("https://api.game-x.com/v1/items", "a/b=c", 10));
    }

    // ===== parseGameResponse =====

    @Test
    @DisplayName("响应解析：items 主键 + next_cursor 透传")
    void parseItemsKey() {
        DimensionPullService.PullPage page = DimensionPullService.parseGameResponse(
                "{\"items\":[{\"item_code\":\"s1\"},{\"item_code\":\"s2\"}],\"next_cursor\":\"1761\"}");
        assertEquals(2, page.rows().size());
        assertEquals("s1", page.rows().get(0).path("item_code").asText());
        assertEquals("1761", page.nextCursor());
    }

    @ParameterizedTest
    @CsvSource({"resources", "levels", "data"})
    @DisplayName("响应解析：resources/levels/data 兼容键探测")
    void parseFallbackKeys(String key) {
        DimensionPullService.PullPage page = DimensionPullService.parseGameResponse(
                "{\"" + key + "\":[{\"id\":\"x\"}]}");
        assertEquals(1, page.rows().size());
        assertNull(page.nextCursor());
    }

    @Test
    @DisplayName("响应解析：next_cursor 空串归 null（窗口结束语义）")
    void parseEmptyCursor() {
        DimensionPullService.PullPage page = DimensionPullService.parseGameResponse(
                "{\"items\":[],\"next_cursor\":\"\"}");
        assertEquals(0, page.rows().size());
        assertNull(page.nextCursor());
    }

    @Test
    @DisplayName("响应解析：缺条目数组/坏 JSON/非对象根均报错")
    void parseRejects() {
        assertThrows(IllegalArgumentException.class,
                () -> DimensionPullService.parseGameResponse("{\"rows\":[]}"));
        assertThrows(IllegalArgumentException.class,
                () -> DimensionPullService.parseGameResponse("not-json"));
        assertThrows(IllegalArgumentException.class,
                () -> DimensionPullService.parseGameResponse("[1,2]"));
    }

    // ===== rowToEvent / translateRows =====

    @Test
    @DisplayName("行翻译：事件契约字段齐全，props.source_type=pull，attributes 剔控制列")
    void rowToEventContract() {
        JsonNode r = row("{\"item_code\":\"sword_001\",\"item_name\":\"铁剑\",\"rarity\":\"common\","
                + "\"type\":\"weapon\",\"op\":\"upsert\",\"updated_at\":1760000000000}");
        Map<String, Object> event = DimensionPullService.rowToEvent(r, "game_x", "prod", "item");
        assertEquals("dimension_define", event.get("event_name"));
        assertEquals("dimension", event.get("event_type"));
        assertEquals("game_x", event.get("game_id"));
        assertEquals("prod", event.get("environment"));
        assertEquals("oddsmaker-control-pull", event.get("device_id"));
        assertNotNull(event.get("event_id"));
        assertNotNull(event.get("ts_server"));

        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) event.get("props");
        assertEquals("pull", props.get("source_type"));
        assertEquals("item", props.get("dim_type"));
        assertEquals("sword_001", props.get("resource_id"));
        assertEquals("upsert", props.get("op"));
        assertEquals(1760000000000L, props.get("version_ts"));

        @SuppressWarnings("unchecked")
        Map<String, Object> attrs = (Map<String, Object>) props.get("attributes");
        assertEquals("铁剑", attrs.get("item_name"));
        assertEquals("common", attrs.get("rarity"));
        assertFalse(attrs.containsKey("updated_at"));
        assertFalse(attrs.containsKey("op"));
        assertFalse(attrs.containsKey("item_code"));
    }

    @Test
    @DisplayName("行翻译：resource_id 候选键按序回落，dim_type 行级覆盖并归一")
    void rowCandidates() {
        Map<String, Object> byLevelId = DimensionPullService.rowToEvent(
                row("{\"level_id\":\"lv_3\",\"version_ts\":1}"), "g", "dev", "level");
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) byLevelId.get("props");
        assertEquals("lv_3", props.get("resource_id"));
        assertEquals("level", props.get("dim_type"));

        // items → item 归一；行级 dim_type 覆盖配置缺省
        Map<String, Object> normalized = DimensionPullService.rowToEvent(
                row("{\"resource_id\":\"r1\",\"dim_type\":\"items\"}"), "g", "dev", "level");
        @SuppressWarnings("unchecked")
        Map<String, Object> np = (Map<String, Object>) normalized.get("props");
        assertEquals("item", np.get("dim_type"));
    }

    @Test
    @DisplayName("行翻译：缺资源标识/非对象条目报错（不静默丢行）")
    void rowRejects() {
        assertThrows(IllegalArgumentException.class,
                () -> DimensionPullService.rowToEvent(row("{\"name\":\"no-id\"}"), "g", "dev", "item"));
        assertThrows(IllegalArgumentException.class,
                () -> DimensionPullService.rowToEvent(row("\"scalar\""), "g", "dev", "item"));
    }

    @Test
    @DisplayName("version_ts：毫秒数字 > 数字文本 > ISO instant > ISO 本地时间，全缺取 now")
    void versionTsVariants() throws Exception {
        long now = 1700000000000L;
        assertEquals(1760000000000L, DimensionPullService.parseVersionTs(row("{\"version_ts\":1760000000000}"), now));
        assertEquals(1760000000000L, DimensionPullService.parseVersionTs(row("{\"version_ts\":\"1760000000000\"}"), now));
        assertEquals(1760000000000L, DimensionPullService.parseVersionTs(
                row("{\"updated_at\":\"2025-10-09T08:53:20Z\"}"), now));
        assertEquals(1760000000000L, DimensionPullService.parseVersionTs(
                row("{\"updated_ts\":\"2025-10-09T08:53:20\"}"), now));
        assertEquals(now, DimensionPullService.parseVersionTs(row("{\"updated_at\":\"garbage\"}"), now));
        assertEquals(now, DimensionPullService.parseVersionTs(row("{}"), now));
    }

    // ===== NDJSON / maxVersionTs =====

    @Test
    @DisplayName("NDJSON：多行拼接与 JSON 往返一致，maxVersionTs 取本页最大")
    void ndjsonAndMax() {
        List<Map<String, Object>> events = DimensionPullService.translateRows(
                List.of(row("{\"id\":\"a\",\"version_ts\":100}"), row("{\"id\":\"b\",\"updated_at\":200}")),
                "g", "prod", "item");
        assertEquals(2, events.size());
        assertEquals(200, DimensionPullService.maxVersionTs(events));

        String ndjson = DimensionPullService.toNdjson(events);
        String[] lines = ndjson.split("\n");
        assertEquals(2, lines.length);
        assertTrue(lines[0].contains("\"resource_id\":\"a\""));
        assertTrue(lines[1].contains("\"resource_id\":\"b\""));
        // 与 GatewaySink 的 NDJSON 契约同构：每行独立合法 JSON
        assertDoesNotThrowJson(lines[0]);
        assertDoesNotThrowJson(lines[1]);
    }

    @Test
    @DisplayName("NDJSON：空页产出空串（不推 Gateway）")
    void ndjsonEmpty() {
        List<Map<String, Object>> events = DimensionPullService.translateRows(List.of(), "g", "prod", "item");
        assertEquals("", DimensionPullService.toNdjson(events));
        assertEquals(0, DimensionPullService.maxVersionTs(events));
    }

    private static void assertDoesNotThrowJson(String line) {
        try {
            new ObjectMapper().readTree(line);
        } catch (Exception e) {
            throw new AssertionError("非法 NDJSON 行: " + line, e);
        }
    }

    // ===== normalizeDimType =====

    @Test
    @DisplayName("维度归一：resource/items→item，levels→level，其余原样小写")
    void dimTypeNormalize() {
        assertEquals("item", DimensionPullService.normalizeDimType("resource"));
        assertEquals("item", DimensionPullService.normalizeDimType("ITEMS"));
        assertEquals("level", DimensionPullService.normalizeDimType("levels"));
        assertEquals("skill", DimensionPullService.normalizeDimType("Skill"));
        assertEquals("item", DimensionPullService.normalizeDimType(null));
    }
}
