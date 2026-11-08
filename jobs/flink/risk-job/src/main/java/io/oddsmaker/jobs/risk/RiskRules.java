package io.oddsmaker.jobs.risk;

import io.oddsmaker.jobs.enrich.KeyCodec;
import org.apache.flink.api.common.state.StateTtlConfig;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * RiskJob 规则族（拆分 LAY-001）：口径谓词、窗口类函数（FREQUENCY/VELOCITY/RATIO/
 * DUPLICATE_RECEIPT/AD_REWARD）、PATTERN 序列状态机、命中构造、主体/信任口径与
 * 特征分支前提谓词。单测经 RiskJob 薄委托面访问（RiskJob.FrequencyFunction 等为子类壳）。
 */
final class RiskRules {

    private RiskRules() {}

    // ============ 口径谓词 ============

    /** THRESHOLD 过滤：资源量严格大于阈值。 */
    static boolean overThreshold(RiskJob.RiskInput i) {
        RuleConfig.RuleSpec spec = RuleConfig.byType("THRESHOLD");
        return i.amount != null && i.amount.compareTo(BigDecimal.valueOf(spec.triggerThreshold)) > 0;
    }

    /** VELOCITY 口径：携带正数金额。 */
    static boolean hasAmount(RiskJob.RiskInput i) {
        return i.amount != null && i.amount.compareTo(BigDecimal.ZERO) > 0;
    }

    /** RATIO 口径：正数金额且 flow 为 source/sink。 */
    static boolean isFlowAmount(RiskJob.RiskInput i) {
        return i.amount != null && i.amount.compareTo(BigDecimal.ZERO) > 0
                && ("source".equals(i.flowType) || "sink".equals(i.flowType));
    }

    /** DUPLICATE_RECEIPT 口径：携带非空收据键。 */
    static boolean hasReceipt(RiskJob.RiskInput i) {
        return i.receiptKey != null && !i.receiptKey.isEmpty();
    }

    /** AD_REWARD 口径：激励广告 reward 事件。 */
    static boolean isAdReward(RiskJob.RiskInput i) { return i.adReward; }

    /** PATTERN 口径：事件名属于当前序列规则的事件集合。 */
    static boolean inPatternSequence(RiskJob.RiskInput i) {
        RuleConfig.RuleSpec spec = RuleConfig.byType("PATTERN");
        return i.eventName != null && spec.sequence.contains(i.eventName);
    }

    /** device_count/win_rate/event_count 的主体口径前提：带 user_id 的 PLAYER 主体 */
    static boolean isPlayerSubject(RiskJob.RiskInput i) {
        return i.userId != null && !i.userId.isEmpty();
    }

    /** account_count_per_ip 的入窗口前提：带账号且带 IP */
    static boolean hasIpAccount(RiskJob.RiskInput i) {
        return isPlayerSubject(i) && i.clientIp != null && !i.clientIp.isEmpty();
    }

    /** account_count_per_ip 的分键：game|env|IP:<ip> */
    static String ipWindowKey(RiskJob.RiskInput i) {
        return KeyCodec.join(i.gameId, i.environment, FeatureCalc.ipScopeKey(i));
    }

    // ============ 命中构造 ============

    /** THRESHOLD 命中构造。 */
    static RiskJob.RiskHit thresholdHit(RiskJob.RiskInput i) {
        RuleConfig.RuleSpec spec = RuleConfig.byType("THRESHOLD");
        Map<String, String> ev = new HashMap<>();
        ev.put("resource_amount", i.amount.toPlainString());
        ev.put("event_name", i.eventName);
        return new RiskJob.RiskHit(
                i.gameId, i.environment, i.ts, UUID.randomUUID().toString(), i.eventId,
                spec.ruleId != null ? spec.ruleId : "risk-threshold-amount", "THRESHOLD", spec.riskLevel,
                subjectType(i), subjectId(i),
                spec.riskScore, spec.actionType,
                "resource amount " + i.amount.toPlainString() + " exceeds threshold " + spec.triggerThreshold,
                ev, i.trustLevel);
    }

    /** PATTERN 命中事件构造。 */
    static RiskJob.RiskHit patternHit(RuleConfig.RuleSpec spec, RiskJob.RiskInput last, List<String> seq, long startTs) {
        int windowMin = Math.max(1, spec.windowSeconds / 60);
        String seqText = String.join(" -> ", seq);
        Map<String, String> ev = new HashMap<>();
        ev.put("sequence", seqText);
        ev.put("window_minutes", String.valueOf(windowMin));
        ev.put("first_ts", String.valueOf(startTs));
        ev.put("subject", subjectKey(last));
        return new RiskJob.RiskHit(
                last.gameId, last.environment, last.ts, UUID.randomUUID().toString(), last.eventId,
                spec.ruleId != null ? spec.ruleId : "risk-pattern-sequence", "PATTERN", spec.riskLevel,
                subjectType(last), subjectId(last),
                spec.riskScore, spec.actionType,
                "sequence matched in " + windowMin + "min: " + seqText,
                ev, last.trustLevel);
    }

