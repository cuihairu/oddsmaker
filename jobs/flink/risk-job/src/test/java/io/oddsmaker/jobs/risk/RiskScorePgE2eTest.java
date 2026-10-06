package io.oddsmaker.jobs.risk;

import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.SinkFunction;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 计划书 §5.4 B6 段的真 PG/CH 端到端：接续 B5 链（金币事件 → risk_features → FEATURE 规则命中），
 * 经 trust 门槛（buildPipeline 同款 applyTrustGate）后分叉进两个 ClickHouse 出口——
 * risk_events（14 列事件流）与 risk_scores（RiskScoreFunction 主体累计分 + 规则触发明细 JSON）。
 * Kafka 出口不在本链（本地无 broker；JSON 契约由 toJson 单测钉住）。
 *
 * 门禁：-Drisk.pg.e2e=true 才运行（CI 无 PG 自跳过；本地配合容器跑，用完删容器）。
 * PG 默认 jdbc:postgresql://127.0.0.1:15434/oddsmaker（-Drisk.pg.e2e.url/user/pass 覆盖），
 * CH 默认 jdbc:clickhouse://127.0.0.1:18123/default（-Drisk.ch.e2e.url/user/pass 覆盖）。
 * 前置：PG 由 control 侧 RiskDecisionPgE2eTest 的 Flyway 全量迁移就绪；CH 表由
 * schema/sql/clickhouse/schema.sql 初始化（risk_events/risk_scores/risk_actions）。
 * 主体每次运行唯一，避免跨运行残留（risk_scores 主体累计按 keyBy 状态起算）。
 */
@DisplayName("B6 §5.4 真 PG/CH 端到端：FEATURE 命中 → trust 门槛 → CH risk_events + risk_scores")
@EnabledIfSystemProperty(named = "risk.pg.e2e", matches = "true")
class RiskScorePgE2eTest {

    static final String PG_URL = System.getProperty("risk.pg.e2e.url", "jdbc:postgresql://127.0.0.1:15434/oddsmaker");
    static final String PG_USER = System.getProperty("risk.pg.e2e.user", "oddsmaker");
    static final String PG_PASS = System.getProperty("risk.pg.e2e.pass", "oddsmaker");
    static final String CH_URL = System.getProperty("risk.ch.e2e.url", "jdbc:clickhouse://127.0.0.1:18123/default");
    static final String CH_USER = System.getProperty("risk.ch.e2e.user", "oddsmaker");
    static final String CH_PASS = System.getProperty("risk.ch.e2e.pass", "oddsmaker");

    /** 命中收集（静态队列，sink 实例须可序列化） */
    static final ConcurrentLinkedQueue<RiskJob.RiskHit> HITS = new ConcurrentLinkedQueue<>();

    static final class HitCollector implements SinkFunction<RiskJob.RiskHit> {
        @Override public void invoke(RiskJob.RiskHit value, Context context) { HITS.add(value); }
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
        i.trustLevel = "HIGH";   // 服务端事件，B4 契约回填 HIGH
        return i;
    }

