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
     * 模拟执行的随机源。可注入以便单测复现失败/停止分支——
     * 不可对 Math 做 mockStatic（与 JaCoCo 对 java.lang 的插桩冲突，VerifyError 打死测试进程）。
     */
    java.util.function.DoubleSupplier randomness = Math::random;

    @Autowired
    private PipelineRepo pipelineRepo;

    @Autowired
    private PipelineJobRepo pipelineJobRepo;

    @Autowired
    private DataQualityRuleRepo dataQualityRuleRepo;

    @Autowired
    private AuditLogService auditLogService;

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

    private static final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

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
     * 执行管道任务
     */
    private void executePipelineJob(PipelineJobEntity job, PipelineEntity pipeline) {
        job.start();

        try {
            // 模拟管道执行
            Thread.sleep(100);

            // 执行数据质量检查
            List<DataQualityRuleEntity> rules = dataQualityRuleRepo.findByPipelineId(pipeline.id)
                .stream()
                .filter(DataQualityRuleEntity::isActive)
                .collect(Collectors.toList());

            boolean qualityPassed = true;
            for (DataQualityRuleEntity rule : rules) {
                boolean passed = evaluateQualityRule(rule);
                if (!passed && rule.shouldStopOnFailure()) {
                    qualityPassed = false;
                    break;
                }
            }

            long processedRows = (long) (randomness.getAsDouble() * 100000) + 1000;
            long errorRows = qualityPassed ? 0 : (long) (randomness.getAsDouble() * 100);

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

    /**
     * 评估质量规则
     */
    private boolean evaluateQualityRule(DataQualityRuleEntity rule) {
        // 模拟质量检查
        boolean passed = randomness.getAsDouble() > 0.1;  // 90%通过率
        int violationCount = passed ? 0 : (int) (randomness.getAsDouble() * 100) + 1;

        rule.recordEvaluation(passed, violationCount);
        dataQualityRuleRepo.save(rule);

        logger.debug("Quality rule evaluation: {} - passed: {}, violations: {}",
            rule.ruleName, passed, violationCount);

        return passed;
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
