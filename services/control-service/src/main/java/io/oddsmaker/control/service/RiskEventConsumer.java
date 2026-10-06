package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.dto.RiskEventDto;
import io.oddsmaker.control.jpa.AuditLogEntity;
import io.oddsmaker.control.jpa.BlockListEntity;
import io.oddsmaker.control.jpa.IdentityLinkEntity;
import io.oddsmaker.control.jpa.IdentityLinkRepo;
import io.oddsmaker.control.jpa.RiskCaseEntity;
import io.oddsmaker.control.jpa.RiskCaseRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 风控事件消费者
 * 消费 Flink RiskJob 产出到 oddsmaker.risk_events topic 的 JSON 消息，
 * 按 action 分发处置：BLOCK→封禁名单、REVIEW→审核队列、WEBHOOK→通知、
 * THROTTLE/MARK→审计+指令下发；block/review/mark/throttle 均通过 risk_action
 * webhook 输出到游戏服执行。
 *
 * B6 Decision→Action（计划书 §5.2）：
 * <ul>
 *   <li>判定型动作（REVIEW/ALERT/MARK/THROTTLE/BLOCK）先在主体未结案案件上按
 *       {@link RiskCaseEntity#canTransition} 状态机落 status（Decision 决定动作，不反推），
 *       非法流转直接拒绝——动作执行只发生在 Decision 之后；</li>
 *   <li>BLOCK 级动作要求输入事件 trust_level=HIGH（§4.3），非 HIGH fail-closed 降级 REVIEW
 *       并打 [trust_gate] 标记，与 risk-job 侧门槛同语义双保险。</li>
 * </ul>
 */
@Component
public class RiskEventConsumer {

    private static final Logger logger = LoggerFactory.getLogger(RiskEventConsumer.class);

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BlockListService blockListService;

    @Autowired
    private WebhookService webhookService;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private IdentityLinkRepo identityLinkRepo;

    @Autowired
    private RiskCaseRepo riskCaseRepo;

    @Autowired
    private ReviewQueueService reviewQueueService;

    @Autowired
    private RiskActionRecorder riskActionRecorder;

    /** 身份关联扩散封禁开关（默认关：共享设备可能关联大量玩家，灰度后再开）。 */
    @Value("${oddsmaker.risk.identity-extend:false}")
    private boolean identityExtend;

    @KafkaListener(topics = "oddsmaker.risk_events")
    public void onRiskEvent(String message) {
        RiskEventDto event;
        try {
            event = objectMapper.readValue(message, RiskEventDto.class);
        } catch (Exception e) {
            logger.error("Failed to deserialize risk event: {}", e.getMessage());
            return;
        }

        if (event.action == null || event.action.isBlank()) {
            logger.warn("Risk event {} has no action, skipping", event.riskEventId);
            return;
        }

        // B6 §4.3：BLOCK 级动作要求输入事件 trust_level=HIGH（服务端事件驱动），
        // fail-closed——trust_level 缺失/非 HIGH 一律降级复核，与 risk-job 侧 gateAction 同语义双保险
        if ("BLOCK".equalsIgnoreCase(event.action) && !"HIGH".equals(event.trustLevel)) {
            logger.warn("BLOCK downgraded to REVIEW for {}:{} (trust_level={}): {}",
                event.subjectType, event.subjectId, event.trustLevel, event.riskEventId);
            event.action = "REVIEW";
            event.reason = event.reason == null || event.reason.isBlank()
                ? "[trust_gate]" : "[trust_gate] " + event.reason;
        }

        logger.info("Processing risk event: action={}, ruleId={}, subject={}:{}, score={}",
            event.action, event.ruleId, event.subjectType, event.subjectId, event.score);

        try {
            // Decision 先于 Action（§5.2）：判定型动作先在主体案件上按状态机落 status，
            // 非法流转（回退/同层改判/终态复活）直接拒绝，不执行任何动作
            RiskCaseEntity.DecisionStatus decision = decisionOf(event.action);
            RiskCaseEntity riskCase = null;
            if (decision != null) {
                riskCase = decide(event, decision);
                if (riskCase == null) {
                    riskActionRecorder.record(event, event.action.toLowerCase(), "decision_rejected", null);
                    return;
                }
            }
            String caseId = riskCase == null ? null : riskCase.id;

            switch (event.action.toUpperCase()) {
                case "BLOCK":
                    handleBlock(event);
                    notifyGameServer(event, outcome("block", "blocked"));
                    riskActionRecorder.record(event, "block", "blocked", caseId);
                    break;
                case "WEBHOOK":
                    handleWebhook(event);
                    riskActionRecorder.record(event, "webhook", "notified", null);
                    break;
                case "REVIEW":
                    handleReview(event, riskCase);
                    break;
                case "THROTTLE":
                    handleAuditOnly(event);
                    notifyGameServer(event, outcome("throttle", "throttled"));
                    riskActionRecorder.record(event, "throttle", "throttled", caseId);
                    break;
                case "MARK":
                    handleAuditOnly(event);
                    notifyGameServer(event, outcome("mark", "marked"));
                    riskActionRecorder.record(event, "mark", "marked", caseId);
                    break;
                case "ALERT":
                    handleAuditOnly(event);
                    riskActionRecorder.record(event, "alert", "logged", caseId);
                    break;
                default:
                    logger.warn("Unknown risk action: {}, logging as SECURITY_ALERT", event.action);
                    handleAuditOnly(event);
                    riskActionRecorder.record(event, event.action.toLowerCase(), "logged", null);
                    break;
            }
        } catch (Exception e) {
            logger.error("Failed to handle risk event {}: {}", event.riskEventId, e.getMessage(), e);
        }
    }

    /** 判定型动作 → DecisionStatus；WEBHOOK/未知动作不构成判定（不建案、不落状态） */
    private static RiskCaseEntity.DecisionStatus decisionOf(String action) {
        if (action == null) return null;
        return switch (action.toUpperCase()) {
            case "REVIEW" -> RiskCaseEntity.DecisionStatus.REVIEW;
            case "ALERT" -> RiskCaseEntity.DecisionStatus.ALERT;
            case "MARK" -> RiskCaseEntity.DecisionStatus.MARK;
            case "THROTTLE" -> RiskCaseEntity.DecisionStatus.THROTTLE;
            case "BLOCK" -> RiskCaseEntity.DecisionStatus.BLOCK;
            default -> null;
        };
    }

    /** 判定 → 处置动作（Decision 决定动作，不反推）：五个判定档与 ActionType 同名一一对应 */
    private static RiskCaseEntity.ActionType actionOf(RiskCaseEntity.DecisionStatus decision) {
        return RiskCaseEntity.ActionType.valueOf(decision.name());
    }

    /**
     * Decision 落点（§5.2「动作执行只发生在 Decision 之后」）：
     * 判定型事件先在主体未结案案件上按状态机前向流转（严格升层，回退/同层改判非法），
     * 无未结案案件则新建 OPEN 案件再判定；非法流转返回 null，调用方不执行动作。
     */
    private RiskCaseEntity decide(RiskEventDto event, RiskCaseEntity.DecisionStatus decision) {
        RiskCaseEntity riskCase = findOpenCase(event);
        if (riskCase == null) {
            riskCase = newCase(event);
        }
        if (!RiskCaseEntity.canTransition(riskCase.status, decision)) {
            logger.warn("Illegal decision transition {} -> {} for subject {}:{} (event {}), action rejected",
                riskCase.status, decision, event.subjectType, event.subjectId, event.riskEventId);
            return null;
        }
        riskCase.transitionTo(decision);
        riskCase.actionTaken = actionOf(decision);
        RiskCaseEntity saved = riskCaseRepo.save(riskCase);
        return saved != null ? saved : riskCase;
    }

    /** 主体未结案案件（状态非 RESOLVED，最新在前）；无则 null → 新建 */
    private RiskCaseEntity findOpenCase(RiskEventDto event) {
        List<RiskCaseEntity> found = riskCaseRepo.findUnresolvedBySubject(
            event.gameId, event.environment, targetTypeOf(event), event.subjectId,
            RiskCaseEntity.DecisionStatus.RESOLVED);
        return found == null || found.isEmpty() ? null : found.get(0);
    }

    /** 新建案件：id/编号/触发信息/等级齐备，status=OPEN 等待判定 */
    private RiskCaseEntity newCase(RiskEventDto event) {
        RiskCaseEntity riskCase = new RiskCaseEntity();
        riskCase.id = "rc_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        riskCase.gameId = event.gameId;
        riskCase.environmentId = event.environment;
        riskCase.riskRuleId = event.ruleId;
        riskCase.caseNumber = "CASE_" + System.currentTimeMillis();
        riskCase.targetType = targetTypeOf(event);
        riskCase.targetId = event.subjectId;
        riskCase.triggerEventId = event.sourceEventId;
        riskCase.actionDescription = event.reason;
        riskCase.riskLevel = parseRiskLevel(event.severity);
        riskCase.executionStatus = RiskCaseEntity.ExecutionStatus.PENDING;
        return riskCase;
    }

    /** subject_type → targetType 映射（PLAYER→player_id、DEVICE→device_id，null→unknown） */
    private static String targetTypeOf(RiskEventDto event) {
        return switch (event.subjectType != null ? event.subjectType.toUpperCase() : "") {
            case "PLAYER" -> "player_id";
            case "DEVICE" -> "device_id";
            default -> event.subjectType != null ? event.subjectType.toLowerCase() : "unknown";
        };
    }

    /** 构造下发游戏服的处置结果说明 */
    private Map<String, Object> outcome(String action, String state) {
        Map<String, Object> m = new HashMap<>();
        m.put("action", action);
        m.put("state", state);
        return m;
    }

    /**
     * 风控动作闭环输出：将处置结果（block/review/mark/throttle）通过 webhook 通知游戏服，
     * 游戏服据此执行踢线、限制功能、打标或降频上报。
     */
    private void notifyGameServer(RiskEventDto event, Map<String, Object> extra) {
        try {
            Map<String, Object> payload = buildWebhookPayload(event);
            payload.put("event_type", "risk_action");
            payload.putAll(extra);
            webhookService.sendCustomWebhook(event.gameId, "risk_action", payload);
            logger.info("risk_action webhook sent: action={}, subject={}:{}",
                extra.get("action"), event.subjectType, event.subjectId);
        } catch (Exception e) {
            // 通知失败不阻断本地处置
            logger.warn("risk_action webhook failed (non-fatal): {}", e.getMessage());
        }
    }

    /**
     * REVIEW 动作：案件已由 decide() 落定判定（Decision 先于 Action），
     * 此处只做 risk_case webhook、入审核队列、审计与通知。
     */
    private void handleReview(RiskEventDto event, RiskCaseEntity riskCase) {
        // 风险案例创建派发 risk_case webhook（按环境与风险等级过滤）
        try {
            webhookService.sendRiskCaseWebhook(event.gameId, event.environment, riskCase);
        } catch (Exception e) {
            logger.warn("risk_case webhook failed (non-fatal): {}", e.getMessage());
        }

        reviewQueueService.addToQueue(riskCase,
            riskCase.riskLevel == RiskCaseEntity.RiskLevel.CRITICAL ? 1 : 2,
            "risk_automation", "fraud");
        handleAuditOnly(event);
        notifyGameServer(event, outcome("review", "queued"));
        riskActionRecorder.record(event, "review", "queued", riskCase.id);
    }

    private RiskCaseEntity.RiskLevel parseRiskLevel(String severity) {
        if (severity == null) return RiskCaseEntity.RiskLevel.MEDIUM;
        try {
            return RiskCaseEntity.RiskLevel.valueOf(severity.toUpperCase());
        } catch (IllegalArgumentException e) {
            return RiskCaseEntity.RiskLevel.MEDIUM;
        }
    }

    // ========== 处置方法 ==========

    private void handleBlock(RiskEventDto event) {
        // subject_type → targetType 映射
        String targetType = targetTypeOf(event);

        // severity → 时长推导
        boolean isPermanent = false;
        Integer durationMinutes = null;
        if (event.severity != null) {
            switch (event.severity.toUpperCase()) {
                case "CRITICAL":
                    isPermanent = true;
                    break;
                case "HIGH":
                    durationMinutes = 1440;   // 1 day
                    break;
                case "MEDIUM":
                    durationMinutes = 240;     // 4 hours
                    break;
                case "LOW":
                    durationMinutes = 60;      // 1 hour
                    break;
                default:
                    durationMinutes = 1440;
                    break;
            }
        }

        blockListService.addBlock(
            event.gameId,
            event.environment,
            targetType,
            event.subjectId,
            event.reason != null ? event.reason : "Risk automation: " + event.riskType,
            "fraud",
            BlockListEntity.BlockType.HARD,
            isPermanent,
            durationMinutes,
            "risk-automation",
            null,
            event.reason
        );

        logger.info("BLOCK applied: {}={} in game={}, permanent={}, duration={}min",
            targetType, event.subjectId, event.gameId, isPermanent, durationMinutes);

        // 身份关联扩散封禁（默认关，限深度1/上限10/失败不阻断）
        extendBlockByIdentity(event, targetType, event.subjectId, isPermanent, durationMinutes);
    }

    /**
     * 身份关联扩散：当主 subject 是 DEVICE 且开关开启时，经 identity_links 把同身份的
     * player_id/user_id 一并封禁（共享设备 → 关联账号）。默认关闭，灰度后再开。
     */
    private void extendBlockByIdentity(RiskEventDto event, String mainTargetType, String mainSubjectId,
                                       boolean isPermanent, Integer durationMinutes) {
        if (!identityExtend || !"device_id".equals(mainTargetType) || mainSubjectId == null) return;
        try {
            int extended = 0;
            for (IdentityLinkEntity link : identityLinkRepo.findByTypeAndId("device_id", mainSubjectId)) {
                if (extended >= 10) break;  // 单次扩散上限，防爆炸
                for (IdentityLinkEntity il : identityLinkRepo.findByIdentityId(link.identityId)) {
                    if (extended >= 10) break;
                    if ("player_id".equals(il.linkedIdentityType) || "user_id".equals(il.linkedIdentityType)) {
                        blockListService.addBlock(
                            event.gameId, event.environment, il.linkedIdentityType, il.linkedId,
                            "identity-linked from " + mainTargetType + "=" + mainSubjectId,
                            "fraud", BlockListEntity.BlockType.HARD,
                            isPermanent, durationMinutes, "risk-automation", null, event.reason);
                        extended++;
                    }
                }
            }
            if (extended > 0) {
                logger.info("Identity-extended BLOCK: {} linked targets from {}={}", extended, mainTargetType, mainSubjectId);
            }
        } catch (Exception e) {
            logger.warn("Identity extend block failed (non-fatal): {}", e.getMessage());
        }
    }

    private void handleWebhook(RiskEventDto event) {
        Map<String, Object> payload = buildWebhookPayload(event);
        webhookService.sendCustomWebhook(event.gameId, "risk_event", payload);
        logger.info("WEBHOOK sent for risk event {}", event.riskEventId);
    }

    private void handleAuditOnly(RiskEventDto event) {
        auditLogService.log(
            AuditLogEntity.AuditAction.SECURITY_ALERT,
            "risk_event",
            event.riskEventId,
            event.riskType != null ? event.riskType : "unknown",
            event.reason,
            AuditLogEntity.AuditResult.SUCCESS,
            "risk-automation",
            null,
            null,
            null,
            null,
            Map.of(
                "gameId", event.gameId != null ? event.gameId : "",
                "environment", event.environment != null ? event.environment : "",
                // action 的 null 防御不可达：onRiskEvent 入口已拦截 action null/blank 才进 switch 分发，
                // 到达 handleAuditOnly 时 action 必非 null（同款三元其余字段可达，此处等价改写删除 null 侧）
                "action", event.action,
                "subjectType", event.subjectType != null ? event.subjectType : "",
                "subjectId", event.subjectId != null ? event.subjectId : "",
                "ruleId", event.ruleId != null ? event.ruleId : "",
                "score", String.valueOf(event.score)
            )
        );

        logger.info("AUDIT logged for risk event {} (action={})", event.riskEventId, event.action);
    }

    // ========== 辅助方法 ==========

    private Map<String, Object> buildWebhookPayload(RiskEventDto event) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("event_type", "risk_event");
        payload.put("game_id", event.gameId);
        payload.put("environment", event.environment);
        payload.put("ts", event.ts);
        payload.put("risk_event_id", event.riskEventId);
        payload.put("source_event_id", event.sourceEventId);
        payload.put("rule_id", event.ruleId);
        payload.put("risk_type", event.riskType);
        payload.put("severity", event.severity);
        payload.put("subject_type", event.subjectType);
        payload.put("subject_id", event.subjectId);
        payload.put("score", event.score);
        payload.put("action", event.action);
        payload.put("reason", event.reason);
        payload.put("evidence", event.evidence);
        return payload;
    }
}
