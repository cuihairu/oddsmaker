package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.jpa.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖率最终冲刺 6（LAY-007 拆分）：CohortService 计算/创建/待处理分支，ReportService 创建与执行失败分支，ReportEntity 可视化配置解析，FlinkJobService 创建与统计 lambda。与 CohortServiceTest / ReportServiceTest / FlinkJobServiceTest 互补。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("覆盖率冲刺 6：群组/报表/Flink 作业")
class FinalSweep6AnalyticsTest {

    @Mock
    private AuditLogService auditLog;

    @Mock
    private CohortRepo cohortRepo;

    @Mock
    private ReportRepo reportRepo;

    @Mock
    private ReportExecutionRepo reportExecutionRepo;

    @Mock
    private FlinkJobRepo flinkJobRepo;

    @Test
    @DisplayName("calculateCohort：结果保存抛异常 → markAsFailed + RuntimeException")
    void cohortCalculateFailureBranch() {
        CohortService service = new CohortService();
        ReflectionTestUtils.setField(service, "cohortRepo", cohortRepo);
        ReflectionTestUtils.setField(service, "auditLogService", auditLog);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());

        CohortEntity cohort = new CohortEntity();
        cohort.id = "c1";
        cohort.gameId = "g";
        cohort.name = "sep";
        cohort.status = CohortEntity.CohortStatus.PENDING;
        when(cohortRepo.findById("c1")).thenReturn(Optional.of(cohort));
        when(cohortRepo.save(any(CohortEntity.class)))
            .thenReturn(cohort)
            .thenThrow(new RuntimeException("db down"))
            .thenReturn(cohort);

