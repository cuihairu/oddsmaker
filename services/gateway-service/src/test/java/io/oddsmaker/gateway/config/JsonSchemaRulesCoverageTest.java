package io.oddsmaker.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Schema 校验器规则矩阵：用测试专用 schema 覆盖生产 schema 未使用的
 * enum / array / integer / 无 type 的 oneOf option 分支。
 */
@DisplayName("Schema 校验规则矩阵测试")
class JsonSchemaRulesCoverageTest {

    private JsonSchemaValidator validator() {
        return new JsonSchemaValidator(new ObjectMapper(), "schemas/test-rules-schema.json");
    }

    private Map<String, Object> base() {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("event_id", "evt_ok");
        return e;
    }

    @Test
    @DisplayName("enum：合法值通过，非法值报 invalid_enum")
    void enumRule() {
        Map<String, Object> vip = base();
        vip.put("tier", "vip");
        assertNull(validator().validate(vip));

        Map<String, Object> bad = base();
        bad.put("tier", "svip");
        assertEquals("tier_invalid_enum", validator().validate(bad));
    }

    @Test
    @DisplayName("integer：整型通过，浮点/字符串报 invalid_type")
    void integerRule() {
        Map<String, Object> ok = base();
        ok.put("count", 3);
        assertNull(validator().validate(ok));

        Map<String, Object> frac = base();
        frac.put("count", 1.5);
        assertEquals("count_invalid_type", validator().validate(frac));

        Map<String, Object> str = base();
        str.put("count", "3");
        assertEquals("count_invalid_type", validator().validate(str));
    }

    @Test
    @DisplayName("array：非数组与超 maxItems 拒绝，合法数组通过")
    void arrayRule() {
        Map<String, Object> ok = base();
        ok.put("tags", List.of("a", "b"));
        assertNull(validator().validate(ok));

        Map<String, Object> notArray = base();
        notArray.put("tags", "a,b");
        assertEquals("tags_invalid_type", validator().validate(notArray));

        Map<String, Object> tooMany = base();
        tooMany.put("tags", List.of("a", "b", "c"));
        assertEquals("tags_too_many_items", validator().validate(tooMany));
    }

    @Test
    @DisplayName("oneOf：无 type 的 option 跳过，后续 option 匹配/失配")
    void oneOfSkipsTypelessOption() {
        Map<String, Object> num = base();
        num.put("mix", 1.25);
        assertNull(validator().validate(num));

        Map<String, Object> bad = base();
        bad.put("mix", true);
        assertEquals("mix_invalid_type", validator().validate(bad));
    }

    @Test
    @DisplayName("type 数组：slot 接受 string 与 null，数字失配")
    void typeArrayRule() {
        Map<String, Object> s = base();
        s.put("slot", "x");
        assertNull(validator().validate(s));

        Map<String, Object> n = base();
        n.put("slot", null);
        assertNull(validator().validate(n));

        Map<String, Object> num = base();
        num.put("slot", 9);
        assertEquals("slot_invalid_type", validator().validate(num));
    }

    @Test
    @DisplayName("minLength/maxLength 文本长度规则")
    void lengthRules() {
        Map<String, Object> shortId = base();
        shortId.put("event_id", "");
        assertEquals("event_id_too_short", validator().validate(shortId));

        Map<String, Object> longId = base();
        longId.put("event_id", "e".repeat(17));
        assertEquals("event_id_too_long", validator().validate(longId));
    }

    @Test
    @DisplayName("object/null/未知 type：object 校验、null 校验、default 直通")
    void objectNullAndUnknownTypeRules() {
        Map<String, Object> obj = base();
        obj.put("meta", Map.of("k", "v"));
        assertNull(validator().validate(obj));

        Map<String, Object> notObj = base();
        notObj.put("meta", "str");
        assertEquals("meta_invalid_type", validator().validate(notObj));

        // type: null —— 传非 null 值才进该校验分支（字段缺省/null 值在校验前就跳过）
        Map<String, Object> nul = base();
        nul.put("absent", "x");
        assertEquals("absent_invalid_type", validator().validate(nul));

        // 未知 type（default 分支）永不失配
        Map<String, Object> weird = base();
        weird.put("anything", 42);
        assertNull(validator().validate(weird));
    }

    @Test
    @DisplayName("schema 资源不存在：构造即抛 IllegalStateException")
    void missingSchemaFailsFast() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
            () -> new JsonSchemaValidator(new ObjectMapper(), "schemas/definitely-missing.json"));
    }

    @Test
    @DisplayName("事件对象序列化失败：降级 invalid_json")
    void unserializableEventDegrades() {
        Object broken = new Object() {
            @com.fasterxml.jackson.annotation.JsonProperty("boom")
            public String getBoom() { throw new IllegalStateException("nope"); }
        };
        assertEquals("invalid_json", validator().validate(broken));
    }
}
