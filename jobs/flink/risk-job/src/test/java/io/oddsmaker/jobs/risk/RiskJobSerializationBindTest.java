package io.oddsmaker.jobs.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static io.oddsmaker.jobs.risk.RiskJobTestSupport.*;

/**
 * RiskJob 测试（LAY-011 拆分）：toJson 全字段拼接与引号/控制字符转义、PreparedStatement 参数绑定。
 */
class RiskJobSerializationBindTest {

    @Test
    @DisplayName("toJson：全字段拼接 + reason/evidence 引号转义")
    void toJsonEscapes() {
        Map<String, String> ev = new LinkedHashMap<>();
        ev.put("k\"1", "v\"2");
        RiskJob.RiskHit h = new RiskJob.RiskHit(
                "g", "prod", new Timestamp(1_000L), "rid", "sid",
                "rule-1", "THRESHOLD", "HIGH", "PLAYER", "u1", 85f, "ALERT",
                "says \"hi\"", ev, "HIGH");
        String json = RiskJob.toJson(h);
        assertTrue(json.startsWith("{\"game_id\":\"g\",\"environment\":\"prod\""), json);
        assertTrue(json.contains("\"ts\":1000"));
        assertTrue(json.contains("\"risk_event_id\":\"rid\""));
        assertTrue(json.contains("\"source_event_id\":\"sid\""));
        assertTrue(json.contains("\"rule_id\":\"rule-1\""));
        assertTrue(json.contains("\"risk_type\":\"THRESHOLD\""));
        assertTrue(json.contains("\"severity\":\"HIGH\""));
        assertTrue(json.contains("\"subject_type\":\"PLAYER\""));
        assertTrue(json.contains("\"subject_id\":\"u1\""));
        assertTrue(json.contains("\"score\":85.0"));
        assertTrue(json.contains("\"action\":\"ALERT\""));
        assertTrue(json.contains("\"trust_level\":\"HIGH\""));
        assertTrue(json.contains("\"reason\":\"says \\\"hi\\\"\""));
        assertTrue(json.contains("\"evidence\":{\"k\\\"1\":\"v\\\"2\"}"));   // key/value 均转义
        assertTrue(json.endsWith("}}"));
    }

    @Test
    @DisplayName("jsonEscape：转义反斜杠/引号与全部控制字符（换行/制表/CR/BS/FF/其余 <0x20）")
    void jsonEscapeControlChars() {
        assertEquals("", RiskJob.jsonEscape(null));
        // 反斜杠与引号
        assertEquals("\\\\", RiskJob.jsonEscape("\\"));
        assertEquals("\\\"", RiskJob.jsonEscape("\""));
        // 控制字符
        assertEquals("\\n", RiskJob.jsonEscape("\n"));
        assertEquals("\\r", RiskJob.jsonEscape("\r"));
        assertEquals("\\t", RiskJob.jsonEscape("\t"));
        assertEquals("\\b", RiskJob.jsonEscape("\b"));
        assertEquals("\\f", RiskJob.jsonEscape("\f"));
        // 其余 <0x20 用 unicode-escape
        assertEquals("\\u0001", RiskJob.jsonEscape("\u0001"));
        // 普通字符不动
        assertEquals("plain", RiskJob.jsonEscape("plain"));
        // 组合：reason 含裸换行/制表不产非法 JSON
        String escaped = RiskJob.jsonEscape("line1\nline2\t\"q\"");
        assertEquals("line1\\nline2\\t\\\"q\\\"", escaped);
    }

    @Test
    @DisplayName("bindRiskHit：14 个参数按序绑定")
    void bindRiskHitSetsAllParameters() throws Exception {
        Map<String, String> ev = Map.of("k", "v");
        RiskJob.RiskHit h = new RiskJob.RiskHit(
                "g", "prod", new Timestamp(1_000L), "rid", "sid",
                "rule-1", "THRESHOLD", "HIGH", "PLAYER", "u1", 85.5f, "ALERT",
                "reason", ev, "");

        Map<String, Object> calls = new LinkedHashMap<>();
        PreparedStatement ps = (PreparedStatement) Proxy.newProxyInstance(
                RiskJobSerializationBindTest.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
                (Object p, Method m, Object[] a) -> {
                    calls.put(m.getName() + ":" + a[0], a[1]);
                    return null;
                });
        RiskJob.bindRiskHit(ps, h);

        assertEquals("g", calls.get("setString:1"));
        assertEquals("prod", calls.get("setString:2"));
        assertEquals(new Timestamp(1_000L), calls.get("setTimestamp:3"));
        assertEquals("rid", calls.get("setString:4"));
        assertEquals("sid", calls.get("setString:5"));
        assertEquals("rule-1", calls.get("setString:6"));
        assertEquals("THRESHOLD", calls.get("setString:7"));
        assertEquals("HIGH", calls.get("setString:8"));
        assertEquals("PLAYER", calls.get("setString:9"));
        assertEquals("u1", calls.get("setString:10"));
        assertEquals(85.5f, calls.get("setFloat:11"));
        assertEquals("ALERT", calls.get("setString:12"));
        assertEquals("reason", calls.get("setString:13"));
        assertEquals(ev, calls.get("setObject:14"));
        assertEquals(14, calls.size());
    }

}
