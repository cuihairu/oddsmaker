package io.oddsmaker.gateway.config;

import io.oddsmaker.common.model.Event;

import java.util.Map;
import java.util.Set;

/**
 * 事件契约 v2 信任策略：source/trust_level 为网关权威字段（06 计划书 §4.2）。
 *
 * <p>推导规则：source 缺省时按 key 档位回填（SERVER key→server，其余→client）；
 * trust_level 一律由 source 推导（client→LOW、server/system→HIGH、derived→COMPUTED），
 * 发送方声明的 trust_level 永不采信。自抬拒绝：CLIENT key 声明 server/system/derived、
 * 任何 key 声明高于推导档的 trust_level，均整事件拒绝（trust_escalation）。
 * system/derived 为平台内部保留档，任何接入 key 不得声明。
 *
 * <p>event_version 缺省回填 1、event_origin 缺省回填 gateway（网关直收，无 SDK 声明）。
 * 回填发生在 schema 校验通过之后、发布之前——所有下游（Kafka/ClickHouse/Flink）看到的
 * 均为带权威 v2 字段的事件；旧字段布局（v1 事件不携带新字段）不受影响，向后兼容。
 */
public final class TrustPolicy {

    /** 事件契约主版本缺省值（v1 基座）。 */
    public static final int DEFAULT_EVENT_VERSION = 1;

    /** event_origin 缺省值：事件经网关直收，无 SDK 声明来源。 */
    public static final String DEFAULT_ORIGIN = "gateway";

    private static final Set<String> CLAIMABLE_SOURCES = Set.of("client", "server");
    private static final Map<String, Integer> TRUST_RANK = Map.of("LOW", 0, "COMPUTED", 1, "HIGH", 2);

    private TrustPolicy() {}

    /**
     * 按可信来源推导信任档（client→LOW；server/system→HIGH；derived→COMPUTED）。
     */
    public static String trustOf(String source) {
        return switch (source) {
            case "server", "system" -> "HIGH";
            case "derived" -> "COMPUTED";
            default -> "LOW";
        };
    }

    /**
     * 校验并回填事件的 v2 权威字段。
     *
     * @param event   已过 schema 校验的事件（source/trust_level 若存在必为合法枚举值）
     * @param keyRole API key 档位（client|server|admin，本地静态 key 为 null）
     * @return null=通过（event 已就地回填权威值）；非 null=拒绝明细（source_escalation / trust_level_escalation）
     */
    public static String apply(Event event, String keyRole) {
        boolean serverKey = "server".equals(keyRole);
        String resolved;
        if (event.source == null) {
            resolved = serverKey ? "server" : "client";
        } else if (!CLAIMABLE_SOURCES.contains(event.source)) {
            // system/derived 为平台内部保留档，任何接入 key 声明即自抬
            return "source_escalation";
        } else if ("server".equals(event.source) && !serverKey) {
            // SERVER 档来源只能由 SERVER key 声明
            return "source_escalation";
        } else {
            resolved = event.source;
        }

        String derived = trustOf(resolved);
        if (event.trustLevel != null) {
            Integer claimedRank = TRUST_RANK.get(event.trustLevel);
            // schema enum 校验保证合法值；防御壳防脏 schema 注入未识别档位
            if (claimedRank != null && claimedRank > TRUST_RANK.get(derived)) {
                return "trust_level_escalation";
            }
        }

        event.source = resolved;
        event.trustLevel = derived;
        if (event.eventVersion == null) {
            event.eventVersion = DEFAULT_EVENT_VERSION;
        }
        if (event.eventOrigin == null || event.eventOrigin.isBlank()) {
            event.eventOrigin = DEFAULT_ORIGIN;
        }
        return null;
    }
}
