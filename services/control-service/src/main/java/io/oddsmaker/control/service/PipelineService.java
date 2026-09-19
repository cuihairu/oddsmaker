package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 管道服务
 * 管理数据处理管道和质量检查
 */
@Service
@Transactional
public class PipelineService {

    private static final Logger logger = LoggerFactory.getLogger(PipelineService.class);

    /**
     * 质量门禁调度开关（默认关闭）：findScheduledPipelines 命中即真跑 CH 规则评估，
     * 每分钟调度对未跑过管道有实际查询放大，默认不开启（对齐 Flink 对账/数据保留的开关先例）。
     */
    @org.springframework.beans.factory.annotation.Value("${oddsmaker.pipeline.schedule-enabled:false}")
    private boolean scheduleEnabled;

    /**
     * 质量规则可校验的 ClickHouse 表白名单（明细/事实表；MV 草图内表除外）。
     * targetTable 拼接进 SQL，必须白名单——不做任何运行时反引号包裹之外的信任。
     */
    private static final Set<String> QUALITY_CHECK_TABLES = Set.of(
        "events", "sessions", "identities", "risk_events", "risk_scores", "risk_actions",
        "retention", "retention_daily", "retention_rolling", "item_dim", "level_dim",
        "funnels_configurable", "exp_exposure_users");

