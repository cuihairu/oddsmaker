package io.oddsmaker.control.service;

import io.oddsmaker.control.dto.RiskEventDto;import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.Map;

/**
 * 风控处置动作归档（ClickHouse）：
 * - risk_actions 记录每次处置（action/state），与 risk_events 通过 risk_event_id 关联，
 *   判定型处置携带 Decision 案件 id（risk_case_id）；非法判定流转归档 state=decision_rejected；
 * - risk_scores 不在此回写——B6「RiskScore 独立」：主体累计分由 risk-job 评估随事件产出
 *   （计划书 §5.2 每次评估落 risk_scores 行），动作侧回写单规则分会覆盖累计语义，已移除。
 * 归档为旁路能力：ClickHouse 不可用或写失败仅记录日志，不影响处置主链路。
 */
@Component
public class RiskActionRecorder {

    private static final Logger logger = LoggerFactory.getLogger(RiskActionRecorder.class);

    private static final String INSERT_ACTION =
            "INSERT INTO risk_actions (game_id, environment, ts, risk_event_id, risk_case_id, rule_id, "
                    + "subject_type, subject_id, severity, action, state, source, reason, detail) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

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
        } catch (Exception e) {
            logger.warn("risk_actions archive failed (non-fatal) for {}: {}", event.riskEventId, e.getMessage());
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
