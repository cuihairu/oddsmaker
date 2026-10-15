package io.oddsmaker.control.jpa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审计事件分类常量（路线图 §2.6）：五类敏感事件 + OTHER 兜底。
 *
 * 判定输入三件套：resourceType（资源）、AuditAction（粗粒度动作）、details 里的 operation
 * （细粒度动作，key=action；轮换 68ce564 已按此约定写入）。不做新表——分类是读时派生量。
 */
@DisplayName("审计事件分类常量")
class AuditEventTypeTest {

    private static AuditEventType classify(String resourceType, AuditLogEntity.AuditAction action, String details) {
        return AuditEventType.classify(resourceType, action, details);
    }

    @Test
    @DisplayName("风控策略变更：risk_rule 的变更类动作命中；读取与别的资源不命中")
    void riskPolicyChanged() {
        for (AuditLogEntity.AuditAction action : EnumSet.of(AuditLogEntity.AuditAction.CREATE,
                AuditLogEntity.AuditAction.UPDATE, AuditLogEntity.AuditAction.DELETE,
                AuditLogEntity.AuditAction.ENABLE, AuditLogEntity.AuditAction.DISABLE,
                AuditLogEntity.AuditAction.ACTIVATE, AuditLogEntity.AuditAction.DEACTIVATE)) {
            assertEquals(AuditEventType.RISK_POLICY_CHANGED, classify("risk_rule", action, null), action + " 应为风控策略变更");
            assertEquals(AuditEventType.RISK_POLICY_CHANGED,
                classify("risk_rule", action, "人工调整阈值"), action + " 自由文本 details 不影响判定");
        }
        assertEquals(AuditEventType.OTHER, classify("risk_rule", AuditLogEntity.AuditAction.READ, null));
        assertEquals(AuditEventType.OTHER, classify("risk_case", AuditLogEntity.AuditAction.UPDATE, null));
    }

    @Test
    @DisplayName("API Key 轮换：api_key + details.action=auto_rotate；普通策略更新不命中")
    void apiKeyRotated() {
        assertEquals(AuditEventType.API_KEY_ROTATED,
            classify("api_key", AuditLogEntity.AuditAction.UPDATE, "{\"action\":\"auto_rotate\",\"rotationDays\":\"90\"}"));
        // 同资源无 operation / operation 不同 → 只是策略更新
        assertEquals(AuditEventType.OTHER, classify("api_key", AuditLogEntity.AuditAction.UPDATE, null));
        assertEquals(AuditEventType.OTHER,
            classify("api_key", AuditLogEntity.AuditAction.UPDATE, "{\"rpmBefore\":\"100\"}"));
        // 非 api_key 资源即便带同 operation 也不命中
        assertEquals(AuditEventType.OTHER,
            classify("sdk_key", AuditLogEntity.AuditAction.UPDATE, "{\"action\":\"auto_rotate\"}"));
    }

    @Test
    @DisplayName("数据导出：EXPORT 动作按资源无关命中")
    void dataExported() {
        assertEquals(AuditEventType.DATA_EXPORTED,
            classify("player_export", AuditLogEntity.AuditAction.EXPORT, null));
        assertEquals(AuditEventType.DATA_EXPORTED,
            classify("risk_case", AuditLogEntity.AuditAction.EXPORT, "{\"rows\":10}"));
        assertEquals(AuditEventType.OTHER,
            classify("player_export", AuditLogEntity.AuditAction.CREATE, null));
    }

    @Test
    @DisplayName("权限变更：角色/权限动作命中；审核派单 GRANT 不算权限变更")
    void permissionChanged() {
        for (AuditLogEntity.AuditAction action : EnumSet.of(AuditLogEntity.AuditAction.GRANT_ROLE,
                AuditLogEntity.AuditAction.REVOKE_ROLE, AuditLogEntity.AuditAction.GRANT_PERMISSION,
                AuditLogEntity.AuditAction.REVOKE_PERMISSION)) {
            assertEquals(AuditEventType.PERMISSION_CHANGED, classify("user", action, null), action + " 应为权限变更");
        }
        // 裸 GRANT 只在 user 资源上算权限（ReviewQueueService 的派单是 review_queue）
        assertEquals(AuditEventType.PERMISSION_CHANGED,
            classify("user", AuditLogEntity.AuditAction.GRANT, null));
        assertEquals(AuditEventType.OTHER,
            classify("review_queue", AuditLogEntity.AuditAction.GRANT, "Assigned to reviewer: bob"));
        // 用户资料更新不算权限变更
        assertEquals(AuditEventType.OTHER, classify("user", AuditLogEntity.AuditAction.UPDATE, null));
    }

