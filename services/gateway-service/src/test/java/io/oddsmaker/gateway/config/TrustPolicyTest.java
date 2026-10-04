package io.oddsmaker.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.common.model.Event;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 事件契约 v2：TrustPolicy 推导/自抬拒绝矩阵 + 生产 schema 对新字段的校验规则。
 */
@DisplayName("TrustPolicy：source/trust_level 推导与自抬拒绝")
class TrustPolicyTest {

    private static Event event(String source, String trustLevel) {
        Event e = new Event();
        e.eventId = "e1";
        e.eventName = "server.purchase.confirmed";
        e.gameId = "g";
        e.environment = "prod";
        e.deviceId = "d1";
        e.source = source;
        e.trustLevel = trustLevel;
        return e;
    }

    @Test
    @DisplayName("缺省回填：CLIENT key→client/LOW；SERVER key→server/HIGH；v1 事件字段全缺省")
    void defaultsByRole() {
        Event clientEvent = event(null, null);
        assertNull(TrustPolicy.apply(clientEvent, "client"));
        assertEquals("client", clientEvent.source);
        assertEquals("LOW", clientEvent.trustLevel);
        assertEquals(Integer.valueOf(1), clientEvent.eventVersion);
        assertEquals("gateway", clientEvent.eventOrigin);

        Event serverEvent = event(null, null);
        assertNull(TrustPolicy.apply(serverEvent, "server"));
        assertEquals("server", serverEvent.source);
        assertEquals("HIGH", serverEvent.trustLevel);

        // 本地静态 key（keyRole=null）按 CLIENT 档处理
        Event localEvent = event(null, null);
        assertNull(TrustPolicy.apply(localEvent, null));
        assertEquals("client", localEvent.source);
        assertEquals("LOW", localEvent.trustLevel);
    }

    @Test
    @DisplayName("自抬拒绝：CLIENT key 声明 server/system/derived 一律 source_escalation")
    void clientKeySourceEscalationRejected() {
        for (String claimed : new String[]{"server", "system", "derived"}) {
            assertEquals("source_escalation", TrustPolicy.apply(event(claimed, null), "client"),
                "claimed=" + claimed);
        }
        // 本地静态 key（null 档）同样不可自抬
        assertEquals("source_escalation", TrustPolicy.apply(event("server", null), null));
    }

    @Test
    @DisplayName("自抬拒绝：system/derived 为平台保留档，SERVER key 亦不可声明")
    void reservedSourcesRejectedEvenForServerKey() {
        assertEquals("source_escalation", TrustPolicy.apply(event("system", null), "server"));
        assertEquals("source_escalation", TrustPolicy.apply(event("derived", null), "server"));
    }

    @Test
    @DisplayName("合法声明：SERVER key 声明 server 放行且 HIGH；声明 client 降档放行 LOW")
    void serverKeyClaims() {
        Event claimed = event("server", "HIGH");
        assertNull(TrustPolicy.apply(claimed, "server"));
        assertEquals("server", claimed.source);
        assertEquals("HIGH", claimed.trustLevel);

        Event downgraded = event("client", null);
        assertNull(TrustPolicy.apply(downgraded, "server"));
        assertEquals("client", downgraded.source);
        assertEquals("LOW", downgraded.trustLevel);
    }

    @Test
    @DisplayName("自抬拒绝：声明 trust_level 高于推导档（client+HIGH / client+COMPUTED）")
    void trustLevelEscalationRejected() {
        assertEquals("trust_level_escalation", TrustPolicy.apply(event("client", "HIGH"), "client"));
        assertEquals("trust_level_escalation", TrustPolicy.apply(event("client", "COMPUTED"), "client"));
        // source 与 trust_level 同时自抬：先拒 source（source 是信任链的根）
        assertEquals("source_escalation", TrustPolicy.apply(event("server", "HIGH"), "client"));
    }

    @Test
    @DisplayName("声明 trust_level 低于/等于推导档：不拒，回填推导档（server+LOW→HIGH）")
    void downgradeClaimOverwrittenWithDerived() {
        Event e = event("server", "LOW");
        assertNull(TrustPolicy.apply(e, "server"));
        assertEquals("HIGH", e.trustLevel);

        Event exact = event("client", "LOW");
        assertNull(TrustPolicy.apply(exact, "client"));
        assertEquals("LOW", exact.trustLevel);
    }

    @Test
    @DisplayName("event_version/event_origin：显式值保留，缺省/空白回填")
    void versionAndOriginBackfill() {
        Event explicit = event(null, null);
        explicit.eventVersion = 2;
        explicit.eventOrigin = "server-java/0.1.0";
        assertNull(TrustPolicy.apply(explicit, "server"));
        assertEquals(Integer.valueOf(2), explicit.eventVersion);
        assertEquals("server-java/0.1.0", explicit.eventOrigin);

        Event blank = event(null, null);
        blank.eventOrigin = "  ";
        assertNull(TrustPolicy.apply(blank, "client"));
        assertEquals("gateway", blank.eventOrigin);
    }

    @Test
    @DisplayName("trustOf：client→LOW；server/system→HIGH；derived→COMPUTED")
    void trustOfMatrix() {
        assertEquals("LOW", TrustPolicy.trustOf("client"));
        assertEquals("HIGH", TrustPolicy.trustOf("server"));
        assertEquals("HIGH", TrustPolicy.trustOf("system"));
        assertEquals("COMPUTED", TrustPolicy.trustOf("derived"));
    }

    @Test
    @DisplayName("生产 schema 增量：新字段规则（enum/minimum/maxLength）经 JsonSchemaValidator 生效")
    void productionSchemaValidatesV2Fields() {
        JsonSchemaValidator validator = new JsonSchemaValidator(new ObjectMapper());
        Map<String, Object> base = Map.of(
            "event_id", "12345678", "game_id", "g", "environment", "prod",
            "event_name", "n", "device_id", "d1", "ts_client", 1730000000000L);

        // 合法 v2 事件通过
        Map<String, Object> valid = new java.util.HashMap<>(base);
        valid.put("event_version", 1);
        valid.put("source", "server");
        valid.put("trust_level", "HIGH");
        valid.put("event_origin", "server-java/0.1.0");
        assertNull(validator.validate(valid));

        // 枚举违规
        Map<String, Object> badSource = new java.util.HashMap<>(base);
        badSource.put("source", "banana");
        assertEquals("source_invalid_enum", validator.validate(badSource));
        Map<String, Object> badTrust = new java.util.HashMap<>(base);
        badTrust.put("trust_level", "high");
        assertEquals("trust_level_invalid_enum", validator.validate(badTrust));

        // event_version ≥ 1（minimum 增量规则）与非整数类型
        Map<String, Object> zeroVersion = new java.util.HashMap<>(base);
        zeroVersion.put("event_version", 0);
        assertEquals("event_version_below_minimum", validator.validate(zeroVersion));
        Map<String, Object> fractionalVersion = new java.util.HashMap<>(base);
        fractionalVersion.put("event_version", 1.5);
        assertEquals("event_version_invalid_type", validator.validate(fractionalVersion));

        // event_origin 长度上限
        Map<String, Object> longOrigin = new java.util.HashMap<>(base);
        longOrigin.put("event_origin", "x".repeat(65));
        assertEquals("event_origin_too_long", validator.validate(longOrigin));

        // v1 事件不携带新字段：schema 仍通过（向后兼容）
        assertNull(validator.validate(base));
    }
}
