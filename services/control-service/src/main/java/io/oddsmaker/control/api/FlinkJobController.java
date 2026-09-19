package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.FlinkJobEntity;
import io.oddsmaker.control.jpa.RiskRuleEntity;
import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.FlinkJobService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Flink作业API控制器
 * 提供Flink作业管理的API接口；鉴权走 AccessGuard 行内风格（flink:read / flink:manage）。
 * 历史形态为 @PreAuthorize hasAuthority('READ_GAME:'+gameId)，而全仓只签发 ROLE_* authority，
 * 方法安全开启后这些注解恒 403——故换成权限种子（V0.9.7）+ AccessGuard 解析。
 */
@RestController
@RequestMapping("/api/flink-jobs")
public class FlinkJobController {

    @Autowired
    private FlinkJobService flinkJobService;

    @Autowired
    private AccessGuard accessGuard;

    /**
     * 创建Flink作业
     */
    @PostMapping
    public ResponseEntity<FlinkJobEntity> createJob(@RequestBody FlinkJobRequest request) {
        accessGuard.requireGamePermission(request.gameId, "flink:manage");
        FlinkJobEntity job = flinkJobService.createJob(
            request.gameId,
            request.environmentId,
            request.name,
            request.displayName,
            request.description,
            request.jobType,
            request.jobConfig,
            request.sourceConfig,
            request.sinkConfig,
            request.ruleIds,
            request.parallelism,
            currentOperator()
        );
        return ResponseEntity.ok(job);
    }

    /**
     * 获取作业详情
     */
    @GetMapping("/{jobId}")
    public ResponseEntity<FlinkJobEntity> getJob(@PathVariable String jobId, @RequestParam String gameId) {
        accessGuard.requireGamePermission(gameId, "flink:read");
        FlinkJobEntity job = flinkJobService.getJob(jobId);
        return ResponseEntity.ok(job);
    }

    /**
     * 获取游戏的作业列表
     */
    @GetMapping("/game/{gameId}")
    public ResponseEntity<List<FlinkJobEntity>> getGameJobs(@PathVariable String gameId) {
        accessGuard.requireGamePermission(gameId, "flink:read");
        List<FlinkJobEntity> jobs = flinkJobService.getGameJobs(gameId);
        return ResponseEntity.ok(jobs);
    }

    /**
     * 获取运行中的作业
     */
    @GetMapping("/running/{gameId}")
    public ResponseEntity<List<FlinkJobEntity>> getRunningJobs(@PathVariable String gameId) {
        accessGuard.requireGamePermission(gameId, "flink:read");
        List<FlinkJobEntity> jobs = flinkJobService.getRunningJobs(gameId);
        return ResponseEntity.ok(jobs);
    }

    /**
     * 部署作业（真 REST 提交；操作者取认证主体）
     */
    @PostMapping("/{jobId}/deploy")
    public ResponseEntity<FlinkJobEntity> deployJob(
            @PathVariable String jobId,
            @RequestParam String gameId,
            @RequestBody(required = false) DeployRequest request) {
        accessGuard.requireGamePermission(gameId, "flink:manage");
        FlinkJobEntity job = flinkJobService.deployJob(jobId, currentOperator());
        return ResponseEntity.ok(job);
    }

    /**
     * 停止作业（真 REST cancel；操作者取认证主体）
     */
    @PostMapping("/{jobId}/stop")
    public ResponseEntity<FlinkJobEntity> stopJob(
            @PathVariable String jobId,
            @RequestParam String gameId,
            @RequestBody(required = false) StopRequest request) {
        accessGuard.requireGamePermission(gameId, "flink:manage");
        FlinkJobEntity job = flinkJobService.stopJob(jobId, currentOperator());
        return ResponseEntity.ok(job);
    }

    /**
     * 从 Flink 集群同步作业状态（查无作业诚实置 FAILED / 漂移回写并审计）
     */
    @PostMapping("/{jobId}/refresh")
    public ResponseEntity<FlinkJobEntity> refreshJob(
            @PathVariable String jobId,
            @RequestParam String gameId) {
        accessGuard.requireGamePermission(gameId, "flink:manage");
        return ResponseEntity.ok(flinkJobService.refreshJobStatus(jobId));
    }

    /**
     * 获取作业统计
     */
    @GetMapping("/stats/{gameId}")
    public ResponseEntity<Map<String, Object>> getJobStats(@PathVariable String gameId) {
        accessGuard.requireGamePermission(gameId, "flink:read");
        Map<String, Object> stats = flinkJobService.getJobStats(gameId);
        return ResponseEntity.ok(stats);
    }

    /**
     * 获取作业配置
     */
    @GetMapping("/{jobId}/config")
    public ResponseEntity<Map<String, Object>> getJobConfig(
            @PathVariable String jobId,
            @RequestParam String gameId) {
        accessGuard.requireGamePermission(gameId, "flink:read");
        Map<String, Object> config = flinkJobService.getJobConfig(jobId);
        return ResponseEntity.ok(config);
    }

    /**
     * 获取作业的关联规则
     */
    @GetMapping("/{jobId}/rules")
    public ResponseEntity<List<RiskRuleEntity>> getJobRules(
            @PathVariable String jobId,
            @RequestParam String gameId) {
        accessGuard.requireGamePermission(gameId, "flink:read");
        List<RiskRuleEntity> rules = flinkJobService.getJobRules(jobId);
        return ResponseEntity.ok(rules);
    }

    /**
     * 更新作业指标
     */
    @PostMapping("/{jobId}/metrics")
    public ResponseEntity<Void> updateMetrics(
            @PathVariable String jobId,
            @RequestParam String gameId,
            @RequestBody MetricsUpdateRequest request) {
        accessGuard.requireGamePermission(gameId, "flink:manage");
        flinkJobService.updateJobMetrics(jobId, request.eventsProcessed, request.casesCreated, request.actionsExecuted);
        return ResponseEntity.ok().build();
    }

    private String currentOperator() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
            .getContext().getAuthentication();
        return auth != null ? auth.getName() : "api";
    }

    // Request DTOs

    public static class FlinkJobRequest {
        public String gameId;
        public String environmentId;
        public String name;
        public String displayName;
        public String description;
        public String jobType;
        public Map<String, Object> jobConfig;
        public Map<String, Object> sourceConfig;
        public Map<String, Object> sinkConfig;
        public List<String> ruleIds;
        public Integer parallelism;
        public String createdBy;
    }

    public static class DeployRequest {
        public String deployedBy;
    }

    public static class StopRequest {
        public String stoppedBy;
    }

    public static class MetricsUpdateRequest {
        public Long eventsProcessed;
        public Long casesCreated;
        public Long actionsExecuted;
    }
}