        assertThrows(RuntimeException.class, () -> service.calculateCohort("c1"));
        assertEquals(CohortEntity.CohortStatus.FAILED, cohort.status);
    }

    @Test
    @DisplayName("createCohort：重名拒绝、序列化失败、空 retentionPeriods 跳过")
    void cohortCreateBranches() {
        CohortService service = new CohortService();
        ReflectionTestUtils.setField(service, "cohortRepo", cohortRepo);
        ReflectionTestUtils.setField(service, "auditLogService", auditLog);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());

        when(cohortRepo.findByGameIdAndName("g", "dup")).thenReturn(Optional.of(new CohortEntity()));
        assertThrows(IllegalArgumentException.class, () -> service.createCohort(
            "g", null, "dup", "Dup", null, null, null, null, null, null, null, null, null, "ops"));

        when(cohortRepo.findByGameIdAndName("g", "new")).thenReturn(Optional.empty());
        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.createCohort(
            "g", null, "new", "New", null, null, null, null, null, null, null,
            Map.of("bad", new Object()), null, "ops"));
        assertTrue(ex.getMessage().contains("Failed to serialize cohort config"));

        when(cohortRepo.save(any(CohortEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        CohortEntity created = service.createCohort(
            "g", null, "new2", "New2", null, null, null, null, null, null, null, null, List.of(), "ops");
        assertEquals(CohortEntity.CohortStatus.PENDING, created.status);
        assertNull(created.retentionPeriods);
        verify(cohortRepo).save(any(CohortEntity.class));
    }

    @Test
    @DisplayName("processPendingCohorts：成功/单任务失败/外层异常三分支")
    void cohortProcessPendingBranches() {
        CohortService service = new CohortService();
        ReflectionTestUtils.setField(service, "cohortRepo", cohortRepo);
        ReflectionTestUtils.setField(service, "auditLogService", auditLog);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());

        CohortEntity pending = new CohortEntity();
        pending.id = "c1";
        pending.gameId = "g";
        pending.name = "p";
        pending.status = CohortEntity.CohortStatus.PENDING;
        pending.startDate = java.time.LocalDate.now().minusDays(7);
        pending.endDate = java.time.LocalDate.now();
        when(cohortRepo.findById("c1")).thenReturn(Optional.of(pending));
        when(cohortRepo.save(any(CohortEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(cohortRepo.findPending()).thenReturn(List.of(pending));
        // 真化后计算走 ClickHouse——局部 mock（空数据即 COMPLETED cohortCount=0）
        ClickHouseClient ch = mock(ClickHouseClient.class);
        when(ch.isAvailable()).thenReturn(true);
        when(ch.query(anyString(), any(Object[].class))).thenReturn(List.of());
        ReflectionTestUtils.setField(service, "clickHouse", ch);
        service.processPendingCohorts();
        assertEquals(CohortEntity.CohortStatus.COMPLETED, pending.status);

        CohortEntity completed = new CohortEntity();
        completed.id = "c2";
        completed.status = CohortEntity.CohortStatus.COMPLETED;
        when(cohortRepo.findPending()).thenReturn(List.of(completed));
        when(cohortRepo.findById("c2")).thenReturn(Optional.of(completed));
        service.processPendingCohorts();  // ISE → 内层 catch

        when(cohortRepo.findPending()).thenThrow(new RuntimeException("boom"));
        service.processPendingCohorts();  // 外层 catch
    }

    @Test
    @DisplayName("createReport：重名拒绝与序列化失败 RuntimeException")
    void reportCreateBranches() {
        ReportService service = new ReportService();
        ReflectionTestUtils.setField(service, "reportRepo", reportRepo);
        ReflectionTestUtils.setField(service, "executionRepo", reportExecutionRepo);
        ReflectionTestUtils.setField(service, "auditLogService", auditLog);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());

        when(reportRepo.findByGameIdAndName("g", "dup")).thenReturn(Optional.of(new ReportEntity()));
        assertThrows(IllegalArgumentException.class, () -> service.createReport(
            "g", null, "dup", "Dup", null, null, null,
            Map.of("bad", new Object()), null, null, "ops"));

        when(reportRepo.findByGameIdAndName("g", "new")).thenReturn(Optional.empty());
        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.createReport(
            "g", null, "new", "New", null, null, null,
            Map.of("bad", new Object()), Map.of("k", 1), "line", "ops"));
        assertTrue(ex.getMessage().contains("Failed to serialize report config"));
    }

    @Test
    @DisplayName("executeReport：报表保存失败与执行完成保存失败两 catch 分支")
    void reportExecutionFailureBranches() {
        ReportService service = new ReportService();
        ReflectionTestUtils.setField(service, "reportRepo", reportRepo);
        ReflectionTestUtils.setField(service, "executionRepo", reportExecutionRepo);
        ReflectionTestUtils.setField(service, "auditLogService", auditLog);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        // 真化后执行依赖 ClickHouse——本用例只测 save 失败分支，CH 置为不可用（诚实 FAILED，不触碰真查询）
        ClickHouseClient ch = mock(ClickHouseClient.class);
        when(ch.isAvailable()).thenReturn(false);
        ReflectionTestUtils.setField(service, "clickHouse", ch);

        ReportEntity report = new ReportEntity();
        report.id = "r1";
        report.gameId = "g";
        report.name = "r";
        when(reportRepo.findById("r1")).thenReturn(Optional.of(report));

        // executeReportAsync catch：reportRepo.save 持续失败
        when(reportRepo.save(any(ReportEntity.class))).thenThrow(new RuntimeException("repo down"));
        when(reportExecutionRepo.save(any(ReportExecutionEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        assertThrows(RuntimeException.class,
            () -> service.executeReport("r1", "ops", null, null, null));

        // simulateReportExecution catch：COMPLETED 状态保存抛异常 → markAsFailed
        when(reportRepo.save(any(ReportEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(reportExecutionRepo.save(any(ReportExecutionEntity.class))).thenAnswer(inv -> {
            ReportExecutionEntity e = inv.getArgument(0);
            if (e.executionStatus == ReportExecutionEntity.ExecutionStatus.COMPLETED) {
                throw new RuntimeException("write failed");
            }
            return e;
        });
        ReportExecutionEntity execution = service.executeReport("r1", "ops", null, null, null);
        assertEquals(ReportExecutionEntity.ExecutionStatus.FAILED, execution.executionStatus);
    }

    @Test
    @DisplayName("getVisualizationConfig：合法 JSON 解析与缺省字段回退分支")
    void reportEntityVisualizationConfigBranches() {
        ReportEntity parsed = new ReportEntity();
        parsed.visualization = "{\"rows\":10}";
        assertEquals(Map.of("rows", 10), parsed.getVisualizationConfig());

        ReportEntity defaults = new ReportEntity();
        defaults.chartType = "bar";
        defaults.groupBy = "[\"d\"]";
        defaults.aggregations = "{\"sum\":\"x\"}";
        Map<String, Object> cfg = defaults.getVisualizationConfig();
        assertEquals("bar", cfg.get("chartType"));
        assertEquals("[\"d\"]", cfg.get("groupBy"));
        assertEquals("{\"sum\":\"x\"}", cfg.get("aggregations"));
    }

    @Test
    @DisplayName("createJob：重名拒绝、缺省 jobType/parallelism、空 ruleIds 跳过")
    void flinkCreateJobBranches() {
        FlinkJobService service = new FlinkJobService();
        ReflectionTestUtils.setField(service, "flinkJobRepo", flinkJobRepo);
        ReflectionTestUtils.setField(service, "riskRuleRepo", mock(RiskRuleRepo.class));
        ReflectionTestUtils.setField(service, "auditLogService", auditLog);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());

        when(flinkJobRepo.findByGameIdAndName("g", "dup")).thenReturn(Optional.of(new FlinkJobEntity()));
        assertThrows(IllegalArgumentException.class, () -> service.createJob(
            "g", null, "dup", "Dup", null, null, null, null, null, null, null, "ops"));

        when(flinkJobRepo.findByGameIdAndName("g", "fresh")).thenReturn(Optional.empty());
        when(flinkJobRepo.save(any(FlinkJobEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        FlinkJobEntity job = service.createJob(
            "g", null, "fresh", "Fresh", null, null, Map.of("k", 1), null, null, List.of(), null, "ops");
        assertEquals(FlinkJobEntity.JobType.RISK_EVALUATION.name(), job.jobType);
        assertEquals(1, job.parallelism);
        assertNull(job.ruleIds);
    }

    @Test
    @DisplayName("getJobStats：指标空/非空与状态过滤 lambda 全分支")
    void flinkGetJobStatsLambdas() {
        FlinkJobService service = new FlinkJobService();
        ReflectionTestUtils.setField(service, "flinkJobRepo", flinkJobRepo);
        ReflectionTestUtils.setField(service, "riskRuleRepo", mock(RiskRuleRepo.class));
        ReflectionTestUtils.setField(service, "auditLogService", auditLog);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());

        FlinkJobEntity running = new FlinkJobEntity();
        running.id = "j1";
        running.status = FlinkJobEntity.JobStatus.RUNNING;
        running.totalEventsProcessed = 100L;
        running.totalRiskCasesCreated = 10L;

        FlinkJobEntity stopped = new FlinkJobEntity();
        stopped.id = "j2";
        stopped.status = FlinkJobEntity.JobStatus.STOPPED;

        FlinkJobEntity failed = new FlinkJobEntity();
        failed.id = "j3";
        failed.status = FlinkJobEntity.JobStatus.FAILED;

        when(flinkJobRepo.findByGameId("g")).thenReturn(List.of(running, stopped, failed));
        when(flinkJobRepo.findRunningJobs("g")).thenReturn(List.of(running));

        Map<String, Object> stats = service.getJobStats("g");

        assertEquals(3, stats.get("totalJobs"));
        assertEquals(1, ((Number) stats.get("runningJobs")).intValue());
        assertEquals(2, ((Number) stats.get("stoppedJobs")).intValue());
        assertEquals(1, ((Number) stats.get("failedJobs")).intValue());
        assertEquals(100L, stats.get("totalEventsProcessed"));
        assertEquals(10L, stats.get("totalRiskCasesCreated"));
        assertEquals(0.1, (double) stats.get("overallRiskCaseRate"), 1e-9);
    }

}
