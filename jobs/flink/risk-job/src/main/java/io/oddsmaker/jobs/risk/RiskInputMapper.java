package io.oddsmaker.jobs.risk;

import io.oddsmaker.jobs.enrich.RawEvent;
import org.apache.flink.api.common.functions.FlatMapFunction;

import java.math.BigDecimal;
import java.sql.Timestamp;

/**
 * RiskJob 输入映射（拆分 LAY-001）：RawEvent → RiskInput 算子与纯映射函数，
 * 必填（game/environment/event/device）缺失返回 null（由 inputMapper 丢弃）。
 * 信任档位为网关权威值（B4 契约 v2），null 归一为空串——'' 不满足 HIGH 精确匹配，
 * 安全侧收敛（fail-closed）。
 */
final class RiskInputMapper {

    private RiskInputMapper() {}

    /** RawEvent → RiskInput 的算子封装（null 表示丢弃）。 */
    static FlatMapFunction<RawEvent, RiskJob.RiskInput> inputMapper() {
        return (r, out) -> {
            RiskJob.RiskInput in = toRiskInput(r);
            if (in != null) out.collect(in);
        };
    }

    /** RawEvent → 风控输入；必填（game/environment/event/device）缺失返回 null。 */
    static RiskJob.RiskInput toRiskInput(RawEvent r) {
        String gameId = str(r.game_id);
        String environmentName = str(r.environment);
        String eventId = str(r.event_id);
        String eventName = str(r.event_name);
        String userId = nz(str(r.user_id));
        String deviceId = str(r.device_id);
        if (gameId == null || environmentName == null || eventId == null || deviceId == null) return null;
        RiskJob.RiskInput in = new RiskJob.RiskInput();
        in.gameId = gameId;
        in.environment = environmentName;
        in.eventId = eventId;
        in.eventName = eventName;
        in.eventType = nz(str(r.event_type));
        in.userId = userId;
        in.deviceId = deviceId;
        in.clientIp = nz(str(r.client_ip));
        in.ts = new Timestamp(System.currentTimeMillis());
        Long tsServer = r.ts_server;
        if (tsServer != null) in.ts = new Timestamp(tsServer / 1000L);
        in.amount = parseAmount(r.resource_amount);
        in.flowType = nz(r.flow_type);
        in.resourceId = nz(r.resource_id);
        in.propsJson = r.props_json;
        in.receiptKey = firstNonBlank(r.receipt_hash, r.order_id);
        in.revenueAmount = parseAmount(r.revenue_amount);
        // B6 §5.2：信任档位为网关权威值（B4 契约 v2），null 归一为空串——
        // 与 CH 事件表迁移同款语义：'' 天然不满足 HIGH 精确匹配，安全侧收敛（fail-closed）
        in.trustLevel = nz(str(r.trust_level));
        String adFormat = nz(r.ad_format);
        // schema 无 game_event_type 字段：gameEventType 是空串字面量，
        // "ad_reward".equalsIgnoreCase("") 恒 false，死枝等价删除
        in.adReward = "rewarded".equalsIgnoreCase(adFormat)
                || (eventName != null && eventName.contains("ad_reward"));
        return in;
    }

    static String str(Object v) { return v == null ? null : v.toString(); }

    static String nz(String s) { return s == null ? "" : s; }

    static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    static BigDecimal parseAmount(Object v) {
        if (v == null) return null;
        try { return new BigDecimal(v.toString()); } catch (Exception e) { return null; }
    }
}
