package io.oddsmaker.control.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.jpa.AuditLogEntity;
import io.oddsmaker.control.jpa.BlockListEntity;
import io.oddsmaker.control.jpa.BlockListRepo;
import io.oddsmaker.control.jpa.RiskCaseEntity;
import io.oddsmaker.control.jpa.RiskCaseRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 风控案例回看服务（调研「误杀漏杀案例回看」落点，B6 骨架的后续增量）
 * 提供案例列表/详情/解除封禁；判定状态机的正向流转仍由 RiskEventConsumer 驱动，
 * 本服务只在 BLOCK 已执行且未解除时做人工解除（含联动解除封禁名单）。
 */
@Service
@Transactional
public class RiskCaseService {

    private static final Logger logger = LoggerFactory.getLogger(RiskCaseService.class);

    /** 单页上限：防止全表量级拉取 */
    private static final int MAX_LIMIT = 500;

    /** 规则/处置后过滤的取数窗口：先取最近 N 条再内存过滤（策略实验室样本下钻量级） */
    private static final int POST_FILTER_SCAN = 2000;

    private final RiskCaseRepo riskCaseRepo;
    private final BlockListRepo blockListRepo;
    private final BlockListService blockListService;
    private final AuditLogService auditLogService;
    private final RiskScoreService riskScoreService;
    private final ObjectMapper objectMapper;

    public RiskCaseService(RiskCaseRepo riskCaseRepo, BlockListRepo blockListRepo,
                           BlockListService blockListService, AuditLogService auditLogService,
                           RiskScoreService riskScoreService, ObjectMapper objectMapper) {
        this.riskCaseRepo = riskCaseRepo;
        this.blockListRepo = blockListRepo;
        this.blockListService = blockListService;
        this.auditLogService = auditLogService;
        this.riskScoreService = riskScoreService;
        this.objectMapper = objectMapper;
    }

    /**
     * 按游戏列案例，最新在前；status/riskLevel/ruleId/disposition 可选过滤，limit 默认 100、上限 500。
     * ruleId/disposition（策略实验室样本下钻）为内存后过滤：先按状态组合取最近 {@value #POST_FILTER_SCAN} 条，
     * 过滤后截断到 limit——不在该窗口内的更深历史不参与下钻（复盘按最新优先）。
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String gameId, String status, String riskLevel,
                                          String ruleId, String disposition, int limit) {
        RiskCaseEntity.DecisionStatus st = parseEnum(RiskCaseEntity.DecisionStatus.class, status, "status");
        RiskCaseEntity.RiskLevel lv = parseEnum(RiskCaseEntity.RiskLevel.class, riskLevel, "riskLevel");
        boolean hasPostFilter = notBlank(ruleId) || notBlank(disposition);
        int clamped = Math.min(Math.max(limit, 1), MAX_LIMIT);
        Pageable page = PageRequest.of(0, hasPostFilter ? POST_FILTER_SCAN : clamped);

        List<RiskCaseEntity> cases;
        if (st != null && lv != null) {
            cases = riskCaseRepo.findByGameIdAndStatusAndRiskLevelOrderByCreatedAtDesc(gameId, st, lv, page);
        } else if (st != null) {
            cases = riskCaseRepo.findByGameIdAndStatusOrderByCreatedAtDesc(gameId, st, page);
        } else if (lv != null) {
            cases = riskCaseRepo.findByGameIdAndRiskLevelOrderByCreatedAtDesc(gameId, lv, page);
        } else {
            cases = riskCaseRepo.findByGameIdOrderByCreatedAtDesc(gameId, page);
        }

        List<RiskCaseEntity> matched = cases;
        if (hasPostFilter) {
            matched = new ArrayList<>();
            for (RiskCaseEntity rc : cases) {
                if (matched.size() >= clamped) {
                    break;
                }
                if (notBlank(ruleId) && !ruleId.equals(rc.riskRuleId)) {
                    continue;
                }
                if (notBlank(disposition) && !disposition.equals(rc.disposition)) {
                    continue;
                }
                matched.add(rc);
            }
        }

        List<Map<String, Object>> result = new ArrayList<>(matched.size());
        for (RiskCaseEntity rc : matched) {
            result.add(toListItem(rc));
        }
        return result;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * 案例详情：含证据/上下文 JSON 解析（解析失败按原文回传，不阻断回看）
     */
    @Transactional(readOnly = true)
    public Map<String, Object> detail(String gameId, String caseId) {
        RiskCaseEntity rc = riskCaseRepo.findById(caseId).orElse(null);
        if (rc == null || !rc.gameId.equals(gameId)) {
            return null;
        }
        Map<String, Object> detail = toListItem(rc);
        detail.put("environmentId", rc.environmentId);
        detail.put("riskRuleId", rc.riskRuleId);
        detail.put("triggerEventId", rc.triggerEventId);
        detail.put("actionDescription", rc.actionDescription);
        detail.put("executionError", rc.executionError);
        detail.put("executedAt", rc.executedAt);
        detail.put("reviewedBy", rc.reviewedBy);
        detail.put("reviewedAt", rc.reviewedAt);
        detail.put("reviewNotes", rc.reviewNotes);
        detail.put("resolvedAt", rc.resolvedAt);
        detail.put("unblockedBy", rc.unblockedBy);
        detail.put("unblockReason", rc.unblockReason);
        detail.put("evidence", parseJsonOrRaw(rc.evidenceData));
        detail.put("context", parseJsonOrRaw(rc.contextData));
        detail.put("subjectRiskScore", subjectRiskScore(rc));
        return detail;
    }