    // ============ FREQUENCY ============

    /** FREQUENCY：窗口内事件数超阈值告警。 */
    static class FrequencyFunction extends ProcessWindowFunction<RiskJob.RiskInput, RiskJob.RiskHit, String, TimeWindow> {
        private final int windowMin;
        private final RiskJob.RuleSource rules;

        FrequencyFunction(int windowMin, RiskJob.RuleSource rules) {
            this.windowMin = windowMin;
            this.rules = rules;
        }

        @Override
        public void open(org.apache.flink.configuration.Configuration parameters) {
            RuleFetcher.startOnce(rules.controlUrl, rules.gameId, rules.token, rules.refreshMs);
        }

        @Override
        public void process(String key, Context ctx, Iterable<RiskJob.RiskInput> events, Collector<RiskJob.RiskHit> out) {
            RuleConfig.RuleSpec spec = RuleConfig.byType("FREQUENCY");
            long count = 0;
            RiskJob.RiskInput last = null;
            boolean allHigh = true;
            for (RiskJob.RiskInput e : events) {
                count++;
                last = e;
                if (!"HIGH".equals(e.trustLevel)) allHigh = false;
            }
            int limit = spec.triggerThreshold;
            if (count > limit && last != null) {
                Map<String, String> ev = new HashMap<>();
                ev.put("window_events", String.valueOf(count));
                ev.put("window_minutes", String.valueOf(windowMin));
                ev.put("subject", subjectKey(last));
                out.collect(new RiskJob.RiskHit(
                        last.gameId, last.environment, last.ts, UUID.randomUUID().toString(), last.eventId,
                        spec.ruleId != null ? spec.ruleId : "risk-frequency-burst", "FREQUENCY", spec.riskLevel,
                        subjectType(last), subjectId(last),
                        spec.riskScore, spec.actionType,
                        "event burst " + count + " in " + windowMin + "min (limit " + limit + ")",
                        ev, trustScope(allHigh)));
            }
        }
    }

    // ============ VELOCITY ============

    /** VELOCITY：窗口内资源总量超阈值告警。 */
    static class VelocityFunction extends ProcessWindowFunction<RiskJob.RiskInput, RiskJob.RiskHit, String, TimeWindow> {
        private final int windowMin;
        private final RiskJob.RuleSource rules;

        VelocityFunction(int windowMin, RiskJob.RuleSource rules) {
            this.windowMin = windowMin;
            this.rules = rules;
        }

        @Override
        public void open(org.apache.flink.configuration.Configuration parameters) {
            RuleFetcher.startOnce(rules.controlUrl, rules.gameId, rules.token, rules.refreshMs);
        }

        @Override
        public void process(String key, Context ctx, Iterable<RiskJob.RiskInput> events, Collector<RiskJob.RiskHit> out) {
            RuleConfig.RuleSpec spec = RuleConfig.byType("VELOCITY");
            BigDecimal sum = BigDecimal.ZERO;
            long count = 0;
            RiskJob.RiskInput last = null;
            boolean allHigh = true;
            for (RiskJob.RiskInput e : events) {
                sum = sum.add(e.amount);
                count++;
                last = e;
                if (!"HIGH".equals(e.trustLevel)) allHigh = false;
            }
            BigDecimal limit = BigDecimal.valueOf(spec.triggerThreshold);
            if (sum.compareTo(limit) > 0 && last != null) {
                Map<String, String> ev = new HashMap<>();
                ev.put("window_sum", sum.toPlainString());
                ev.put("window_events", String.valueOf(count));
                ev.put("window_minutes", String.valueOf(windowMin));
                ev.put("subject", subjectKey(last));
                out.collect(new RiskJob.RiskHit(
                        last.gameId, last.environment, last.ts, UUID.randomUUID().toString(), last.eventId,
                        spec.ruleId != null ? spec.ruleId : "risk-velocity-amount", "VELOCITY", spec.riskLevel,
                        subjectType(last), subjectId(last),
                        spec.riskScore, spec.actionType,
                        "resource velocity " + sum.toPlainString() + " in " + windowMin + "min exceeds " + limit.toPlainString(),
                        ev, trustScope(allHigh)));
            }
        }
    }