    @Test
    @DisplayName("实验发布：experiment + details.action=publish；暂停/删除不算发布")
    void experimentPublished() {
        assertEquals(AuditEventType.EXPERIMENT_PUBLISHED,
            classify("experiment", AuditLogEntity.AuditAction.UPDATE, "{\"action\":\"publish\"}"));
        assertEquals(AuditEventType.OTHER,
            classify("experiment", AuditLogEntity.AuditAction.UPDATE, "{\"action\":\"pause\"}"));
        assertEquals(AuditEventType.OTHER, classify("experiment", AuditLogEntity.AuditAction.DELETE, null));
    }

    @Test
    @DisplayName("details 异常形态：空串/非 JSON/JSON 数组/JSON null/缺 action 键 → 视作无 operation")
    void malformedDetails() {
        for (String details : new String[]{null, "", "   ", "人工调整阈值", "{oops", "[1,2]", "null", "\"publish\"",
                "{\"op\":\"publish\"}", "{\"action\":123}", "{\"action\":null}"}) {
            assertNull(AuditEventType.operation(details), "details=" + details + " 应解析不出 operation");
            assertEquals(AuditEventType.OTHER, classify("experiment", AuditLogEntity.AuditAction.UPDATE, details));
        }
    }

    @Test
    @DisplayName("入参缺失：resourceType/action 为 null 一律 OTHER，不抛")
    void nullInputs() {
        assertEquals(AuditEventType.OTHER, classify(null, AuditLogEntity.AuditAction.UPDATE, null));
        assertEquals(AuditEventType.OTHER, classify("api_key", null, "{\"action\":\"auto_rotate\"}"));
        assertEquals(AuditEventType.OTHER, classify(null, null, null));
    }

    @Test
    @DisplayName("常量集与描述：五类 + OTHER 齐备，描述非空且互不相同")
    void constantsAndDescriptions() {
        assertEquals(6, AuditEventType.values().length);
        Set<String> descriptions = new java.util.HashSet<>();
        for (AuditEventType type : AuditEventType.values()) {
            assertNotNull(type.getDescription());
            assertFalse(type.getDescription().isBlank(), type + " 描述不应为空");
            assertTrue(descriptions.add(type.getDescription()), type + " 描述与同类重复：" + type.getDescription());
        }
        assertEquals("风控策略变更", AuditEventType.RISK_POLICY_CHANGED.getDescription());
        assertEquals("API Key 轮换", AuditEventType.API_KEY_ROTATED.getDescription());
        assertEquals("数据导出", AuditEventType.DATA_EXPORTED.getDescription());
        assertEquals("权限变更", AuditEventType.PERMISSION_CHANGED.getDescription());
        assertEquals("实验发布", AuditEventType.EXPERIMENT_PUBLISHED.getDescription());
        assertEquals("其他", AuditEventType.OTHER.getDescription());
    }

    @Test
    @DisplayName("实体派生：AuditLogEntity.getEventType() 直接给出分类，字段缺省为 OTHER")
    void entityDerivedGetter() {
        AuditLogEntity log = new AuditLogEntity();
        assertEquals(AuditEventType.OTHER, log.getEventType());

        log.resourceType = "api_key";
        log.action = AuditLogEntity.AuditAction.UPDATE;
        log.details = "{\"action\":\"auto_rotate\",\"rotationDays\":\"90\"}";
        assertEquals(AuditEventType.API_KEY_ROTATED, log.getEventType());

        log.resourceType = "risk_rule";
        log.details = "旧值→新值";
        assertEquals(AuditEventType.RISK_POLICY_CHANGED, log.getEventType());
    }
}
