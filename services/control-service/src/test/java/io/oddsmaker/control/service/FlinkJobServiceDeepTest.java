package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.jpa.AuditLogEntity;
import io.oddsmaker.control.jpa.FlinkJobEntity;
import io.oddsmaker.control.jpa.FlinkJobRepo;
import io.oddsmaker.control.jpa.RiskRuleEntity;
import io.oddsmaker.control.jpa.RiskRuleRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 基础设施 Service 深度单元测试（LAY-008 拆分）：Flink 作业创建、部署与 jar 解析/默认参数、停止、状态刷新/同步、指标更新、配置与规则读取、空白与 token 侧。与 FlinkJobServiceTest / InfraServicesTest 互补。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Flink 作业 Service 深度测试（部署/停止/状态同步）")
class FlinkJobServiceDeepTest {

    @Mock
    private FlinkJobRepo flinkJobRepo;

    @Mock
    private RiskRuleRepo riskRuleRepo;

    @Mock
    private FlinkRestClient flinkRestClient;

    @Mock
    private AuditLogService auditLogService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private FlinkJobService flinkJobService;

    @BeforeEach
    void setUp() {
        lenient().when(flinkJobRepo.save(any(FlinkJobEntity.class))).thenAnswer(i -> i.getArgument(0));
    }

    private FlinkJobEntity flinkJob(String id, FlinkJobEntity.JobStatus status) {
        FlinkJobEntity job = new FlinkJobEntity();
        job.id = id;
        job.gameId = "g1";
        job.environmentId = "env1";
        job.name = "risk-job";
        job.displayName = "Risk Job";
        job.jobType = FlinkJobEntity.JobType.RISK_EVALUATION.name();
        job.parallelism = 2;
        job.status = status;
        job.createdBy = "admin";
        return job;
    }

    private Path makeJarDir(String... jarNames) throws Exception {
        Path dir = Files.createTempDirectory("flink-jars-test");
        for (String name : jarNames) {
            Files.writeString(dir.resolve(name), "fake-jar");
        }
        return dir;
    }

    private void setFlinkField(String name, Object value) throws Exception {
        Field f = FlinkJobService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(flinkJobService, value);
    }

    @Test
    @DisplayName("Flink：创建作业（重名校验、序列化、默认值、空配置）")
    void flinkCreateJob() {
        // 名称重复
        lenient().when(flinkJobRepo.findByGameIdAndName("g1", "risk-job"))
            .thenReturn(Optional.of(flinkJob("fj-existing", FlinkJobEntity.JobStatus.RUNNING)));
        assertThrows(IllegalArgumentException.class, () -> flinkJobService.createJob(
            "g1", "env1", "risk-job", "Risk Job", "d", "FRAUD_DETECTION",
            Map.of("k", "v"), Map.of("src", "kafka"), Map.of("sink", "alert"),
            List.of("r1"), 2, "admin"));

        // 正常创建（全参数）—— anyString() 覆盖后续所有名称查询（未 stub 的 Optional 返回 null 会 NPE）
        lenient().when(flinkJobRepo.findByGameIdAndName(eq("g1"), anyString())).thenReturn(Optional.empty());
        FlinkJobEntity job = flinkJobService.createJob("g1", "env1", "risk-job", "Risk Job", "d",
            "FRAUD_DETECTION", Map.of("k", "v"), Map.of("src", "kafka"), Map.of("sink", "alert"),
            List.of("r1"), 2, "admin");
        assertNotNull(job.id);
        assertTrue(job.id.startsWith("fj_"));
        assertEquals(FlinkJobEntity.JobStatus.DRAFT, job.status);
        assertEquals("FRAUD_DETECTION", job.jobType);
        assertEquals(2, job.parallelism);
        assertTrue(job.jobConfig.contains("\"k\":\"v\""));
        assertTrue(job.sourceConfig.contains("kafka"));
        assertTrue(job.sinkConfig.contains("alert"));
        assertTrue(job.ruleIds.contains("r1"));

        // 默认值 + 空配置：jobType/parallelism 兜底，null 配置不序列化
        FlinkJobEntity emptyJob = flinkJobService.createJob("g1", null, "empty-job", null, null,
            null, null, null, null, null, null, null);
        assertEquals("RISK_EVALUATION", emptyJob.jobType);
        assertEquals(1, emptyJob.parallelism);
        assertNull(emptyJob.jobConfig);
        assertNull(emptyJob.sourceConfig);
        assertNull(emptyJob.sinkConfig);
        assertNull(emptyJob.ruleIds);
    }

