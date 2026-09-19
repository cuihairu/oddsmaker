package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.jpa.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Flink作业管理服务
 * 管理实时风险控制作业的生命周期。
 *
 * deploy/stop 走真实 Flink REST（FlinkRestClient，面向 JobManager REST 口）：
 * 平台当前无 session cluster（compose 为 local executor 直跑），REST 不可达时
 * deploy 诚实落 FAILED（取代历史"假成功"）。jobType → jar 映射仅 RISK_EVALUATION 有
 * 真实 fat jar（risk-job-*-all.jar）；定时状态对账默认关闭（oddsmaker.flink.status-sync-enabled）。
 */
@Service
@Transactional
public class FlinkJobService {

    private static final Logger logger = LoggerFactory.getLogger(FlinkJobService.class);

    /** jobType → jar 目录内 glob（文件名含版本号，取字典序最新）；其余 JobType 无真实对应拒绝部署 */
    private static final Map<String, String> JAR_GLOBS = Map.of(
        FlinkJobEntity.JobType.RISK_EVALUATION.name(), "glob:risk-job-*-all.jar");

    @Autowired
    private FlinkJobRepo flinkJobRepo;

    @Autowired
    private RiskRuleRepo riskRuleRepo;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private FlinkRestClient flinkRestClient;

    @Value("${oddsmaker.flink.rest.url:http://localhost:8081}")
    private String flinkRestUrl = "http://localhost:8081";

    @Value("${oddsmaker.flink.jar-dir:deploy-rt/jars}")
    private String jarDir = "deploy-rt/jars";

    @Value("${oddsmaker.flink.entry-class:io.oddsmaker.jobs.risk.RiskJob}")
    private String entryClass = "io.oddsmaker.jobs.risk.RiskJob";

    @Value("${oddsmaker.flink.control-url:http://localhost:8085}")
    private String controlUrl = "http://localhost:8085";

    @Value("${oddsmaker.flink.control-token:}")
    private String controlToken = "";

    @Value("${oddsmaker.flink.status-sync-enabled:false}")
    private boolean statusSyncEnabled = false;

    /**
     * 创建Flink作业
     */
    public FlinkJobEntity createJob(String gameId, String environmentId, String name, String displayName,
                                    String description, String jobType, Map<String, Object> jobConfig,
                                    Map<String, Object> sourceConfig, Map<String, Object> sinkConfig,
                                    List<String> ruleIds, Integer parallelism, String createdBy) {

        // 检查名称唯一性
        var existing = flinkJobRepo.findByGameIdAndName(gameId, name);
        if (existing.isPresent()) {
            throw new IllegalArgumentException("Job name already exists: " + name);
        }

        FlinkJobEntity job = new FlinkJobEntity();
        job.id = "fj_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        job.gameId = gameId;
        job.environmentId = environmentId;
        job.name = name;
        job.displayName = displayName;
        job.description = description;
        job.jobType = jobType != null ? jobType : FlinkJobEntity.JobType.RISK_EVALUATION.name();
        job.parallelism = parallelism != null ? parallelism : 1;
        job.status = FlinkJobEntity.JobStatus.DRAFT;
        job.createdBy = createdBy;

        try {
            if (jobConfig != null) {
                job.jobConfig = objectMapper.writeValueAsString(jobConfig);
            }
            if (sourceConfig != null) {
                job.sourceConfig = objectMapper.writeValueAsString(sourceConfig);
            }
            if (sinkConfig != null) {
                job.sinkConfig = objectMapper.writeValueAsString(sinkConfig);
            }
            if (ruleIds != null && !ruleIds.isEmpty()) {
                job.ruleIds = objectMapper.writeValueAsString(ruleIds);
            }
        } catch (Exception e) {
            logger.error("Failed to serialize job config", e);
            throw new RuntimeException("Failed to serialize job config", e);
        }

        job = flinkJobRepo.save(job);

        // 记录审计日志
        auditLogService.logCreate("flink_job", job.id, name, createdBy, null, null,
            Map.of("gameId", gameId, "jobType", job.jobType));

        logger.info("Created Flink job: {} for game: {}", name, gameId);
        return job;
    }

