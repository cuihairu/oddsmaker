package io.oddsmaker.jobs.risk;

import org.apache.flink.api.common.state.StateTtlConfig;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RiskJob 评估产出（拆分 LAY-001）：RiskScore 独立算子——按主体键累计各规则最大贡献分，
 * 每次评估触发产出一行 risk_scores（ReplacingMergeTree 按主体保留最新累计快照）。
 */
final class RiskScore {

    private RiskScore() {}

    /**
     * B6 §5.2 RiskScore 独立：按主体键累计各规则对主体的最大贡献分——Rule 静态 riskScore
     * 降级为「该规则最大贡献分」，同规则重复命中取高不叠加；每次评估触发产出一行 risk_scores
     * （subject、累计分、规则触发明细 JSON），ReplacingMergeTree 按主体保留最新累计快照。
     */
    static class RiskScoreFunction extends KeyedProcessFunction<String, RiskJob.RiskHit, RiskJob.RiskScoreRow> {
        private transient ValueState<Map<String, Float>> contributions;

        @Override
        public void open(org.apache.flink.configuration.Configuration parameters) {
            ValueStateDescriptor<Map<String, Float>> desc =
                    new ValueStateDescriptor<>("risk-score-contributions", Types.MAP(Types.STRING, Types.FLOAT));
            // 状态兜底回收（同 PatternFunction 惯例）：主体 7 天无命中则累计清零重新起算
            desc.enableTimeToLive(StateTtlConfig.newBuilder(org.apache.flink.api.common.time.Time.days(7)).build());
            contributions = getRuntimeContext().getState(desc);
        }

        @Override
        public void processElement(RiskJob.RiskHit h, Context ctx, Collector<RiskJob.RiskScoreRow> out) throws Exception {
            Map<String, Float> byRule = contributions.value();
            if (byRule == null) byRule = new HashMap<>();
            Float prev = byRule.get(h.ruleId);
            if (prev == null || h.score > prev) byRule.put(h.ruleId, h.score);
            contributions.update(byRule);

            float total = 0f;
            List<String> reasons = new ArrayList<>(byRule.size());
            for (Map.Entry<String, Float> e : byRule.entrySet()) {
                total += e.getValue();
                reasons.add("{\"rule_id\":\"" + RiskJson.jsonEscape(e.getKey())
                        + "\",\"contribution\":" + e.getValue() + "}");
            }
            reasons.sort(null); // 明细顺序确定，便于断言与回溯
            out.collect(new RiskJob.RiskScoreRow(h.gameId, h.environment, h.subjectType, h.subjectId,
                    total, h.ts, reasons));
        }
    }

    /** risk_scores 的主体分键：game|env|subjectType:subjectId（与表 ORDER BY 主体维度对齐）。 */
    static String scoreKey(RiskJob.RiskHit h) {
        return io.oddsmaker.jobs.enrich.KeyCodec.join(h.gameId, h.environment, h.subjectType + ":" + h.subjectId);
    }
}
