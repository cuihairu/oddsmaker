package io.oddsmaker.gateway.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.common.model.Event;
import io.oddsmaker.gateway.config.PolicyService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * BatchController 私有纯函数的分支直测（对侧输入构造）：
 * 事件类型推断关键词矩阵、环境归一化边界、app_id 后缀解析边界、
 * clientIp 提取、policy 覆盖转换、parseEvents 的 contentType 与 null 行。
 * 与 CoverageTest 的端到端路径互补，覆盖短路条件的未走侧。
 */
@DisplayName("批量入口纯函数分支测试")
class BatchControllerPureFunctionsTest {

    /** 生产 gateway ObjectMapper 配置 SNAKE_CASE 命名策略，convertValue 依赖它映射 event_id 等 */
    private final BatchController controller = new BatchController(
            new ObjectMapper().setPropertyNamingStrategy(
                    com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE),
            null, null, null, null, null, null, null, null,
            new io.oddsmaker.gateway.inspector.EventInspectorBuffer(10, 4, 600_000L),
                new io.oddsmaker.gateway.metrics.DataQualityCounters());

    private String infer(String eventName) {
        return ReflectionTestUtils.invokeMethod(controller, "inferEventType", eventName);
    }

    @Test
    @DisplayName("事件类型推断：关键词矩阵对侧（ad 前缀/user 族各词）")
    void inferEventTypeKeywordMatrix() {
        assertEquals("risk", infer("fraud_check"));          // fraud 分支
        assertEquals("experiment", infer("experiment_view"));
        assertEquals("ad", infer("ad_click"));              // contains("ad_")
        assertEquals("ad", infer("adview"));                // 仅 startsWith("ad")——无 ad_ 的对侧
        assertEquals("progression", infer("quest_complete"));
        assertEquals("session", infer("session_end"));
        assertEquals("error", infer("crash_report"));
        assertEquals("resource", infer("currency_gain"));
        assertEquals("user", infer("login_success"));       // login 词
        assertEquals("user", infer("register_account"));    // register 词
        assertEquals("user", infer("signup_done"));         // signup 词
        assertEquals("user", infer("oauth_token"));         // auth 词
        assertEquals("design", infer("design_wheel"));
        assertEquals("business", infer("purchase"));        // 兜底
        assertEquals("business", infer(null));              // null 事件名
    }

    @Test
    @DisplayName("环境归一化：blank/env_ 恰等边界/下划线截断")
    void normalizeEnvironmentEdges() {
        assertNull(ReflectionTestUtils.invokeMethod(controller, "normalizeEnvironment", (Object) null));
        assertNull(ReflectionTestUtils.invokeMethod(controller, "normalizeEnvironment", "   "));
        assertEquals("env_", ReflectionTestUtils.invokeMethod(controller, "normalizeEnvironment", "env_"));
        assertEquals("x", ReflectionTestUtils.invokeMethod(controller, "normalizeEnvironment", "env_x"));
        assertEquals("prod", ReflectionTestUtils.invokeMethod(controller, "normalizeEnvironment", "production"));
        assertEquals("dev", ReflectionTestUtils.invokeMethod(controller, "normalizeEnvironment", "development"));
        assertEquals("staging", ReflectionTestUtils.invokeMethod(controller, "normalizeEnvironment", "STAGING"));
        assertEquals("beta", ReflectionTestUtils.invokeMethod(controller, "normalizeEnvironment", "game1__beta"));
    }

    @Test
    @DisplayName("app_id gameId 解析：blank 与恰等后缀长度边界")
    void parseGameIdFromAppIdEdges() {
        assertNull(ReflectionTestUtils.invokeMethod(controller, "parseGameIdFromAppId", (Object) null));
        assertNull(ReflectionTestUtils.invokeMethod(controller, "parseGameIdFromAppId", "  "));
        // 后缀恰等全长：length > suffix.length 不满足，落后续循环，最终原样返回
        // "__prod" 先落第二循环单下划线后缀 "_prod"：截去后余 "_"（比后缀长才截的条件走 false 的是 "__prod" 对 "__prod" 自身）
        assertEquals("_", ReflectionTestUtils.invokeMethod(controller, "parseGameIdFromAppId", "__prod"));
        // 恰等后缀：4 > 4 不成立，原样返回（length > suffix.length 的 false 侧）
        assertEquals("_dev", ReflectionTestUtils.invokeMethod(controller, "parseGameIdFromAppId", "_dev"));
        assertEquals("game1", ReflectionTestUtils.invokeMethod(controller, "parseGameIdFromAppId", "game1__prod"));
        assertEquals("game2", ReflectionTestUtils.invokeMethod(controller, "parseGameIdFromAppId", "game2_stage"));
    }

