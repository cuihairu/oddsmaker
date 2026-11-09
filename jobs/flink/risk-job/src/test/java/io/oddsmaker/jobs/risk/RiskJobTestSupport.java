package io.oddsmaker.jobs.risk;

import io.oddsmaker.jobs.enrich.RawEvent;
import org.apache.flink.util.Collector;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * RiskJobTest 拆分共享静态 helper（LAY-011 自 RiskJobTest 抽出）：
 * fullEvent/sinkTo/input/flowInput/rules、RuleOverride 规则快照覆盖、
 * deadRules/patternSpec/SEQ3、RuleFetcher 单例反射三件套（RuleFetcherTest 亦引用）。
 */
final class RiskJobTestSupport {

    private RiskJobTestSupport() {
    }

    static RawEvent fullEvent() {
        RawEvent r = new RawEvent();
        r.game_id = "g";
        r.environment = "prod";
        r.event_id = "e1";
        r.event_name = "ad_reward_claim";
        r.event_type = "economy";
        r.user_id = "u1";
        r.device_id = "d1";
        r.client_ip = "1.2.3.4";
        r.ts_server = 2_000_000L;
        r.resource_amount = 3.5;
        r.flow_type = "sink";
        r.receipt_hash = "rh1";
        r.revenue_amount = 9.99;
        r.ad_format = "rewarded";
        return r;
    }

    /** Flink 1.19 Collector.close() 非默认实现，需匿名类补齐。 */
    static <T> Collector<T> sinkTo(List<T> out) {
        return new Collector<>() {
            @Override
            public void collect(T record) {
                out.add(record);
            }

            @Override
            public void close() {
            }
        };
    }

    static RiskJob.RiskInput input(String eventId, String userId) {
        RiskJob.RiskInput in = new RiskJob.RiskInput();
        in.gameId = "g";
        in.environment = "prod";
        in.eventId = eventId;
        in.eventName = "n_" + eventId;
        in.userId = userId;
        in.deviceId = "d1";
        in.ts = new Timestamp(1_000L);
        return in;
    }

    static RiskJob.RiskInput flowInput(String flowType, String amount) {
        RiskJob.RiskInput in = input(flowType, "u1");
        in.eventId = "e_" + flowType;
        in.eventName = "n_" + flowType;
        in.flowType = flowType;
        in.amount = new BigDecimal(amount);
        return in;
    }

    static Map<String, RuleConfig.RuleSpec> rules(String type, int threshold, String ruleId) {
        return Map.of(type, new RuleConfig.RuleSpec(ruleId, type, threshold, "ALERT", 55, "MEDIUM"));
    }

    /** 临时覆盖全局规则快照，关闭时恢复为空（回落 DEFAULTS）。 */
    static final class RuleOverride implements AutoCloseable {
        private RuleOverride() {
        }

        static RuleOverride set(Map<String, RuleConfig.RuleSpec> m) {
            RuleConfig.update(new RuleConfig(m));
            return new RuleOverride();
        }

        @Override
        public void close() {
            RuleConfig.update(new RuleConfig(Map.of()));
        }
    }

    static RiskJob.RuleSource deadRules() {
        return new RiskJob.RuleSource("http://127.0.0.1:1", "g", "", 999_999_999L);
    }

    static RuleConfig.RuleSpec patternSpec(String ruleId, List<String> sequence) {
        return new RuleConfig.RuleSpec(ruleId, "PATTERN", 300, "REVIEW", 85, "HIGH", sequence, 300);
    }

    static final List<String> SEQ3 = List.of("login", "purchase", "refund");

    static Object ruleFetcherInstance() throws Exception {
        Field f = RuleFetcher.class.getDeclaredField("instance");
        f.setAccessible(true);
        return ((java.util.concurrent.atomic.AtomicReference<?>) f.get(null)).get();
    }

    /** 停掉并清空 RuleFetcher 单例（若存在）。 */
    static void stopRuleFetcher() throws Exception {
        Object inst = ruleFetcherInstance();
        if (inst instanceof RuleFetcher rf) rf.stop();
        Field f = RuleFetcher.class.getDeclaredField("instance");
        f.setAccessible(true);
        ((java.util.concurrent.atomic.AtomicReference<?>) f.get(null)).set(null);
    }

    static void resetRuleFetcher() throws Exception {
        stopRuleFetcher();
    }

}
