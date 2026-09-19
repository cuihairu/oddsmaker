package io.oddsmaker.jobs.dimension;

import io.oddsmaker.jobs.enrich.RawEvent;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.util.Collector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("维度同步 job")
class DimensionSyncJobTest {

    @Test
    void parsePropsSupportsResourceDimensionEvent() {
        String json = """
                {
                  "dim_type": "resource",
                  "item_code": "gold_coin",
                  "display_name": "Gold Coin",
                  "quality": "common",
                  "updated_at": "2026-06-30T10:00:00Z"
                }
                """;

        DimensionSyncJob.DimRecord rec = DimensionSyncJob.parseProps("game_demo", "production", json);

        assertNotNull(rec);
        assertEquals("game_demo", rec.gameId);
        assertEquals("prod", rec.environment);
        assertEquals("item", rec.dimType);
        assertEquals("gold_coin", rec.id);
        assertEquals("Gold Coin", rec.attributes.get("name"));
        assertEquals("common", rec.attributes.get("rarity"));
        assertTrue(rec.isCurrent);
    }

    @Test
    void parseDebeziumUsesAfterForUpsert() {
        String json = """
                {
                  "payload": {
                    "op": "u",
                    "ts_ms": 1782813600000,
                    "source": {
                      "table": "items"
                    },
                    "after": {
                      "item_code": "sword_001",
                      "display_name": "Iron Sword",
                      "quality": "rare"
                    }
                  }
                }
                """;

        DimensionSyncJob.DimRecord rec = DimensionSyncJob.parseDebezium(json, "game_demo", "prod", "item");

        assertNotNull(rec);
        assertEquals("game_demo", rec.gameId);
        assertEquals("prod", rec.environment);
        assertEquals("item", rec.dimType);
        assertEquals("sword_001", rec.id);
        assertEquals("Iron Sword", rec.attributes.get("name"));
        assertEquals("rare", rec.attributes.get("rarity"));
        assertTrue(rec.isCurrent);
    }

    @Test
    void parseDebeziumUsesBeforeForDelete() {
        String json = """
                {
                  "payload": {
                    "op": "d",
                    "ts_ms": 1782813600000,
                    "source": {
                      "table": "levels",
                      "game_id": "game_demo",
                      "environment": "staging"
                    },
                    "before": {
                      "level_id": "level_10",
                      "level_name": "Frozen Gate",
                      "level_difficulty": "hard"
                    }
                  }
                }
                """;

        DimensionSyncJob.DimRecord rec = DimensionSyncJob.parseDebezium(json, "", "", "");

        assertNotNull(rec);
        assertEquals("game_demo", rec.gameId);
        assertEquals("staging", rec.environment);
        assertEquals("level", rec.dimType);
        assertEquals("level_10", rec.id);
        assertEquals("Frozen Gate", rec.attributes.get("name"));
        assertEquals("hard", rec.attributes.get("difficulty"));
        assertFalse(rec.isCurrent);
    }

    // ===== 管道搭建（惰性，本地环境不 execute） =====