    @Test
    @DisplayName("2×600k 金币 → PG 特征行 → FEATURE 命中 → CH risk_events 行 + risk_scores 累计分 85")
    void featureRuleToRiskEventsAndRiskScores() throws Exception {
        // 0. 前置：PG 迁移就绪（control 侧 e2e 先跑 Flyway）+ CH 风控表就绪
        try (Connection c = DriverManager.getConnection(PG_URL, PG_USER, PG_PASS);
             PreparedStatement q = c.prepareStatement("SELECT count(*) FROM risk_features");
             ResultSet rs = q.executeQuery()) {
            assertTrue(rs.next());
        }
        try (Connection c = DriverManager.getConnection(CH_URL, CH_USER, CH_PASS);
             PreparedStatement q = c.prepareStatement("SELECT count(*) FROM risk_scores");
             ResultSet rs = q.executeQuery()) {
            assertTrue(rs.next());
        }

        // 1. 注入 FEATURE 规则（阈值 500k，贡献分 85）
        RuleConfig.RuleSpec spec = RuleConfig.RuleSpec.featureSpec(
                "e2e-gold-1h", "FEATURE", "REVIEW", 85, "HIGH",
                List.of(new RuleConfig.FeatureCondition("SUBJECT", "gold_gain_1h", ">", 500_000)));
        RuleConfig.update(new RuleConfig(Map.of("FEATURE", spec)));
        HITS.clear();

        String subject = "e2e-score-" + Long.toHexString(System.nanoTime());
        long t = System.currentTimeMillis() - Duration.ofHours(5).toMillis();  // 全部落在过去，窗口按事件时间闭合
        List<RiskJob.RiskInput> goldEvents = List.of(
                gold(t, subject, 600_000),                                     // 超阈值金币事件 #1
                gold(t + Duration.ofMinutes(70).toMillis(), subject, 600_000)); // #2（下一窗）
        List<RiskJob.RiskInput> carriers = List.of(
                gold(t + Duration.ofMinutes(80).toMillis(), subject, 1),        // 评估载体 #1
                gold(t + Duration.ofMinutes(81).toMillis(), subject, 1),        // 评估载体 #2
                gold(t + Duration.ofMinutes(82).toMillis(), subject, 1));       // 评估载体 #3

        StreamExecutionEnvironment env = StreamExecutionEnvironment.createLocalEnvironment(1);
        env.setParallelism(1);
        env.getConfig().setAutoWatermarkInterval(0);   // 水位线由 TimedSource 显式推进
        DataStream<RiskJob.RiskInput> inputs =
                env.addSource(new RiskFeaturePgE2eTest.TimedSource(goldEvents, carriers, 1200));

        // 特征分支 → PG upsert（B5 段）
        DataStream<FeatureRow> featureRows = RiskJob.buildFeatureBranch(inputs);
        featureRows.addSink(RiskJob.featureJdbcSink(PG_URL, PG_USER, PG_PASS)).name("postgres-risk-features");

        // FEATURE 规则评估 → trust 门槛 → CH risk_events / risk_scores（B6 段，与 buildPipeline 同序）
        DataStream<RiskJob.RiskHit> featureRuleHits = inputs
                .keyBy(RiskJob::subjectWindowKey)
                .connect(featureRows.broadcast(FeatureRuleFunction.FEATURE_STATE))
                .process(new FeatureRuleFunction(new RiskJob.RuleSource("http://127.0.0.1:1", "DEFAULT", "", 60_000)))
                .returns(org.apache.flink.api.common.typeinfo.Types.POJO(RiskJob.RiskHit.class));

        DataStream<RiskJob.RiskHit> allHits = featureRuleHits
                .map(RiskJob::applyTrustGate)
                .returns(org.apache.flink.api.common.typeinfo.Types.POJO(RiskJob.RiskHit.class));
        allHits.addSink(new HitCollector()).name("test-hits");
        allHits.addSink(RiskJob.riskEventsJdbcSink(CH_URL, CH_USER, CH_PASS)).name("clickhouse-risk-events");

        DataStream<RiskJob.RiskScoreRow> scores = allHits
                .keyBy(RiskJob::scoreKey)
                .process(new RiskJob.RiskScoreFunction())
                .returns(org.apache.flink.api.common.typeinfo.Types.POJO(RiskJob.RiskScoreRow.class));
        scores.addSink(RiskJob.riskScoresJdbcSink(CH_URL, CH_USER, CH_PASS)).name("clickhouse-risk-scores");

        env.execute("b6-pg-ch-e2e");

        // 2. 断言一：命中 + trust 门槛后动作保持 REVIEW（HIGH 信任不过门槛）
        assertFalse(HITS.isEmpty(), "FEATURE 规则应命中");
        RiskJob.RiskHit hit = HITS.peek();
        assertEquals("FEATURE", hit.riskType);
        assertEquals("e2e-gold-1h", hit.ruleId);
        assertEquals("PLAYER", hit.subjectType);
        assertEquals(subject, hit.subjectId);
        assertEquals("REVIEW", hit.action);
        assertEquals("600000", hit.evidence.get("feature:gold_gain_1h"));

        // 3. 断言二：CH risk_events 落行（JdbcSink 500ms 批 flush，轮询等待）
        List<Map<String, Object>> evRows = awaitChRows(
                "SELECT rule_id, risk_type, severity, action, score FROM risk_events "
                        + "WHERE game_id='DEFAULT' AND environment='prod' AND subject_id='" + subject + "'");
        assertFalse(evRows.isEmpty(), "risk_events 应在 CH 落行");
        assertEquals("e2e-gold-1h", evRows.get(0).get("rule_id"));
        assertEquals("FEATURE", evRows.get(0).get("risk_type"));
        assertEquals("HIGH", evRows.get(0).get("severity"));
        assertEquals("REVIEW", evRows.get(0).get("action"));

        // 4. 断言三：CH risk_scores 落行（主体累计分=85，明细含规则贡献分 JSON）
        ScoreRow scoreRow = awaitScoreRow(subject);
        assertNotNull(scoreRow, "risk_scores 应在 20s 内落行（JdbcSink 批 flush）");
        assertEquals(85.0f, scoreRow.score(), 0.001f);
        assertTrue(scoreRow.reasons().contains("{\"rule_id\":\"e2e-gold-1h\",\"contribution\":85.0}"),
                "规则触发明细应含贡献分 JSON，实际=" + scoreRow.reasons());

        RuleConfig.update(new RuleConfig(Map.of()));
    }

    private record ScoreRow(float score, List<String> reasons) {}

    /** 轮询等待 risk_scores 行（ReplacingMergeTree 主体快照）；超时返回 null 供断言报根因 */
    private ScoreRow awaitScoreRow(String subject) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            try (Connection c = DriverManager.getConnection(CH_URL, CH_USER, CH_PASS);
                 PreparedStatement q = c.prepareStatement(
                         "SELECT score, reasons FROM risk_scores WHERE game_id='DEFAULT' AND environment='prod' "
                         + "AND subject_type='PLAYER' AND subject_id=?")) {
                q.setString(1, subject);
                try (ResultSet rs = q.executeQuery()) {
                    if (rs.next()) {
                        List<String> reasons = new ArrayList<>();
                        Object[] arr = (Object[]) rs.getArray(2).getArray();
                        for (Object o : arr) reasons.add(String.valueOf(o));
                        return new ScoreRow(rs.getFloat(1), reasons);
                    }
                }
            }
            Thread.sleep(500);
        }
        return null;
    }

    /** 轮询等待查询非空（CH 写为 JdbcSink 批 flush，execute 返回后可能仍有余量），超时返回空表 */
    private static List<Map<String, Object>> awaitChRows(String sql) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            try (Connection c = DriverManager.getConnection(CH_URL, CH_USER, CH_PASS);
                 PreparedStatement q = c.prepareStatement(sql);
                 ResultSet rs = q.executeQuery()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                int cols = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    for (int i = 1; i <= cols; i++) row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                    rows.add(row);
                }
                if (!rows.isEmpty()) return rows;
            }
            Thread.sleep(500);
        }
        return List.of();
    }
}
