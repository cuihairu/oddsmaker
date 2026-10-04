package io.oddsmaker.jobs.risk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

/**
 * 首批 6 特征的口径与窗口聚合（B5，特征清单以计划书 §5.1 为准）：
 * <ul>
 *   <li>gold_gain_1h / gold_gain_24h：主体口径，窗口内金币资源流入合计（flow_type=source 且 resource_id=gold）</li>
 *   <li>device_count：主体（仅 PLAYER）口径，窗口内使用过的不同设备数</li>
 *   <li>account_count_per_ip：IP 口径，窗口内同一 client_ip 出现的不同账号数</li>
 *   <li>win_rate：主体口径，窗口内 match 结果事件（match:complete/:end）中 props.win=true 占比</li>
 *   <li>event_count_10m：主体口径，10 分钟窗口事件数（FREQUENCY 型信号特征化）</li>
 * </ul>
 * 聚合核心是纯函数（直测），窗口函数类只做 ctx/时间戳接线。
 */
final class FeatureCalc {

    /** 金币资源 ID 首批口径：resource_id=gold（B7 EventSchema 字典化后再扩展） */
    static final String GOLD_RESOURCE_ID = "gold";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FeatureCalc() {}

    // ===== 事件口径过滤（直测） =====

    /** 金币流入事件：flow_type=source 且 resource_id=gold */
    static boolean isGoldSource(RiskJob.RiskInput i) {
        return "source".equals(i.flowType) && GOLD_RESOURCE_ID.equals(i.resourceId);
    }

    /** match 结果事件：事件名以 match: 开头且第二段为 complete/end */
    static boolean isMatchResult(RiskJob.RiskInput i) {
        if (i.eventName == null) return false;
        if (!i.eventName.startsWith("match:")) return false;
        String rest = i.eventName.substring("match:".length());
        return rest.equals("complete") || rest.equals("end");
    }

    /** props_json 的 win 布尔值；无字段/非布尔/解析失败返回 false（按未胜计） */
    static boolean extractWin(String propsJson) {
        if (propsJson == null || propsJson.isEmpty()) return false;
        try {
            JsonNode n = MAPPER.readTree(propsJson).path("win");
            return n.isBoolean() && n.asBoolean();
        } catch (Exception e) {
            return false;
        }
    }

    /** IP 口径 scope_key：IP:&lt;client_ip&gt;；无 IP 返回 null */
    static String ipScopeKey(RiskJob.RiskInput i) {
        return i.clientIp == null || i.clientIp.isEmpty() ? null : "IP:" + i.clientIp;
    }

    /** 主体口径 scope_key：PLAYER:&lt;uid&gt; / DEVICE:&lt;did&gt; */
    static String subjectScopeKey(RiskJob.RiskInput i) {
        return RiskJob.subjectKey(i);
    }

    // ===== 聚合核心（纯函数，直测） =====

    /** 窗口内金币流入合计 */
    static double goldGain(Iterable<RiskJob.RiskInput> events) {
        BigDecimal sum = BigDecimal.ZERO;
        for (RiskJob.RiskInput e : events) {
            if (isGoldSource(e) && e.amount != null) {
                sum = sum.add(e.amount);
            }
        }
        return sum.doubleValue();
    }

    /** 窗口内不同设备数 */
    static long deviceCount(Iterable<RiskJob.RiskInput> events) {
        Set<String> devices = new HashSet<>();
        for (RiskJob.RiskInput e : events) {
            if (e.deviceId != null && !e.deviceId.isEmpty()) devices.add(e.deviceId);
        }
        return devices.size();
    }

    /** 窗口内同一 IP 出现的不同账号数 */
    static long accountCount(Iterable<RiskJob.RiskInput> events) {
        Set<String> users = new HashSet<>();
        for (RiskJob.RiskInput e : events) {
            if (e.userId != null && !e.userId.isEmpty()) users.add(e.userId);
        }
        return users.size();
    }

    /** 窗口内 match 结果事件的胜率（无结果事件返回 -1，调用方跳过产出） */
    static double winRate(Iterable<RiskJob.RiskInput> events) {
        long total = 0;
        long wins = 0;
        for (RiskJob.RiskInput e : events) {
            if (isMatchResult(e)) {
                total++;
                if (extractWin(e.propsJson)) wins++;
            }
        }
        return total == 0 ? -1 : (double) wins / total;
    }

