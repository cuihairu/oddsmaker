package io.oddsmaker.control.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 风控规则条件类型化视图（重构契约 §2.4「收敛 ruleConditions 为结构化 JSON schema」，2026-10-15 代决落地）：
 * {@code rule_conditions} 列维持 JSON 文本存储（不改写存量行、不改对外暴露面），本类是
 * 读时类型化 + 写时校验的单一入口。合法形态与 jobs/flink/risk-job {@code RuleFetcher} 的解析口径逐条对齐：
 *
 * <ul>
 *   <li>PATTERN：{@code {"sequence":["login","purchase"]}}（2-8 步、名称长度约束在 RiskRuleService）</li>
 *   <li>FEATURE（B5）：{@code {"features":[{"scope":"SUBJECT","feature":"gold_gain_1h","op":"&gt;","value":100}]}}
 *       ——scope 缺省 SUBJECT，仅 SUBJECT/IP</li>
 *   <li>其余键不识别也不丢弃，{@link #raw} 原样保留（存量自由文本 JSON 对象可解析即兼容）</li>
 * </ul>
 *
 * 两档读取：
 * <ul>
 *   <li>{@link #parse(String)} 严格档——不可解析 / 非对象 / 序列或特征数组任一元素非法即抛
 *       {@link IllegalArgumentException}（写路径「显式报错」，异常文案以 ruleConditions 开头）</li>
 *   <li>{@link #parseTolerant(String, ObjectMapper)} 容忍档——同口径但坏数据落
 *       {@code parsed=false} + 空列表不抛（读路径存量兼容；replay 跳过语义与 RuleFetcher 一致）</li>
 * </ul>
 */
public final class RuleConditions {

    /** FEATURE 条件合法算子（与 jobs/flink/risk-job RuleFetcher.OPS 同款，两侧格式级改动须同批） */
    public static final Set<String> FEATURE_OPS = Set.of(">", ">=", "<", "<=", "==");

    private static final ObjectMapper SHARED = new ObjectMapper();

    /** PATTERN 序列（非法或缺失为空列表） */
    public final List<String> sequence;
    /** FEATURE 特征条件（非法或缺失为空列表） */
    public final List<FeatureCondition> features;
    /** 整棵根节点原样（容忍档坏数据为 null）；未识别键的存量自由文本在此保留 */
    public final JsonNode raw;
    /** 容忍档专用：false = 不可解析或形态非法（严格档此形态直接抛） */
    public final boolean parsed;

    /** FEATURE 单条特征条件（scope 缺省 SUBJECT，仅 SUBJECT/IP） */
    public record FeatureCondition(String scope, String feature, String op, double value) {}

    private RuleConditions(List<String> sequence, List<FeatureCondition> features, JsonNode raw, boolean parsed) {
        this.sequence = sequence;
        this.features = features;
        this.raw = raw;
        this.parsed = parsed;
    }

    /** FEATURE 写路径最低要求：至少一条合法特征条件 */
    public boolean hasFeatures() {
        return !features.isEmpty();
    }

    /** 严格档：坏 JSON / 非对象 / 已知键形态非法显式抛错（异常文案以 ruleConditions 开头） */
    public static RuleConditions parse(String ruleConditions) {
        return of(ruleConditions, null, true);
    }

    /** 严格档（调用方自带 ObjectMapper，保持注入缝隙） */
    public static RuleConditions parse(String ruleConditions, ObjectMapper mapper) {
        return of(ruleConditions, mapper, true);
    }

    /** 容忍档：坏数据不抛、parsed=false + 空列表（存量行向后兼容读取） */
    public static RuleConditions parseTolerant(String ruleConditions, ObjectMapper mapper) {
        return of(ruleConditions, mapper, false);
    }

    private static RuleConditions of(String ruleConditions, ObjectMapper mapper, boolean strict) {
        if (ruleConditions == null || ruleConditions.isBlank()) {
            return new RuleConditions(List.of(), List.of(), null, true);
        }
        JsonNode root;
        try {
            root = (mapper != null ? mapper : SHARED).readTree(ruleConditions);
        } catch (Exception e) {
            if (strict) {
                throw new IllegalArgumentException("ruleConditions is not valid JSON: " + e.getMessage(), e);
            }
            return new RuleConditions(List.of(), List.of(), null, false);
        }
        if (root == null || !root.isObject()) {
            if (strict) {
                throw new IllegalArgumentException("ruleConditions must be a JSON object");
            }
            return new RuleConditions(List.of(), List.of(), null, false);
        }
        List<String> sequence = parseSequence(root);
        List<FeatureCondition> features = parseFeatures(root);
        boolean wellFormed = sequence != null && features != null;
        if (strict) {
            if (sequence == null) {
                throw new IllegalArgumentException(
                    "ruleConditions.sequence must be an array of non-blank event names");
            }
            if (features == null) {
                throw new IllegalArgumentException(
                    "ruleConditions.features requires scope(SUBJECT/IP), non-blank feature, op("
                        + String.join(" ", FEATURE_OPS) + ") and numeric value on every entry");
            }
        }
        return new RuleConditions(sequence == null ? List.of() : sequence,
            features == null ? List.of() : features, root, wellFormed);
    }

    /** sequence 键 → 列表（去空白保序）；缺失为空列表；非数组 / 空事件名 → null（非法） */
    private static List<String> parseSequence(JsonNode root) {
        JsonNode arr = root.path("sequence");
        if (arr.isMissingNode()) {
            return List.of();
        }
        if (!arr.isArray()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (JsonNode n : arr) {
            String s = n.asText("").trim();
            if (s.isEmpty()) {
                return null;
            }
            out.add(s);
        }
        return List.copyOf(out);
    }

    /** features 键 → 条件列表；缺失为空列表；非数组 / 空数组 / 任一元素非法 → null（整列表作废） */
    private static List<FeatureCondition> parseFeatures(JsonNode root) {
        JsonNode arr = root.path("features");
        if (arr.isMissingNode()) {
            return List.of();
        }
        if (!arr.isArray() || arr.isEmpty()) {
            return null;
        }
        List<FeatureCondition> out = new ArrayList<>();
        for (JsonNode n : arr) {
            String scope = n.path("scope").asText("SUBJECT").trim();
            if (!"SUBJECT".equals(scope) && !"IP".equals(scope)) {
                return null;
            }
            String feature = n.path("feature").asText("").trim();
            if (feature.isEmpty()) {
                return null;
            }
            String op = n.path("op").asText("").trim();
            if (!FEATURE_OPS.contains(op)) {
                return null;
            }
            JsonNode value = n.path("value");
            if (!value.isNumber()) {
                return null;
            }
            out.add(new FeatureCondition(scope, feature, op, value.asDouble()));
        }
        return List.copyOf(out);
    }
}
