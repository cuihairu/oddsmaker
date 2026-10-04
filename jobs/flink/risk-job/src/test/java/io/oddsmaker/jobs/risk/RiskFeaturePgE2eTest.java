package io.oddsmaker.jobs.risk;

import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.SinkFunction;
import org.apache.flink.streaming.api.functions.source.SourceFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 计划书 §5.4 B5 段的真 PG 端到端（复现法：本机一次性 postgres:16 容器 + bootRun Flyway 全量迁移，
 * 见 docs/operations/startup 与仓库记忆 local-pg-repro-for-control-service）：
 * 灌 2 条超阈值「金币获取」事件 → risk_features 出 1h 窗口行（真 PostgreSQL UPSERT）→ FEATURE 规则命中。
 * risk_scores/Decision/Action 段属 B6，不在本链。
 *
 * 门禁：-Drisk.pg.e2e=true 才运行（CI 无 PG 自跳过；本地配合容器跑，用完删容器）。
 * 可用 -Drisk.pg.e2e.url=... 覆盖默认 jdbc:postgresql://127.0.0.1:15434/oddsmaker。
 * game_id 取 'DEFAULT'（V0.4.1 种子行，risk_features.game_id 外键指向 games）。
 */
@DisplayName("B5 §5.4 真 PG 端到端：超阈值金币事件 → risk_features 1h 行 → FEATURE 规则命中")
@EnabledIfSystemProperty(named = "risk.pg.e2e", matches = "true")
class RiskFeaturePgE2eTest {

    static final String URL = System.getProperty("risk.pg.e2e.url", "jdbc:postgresql://127.0.0.1:15434/oddsmaker");
    static final String USER = System.getProperty("risk.pg.e2e.user", "oddsmaker");
    static final String PASS = System.getProperty("risk.pg.e2e.pass", "oddsmaker");

    /** 命中收集（静态队列，sink 实例须可序列化） */
    static final ConcurrentLinkedQueue<RiskJob.RiskHit> HITS = new ConcurrentLinkedQueue<>();

    static final class HitCollector implements SinkFunction<RiskJob.RiskHit> {
        @Override public void invoke(RiskJob.RiskHit value, Context context) { HITS.add(value); }
    }

    /**
     * 事件时序源：gold1(T) → gold2(T+70m) + 显式水位线（周期水位线在 legacy source 的 run() 里
     * 走同线程 timer，sleep 期间发不出来，必须 collectWithTimestamp + emitWatermark 手工推进）
     * → 停顿让特征行广播就位 → 载体梯子（3 条，间隔 1.2s）携带规则评估。前两条即验收口径的
     * 「2 条超阈值金币事件」；本地执行无 checkpoint 时跨 task partial buffer 无周期 flush，
     * 广播行到达存在秒级抖动，单载体会竞态漏判，梯子拉长观测窗保证确定性。
     */
    @SuppressWarnings("deprecation")
    static final class TimedSource implements SourceFunction<RiskJob.RiskInput> {
        private final List<RiskJob.RiskInput> goldEvents;
        private final List<RiskJob.RiskInput> carriers;
        private final long pauseMs;

        TimedSource(List<RiskJob.RiskInput> goldEvents, List<RiskJob.RiskInput> carriers, long pauseMs) {
            this.goldEvents = goldEvents;
            this.carriers = carriers;
            this.pauseMs = pauseMs;
        }

        @Override public void run(SourceContext<RiskJob.RiskInput> ctx) throws Exception {
            for (RiskJob.RiskInput e : goldEvents) {
                ctx.collectWithTimestamp(e, e.ts.getTime());
            }
            ctx.emitWatermark(new org.apache.flink.streaming.api.watermark.Watermark(
                    goldEvents.get(goldEvents.size() - 1).ts.getTime()));
            Thread.sleep(pauseMs);
            for (RiskJob.RiskInput c : carriers) {
                ctx.collectWithTimestamp(c, c.ts.getTime());
                Thread.sleep(pauseMs);
            }
        }

        @Override public void cancel() {}
    }

    private static RiskJob.RiskInput gold(long tsMs, String userId, double amount) {
        RiskJob.RiskInput i = new RiskJob.RiskInput();
        i.gameId = "DEFAULT";
        i.environment = "prod";
        i.eventId = "e2e-" + tsMs + "-" + userId;
        i.eventName = "resource:gold:gain";
        i.userId = userId;
        i.deviceId = "e2e-device";
        i.clientIp = "10.77.0.1";
        i.ts = new Timestamp(tsMs);
        i.amount = java.math.BigDecimal.valueOf(amount);
        i.flowType = "source";
        i.resourceId = "gold";
        return i;
    }

    @Test
    @DisplayName("2 条 600k 金币事件 → 真 PG risk_features 1h 窗口行（值=600000）→ FEATURE 规则命中")
    void goldGainFeatureToRuleHit() throws Exception {
        // 0. 前置：库与表就绪（bootRun Flyway 已跑 V0.9.18）
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement q = c.prepareStatement("SELECT count(*) FROM risk_features")) {
            try (ResultSet rs = q.executeQuery()) {
                assertTrue(rs.next());
            }
        }

        // 1. 注入 FEATURE 规则（阈值 500k；HTTP 拉取解析另有 RuleFetcherTest 钉住）
        RuleConfig.RuleSpec spec = RuleConfig.RuleSpec.featureSpec(
                "e2e-gold-1h", "FEATURE", "REVIEW", 85, "HIGH",
                List.of(new RuleConfig.FeatureCondition("SUBJECT", "gold_gain_1h", ">", 500_000)));
        RuleConfig.update(new RuleConfig(Map.of("FEATURE", spec)));
        HITS.clear();

