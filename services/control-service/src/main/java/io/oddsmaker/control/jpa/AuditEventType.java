package io.oddsmaker.control.jpa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.EnumSet;
import java.util.Set;

/**
 * 审计事件分类常量（路线图 §2.6）：五类敏感事件 + OTHER 兜底。
 *
 * <p>判定输入三件套：resourceType（资源）、{@link AuditLogEntity.AuditAction}（粗粒度动作）、
 * details 里的 operation（细粒度动作，key=action；轮换 68ce564 已按此约定写入）。
 * 不做新表——分类是读时派生量（{@link AuditLogEntity#getEventType()}）。
 */
public enum AuditEventType {

    RISK_POLICY_CHANGED("风控策略变更"),
    API_KEY_ROTATED("API Key 轮换"),
    DATA_EXPORTED("数据导出"),
    PERMISSION_CHANGED("权限变更"),
    EXPERIMENT_PUBLISHED("实验发布"),
    OTHER("其他");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 风控策略变更动作集：risk_rule 资源上的写类动作（READ 不算）。 */
    private static final Set<AuditLogEntity.AuditAction> RISK_POLICY_ACTIONS = EnumSet.of(
            AuditLogEntity.AuditAction.CREATE,
            AuditLogEntity.AuditAction.UPDATE,
            AuditLogEntity.AuditAction.DELETE,
            AuditLogEntity.AuditAction.ENABLE,
            AuditLogEntity.AuditAction.DISABLE,
            AuditLogEntity.AuditAction.ACTIVATE,
            AuditLogEntity.AuditAction.DEACTIVATE);

    /** 权限语义动作集：这些动作本身就是权限变更，不挑资源。 */
    private static final Set<AuditLogEntity.AuditAction> ROLE_PERMISSION_ACTIONS = EnumSet.of(
            AuditLogEntity.AuditAction.GRANT_ROLE,
            AuditLogEntity.AuditAction.REVOKE_ROLE,
            AuditLogEntity.AuditAction.GRANT_PERMISSION,
            AuditLogEntity.AuditAction.REVOKE_PERMISSION);

    private final String description;

    AuditEventType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    /**
     * details 里的细粒度动作：JSON 对象的 action 键，且值必须是字符串。
     * 空串/非 JSON/JSON 数组/标量/缺键/非字符串值一律 null——分类宁落 OTHER 也不错分。
     */
    public static String operation(String details) {
        if (details == null || details.isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(details);
            if (!root.isObject()) {
                return null;
            }
            JsonNode action = root.get("action");
            return action != null && action.isTextual() ? action.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 读时派生分类：按 资源 × 动作 × operation 判定，规则外一律 OTHER，入参 null 不抛。
     */
    public static AuditEventType classify(String resourceType, AuditLogEntity.AuditAction action, String details) {
        if (resourceType == null || action == null) {
            return OTHER;
        }
        if (action == AuditLogEntity.AuditAction.EXPORT) {
            return DATA_EXPORTED;
        }
        String op = operation(details);
        if ("api_key".equals(resourceType)
                && action == AuditLogEntity.AuditAction.UPDATE
                && "auto_rotate".equals(op)) {
            return API_KEY_ROTATED;
        }
        if ("experiment".equals(resourceType)
                && action == AuditLogEntity.AuditAction.UPDATE
                && "publish".equals(op)) {
            return EXPERIMENT_PUBLISHED;
        }
        if (ROLE_PERMISSION_ACTIONS.contains(action)
                || (action == AuditLogEntity.AuditAction.GRANT && "user".equals(resourceType))) {
            return PERMISSION_CHANGED;
        }
        if ("risk_rule".equals(resourceType) && RISK_POLICY_ACTIONS.contains(action)) {
            return RISK_POLICY_CHANGED;
        }
        return OTHER;
    }
}
