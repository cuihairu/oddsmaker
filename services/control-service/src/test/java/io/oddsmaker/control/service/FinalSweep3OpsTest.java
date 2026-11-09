package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.jpa.AuditLogEntity;
import io.oddsmaker.control.jpa.AuditLogRepo;
import io.oddsmaker.control.jpa.DataQualityRuleEntity;
import io.oddsmaker.control.jpa.DataQualityRuleRepo;
import io.oddsmaker.control.jpa.ExportJobEntity;
import io.oddsmaker.control.jpa.ExportJobRepo;
import io.oddsmaker.control.jpa.HealthCheckEntity;
import io.oddsmaker.control.jpa.HealthCheckRepo;
import io.oddsmaker.control.jpa.HealthMetricRepo;
import io.oddsmaker.control.jpa.PipelineEntity;
import io.oddsmaker.control.jpa.PipelineJobEntity;
import io.oddsmaker.control.jpa.PipelineJobRepo;
import io.oddsmaker.control.jpa.PipelineRepo;
import io.oddsmaker.control.jpa.SDKKeyEntity;
import io.oddsmaker.control.jpa.SDKKeyRepo;
import io.oddsmaker.control.jpa.SDKVersionEntity;
import io.oddsmaker.control.jpa.SDKVersionRepo;
import io.oddsmaker.control.jpa.SystemAlertRepo;
import io.oddsmaker.control.jpa.TelemetryConfigRepo;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖率收尾（第四轮·LAY-003 拆分）：HealthMonitorService、DeveloperPortalService、ExportService、CrashMetricsService、PredictionMetricsService、PipelineService。与 FinalSweep2Test / ServicesFinalSweep*Test 系列 / 各专项 Service 测试互补。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("覆盖率收尾（第四轮）·健康/开发者/导出/指标/管道域")
class FinalSweep3OpsTest {

// ==================== 共享 Mock（原类逐字搬运） ====================

    @Mock
    private AuditLogRepo auditLogRepo;

    @Mock
    private AuditLogService auditLogService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private HealthCheckRepo healthCheckRepo;

    @Mock
    private HealthMetricRepo healthMetricRepo;

    @Mock
    private SystemAlertRepo systemAlertRepo;

    @InjectMocks
    private HealthMonitorService healthMonitorService;

    @Mock
    private SDKKeyRepo sdkKeyRepo;

    @Mock
    private SDKVersionRepo sdkVersionRepo;

    @Mock
    private TelemetryConfigRepo telemetryConfigRepo;

    @InjectMocks
    private DeveloperPortalService developerPortalService;

    @Mock
    private ExportJobRepo exportJobRepo;

    @InjectMocks
    private ExportService exportService;

    @Mock
    private ClickHouseClient clickHouseClient;

    @InjectMocks
    private CrashMetricsService crashMetricsService;

    @InjectMocks
    private PredictionMetricsService predictionMetricsService;

    @Mock
    private PipelineRepo pipelineRepo;

    @Mock
    private PipelineJobRepo pipelineJobRepo;

    @Mock
    private DataQualityRuleRepo dataQualityRuleRepo;

    @InjectMocks
    private PipelineService pipelineService;