    @Test
    @DisplayName("app_id environment 解析：blank 边界")
    void parseEnvironmentFromAppIdEdges() {
        assertNull(ReflectionTestUtils.invokeMethod(controller, "parseEnvironmentFromAppId", (Object) null));
        assertNull(ReflectionTestUtils.invokeMethod(controller, "parseEnvironmentFromAppId", "\t"));
        assertEquals("prod", ReflectionTestUtils.invokeMethod(controller, "parseEnvironmentFromAppId", "game__production"));
        assertEquals("prod", ReflectionTestUtils.invokeMethod(controller, "parseEnvironmentFromAppId", "game_prod"));
        assertEquals("dev", ReflectionTestUtils.invokeMethod(controller, "parseEnvironmentFromAppId", "game_development"));
        assertEquals("staging", ReflectionTestUtils.invokeMethod(controller, "parseEnvironmentFromAppId", "game_staging"));
        assertNull(ReflectionTestUtils.invokeMethod(controller, "parseEnvironmentFromAppId", "game_plain"));
    }

    @Test
    @DisplayName("clientIp 提取：无 XFF 头的两侧（有/无 remoteAddress）")
    void extractClientIpSides() {
        MockServerHttpRequest noXffNoRemote = MockServerHttpRequest.post("/v1/batch").build();
        assertNull(ReflectionTestUtils.invokeMethod(controller, "extractClientIp", noXffNoRemote));

        MockServerHttpRequest noXffWithRemote = MockServerHttpRequest
                .post("/v1/batch").remoteAddress(new java.net.InetSocketAddress(0)).build();
        Object ip = ReflectionTestUtils.invokeMethod(controller, "extractClientIp", noXffWithRemote);
        assertNotNull(ip);

        // xff 存在但为空白串：isBlank 短路侧，回落 remoteAddress
        MockServerHttpRequest blankXff = MockServerHttpRequest
                .post("/v1/batch").header("x-forwarded-for", "   ").build();
        assertNull(ReflectionTestUtils.invokeMethod(controller, "extractClientIp", blankXff));
    }

    @Test
    @DisplayName("policyToOverrides：denyKeys null / maskKeys 空列表的组合侧")
    void policyToOverridesSides() {
        PolicyService.Policy p = new PolicyService.Policy();
        p.denyKeys = null;            // denyKeys null → 不覆盖（默认空集）
        p.maskKeys = List.of();       // maskKeys 空 → 不覆盖
        p.piiEmail = "allow";
        p.piiIp = "drop";
        Object overrides = ReflectionTestUtils.invokeMethod(controller, "policyToOverrides", p);
        // 用反射读字段断言（Overrides 是 PiiPolicy 内部类）
        Object emailMode = ReflectionTestUtils.getField(overrides, "emailMode");
        Object ipMode = ReflectionTestUtils.getField(overrides, "ipMode");
        assertNotNull(emailMode);
        assertNotNull(ipMode);
        assertNull(ReflectionTestUtils.getField(overrides, "denyKeys"));
        assertNull(ReflectionTestUtils.getField(overrides, "maskKeys"));

        // denyKeys 非 null 但空列表：同样不覆盖（!isEmpty 短路侧）
        PolicyService.Policy p2 = new PolicyService.Policy();
        p2.denyKeys = List.of();
        Object overrides2 = ReflectionTestUtils.invokeMethod(controller, "policyToOverrides", p2);
        assertNull(ReflectionTestUtils.getField(overrides2, "denyKeys"));
    }

