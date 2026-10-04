package io.oddsmaker.common.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Event} 是所有 source/网关/下游只认的事件模型（纯字段 POJO，本体零依赖；
 * v1 包络 + 契约 v2 增量字段，06 计划书 §4.2）。
 * 这组测试钉的是它的<b>契约面</b>：
 *
 * <ul>
 *   <li><b>字段契约</b>：名字 + 类型逐一反射核对（漏列/改名/改类型立即编译期表更新并当场失败）；
 *       全部 public 非 final 非 static（Jackson 按公共字段读写的根保证）</li>
 *   <li><b>缺省值</b>：对象字段全 null、{@code tsClient} 为 0（字段直读不过 setter，无初始化副作用）</li>
 *   <li><b>JSON 往返</b>：camelCase 键、标量逐字段还原一致；{@code experiments}/
 *       {@code props} 两个 Map 字段往返内容相等（props 的嵌套对象/数组经 Jackson 还原为
 *       LinkedHashMap/ArrayList，内容等价）</li>
 *   <li><b>序列化形态（如实记录）</b>：默认 mapper 保留 null 字段（稀疏事件也全键输出），
 *       且对未知字段 fail（POJO 本身不带 @JsonIgnoreProperties 等宽容配置，容错在调用方）</li>
 * </ul>
 *
 * <p>JSON 断言用 Jackson：仓库 v1 模型的真实反序列化路径就是 Jackson
 * （gateway BatchController convertValue 到本类），testImplementation 不进 POJO 依赖面。
 */
class EventTest {

    /** 契约表：字段名:类型（Map 为原始类型判定）。新增/删改字段必须同步此表，缺失即测试失败。 */
    private static final String[] CONTRACT = {
            // Core identifiers
            "eventId:String", "gameId:String", "environment:String",
            // Event classification
            "eventType:String", "eventName:String",
            // Identity
            "userId:String", "deviceId:String", "playerId:String", "characterId:String", "sessionId:String",
            // Timestamps
            "tsClient:long", "tsServer:Long",
            // Client context
            "platform:String", "appVersion:String", "sdkVersion:String", "country:String",
            "clientIp:String", "userAgent:String",
            // Game context
            "serverId:String", "guildId:String", "matchId:String", "levelId:String",
            "gameMode:String", "difficulty:String", "progressionPath:String",
            // Revenue
            "orderId:String", "productId:String", "revenueAmount:Double", "revenueCurrency:String",
            "receiptHash:String",
            // Resource flow
            "virtualCurrency:String", "virtualAmount:Double", "itemId:String", "resourceId:String",
            "resourceAmount:Double", "flowType:String", "operationId:String", "operationType:String",
            // Ad
            "adNetwork:String", "adPlacement:String", "adFormat:String", "adImpressionId:String",
            // Risk & diagnostics
            "riskContext:String", "deviceFingerprint:String", "clientIntegrity:String",
            // Experiments & props
            "experiments:Map", "props:Map",
            // Contract v2 (gateway-authoritative, 06 计划书 §4.2)
            "eventVersion:Integer", "source:String", "trustLevel:String", "eventOrigin:String",
    };

    @Test
    @DisplayName("字段契约：契约表与实际字段一一对应（名字+精确类型+public 非 final 非 static），无多余字段")
    void fieldContractPinnedByReflection() throws Exception {
        for (String entry : CONTRACT) {
            int sep = entry.indexOf(':');
            String name = entry.substring(0, sep);
            String type = entry.substring(sep + 1);
            Field f = Event.class.getDeclaredField(name);
            assertEquals(type.equals("Map") ? Map.class : type.equals("long") ? long.class
                            : type.equals("Long") ? Long.class : type.equals("Double") ? Double.class
                            : type.equals("Integer") ? Integer.class
                            : String.class,
                    f.getType(), "字段类型不符: " + name);
            int mods = f.getModifiers();
            assertTrue(Modifier.isPublic(mods), name + " 必须 public（Jackson 字段读写的根保证）");
            assertTrue(!Modifier.isFinal(mods) && !Modifier.isStatic(mods), name + " 必须可变实例字段");
        }
        assertEquals(CONTRACT.length, Event.class.getDeclaredFields().length,
                "实际字段数与契约表不一致——新增/删除字段请同步 CONTRACT 表");
    }

    @Test
    @DisplayName("缺省值：new Event() 对象字段全 null、tsClient 为 0（无初始化副作用）")
    void defaultsAreNullAndZero() throws Exception {
        Event e = new Event();
        for (Field f : Event.class.getDeclaredFields()) {
            if (f.getType() == long.class) {
                assertEquals(0L, f.getLong(e), "tsClient 缺省应为 0");
            } else {
                assertNull(f.get(e), "缺省应为 null: " + f.getName());
            }
        }
    }