    @BeforeEach
    void setUp() {
        lenient().when(auditLogRepo.save(any(AuditLogEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        objectMapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    }

    private ExportJobEntity pendingJob(String id) {
        ExportJobEntity job = new ExportJobEntity();
        job.id = id;
        job.gameId = "g1";
        job.exportType = "events";
        job.exportFormat = "json";
        job.fileName = id + ".json";
        job.notifyOnComplete = true;
        job.notificationEmail = "ops@example.com";
        return job;
    }

    private PipelineEntity activePipeline() {
        PipelineEntity pipeline = new PipelineEntity();
        pipeline.id = "p1";
        pipeline.gameId = "g1";
        pipeline.pipelineName = "etl";
        pipeline.pipelineStatus = PipelineEntity.PipelineStatus.ACTIVE;
        return pipeline;
    }

    @Test
    @DisplayName("HealthMonitorService.performHealthCheck 成功与未找到")
    void testPerformHealthCheck() {
        HealthCheckEntity check = new HealthCheckEntity();
        check.checkName = "primary-db";
        check.checkType = HealthCheckEntity.CheckType.DATABASE;
        when(healthCheckRepo.findByName("primary-db")).thenReturn(Optional.of(check));
        when(healthCheckRepo.save(any(HealthCheckEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        HealthCheckEntity out = healthMonitorService.performHealthCheck("primary-db");
        assertNotNull(out.lastCheckedAt);
        assertNotNull(out.responseTimeMs);
        assertTrue(out.totalChecks >= 1);

        when(healthCheckRepo.findByName("ghost")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
            () -> healthMonitorService.performHealthCheck("ghost"));
    }

    @Test
    @DisplayName("DeveloperPortalService.cleanupExpiredKeys 过期密钥标记 EXPIRED")
    void testCleanupExpiredKeys() {
        SDKKeyEntity k1 = new SDKKeyEntity();
        k1.id = "sdk1";
        SDKKeyEntity k2 = new SDKKeyEntity();
        k2.id = "sdk2";
        when(sdkKeyRepo.findExpired()).thenReturn(List.of(k1, k2));
        when(sdkKeyRepo.save(any(SDKKeyEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        developerPortalService.cleanupExpiredKeys();
        assertEquals(SDKKeyEntity.KeyStatus.EXPIRED, k1.keyStatus);
        assertEquals(SDKKeyEntity.KeyStatus.EXPIRED, k2.keyStatus);
        verify(sdkKeyRepo, times(2)).save(any(SDKKeyEntity.class));
    }

    @Test
    @DisplayName("DeveloperPortalService.checkRetiringVersions 遍历即将退役版本")
    void testCheckRetiringVersions() {
        SDKVersionEntity v = new SDKVersionEntity();
        v.version = "1.2.3";
        v.platform = SDKVersionEntity.SDKPlatform.values()[0];
        v.retirementDate = LocalDateTime.now().plusDays(10);
        when(sdkVersionRepo.findRetiringSoon(any(LocalDateTime.class))).thenReturn(List.of(v));
        assertDoesNotThrow(() -> developerPortalService.checkRetiringVersions());
        verify(sdkVersionRepo).findRetiringSoon(any(LocalDateTime.class));
    }

    @Test
    @DisplayName("ExportService.processExportJob 完成导出并触发通知分支")
    void testProcessExportJob() throws Exception {
        String tmpDir = java.nio.file.Files.createTempDirectory("final3-export").toString();
        org.springframework.test.util.ReflectionTestUtils.setField(exportService, "storageDir", tmpDir);
        org.springframework.test.util.ReflectionTestUtils.setField(exportService, "maxRows", 1000);
        when(clickHouseClient.query(anyString(), any(Object[].class)))
            .thenReturn(List.of(Map.of("event_id", "e-1"), Map.of("event_id", "e-2")));
        ExportJobEntity job = pendingJob("ex1");
        when(exportJobRepo.findById("ex1")).thenReturn(Optional.of(job));
        when(exportJobRepo.save(any(ExportJobEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ExportJobEntity out = exportService.processExportJob("ex1");
        assertTrue(out.isCompleted());
        assertNotNull(out.filePath);
        assertTrue(out.fileSizeBytes != null && out.fileSizeBytes > 0);
        assertEquals(2L, out.totalRows);  // 实际查询行数（原 Math.random 假值）
        assertTrue(java.nio.file.Path.of(out.filePath).toFile().isFile());

        ExportJobEntity done = pendingJob("ex2");
        done.exportStatus = ExportJobEntity.ExportStatus.COMPLETED;
        when(exportJobRepo.findById("ex2")).thenReturn(Optional.of(done));
        assertThrows(IllegalStateException.class, () -> exportService.processExportJob("ex2"));
    }

    @Test
    @DisplayName("ExportService.processPendingExports 扫描处理 PENDING 任务")
    void testProcessPendingExports() throws Exception {
        String tmpDir = java.nio.file.Files.createTempDirectory("final3-export2").toString();
        org.springframework.test.util.ReflectionTestUtils.setField(exportService, "storageDir", tmpDir);
        org.springframework.test.util.ReflectionTestUtils.setField(exportService, "maxRows", 1000);
        when(clickHouseClient.query(anyString(), any(Object[].class))).thenReturn(List.of());
        ExportJobEntity job = pendingJob("ex1");
        when(exportJobRepo.findPending()).thenReturn(List.of(job));
        when(exportJobRepo.findById("ex1")).thenReturn(Optional.of(job));
        when(exportJobRepo.save(any(ExportJobEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        assertDoesNotThrow(() -> exportService.processPendingExports());
        assertTrue(job.isCompleted());
    }

    @Test
    @DisplayName("CrashMetricsService.topGroups 不可用 / 带环境 / 不带环境")
    void testTopGroups() {
        when(clickHouseClient.isAvailable()).thenReturn(false);
        Map<String, Object> unavailable = crashMetricsService.topGroups("g1", null, 30, null);
        assertEquals(false, unavailable.get("available"));

        when(clickHouseClient.isAvailable()).thenReturn(true);
        when(clickHouseClient.query(anyString(), eq("g1"), eq("prod"), any(LocalDate.class)))
            .thenReturn(List.of());
        Map<String, Object> withEnv = crashMetricsService.topGroups("g1", "prod", 30, null);
        assertEquals(true, withEnv.get("available"));
        assertEquals(List.of(), withEnv.get("groups"));

        when(clickHouseClient.query(anyString(), eq("g1"), any(LocalDate.class)))
            .thenReturn(List.of());
        Map<String, Object> noEnv = crashMetricsService.topGroups("g1", "  ", 0, null);
        assertEquals(true, noEnv.get("available"));
        assertEquals(14, noEnv.get("days"));
    }

    @Test
    @DisplayName("PipelineService.executePipelineJob 活跃管道执行完成（含质量规则过滤）")
    void testExecutePipeline() {
        PipelineEntity pipeline = activePipeline();
        when(pipelineRepo.findById("p1")).thenReturn(Optional.of(pipeline));
        DataQualityRuleEntity active = new DataQualityRuleEntity();
        active.ruleName = "not-null";
        active.actionOnFailure = "stop";
        active.ruleType = DataQualityRuleEntity.RuleType.COMPLETENESS;
        active.targetTable = "events";
        active.targetColumn = "user_id";
        DataQualityRuleEntity inactive = new DataQualityRuleEntity();
        inactive.ruleName = "inactive";
        inactive.ruleStatus = DataQualityRuleEntity.RuleStatus.INACTIVE;
        when(dataQualityRuleRepo.findByPipelineId("p1")).thenReturn(List.of(active, inactive));
        when(clickHouseClient.query(anyString(), any(Object[].class)))
            .thenReturn(List.of(Map.of("total", 10L, "v", 0L)));
        when(pipelineJobRepo.save(any(PipelineJobEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(pipelineRepo.save(any(PipelineEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        PipelineJobEntity job = pipelineService.executePipeline("p1", "api");
        assertTrue(job.isCompleted());
        assertEquals(1, pipeline.runCount);

        PipelineEntity draft = new PipelineEntity();
        draft.id = "p2";
        when(pipelineRepo.findById("p2")).thenReturn(Optional.of(draft));
        assertThrows(IllegalStateException.class, () -> pipelineService.executePipeline("p2", "api"));
    }

    @Test
    @DisplayName("PipelineService.executeScheduledPipelines 调度扫描执行")
    void testExecuteScheduledPipelines() {
        PipelineEntity pipeline = activePipeline();
        when(pipelineRepo.findScheduledPipelines(any(LocalDateTime.class))).thenReturn(List.of(pipeline));
        when(pipelineJobRepo.save(any(PipelineJobEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(pipelineRepo.save(any(PipelineEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(dataQualityRuleRepo.findByPipelineId("p1")).thenReturn(List.of());
        org.springframework.test.util.ReflectionTestUtils.setField(pipelineService, "scheduleEnabled", true);

        assertDoesNotThrow(() -> pipelineService.executeScheduledPipelines());
        assertEquals(1, pipeline.runCount);
    }

    @Test
    @DisplayName("PredictionMetricsService.topRiskScore 不可用与可用（含特征回填分支）")
    void testTopRiskScore() {
        when(clickHouseClient.isAvailable()).thenReturn(false);
        Map<String, Object> unavailable = predictionMetricsService.topRiskScore("g1", "prod", 10);
        assertEquals(false, unavailable.get("available"));

        when(clickHouseClient.isAvailable()).thenReturn(true);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("user_id", "u1");
        row.put("score", 0.9);
        row.put("predicted_at", Timestamp.valueOf("2026-01-01 00:00:00"));
        row.put("days_inactive", 5L);
        row.put("session_count", 3L);
        row.put("revenue_total", 12.5);
        when(clickHouseClient.query(anyString(), eq("g1"), eq("prod"))).thenReturn(List.of(row));
        Map<String, Object> withEnv = predictionMetricsService.topRiskScore("g1", "prod", 10);
        assertEquals(true, withEnv.get("available"));
        List<Map<String, Object>> users = (List<Map<String, Object>>) withEnv.get("users");
        assertEquals(1, users.size());
        assertEquals("u1", users.get(0).get("userId"));
        assertEquals(5L, users.get(0).get("daysInactive"));

        when(clickHouseClient.query(anyString(), eq("g1"))).thenReturn(List.of());
        Map<String, Object> noEnv = predictionMetricsService.topRiskScore("g1", null, 0);
        assertEquals(List.of(), noEnv.get("users"));
    }
}