    @Test
    @DisplayName("buildPipeline：本地环境完成全管道搭建（source→flatMap→filter→JdbcSink）不抛异常")
    void buildPipelineWiresWholeGraphLazily() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.createLocalEnvironment(1);
        SingleOutputStreamOperator<DimensionSyncJob.DimRecord> head = DimensionSyncJob.buildPipeline(env,
                new String[]{"localhost:9092", "http://localhost:8081/apis/registry/v2", "oddsmaker.events_raw",
                        "jdbc:clickhouse://localhost:8123/default", "default", ""});
        assertNotNull(head);
        // 惰性验证：不 execute，但 transformation 图已包含 source + 两个 sink 分支
        assertTrue(env.getTransformations().size() >= 5, "expect >= 5 transformations, got " + env.getTransformations().size());
    }

    @Test
    @DisplayName("config：读 System properties，缺省回退默认值；逐项 getter 可覆盖")
    void configReadsPropertiesWithDefaults() {
        assertEquals("localhost:9092", DimensionSyncJob.kafkaBootstrap());
        assertEquals("http://localhost:8081/apis/registry/v2", DimensionSyncJob.registryUrl());
        assertEquals("oddsmaker.events_raw", DimensionSyncJob.kafkaTopic());
        assertEquals("jdbc:clickhouse://localhost:8123/default", DimensionSyncJob.chUrl());
        assertEquals("default", DimensionSyncJob.chUser());
        assertEquals("", DimensionSyncJob.chPass());
        assertEquals("oddsmaker-dimension-sync", DimensionSyncJob.JOB_NAME);

        System.setProperty("kafka.bootstrap", "kafka-1:9093");
        System.setProperty("registry.url", "http://registry:8081");
        System.setProperty("kafka.topic", "other.topic");
        System.setProperty("clickhouse.url", "jdbc:clickhouse://ch:8123/db");
        System.setProperty("clickhouse.user", "ch_admin");
        System.setProperty("clickhouse.pass", "secret");
        try {
            String[] cfg = DimensionSyncJob.config();
            assertEquals("kafka-1:9093", cfg[0]);
            assertEquals("http://registry:8081", cfg[1]);
            assertEquals("other.topic", cfg[2]);
            assertEquals("jdbc:clickhouse://ch:8123/db", cfg[3]);
            assertEquals("ch_admin", cfg[4]);
            assertEquals("secret", cfg[5]);
        } finally {
            System.clearProperty("kafka.bootstrap");
            System.clearProperty("registry.url");
            System.clearProperty("kafka.topic");
            System.clearProperty("clickhouse.url");
            System.clearProperty("clickhouse.user");
            System.clearProperty("clickhouse.pass");
        }
    }

    @Test
    @DisplayName("watermarks/isItem/isLevel：辅助构件（assigner 直调覆盖 lambda 体）")
    void helpers() {
        org.apache.flink.api.common.eventtime.WatermarkStrategy<RawEvent> wm = DimensionSyncJob.watermarks();
        assertNotNull(wm);
        // 有界乱序生成器可创建（Context 未被实现使用，传 null 安全）
        assertNotNull(wm.createWatermarkGenerator(null));
        org.apache.flink.api.common.eventtime.TimestampAssigner<RawEvent> assigner =
                wm.createTimestampAssigner(null);
        RawEvent e = new RawEvent();
        e.ts_server = 1_234_567_890L;   // micros
        assertEquals(1_234_567L, assigner.extractTimestamp(e, 0L));

        DimensionSyncJob.DimRecord item = new DimensionSyncJob.DimRecord();
        item.dimType = "item";
        DimensionSyncJob.DimRecord level = new DimensionSyncJob.DimRecord();
        level.dimType = "level";
        DimensionSyncJob.DimRecord other = new DimensionSyncJob.DimRecord();
        other.dimType = "npc";
        assertTrue(DimensionSyncJob.isItem(item));
        assertFalse(DimensionSyncJob.isItem(level));
        assertTrue(DimensionSyncJob.isLevel(level));
        assertFalse(DimensionSyncJob.isLevel(item));
        assertFalse(DimensionSyncJob.isItem(other));
        // 覆盖隐式构造器行
        assertNotNull(new DimensionSyncJob());
    }

    // ===== flatMap 体 =====

    /** Flink 1.19 Collector.close() 非默认实现，需匿名类补齐。 */
    private static Collector<DimensionSyncJob.DimRecord> sinkTo(List<DimensionSyncJob.DimRecord> out) {
        return new Collector<>() {
            @Override
            public void collect(DimensionSyncJob.DimRecord record) {
                out.add(record);
            }

            @Override
            public void close() {
            }
        };
    }

    @Test
    @DisplayName("dimRecords：非 dimension 事件/缺 game_id/缺 environment/空 props 全部静默丢弃")
    void dimRecordsDropsInvalidEvents() {
        List<DimensionSyncJob.DimRecord> out = new ArrayList<>();
        Collector<DimensionSyncJob.DimRecord> collector = sinkTo(out);

        RawEvent notDim = new RawEvent();
        notDim.event_type = "business";
        notDim.game_id = "g";
        notDim.environment = "prod";
        notDim.props_json = "{}";
        DimensionSyncJob.dimRecords(notDim, collector);   // 非 dimension

        RawEvent noGame = new RawEvent();
        noGame.event_type = "dimension";
        noGame.environment = "prod";
        noGame.props_json = "{}";
        DimensionSyncJob.dimRecords(noGame, collector);   // game_id null

        RawEvent noEnv = new RawEvent();
        noEnv.event_type = "dimension";
        noEnv.game_id = "g";
        noEnv.props_json = "{}";
        DimensionSyncJob.dimRecords(noEnv, collector);    // environment null

        RawEvent noProps = new RawEvent();
        noProps.event_type = "dimension";
        noProps.game_id = "g";
        noProps.environment = "prod";
        noProps.props_json = "";
        DimensionSyncJob.dimRecords(noProps, collector);   // 空 props

        RawEvent badProps = new RawEvent();
        badProps.event_type = "dimension";
        badProps.game_id = "g";
        badProps.environment = "prod";
        badProps.props_json = "{\"dim_type\":\"item\"}";  // 无 id → parseProps 返回 null
        DimensionSyncJob.dimRecords(badProps, collector);

        assertTrue(out.isEmpty());
    }

    @Test
    @DisplayName("dimRecords：合法 dimension 事件解析后收集")
    void dimRecordsCollectsValidDimension() {
        List<DimensionSyncJob.DimRecord> out = new ArrayList<>();
        RawEvent e = new RawEvent();
        e.event_type = "dimension";
        e.game_id = "game_demo";
        e.environment = "prod";
        e.props_json = "{\"dim_type\":\"level\",\"level_id\":\"lv_1\",\"level_name\":\"Gate\"}";
        DimensionSyncJob.dimRecords(e, sinkTo(out));
        assertEquals(1, out.size());
        assertEquals("level", out.get(0).dimType);
        assertEquals("lv_1", out.get(0).id);
    }

    // ===== 水位线 assigner =====

    @Test
    @DisplayName("eventMillis：ts_server 优先，回退 ts_client，均缺省用当前时间")
    void eventMillisFallsBackInOrder() {
        RawEvent both = new RawEvent();
        both.ts_server = 1_000_000_000L;   // micros → 1000ms
        both.ts_client = 2_000_000_000L;
        assertEquals(1_000_000L, DimensionSyncJob.eventMillis(both));

        RawEvent clientOnly = new RawEvent();
        clientOnly.ts_client = 2_000_000_000L;
        assertEquals(2_000_000L, DimensionSyncJob.eventMillis(clientOnly));

        RawEvent neither = new RawEvent();
        long before = System.currentTimeMillis();
        long got = DimensionSyncJob.eventMillis(neither);
        long after = System.currentTimeMillis();
        assertTrue(got >= before && got <= after, "expected now-based millis, got " + got);
    }

    // ===== JdbcSink binder =====

    /** Proxy 造 PreparedStatement：按 (方法名, 参数序号) 记录 set 值。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> recordingPs() {
        Map<String, Object> calls = new LinkedHashMap<>();
        InvocationHandler h = (Object proxy, Method method, Object[] args) -> {
            calls.put(method.getName() + ":" + args[0], args[1]);
            return null;
        };
        PreparedStatement ps = (PreparedStatement) Proxy.newProxyInstance(
                DimensionSyncJobTest.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, h);
        // 把 proxy 也塞进去，调用方取出后传给 binder
        calls.put("$ps", ps);
        return calls;
    }

    @Test
    @DisplayName("bindItemDim：9 个参数按序绑定（attributes 缺失键回退空串）")
    void bindItemDimSetsAllParameters() throws Exception {
        DimensionSyncJob.DimRecord r = new DimensionSyncJob.DimRecord();
        r.gameId = "game_demo";
        r.environment = "prod";
        r.dimType = "item";
        r.id = "sword_001";
        r.versionTs = new Timestamp(1_700_000_000_000L);
        r.attributes.put("name", "Iron Sword");

        Map<String, Object> calls = recordingPs();
        DimensionSyncJob.bindItemDim((PreparedStatement) calls.remove("$ps"), r);

        assertEquals("game_demo", calls.get("setString:1"));
        assertEquals("prod", calls.get("setString:2"));
        assertEquals("sword_001", calls.get("setString:3"));
        assertEquals("Iron Sword", calls.get("setString:4"));
        assertEquals("", calls.get("setString:5"));   // type 缺失 → 空串
        assertEquals("", calls.get("setString:7"));   // category 缺失 → 空串
        assertEquals(new Timestamp(1_700_000_000_000L), calls.get("setTimestamp:9"));
        assertEquals(9, calls.size());
    }

    @Test
    @DisplayName("bindLevelDim：7 个参数按序绑定")
    void bindLevelDimSetsAllParameters() throws Exception {
        DimensionSyncJob.DimRecord r = new DimensionSyncJob.DimRecord();
        r.gameId = "game_demo";
        r.environment = "staging";
        r.dimType = "level";
        r.id = "level_10";
        r.versionTs = new Timestamp(1_700_000_000_000L);
        r.attributes.put("difficulty", "hard");
        r.attributes.put("chapter", "ch2");

        Map<String, Object> calls = recordingPs();
        DimensionSyncJob.bindLevelDim((PreparedStatement) calls.remove("$ps"), r);

        assertEquals("game_demo", calls.get("setString:1"));
        assertEquals("staging", calls.get("setString:2"));
        assertEquals("level_10", calls.get("setString:3"));
        assertEquals("", calls.get("setString:4"));   // name 缺失 → 空串
        assertEquals("hard", calls.get("setString:5"));
        assertEquals("ch2", calls.get("setString:6"));
        assertEquals(new Timestamp(1_700_000_000_000L), calls.get("setTimestamp:7"));
        assertEquals(7, calls.size());
    }

    // ===== parseProps / parseDebezium 边角 =====

    @Test
    @DisplayName("parseProps：无 attributes 对象时顶层字段兜底（保留字段排除后全收）")
    void parsePropsFallsBackToTopLevelFields() {
        String json = """
                {
                  "dim_type": "item",
                  "resource_id": "gem_01",
                  "rarity": "epic",
                  "note": "crafted"
                }
                """;
        DimensionSyncJob.DimRecord rec = DimensionSyncJob.parseProps("g", "dev", json);
        assertNotNull(rec);
        assertEquals("item", rec.dimType);
        assertEquals("gem_01", rec.id);
        assertEquals("epic", rec.attributes.get("rarity"));
        assertEquals("crafted", rec.attributes.get("note"));
        assertFalse(rec.attributes.containsKey("resource_id")); // 保留字段不进 attributes
        assertFalse(rec.attributes.containsKey("dim_type"));

        // 空 attributes 对象（size=0）同样走顶层兜底
        DimensionSyncJob.DimRecord emptyAttrs = DimensionSyncJob.parseProps("g", "dev",
                "{\"dim_type\":\"item\",\"id\":\"x\",\"attributes\":{}}");
        assertNotNull(emptyAttrs);
        assertFalse(emptyAttrs.attributes.isEmpty()); // 顶层非保留字段被收编
    }

    @Test
    @DisplayName("parseProps：带 attributes 对象时只收 attributes（键归一化生效）")
    void parsePropsUsesAttributesObject() {
        String json = """
                {
                  "dim_type": "level",
                  "level_id": "lv_9",
                  "name": "不应进 attributes 的顶层字段",
                  "attributes": {
                    "display_name": "Crystal Cave",
                    "level_difficulty": "normal",
                    "chapter": "ch3"
                  }
                }
                """;
        DimensionSyncJob.DimRecord rec = DimensionSyncJob.parseProps("g", "dev", json);
        assertNotNull(rec);
        assertEquals("level", rec.dimType);
        assertEquals("lv_9", rec.id);
        assertEquals("Crystal Cave", rec.attributes.get("name"));
        assertEquals("normal", rec.attributes.get("difficulty"));
        assertEquals("ch3", rec.attributes.get("chapter"));
        assertEquals(3, rec.attributes.size()); // 顶层 name 不混入
    }

    @Test
    @DisplayName("parseProps：坏 JSON/version_ts 缺省/无 id 均安全返回 null 或 now 时间戳")
    void parsePropsEdgeCases() {
        assertNull(DimensionSyncJob.parseProps("g", "dev", "not-json"));
        assertNull(DimensionSyncJob.parseProps("g", "dev", "{}"));   // 无 id

        // version_ts <= 0 → 当前时间
        long before = System.currentTimeMillis();
        DimensionSyncJob.DimRecord rec = DimensionSyncJob.parseProps("g", "dev",
                "{\"dim_type\":\"item\",\"id\":\"x\"}");
        long after = System.currentTimeMillis();
        assertNotNull(rec);
        assertTrue(rec.versionTs.getTime() >= before && rec.versionTs.getTime() <= after);

        // 显式 version_ts 被采用
        DimensionSyncJob.DimRecord rec2 = DimensionSyncJob.parseProps("g", "dev",
                "{\"dim_type\":\"item\",\"id\":\"x\",\"version_ts\":1782813600000}");
        assertEquals(1782813600000L, rec2.versionTs.getTime());
    }

    @Test
    @DisplayName("parseDebezium：非对象 payload/data、缺关键字段、坏 JSON 均返回 null")
    void parseDebeziumInvalidEnvelopes() {
        assertNull(DimensionSyncJob.parseDebezium("[]", "g", "dev", "item"));          // payload 非 object
        assertNull(DimensionSyncJob.parseDebezium("{\"payload\":{\"op\":\"u\"}}", "g", "dev", "item")); // after 缺失
        assertNull(DimensionSyncJob.parseDebezium("nope", "g", "dev", "item"));         // 坏 JSON
        // 缺 game_id/environment（参数与 source 均空）
        assertNull(DimensionSyncJob.parseDebezium(
                "{\"payload\":{\"op\":\"u\",\"after\":{\"id\":\"x\"},\"source\":{\"table\":\"items\"}}}",
                "", "", "item"));
        // 空 id
        assertNull(DimensionSyncJob.parseDebezium(
                "{\"payload\":{\"op\":\"u\",\"after\":{},\"source\":{\"game_id\":\"g\",\"environment\":\"dev\",\"table\":\"items\"}}}",
                "", "", ""));
    }

    @Test
    @DisplayName("parseDebezium：op=r 读 after；ts_ms 缺省用当前时间")
    void parseDebeziumReadsAfterForReadOp() {
        String json = """
                {"payload":{"op":"r","after":{"id":"starter_pack"},"source":{"game_id":"g","environment":"production","table":"items"}}}
                """;
        long before = System.currentTimeMillis();
        DimensionSyncJob.DimRecord rec = DimensionSyncJob.parseDebezium(json, "", "", "");
        long after = System.currentTimeMillis();
        assertNotNull(rec);
        assertEquals("item", rec.dimType);
        assertEquals("prod", rec.environment);
        assertTrue(rec.versionTs.getTime() >= before && rec.versionTs.getTime() <= after);
    }

    // ===== 纯函数直测 =====

    @Test
    @DisplayName("normalizeEnv/normalizeDimType：production→prod；resource/items→item；levels→level；null→item")
    void normalizers() {
        assertEquals("prod", DimensionSyncJob.normalizeEnv("production"));
        assertEquals("staging", DimensionSyncJob.normalizeEnv("staging"));
        assertEquals("item", DimensionSyncJob.normalizeDimType("resource"));
        assertEquals("item", DimensionSyncJob.normalizeDimType("items"));
        assertEquals("level", DimensionSyncJob.normalizeDimType("levels"));
        assertEquals("npc", DimensionSyncJob.normalizeDimType("npc"));
        assertEquals("item", DimensionSyncJob.normalizeDimType(null));
    }

    @Test
    @DisplayName("firstNonEmpty/putAttribute：依次取值与属性键归一化")
    void fieldAndAttributeHelpers() throws Exception {
        com.fasterxml.jackson.databind.JsonNode node =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"b\":\"\",\"c\":\"third\"}");
        assertNull(DimensionSyncJob.firstNonEmpty(node, "missing", "b")); // 全空 → null
        assertEquals("third", DimensionSyncJob.firstNonEmpty(node, "missing", "b", "c"));

        Map<String, String> attrs = new LinkedHashMap<>();
        DimensionSyncJob.putAttribute(attrs, "display_name", "Sword");
        DimensionSyncJob.putAttribute(attrs, "level_name", "Gate");
        DimensionSyncJob.putAttribute(attrs, "quality", "rare");
        DimensionSyncJob.putAttribute(attrs, "level_difficulty", "hard");
        DimensionSyncJob.putAttribute(attrs, "custom_key", "v");
        assertEquals("Gate", attrs.get("name"));       // 后写覆盖
        assertEquals("rare", attrs.get("rarity"));
        assertEquals("hard", attrs.get("difficulty"));
        assertEquals("v", attrs.get("custom_key"));
        assertEquals(4, attrs.size());
    }
    @Test
    @DisplayName("main：替身执行环境下完成入口（不触达真实集群）")
    void mainCompletesWithMockEnv() {
        try (org.mockito.MockedStatic<StreamExecutionEnvironment> mocked =
                 org.mockito.Mockito.mockStatic(StreamExecutionEnvironment.class)) {
            StreamExecutionEnvironment env = org.mockito.Mockito.mock(
                StreamExecutionEnvironment.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
            mocked.when(StreamExecutionEnvironment::getExecutionEnvironment).thenReturn(env);
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> DimensionSyncJob.main(new String[0]));
        }
    }
}