    @Test
    @DisplayName("Flink：部署作业（真 REST 成功、状态校验、REST 失败诚实落 FAILED）")
    void flinkDeployJob() throws Exception {
        // 不存在
        lenient().when(flinkJobRepo.findById("missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> flinkJobService.deployJob("missing", "op"));

        // 状态不可部署
        FlinkJobEntity running = flinkJob("fj-run", FlinkJobEntity.JobStatus.RUNNING);
        lenient().when(flinkJobRepo.findById("fj-run")).thenReturn(Optional.of(running));
        assertThrows(IllegalStateException.class, () -> flinkJobService.deployJob("fj-run", "op"));

        // DRAFT -> 部署成功（temp jar + mock FlinkRestClient 回真实 id）
        Path jarDirPath = makeJarDir("risk-job-0.1.0-all.jar");
        setFlinkField("jarDir", jarDirPath.toString());
        lenient().when(flinkRestClient.uploadJar(any(Path.class))).thenReturn("jar_1");
        lenient().when(flinkRestClient.launch(eq("jar_1"), anyString(), eq(2), anyString()))
            .thenReturn("job-123");
        FlinkJobEntity draft = flinkJob("fj-draft", FlinkJobEntity.JobStatus.DRAFT);
        draft.ruleIds = "[\"r1\"]";
        lenient().when(flinkJobRepo.findById("fj-draft")).thenReturn(Optional.of(draft));
        FlinkJobEntity deployed = flinkJobService.deployJob("fj-draft", "op");
        assertEquals(FlinkJobEntity.JobStatus.RUNNING, deployed.status);
        assertEquals("job-123", deployed.flinkJobId);
        assertTrue(deployed.flinkUrl.contains("/#/job/job-123"));
        assertEquals("op", deployed.updatedBy);
        assertNotNull(deployed.deployedAt);
        verify(auditLogService).log(eq(AuditLogEntity.AuditAction.ACTIVATE), eq("flink_job"), eq("fj-draft"),
            eq("risk-job"), anyString(), eq(AuditLogEntity.AuditResult.SUCCESS), eq("op"),
            isNull(), isNull(), isNull(), isNull(), anyMap());

        // 部署过程 REST 失败：标记 FAILED 并抛出（无集群形态的真实表现）
        Path jarDir2 = makeJarDir("risk-job-0.1.0-all.jar");
        setFlinkField("jarDir", jarDir2.toString());
        lenient().when(flinkRestClient.uploadJar(any(Path.class)))
            .thenThrow(new RuntimeException("Connection refused: localhost/127.0.0.1:8081"));
        FlinkJobEntity failedDraft = flinkJob("fj-fail", FlinkJobEntity.JobStatus.STOPPED);
        lenient().when(flinkJobRepo.findById("fj-fail")).thenReturn(Optional.of(failedDraft));
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> flinkJobService.deployJob("fj-fail", "op"));
        assertEquals("Failed to deploy job", ex.getMessage());
        assertEquals(FlinkJobEntity.JobStatus.FAILED, failedDraft.status);
        assertNotNull(failedDraft.errorMessage);
    }

    @Test
    @DisplayName("Flink：部署程序参数（parallelism null 兜底 1、control-token/url/rule-ids 透传）")
    void flinkDeployProgramArgsAndDefaults() throws Exception {
        Path jarDirPath = makeJarDir("risk-job-0.1.0-all.jar");
        setFlinkField("jarDir", jarDirPath.toString());
        setFlinkField("controlToken", "tk-1");
        lenient().when(flinkRestClient.uploadJar(any(Path.class))).thenReturn("jar_1");
        lenient().when(flinkRestClient.launch(anyString(), anyString(), eq(1), anyString()))
            .thenReturn("job-args");
        FlinkJobEntity draft = flinkJob("fj-args", FlinkJobEntity.JobStatus.DRAFT);
        draft.parallelism = null;
        draft.ruleIds = "[\"r1\",\"r2\"]";
        lenient().when(flinkJobRepo.findById("fj-args")).thenReturn(Optional.of(draft));

        FlinkJobEntity deployed = flinkJobService.deployJob("fj-args", "op");
        assertEquals(FlinkJobEntity.JobStatus.RUNNING, deployed.status);

        ArgumentCaptor<String> args = ArgumentCaptor.forClass(String.class);
        verify(flinkRestClient).launch(anyString(), anyString(), eq(1), args.capture());
        String programArgs = args.getValue();
        assertTrue(programArgs.contains("--job-id=fj-args"));
        assertTrue(programArgs.contains("--game-id=g1"));
        assertTrue(programArgs.contains("--environment-id=env1"));
        assertTrue(programArgs.contains("--job-type=RISK_EVALUATION"));
        assertTrue(programArgs.contains("--control.url=http://localhost:8085"));
        assertTrue(programArgs.contains("--control.token=tk-1"));
        assertTrue(programArgs.contains("--rule-ids=r1,r2"));
    }

    @Test
    @DisplayName("Flink：deploy jar 解析（多版本取最新、无映射 jobType、目录无 jar、目录不存在）")
    void flinkDeployJarResolution() throws Exception {
        Path jarDirPath = makeJarDir("risk-job-0.1.0-all.jar", "risk-job-0.2.0-all.jar");
        setFlinkField("jarDir", jarDirPath.toString());
        lenient().when(flinkRestClient.uploadJar(any(Path.class))).thenReturn("jar_x");
        lenient().when(flinkRestClient.launch(anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt(), anyString()))
            .thenReturn("job-x");
        FlinkJobEntity draft = flinkJob("fj-newest", FlinkJobEntity.JobStatus.DRAFT);
        lenient().when(flinkJobRepo.findById("fj-newest")).thenReturn(Optional.of(draft));
        flinkJobService.deployJob("fj-newest", "op");
        // 字典序最大（0.2.0）被选中上传
        verify(flinkRestClient).uploadJar(argThat((Path p) ->
            p.getFileName().toString().equals("risk-job-0.2.0-all.jar")));

        // jobType 无 jar 映射：拒绝部署且诚实落 FAILED
        Path emptyDir = makeJarDir("risk-job-0.1.0-all.jar");
        setFlinkField("jarDir", emptyDir.toString());
        lenient().when(flinkRestClient.uploadJar(any(Path.class)))
            .thenThrow(new RuntimeException("should not be reached"));
        FlinkJobEntity fraud = flinkJob("fj-fraud", FlinkJobEntity.JobStatus.DRAFT);
        fraud.jobType = "FRAUD_DETECTION";
        lenient().when(flinkJobRepo.findById("fj-fraud")).thenReturn(Optional.of(fraud));
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> flinkJobService.deployJob("fj-fraud", "op"));
        assertEquals("Failed to deploy job", ex.getMessage());
        assertTrue(fraud.errorMessage.contains("no jar mapping"));
        assertEquals(FlinkJobEntity.JobStatus.FAILED, fraud.status);

        // 目录存在但无匹配 jar
        Path bareDir = makeJarDir();
        setFlinkField("jarDir", bareDir.toString());
        FlinkJobEntity draft2 = flinkJob("fj-nojar", FlinkJobEntity.JobStatus.DRAFT);
        lenient().when(flinkJobRepo.findById("fj-nojar")).thenReturn(Optional.of(draft2));
        assertThrows(RuntimeException.class, () -> flinkJobService.deployJob("fj-nojar", "op"));
        assertTrue(draft2.errorMessage.contains("No jar matching"));

        // 目录不存在（IOException 分支）
        setFlinkField("jarDir", bareDir.resolve("missing-sub").toString());
        FlinkJobEntity draft3 = flinkJob("fj-nodir", FlinkJobEntity.JobStatus.DRAFT);
        lenient().when(flinkJobRepo.findById("fj-nodir")).thenReturn(Optional.of(draft3));
        assertThrows(RuntimeException.class, () -> flinkJobService.deployJob("fj-nodir", "op"));
        assertTrue(draft3.errorMessage.contains("Cannot list Flink jar dir"));

        // jobType 为 null：归一后同样拒绝（Map.of 不接受 null 键查询）
        FlinkJobEntity nullType = flinkJob("fj-nulltype", FlinkJobEntity.JobStatus.DRAFT);
        nullType.jobType = null;
        lenient().when(flinkJobRepo.findById("fj-nulltype")).thenReturn(Optional.of(nullType));
        RuntimeException nullEx = assertThrows(RuntimeException.class,
            () -> flinkJobService.deployJob("fj-nulltype", "op"));
        assertEquals("Failed to deploy job", nullEx.getMessage());
        assertTrue(nullType.errorMessage.contains("jobType null has no jar mapping"));
    }

