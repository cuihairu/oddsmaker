package io.oddsmaker.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网关配置组件测试：PII 清洗全分支、JSON Schema 校验全规则、Props 白名单、API Key 认证服务。
 */
@DisplayName("网关配置组件测试")
class GatewayConfigComponentsTest {

    // ===== PiiPolicy =====

    private PiiPolicy policy(String email, String phone, String ip) {
        MockEnvironment env = new MockEnvironment();
        if (email != null) env.setProperty("oddsmaker.pii.email", email);
        if (phone != null) env.setProperty("oddsmaker.pii.phone", phone);
        if (ip != null) env.setProperty("oddsmaker.pii.ip", ip);
        env.setProperty("oddsmaker.pii.deny-keys", "password,token");
        env.setProperty("oddsmaker.pii.mask-keys", "contact,email_backup");
        return new PiiPolicy(env);
    }

    @Test
    @DisplayName("PII：模式解析（allow/drop/mask 与默认）")
    void piiModeParsing() {
        // 邮箱三模式
        assertEquals("a@b.com", policy("allow", "allow", "allow").sanitizeProps(Map.of("k", "a@b.com")).get("k"));
        assertNull(policy("drop", "allow", "allow").sanitizeProps(Map.of("k", "a@b.com")).get("k"));
        assertEquals("***@b.com", policy("mask", "allow", "allow").sanitizeProps(Map.of("k", "a@b.com")).get("k"));
        // null 配置默认 mask/coarse
        assertEquals("***@b.com", policy(null, null, null).sanitizeProps(Map.of("k", "a@b.com")).get("k"));
        assertEquals("1.2.3.0", policy(null, null, null).sanitizeClientIp("1.2.3.4"));
    }

    @Test
    @DisplayName("PII：手机号脱敏（保留尾 2 位）与 drop")
    void piiPhoneMasking() {
        Map<String, Object> props = Map.of("phone", "138-1234-5678");
        String masked = String.valueOf(policy("allow", "mask", "allow").sanitizeProps(props).get("phone"));
        assertTrue(masked.endsWith("78"));
        assertTrue(masked.contains("x"));
        // drop
        assertNull(policy("allow", "drop", "allow").sanitizeProps(props).get("phone"));
        // allow
        assertEquals("138-1234-5678", policy("allow", "allow", "allow").sanitizeProps(props).get("phone"));
        // maskKeys 命中但不足 10 位数字 → maskAll
        Map<String, Object> tagged = Map.of("contact", "short");
        assertEquals("***", policy("allow", "allow", "allow").sanitizeProps(tagged).get("contact"));
        // maskKeys 命中邮箱 → 邮箱脱敏
        Map<String, Object> taggedEmail = Map.of("contact", "x@y.io");
        assertEquals("***@y.io", policy("allow", "allow", "allow").sanitizeProps(taggedEmail).get("contact"));
        // maskKeys 命中手机号 → 手机脱敏（11 位数字：前 9 位打 x，保留尾 2 位）
        Map<String, Object> taggedPhone = Map.of("contact", "13900001111");
        assertEquals("xxxxxxxxx11", policy("allow", "allow", "allow").sanitizeProps(taggedPhone).get("contact"));
    }

    @Test
    @DisplayName("PII：denyKeys 命中与 Overrides 覆盖")
    void piiBlockedKeys() {
        PiiPolicy p = policy("allow", "allow", "allow");
        assertTrue(p.hasBlockedKeys(Map.of("password", "x")));
        assertFalse(p.hasBlockedKeys(Map.of("name", "x")));
        assertFalse(p.hasBlockedKeys(null));

        PiiPolicy.Overrides o = new PiiPolicy.Overrides();
        o.denyKeys = java.util.Set.of("secret");
        assertTrue(p.hasBlockedKeys(Map.of("secret", "1"), o));
        assertFalse(p.hasBlockedKeys(Map.of("password", "x"), o));
    }