    /** 窗口内事件数 */
    static long eventCount(Iterable<RiskJob.RiskInput> events) {
        long n = 0;
        for (RiskJob.RiskInput e : events) n++;
        return n;
    }

    /** FEATURE 条件比较（直测）；未知算子恒 false */
    static boolean matches(String op, double value, double threshold) {
        switch (op) {
            case ">":  return value > threshold;
            case ">=": return value >= threshold;
            case "<":  return value < threshold;
            case "<=": return value <= threshold;
            case "==": return Double.compare(value, threshold) == 0;
            default:   return false;
        }
    }

    /** 取迭代器最后一个元素（窗口内事件非空时用于取主体/归属字段） */
    static RiskJob.RiskInput lastOf(Iterable<RiskJob.RiskInput> events) {
        RiskJob.RiskInput last = null;
        for (RiskJob.RiskInput e : events) last = e;
        return last;
    }

    // ===== 窗口函数（接线层：ctx 提供窗口界与产出时间，聚合走上面纯函数） =====

    /** 窗口产出的公共骨架：聚合值有效（非 NaN）才发 FeatureRow。 */
    private abstract static class FeatureWindowFunction
            extends ProcessWindowFunction<RiskJob.RiskInput, FeatureRow, String, TimeWindow> {
        final String featureName;

        FeatureWindowFunction(String featureName) { this.featureName = featureName; }

        /** 聚合；返回 NaN 表示本窗口无产出（如无结果事件的胜率） */
        abstract double aggregate(Iterable<RiskJob.RiskInput> events);

        /** 主体 scope_key（IP 口径特征覆写） */
        String scopeKeyOf(RiskJob.RiskInput last) {
            return FeatureCalc.subjectScopeKey(last);
        }

        @Override
        public void process(String key, Context ctx, Iterable<RiskJob.RiskInput> events, Collector<FeatureRow> out) {
            double value = aggregate(events);
            if (Double.isNaN(value)) return;
            RiskJob.RiskInput last = lastOf(events);
            if (last == null) return;
            out.collect(new FeatureRow(last.gameId, last.environment, scopeKeyOf(last), featureName,
                    ctx.window().getStart(), windowEndOf(ctx.window()), value, ctx.currentProcessingTime()));
        }
    }

    /** 窗口边界：Flink TimeWindow 的 end 是排他界，落表取 maxTimestamp+1（=end）保持 [start, end) 语义 */
    static long windowEndOf(TimeWindow w) {
        return w.maxTimestamp() + 1;
    }

    static final class GoldGainFeature extends FeatureWindowFunction {
        GoldGainFeature(String featureName) { super(featureName); }
        @Override double aggregate(Iterable<RiskJob.RiskInput> events) { return goldGain(events); }
    }

    /** 仅 PLAYER 主体：设备聚集对匿名设备口径无意义（单设备恒 1） */
    static final class DeviceCountFeature extends FeatureWindowFunction {
        DeviceCountFeature() { super("device_count"); }
        @Override double aggregate(Iterable<RiskJob.RiskInput> events) { return deviceCount(events); }
    }

    /** IP 口径：scope_key 取 IP:&lt;client_ip&gt; */
    static final class AccountPerIpFeature extends FeatureWindowFunction {
        AccountPerIpFeature() { super("account_count_per_ip"); }
        @Override String scopeKeyOf(RiskJob.RiskInput last) { return ipScopeKey(last); }
        @Override double aggregate(Iterable<RiskJob.RiskInput> events) { return accountCount(events); }
    }

    static final class WinRateFeature extends FeatureWindowFunction {
        WinRateFeature() { super("win_rate"); }
        @Override double aggregate(Iterable<RiskJob.RiskInput> events) {
            double r = winRate(events);
            return r < 0 ? Double.NaN : r;
        }
    }

    static final class EventCountFeature extends FeatureWindowFunction {
        EventCountFeature() { super("event_count_10m"); }
        @Override double aggregate(Iterable<RiskJob.RiskInput> events) { return eventCount(events); }
    }
}