    @Test
    @DisplayName("Flink：停止作业（真 cancel 成功、无 flinkJobId 跳过、cancel 失败落 FAILED）")
    void flinkStopJob() {
        // 不存在
        lenient().when(flinkJobRepo.findById("missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> flinkJobService.stopJob("missing", "op"));

        // DRAFT 不可停止
        FlinkJobEntity draft = flinkJob("fj-draft2", FlinkJobEntity.JobStatus.DRAFT);
        lenient().when(flinkJobRepo.findById("fj-draft2")).thenReturn(Optional.of(draft));
        assertThrows(IllegalStateException.class, () -> flinkJobService.stopJob("fj-draft2", "op"));

        // RUNNING + flinkJobId -> cancel 成功后停止
        FlinkJobEntity running = flinkJob("fj-stop", FlinkJobEntity.JobStatus.RUNNING);
        running.flinkJobId = "flink_xyz";
        lenient().when(flinkJobRepo.findById("fj-stop")).thenReturn(Optional.of(running));
        FlinkJobEntity stopped = flinkJobService.stopJob("fj-stop", "op");
        assertEquals(FlinkJobEntity.JobStatus.STOPPED, stopped.status);
        assertNotNull(stopped.stoppedAt);
        assertEquals("op", stopped.updatedBy);
        verify(flinkRestClient).cancel("flink_xyz");
        org.mockito.Mockito.clearInvocations(flinkRestClient); // 隔离后续 never 校验

        // RUNNING 但从未部署过（flinkJobId null）→ 不调 cancel，仅落状态（NPE 修复回归）
        FlinkJobEntity neverDeployed = flinkJob("fj-bare-stop", FlinkJobEntity.JobStatus.RUNNING);
        lenient().when(flinkJobRepo.findById("fj-bare-stop")).thenReturn(Optional.of(neverDeployed));
        FlinkJobEntity stopped2 = flinkJobService.stopJob("fj-bare-stop", "op");
        assertEquals(FlinkJobEntity.JobStatus.STOPPED, stopped2.status);
        verify(flinkRestClient, never()).cancel(anyString());

        // cancel 失败：诚实落 FAILED 并抛出（stopJob 无前置 save，catch 内的 save 走 setUp 兜底）
        FlinkJobEntity deploying = flinkJob("fj-stopfail", FlinkJobEntity.JobStatus.DEPLOYING);
        deploying.flinkJobId = "flink_dead";
        lenient().when(flinkJobRepo.findById("fj-stopfail")).thenReturn(Optional.of(deploying));
        org.mockito.Mockito.doThrow(new RuntimeException("PATCH failed: 404"))
            .when(flinkRestClient).cancel("flink_dead");
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> flinkJobService.stopJob("fj-stopfail", "op"));
        assertEquals("Failed to stop job", ex.getMessage());
        assertEquals(FlinkJobEntity.JobStatus.FAILED, deploying.status);
    }

    @Test
    @DisplayName("Flink：refreshJobStatus（漂移回写+审计、一致不动、集群查无落 FAILED、无 flinkJobId 拒绝）")
    void flinkRefreshJobStatus() {
        // 不存在
        lenient().when(flinkJobRepo.findById("missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> flinkJobService.refreshJobStatus("missing"));

        // 从未部署过（flinkJobId 空白）
        FlinkJobEntity undeployed = flinkJob("fj-undeployed", FlinkJobEntity.JobStatus.DRAFT);
        lenient().when(flinkJobRepo.findById("fj-undeployed")).thenReturn(Optional.of(undeployed));
        assertThrows(IllegalStateException.class, () -> flinkJobService.refreshJobStatus("fj-undeployed"));

        // 状态漂移：STOPPED -> RUNNING，回写并审计
        FlinkJobEntity drifted = flinkJob("fj-drift", FlinkJobEntity.JobStatus.STOPPED);
        drifted.flinkJobId = "flink_1";
        lenient().when(flinkJobRepo.findById("fj-drift")).thenReturn(Optional.of(drifted));
        lenient().when(flinkRestClient.jobState("flink_1")).thenReturn("RUNNING");
        FlinkJobEntity refreshed = flinkJobService.refreshJobStatus("fj-drift");
        assertEquals(FlinkJobEntity.JobStatus.RUNNING, refreshed.status);
        verify(auditLogService).log(eq(AuditLogEntity.AuditAction.UPDATE), eq("flink_job"), eq("fj-drift"),
            eq("risk-job"), anyString(), eq(AuditLogEntity.AuditResult.SUCCESS), eq("system"),
            isNull(), isNull(), isNull(), isNull(), anyMap());

        // 状态一致：不审计
        lenient().when(flinkJobRepo.findById("fj-drift")).thenReturn(Optional.of(drifted));
        org.mockito.Mockito.clearInvocations(auditLogService);
        flinkJobService.refreshJobStatus("fj-drift");
        verify(auditLogService, never()).log(eq(AuditLogEntity.AuditAction.UPDATE), anyString(),
            anyString(), anyString(), anyString(), any(), anyString(),
            isNull(), isNull(), isNull(), isNull(), anyMap());

        // 集群查无此作业（404 → null）：诚实置 FAILED
        FlinkJobEntity gone = flinkJob("fj-gone", FlinkJobEntity.JobStatus.RUNNING);
        gone.flinkJobId = "flink_gone";
        lenient().when(flinkJobRepo.findById("fj-gone")).thenReturn(Optional.of(gone));
        lenient().when(flinkRestClient.jobState("flink_gone")).thenReturn(null);
        FlinkJobEntity goneResult = flinkJobService.refreshJobStatus("fj-gone");
        assertEquals(FlinkJobEntity.JobStatus.FAILED, goneResult.status);
        assertTrue(goneResult.errorMessage.contains("no longer exists on cluster"));
    }

    @Test
    @DisplayName("Flink：定时状态对账（enabled 门、逐作业隔离、集群失联不置 FAILED）")
    void flinkScheduledStatusSync() throws Exception {
        // 默认关闭：不查不刷
        flinkJobService.scheduledStatusSync();
        verify(flinkJobRepo, never()).findByStatusInAndDeletedAtIsNull(any());

        // 开启后对账 RUNNING/DEPLOYING/STOPPING
        setFlinkField("statusSyncEnabled", true);
        FlinkJobEntity running = flinkJob("fj-sync1", FlinkJobEntity.JobStatus.RUNNING);
        running.flinkJobId = "flink_1";
        FlinkJobEntity deploying = flinkJob("fj-sync2", FlinkJobEntity.JobStatus.DEPLOYING);
        deploying.flinkJobId = "flink_2";
        FlinkJobEntity broken = flinkJob("fj-sync3", FlinkJobEntity.JobStatus.RUNNING);
        broken.flinkJobId = "flink_3";
        lenient().when(flinkJobRepo.findByStatusInAndDeletedAtIsNull(any()))
            .thenReturn(List.of(running, deploying, broken));
        lenient().when(flinkJobRepo.findById("fj-sync1")).thenReturn(Optional.of(running));
        lenient().when(flinkJobRepo.findById("fj-sync2")).thenReturn(Optional.of(deploying));
        lenient().when(flinkJobRepo.findById("fj-sync3")).thenReturn(Optional.empty());
        lenient().when(flinkRestClient.jobState("flink_1")).thenReturn("FINISHED");
        // 第二个作业集群失联：只告警，不影响第一个，也不批量置 FAILED
        lenient().when(flinkRestClient.jobState("flink_2"))
            .thenThrow(new org.springframework.web.client.ResourceAccessException("connect timed out"));

        flinkJobService.scheduledStatusSync();

        // FINISHED 是集群态，落库映射为 STOPPED；失联的 deploying 保持原状无错误信息
        assertEquals(FlinkJobEntity.JobStatus.STOPPED, running.status);
        assertEquals(FlinkJobEntity.JobStatus.DEPLOYING, deploying.status);
        assertNull(deploying.errorMessage);
        // 第三个作业刷新抛非失联异常（记录不存在）：被逐作业泛 catch 吞掉，状态原样
        assertEquals(FlinkJobEntity.JobStatus.RUNNING, broken.status);
        assertNull(broken.errorMessage);
    }

    @Test
    @DisplayName("Flink：更新作业指标与获取作业")
    void flinkUpdateJobMetrics() {
        // 作业不存在：静默返回
        lenient().when(flinkJobRepo.findById("missing")).thenReturn(Optional.empty());
        assertDoesNotThrow(() ->
            flinkJobService.updateJobMetrics("missing", 1L, 1L, 1L));
        verify(flinkJobRepo, never()).save(any(FlinkJobEntity.class));

        // 作业存在：更新指标
        FlinkJobEntity job = flinkJob("fj-m", FlinkJobEntity.JobStatus.RUNNING);
        lenient().when(flinkJobRepo.findById("fj-m")).thenReturn(Optional.of(job));
        flinkJobService.updateJobMetrics("fj-m", 100L, 10L, 5L);
        assertEquals(100L, job.totalEventsProcessed);
        assertEquals(10L, job.totalRiskCasesCreated);
        assertEquals(5L, job.totalActionsExecuted);
        assertNotNull(job.lastMetricsUpdate);

        // getJob 不存在
        assertThrows(IllegalArgumentException.class, () -> flinkJobService.getJob("missing"));

        // getJob 成功路径
        assertEquals("fj-m", flinkJobService.getJob("fj-m").id);
    }

    @Test
    @DisplayName("Flink：getJobConfig 解析（成功与坏 JSON）")
    void flinkGetJobConfig() {
        // 不存在
        lenient().when(flinkJobRepo.findById("missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> flinkJobService.getJobConfig("missing"));

        // 完整配置解析
        FlinkJobEntity job = flinkJob("fj-cfg", FlinkJobEntity.JobStatus.RUNNING);
        job.jobConfig = "{\"k\":\"v\"}";
        job.sourceConfig = "{\"src\":\"kafka\"}";
        job.sinkConfig = "{\"sink\":\"alert\"}";
        job.ruleIds = "[\"r1\",\"r2\"]";
        lenient().when(flinkJobRepo.findById("fj-cfg")).thenReturn(Optional.of(job));
        Map<String, Object> config = flinkJobService.getJobConfig("fj-cfg");
        assertEquals("fj-cfg", config.get("jobId"));
        assertEquals("g1", config.get("gameId"));
        assertEquals("env1", config.get("environmentId"));
        assertEquals(2, config.get("parallelism"));
        assertEquals(Map.of("k", "v"), config.get("jobConfig"));
        assertEquals(List.of("r1", "r2"), config.get("ruleIds"));

        // 空 job（null 配置）
        FlinkJobEntity bare = flinkJob("fj-bare", FlinkJobEntity.JobStatus.DRAFT);
        lenient().when(flinkJobRepo.findById("fj-bare")).thenReturn(Optional.of(bare));
        Map<String, Object> bareConfig = flinkJobService.getJobConfig("fj-bare");
        assertFalse(bareConfig.containsKey("jobConfig"));

        // 坏 JSON：解析失败仅记录日志
        FlinkJobEntity broken = flinkJob("fj-bad", FlinkJobEntity.JobStatus.DRAFT);
        broken.jobConfig = "{not-valid-json";
        broken.ruleIds = "not-a-json";
        lenient().when(flinkJobRepo.findById("fj-bad")).thenReturn(Optional.of(broken));
        Map<String, Object> brokenConfig = flinkJobService.getJobConfig("fj-bad");
        assertFalse(brokenConfig.containsKey("jobConfig"));
        assertFalse(brokenConfig.containsKey("ruleIds"));
    }

    @Test
    @DisplayName("Flink：getJobRules 规则解析（空/部分命中/坏 JSON）")
    void flinkGetJobRules() {
        // 不存在
        lenient().when(flinkJobRepo.findById("missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> flinkJobService.getJobRules("missing"));

        // ruleIds 为 null
        FlinkJobEntity noRules = flinkJob("fj-norules", FlinkJobEntity.JobStatus.RUNNING);
        lenient().when(flinkJobRepo.findById("fj-norules")).thenReturn(Optional.of(noRules));
        assertEquals(List.of(), flinkJobService.getJobRules("fj-norules"));

        // 部分规则命中
        FlinkJobEntity job = flinkJob("fj-rules", FlinkJobEntity.JobStatus.RUNNING);
        job.ruleIds = "[\"r1\",\"missing-rule\"]";
        lenient().when(flinkJobRepo.findById("fj-rules")).thenReturn(Optional.of(job));
        RiskRuleEntity rule = new RiskRuleEntity();
        rule.id = "r1";
        lenient().when(riskRuleRepo.findById("r1")).thenReturn(Optional.of(rule));
        lenient().when(riskRuleRepo.findById("missing-rule")).thenReturn(Optional.empty());
        List<RiskRuleEntity> rules = flinkJobService.getJobRules("fj-rules");
        assertEquals(1, rules.size());
        assertEquals("r1", rules.get(0).id);

        // 坏 JSON：返回空列表
        FlinkJobEntity broken = flinkJob("fj-badrules", FlinkJobEntity.JobStatus.RUNNING);
        broken.ruleIds = "not-a-json";
        lenient().when(flinkJobRepo.findById("fj-badrules")).thenReturn(Optional.of(broken));
        assertEquals(List.of(), flinkJobService.getJobRules("fj-badrules"));
    }

    @Test
    @DisplayName("Flink：blank flinkJobId（stop 不 cancel、refresh 拒绝）+ controlToken/ruleIds 空 Triple 侧")
    void flinkJobBlankAndTokenSides() throws Exception {
        // 203 行 isBlank 侧：blank flinkJobId → 不调 cancel 仅落状态
        FlinkJobEntity blankFlink = flinkJob("fj-blank-stop", FlinkJobEntity.JobStatus.RUNNING);
        blankFlink.flinkJobId = "   ";
        lenient().when(flinkJobRepo.findById("fj-blank-stop")).thenReturn(Optional.of(blankFlink));
        FlinkJobEntity stopped = flinkJobService.stopJob("fj-blank-stop", "op");
        assertEquals(FlinkJobEntity.JobStatus.STOPPED, stopped.status);
        verify(flinkRestClient, never()).cancel(any());

        // 249 行 isBlank 侧
        FlinkJobEntity blankFlink2 = flinkJob("fj-blank-refresh", FlinkJobEntity.JobStatus.RUNNING);
        blankFlink2.flinkJobId = "   ";
        lenient().when(flinkJobRepo.findById("fj-blank-refresh")).thenReturn(Optional.of(blankFlink2));
        assertThrows(IllegalStateException.class,
            () -> flinkJobService.refreshJobStatus("fj-blank-refresh"));

        // 450 行 true 侧：controlToken 非空 → programArgs 带 token；458 行空 ruleIds 侧
        Path jarDirPath = makeJarDir("risk-job-0.1.0-all.jar");
        setFlinkField("jarDir", jarDirPath.toString());
        org.springframework.test.util.ReflectionTestUtils.setField(flinkJobService, "controlToken", "tok123");
        lenient().when(flinkRestClient.uploadJar(any(Path.class))).thenReturn("jar_1");
        lenient().when(flinkRestClient.launch(eq("jar_1"), anyString(), eq(2), anyString()))
            .thenReturn("job-9");
        FlinkJobEntity draft2 = flinkJob("fj-draft-empty-rules", FlinkJobEntity.JobStatus.DRAFT);
        draft2.ruleIds = "[]";
        lenient().when(flinkJobRepo.findById("fj-draft-empty-rules")).thenReturn(Optional.of(draft2));
        flinkJobService.deployJob("fj-draft-empty-rules", "op");
        org.mockito.ArgumentCaptor<String> argsCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(flinkRestClient).launch(eq("jar_1"), anyString(), eq(2), argsCaptor.capture());
        assertTrue(argsCaptor.getValue().contains("--control.token=tok123"));
        assertFalse(argsCaptor.getValue().contains("--rule-ids="));
    }

}
