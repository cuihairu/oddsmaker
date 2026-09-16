package io.oddsmaker.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 策略边角分支：PiiPolicy 空值/null key/Overrides 模式覆盖，JsonSchemaValidator 类型与 oneOf 失配。
 */
@DisplayName("网关策略边角分支测试")
class GatewayPolicyEdgeTest {

    private PiiPolicy policy(String email, String phone, String ip) {
        MockEnvironment env = new MockEnvironment();
        if (email != null) env.setProperty("oddsmaker.pii.email", email);
        if (phone != null) env.setProperty("oddsmaker.phone", phone);
        if (phone != null) env.setProperty("oddsmaker.pii.phone", phone);
        if (ip != null) env.setProperty("oddsmaker.pii.ip", ip);
        return new PiiPolicy(env);
    }

    // ===== PiiPolicy =====

    @Test
    @DisplayName("PII：null props 直接返回 null")
    void nullPropsReturnsNull() {
        assertNull(policy(null, null, null).sanitizeProps(null));
    }

    @Test
    @DisplayName("PII：null 值条目被剔除；null key 不抛 NPE 按\"\"处理")
    void nullValuesAndNullKeys() {
        Map<String, Object> props = new HashMap<>();
        props.put("k_null", null);
        props.put(null, "plain");
        Map<String, Object> out = policy(null, null, null).sanitizeProps(props);
        assertFalse(out.containsKey("k_null"));
        assertEquals("plain", out.get(null));
    }

    @Test
    @DisplayName("PII：Overrides 邮箱/手机模式覆盖全局（drop 剔除、mask 脱敏）")
    void overridesModes() {
        PiiPolicy.Overrides o = new PiiPolicy.Overrides();
        o.emailMode = PiiPolicy.Mode.DROP;
        o.phoneMode = PiiPolicy.Mode.MASK;
        Map<String, Object> props = Map.of("contact", "a@b.com", "tel", "13812345678");

        Map<String, Object> out = policy("allow", "allow", "allow").sanitizeProps(props, o);
        assertFalse(out.containsKey("contact")); // email override drop
        assertTrue(String.valueOf(out.get("tel")).endsWith("78")); // phone override mask

        // override 只影响显式设置的模式：emailMode 恢复 MASK
        PiiPolicy.Overrides o2 = new PiiPolicy.Overrides();
        o2.emailMode = PiiPolicy.Mode.MASK;
        Map<String, Object> out2 = policy("allow", "allow", "allow").sanitizeProps(
            Map.of("contact", "a@b.com"), o2);
        assertEquals("***@b.com", out2.get("contact"));
    }

    @Test
    @DisplayName("PII：嵌套 Map 的 null key 跳过、null 值剔除")
    void nestedMapNullEntries() {
        Map<String, Object> inner = new HashMap<>();
        inner.put("keep", "13812345678");
        inner.put("dead", null);
        inner.put(null, "x");
        Map<String, Object> props = new HashMap<>();
        props.put("outer", inner);

        Map<String, Object> out = policy("allow", "mask", "allow").sanitizeProps(props);
        @SuppressWarnings("unchecked")
        Map<String, Object> sanitizedInner = (Map<String, Object>) out.get("outer");
        assertEquals(1, sanitizedInner.size()); // 仅保留 keep
        assertTrue(String.valueOf(sanitizedInner.get("keep")).endsWith("78"));
    }

    @Test
    @DisplayName("ReDoS 回归：7 万字符超长值必须线性完成（原正则回溯实测 40s+ 卡死 event loop）")
    void oversizedValuesCompleteLinearly() {
        PiiPolicy p = policy("mask", "mask", "allow");
        String huge = "x".repeat(70_000);
        String hugeDigits = "7".repeat(70_000);

        // 无 '@' 超长文本：跳过邮箱正则，原样返回
        long t0 = System.nanoTime();
        assertEquals(huge, p.sanitizeProps(Map.of("blob", huge)).get("blob"));
        // 超长数字串：走手机号脱敏（maskPhone 必须线性，原来循环内重数位数是 O(n²)）
        String masked = (String) p.sanitizeProps(Map.of("blob", hugeDigits)).get("blob");
        assertTrue(masked.endsWith("77"));
        assertFalse(masked.startsWith("777"));

        // maskKeys 命中的超长值：maskAll 快速路径
        MockEnvironment env = new MockEnvironment();
        env.setProperty("oddsmaker.pii.mask-keys", "contact,email");
        PiiPolicy withMaskKeys = new PiiPolicy(env);
        assertEquals("***", withMaskKeys.sanitizeProps(Map.of("contact", huge)).get("contact"));

        // 320 字符内的正则路径依旧正确
        assertEquals("***@b.com", p.sanitizeProps(Map.of("blob", "a@b.com")).get("blob"));
        assertTrue(System.nanoTime() - t0 < 2_000_000_000); // 全部须在 2s 内
    }

    // ===== PropsPolicy / sanitizeClientIp / PolicyService =====

    @Test
    @DisplayName("Props：null 值条目保留 null 键跳过、布尔原样、未知类型转字符串")
    void propsPolicySanitizeCorners() {
        PropsPolicy pp = new PropsPolicy(new org.springframework.mock.env.MockEnvironment(), new ObjectMapper());
        // 全局 allowlist 为空时 filter 原样返回；用显式 allowlist 走 sanitize 归一化路径
        java.util.Map<String, Object> props = new HashMap<>();
        props.put("k_null", null);
        props.put("flag", Boolean.TRUE);
        props.put("weird", new Object()); // 非 Jackson 产物的兜底路径
        java.util.Map<String, Object> out = pp.filterWithAllowlist(props,
            List.of("k_null", "flag", "weird"));
        assertNull(out.get("k_null"));
        assertEquals(Boolean.TRUE, out.get("flag"));
        assertNotNull(out.get("weird"));

        // 嵌套 map 的 null key 被跳过
        java.util.Map<String, Object> inner = new HashMap<>();
        inner.put(null, "x");
        inner.put("keep", "v");
        java.util.Map<String, Object> nested = pp.filterWithAllowlist(java.util.Map.of("outer", inner),
            List.of("outer"));
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> sanitized = (java.util.Map<String, Object>) nested.get("outer");
        assertEquals(1, sanitized.size());
    }