    @Test
    @DisplayName("parseEvents：contentType null 走默认 json 分支")
    void parseEventsNullContentType() {
        byte[] body = "[{\"event_id\":\"e1\",\"event_name\":\"n\"}]".getBytes();
        @SuppressWarnings("unchecked")
        List<Event> events = ReflectionTestUtils.invokeMethod(controller, "parseEvents", body, (Object) null);
        assertEquals(1, events.size());
        assertEquals("e1", events.get(0).eventId);
    }

    @Test
    @DisplayName("PII 优先级链：Schema 级压 ApiKey 级——模式覆盖/名单并集/非法值与非法 JSON 回落")
    void schemaPiiPolicyPrecedenceChain() {
        // Schema email=drop 压 ApiKey allow；ApiKey phone 与 Schema 未设的 ip 保留；denyKeys 并集 + 小写归一
        PolicyService.Policy p = new PolicyService.Policy();
        p.piiEmail = "allow";
        p.piiPhone = "drop";
        p.piiIp = "coarse";
        p.denyKeys = List.of("K1");
        p.schemaPiiPolicy = "{\"email\":\"drop\",\"ip\":\"drop\",\"denyKeys\":[\"k2\"]}";
        Object overrides = ReflectionTestUtils.invokeMethod(controller, "policyToOverrides", p);
        assertEquals(io.oddsmaker.gateway.config.PiiPolicy.Mode.DROP,
                ReflectionTestUtils.getField(overrides, "emailMode"));
        assertEquals(io.oddsmaker.gateway.config.PiiPolicy.Mode.DROP,
                ReflectionTestUtils.getField(overrides, "phoneMode"));
        assertEquals(io.oddsmaker.gateway.config.PiiPolicy.IpMode.DROP,
                ReflectionTestUtils.getField(overrides, "ipMode"));
        assertEquals(java.util.Set.of("k1", "k2"), ReflectionTestUtils.getField(overrides, "denyKeys"));

        // 非法模式值/非字符串/空名单不动 ApiKey 级；maskKeys 并集
        PolicyService.Policy p2 = new PolicyService.Policy();
        p2.piiEmail = "allow";
        p2.piiIp = "coarse";
        p2.maskKeys = List.of("K1");
        p2.schemaPiiPolicy = "{\"email\":\"bogus\",\"ip\":42,\"maskKeys\":[\"contact\",\"  \"]}";
        Object o2 = ReflectionTestUtils.invokeMethod(controller, "policyToOverrides", p2);
        assertEquals(io.oddsmaker.gateway.config.PiiPolicy.Mode.ALLOW,
                ReflectionTestUtils.getField(o2, "emailMode"));
        assertEquals(io.oddsmaker.gateway.config.PiiPolicy.IpMode.COARSE,
                ReflectionTestUtils.getField(o2, "ipMode"));
        assertEquals(java.util.Set.of("k1", "contact"), ReflectionTestUtils.getField(o2, "maskKeys"));

        // 非法 JSON / 非对象 / 空白串：整体视为未设置，ApiKey 级原样
        for (String bad : List.of("{not-json", "[1,2]", "null", "   ")) {
            PolicyService.Policy pb = new PolicyService.Policy();
            pb.piiEmail = "drop";
            pb.denyKeys = List.of("k1");
            pb.schemaPiiPolicy = bad;
            Object ob = ReflectionTestUtils.invokeMethod(controller, "policyToOverrides", pb);
            assertEquals(io.oddsmaker.gateway.config.PiiPolicy.Mode.DROP,
                    ReflectionTestUtils.getField(ob, "emailMode"));
            assertEquals(java.util.Set.of("k1"), ReflectionTestUtils.getField(ob, "denyKeys"));
            assertNull(ReflectionTestUtils.getField(ob, "maskKeys"));
        }

        // schemaPiiPolicy null（无 Schema/静态 key）：不触碰任何字段
        PolicyService.Policy pn = new PolicyService.Policy();
        pn.piiEmail = "mask";
        Object on = ReflectionTestUtils.invokeMethod(controller, "policyToOverrides", pn);
        assertEquals(io.oddsmaker.gateway.config.PiiPolicy.Mode.MASK,
                ReflectionTestUtils.getField(on, "emailMode"));
        assertNull(ReflectionTestUtils.getField(on, "denyKeys"));
    }

