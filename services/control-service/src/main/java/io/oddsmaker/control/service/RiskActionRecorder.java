package io.oddsmaker.control.service;

import io.oddsmaker.control.dto.RiskEventDto;import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.sql.Timestamp;
import java.util.Map;

/**
 * 风控处置动作归档（ClickHouse）：
 * - risk_actions 记录每次处置（action/state），与 risk_events 通过 risk_event_id 关联；
 * - risk_scores 更新主体最新风险分（ReplacingMergeTree 按 updated_at 保留最新）。
 * 归档为旁路能力：ClickHouse 不可用或写失败仅记录日志，不影响处置主链路。
 */
@Component
public class RiskActionRecorder {

    private static final Logger logger = LoggerFactory.getLogger(RiskActionRecorder.class);

    private static final String INSERT_ACTION =
            "INSERT INTO risk_actions (game_id, environment, ts, risk_event_id, risk_case_id, rule_id, "
                    + "subject_type, subject_id, severity, action, state, source, reason, detail) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

    private static final String INSERT_SCORE =
            "INSERT INTO risk_scores (game_id, environment, subject_type, subject_id, score, updated_at, reasons) "
                    + "VALUES (?,?,?,?,?,?,?)";

    private final ClickHouseClient client;

    public RiskActionRecorder(ClickHouseClient client) {
        this.client = client;
    }

    public void record(RiskEventDto event, String action, String state, String riskCaseId) {
        if (client == null || !client.isAvailable()) {
            return;
        }
        long ts = event.ts != null ? event.ts : System.currentTimeMillis();
        try {
            client.update(INSERT_ACTION,
                    event.gameId,
                    event.environment,
                    new Timestamp(ts),
                    nz(event.riskEventId),
                    nz(riskCaseId),
                    nz(event.ruleId),
                    nz(event.subjectType),
                    nz(event.subjectId),
                    nz(event.severity),
                    nz(action),
                    nz(state),
                    "system",
                    nz(event.reason),
                    event.evidence != null ? event.evidence : Map.of());
            updateSubjectScore(event, ts);
        } catch (Exception e) {
            logger.warn("risk_actions archive failed (non-fatal) for {}: {}", event.riskEventId, e.getMessage());
        }
    }

    private void updateSubjectScore(RiskEventDto event, long ts) {
        if (event.subjectType == null || event.subjectId == null || event.score == null) {
            return;
        }
        try {
            client.execute(conn -> {
                java.sql.PreparedStatement ps = conn.prepareStatement(INSERT_SCORE);
                ps.setString(1, event.gameId);
                ps.setString(2, event.environment);
                ps.setString(3, event.subjectType);
                ps.setString(4, event.subjectId);
                ps.setFloat(5, event.score);
                ps.setTimestamp(6, new Timestamp(ts));
                Array reasons = conn.createArrayOf("String",
                        new String[]{nz(event.ruleId), nz(event.action)});
                ps.setArray(7, reasons);
                return ps.executeUpdate();
            });
        } catch (Exception e) {
            logger.warn("risk_scores update failed (non-fatal): {}", e.getMessage());
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