    @Test
    @DisplayName("PII：client_ip 各模式（drop/allow/IPv4 掩码/IPv6 掩码/超界/主机名原样）")
    void sanitizeClientIpModes() {
        assertNull(policy("mask", "mask", "drop").sanitizeClientIp("1.2.3.4"));   // drop
        assertEquals("1.2.3.4", policy("mask", "mask", "allow").sanitizeClientIp("1.2.3.4")); // allow
        PiiPolicy coarse = policy("drop", "drop", "coarse");
        assertEquals("1.2.3.0", coarse.sanitizeClientIp("1.2.3.4"));            // IPv4 /24
        // IPv6：低 10 字节清零（注释写 /48，实际清零从第 6 字节起，保留前三组）
        String v6 = coarse.sanitizeClientIp("2001:db8:1:2:3:4:5:6");
        assertNotNull(v6);
        assertTrue(v6.startsWith("2001:db8:1:"));
        assertNull(coarse.sanitizeClientIp("256.1.1.1"));                        // 段越界 → null
        assertNull(coarse.sanitizeClientIp("1.2.3.999"));                        // 段越界 → null
        assertNull(coarse.sanitizeClientIp("[bad"));                             // 非法字面量 → getByName 抛 → null
        assertEquals("localhost", coarse.sanitizeClientIp("localhost"));         // 非四段主机名原样
        assertNull(coarse.sanitizeClientIp(null));
        assertNull(coarse.sanitizeClientIp(""));
    }

    @Test
    @DisplayName("PII：toMode/toIpMode 的 drop 文本分支（构造器绑定）")
    void dropModeBoundFromEnv() {
        PiiPolicy p = policy("drop", "drop", "drop");
        // email/phone/ip 全 drop：任何含 PII 的值都被剔除
        java.util.Map<String, Object> out = p.sanitizeProps(java.util.Map.of(
            "contact", "a@b.com", "tel", "13812345678"));
        assertTrue(out.isEmpty());
        assertNull(p.sanitizeClientIp("1.2.3.4"));
    }

    @Test
    @DisplayName("Props：事件序列化异常时超限判定降级为 false")
    void exceedsEventLimitDegradesOnBrokenEvent() {
        PropsPolicy pp = new PropsPolicy(new org.springframework.mock.env.MockEnvironment(), new ObjectMapper());
        Object broken = new Object() {
            @com.fasterxml.jackson.annotation.JsonProperty("boom")
            public String getBoom() { throw new IllegalStateException("nope"); }
        };
        assertFalse(pp.exceedsEventLimit(broken));
        // 正常事件按字节阈值判定
        assertTrue(pp.exceedsEventLimit(Map.of("blob", "x".repeat(200_000))));
    }

    @Test
    @DisplayName("PolicyService：key 上下文为 null 时投影为 null")
    void policyServiceNullContext() {
        AuthService auth = org.mockito.Mockito.mock(AuthService.class);
        org.mockito.Mockito.when(auth.getContext("pk_ghost")).thenReturn(null);
        PolicyService service = new PolicyService(auth);
        assertNull(service.getPolicy("pk_ghost"));
    }

    // ===== JsonSchemaValidator =====

    private JsonSchemaValidator validator() {
        return new JsonSchemaValidator(new ObjectMapper());
    }

    /** 必填字段齐全的基础事件（字段级错误隔离验证用）。 */
    private Map<String, Object> baseEvent() {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("event_id", "evt_12345678");
        e.put("game_id", "game_demo");
        e.put("environment", "prod");
        e.put("event_name", "level_start");
        e.put("device_id", "d1");
        e.put("ts_client", 1730000000000L);
        return e;
    }

    @Test
    @DisplayName("Schema：单类型 string 字段传数字 → invalid_type")
    void textualTypeMismatch() {
        Map<String, Object> e = baseEvent();
        e.put("event_name", 123);
        assertEquals("event_name_invalid_type", validator().validate(e));
    }

    @Test
    @DisplayName("Schema：可空类型 [string,null] 字段传数字 → 全 option 失配")
    void nullableTypeArrayMismatch() {
        Map<String, Object> e = baseEvent();
        e.put("platform", 3.14);
        assertEquals("platform_invalid_type", validator().validate(e));

        Map<String, Object> e2 = baseEvent();
        e2.put("country", true);
        assertEquals("country_invalid_type", validator().validate(e2));
    }

    @Test
    @DisplayName("Schema：oneOf 字段传 boolean → string/number 全失配")
    void oneOfMismatch() {
        Map<String, Object> e = baseEvent();
        e.put("ts_client", true);
        assertEquals("ts_client_invalid_type", validator().validate(e));
    }

    @Test
    @DisplayName("Schema：maxLength 超限")
    void maxLengthExceeded() {
        Map<String, Object> e = baseEvent();
        e.put("environment", "e".repeat(33));
        assertEquals("environment_too_long", validator().validate(e));
    }

    @Test
    @DisplayName("Schema：合法事件整体通过")
    void validEventPasses() {
        assertNull(validator().validate(baseEvent()));
    }
}