    @Test
    @DisplayName("readCompatEvent：ts_client/ts_server 字符串数字与 ISO 时间戳两侧")
    void readCompatEventTimestampSides() throws Exception {
        ObjectMapper om = new ObjectMapper().setPropertyNamingStrategy(
                com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
        Method m = BatchController.class.getDeclaredMethod("readCompatEvent", com.fasterxml.jackson.databind.JsonNode.class);
        m.setAccessible(true);

        // 数字形态与 ISO 字符串形态（Instant.parse）与坏字符串（null 侧）
        var numeric = (Event) m.invoke(controller, om.readTree("{\"ts_client\":\"1730000000000\",\"ts_server\":\"1730000001000\"}"));
        assertEquals(1730000000000L, numeric.tsClient);
        assertEquals(1730000001000L, numeric.tsServer);

        var iso = (Event) m.invoke(controller, om.readTree(
                "{\"ts_client\":\"2024-11-27T02:13:20Z\",\"ts_server\":\"2024-11-27T02:13:21Z\"}"));
        assertEquals(1732673600000L, iso.tsClient);
        assertEquals(1732673601000L, iso.tsServer);

        // 坏字符串：normalizeTimestampField 不回写，convertValue 对 long 字段直接抛 IllegalArgumentException
        var badNode = om.readTree("{\"ts_client\":\"not-a-time\"}");
        org.junit.jupiter.api.Assertions.assertThrows(
                java.lang.reflect.InvocationTargetException.class, () -> m.invoke(controller, badNode));

        // 空串：convertValue 强转为 0 不抛（long 原始），parseEpochMillis 返回 null 不回写（null 侧）
        var blank = (Event) m.invoke(controller, om.readTree("{\"ts_client\":\"\",\"ts_server\":\" \"}"));
        assertEquals(0L, blank.tsClient);
        assertNull(blank.tsServer); // Long 装箱：空白串映射为 null
    }

    @Test
    @DisplayName("matchesApiKeyScope：keyContext null 直通")
    void matchesApiKeyScopeNullContext() throws Exception {
        Method m = BatchController.class.getDeclaredMethod("matchesApiKeyScope",
                Event.class, io.oddsmaker.gateway.config.AuthService.ApiKeyContext.class);
        m.setAccessible(true);
        Event e = new Event();
        e.gameId = "g";
        e.environment = "prod";
        assertEquals(Boolean.TRUE, m.invoke(controller, e, null));
    }

    @Test
    @DisplayName("maybeGunzip：identity 编码与 null 编码直通原字节")
    void maybeGunzipPassthroughSides() {
        byte[] raw = "plain".getBytes();
        Object keptIdentity = ReflectionTestUtils.invokeMethod(controller, "maybeGunzip", raw, "identity");
        assertEquals("plain", new String((byte[]) keptIdentity));
        Object keptNull = ReflectionTestUtils.invokeMethod(controller, "maybeGunzip", raw, (Object) null);
        assertEquals("plain", new String((byte[]) keptNull));
    }

    @Test
    @DisplayName("normalizeCompatFields：eventType 空白串触发推断")
    void normalizeCompatFieldsBlankType() {
        Event e = new Event();
        e.eventName = "login_x";
        e.eventType = "   ";
        ReflectionTestUtils.invokeMethod(controller, "normalizeCompatFields", e);
        assertEquals("user", e.eventType);

        // eventType 已有非空值：不触发推断（条件 false 侧）
        Event kept = new Event();
        kept.eventName = "login_x";
        kept.eventType = "resource";
        ReflectionTestUtils.invokeMethod(controller, "normalizeCompatFields", kept);
        assertEquals("resource", kept.eventType);
    }

    @Test
    @DisplayName("ndjson null 字面量行：readCompatEvent 内抛 NPE 走 catch 跳过")
    void ndjsonNullLineYieldsNullEvent() {
        // convertValue(NullNode, Event.class) 得 null 引用后，readCompatEvent 内 event.gameId 访问
        // 即 NPE——null 行由 parseEvents 的行级 catch 吞掉，不产生 null 元素
        @SuppressWarnings("unchecked")
        List<Event> r = ReflectionTestUtils.invokeMethod(controller, "parseEvents",
                "null\n{\"event_id\":\"e1\",\"event_name\":\"n\"}".getBytes(), "application/x-ndjson");
        assertEquals(1, r.size());
        assertEquals("e1", r.get(0).eventId);
    }

    @Test
    @DisplayName("applyPropsFilter：policy null（缓存过期 race 侧）与 props null 直通")
    void applyPropsFilterPolicyNullSide() {
        BatchController withPolicy = new BatchController(
                new ObjectMapper().setPropertyNamingStrategy(
                        com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE),
                null, null, new io.oddsmaker.gateway.config.PropsPolicy(
                        new org.springframework.mock.env.MockEnvironment()
                                .withProperty("oddsmaker.props.allowlist", "keep"),
                        new ObjectMapper()),
                null, null, null, null, null,
                new io.oddsmaker.gateway.inspector.EventInspectorBuffer(10, 4, 600_000L),
                new io.oddsmaker.gateway.metrics.DataQualityCounters());
        // policy null → 回落通用 allowlist 过滤
        io.oddsmaker.common.model.Event e = new io.oddsmaker.common.model.Event();
        e.props = new java.util.HashMap<>(Map.of("keep", "v", "junk", "w"));
        ReflectionTestUtils.invokeMethod(withPolicy, "applyPropsFilter", e, (Object) null);
        assertEquals(Map.of("keep", "v"), e.props);

        // props null → 直通不抛
        io.oddsmaker.common.model.Event noProps = new io.oddsmaker.common.model.Event();
        ReflectionTestUtils.invokeMethod(withPolicy, "applyPropsFilter", noProps, (Object) null);
        assertNull(noProps.props);
    }

    @Test
    @DisplayName("isUnknownEvent：B7 §4.4 分支矩阵——未下发不启用/dev 豁免/清单命中与未命中")
    void isUnknownEventBranchMatrix() {
        io.oddsmaker.common.model.Event e = new io.oddsmaker.common.model.Event();
        e.eventName = "bet_place";

        // keyContext null / 未 scoped：不启用
        assertEquals(false, BatchController.isUnknownEvent(e, null));
        io.oddsmaker.gateway.config.AuthService.ApiKeyContext unscoped =
                new io.oddsmaker.gateway.config.AuthService.ApiKeyContext();
        unscoped.rejectUnknownEvents = true;
        assertEquals(false, BatchController.isUnknownEvent(e, unscoped));

        // scoped 但未下发开关（null）或 false：不启用
        io.oddsmaker.gateway.config.AuthService.ApiKeyContext scoped =
                new io.oddsmaker.gateway.config.AuthService.ApiKeyContext();
        scoped.gameId = "g";
        scoped.environment = "prod";
        assertEquals(false, BatchController.isUnknownEvent(e, scoped));
        scoped.rejectUnknownEvents = false;
        assertEquals(false, BatchController.isUnknownEvent(e, scoped));

        // dev 环境豁免（即使开关打开）
        scoped.rejectUnknownEvents = true;
        scoped.environment = "dev";
        assertEquals(false, BatchController.isUnknownEvent(e, scoped));

        // prod + 开关：eventNames null/空=全未知；命中=已知；未命中=未知
        scoped.environment = "prod";
        assertEquals(true, BatchController.isUnknownEvent(e, scoped));
        scoped.eventNames = List.of();
        assertEquals(true, BatchController.isUnknownEvent(e, scoped));
        scoped.eventNames = List.of("bet_settle");
        assertEquals(true, BatchController.isUnknownEvent(e, scoped));
        scoped.eventNames = List.of("bet_place", "bet_settle");
        assertEquals(false, BatchController.isUnknownEvent(e, scoped));
    }
}