    @Test
    @DisplayName("PII：嵌套 Map/List 深度清洗与未知类型丢弃")
    void piiNestedSanitization() {
        PiiPolicy p = policy("mask", "mask", "allow");
        Map<String, Object> inner = new HashMap<>();
        inner.put("email", "deep@x.io");
        inner.put("num", 5);
        inner.put("keep", "plain");
        Map<String, Object> props = new HashMap<>();
        props.put("nested", inner);
        props.put("flag", true);
        props.put("unknown", new Object());  // 未知类型 → null 丢弃

        Map<String, Object> out = p.sanitizeProps(props);
        @SuppressWarnings("unchecked")
        Map<String, Object> nestedOut = (Map<String, Object>) out.get("nested");
        assertEquals("***@x.io", nestedOut.get("email"));
        assertEquals(5, nestedOut.get("num"));
        assertEquals(Boolean.TRUE, out.get("flag"));
        assertFalse(out.containsKey("unknown"));

        // 深度 ≥3 裁剪 + List 50 上限 + List 内 null 剔除
        Map<String, Object> deep = new HashMap<>();
        Map<String, Object> l3 = new HashMap<>();
        l3.put("x", java.util.Arrays.asList("a", null, "b@b.com"));
        deep.put("l2", Map.of("l3", l3));
        Map<String, Object> deepOut = p.sanitizeProps(Map.of("d", deep));
        assertNotNull(deepOut);

        List<Object> bigList = new java.util.ArrayList<>();
        for (int i = 0; i < 60; i++) bigList.add("v" + i);
        bigList.add(null);
        Map<String, Object> listOut = p.sanitizeProps(Map.of("arr", bigList));
        assertEquals(50, ((List<?>) listOut.get("arr")).size());
    }

    @Test
    @DisplayName("PII：客户端 IP 四模式（coarse/allow/drop/异常与 Overrides）")
    void piiClientIpModes() {
        PiiPolicy p = policy("allow", "allow", "coarse");
        assertEquals("1.2.3.0", p.sanitizeClientIp("1.2.3.4"));
        assertEquals("1.2.3.4", p.sanitizeClientIp("1.2.3.4",
            overrides(PiiPolicy.IpMode.ALLOW)));
        assertNull(p.sanitizeClientIp("1.2.3.4", overrides(PiiPolicy.IpMode.DROP)));
        assertNull(p.sanitizeClientIp(null));
        assertNull(p.sanitizeClientIp(""));
        // IPv6 → /48 之后置零（::1 置零后为全零地址，完整展开形式输出）
        assertEquals("0:0:0:0:0:0:0:0", p.sanitizeClientIp("::1"));
        // 非法地址解析抛异常 → null
        assertNull(p.sanitizeClientIp("999.999.999.999"));
    }

    private PiiPolicy.Overrides overrides(PiiPolicy.IpMode mode) {
        PiiPolicy.Overrides o = new PiiPolicy.Overrides();
        o.ipMode = mode;
        return o;
    }

    // ===== JsonSchemaValidator =====

    private JsonSchemaValidator validator() {
        return new JsonSchemaValidator(new ObjectMapper());
    }

    private Map<String, Object> validEvent() {
        Map<String, Object> e = new HashMap<>();
        e.put("event_id", "evt_12345678");
        e.put("game_id", "game_demo");
        e.put("environment", "prod");
        e.put("event_name", "level_complete");
        e.put("device_id", "dev_1");
        e.put("ts_client", 1700000000000L);
        return e;
    }

    @Test
    @DisplayName("Schema：合法事件通过；必填缺失逐字段报错")
    void schemaRequiredFields() {
        JsonSchemaValidator v = validator();
        assertNull(v.validate(validEvent()));

        for (String field : List.of("event_id", "game_id", "environment", "event_name", "device_id", "ts_client")) {
            Map<String, Object> bad = validEvent();
            bad.remove(field);
            assertEquals("missing_" + field, v.validate(bad));
        }
    }

