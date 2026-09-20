package io.oddsmaker.control.jpa;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 数据质量规则实体
 * 管理数据质量验证规则
 */
@Entity
@Table(name = "data_quality_rules")
public class DataQualityRuleEntity {

    /**
     * 规则类型
     */
    public enum RuleType {
        SCHEMA,         // 模式验证
        COMPLETENESS,   // 完整性检查
        UNIQUENESS,     // 唯一性检查
        ACCURACY,       // 准确性检查
        CONSISTENCY,    // 一致性检查
        TIMELINESS,     // 及时性检查
        VALIDITY,       // 有效性检查
        RANGE,          // 范围检查
        PATTERN,        // 模式匹配
        REFERENCE       // 引用完整性
    }

    /**
     * 规则严重级别
     */
    public enum Severity {
        INFO,           // 信息
        WARNING,        // 警告
        ERROR,          // 错误
        CRITICAL        // 严重
    }

    /**
     * 规则状态
     */
    public enum RuleStatus {
        ACTIVE,         // 活跃
        INACTIVE,       // 未激活
        DEPRECATED      // 已弃用
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public String id;

    @Column(name = "game_id")
    public String gameId;  // 游戏ID

    @Column(name = "pipeline_id")
    public String pipelineId;  // 管道ID（如果是管道特定规则）

    @Column(name = "rule_name", nullable = false, length = 100)
    public String ruleName;  // 规则名称

    @Column(name = "rule_type", nullable = false)
    @Enumerated(EnumType.STRING)
    public RuleType ruleType;  // 规则类型

    @Column(name = "severity", nullable = false)
    @Enumerated(EnumType.STRING)
    public Severity severity = Severity.WARNING;  // 严重级别

    @Column(name = "rule_status", nullable = false)
    @Enumerated(EnumType.STRING)
    public RuleStatus ruleStatus = RuleStatus.ACTIVE;  // 规则状态

    @Column(name = "description", length = 500)
    public String description;  // 描述

    @Column(name = "target_table", length = 100)
    public String targetTable;  // 目标表

    @Column(name = "target_column", length = 100)
    public String targetColumn;  // 目标列

    @Column(name = "rule_definition", columnDefinition = "TEXT")
    public String ruleDefinition;  // JSON格式的规则定义

    @Column(name = "conditions", columnDefinition = "TEXT")
    public String conditions;  // JSON格式的条件表达式

    @Column(name = "threshold_value")
    public String thresholdValue;  // 阈值

    @Column(name = "threshold_operator", length = 20)
    public String thresholdOperator;  // 阈值操作符：gt, lt, eq, gte, lte

    @Column(name = "min_threshold")
    public String minThreshold;  // 最小阈值

    @Column(name = "max_threshold")
    public String maxThreshold;  // 最大阈值

    @Column(name = "allowed_values", columnDefinition = "TEXT")
    public String allowedValues;  // JSON格式的允许值列表

    @Column(name = "pattern_regex", length = 500)
    public String patternRegex;  // 正则表达式模式

    @Column(name = "reference_table", length = 100)
    public String referenceTable;  // 引用表（用于引用完整性检查）

    @Column(name = "reference_column", length = 100)
    public String referenceColumn;  // 引用列

    @Column(name = "sql_condition", columnDefinition = "TEXT")
    public String sqlCondition;  // SQL条件表达式

    @Column(name = "error_message_template", length = 500)
    public String errorMessageTemplate;  // 错误消息模板

    @Column(name = "action_on_failure", length = 50)
    public String actionOnFailure;  // 失败时的操作：stop, warn, log, skip

    @Column(name = "sample_size", columnDefinition = "INTEGER")
    public Integer sampleSize = 10000;  // 抽样大小

    @Column(name = "enabled", columnDefinition = "BOOLEAN")
    public Boolean enabled = true;  // 是否启用

    @Column(name = "last_evaluated_at")
    public LocalDateTime lastEvaluatedAt;  // 最后评估时间

    @Column(name = "last_evaluation_result", columnDefinition = "TEXT")
    public String lastEvaluationResult;  // JSON格式的最后评估结果

    @Column(name = "last_violation_count", columnDefinition = "INTEGER")
    public Integer lastViolationCount = 0;  // 最后违规数量

    @Column(name = "total_evaluations", columnDefinition = "INTEGER")
    public Integer totalEvaluations = 0;  // 总评估次数

    @Column(name = "total_violations", columnDefinition = "INTEGER")
    public Integer totalViolations = 0;  // 总违规次数

    @Column(name = "tags", columnDefinition = "TEXT")
    public String tags;  // JSON格式的标签

    @Column(name = "created_by", nullable = false, length = 64)
    public String createdBy;  // 创建人

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @Column(name = "updated_at")
    public LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    public LocalDateTime deletedAt;

    // 辅助方法

    public boolean isActive() {
        return ruleStatus == RuleStatus.ACTIVE && enabled;
    }

    public boolean isInactive() {
        return ruleStatus == RuleStatus.INACTIVE;
    }

    public boolean isCritical() {
        return severity == Severity.CRITICAL || severity == Severity.ERROR;
    }

    public boolean isWarning() {
        return severity == Severity.WARNING || severity == Severity.INFO;
    }

    public boolean shouldStopOnFailure() {
        return "stop".equals(actionOnFailure);
    }

    public double getViolationRate() {
        if (totalEvaluations == null || totalEvaluations == 0) {
            return 0.0;
        }
        int violations = totalViolations != null ? totalViolations : 0;
        return ((double) violations) / totalEvaluations * 100;
    }

    public void recordEvaluation(boolean passed, int violationCount) {
        this.lastEvaluatedAt = LocalDateTime.now();
        this.lastViolationCount = violationCount;

        if (totalEvaluations == null) {
            totalEvaluations = 0;
        }
        this.totalEvaluations++;

        if (totalViolations == null) {
            totalViolations = 0;
        }
        if (!passed) {
            this.totalViolations += violationCount;
        }
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (ruleStatus == null) {
            ruleStatus = RuleStatus.ACTIVE;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
