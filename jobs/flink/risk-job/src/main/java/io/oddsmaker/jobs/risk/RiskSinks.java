package io.oddsmaker.jobs.risk;

import org.apache.flink.connector.jdbc.JdbcConnectionOptions;
import org.apache.flink.connector.jdbc.JdbcExecutionOptions;
import org.apache.flink.connector.jdbc.JdbcSink;
import org.apache.flink.streaming.api.functions.sink.SinkFunction;

import java.sql.SQLException;

/**
 * RiskJob 落库出口（拆分 LAY-001）：risk_features / feature_store（PostgreSQL control 库）、
 * risk_events / risk_scores（ClickHouse）。SQL 常量、sink 装配与行绑定集中于此。
 * 各 sink 供 buildPipeline 与 §5.4 端到端共用。
 */
final class RiskSinks {

    private RiskSinks() {}

    /** risk_features upsert：同窗口重算（迟到事件修正）按唯一键覆盖 value/as_of，幂等可重放 */
    static final String FEATURE_UPSERT_SQL =
            "INSERT INTO risk_features (game_id, environment, scope_key, feature_name, window_start, window_end, value, as_of) "
            + "VALUES (?,?,?,?,?,?,?,?) "
            + "ON CONFLICT (game_id, environment, scope_key, feature_name, window_start, window_end) "
            + "DO UPDATE SET value = EXCLUDED.value, as_of = EXCLUDED.as_of";

    /** risk_features 写入 sink（PostgreSQL control 库） */
    static SinkFunction<FeatureRow> featureJdbcSink(String pgUrl, String pgUser, String pgPass) {
        return JdbcSink.sink(
                FEATURE_UPSERT_SQL,
                RiskSinks::bindFeatureRow,
                JdbcExecutionOptions.builder().withBatchIntervalMs(500).withBatchSize(500).withMaxRetries(3).build(),
                new JdbcConnectionOptions.JdbcConnectionOptionsBuilder()
                        .withUrl(pgUrl)
                        .withDriverName("org.postgresql.Driver")
                        .withUsername(pgUser)
                        .withPassword(pgPass)
                        .build());
    }

    /**
     * feature_store 摘要 upsert（B10 双写，同连接参数同批次语义）。
     * 长格式行各自携带单特征 JSON 片段写入，冲突时 jsonb 合并——一个 scope×窗口
     * 的全部特征最终聚在同一行的 features 串里；同名键迟到重算取右值覆盖。
     */
    static final String FEATURE_STORE_UPSERT_SQL =
            "INSERT INTO feature_store (game_id, environment, scope_key, window_start, window_end, features, as_of, created_at) "
            + "VALUES (?,?,?,?,?,?,?,now()) "
            + "ON CONFLICT (game_id, environment, scope_key, window_start, window_end) "
            + "DO UPDATE SET features = (feature_store.features::jsonb || EXCLUDED.features::jsonb)::text, "
            + "as_of = EXCLUDED.as_of";

    /** feature_store 写入 sink（PostgreSQL control 库） */
    static SinkFunction<FeatureRow> featureStoreJdbcSink(String pgUrl, String pgUser, String pgPass) {
        return JdbcSink.sink(
                FEATURE_STORE_UPSERT_SQL,
                RiskSinks::bindFeatureStoreRow,
                JdbcExecutionOptions.builder().withBatchIntervalMs(500).withBatchSize(500).withMaxRetries(3).build(),
                new JdbcConnectionOptions.JdbcConnectionOptionsBuilder()
                        .withUrl(pgUrl)
                        .withDriverName("org.postgresql.Driver")
                        .withUsername(pgUser)
                        .withPassword(pgPass)
                        .build());
    }

    /** 单特征 JSON 片段（feature_store.features 合并单元）。featureName 恒为代码内特征常量，无注入面。 */
    static String featureStoreFragment(FeatureRow r) {
        return "{\"" + r.featureName + "\":" + r.value + "}";
    }

    /** feature_store 表写入绑定（7 参 + now()）。 */
    static void bindFeatureStoreRow(java.sql.PreparedStatement ps, FeatureRow r) throws SQLException {
        ps.setString(1, r.gameId);
        ps.setString(2, r.environment);
        ps.setString(3, r.scopeKey);
        ps.setTimestamp(4, new java.sql.Timestamp(r.windowStartMs));
        ps.setTimestamp(5, new java.sql.Timestamp(r.windowEndMs));
        ps.setString(6, featureStoreFragment(r));
        ps.setTimestamp(7, new java.sql.Timestamp(r.asOfMs));
    }

