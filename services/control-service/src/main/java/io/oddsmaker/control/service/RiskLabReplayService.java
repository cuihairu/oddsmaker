package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.jpa.RiskRuleEntity;
import io.oddsmaker.control.jpa.RiskRuleRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 策略实验室试算回放（调研「样本管理 + 策略实验室」落点，V0.3 dry-run）：
 * 上传一批样本事件，按游戏活跃规则逐事件试算命中——语义对齐线上纯函数：
 * THRESHOLD 严格大于阈值（RiskJob.overThreshold）、FEATURE 条件 op 对比全 AND
 * 且缺值/非法条件 fail-closed（FeatureCalc.matches / RuleFetcher.parseFeatureConditions）。
 *
 * 诚实边界（不造假命中）：FREQUENCY/VELOCITY/RATIO/DUPLICATE_RECEIPT/AD_REWARD/PATTERN
 * 依赖流式窗口/序列聚合，标 needsStreaming 跳过；ANOMALY/MACHINE_LEARNING 生产链路
 * 本就不评估，标 notCovered；条件非法（阈值缺失、特征条件残缺）标 invalid——与
 * RuleFetcher 整条跳过语义一致。
 *
 * 同 ruleType 线上只生效 riskScore 最高者（RuleFetcher 收敛）：sampleResults 的
 * effectiveHits 按类型收敛后给出线上会生效的命中；ruleResults 保留逐规则原始命中
 * 便于同型规则对比。samples 即传即算不落库，单次上限 {@value #MAX_SAMPLES} 条。
 */
@Service
@Transactional(readOnly = true)
public class RiskLabReplayService {

    /** 单次试算样本上限（dry-run 量级，防滥用；超出应分批） */
    static final int MAX_SAMPLES = 500;

    static final String STATUS_EVALUABLE = "evaluable";
    static final String STATUS_NEEDS_STREAMING = "needsStreaming";
    static final String STATUS_NOT_COVERED = "notCovered";
    static final String STATUS_INVALID = "invalid";

    /** 合法特征条件算子集（与 RuleFetcher 同款） */
    private static final Set<String> OPS = Set.of(">", ">=", "<", "<=", "==");

    /** 依赖流式窗口/序列聚合的规则类型：dry-run 明示跳过，不模拟 */
    private static final Set<String> STREAMING_TYPES =
            Set.of("FREQUENCY", "VELOCITY", "RATIO", "DUPLICATE_RECEIPT", "AD_REWARD", "PATTERN");

    /** 生产链路（Flink risk-job）不评估的规则类型 */
    private static final Set<String> NOT_COVERED_TYPES = Set.of("ANOMALY", "MACHINE_LEARNING");

    private final RiskRuleRepo riskRuleRepo;
    private final ObjectMapper objectMapper;

    public RiskLabReplayService(RiskRuleRepo riskRuleRepo, ObjectMapper objectMapper) {
        this.riskRuleRepo = riskRuleRepo;
        this.objectMapper = objectMapper;
    }

    /**
     * 批量试算：samples 为样本事件对象列表（eventId 可选默认按序号、amount 数值可选、
     * features 数值 map 可选即该样本的特征快照），ruleIds 可选过滤（缺省评估全部活跃规则）。
     * 返回 {gameId, summary{...}, ruleResults[], sampleResults[]}。
     */
    public Map<String, Object> dryRun(String gameId, List<Map<String, Object>> rawSamples, List<String> ruleIds) {
        validateSamples(rawSamples);

        List<ReplaySample> samples = new ArrayList<>(rawSamples.size());
        for (int i = 0; i < rawSamples.size(); i++) {
            Object o = rawSamples.get(i);
            if (!(o instanceof Map)) {
                throw new IllegalArgumentException("samples 第 " + (i + 1) + " 条必须是对象");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = (Map<String, Object>) o;
            String eventId = raw.get("eventId") instanceof String s && !s.isBlank()
                    ? s : "sample-" + (i + 1);
            BigDecimal amount = toAmount(raw.get("amount"), i + 1);
            Map<String, Double> features = toFeatures(raw.get("features"), i + 1);
            samples.add(new ReplaySample(eventId, amount, features));
        }

        List<RiskRuleEntity> rules = riskRuleRepo.findActiveByGameId(gameId);
        Set<String> idFilter = ruleIds != null ? new LinkedHashSet<>(ruleIds) : Set.of();

        // 第一遍：分类 + 命中表初始化（含跳过类规则，命中恒空）
        List<EvalRule> evaluable = new ArrayList<>();
        Map<String, RiskRuleEntity> allRules = new LinkedHashMap<>();
        Map<String, String> statusByRule = new LinkedHashMap<>();
        Map<String, String> reasonByRule = new LinkedHashMap<>();
        Map<String, List<String>> hitsByRule = new LinkedHashMap<>();
        long skipped = 0;
        for (RiskRuleEntity rule : rules) {
            if (!idFilter.isEmpty() && !idFilter.contains(rule.id)) {
                continue;
            }
            allRules.put(rule.id, rule);
            hitsByRule.put(rule.id, new ArrayList<>());
            String type = rule.ruleType != null ? rule.ruleType.name() : "";
            String status;
            String skipReason = null;
            Integer threshold = null;
            List<FeatureCondition> conditions = List.of();
            if ("THRESHOLD".equals(type)) {
                if (rule.triggerThreshold == null || rule.triggerThreshold <= 0) {
                    status = STATUS_INVALID;
                    skipReason = "触发阈值缺失或非正，线上也不会装载";
                } else {
                    status = STATUS_EVALUABLE;
                    threshold = rule.triggerThreshold;
                }
            } else if ("FEATURE".equals(type)) {
                conditions = parseFeatureConditions(rule.ruleConditions);
                if (conditions.isEmpty()) {
                    status = STATUS_INVALID;
                    skipReason = "特征条件缺失或非法，线上也不会装载";
                } else {
                    status = STATUS_EVALUABLE;
                }
            } else if (STREAMING_TYPES.contains(type)) {
                status = STATUS_NEEDS_STREAMING;
                skipReason = "依赖流式窗口/序列聚合，dry-run 不模拟";
            } else if (NOT_COVERED_TYPES.contains(type)) {
                status = STATUS_NOT_COVERED;
                skipReason = "生产链路未评估该类型";
            } else {
                status = STATUS_INVALID;
                skipReason = "未知规则类型";
            }

            if (STATUS_EVALUABLE.equals(status)) {
                evaluable.add(new EvalRule(rule, threshold, conditions));
            } else {
                skipped++;
            }
            statusByRule.put(rule.id, status);
            reasonByRule.put(rule.id, skipReason);
        }

        // 第二遍：逐样本评估命中（规则表序即命中表序）；逐样本按类型收敛
        List<Map<String, Object>> sampleResults = new ArrayList<>(samples.size());
        long hitSamples = 0;
        for (ReplaySample sample : samples) {
            List<String> matched = new ArrayList<>();
            List<RiskRuleEntity> matchedRules = new ArrayList<>();
            for (EvalRule er : evaluable) {
                if (matches(er, sample)) {
                    matched.add(er.rule.id);
                    matchedRules.add(er.rule);
                    hitsByRule.get(er.rule.id).add(sample.eventId);
                }
            }
            if (!matched.isEmpty()) {
                hitSamples++;
            }
            sampleResults.add(sampleRow(sample, matched, matchedRules));
        }

        List<Map<String, Object>> ruleResults = new ArrayList<>(allRules.size());
        for (Map.Entry<String, RiskRuleEntity> e : allRules.entrySet()) {
            List<String> ids = hitsByRule.get(e.getKey());
            ruleResults.add(ruleRow(e.getValue(), statusByRule.get(e.getKey()),
                    reasonByRule.get(e.getKey()), ids));
        }

        long hitRules = hitsByRule.values().stream().filter(l -> !l.isEmpty()).count();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalSamples", samples.size());
        summary.put("hitSamples", hitSamples);
        summary.put("hitRules", hitRules);
        summary.put("skippedRules", skipped);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("gameId", gameId);
        report.put("summary", summary);
        report.put("ruleResults", ruleResults);
        report.put("sampleResults", sampleResults);
        return report;
    }

    // ===== 辅助 =====

    /**
     * 样本结构校验（dry-run 与样本集存储共用口径）：非空、不超上限、
     * 逐条须为对象且 amount/features 可数值化——留档样本保证之后任何一次重放都能算
     */
    static void validateSamples(List<Map<String, Object>> rawSamples) {
        if (rawSamples == null || rawSamples.isEmpty()) {
            throw new IllegalArgumentException("samples 不能为空");
        }
        if (rawSamples.size() > MAX_SAMPLES) {
            throw new IllegalArgumentException("samples 超过单次上限 " + MAX_SAMPLES + " 条，请分批试算");
        }
        for (int i = 0; i < rawSamples.size(); i++) {
            Object o = rawSamples.get(i);
            if (!(o instanceof Map)) {
                throw new IllegalArgumentException("samples 第 " + (i + 1) + " 条必须是对象");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = (Map<String, Object>) o;
            toAmount(raw.get("amount"), i + 1);
            toFeatures(raw.get("features"), i + 1);
        }
    }

    private record ReplaySample(String eventId, BigDecimal amount, Map<String, Double> features) {}

    private record EvalRule(RiskRuleEntity rule, Integer threshold, List<FeatureCondition> conditions) {}

    /** FEATURE 单条条件（scope 仅决定线上归属口径，dry-run 对样本特征快照统一取值） */
    private record FeatureCondition(String feature, String op, double threshold) {}

    /** THRESHOLD：金额严格大于阈值（对齐 RiskJob.overThreshold） */
    private static boolean matches(EvalRule er, ReplaySample sample) {
        if (er.threshold != null) {
            return sample.amount != null
                    && sample.amount.compareTo(BigDecimal.valueOf(er.threshold)) > 0;
        }
        // FEATURE：全部条件 AND；缺值 fail-closed（对齐 FeatureCalc.matches 语义）
        for (FeatureCondition c : er.conditions) {
            Double v = sample.features.get(c.feature);
            if (v == null || !matchesOp(c.op, v, c.threshold)) {
                return false;
            }
        }
        return !er.conditions.isEmpty();
    }

    private static boolean matchesOp(String op, double value, double threshold) {
        return switch (op) {
            case ">" -> value > threshold;
            case ">=" -> value >= threshold;
            case "<" -> value < threshold;
            case "<=" -> value <= threshold;
            case "==" -> Double.compare(value, threshold) == 0;
            default -> false;
        };
    }

    /**
     * ruleConditions JSON → 特征条件列表；与 RuleFetcher.parseFeatureConditions 同款校验：
     * scope 缺省 SUBJECT 且仅 SUBJECT/IP、feature 非空、op 合法、value 数值，任一违反整列表作废。
     */
    private List<FeatureCondition> parseFeatureConditions(String ruleConditions) {
        if (ruleConditions == null || ruleConditions.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(ruleConditions);
        } catch (Exception e) {
            return List.of();
        }
        if (root == null || !root.isObject()) {
            return List.of();
        }
        JsonNode arr = root.path("features");
        if (!arr.isArray() || arr.isEmpty()) {
            return List.of();
        }
        List<FeatureCondition> out = new ArrayList<>();
        for (JsonNode n : arr) {
            String scope = n.path("scope").asText("SUBJECT").trim();
            if (!"SUBJECT".equals(scope) && !"IP".equals(scope)) {
                return List.of();
            }
            String feature = n.path("feature").asText("").trim();
            if (feature.isEmpty()) {
                return List.of();
            }
            String op = n.path("op").asText("").trim();
            if (!OPS.contains(op)) {
                return List.of();
            }
            JsonNode value = n.path("value");
            if (!value.isNumber()) {
                return List.of();
            }
            out.add(new FeatureCondition(feature, op, value.asDouble()));
        }
        return List.copyOf(out);
    }

    private static BigDecimal toAmount(Object v, int idx) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return new BigDecimal(s.trim());
            } catch (NumberFormatException ignored) {
                // 落到下方统一抛错
            }
        }
        throw new IllegalArgumentException("samples 第 " + idx + " 条 amount 非数值");
    }

    private static Map<String, Double> toFeatures(Object v, int idx) {
        if (v == null) {
            return Map.of();
        }
        if (!(v instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("samples 第 " + idx + " 条 features 须为对象");
        }
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            String k = String.valueOf(e.getKey());
            Object val = e.getValue();
            if (val instanceof Number n) {
                out.put(k, n.doubleValue());
            } else if (val instanceof String s && !s.isBlank()) {
                try {
                    out.put(k, new BigDecimal(s.trim()).doubleValue());
                } catch (NumberFormatException ignored) {
                    throw new IllegalArgumentException("samples 第 " + idx + " 条 features 值非数值: " + k);
                }
            } else {
                throw new IllegalArgumentException("samples 第 " + idx + " 条 features 值非数值: " + k);
            }
        }
        return out;
    }

    private static Map<String, Object> ruleRow(RiskRuleEntity rule, String status, String skipReason,
                                               List<String> sampleIds) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ruleId", rule.id);
        m.put("ruleName", rule.name);
        m.put("ruleType", rule.ruleType != null ? rule.ruleType.name() : null);
        m.put("status", status);
        m.put("skipReason", skipReason);
        m.put("hitSamples", sampleIds.size());
        m.put("sampleIds", sampleIds);
        m.put("riskScore", rule.riskScore);
        m.put("riskLevel", rule.riskLevel != null ? rule.riskLevel.name() : null);
        m.put("actionType", rule.actionType != null ? rule.actionType.name() : null);
        return m;
    }

    private static Map<String, Object> sampleRow(ReplaySample sample, List<String> matched,
                                                 List<RiskRuleEntity> matchedRules) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("eventId", sample.eventId);
        m.put("matchedRuleIds", matched);
        m.put("effectiveHits", effectiveHits(matchedRules));
        return m;
    }

    /**
     * 同 ruleType 线上只生效 riskScore 最高者（RuleFetcher 收敛，平分取 ruleId 小者保稳定）；
     * 输出按 riskScore 降序
     */
    private static List<Map<String, Object>> effectiveHits(List<RiskRuleEntity> matchedRules) {
        Map<String, RiskRuleEntity> byType = new LinkedHashMap<>();
        for (RiskRuleEntity rule : matchedRules) {
            String type = rule.ruleType != null ? rule.ruleType.name() : "";
            RiskRuleEntity cur = byType.get(type);
            if (cur == null || rule.riskScore > cur.riskScore
                    || (rule.riskScore == cur.riskScore && rule.id.compareTo(cur.id) < 0)) {
                byType.put(type, rule);
            }
        }
        List<RiskRuleEntity> effective = new ArrayList<>(byType.values());
        effective.sort(Comparator.comparingInt((RiskRuleEntity r) -> r.riskScore).reversed()
                .thenComparing(r -> r.id));
        List<Map<String, Object>> out = new ArrayList<>(effective.size());
        for (RiskRuleEntity rule : effective) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ruleId", rule.id);
            m.put("ruleName", rule.name);
            m.put("ruleType", rule.ruleType != null ? rule.ruleType.name() : null);
            m.put("riskScore", rule.riskScore);
            m.put("riskLevel", rule.riskLevel != null ? rule.riskLevel.name() : null);
            m.put("actionType", rule.actionType != null ? rule.actionType.name() : null);
            out.add(m);
        }
        return out;
    }
}
