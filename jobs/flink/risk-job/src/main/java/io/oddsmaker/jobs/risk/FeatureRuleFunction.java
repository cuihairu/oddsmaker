package io.oddsmaker.jobs.risk;

import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.api.common.state.ReadOnlyBroadcastState;
import org.apache.flink.api.common.state.StateTtlConfig;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.streaming.api.functions.co.KeyedBroadcastProcessFunction;
import org.apache.flink.util.Collector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * FEATURE 规则评估（B5 事件→特征→规则三段解耦的规则段）。
 *
 * 数据面：事件流 keyBy 主体；特征行（risk_features 产出）广播到全部 subtask。
 * 评估面：每个事件到达时，按主体最新特征快照评估 FEATURE 规则条件（全 AND），
 * 命中产出 RiskHit（riskType=FEATURE）进既有 risk_events 双 sink。
 *
 * 特征状态键为 scope|feature（{@link FeatureRow#stateKey}）：
 * SUBJECT 条件取主体 scope_key（PLAYER:/DEVICE:），IP 条件取主体最近一次上报的 client_ip
 * （keyed 状态记最近 IP，TTL 1 天兜底僵尸主体）。特征行未产出前条件按不满足计——无值不判真。
 */
class FeatureRuleFunction extends KeyedBroadcastProcessFunction<
        String, RiskJob.RiskInput, FeatureRow, RiskJob.RiskHit> {

    static final MapStateDescriptor<String, FeatureRow> FEATURE_STATE =
            new MapStateDescriptor<>("risk-feature-values", Types.STRING, Types.POJO(FeatureRow.class));

    private final RiskJob.RuleSource rules;
    private transient ValueState<String> lastIp;

    FeatureRuleFunction(RiskJob.RuleSource rules) {
        this.rules = rules;
    }

    @Override
    public void open(org.apache.flink.configuration.Configuration parameters) {
        RuleFetcher.startOnce(rules.controlUrl, rules.gameId, rules.token, rules.refreshMs);
        // 主体最近 IP：IP 口径条件的取值依据；TTL 兜底无后续事件的僵尸主体
        ValueStateDescriptor<String> desc =
                new ValueStateDescriptor<>("risk-subject-last-ip", Types.STRING);
        desc.enableTimeToLive(StateTtlConfig.newBuilder(
                org.apache.flink.api.common.time.Time.days(1)).build());
        lastIp = getRuntimeContext().getState(desc);
    }

    @Override
    public void processElement(RiskJob.RiskInput in, ReadOnlyContext ctx, Collector<RiskJob.RiskHit> out) throws Exception {
        RuleConfig.RuleSpec spec = RuleConfig.byType("FEATURE");
        if (spec == null || spec.features.isEmpty()) return;

        // IP 口径取值：本事件 IP 优先，退化主体最近一次上报（keyed TTL 状态）
        if (in.clientIp != null && !in.clientIp.isEmpty()) {
            lastIp.update(in.clientIp);
        }
        String ip = in.clientIp != null && !in.clientIp.isEmpty() ? in.clientIp : lastIp.value();
        String ipScope = ip == null ? null : "IP:" + ip;

        ReadOnlyBroadcastState<String, FeatureRow> state = ctx.getBroadcastState(FEATURE_STATE);
        Map<String, FeatureRow> snapshot = resolveSnapshot(spec, FeatureCalc.subjectScopeKey(in), ipScope, state);
        if (snapshot == null) return;

        out.collect(featureHit(spec, in, snapshot));
    }

    /**
     * 逐条件解析特征值并比较（全 AND）。返回 null = 至少一条件无值或不满足（不发事件）；
     * 命中返回 特征→特征行 快照供证据构造。IP 条件在主体无 IP（本事件与历史皆无）时按无值不判真。
     */
    static Map<String, FeatureRow> resolveSnapshot(RuleConfig.RuleSpec spec, String subjectScope, String ipScope,
                                                   ReadOnlyBroadcastState<String, FeatureRow> state) throws Exception {
        Map<String, FeatureRow> snapshot = new HashMap<>();
        for (RuleConfig.FeatureCondition c : spec.features) {
            String scopeKey = "IP".equals(c.scope) ? ipScope : subjectScope;
            if (scopeKey == null) return null;
            FeatureRow row = state.get(FeatureRow.stateKey(scopeKey, c.feature));
            // 特征行缺失按不满足计；窗口推进由最新广播覆盖自然接上
            if (row == null) return null;
            if (!FeatureCalc.matches(c.op, row.value, c.threshold)) return null;
            snapshot.put(c.feature, row);
        }
        return snapshot;
    }

    /** FEATURE 命中事件构造：证据带特征值、窗口界与 scope_key，回溯可对上 risk_features 行 */
    static RiskJob.RiskHit featureHit(RuleConfig.RuleSpec spec, RiskJob.RiskInput in,
                                      Map<String, FeatureRow> snapshot) {
        StringBuilder reason = new StringBuilder("feature rule hit:");
        Map<String, String> ev = new HashMap<>();
        boolean first = true;
        for (RuleConfig.FeatureCondition c : spec.features) {
            FeatureRow row = snapshot.get(c.feature);
            reason.append(first ? " " : ", ")
                    .append(c.feature).append("=").append(format(row.value))
                    .append(" ").append(c.op).append(" ").append(format(c.threshold));
            ev.put("feature:" + c.feature, format(row.value));
            ev.put("feature:" + c.feature + ":window_end", String.valueOf(row.windowEndMs));
            first = false;
        }
        ev.put("scope_key", FeatureCalc.subjectScopeKey(in));
        return new RiskJob.RiskHit(
                in.gameId, in.environment, in.ts, UUID.randomUUID().toString(), in.eventId,
                spec.ruleId != null ? spec.ruleId : "risk-feature-rule", "FEATURE", spec.riskLevel,
                RiskJob.subjectType(in), RiskJob.subjectId(in),
                spec.riskScore, spec.actionType, reason.toString(), ev, in.trustLevel);
    }

    private static String format(double v) {
        return v == Math.rint(v) && !Double.isInfinite(v) && Math.abs(v) < 1e15
                ? String.valueOf((long) v) : String.valueOf(v);
    }

    @Override
    public void processBroadcastElement(FeatureRow row, Context ctx, Collector<RiskJob.RiskHit> out) throws Exception {
        ctx.getBroadcastState(FEATURE_STATE).put(row.stateKey(), row);
    }
}