        long t = System.currentTimeMillis() - Duration.ofHours(5).toMillis();  // 全部落在过去，窗口按事件时间闭合
        List<RiskJob.RiskInput> goldEvents = List.of(
                gold(t, "e2e-player", 600_000),                          // 超阈值金币事件 #1
                gold(t + Duration.ofMinutes(70).toMillis(), "e2e-player", 600_000)); // #2（下一窗）
        List<RiskJob.RiskInput> carriers = List.of(
                gold(t + Duration.ofMinutes(80).toMillis(), "e2e-player", 1),        // 评估载体 #1
                gold(t + Duration.ofMinutes(81).toMillis(), "e2e-player", 1),        // 评估载体 #2
                gold(t + Duration.ofMinutes(82).toMillis(), "e2e-player", 1));       // 评估载体 #3

        // 探针：轮询 PG 记录特征行首次可见时刻（区分「窗口未触发」与「广播未送达」两类失败）
        java.util.concurrent.atomic.AtomicLong firstRowSeenAt = new java.util.concurrent.atomic.AtomicLong(0);
        Thread probe = new Thread(() -> {
            try (Connection c = DriverManager.getConnection(URL, USER, PASS);
                 PreparedStatement q = c.prepareStatement(
                         "SELECT count(*) FROM risk_features WHERE game_id='DEFAULT' AND environment='prod' "
                         + "AND scope_key='PLAYER:e2e-player' AND feature_name='gold_gain_1h' AND value = 600000")) {
                while (firstRowSeenAt.get() == 0 && !Thread.currentThread().isInterrupted()) {
                    try (ResultSet rs = q.executeQuery()) {
                        if (rs.next() && rs.getLong(1) > 0) {
                            firstRowSeenAt.set(System.currentTimeMillis());
                        }
                    }
                    Thread.sleep(200);
                }
            } catch (Exception ignored) {
                // 探针失败不影响主断言（PG 断言在 execute 后另行核查）
            }
        });
        probe.setDaemon(true);
        probe.start();

        StreamExecutionEnvironment env = StreamExecutionEnvironment.createLocalEnvironment(1);
        env.setParallelism(1);
        // 关自动水位线（200ms 周期 + 自动上下文会吞手工 emitWatermark）：改由 TimedSource 显式发
        env.getConfig().setAutoWatermarkInterval(0);
        long testStart = System.currentTimeMillis();
        DataStream<RiskJob.RiskInput> inputs = env.addSource(new TimedSource(goldEvents, carriers, 1200));

        DataStream<FeatureRow> featureRows = RiskJob.buildFeatureBranch(inputs);
        featureRows.addSink(RiskJob.featureJdbcSink(URL, USER, PASS)).name("postgres-risk-features");
        inputs.keyBy(RiskJob::subjectWindowKey)
                .connect(featureRows.broadcast(FeatureRuleFunction.FEATURE_STATE))
                .process(new FeatureRuleFunction(new RiskJob.RuleSource("http://127.0.0.1:1", "DEFAULT", "", 60_000)))
                .returns(org.apache.flink.api.common.typeinfo.Types.POJO(RiskJob.RiskHit.class))
                .addSink(new HitCollector());
        env.execute("b5-pg-e2e");

        // 2. 断言一：真 PG 落行——1h 窗口行值=600000、scope/窗界对齐
        List<long[]> windows = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement q = c.prepareStatement(
                     "SELECT window_start, window_end, value FROM risk_features "
                     + "WHERE game_id='DEFAULT' AND environment='prod' AND scope_key='PLAYER:e2e-player' "
                     + "AND feature_name='gold_gain_1h' AND value = 600000")) {
            try (ResultSet rs = q.executeQuery()) {
                while (rs.next()) {
                    windows.add(new long[]{rs.getTimestamp(1).getTime(), rs.getTimestamp(2).getTime(),
                            (long) rs.getDouble(3)});
                }
            }
        }
        assertFalse(windows.isEmpty(), "risk_features 应有 gold_gain_1h=600000 的 1h 窗口行");
        for (long[] w : windows) {
            assertEquals(Duration.ofHours(1).toMillis(), w[1] - w[0], "窗口跨度须为 1h");
            assertEquals(600_000L, w[2]);
        }

        // 3. 断言二：规则命中——载体事件评估特征快照触发 FEATURE 规则
        // 失败时用探针时刻区分两类根因：PG 无行=窗口未在运行期触发；PG 有行=特征行已产出但广播迟到
        String probeMsg = firstRowSeenAt.get() == 0 ? "从未落库"
                : "落库于 t+" + (firstRowSeenAt.get() - testStart) + "ms（载体梯子覆盖约 6s）";
        assertFalse(HITS.isEmpty(), "FEATURE 规则应命中，特征行" + probeMsg);
        RiskJob.RiskHit hit = HITS.peek();
        assertEquals("FEATURE", hit.riskType);
        assertEquals("e2e-gold-1h", hit.ruleId);
        assertEquals("PLAYER", hit.subjectType);
        assertEquals("e2e-player", hit.subjectId);
        assertEquals("600000", hit.evidence.get("feature:gold_gain_1h"));

        RuleConfig.update(new RuleConfig(Map.of()));
    }
}