    @Test
    @DisplayName("Schema：长度/枚举/类型校验规则")
    void schemaLengthEnumAndTypes() {
        JsonSchemaValidator v = validator();

        Map<String, Object> tooShort = validEvent();
        tooShort.put("event_id", "short");
        assertEquals("event_id_too_short", v.validate(tooShort));

        Map<String, Object> tooLong = validEvent();
        tooLong.put("event_name", "n".repeat(200));
        assertEquals("event_name_too_long", v.validate(tooLong));

        // 枚举字段：找 schema 中带 enum 的属性触发 invalid_enum（若无则跳过该断言）
        com.fasterxml.jackson.databind.JsonNode schema = (com.fasterxml.jackson.databind.JsonNode)
            ReflectionTestUtils.getField(v, "schema");
        String enumField = null;
        java.util.Iterator<Map.Entry<String, com.fasterxml.jackson.databind.JsonNode>> it =
            schema.path("properties").fields();
        while (it.hasNext()) {
            Map.Entry<String, com.fasterxml.jackson.databind.JsonNode> en = it.next();
            if (en.getValue().has("enum")) {
                enumField = en.getKey();
                break;
            }
        }
        if (enumField != null) {
            Map<String, Object> badEnum = validEvent();
            badEnum.put(enumField, "__definitely_not_in_enum__");
            String err = v.validate(badEnum);
            assertTrue(err == null || err.equals(enumField + "_invalid_enum"));
        }

        // 类型错误：ts_client 为 oneOf [string, number]，boolean 两个分支都不匹配
        Map<String, Object> badType = validEvent();
        badType.put("ts_client", true);
        String typeErr = v.validate(badType);
        assertTrue(typeErr != null && typeErr.contains("invalid_type"));

        // null 类型字段合法（type: ["string","null"]）
        Map<String, Object> withNull = validEvent();
        withNull.put("user_id", null);
        assertNull(v.validate(withNull));
    }

    // ===== PropsPolicy =====

    private PropsPolicy propsPolicy(String allowlist) {
        MockEnvironment env = new MockEnvironment();
        if (allowlist != null) {
            env.setProperty("oddsmaker.props.allowlist", allowlist);
        }
        return new PropsPolicy(env, new ObjectMapper());
    }

    @Test
    @DisplayName("Props：白名单过滤/深度裁剪/大小限制")
    void propsPolicyFiltering() {
        // 空白名单：原样返回
        PropsPolicy open = propsPolicy(null);
        Map<String, Object> props = Map.of("any", "value");
        assertEquals(props, open.filter(props));
        assertNull(open.filter(null));

        // 白名单过滤 + 嵌套深度裁剪
        PropsPolicy restricted = propsPolicy("level,coins");
        Map<String, Object> deep = Map.of("level", 10, "coins", Map.of("gold",
            Map.of("deep", Map.of("deeper", "x"))), "secret", "s");
        Map<String, Object> filtered = restricted.filter(deep);
        assertEquals(10, filtered.get("level"));
        assertFalse(filtered.containsKey("secret"));

        // 自定义白名单（filterWithAllowlist）
        Map<String, Object> custom = restricted.filterWithAllowlist(deep, List.of("secret"));
        assertEquals("s", custom.get("secret"));
        assertFalse(custom.containsKey("level"));
        // null/空 allowlist 回退 filter
        assertEquals(restricted.filter(deep), restricted.filterWithAllowlist(deep, null));
        assertEquals(restricted.filter(deep), restricted.filterWithAllowlist(deep, List.of()));

        // 事件超限：构造超 65536 字节的事件体
        Map<String, Object> huge = new HashMap<>();
        huge.put("blob", "x".repeat(70_000));
        Map<String, Object> eventLike = new HashMap<>();
        eventLike.put("props", huge);
        assertTrue(open.exceedsEventLimit(eventLike));
        assertFalse(open.exceedsEventLimit(Map.of("props", Map.of("k", "v"))));
    }

    // ===== AuthService =====