    /** 列名/标识符形态白名单（列无法参数化，只能严格校验后拼接）。 */
    private static final java.util.regex.Pattern IDENTIFIER =
        java.util.regex.Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]{0,63}$");

    /** TIMELINESS 阈值秒数上限（10 年），防 INTERVAL 字面量溢出滥用。 */
    private static final long MAX_TIMELINESS_SECONDS = 315_360_000L;

    private static final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    @Autowired
    private PipelineRepo pipelineRepo;

    @Autowired
    private PipelineJobRepo pipelineJobRepo;

    @Autowired
    private DataQualityRuleRepo dataQualityRuleRepo;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private ClickHouseClient clickHouse;

    /**
     * 创建管道
     */
    public PipelineEntity createPipeline(String gameId, String environmentId, String pipelineName,
                                         PipelineEntity.PipelineType type, String description,
                                         Map<String, Object> sourceConfig,
                                         Map<String, Object> transformConfig,
                                         Map<String, Object> destinationConfig,
                                         String createdBy) {

        PipelineEntity pipeline = new PipelineEntity();
        pipeline.id = "pipe_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        pipeline.gameId = gameId;
        pipeline.environmentId = environmentId;
        pipeline.pipelineName = pipelineName;
        pipeline.pipelineType = type;
        pipeline.description = description;
        pipeline.pipelineStatus = PipelineEntity.PipelineStatus.DRAFT;
        pipeline.createdBy = createdBy;

        try {
            if (sourceConfig != null) {
                pipeline.sourceConfig = objectMapper.writeValueAsString(sourceConfig);
            }
            if (transformConfig != null) {
                pipeline.transformConfig = objectMapper.writeValueAsString(transformConfig);
            }
            if (destinationConfig != null) {
                pipeline.destinationConfig = objectMapper.writeValueAsString(destinationConfig);
            }
        } catch (Exception e) {
            logger.error("Failed to serialize pipeline config", e);
        }

        pipeline = pipelineRepo.save(pipeline);

        // 记录审计日志
        auditLogService.logCreate("pipeline", pipeline.id, pipelineName, createdBy, createdBy, null,
            Map.of("type", type, "gameId", gameId));

        logger.info("Created pipeline: {} for game: {}", pipeline.id, gameId);
        return pipeline;
    }

    /**
     * 执行管道
     */
    public PipelineJobEntity executePipeline(String pipelineId, String triggeredBy) {
        PipelineEntity pipeline = pipelineRepo.findById(pipelineId)
            .orElseThrow(() -> new IllegalArgumentException("Pipeline not found: " + pipelineId));

        if (!pipeline.isActive()) {
            throw new IllegalStateException("Pipeline is not active: " + pipeline.pipelineStatus);
        }

        // 创建任务
        PipelineJobEntity job = new PipelineJobEntity();
        job.id = "pjob_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        job.pipelineId = pipelineId;
        job.gameId = pipeline.gameId;
        job.environmentId = pipeline.environmentId;
        job.jobName = pipeline.pipelineName + " - " + LocalDateTime.now().toString();
        job.jobStatus = PipelineJobEntity.JobStatus.PENDING;
        job.triggerType = "manual";
        job.triggeredBy = triggeredBy;

        job = pipelineJobRepo.save(job);

        // 执行管道
        executePipelineJob(job, pipeline);

        return job;
    }

    /**
     * 执行管道任务。
     *
     * <p>控制面没有分布式执行引擎，管道执行在此收窄为真实语义：<b>质量门禁执行</b>——
     * 对管道关联的启用规则在 ClickHouse 上做真实校验（一次 countIf 查询同时取全表行数与违规行数），
     * processedRows/errorRows 均为实际查询结果。stop 级规则超阈值或 CH 不可达，job 诚实 FAILED。
     */
    private void executePipelineJob(PipelineJobEntity job, PipelineEntity pipeline) {
        job.start();

        try {
            // 执行数据质量检查
            List<DataQualityRuleEntity> rules = dataQualityRuleRepo.findByPipelineId(pipeline.id)
                .stream()
                .filter(DataQualityRuleEntity::isActive)
                .collect(Collectors.toList());

            long processedRows = 0;
            long errorRows = 0;
            for (DataQualityRuleEntity rule : rules) {
                RuleOutcome outcome = evaluateQualityRule(rule);
                processedRows += outcome.scannedRows();
                errorRows += outcome.violations();
                if (!outcome.passed() && rule.shouldStopOnFailure()) {
                    throw new IllegalStateException("Quality gate failed: " + rule.ruleName
                        + " (" + rule.ruleType + ", violations=" + outcome.violations() + ")");
                }
            }

            job.complete(processedRows, errorRows);
            pipeline.recordRun(true, null);

        } catch (Exception e) {
            job.fail(e.getMessage());
            pipeline.recordRun(false, e.getMessage());

            // 检查是否需要重试
            if (job.isRetryable() && pipeline.needsRetry()) {
                job.retry();
                logger.warn("Pipeline job failed, scheduling retry: {}", job.id);
            }
        }

        pipelineJobRepo.save(job);
        pipelineRepo.save(pipeline);
    }

    /** 单条规则的评估结果：扫描行数、违规行数、是否通过（违规未超阈值）。 */
    private record RuleOutcome(long scannedRows, long violations, boolean passed) {}

    /**
     * 评估质量规则：真查 ClickHouse，落库评估结果。
     * CH 异常原样上抛（executePipelineJob 诚实 FAILED），不假成功。
     */
    RuleOutcome evaluateQualityRule(DataQualityRuleEntity rule) {
        long[] result = queryViolations(rule);
        long violations = result[1];
        long threshold = parseThreshold(rule.thresholdValue);
        boolean passed = !violationsExceedThreshold(violations, rule.thresholdOperator, threshold);

        rule.recordEvaluation(passed, (int) Math.min(violations, Integer.MAX_VALUE));
        dataQualityRuleRepo.save(rule);

        logger.debug("Quality rule evaluation: {} - passed: {}, violations: {} / {} rows",
            rule.ruleName, passed, violations, result[0]);

        return new RuleOutcome(result[0], violations, passed);
    }

    /**
     * 构造并执行违规统计查询：{@code SELECT count() AS total, countIf(<违规条件>) AS v FROM <表> [参数]}。
     * 表名走白名单、列名走标识符正则、值全 ? 参数化。
     */
    private long[] queryViolations(DataQualityRuleEntity rule) {
        String table = requireSafeTable(rule.targetTable);
        String column = rule.targetColumn == null ? null : rule.targetColumn.trim();
        requireSafeIdentifier(column, "targetColumn");

        String violationCondition;
        List<Object> args = new ArrayList<>();
        switch (rule.ruleType) {
            case COMPLETENESS -> violationCondition =
                column + " IS NULL OR " + column + " = ''";
            case UNIQUENESS -> {
                // 唯一性违规 = 重复行数；countIf 语义不同，单独构造
                String sql = "SELECT count() AS total, count() - uniqExact(" + column + ") AS v FROM " + table;
                return executeCountQuery(sql);
            }
            case RANGE -> {
                Double min = parseThresholdValue(rule.minThreshold);
                Double max = parseThresholdValue(rule.maxThreshold);
                if (min == null && max == null) {
                    throw new IllegalArgumentException(
                        "RANGE rule requires min_threshold or max_threshold: " + rule.ruleName);
                }
                List<String> conds = new ArrayList<>();
                if (min != null) {
                    conds.add(column + " < ?");
                    args.add(min);
                }
                if (max != null) {
                    conds.add(column + " > ?");
                    args.add(max);
                }
                violationCondition = "isNotNull(" + column + ") AND (" + String.join(" OR ", conds) + ")";
            }
            case PATTERN -> {
                if (rule.patternRegex == null || rule.patternRegex.isBlank()) {
                    throw new IllegalArgumentException(
                        "PATTERN rule requires pattern_regex: " + rule.ruleName);
                }
                violationCondition = "isNotNull(" + column + ") AND NOT match(" + column + ", ?)";
                args.add(rule.patternRegex);
            }
            case VALIDITY -> {
                List<Object> allowed = parseAllowedValues(rule.allowedValues);
                if (allowed.isEmpty()) {
                    throw new IllegalArgumentException(
                        "VALIDITY rule requires allowed_values JSON array: " + rule.ruleName);
                }
                violationCondition = "isNotNull(" + column + ") AND " + column
                    + " NOT IN (" + String.join(", ", java.util.Collections.nCopies(allowed.size(), "?")) + ")";
                args.addAll(allowed);
            }
            case REFERENCE -> {
                String refTable = requireSafeTable(rule.referenceTable);
                String refColumn = rule.referenceColumn == null ? null : rule.referenceColumn.trim();
                requireSafeIdentifier(refColumn, "referenceColumn");
                violationCondition = "isNotNull(" + column + ") AND " + column
                    + " NOT IN (SELECT " + refColumn + " FROM " + refTable + ")";
            }
            case TIMELINESS -> {
                long seconds = parseThreshold(rule.thresholdValue);
                if (seconds <= 0 || seconds > MAX_TIMELINESS_SECONDS) {
                    throw new IllegalArgumentException(
                        "TIMELINESS rule threshold out of range (1.." + MAX_TIMELINESS_SECONDS + "s): " + rule.thresholdValue);
                }
                // INTERVAL 字面量仅接受已通过 parseThreshold 的纯数字，无注入面
                violationCondition = "isNotNull(" + column + ") AND " + column
                    + " < now() - INTERVAL " + seconds + " SECOND";
            }
            default -> throw new IllegalArgumentException(
                "Rule type " + rule.ruleType + " is not auto-evaluable (free-form SQL conditions are not accepted)");
        }

        return executeCountQuery(
            "SELECT count() AS total, countIf(" + violationCondition + ") AS v FROM " + table,
            args.toArray());
    }

    private long[] executeCountQuery(String sql, Object... args) {
        List<Map<String, Object>> rows = clickHouse.query(sql, args);
        if (rows.isEmpty()) {
            throw new IllegalStateException("ClickHouse returned no rows for quality query");
        }
        Number total = (Number) rows.get(0).get("total");
        Number violations = (Number) rows.get(0).get("v");
        return new long[]{
            total != null ? total.longValue() : 0,
            violations != null ? violations.longValue() : 0};
    }

    private String requireSafeTable(String tableName) {
        if (tableName == null || !QUALITY_CHECK_TABLES.contains(tableName.trim())) {
            throw new IllegalArgumentException(
                "targetTable must be one of the ClickHouse quality tables, got: " + tableName);
        }
        return tableName.trim();
    }

    private void requireSafeIdentifier(String identifier, String fieldName) {
        if (identifier == null || !IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException(
                fieldName + " must match ^[a-zA-Z_][a-zA-Z0-9_]{0,63}$, got: " + identifier);
        }
    }

    private long parseThreshold(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            long value = Long.parseLong(raw.trim());
            if (value < 0) {
                throw new IllegalArgumentException("threshold must be non-negative: " + raw);
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("threshold must be an integer, got: " + raw);
        }
    }

    private Double parseThresholdValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("threshold must be a number, got: " + raw);
        }
    }

    private List<Object> parseAllowedValues(String json) {
        try {
            com.fasterxml.jackson.databind.JsonNode array = objectMapper.readTree(json);
            if (!array.isArray()) {
                throw new IllegalArgumentException("allowed_values must be a JSON array");
            }
            List<Object> values = new ArrayList<>();
            array.forEach(node -> values.add(node.isNumber() ? node.decimalValue() : node.asText()));
            return values;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("allowed_values is not valid JSON: " + json, e);
        }
    }

    /** 违规数相对阈值的越界判定（operator 默认 gt 0 语义：出现违规即不通过）。 */
    private static boolean violationsExceedThreshold(long violations, String operator, long threshold) {
        String op = operator == null || operator.isBlank() ? "gt" : operator.trim().toLowerCase(Locale.ROOT);
        return switch (op) {
            case "gt" -> violations > threshold;
            case "gte" -> violations >= threshold;
            case "lt" -> violations < threshold;
            case "lte" -> violations <= threshold;
            case "eq" -> violations == threshold;
            default -> throw new IllegalArgumentException("Unsupported threshold operator: " + operator);
        };
    }

    /**
     * 获取管道
     */
    @Transactional(readOnly = true)
    public PipelineEntity getPipeline(String pipelineId) {
        return pipelineRepo.findById(pipelineId)
            .orElseThrow(() -> new IllegalArgumentException("Pipeline not found: " + pipelineId));
    }

    /**
     * 获取游戏的管道
     */
    @Transactional(readOnly = true)
    public List<PipelineEntity> getPipelines(String gameId) {
        return pipelineRepo.findByGameId(gameId);
    }

    /**
     * 获取管道任务
     */
    @Transactional(readOnly = true)
    public List<PipelineJobEntity> getPipelineJobs(String pipelineId) {
        return pipelineJobRepo.findByPipelineId(pipelineId);
    }

    /**
     * 获取管道统计
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getPipelineStats(String pipelineId) {
        PipelineEntity pipeline = getPipeline(pipelineId);
        List<PipelineJobEntity> jobs = pipelineJobRepo.findByPipelineId(pipelineId);

        long totalJobs = jobs.size();
        long completedJobs = jobs.stream().filter(PipelineJobEntity::isCompleted).count();
        long failedJobs = jobs.stream().filter(PipelineJobEntity::isFailed).count();

        return Map.of(
            "pipelineStatus", pipeline.pipelineStatus,
            "totalRuns", pipeline.runCount,
            "successCount", pipeline.successCount,
            "failureCount", pipeline.failureCount,
            "successRate", pipeline.getSuccessRate(),
            "totalJobs", totalJobs,
            "completedJobs", completedJobs,
            "failedJobs", failedJobs,
            "lastRun", pipeline.lastRunAt,
            "lastSuccess", pipeline.lastSuccessAt
        );
    }

    /**
     * 激活管道
     */
    public PipelineEntity activatePipeline(String pipelineId) {
        PipelineEntity pipeline = getPipeline(pipelineId);
        pipeline.activate();
        return pipelineRepo.save(pipeline);
    }

    /**
     * 暂停管道
     */
    public PipelineEntity pausePipeline(String pipelineId) {
        PipelineEntity pipeline = getPipeline(pipelineId);
        pipeline.pause();
        return pipelineRepo.save(pipeline);
    }

    /**
     * 停止管道
     */
    public PipelineEntity stopPipeline(String pipelineId) {
        PipelineEntity pipeline = getPipeline(pipelineId);
        pipeline.stop();
        return pipelineRepo.save(pipeline);
    }

    /**
     * 创建数据质量规则
     */
    public DataQualityRuleEntity createQualityRule(String gameId, String pipelineId, String ruleName,
                                                   DataQualityRuleEntity.RuleType type,
                                                   DataQualityRuleEntity.Severity severity,
                                                   String targetTable, String targetColumn,
                                                   Map<String, Object> ruleDefinition,
                                                   String createdBy) {

        DataQualityRuleEntity rule = new DataQualityRuleEntity();
        rule.id = "dqr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        rule.gameId = gameId;
        rule.pipelineId = pipelineId;
        rule.ruleName = ruleName;
        rule.ruleType = type;
        rule.severity = severity;
        rule.targetTable = targetTable;
        rule.targetColumn = targetColumn;
        rule.createdBy = createdBy;

        try {
            if (ruleDefinition != null) {
                rule.ruleDefinition = objectMapper.writeValueAsString(ruleDefinition);
            }
        } catch (Exception e) {
            logger.error("Failed to serialize rule definition", e);
        }

        rule = dataQualityRuleRepo.save(rule);

        // 记录审计日志
        auditLogService.logCreate("data_quality_rule", rule.id, ruleName, createdBy, createdBy, null,
            Map.of("type", type, "severity", severity));

        logger.info("Created quality rule: {} for pipeline: {}", rule.id, pipelineId);
        return rule;
    }

    /**
     * 获取质量规则
     */
    @Transactional(readOnly = true)
    public List<DataQualityRuleEntity> getQualityRules(String gameId) {
        return dataQualityRuleRepo.findByGameId(gameId);
    }

    /**
     * 获取管道的质量规则
     */
    @Transactional(readOnly = true)
    public List<DataQualityRuleEntity> getPipelineQualityRules(String pipelineId) {
        return dataQualityRuleRepo.findByPipelineId(pipelineId);
    }

    /**
     * 定期执行调度的管道
     */
    @Scheduled(fixedDelay = 60000)  // 每分钟执行一次
    public void executeScheduledPipelines() {
        if (!scheduleEnabled) {
            return;
        }
        try {
            LocalDateTime since = LocalDateTime.now().minusMinutes(5);
            List<PipelineEntity> pipelines = pipelineRepo.findScheduledPipelines(since);

            for (PipelineEntity pipeline : pipelines) {
                try {
                    PipelineJobEntity job = new PipelineJobEntity();
                    job.id = "pjob_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
                    job.pipelineId = pipeline.id;
                    job.gameId = pipeline.gameId;
                    job.environmentId = pipeline.environmentId;
                    job.jobName = pipeline.pipelineName + " - Scheduled";
                    job.jobStatus = PipelineJobEntity.JobStatus.PENDING;
                    job.triggerType = "schedule";
                    job.triggeredBy = "system";

                    job = pipelineJobRepo.save(job);
                    executePipelineJob(job, pipeline);

                } catch (Exception e) {
                    logger.error("Failed to execute scheduled pipeline: {} - {}", pipeline.id, e.getMessage());
                }
            }

            if (!pipelines.isEmpty()) {
                logger.debug("Executed {} scheduled pipelines", pipelines.size());
            }
        } catch (Exception e) {
            logger.error("Failed to execute scheduled pipelines", e);
        }
    }

    /**
     * 定期清理旧的任务记录
     */
    @Scheduled(cron = "0 0 3 * * ?")  // 每天凌晨3点执行
    public void cleanupOldJobs() {
        try {
            LocalDateTime expireBefore = LocalDateTime.now().minusDays(90);
            int deleted = pipelineJobRepo.deleteCompletedBefore(expireBefore);

            if (deleted > 0) {
                logger.info("Cleaned up {} old pipeline jobs", deleted);
            }
        } catch (Exception e) {
            logger.error("Failed to cleanup old jobs", e);
        }
    }
}