    // ============ RATIO ============

    /** RATIO：窗口内 source/sink 金额比超阈值告警。 */
    static class RatioFunction extends ProcessWindowFunction<RiskJob.RiskInput, RiskJob.RiskHit, String, TimeWindow> {
        private final int windowMin;
        private final RiskJob.RuleSource rules;

        RatioFunction(int windowMin, RiskJob.RuleSource rules) {
            this.windowMin = windowMin;
            this.rules = rules;
        }

        @Override
        public void open(org.apache.flink.configuration.Configuration parameters) {
            RuleFetcher.startOnce(rules.controlUrl, rules.gameId, rules.token, rules.refreshMs);
        }

        @Override
        public void process(String key, Context ctx, Iterable<RiskJob.RiskInput> events, Collector<RiskJob.RiskHit> out) {
            RuleConfig.RuleSpec spec = RuleConfig.byType("RATIO");
            BigDecimal sourceSum = BigDecimal.ZERO;
            BigDecimal sinkSum = BigDecimal.ZERO;
            RiskJob.RiskInput last = null;
            boolean allHigh = true;
            for (RiskJob.RiskInput e : events) {
                if ("source".equals(e.flowType)) sourceSum = sourceSum.add(e.amount);
                else if ("sink".equals(e.flowType)) sinkSum = sinkSum.add(e.amount);
                last = e;
                if (!"HIGH".equals(e.trustLevel)) allHigh = false;
            }
            // sinkSum > 0 蕴含窗口循环至少执行过一次（同一循环里给 last 赋值），
            // last != null 恒真，等价删枝
            if (sinkSum.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal ratio = sourceSum.divide(sinkSum, 2, RoundingMode.HALF_UP);
                BigDecimal limit = BigDecimal.valueOf(spec.triggerThreshold);
                if (ratio.compareTo(limit) > 0) {
                    Map<String, String> ev = new HashMap<>();
                    ev.put("source_sum", sourceSum.toPlainString());
                    ev.put("sink_sum", sinkSum.toPlainString());
                    ev.put("ratio", ratio.toPlainString());
                    ev.put("window_minutes", String.valueOf(windowMin));
                    ev.put("subject", subjectKey(last));
                    out.collect(new RiskJob.RiskHit(
                            last.gameId, last.environment, last.ts, UUID.randomUUID().toString(), last.eventId,
                            spec.ruleId != null ? spec.ruleId : "risk-ratio-source-sink", "RATIO", spec.riskLevel,
                            subjectType(last), subjectId(last),
                            spec.riskScore, spec.actionType,
                            "source/sink ratio " + ratio.toPlainString() + " in " + windowMin + "min exceeds " + limit.toPlainString(),
                            ev, trustScope(allHigh)));
                }
            }
        }
    }

    // ============ DUPLICATE_RECEIPT ============

    /** DUPLICATE_RECEIPT：同一收据键在窗口内出现次数达到阈值。 */
    static class DuplicateReceiptFunction extends ProcessWindowFunction<RiskJob.RiskInput, RiskJob.RiskHit, String, TimeWindow> {
        private final int windowMin;
        private final RiskJob.RuleSource rules;

        DuplicateReceiptFunction(int windowMin, RiskJob.RuleSource rules) {
            this.windowMin = windowMin;
            this.rules = rules;
        }

        @Override
        public void open(org.apache.flink.configuration.Configuration parameters) {
            RuleFetcher.startOnce(rules.controlUrl, rules.gameId, rules.token, rules.refreshMs);
        }

        @Override
        public void process(String key, Context ctx, Iterable<RiskJob.RiskInput> events, Collector<RiskJob.RiskHit> out) {
            RuleConfig.RuleSpec spec = RuleConfig.byType("DUPLICATE_RECEIPT");
            long count = 0;
            RiskJob.RiskInput last = null;
            boolean allHigh = true;
            for (RiskJob.RiskInput e : events) {
                count++;
                last = e;
                if (!"HIGH".equals(e.trustLevel)) allHigh = false;
            }
            if (count >= spec.triggerThreshold && last != null) {
                Map<String, String> ev = new HashMap<>();
                ev.put("receipt_key", last.receiptKey);
                ev.put("occurrences", String.valueOf(count));
                ev.put("window_minutes", String.valueOf(windowMin));
                ev.put("subject", subjectKey(last));
                out.collect(new RiskJob.RiskHit(
                        last.gameId, last.environment, last.ts, UUID.randomUUID().toString(), last.eventId,
                        spec.ruleId != null ? spec.ruleId : "risk-duplicate-receipt", "DUPLICATE_RECEIPT", spec.riskLevel,
                        subjectType(last), subjectId(last),
                        spec.riskScore, spec.actionType,
                        "receipt " + last.receiptKey + " submitted " + count + " times in " + windowMin + "min",
                        ev, trustScope(allHigh)));
            }
        }
    }

