package io.oddsmaker.control.jpa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * AuditLogEntity 的对外 JSON 契约：审计 API（{@code AuditLogController}）直接收发实体、
 * 无 DTO 隔离层，读时派生的 {@code eventType} 随实体出参——审计事件分类即由此暴露给调用方
 * （路线图 §4，见 {@link AuditEventType}）。注解层面的改动会直接变成线上行为，故钉住。
 */
@DisplayName("AuditLogEntity JSON 契约：派生 eventType 随实体出参")
class AuditLogEntityJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private static JsonNode json(AuditLogEntity log) throws Exception {
        return MAPPER.readTree(MAPPER.writeValueAsString(log));
    }

    @Test
    @DisplayName("轮换行：api_key × UPDATE × details.action=auto_rotate → eventType=API_KEY_ROTATED")
    void rotationRowExposesApiKeyRotated() throws Exception {
        AuditLogEntity log = new AuditLogEntity();
        log.resourceType = "api_key";
        log.action = AuditLogEntity.AuditAction.UPDATE;
        log.details = "{\"action\":\"auto_rotate\",\"rotationDays\":\"90\"}";

        assertEquals("API_KEY_ROTATED", json(log).path("eventType").asText());
    }

    @Test
    @DisplayName("分类随实体透出：风控规则变更 RISK_POLICY_CHANGED、其余 OTHER")
    void classifyFlowsThroughEntity() throws Exception {
        AuditLogEntity rule = new AuditLogEntity();
        rule.resourceType = "risk_rule";
        rule.action = AuditLogEntity.AuditAction.UPDATE;
        assertEquals("RISK_POLICY_CHANGED", json(rule).path("eventType").asText());

        AuditLogEntity other = new AuditLogEntity();
        other.resourceType = "game";
        other.action = AuditLogEntity.AuditAction.UPDATE;
        assertEquals("OTHER", json(other).path("eventType").asText());
    }
}