    @Test
    @DisplayName("JSON 往返：camelCase 键、标量逐字段还原一致，experiments/props 两个 Map 字段往返内容相等")
    void jacksonRoundTripKeepsScalarsAndMaps() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        Event original = new Event();
        original.eventId = "evt-1";
        original.gameId = "g1";
        original.environment = "prod";
        original.eventType = "business";
        original.eventName = "purchase";
        original.userId = "u1";
        original.deviceId = "d1";
        original.playerId = "p1";
        original.characterId = "c1";
        original.sessionId = "s1";
        original.tsClient = 1735689605000L;
        original.tsServer = 1735689605123L;
        original.platform = "ios";
        original.appVersion = "1.2.3";
        original.sdkVersion = "0.2.0";
        original.country = "CN";
        original.clientIp = "203.0.113.7";
        original.userAgent = "ua/1.0";
        original.serverId = "s-01";
        original.guildId = "guild-9";
        original.matchId = "m-77";
        original.levelId = "lvl-3";
        original.gameMode = "pvp";
        original.difficulty = "hard";
        original.progressionPath = "chapter/2";
        original.orderId = "ord-1";
        original.productId = "sku-1";
        original.revenueAmount = 12.5;
        original.revenueCurrency = "USD";
        original.receiptHash = "deadbeef";
        original.virtualCurrency = "gold";
        original.virtualAmount = 3.25;
        original.itemId = "item-1";
        original.resourceId = "res-1";
        original.resourceAmount = 7.75;
        original.flowType = "sink";
        original.operationId = "op-1";
        original.operationType = "purchase_item";
        original.adNetwork = "adn-1";
        original.adPlacement = "rewarded_home";
        original.adFormat = "rewarded";
        original.adImpressionId = "imp-1";
        original.riskContext = "{\"reason\":\"velocity\"}";
        original.deviceFingerprint = "fp-1";
        original.clientIntegrity = "attested";
        original.experiments = new LinkedHashMap<>(Map.of("exp-1", "variant-b"));
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("text", "中文值");
        props.put("count", 42);
        props.put("ratio", 1.5);
        props.put("flag", true);
        props.put("nested", Map.of("k", "v"));
        props.put("list", List.of(1, 2, 3));
        original.props = props;

        String json = mapper.writeValueAsString(original);

        // 键名契约：JSON 键 = Java 字段名（camelCase，无命名策略改写）
        for (String entry : CONTRACT) {
            assertTrue(json.contains("\"" + entry.substring(0, entry.indexOf(':')) + "\":"),
                    "JSON 缺少键: " + entry);
        }

        Event back = mapper.readValue(json, Event.class);
        for (Field f : Event.class.getDeclaredFields()) {
            Object expected = f.get(original);
            Object actual = f.get(back);
            assertEquals(expected, actual, "往返后字段不一致: " + f.getName());
        }
        // props 的嵌套结构经 Jackson 还原为 LinkedHashMap/ArrayList——内容等价、类型形态如实核对
        Map<?, ?> roundTrippedProps = back.props;
        assertEquals(6, roundTrippedProps.size());
        assertEquals("中文值", roundTrippedProps.get("text"));
        assertEquals(42, roundTrippedProps.get("count"));
        assertTrue(roundTrippedProps.get("nested") instanceof Map);
        assertTrue(roundTrippedProps.get("list") instanceof List);
        assertEquals(new ArrayList<>(List.of(1, 2, 3)), roundTrippedProps.get("list"));
        assertEquals(Map.of("exp-1", "variant-b"), back.experiments);
    }

    @Test
    @DisplayName("序列化形态（如实记录）：默认 mapper 保留 null 字段；未知 JSON 字段默认拒绝（宽容配置在调用方）")
    void nullsKeptAndUnknownFieldsRejectedByDefaultMapper() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        String sparseJson = mapper.writeValueAsString(new Event());
        assertTrue(sparseJson.contains("\"gameId\":null"), "默认应保留 null 字段（全键输出）");
        assertTrue(sparseJson.contains("\"tsClient\":0"));

        Event e = new Event();
        e.gameId = "g1";
        assertTrue(mapper.writeValueAsString(e).contains("\"environment\":null"));

        // POJO 无 @JsonIgnoreProperties：未知字段默认 fail——v1 契约的「多字段即拒绝」由 POJO 自身提供
        assertThrows(com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException.class,
                () -> mapper.readValue("{\"gameId\":\"g1\",\"unknown_field\":1}", Event.class));
    }
}
