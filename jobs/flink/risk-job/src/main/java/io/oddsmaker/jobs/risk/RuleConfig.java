package io.oddsmaker.jobs.risk;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 风控规则配置
 *
 * 以 volatile 不可变对象整体替换方式维护全局规则快照。
 * 规则按 ruleType 索引，每种类型一条（同类型多条按 riskScore 取最高）。
 * 未配置的规则类型走 DEFAULTS 兜底（读取系统属性或硬编码默认值）。
 */
public final class RuleConfig {
    private static volatile RuleConfig current;

    /** 按 ruleType 索引的规则快照（不可变） */
    public final Map<String, RuleSpec> byType;

    /** PATTERN 默认序列解析：逗号分隔事件名，至少 2 步 */
    static List<String> defaultPatternSequence() {
        String raw = System.getProperty("risk.pattern.sequence", "login,purchase,refund");
        return java.util.Arrays.stream(raw.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).distinct()
                .collect(java.util.stream.Collectors.toUnmodifiableList());
    }

    /** 默认兜底规则（均 action="ALERT"） */
    private static final Map<String, RuleSpec> DEFAULTS;

    static {
        LinkedHashMap<String, RuleSpec> defs = new LinkedHashMap<>();
        defs.put("THRESHOLD", new RuleSpec(
                null, "THRESHOLD",
                Integer.parseInt(System.getProperty("risk.threshold.amount", "100000")),
                "ALERT", 80, "HIGH"));
        defs.put("FREQUENCY", new RuleSpec(
                null, "FREQUENCY",
                Integer.parseInt(System.getProperty("risk.frequency.max-events", "1000")),
                "ALERT", 60, "MEDIUM"));
        defs.put("VELOCITY", new RuleSpec(
                null, "VELOCITY",
                Integer.parseInt(System.getProperty("risk.velocity.max-amount", "1000000")),
                "ALERT", 75, "HIGH"));
        defs.put("RATIO", new RuleSpec(
                null, "RATIO",
                Integer.parseInt(System.getProperty("risk.ratio.max-source-sink", "10")),
                "ALERT", 65, "MEDIUM"));
        defs.put("DUPLICATE_RECEIPT", new RuleSpec(
                null, "DUPLICATE_RECEIPT",
                Integer.parseInt(System.getProperty("risk.receipt.max-occurrences", "2")),
                "REVIEW", 90, "CRITICAL"));
        defs.put("AD_REWARD", new RuleSpec(
                null, "AD_REWARD",
                Integer.parseInt(System.getProperty("risk.adreward.max-per-window", "60")),
                "ALERT", 70, "HIGH"));
        int patternWindowSeconds = Integer.parseInt(System.getProperty("risk.pattern.window-minutes", "5")) * 60;
        defs.put("PATTERN", new RuleSpec(
                null, "PATTERN",
                patternWindowSeconds,   // triggerThreshold 借用为窗口秒数（序列规则无金额阈值）
                "REVIEW", 85, "HIGH",
                defaultPatternSequence(), patternWindowSeconds));
        DEFAULTS = Collections.unmodifiableMap(defs);
    }

    public RuleConfig(Map<String, RuleSpec> byType) {
        this.byType = byType != null ? Collections.unmodifiableMap(new LinkedHashMap<>(byType)) : Map.of();
    }

    public static RuleConfig current() { return current; }

    public static void update(RuleConfig rc) {
        if (rc != null) current = rc;
    }

    /** 获取某类型的有效规则；未配置时返回默认兜底 */
    public static RuleSpec byType(String ruleType) {
        RuleConfig c = current;
        if (c != null && c.byType.containsKey(ruleType)) {
            return c.byType.get(ruleType);
        }
        return DEFAULTS.get(ruleType);
    }

    /** 单条规则的定义 */
    public static final class RuleSpec {
        /** 规则 ID（来自 control API） */
        public final String ruleId;
        /** 规则类型：THRESHOLD / FREQUENCY / VELOCITY / RATIO / DUPLICATE_RECEIPT / AD_REWARD / PATTERN */
        public final String ruleType;
        /** 触发阈值（金额为分/元单位计数值；PATTERN 借用为窗口秒数） */
        public final int triggerThreshold;
        /** 处置动作：ALERT / BLOCK / REVIEW / THROTTLE / WEBHOOK */
        public final String actionType;
        /** 风险评分 0-100 */
        public final int riskScore;
        /** 风险等级：LOW / MEDIUM / HIGH / CRITICAL */
        public final String riskLevel;
        /** PATTERN 专属：有序事件名序列（≥2 步）；其他类型为空列表 */
        public final List<String> sequence;
        /** PATTERN 专属：整段序列允许的时间窗（秒，自第一步起算）；其他类型为 0 */
        public final int windowSeconds;

        public RuleSpec(String ruleId, String ruleType, int triggerThreshold,
                        String actionType, int riskScore, String riskLevel) {
            this(ruleId, ruleType, triggerThreshold, actionType, riskScore, riskLevel, List.of(), 0);
        }

        public RuleSpec(String ruleId, String ruleType, int triggerThreshold,
                        String actionType, int riskScore, String riskLevel, List<String> sequence) {
            this(ruleId, ruleType, triggerThreshold, actionType, riskScore, riskLevel, sequence, 0);
        }

        public RuleSpec(String ruleId, String ruleType, int triggerThreshold,
                        String actionType, int riskScore, String riskLevel,
                        List<String> sequence, int windowSeconds) {
            this.ruleId = ruleId;
            this.ruleType = ruleType;
            this.triggerThreshold = triggerThreshold;
            this.actionType = actionType;
            this.riskScore = riskScore;
            this.riskLevel = riskLevel;
            this.sequence = sequence != null ? List.copyOf(sequence) : List.of();
            this.windowSeconds = windowSeconds;
        }
    }
}