    // ============ AD_REWARD ============

    /** AD_REWARD：激励广告 reward 事件窗口内爆发。 */
    static class AdRewardFunction extends ProcessWindowFunction<RiskJob.RiskInput, RiskJob.RiskHit, String, TimeWindow> {
        private final int windowMin;
        private final RiskJob.RuleSource rules;

        AdRewardFunction(int windowMin, RiskJob.RuleSource rules) {
            this.windowMin = windowMin;
            this.rules = rules;
        }

        @Override
        public void open(org.apache.flink.configuration.Configuration parameters) {
            RuleFetcher.startOnce(rules.controlUrl, rules.gameId, rules.token, rules.refreshMs);
        }

        @Override
        public void process(String key, Context ctx, Iterable<RiskJob.RiskInput> events, Collector<RiskJob.RiskHit> out) {
            RuleConfig.RuleSpec spec = RuleConfig.byType("AD_REWARD");
            long count = 0;
            BigDecimal revenueSum = BigDecimal.ZERO;
            RiskJob.RiskInput last = null;
            boolean allHigh = true;
            for (RiskJob.RiskInput e : events) {
                count++;
                if (e.revenueAmount != null) revenueSum = revenueSum.add(e.revenueAmount);
                last = e;
                if (!"HIGH".equals(e.trustLevel)) allHigh = false;
            }
            if (count > spec.triggerThreshold && last != null) {
                Map<String, String> ev = new HashMap<>();
                ev.put("ad_reward_count", String.valueOf(count));
                ev.put("revenue_sum", revenueSum.toPlainString());
                ev.put("window_minutes", String.valueOf(windowMin));
                ev.put("subject", subjectKey(last));
                out.collect(new RiskJob.RiskHit(
                        last.gameId, last.environment, last.ts, UUID.randomUUID().toString(), last.eventId,
                        spec.ruleId != null ? spec.ruleId : "risk-ad-reward-abuse", "AD_REWARD", spec.riskLevel,
                        subjectType(last), subjectId(last),
                        spec.riskScore, spec.actionType,
                        "ad reward burst " + count + " in " + windowMin + "min (limit " + spec.triggerThreshold + ")",
                        ev, trustScope(allHigh)));
            }
        }
    }

    // ============ PATTERN 序列状态机 ============

    /** PATTERN 状态机进行中的状态（nextIdx=null 表示空闲）。 */
    static class SeqState {
        /** 下一个期望步的索引；null=空闲 */
        Integer nextIdx;
        /** 第一步命中时间戳（毫秒）；空闲为 null */
        Long startTs;
    }

    /**
     * PATTERN：keyed 有序序列状态机。与窗口类算子不同，序列匹配是逐事件增量推进，
     * 用 KeyedProcessFunction + ValueState 维护「下一步期望索引 + 第一步时间戳」，
     * 窗口自第一步起算、惰性过期；命中后重置，可再次从头匹配。
     * 状态机核心在纯函数 {@link #advance}，不依赖 Flink 运行时，单测直测。
     */
    static class PatternFunction extends KeyedProcessFunction<String, RiskJob.RiskInput, RiskJob.RiskHit> {
        private final RiskJob.RuleSource rules;
        private transient ValueState<Integer> progress;
        private transient ValueState<Long> startedAt;

        PatternFunction(RiskJob.RuleSource rules) {
            this.rules = rules;
        }

        @Override
        public void open(org.apache.flink.configuration.Configuration parameters) {
            RuleFetcher.startOnce(rules.controlUrl, rules.gameId, rules.token, rules.refreshMs);
            progress = keyedState("pattern-progress", Types.INT);
            startedAt = keyedState("pattern-started-at", Types.LONG);
        }

        /** 带 TTL 的 keyed 状态：惰性过期之外，主体再无后续事件的僵尸 key 由 TTL 兜底回收。 */
        private <T> ValueState<T> keyedState(String name, org.apache.flink.api.common.typeinfo.TypeInformation<T> type) {
            ValueStateDescriptor<T> desc = new ValueStateDescriptor<>(name, type);
            desc.enableTimeToLive(StateTtlConfig.newBuilder(org.apache.flink.api.common.time.Time.days(1)).build());
            return getRuntimeContext().getState(desc);
        }