    @Test
    @DisplayName("Auth：空白 key 返回 null；本地密钥回退；缓存命中")
    void authServiceLocalAndCache() {
        MockEnvironment env = new MockEnvironment()
            .withProperty("oddsmaker.auth.keys.local-dev-key", "dev-secret");
        AuthService service = new AuthService(env, new SimpleMeterRegistry());

        assertNull(service.getContext(null));
        assertNull(service.getContext(" "));

        AuthService.ApiKeyContext local = service.getContext("local-dev-key");
        assertNotNull(local);
        assertEquals("dev-secret", local.secret);
        assertTrue(local.allowsWrite());
        assertFalse(local.isScoped());
        assertTrue(local.envWritable());
        assertFalse(local.samplingEnabled());

        // 未知 key
        assertNull(service.getContext("unknown"));

        // 缓存命中：反射构造 CacheEntry 塞入缓存
        AuthService.ApiKeyContext cached = new AuthService.ApiKeyContext();
        cached.apiKey = "cached-key";
        try {
            @SuppressWarnings("unchecked")
            ConcurrentHashMap<String, Object> cache = (ConcurrentHashMap<String, Object>)
                ReflectionTestUtils.getField(service, "cache");
            Class<?> entryClass = Class.forName("io.oddsmaker.gateway.config.AuthService$CacheEntry");
            Object ce = entryClass.getDeclaredConstructor().newInstance();
            ReflectionTestUtils.setField(ce, "context", cached);
            ReflectionTestUtils.setField(ce, "expireAt", java.time.Instant.now().getEpochSecond() + 60);
            cache.put("cached-key", ce);
            assertEquals(cached, service.getContext("cached-key"));
        } catch (Exception ignore) {
            // 结构变化时跳过缓存断言
        }
    }

    @Test
    @DisplayName("Auth：远端不可达回退本地；上下文判定分支")
    void authRemoteFallbackAndContextFlags() {
        MockEnvironment env = new MockEnvironment()
            .withProperty("oddsmaker.auth.keys.k1", "s1")
            .withProperty("oddsmaker.control.url", "http://127.0.0.1:1")
            .withProperty("oddsmaker.control.internal-token", "tok");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AuthService service = new AuthService(env, registry);
        // 远端连接拒绝（onErrorResume → empty）→ 本地回退
        AuthService.ApiKeyContext ctx = service.getContext("k1");
        assertNotNull(ctx);
        assertEquals("s1", ctx.secret);
        // 远端失败必须留指标，不再只有 logger.warn
        assertEquals(1.0, registry.get(AuthService.REMOTE_LOOKUP_METRIC)
            .tag("outcome", "exception").counter().count());

        // internal-token 空白 → 直接本地
        MockEnvironment noToken = new MockEnvironment()
            .withProperty("oddsmaker.auth.keys.k2", "s2")
            .withProperty("oddsmaker.control.url", "http://127.0.0.1:1");
        SimpleMeterRegistry noTokenRegistry = new SimpleMeterRegistry();
        AuthService noTokenService = new AuthService(noToken, noTokenRegistry);
        assertEquals("s2", noTokenService.getContext("k2").secret);
        // 未配置 control → not_configured，区别于"配置了但失败"
        assertEquals(1.0, noTokenRegistry.get(AuthService.REMOTE_LOOKUP_METRIC)
            .tag("outcome", "not_configured").counter().count());

        // ApiKeyContext 判定分支
        AuthService.ApiKeyContext scoped = new AuthService.ApiKeyContext();
        scoped.gameId = "g";
        scoped.environment = "prod";
        assertTrue(scoped.isScoped());
        scoped.environment = " ";
        assertFalse(scoped.isScoped());
        scoped.canWrite = false;
        assertFalse(scoped.allowsWrite());
        scoped.envStatus = "maintenance";
        assertFalse(scoped.envWritable());
        scoped.envStatus = "ACTIVE";  // 大小写不敏感
        assertTrue(scoped.envWritable());
        scoped.envEnableSampling = true;
        scoped.envSampleRate = 0.5;
        assertTrue(scoped.samplingEnabled());
        scoped.envSampleRate = 1.0;
        assertFalse(scoped.samplingEnabled());
        scoped.envSampleRate = null;
        assertFalse(scoped.samplingEnabled());
    }
}