    /**
     * 主体累计风险分快照（B6 闭合的回看侧表面）：按案例目标读 ClickHouse risk_scores 最新一条；
     * 未落过分（found=false）或 CH 不可用/查询失败一律降级为 null，不阻断案例回看。
     */
    private Map<String, Object> subjectRiskScore(RiskCaseEntity rc) {
        try {
            Map<String, Object> snapshot = riskScoreService.latest(rc.gameId, rc.targetType, rc.targetId);
            return snapshot != null && Boolean.TRUE.equals(snapshot.get("found")) ? snapshot : null;
        } catch (RuntimeException e) {
            logger.debug("subject risk score unavailable for case {}: {}", rc.id, e.getMessage());
            return null;
        }
    }

    /**
     * 人工解除封禁（误杀处置主出口）：案例须处于 BLOCK 已执行且未解除；
     * 联动解除由本案例创建的、仍活跃的封禁名单记录（BlockListService.unblock 内逐条审计）
     */
    public RiskCaseEntity unblock(String gameId, String caseId, String by, String reason) {
        RiskCaseEntity rc = riskCaseRepo.findById(caseId)
            .orElseThrow(() -> new IllegalArgumentException("Risk case not found: " + caseId));
        if (!rc.gameId.equals(gameId)) {
            throw new IllegalArgumentException("Risk case not found: " + caseId);
        }
        if (!rc.isBlocked() || rc.isUnblocked()) {
            throw new IllegalStateException("案例不可解除封禁（须 BLOCK 已执行且未解除）: " + caseId);
        }

        List<BlockListEntity> linked = blockListRepo.findByRiskCaseId(caseId);
        int cascaded = 0;
        for (BlockListEntity block : linked) {
            if (block.isActive()) {
                blockListService.unblock(block.id, by, reason);
                cascaded++;
            }
        }

        rc.unblock(by, reason);
        riskCaseRepo.save(rc);

        auditLogService.log(
            AuditLogEntity.AuditAction.UNBLOCK,
            rc.targetType,
            rc.targetId,
            rc.caseNumber,
            reason,
            AuditLogEntity.AuditResult.SUCCESS,
            by,
            null,
            null,
            null,
            null,
            Map.of(
                "gameId", gameId,
                "riskCaseId", caseId,
                "cascadedBlocks", cascaded
            )
        );

        logger.info("Risk case {} unblocked by {} ({} linked blocks released)", caseId, by, cascaded);
        return rc;
    }

    // ===== 私有辅助 =====

    private Map<String, Object> toListItem(RiskCaseEntity rc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rc.id);
        m.put("caseNumber", rc.caseNumber);
        m.put("riskLevel", rc.riskLevel != null ? rc.riskLevel.name() : null);
        m.put("riskScore", rc.riskScore);
        m.put("status", rc.status != null ? rc.status.name() : null);
        m.put("actionTaken", rc.actionTaken != null ? rc.actionTaken.name() : null);
        m.put("executionStatus", rc.executionStatus != null ? rc.executionStatus.name() : null);
        m.put("targetType", rc.targetType);
        m.put("targetId", rc.targetId);
        m.put("targetName", rc.targetName);
        m.put("triggerEventType", rc.triggerEventType);
        m.put("triggerEventName", rc.triggerEventName);
        m.put("disposition", rc.disposition);
        m.put("reviewStatus", rc.reviewStatus);
        m.put("unblockedAt", rc.unblockedAt);
        m.put("createdAt", rc.createdAt);
        m.put("updatedAt", rc.updatedAt);
        return m;
    }

    private Object parseJsonOrRaw(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return json;
        }
    }

    private <E extends Enum<E>> E parseEnum(Class<E> type, String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("非法 " + field + " 取值: " + value);
        }
    }
}