    /**
     * 部署作业到Flink集群（真 REST：上传 jar → run → 回写真实 jobid；失败诚实落 FAILED）
     */
    public FlinkJobEntity deployJob(String jobId, String deployedBy) {
        FlinkJobEntity job = flinkJobRepo.findById(jobId)
            .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));

        if (!job.canDeploy()) {
            throw new IllegalStateException("Job cannot be deployed in current status: " + job.status);
        }

        try {
            // 更新状态为部署中
            job.status = FlinkJobEntity.JobStatus.DEPLOYING;
            job.updatedBy = deployedBy;
            flinkJobRepo.save(job);

            // 解析 jar / 构建程序参数（RiskJob 只读系统属性，--key=value 由其 parseArgs 落入）
            Path jarPath = resolveJobJar(job.jobType);
            String programArgs = buildProgramArgs(job);
            int parallelism = job.parallelism != null ? job.parallelism : 1;

            // 调用Flink REST API提交作业
            String jarId = flinkRestClient.uploadJar(jarPath);
            String flinkJobId = flinkRestClient.launch(jarId, entryClass, parallelism, programArgs);
            String flinkUrl = flinkRestUrl + "/#/job/" + flinkJobId;

            // 更新作业状态
            job.markAsDeployed(flinkJobId, flinkUrl);
            job.updatedBy = deployedBy;
            flinkJobRepo.save(job);

            // 记录审计日志
            auditLogService.log(
                AuditLogEntity.AuditAction.ACTIVATE,
                "flink_job",
                job.id,
                job.name,
                "Deployed Flink job: " + flinkJobId,
                AuditLogEntity.AuditResult.SUCCESS,
                deployedBy,
                null,
                null,
                null,
                null,
                Map.of("flinkJobId", flinkJobId, "flinkUrl", flinkUrl, "jarId", jarId)
            );

            logger.info("Deployed Flink job {} with Flink Job ID: {}", job.name, flinkJobId);
            return job;

        } catch (Exception e) {
            job.markAsFailed(e.getMessage());
            flinkJobRepo.save(job);
            logger.error("Failed to deploy Flink job {}: {}", job.name, e.getMessage(), e);
            throw new RuntimeException("Failed to deploy job", e);
        }
    }

    /**
     * 停止作业（flinkJobId 存在时真 PATCH cancel；取消失败落 FAILED）
     */
    public FlinkJobEntity stopJob(String jobId, String stoppedBy) {
        FlinkJobEntity job = flinkJobRepo.findById(jobId)
            .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));

        if (!job.canStop()) {
            throw new IllegalStateException("Job cannot be stopped in current status: " + job.status);
        }

        try {
            // 调用Flink REST API停止作业（未部署过则仅落状态）
            if (job.flinkJobId != null && !job.flinkJobId.isBlank()) {
                flinkRestClient.cancel(job.flinkJobId);
            }

            job.markAsStopped();
            job.updatedBy = stoppedBy;
            flinkJobRepo.save(job);

            // 记录审计日志（flinkJobId 可能为 null——Map.of 不允许 null 值，须判空）
            Map<String, Object> metadata = job.flinkJobId != null
                ? Map.of("flinkJobId", job.flinkJobId)
                : Map.of();
            auditLogService.log(
                AuditLogEntity.AuditAction.DEACTIVATE,
                "flink_job",
                job.id,
                job.name,
                "Stopped Flink job",
                AuditLogEntity.AuditResult.SUCCESS,
                stoppedBy,
                null,
                null,
                null,
                null,
                metadata
            );

            logger.info("Stopped Flink job: {}", job.name);
            return job;

        } catch (Exception e) {
            job.markAsFailed(e.getMessage());
            flinkJobRepo.save(job);
            logger.error("Failed to stop Flink job {}: {}", job.name, e.getMessage(), e);
            throw new RuntimeException("Failed to stop job", e);
        }
    }

    /**
     * 从集群同步作业状态（手动/定时共用）：集群查无此作业（404）诚实置 FAILED；
     * 状态漂移回写并审计；一致则不动。
     */
    public FlinkJobEntity refreshJobStatus(String jobId) {
        FlinkJobEntity job = flinkJobRepo.findById(jobId)
            .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));

        if (job.flinkJobId == null || job.flinkJobId.isBlank()) {
            throw new IllegalStateException("Job has no Flink job id (never deployed): " + jobId);
        }

        String state = flinkRestClient.jobState(job.flinkJobId);
        FlinkJobEntity.JobStatus mapped = FlinkRestClient.mapState(state);
        if (mapped == null) {
            job.markAsFailed("job no longer exists on cluster (state=" + state + ")");
            flinkJobRepo.save(job);
            logger.warn("Flink job {} ({}) not found on cluster, marked FAILED", job.name, job.flinkJobId);
            return job;
        }

        if (job.status != mapped) {
            FlinkJobEntity.JobStatus from = job.status;
            job.status = mapped;
            flinkJobRepo.save(job);
            auditLogService.log(
                AuditLogEntity.AuditAction.UPDATE,
                "flink_job",
                job.id,
                job.name,
                "Status synced from Flink cluster: " + from + " -> " + mapped,
                AuditLogEntity.AuditResult.SUCCESS,
                "system",
                null,
                null,
                null,
                null,
                Map.of("from", from.name(), "to", mapped.name(), "flinkState", state)
            );
        }
        return job;
    }

    /**
     * 定时状态对账：仅 enabled 开启时执行（默认关）。逐作业 try-catch 隔离，
     * 集群失联（ResourceAccessException）只告警不置 FAILED——集群 down ≠ 作业死了。
     */
    @Scheduled(fixedDelayString = "${oddsmaker.flink.status-sync-interval-ms:60000}")
    public void scheduledStatusSync() {
        if (!statusSyncEnabled) {
            return;
        }
        List<FlinkJobEntity> active = flinkJobRepo.findByStatusInAndDeletedAtIsNull(
            List.of(FlinkJobEntity.JobStatus.RUNNING,
                FlinkJobEntity.JobStatus.DEPLOYING,
                FlinkJobEntity.JobStatus.STOPPING));
        for (FlinkJobEntity job : active) {
            try {
                refreshJobStatus(job.id);
            } catch (ResourceAccessException e) {
                logger.warn("Flink cluster unreachable, skip status sync for {}: {}", job.id, e.getMessage());
            } catch (Exception e) {
                logger.error("Status sync failed for {}: {}", job.id, e.getMessage());
            }
        }
    }

    /**
     * 更新作业指标
     */
    public void updateJobMetrics(String jobId, Long eventsProcessed, Long casesCreated, Long actionsExecuted) {
        FlinkJobEntity job = flinkJobRepo.findById(jobId).orElse(null);
        if (job == null) {
            logger.warn("Job not found for metrics update: {}", jobId);
            return;
        }

        job.updateMetrics(eventsProcessed, casesCreated, actionsExecuted);
        flinkJobRepo.save(job);
    }

    /**
     * 获取作业详情
     */
    @Transactional(readOnly = true)
    public FlinkJobEntity getJob(String jobId) {
        return flinkJobRepo.findById(jobId)
            .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));
    }

    /**
     * 获取游戏的作业列表
     */
    @Transactional(readOnly = true)
    public List<FlinkJobEntity> getGameJobs(String gameId) {
        return flinkJobRepo.findByGameId(gameId);
    }

    /**
     * 获取运行中的作业
     */
    @Transactional(readOnly = true)
    public List<FlinkJobEntity> getRunningJobs(String gameId) {
        return flinkJobRepo.findRunningJobs(gameId);
    }

    /**
     * 获取作业统计信息
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getJobStats(String gameId) {
        List<FlinkJobEntity> allJobs = flinkJobRepo.findByGameId(gameId);
        List<FlinkJobEntity> runningJobs = flinkJobRepo.findRunningJobs(gameId);

        long totalEventsProcessed = allJobs.stream()
            .mapToLong(j -> j.totalEventsProcessed != null ? j.totalEventsProcessed : 0)
            .sum();

        long totalRiskCasesCreated = allJobs.stream()
            .mapToLong(j -> j.totalRiskCasesCreated != null ? j.totalRiskCasesCreated : 0)
            .sum();

        return Map.of(
            "totalJobs", allJobs.size(),
            "runningJobs", runningJobs.size(),
            "stoppedJobs", allJobs.stream().filter(j -> j.isStopped()).count(),
            "failedJobs", allJobs.stream().filter(j -> j.status == FlinkJobEntity.JobStatus.FAILED).count(),
            "totalEventsProcessed", totalEventsProcessed,
            "totalRiskCasesCreated", totalRiskCasesCreated,
            "overallRiskCaseRate", totalEventsProcessed > 0 ? (double) totalRiskCasesCreated / totalEventsProcessed : 0.0
        );
    }

    /**
     * 获取作业配置（用于生成Flink作业）
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getJobConfig(String jobId) {
        FlinkJobEntity job = flinkJobRepo.findById(jobId)
            .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));

        Map<String, Object> config = new HashMap<>();
        config.put("jobId", job.id);
        config.put("gameId", job.gameId);
        config.put("environmentId", job.environmentId);
        config.put("jobType", job.jobType);
        config.put("parallelism", job.parallelism);

        try {
            if (job.jobConfig != null) {
                config.put("jobConfig", objectMapper.readValue(job.jobConfig, Map.class));
            }
            if (job.sourceConfig != null) {
                config.put("sourceConfig", objectMapper.readValue(job.sourceConfig, Map.class));
            }
            if (job.sinkConfig != null) {
                config.put("sinkConfig", objectMapper.readValue(job.sinkConfig, Map.class));
            }
            if (job.ruleIds != null) {
                config.put("ruleIds", objectMapper.readValue(job.ruleIds, List.class));
            }
        } catch (Exception e) {
            logger.error("Failed to parse job config", e);
        }

        return config;
    }

    // 私有辅助方法

    /**
     * 按 jobType 解析要上传的 jar：在 jar 目录内按 glob 匹配，文件名含版本号取字典序最新。
     * 仅 RISK_EVALUATION 有真实 fat jar（risk-job），其余 JobType 拒绝部署。
     */
    Path resolveJobJar(String jobType) {
        // Map.of 不接受 null 键查询（会 NPE），先做 null 归一
        String glob = jobType == null ? null : JAR_GLOBS.get(jobType);
        if (glob == null) {
            throw new IllegalArgumentException("jobType " + jobType + " has no jar mapping");
        }
        Path dir = Path.of(jarDir);
        FileSystem fs = FileSystems.getDefault();
        PathMatcher matcher = fs.getPathMatcher(glob);
        List<Path> matches;
        try (Stream<Path> entries = Files.list(dir)) {
            matches = entries.filter(p -> matcher.matches(p.getFileName())).collect(Collectors.toList());
        } catch (IOException e) {
            throw new IllegalStateException(
                "Cannot list Flink jar dir " + dir.toAbsolutePath() + ": " + e.getMessage());
        }
        if (matches.isEmpty()) {
            throw new IllegalStateException(
                "No jar matching " + glob + " in " + dir.toAbsolutePath() + " (build risk-job first)");
        }
        return matches.stream()
            .max(Comparator.comparing(p -> p.getFileName().toString()))
            .orElseThrow();
    }

    private String buildProgramArgs(FlinkJobEntity job) {
        // 构建程序参数（--key=value 形态，RiskJob.parseArgs 解析后落入系统属性）
        List<String> args = new ArrayList<>();
        args.add("--job-id=" + job.id);
        args.add("--game-id=" + job.gameId);
        if (job.environmentId != null) {
            args.add("--environment-id=" + job.environmentId);
        }
        args.add("--job-type=" + job.jobType);
        args.add("--control.url=" + controlUrl);
        if (controlToken != null && !controlToken.isBlank()) {
            args.add("--control.token=" + controlToken);
        }

        // 添加规则ID（注意：RiskJob 规则实际走 RuleFetcher 按 game 拉取，此参数当前不被消费）
        if (job.ruleIds != null) {
            try {
                List<String> rules = objectMapper.readValue(job.ruleIds, List.class);
                if (!rules.isEmpty()) {
                    args.add("--rule-ids=" + String.join(",", rules));
                }
            } catch (Exception e) {
                logger.warn("Failed to parse rule IDs", e);
            }
        }

        return String.join(" ", args);
    }

    /**
     * 获取作业的关联规则
     */
    @Transactional(readOnly = true)
    public List<RiskRuleEntity> getJobRules(String jobId) {
        FlinkJobEntity job = flinkJobRepo.findById(jobId)
            .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));

        if (job.ruleIds == null) {
            return List.of();
        }

        try {
            List<String> ruleIds = objectMapper.readValue(job.ruleIds, List.class);
            return ruleIds.stream()
                .map(ruleId -> riskRuleRepo.findById(ruleId).orElse(null))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("Failed to parse rule IDs", e);
            return List.of();
        }
    }
}