        @Override
        public void processElement(RiskJob.RiskInput i, Context ctx, Collector<RiskJob.RiskHit> out) throws Exception {
            RuleConfig.RuleSpec spec = RuleConfig.byType("PATTERN");
            SeqState st = new SeqState();
            st.nextIdx = progress.value();
            st.startTs = startedAt.value();
            RiskJob.RiskHit hit = advance(spec, i, st);
            if (hit != null) out.collect(hit);
            if (st.nextIdx == null) progress.clear();
            else progress.update(st.nextIdx);
            if (st.startTs == null) startedAt.clear();
            else startedAt.update(st.startTs);
        }
    }

    /**
     * PATTERN 单步转移（纯函数）：按当前状态推进序列匹配，原地更新 st；
     * 序列完成时返回命中事件（st 已重置为空闲），否则返回 null。
     * 语义：窗口自第一步起算，超窗惰性作废；非期望步事件若是第一步则重新起序，否则忽略。
     */
    static RiskJob.RiskHit advance(RuleConfig.RuleSpec spec, RiskJob.RiskInput i, SeqState st) {
        List<String> seq = spec.sequence;
        if (i.eventName == null || seq.size() < 2) return null;
        long ts = i.ts.getTime();

        // 惰性过期：进行中的序列超出窗口则作废
        if (st.nextIdx != null && st.startTs != null && ts - st.startTs > spec.windowSeconds * 1000L) {
            st.nextIdx = null;
            st.startTs = null;
        }

        if (st.nextIdx == null) {
            // 空闲：只有第一步能起序
            if (seq.get(0).equals(i.eventName)) {
                st.nextIdx = 1;
                st.startTs = ts;
            }
            return null;
        }

        if (seq.get(st.nextIdx).equals(i.eventName)) {
            if (st.nextIdx == seq.size() - 1) {
                long start = st.startTs;
                st.nextIdx = null;
                st.startTs = null;
                return patternHit(spec, i, seq, start);
            }
            st.nextIdx++;
            return null;
        }

        // 非期望步：第一步重新起序（含第一步重复出现 → 以最新为起点），其余忽略
        if (seq.get(0).equals(i.eventName)) {
            st.nextIdx = 1;
            st.startTs = ts;
        }
        return null;
    }

    // ============ 主体/信任口径 ============

    static String subjectType(RiskJob.RiskInput i) {
        return i.userId != null && !i.userId.isEmpty() ? "PLAYER" : "DEVICE";
    }

    static String subjectId(RiskJob.RiskInput i) {
        return i.userId != null && !i.userId.isEmpty() ? i.userId : i.deviceId;
    }

    static String subjectKey(RiskJob.RiskInput i) {
        return subjectType(i) + ":" + subjectId(i);
    }

    /** FREQUENCY/VELOCITY/RATIO/AD_REWARD 的分键：game|env|subject。 */
    static String subjectWindowKey(RiskJob.RiskInput i) {
        return KeyCodec.join(i.gameId, i.environment, subjectKey(i));
    }

    /** DUPLICATE_RECEIPT 的分键：主体键 + 收据键。 */
    static String receiptWindowKey(RiskJob.RiskInput i) {
        return KeyCodec.join(i.gameId, i.environment, subjectKey(i), i.receiptKey);
    }

    /** 窗口口径信任档位：窗口内事件全为 HIGH 才收敛为 "HIGH"，否则 ""（混杂窗口 fail-closed）。 */
    static String trustScope(boolean allHigh) {
        return allHigh ? "HIGH" : "";
    }

    /**
     * BLOCK 级动作高信任门槛（计划书 §5.2/§4.3）：BLOCK 只允许 trust_level=HIGH 的输入驱动；
     * ''/null/LOW/COMPUTED 或窗口混杂一律降级 REVIEW——安全侧收敛，宁转人审不放行封禁。
     * 其余动作不受门槛影响，原样返回。
     */
    static String gateAction(String action, String trustLevel) {
        return "BLOCK".equalsIgnoreCase(action) && !"HIGH".equals(trustLevel) ? "REVIEW" : action;
    }

    /** 命中流统一门槛（union 后、进 sink 前）：BLOCK 降级时 reason 打 trust_gate 标记便于回溯。 */
    static RiskJob.RiskHit applyTrustGate(RiskJob.RiskHit h) {
        String gated = gateAction(h.action, h.trustLevel);
        if (!gated.equals(h.action)) {
            h.action = gated;
            h.reason = "[trust_gate] " + h.reason;
        }
        return h;
    }
}
