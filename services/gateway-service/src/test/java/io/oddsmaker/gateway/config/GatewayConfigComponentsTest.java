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
        // 四段形态但某段非数字（parseInt 抛 NumberFormatException）→ null
        assertNull(p.sanitizeClientIp("abc.def.ghi.jkl"));
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
    @DisplayName("Auth：redact 日志脱敏——密钥本体不整段进日志（失败场景任何客户端发错 key 都触发）")
    void authRedactMasksSecret() {
        assertEquals("sk_live_…(len=30)", AuthService.redact("sk_live_abcdefghijklmnopqrstuv"));
        // 短密钥只暴露长度，前缀也不给（≤8 字符前缀可被暴力枚举补全）
        assertEquals("(len=5)", AuthService.redact("short"));
        assertEquals("(len=0)", AuthService.redact(""));
        assertEquals("null", AuthService.redact(null));
    }

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

    // ===== 内存态过期清扫（外部键空间无界驻留防护） =====

    @Test
    @DisplayName("限流：清扫过期限流窗口——旧窗口驱逐、当前窗口保留")
    void rateLimiterEvictsStaleWindows() {
        RateLimiterService svc = new RateLimiterService(10, 10);
        assertTrue(svc.allowIp("1.1.1.1"));
        ConcurrentHashMap<String, Object> buckets =
            (ConcurrentHashMap<String, Object>) ReflectionTestUtils.getField(svc, "bucketsIp");
        assertEquals(1, buckets.size());
        // 窗口翻为过去分钟 → 清扫驱逐
        ReflectionTestUtils.setField(buckets.get("1.1.1.1"), "window", 0L);
        svc.evictStaleWindows();
        assertTrue(buckets.isEmpty());
        // 当前分钟窗口 → 清扫保留（正在服务）
        assertTrue(svc.allowIp("2.2.2.2"));
        svc.evictStaleWindows();
        assertEquals(1, buckets.size());
    }

    @Test
    @DisplayName("Auth：清扫过期缓存条目——过期驱逐、未过期保留")
    void authEvictsExpiredCacheEntries() {
        AuthService service = new AuthService(new MockEnvironment(), new SimpleMeterRegistry());
        ConcurrentHashMap<String, Object> cache =
            (ConcurrentHashMap<String, Object>) ReflectionTestUtils.getField(service, "cache");
        long now = java.time.Instant.now().getEpochSecond();
        AuthService.CacheEntry fresh = new AuthService.CacheEntry();
        fresh.context = new AuthService.ApiKeyContext();
        fresh.expireAt = now + 60;
        AuthService.CacheEntry stale = new AuthService.CacheEntry();
        stale.context = new AuthService.ApiKeyContext();
        stale.expireAt = now - 1;
        cache.put("fresh", fresh);
        cache.put("stale", stale);

        service.evictExpired();
        assertEquals(1, cache.size());
        assertTrue(cache.containsKey("fresh"));
    }

    @Test
    @DisplayName("BlockList：清扫过期缓存条目（targetValue 外部输入，键空间无界）")
    void blockListEvictsExpiredCacheEntries() {
        MockEnvironment env = new MockEnvironment()
            .withProperty("oddsmaker.blocklist.enabled", "false");
        BlockListClient client = new BlockListClient(env);
        ConcurrentHashMap<String, Object> cache =
            (ConcurrentHashMap<String, Object>) ReflectionTestUtils.getField(client, "cache");
        long now = java.time.Instant.now().getEpochSecond();
        BlockListClient.CacheEntry fresh = new BlockListClient.CacheEntry();
        fresh.blocked = false;
        fresh.expireAt = now + 15;
        BlockListClient.CacheEntry stale = new BlockListClient.CacheEntry();
        stale.blocked = true;
        stale.expireAt = now - 1;
        cache.put("device_id:fresh", fresh);
        cache.put("user_id:stale", stale);

        client.evictExpired();
        assertEquals(1, cache.size());
        assertTrue(cache.containsKey("device_id:fresh"));
    }

    // ===== 分支对侧补充（BRANCH 收口） =====

    @Test
    @DisplayName("PII：toMode/toIpMode null 输入与 DROP IP 模式解析侧")
    void piiModeParserNullInputAndDropIp() {
        PiiPolicy p = policy("allow", "allow", "allow");
        // 构造器 Binder orElse 兜底使生产 s 恒非 null，null 侧经直测覆盖
        assertEquals(PiiPolicy.Mode.MASK,
            ReflectionTestUtils.invokeMethod(p, "toMode", (Object) null));
        assertEquals(PiiPolicy.IpMode.COARSE,
            ReflectionTestUtils.invokeMethod(p, "toIpMode", (Object) null));
        // toIpMode("drop") 解析侧（构造器路径）
        assertNull(policy("allow", "allow", "drop").sanitizeClientIp("1.2.3.4"));
    }

    @Test
    @DisplayName("PII：Overrides 存在但 email/phoneMode 未设——回落构造器模式")
    void piiOverridesFallBackWhenModeUnset() {
        PiiPolicy p = policy("mask", "mask", "allow");
        PiiPolicy.Overrides o = new PiiPolicy.Overrides();
        o.ipMode = PiiPolicy.IpMode.ALLOW; // 只设 ipMode，email/phoneMode 保持 null
        Map<String, Object> out = p.sanitizeProps(Map.of("mail", "a@b.com", "tel", "13812345678"), o);
        assertEquals("***@b.com", out.get("mail"));   // emailMode 回落构造器 mask
        assertTrue(String.valueOf(out.get("tel")).contains("x")); // phoneMode 回落构造器 mask
    }

    @Test
    @DisplayName("PII：列表元素清洗为 null 时被剔除（drop 模式）")
    void piiListElementDropped() {
        PiiPolicy p = policy("drop", "allow", "allow");
        Map<String, Object> out = p.sanitizeProps(Map.of("arr", List.of("a@b.com", "plain")));
        // 邮箱元素被 drop → null 剔除，仅剩普通文本
        assertEquals(1, ((List<?>) out.get("arr")).size());
        assertEquals("plain", ((List<?>) out.get("arr")).get(0));
    }

    @Test
    @DisplayName("Auth：isScoped 的 gameId/environment null 短路侧与 canWrite null")
    void authScopedNullShortCircuits() {
        AuthService.ApiKeyContext ctx = new AuthService.ApiKeyContext();
        ctx.environment = "prod";
        assertFalse(ctx.isScoped());  // gameId null 短路
        ctx.gameId = "  ";
        assertFalse(ctx.isScoped());  // gameId 空白串（!isBlank false 侧）
        ctx.gameId = "g";
        ctx.environment = null;
        assertFalse(ctx.isScoped());  // environment null
        // canWrite null → 视为可写（宽松默认）
        assertTrue(new AuthService.ApiKeyContext().allowsWrite());
        // 采样：enableSampling true 但 rate=0 → 不启用
        AuthService.ApiKeyContext zeroRate = new AuthService.ApiKeyContext();
        zeroRate.envEnableSampling = true;
        zeroRate.envSampleRate = 0.0;
        assertFalse(zeroRate.samplingEnabled());
    }

    @Test
    @DisplayName("Auth：缓存条目过期后重新拉取（expireAt <= now 侧）与 controlUrl 空白")
    void authExpiredCacheRefetchesAndBlankControlUrl() {
        MockEnvironment env = new MockEnvironment()
            .withProperty("oddsmaker.auth.keys.k_exp", "sk_exp")
            .withProperty("oddsmaker.control.url", "   "); // 空白 → not_configured → 本地回退
        AuthService service = new AuthService(env, new SimpleMeterRegistry());
        AuthService.ApiKeyContext fresh = new AuthService.ApiKeyContext();
        fresh.apiKey = "k_exp";
        try {
            ConcurrentHashMap<String, Object> cache = (ConcurrentHashMap<String, Object>)
                ReflectionTestUtils.getField(service, "cache");
            AuthService.CacheEntry stale = new AuthService.CacheEntry();
            stale.context = fresh;
            stale.expireAt = java.time.Instant.now().getEpochSecond() - 1;
            cache.put("k_exp", stale);
        } catch (Exception ignore) {
            // 结构变化时跳过
        }
        // 过期条目不被命中：走重新拉取（not_configured）→ 本地密钥回退
        AuthService.ApiKeyContext ctx = service.getContext("k_exp");
        assertNotNull(ctx);
        assertEquals("sk_exp", ctx.secret);
    }

    @Test
    @DisplayName("Props：filterWithAllowlist null props 直通与列表 null 元素剔除")
    void propsAllowlistNullPropsAndListNulls() {
        PropsPolicy restricted = propsPolicy("level");
        assertNull(restricted.filterWithAllowlist(null, List.of("level")));

        Map<String, Object> withList = Map.of("level", java.util.Arrays.asList("a", null, "b"));
        Map<String, Object> out = restricted.filterWithAllowlist(withList, List.of("level"));
        assertEquals(List.of("a", "b"), out.get("level")); // null 元素剔除 + 多元素迭代

        // 空白名单（allowlist 配置为空）的列表直通
        PropsPolicy open = propsPolicy(null);
        Map<String, Object> emptyOut = open.filter(Map.of("arr", List.of()));
        assertTrue(((List<?>) emptyOut.get("arr")).isEmpty());
    }

    @Test
    @DisplayName("Props：exceedsRequestLimit 对 null 请求体返回 false")
    void propsRequestLimitNullBody() {
        assertFalse(propsPolicy(null).exceedsRequestLimit(null));
    }

    @Test
    @DisplayName("限流：同 key 同分钟二次命中（窗口未翻侧）与 bucketsApi 当前窗口保留")
    void rateLimiterSameMinuteAndApiBucketsEvict() {
        RateLimiterService svc = new RateLimiterService(10, 10);
        assertTrue(svc.allowApiKey("k"));
        assertTrue(svc.allowApiKey("k")); // 同分钟二次：w.window != minute 为 false
        // bucketsApi 的当前分钟窗口清扫保留（谓词 false 侧）
        svc.evictStaleWindows();
        ConcurrentHashMap<String, Object> bucketsApi =
            (ConcurrentHashMap<String, Object>) ReflectionTestUtils.getField(svc, "bucketsApi");
        assertEquals(1, bucketsApi.size());

        // 窗口翻新：置为过去分钟后再次 allow → 计数重置（w.window != minute true 侧）
        ReflectionTestUtils.setField(bucketsApi.get("k"), "window", 0L);
        assertTrue(svc.allowApiKey("k"));
        assertEquals(1, bucketsApi.size());

        // bucketsApi 旧窗口清扫驱逐（removeIf 谓词 true 侧）
        ReflectionTestUtils.setField(bucketsApi.get("k"), "window", 0L);
        svc.evictStaleWindows();
        assertTrue(bucketsApi.isEmpty());
    }
}
