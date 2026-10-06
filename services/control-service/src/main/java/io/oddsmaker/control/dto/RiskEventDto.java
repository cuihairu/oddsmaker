package io.oddsmaker.control.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * 风控事件 DTO，对齐 RiskJob.toJson 输出的 JSON 字段（snake_case）
 */
public class RiskEventDto {

    @JsonProperty("game_id")
    public String gameId;

    @JsonProperty("environment")
    public String environment;

    @JsonProperty("ts")
    public Long ts;

    @JsonProperty("risk_event_id")
    public String riskEventId;

    @JsonProperty("source_event_id")
    public String sourceEventId;

    @JsonProperty("rule_id")
    public String ruleId;

    @JsonProperty("risk_type")
    public String riskType;

    @JsonProperty("severity")
    public String severity;

    @JsonProperty("subject_type")
    public String subjectType;   // PLAYER / DEVICE

    @JsonProperty("subject_id")
    public String subjectId;

    @JsonProperty("score")
    public Float score;

    /**
     * 判定输入的信任档位（B6 §5.2，对齐 RiskJob.toJson 输出）：
     * 单事件=该事件 trust_level，窗口=全 HIGH 才 "HIGH"；BLOCK 级动作以此为门槛（fail-closed）。
     * 值由网关权威回填（B4 事件契约 v2）：client→LOW、server→HIGH、derived→COMPUTED，客户端不可自抬。
     */
    @JsonProperty("trust_level")
    public String trustLevel;

    @JsonProperty("cumulative_score")
    public Float cumulativeScore;  // B6 主体累计分契约位：累计分当前由 risk-job 评估落 risk_scores 表，事件 JSON 暂不携带该字段

    @JsonProperty("action")
    public String action;        // BLOCK / ALERT / WEBHOOK / REVIEW / THROTTLE

    @JsonProperty("reason")
    public String reason;

    @JsonProperty("evidence")
    public Map<String, String> evidence;
}
