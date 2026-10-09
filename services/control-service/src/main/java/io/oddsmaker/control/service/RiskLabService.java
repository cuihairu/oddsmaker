package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.RiskCaseEntity;
import io.oddsmaker.control.jpa.RiskCaseRepo;
import io.oddsmaker.control.jpa.RiskRuleEntity;
import io.oddsmaker.control.jpa.RiskRuleRepo;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 策略实验室（调研「样本管理 + 策略实验室」落点，V0.2 复盘聚合）：
 * 规则维度的案例复盘聚合——每条规则的案例数、误杀（confirmed_benign）/确认违规
 * （confirmed_fraud）/证据不足（inconclusive）/未复盘分桶、误杀率（分母=已复盘）、
 * 平均复盘时长（reviewedAt − createdAt）与最近案例时间；零案例规则也出行。
 *
 * 样本下钻沿用案例回看 API（risk-cases 列表的 ruleId/disposition 过滤）。
 * 边界：聚合在每游戏最近 {@value #MAX_CASES_SCAN} 条案例内进行（复盘数据量级），不回放打分。
 */
@Service
@Transactional(readOnly = true)
public class RiskLabService {

    /** 聚合扫描上限：复盘口径按最新案例优先，超出量级应先结案归档 */
    static final int MAX_CASES_SCAN = 5000;

    private final RiskRuleRepo riskRuleRepo;
    private final RiskCaseRepo riskCaseRepo;

    public RiskLabService(RiskRuleRepo riskRuleRepo, RiskCaseRepo riskCaseRepo) {
        this.riskRuleRepo = riskRuleRepo;
        this.riskCaseRepo = riskCaseRepo;
    }

    /**
     * 按游戏出规则复盘聚合：每规则一行（含零案例规则），行内含误杀率与平均复盘时长；
     * 案例引用了已删除规则的按 ruleId 出孤儿行（计数不丢）；按案例数降序、ruleId 升序稳定排序
     */
    public Map<String, Object> ruleStats(String gameId) {
        Map<String, RiskRuleEntity> rulesById = new LinkedHashMap<>();
        for (RiskRuleEntity rule : riskRuleRepo.findByGameId(gameId)) {
            rulesById.put(rule.id, rule);
        }

        Pageable page = PageRequest.of(0, MAX_CASES_SCAN);
        List<RiskCaseEntity> cases = riskCaseRepo.findByGameIdOrderByCreatedAtDesc(gameId, page);

        // ruleId 维度累加器；顺序按首次出现（最新案例优先），输出前再统一排序
        Map<String, RuleAgg> aggByRule = new LinkedHashMap<>();
        long totalCases = 0;
        long totalBenign = 0;
        for (RiskCaseEntity rc : cases) {
            totalCases++;
            if (rc.isConfirmedBenign()) {
                totalBenign++;
            }
            if (rc.riskRuleId == null || rc.riskRuleId.isBlank()) {
                continue;  // 无规则归属的案例只进总数，不产规则行
            }
            RuleAgg agg = aggByRule.computeIfAbsent(rc.riskRuleId, RuleAgg::new);
            agg.caseCount++;
            if (rc.isConfirmedBenign()) {
                agg.falsePositiveCount++;
            } else if (rc.isConfirmedFraud()) {
                agg.confirmedFraudCount++;
            } else if ("inconclusive".equals(rc.disposition)) {
                agg.inconclusiveCount++;
            } else {
                agg.unreviewedCount++;
            }
            if (rc.reviewedAt != null && rc.createdAt != null) {
                agg.reviewHoursSum += Duration.between(rc.createdAt, rc.reviewedAt).toMinutes() / 60.0;
                agg.reviewedCount++;
            }
            if (agg.lastCaseAt == null || (rc.createdAt != null && rc.createdAt.isAfter(agg.lastCaseAt))) {
                agg.lastCaseAt = rc.createdAt;
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>(aggByRule.size());
        // 零案例规则也出行（案例数为 0、比率与时长为 null）
        for (Map.Entry<String, RiskRuleEntity> entry : rulesById.entrySet()) {
            if (!aggByRule.containsKey(entry.getKey())) {
                aggByRule.put(entry.getKey(), new RuleAgg(entry.getKey()));
            }
        }
        for (RuleAgg agg : aggByRule.values()) {
            RiskRuleEntity rule = rulesById.get(agg.ruleId);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ruleId", agg.ruleId);
            row.put("ruleName", rule != null ? (rule.displayName != null && !rule.displayName.isBlank()
                ? rule.displayName : rule.name) : null);
            row.put("ruleStatus", rule != null && rule.status != null ? rule.status.name() : null);
            row.put("riskScore", rule != null ? rule.riskScore : null);
            row.put("caseCount", agg.caseCount);
            row.put("reviewedCount", agg.reviewedCount);
            row.put("falsePositiveCount", agg.falsePositiveCount);
            row.put("confirmedFraudCount", agg.confirmedFraudCount);
            row.put("inconclusiveCount", agg.inconclusiveCount);
            row.put("unreviewedCount", agg.unreviewedCount);
            long reviewed = agg.falsePositiveCount + agg.confirmedFraudCount + agg.inconclusiveCount;
            row.put("misKillRate", reviewed > 0 ? round1(agg.falsePositiveCount * 100.0 / reviewed) : null);
            row.put("avgReviewHours", agg.reviewedCount > 0 ? round1(agg.reviewHoursSum / agg.reviewedCount) : null);
            row.put("lastCaseAt", agg.lastCaseAt);
            rows.add(row);
        }
        rows.sort(Comparator
            .comparing((Map<String, Object> r) -> (long) r.get("caseCount")).reversed()
            .thenComparing(r -> String.valueOf(r.get("ruleId"))));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("gameId", gameId);
        result.put("scannedCases", cases.size());
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("totalCases", totalCases);
        totals.put("totalFalsePositives", totalBenign);
        totals.put("rulesWithCases", aggByRule.values().stream().filter(a -> a.caseCount > 0).count());
        result.put("totals", totals);
        result.put("rules", rows);
        return result;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    /** 单规则累加器（service 内部） */
    private static final class RuleAgg {
        final String ruleId;
        long caseCount;
        long reviewedCount;          // 有 reviewedAt 的案例数（复盘时长分母）
        long falsePositiveCount;     // confirmed_benign
        long confirmedFraudCount;    // confirmed_fraud
        long inconclusiveCount;      // inconclusive
        long unreviewedCount;        // 无 disposition（含未知取值）
        double reviewHoursSum;
        java.time.LocalDateTime lastCaseAt;

        RuleAgg(String ruleId) {
            this.ruleId = ruleId;
        }
    }
}