    /** risk_events 写入 sink（ClickHouse 风控事件流，14 列）——buildPipeline 与 §5.4 端到端共用 */
    static SinkFunction<RiskJob.RiskHit> riskEventsJdbcSink(String chUrl, String chUser, String chPass) {
        return JdbcSink.sink(
                "INSERT INTO risk_events (game_id, environment, ts, risk_event_id, source_event_id, rule_id, risk_type, severity, subject_type, subject_id, score, action, reason, evidence) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                RiskSinks::bindRiskHit,
                JdbcExecutionOptions.builder().withBatchIntervalMs(500).withBatchSize(500).withMaxRetries(3).build(),
                chJdbcOptions(chUrl, chUser, chPass));
    }

    /** risk_scores 写入 sink（ClickHouse 主体累计分评估产出，7 列）——buildPipeline 与 §5.4 端到端共用 */
    static SinkFunction<RiskJob.RiskScoreRow> riskScoresJdbcSink(String chUrl, String chUser, String chPass) {
        return JdbcSink.sink(
                "INSERT INTO risk_scores (game_id, environment, subject_type, subject_id, score, updated_at, reasons) VALUES (?,?,?,?,?,?,?)",
                RiskSinks::bindRiskScore,
                JdbcExecutionOptions.builder().withBatchIntervalMs(500).withBatchSize(500).withMaxRetries(3).build(),
                chJdbcOptions(chUrl, chUser, chPass));
    }

    /** ClickHouse JDBC 连接参数（risk_events / risk_scores 两个出口共用） */
    private static JdbcConnectionOptions chJdbcOptions(String chUrl, String chUser, String chPass) {
        return new JdbcConnectionOptions.JdbcConnectionOptionsBuilder()
                .withUrl(chUrl)
                .withDriverName("com.clickhouse.jdbc.ClickHouseDriver")
                .withUsername(chUser)
                .withPassword(chPass)
                .build();
    }

    /** risk_features 表写入绑定（8 列）。 */
    static void bindFeatureRow(java.sql.PreparedStatement ps, FeatureRow r) throws SQLException {
        ps.setString(1, r.gameId);
        ps.setString(2, r.environment);
        ps.setString(3, r.scopeKey);
        ps.setString(4, r.featureName);
        ps.setTimestamp(5, new java.sql.Timestamp(r.windowStartMs));
        ps.setTimestamp(6, new java.sql.Timestamp(r.windowEndMs));
        ps.setDouble(7, r.value);
        ps.setTimestamp(8, new java.sql.Timestamp(r.asOfMs));
    }

    /** risk_events 表写入绑定（14 列）。 */
    static void bindRiskHit(java.sql.PreparedStatement ps, RiskJob.RiskHit h) throws SQLException {
        ps.setString(1, h.gameId);
        ps.setString(2, h.environment);
        ps.setTimestamp(3, h.ts);
        ps.setString(4, h.riskEventId);
        ps.setString(5, h.sourceEventId);
        ps.setString(6, h.ruleId);
        ps.setString(7, h.riskType);
        ps.setString(8, h.severity);
        ps.setString(9, h.subjectType);
        ps.setString(10, h.subjectId);
        ps.setFloat(11, h.score);
        ps.setString(12, h.action);
        ps.setString(13, h.reason);
        ps.setObject(14, h.evidence);
    }

    /** risk_scores 表写入绑定（7 列；reasons 为规则触发明细 JSON 数组）。 */
    static void bindRiskScore(java.sql.PreparedStatement ps, RiskJob.RiskScoreRow r) throws SQLException {
        ps.setString(1, r.gameId);
        ps.setString(2, r.environment);
        ps.setString(3, r.subjectType);
        ps.setString(4, r.subjectId);
        ps.setFloat(5, r.score);
        ps.setTimestamp(6, r.updatedTs);
        ps.setArray(7, ps.getConnection().createArrayOf("String", r.reasons.toArray(new String[0])));
    }
}
