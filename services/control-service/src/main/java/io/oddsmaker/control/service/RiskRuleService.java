package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.GameRepo;
import io.oddsmaker.control.jpa.RiskRuleEntity;
import io.oddsmaker.control.jpa.RiskRuleRepo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 风控规则管理：阈值、速度、序列（模式）、黑名单联动、模型规则。
 */
@Service
public class RiskRuleService {

    private final RiskRuleRepo ruleRepo;
    private final GameRepo gameRepo;
    private final AuditLogService auditLog;

    public RiskRuleService(RiskRuleRepo ruleRepo, GameRepo gameRepo, AuditLogService auditLog) {
        this.ruleRepo = ruleRepo;
        this.gameRepo = gameRepo;
        this.auditLog = auditLog;
    }

    @Transactional(readOnly = true)
    public Page<RiskRuleEntity> list(String gameId, String environmentId, String status,
                                     String type, String q, int page, int size) {
        Specification<RiskRuleEntity> spec = (root, query, cb) -> {
            var predicates = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            predicates.add(cb.isNull(root.get("deletedAt")));
            if (gameId != null && !gameId.isBlank()) {
                predicates.add(cb.equal(root.get("gameId"), gameId));
            }
            if (environmentId != null && !environmentId.isBlank()) {
                predicates.add(cb.or(
                    cb.equal(root.get("environmentId"), environmentId),
                    cb.isNull(root.get("environmentId"))));
            }
            if (status != null && !status.isBlank()) {
                predicates.add(cb.equal(root.get("status"), RiskRuleEntity.RuleStatus.valueOf(status.trim().toUpperCase())));
            }
            if (type != null && !type.isBlank()) {
                predicates.add(cb.equal(root.get("ruleType"), RiskRuleEntity.RuleType.valueOf(type.trim().toUpperCase())));
            }
            if (q != null && !q.isBlank()) {
                String like = "%" + q.trim() + "%";
                predicates.add(cb.or(
                    cb.like(root.get("name"), like),
                    cb.like(root.get("displayName"), like),
                    cb.like(root.get("description"), like)));
            }
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, size),
            Sort.by(Sort.Order.desc("priority"), Sort.Order.asc("createdAt")));
        return ruleRepo.findAll(spec, pageable);
    }

    @Transactional(readOnly = true)
    public RiskRuleEntity get(String id) {
        return ruleRepo.findById(id)
            .filter(rule -> rule.deletedAt == null)
            .orElse(null);
    }

    @Transactional
    public RiskRuleEntity create(RiskRuleEntity rule, String operator) {
        if (rule.gameId == null || rule.gameId.isBlank()) {
            throw new IllegalArgumentException("gameId is required");
        }
        gameRepo.findById(rule.gameId)
            .filter(game -> game.deletedAt == null)
            .orElseThrow(() -> new IllegalArgumentException("Game not found: " + rule.gameId));
        if (rule.name == null || rule.name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (ruleRepo.findByGameId(rule.gameId).stream()
            .anyMatch(existing -> existing.name.equals(rule.name.trim()))) {
            throw new IllegalArgumentException("Rule name already exists in game: " + rule.name);
        }
        rule.id = "rr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        rule.name = rule.name.trim();
        if (rule.status == null) rule.status = RiskRuleEntity.RuleStatus.DRAFT;
        if (rule.status == RiskRuleEntity.RuleStatus.ACTIVE) rule.activatedAt = LocalDateTime.now();
        validatePatternConditions(rule.ruleType, rule.ruleConditions);
        rule.totalTriggeredCount = 0L;
        rule.totalBlockedCount = 0L;
        rule.totalReviewCount = 0L;
        rule.createdBy = operator;
        RiskRuleEntity saved = ruleRepo.save(rule);
        auditLog.logCreate(operator, operator, "risk_rule", saved.id, saved.name,
            "gameId=" + saved.gameId + ",type=" + saved.ruleType + ",action=" + saved.actionType, (String) null);
        return saved;
    }

    @Transactional
    public RiskRuleEntity update(String id, RiskRuleEntity req, String operator) {
        RiskRuleEntity existing = get(id);
        if (existing == null) return null;
        String oldValue = "name=" + existing.name + ",conditions=" + existing.ruleConditions
            + ",action=" + existing.actionType;

        if (req.name != null && !req.name.isBlank()) existing.name = req.name.trim();
        if (req.displayName != null) existing.displayName = req.displayName;
        if (req.description != null) existing.description = req.description;
        if (req.category != null) existing.category = req.category;
        if (req.ruleType != null) existing.ruleType = req.ruleType;
        if (req.ruleConditions != null) existing.ruleConditions = req.ruleConditions;
        if (req.riskLevel != null) existing.riskLevel = req.riskLevel;
        if (req.riskScore != null) existing.riskScore = Math.max(0, Math.min(100, req.riskScore));
        if (req.actionType != null) existing.actionType = req.actionType;
        if (req.actionParams != null) existing.actionParams = req.actionParams;
        if (req.enableAutoBlock != null) existing.enableAutoBlock = req.enableAutoBlock;
        if (req.blockDuration != null) existing.blockDuration = req.blockDuration;
        if (req.enableWebhook != null) existing.enableWebhook = req.enableWebhook;
        if (req.webhookUrl != null) existing.webhookUrl = req.webhookUrl;
        if (req.enableReviewQueue != null) existing.enableReviewQueue = req.enableReviewQueue;
        if (req.triggerThreshold != null) existing.triggerThreshold = Math.max(1, req.triggerThreshold);
        if (req.timeWindowMinutes != null) existing.timeWindowMinutes = Math.max(1, req.timeWindowMinutes);
        if (req.cooldownMinutes != null) existing.cooldownMinutes = Math.max(0, req.cooldownMinutes);
        if (req.priority != null) existing.priority = req.priority;
        if (req.testMode != null) existing.testMode = req.testMode;
        // 仅在本请求触及类型/条件时校验合并后的终态：
        // 无关字段编辑不应被历史遗留的坏数据卡住（存量非法序列由 job 侧跳过兜底）
        if (req.ruleType != null || req.ruleConditions != null) {
            validatePatternConditions(existing.ruleType, existing.ruleConditions);
        }

        RiskRuleEntity saved = ruleRepo.save(existing);
        auditLog.logUpdate(operator, operator, "risk_rule", saved.id, saved.name, oldValue,
            "conditions=" + saved.ruleConditions + ",action=" + saved.actionType, null);
        return saved;
    }

    @Transactional
    public RiskRuleEntity setStatus(String id, boolean enable, String operator) {
        RiskRuleEntity existing = get(id);
        if (existing == null) return null;
        existing.status = enable ? RiskRuleEntity.RuleStatus.ACTIVE : RiskRuleEntity.RuleStatus.PAUSED;
        if (enable) existing.activatedAt = LocalDateTime.now();
        RiskRuleEntity saved = ruleRepo.save(existing);
        auditLog.log(
            enable ? io.oddsmaker.control.jpa.AuditLogEntity.AuditAction.ENABLE
                   : io.oddsmaker.control.jpa.AuditLogEntity.AuditAction.DISABLE,
            "risk_rule", saved.id, saved.name,
            enable ? "rule enabled" : "rule paused",
            io.oddsmaker.control.jpa.AuditLogEntity.AuditResult.SUCCESS,
            operator, null, null, null, null, null);
        return saved;
    }

    @Transactional
    public boolean delete(String id, String operator) {
        RiskRuleEntity existing = get(id);
        if (existing == null) return false;
        existing.deletedAt = LocalDateTime.now();
        existing.status = RiskRuleEntity.RuleStatus.ARCHIVED;
        ruleRepo.save(existing);
        auditLog.logDelete(operator, operator, "risk_rule", existing.id, existing.name,
            "gameId=" + existing.gameId, null);
        return true;
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper SEQUENCE_JSON =
        new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * PATTERN 规则约束：ruleConditions 必须是 {"sequence": [事件名...]}，2-8 个非空事件名（各 ≤100 字符）。
     * 非法即抛 IllegalArgumentException（create/update 的 4xx 语义）；其他类型不校验。
     */
    private static void validatePatternConditions(RiskRuleEntity.RuleType ruleType, String ruleConditions) {
        if (ruleType != RiskRuleEntity.RuleType.PATTERN) return;
        if (parseSequence(ruleConditions).isEmpty()) {
            throw new IllegalArgumentException(
                "PATTERN rule requires ruleConditions {\"sequence\": [...]} with 2-8 non-blank event names");
        }
    }

    /** ruleConditions JSON → sequence 数组；非法 JSON / 非数组 / 空·超长名 / 数量越界返回空列表。 */
    private static java.util.List<String> parseSequence(String ruleConditions) {
        if (ruleConditions == null || ruleConditions.isBlank()) return java.util.List.of();
        try {
            com.fasterxml.jackson.databind.JsonNode arr = SEQUENCE_JSON.readTree(ruleConditions).path("sequence");
            if (!arr.isArray()) return java.util.List.of();
            java.util.List<String> seq = new java.util.ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode n : arr) {
                String s = n.asText("").trim();
                if (s.isEmpty() || s.length() > 100) return java.util.List.of();
                seq.add(s);
            }
            if (seq.size() < 2 || seq.size() > 8) return java.util.List.of();
            return java.util.List.copyOf(seq);
        } catch (Exception e) {
            return java.util.List.of();
        }
    }
}
