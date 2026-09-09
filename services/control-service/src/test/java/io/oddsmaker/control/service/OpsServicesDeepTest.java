package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.dto.StorageProfileDTO;
import io.oddsmaker.control.jpa.AdAnalysisRepo;
import io.oddsmaker.control.jpa.CohortEntity;
import io.oddsmaker.control.jpa.CohortRepo;
import io.oddsmaker.control.jpa.ExportJobEntity;
import io.oddsmaker.control.jpa.ExportJobRepo;
import io.oddsmaker.control.jpa.FeatureFlagEntity;
import io.oddsmaker.control.jpa.FeatureFlagRepo;
import io.oddsmaker.control.jpa.GameEnvironmentEntity;
import io.oddsmaker.control.jpa.GameEnvironmentRepo;
import io.oddsmaker.control.jpa.MaintenanceWindowEntity;
import io.oddsmaker.control.jpa.MaintenanceWindowRepo;
import io.oddsmaker.control.jpa.PerformanceMetricRepo;
import io.oddsmaker.control.jpa.ReportEntity;
import io.oddsmaker.control.jpa.ReportExecutionEntity;
import io.oddsmaker.control.jpa.ReportExecutionRepo;
import io.oddsmaker.control.jpa.ReportRepo;
import io.oddsmaker.control.jpa.RevenueAnalysisEntity;
import io.oddsmaker.control.jpa.RevenueAnalysisRepo;
import io.oddsmaker.control.jpa.SessionAnalysisRepo;
import io.oddsmaker.control.jpa.SocialAnalyticsRepo;
import io.oddsmaker.control.jpa.StorageProfileEntity;
import io.oddsmaker.control.jpa.StorageProfileRepo;
import io.oddsmaker.control.jpa.SystemConfigEntity;
import io.oddsmaker.control.jpa.SystemConfigRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;

/**
 * 运营域 Service 深度单元测试：报表/导出/Cohort/维护/存储配置/分析（主路径 + 校验分支，纯 Mockito）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("运营域 Service 深度测试")
class OpsServicesDeepTest {

    @Mock
    private AuditLogService auditLogService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    // ===== 报表 =====

    @Mock
    private ReportRepo reportRepo;

    @Mock
    private ReportExecutionRepo executionRepo;

    @InjectMocks
    private ReportService reportService;

    private ReportEntity report(String id, String name, ReportEntity.ReportStatus status) {
        ReportEntity r = new ReportEntity();
        r.id = id;
        r.gameId = "g";
        r.name = name;
        r.displayName = name;
        r.reportCategory = "analytics";
        r.status = status;
        return r;
    }

    @Test
    @DisplayName("报表：创建/发布主路径与重名、不存在校验")
    void reportCreateAndPublish() {
        lenient().when(reportRepo.findByGameIdAndName("g", "dup")).thenReturn(Optional.of(report("r0", "dup", ReportEntity.ReportStatus.DRAFT)));
        lenient().when(reportRepo.findByGameIdAndName("g", "fresh")).thenReturn(Optional.empty());
        lenient().when(reportRepo.findByGameIdAndName(eq("g2"), anyString())).thenReturn(Optional.empty());
        lenient().when(reportRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ReportEntity existing = report("r9", "published", ReportEntity.ReportStatus.DRAFT);
        lenient().when(reportRepo.findById("r9")).thenReturn(Optional.of(existing));
        lenient().when(reportRepo.findById("missing")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> reportService.createReport(
            "g", "env", "dup", "Dup", "d", ReportEntity.ReportType.CUSTOM, "analytics",
            Map.of("k", "v"), Map.of("chart", "line"), "line", "ops"));

        ReportEntity created = reportService.createReport(
            "g", "env", "fresh", "Fresh", "desc", null, "analytics",
            Map.of("metric", "dau"), Map.of("type", "bar"), "bar", "ops");
        assertEquals("fresh", created.name);
        assertEquals(ReportEntity.ReportType.CUSTOM, created.reportType);
        assertEquals(ReportEntity.ReportStatus.DRAFT, created.status);
        assertTrue(created.queryConfig.contains("dau"));
        assertTrue(created.visualization.contains("bar"));

        ReportEntity scheduled = reportService.createReport(
            "g2", null, "s1", "S1", null, ReportEntity.ReportType.SCHEDULED, null,
            null, null, null, "admin");
        assertEquals(ReportEntity.ReportType.SCHEDULED, scheduled.reportType);
        assertNull(scheduled.queryConfig);

        ReportEntity published = reportService.publishReport("r9", "publisher");
        assertEquals(ReportEntity.ReportStatus.PUBLISHED, published.status);
        assertEquals("publisher", published.updatedBy);

        assertThrows(IllegalArgumentException.class, () -> reportService.publishReport("missing", "u"));
        assertThrows(IllegalArgumentException.class, () -> reportService.getReport("missing"));
        assertEquals("r9", reportService.getReport("r9").id);
    }

    @Test
    @DisplayName("报表：执行主路径（含异步模拟）、统计、历史与排行")
    void reportExecuteAndStats() {
        lenient().when(reportRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(executionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ReportEntity r = report("r1", "active", ReportEntity.ReportStatus.PUBLISHED);
        lenient().when(reportRepo.findById("r1")).thenReturn(Optional.of(r));
        lenient().when(reportRepo.findById("none")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> reportService.executeReport("none", "u", "manual", null, null));

        ReportExecutionEntity execution = reportService.executeReport("r1", "ops", null, Map.of("p", 1), Map.of("f", 2));
        assertEquals("r1", execution.reportId);
        assertEquals("manual", execution.triggerType);
        assertTrue(execution.parameters.contains("\"p\""));
        assertTrue(execution.filters.contains("\"f\""));
        assertEquals(ReportExecutionEntity.ExecutionStatus.COMPLETED, execution.executionStatus);
        assertTrue(execution.rowCount >= 100);
        assertNotNull(execution.resultSummary);
        assertEquals(ReportEntity.ReportStatus.PUBLISHED, r.status);
        assertEquals(Long.valueOf(1L), r.totalRuns);
        assertEquals("running", r.lastRunStatus);

        ReportExecutionEntity done = new ReportExecutionEntity();
        done.id = "re1";
        done.reportId = "r1";
        done.gameId = "g";
        done.triggerType = "manual";
        done.executionStatus = ReportExecutionEntity.ExecutionStatus.COMPLETED;
        lenient().when(executionRepo.countByReportId("r1")).thenReturn(2L);
        lenient().when(executionRepo.countSuccessByReportId("r1")).thenReturn(1L);
        lenient().when(executionRepo.averageExecutionTime("r1")).thenReturn(50.0);
        lenient().when(executionRepo.sumRowCountByReportId("r1")).thenReturn(300L);
        lenient().when(executionRepo.findByReportId("r1")).thenReturn(List.of(done));

        Map<String, Object> stats = reportService.getReportStats("r1");
        assertEquals(2L, stats.get("totalRuns"));
        assertEquals(1L, stats.get("successRuns"));
        assertEquals(1L, stats.get("failedRuns"));
        assertEquals(300L, stats.get("totalRows"));
        assertEquals(1, ((Map<?, ?>) stats.get("byStatus")).size());

        assertEquals(1, reportService.getReportExecutions("r1").size());

        lenient().when(reportRepo.findPopularReports("g")).thenReturn(List.of(report("r1", "p1", ReportEntity.ReportStatus.PUBLISHED)));
        lenient().when(reportRepo.findRecentlyRun("g")).thenReturn(List.of(r));
        assertEquals(1, reportService.getPopularReports("g").size());
        assertEquals(1, reportService.getRecentlyRunReports("g").size());
    }

    @Test
    @DisplayName("报表：游戏概览统计、超时标记与过期清理")
    void reportOverviewAndSchedules() {
        ReportEntity a = report("ra", "alpha", ReportEntity.ReportStatus.PUBLISHED);
        ReportEntity b = report("rb", "beta", ReportEntity.ReportStatus.DRAFT);
        ReportEntity scheduled = report("rs", "gamma", ReportEntity.ReportStatus.SCHEDULED);
        ReportEntity otherGame = report("ro", "other", ReportEntity.ReportStatus.SCHEDULED);
        otherGame.gameId = "other";
        lenient().when(reportRepo.findByGameId("g")).thenReturn(List.of(a, b));
        lenient().when(reportRepo.findPublishedByGameId("g")).thenReturn(List.of(a));
        lenient().when(reportRepo.findScheduledReports()).thenReturn(List.of(scheduled, otherGame));
        lenient().when(executionRepo.countByReportId(anyString())).thenReturn(3L);
        lenient().when(executionRepo.countSuccessByReportId(anyString())).thenReturn(2L);
        lenient().when(executionRepo.averageExecutionTime(anyString())).thenReturn(10.0);
        lenient().when(executionRepo.sumRowCountByReportId(anyString())).thenReturn(50L);
        lenient().when(executionRepo.findByReportId(anyString())).thenReturn(List.of());
        lenient().when(executionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(executionRepo.deleteExpired(any())).thenReturn(5);

        Map<String, Object> overview = reportService.getGameReportOverview("g");
        assertEquals(2, overview.get("totalReports"));
        assertEquals(1, overview.get("publishedReports"));
        assertEquals(1, overview.get("scheduledReports"));
        assertEquals(1L, overview.get("draftReports"));
        assertEquals(6L, overview.get("totalExecutions"));
        assertEquals(100L, overview.get("totalRowsProcessed"));

        ReportExecutionEntity running = new ReportExecutionEntity();
        running.id = "re-timeout";
        running.reportId = "ra";
        running.gameId = "g";
        running.executionStatus = ReportExecutionEntity.ExecutionStatus.RUNNING;
        lenient().when(executionRepo.findTimeout(any())).thenReturn(List.of(running));
        reportService.checkTimeoutExecutions();
        assertEquals(ReportExecutionEntity.ExecutionStatus.TIMEOUT, running.executionStatus);
        assertEquals("Execution timeout", running.statusMessage);
        assertNotNull(running.completedAt);

        reportService.cleanupExpiredExecutions();
        org.mockito.Mockito.verify(executionRepo).deleteExpired(any());
    }

    // ===== 导出 =====

    @Mock
    private ExportJobRepo exportJobRepo;

    @InjectMocks
    private ExportService exportService;

    private ExportJobEntity exportJob(String id, ExportJobEntity.ExportStatus status) {
        ExportJobEntity j = new ExportJobEntity();
        j.id = id;
        j.gameId = "g";
        j.userId = "u1";
        j.exportType = "events";
        j.exportStatus = status;
        return j;
    }

    @Test
    @DisplayName("导出：创建全参/处理全流程/取消与状态校验")
    void exportCreateProcessCancel() {
        lenient().when(exportJobRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ExportJobEntity pending = exportJob("e1", ExportJobEntity.ExportStatus.PENDING);
        pending.notifyOnComplete = true;
        pending.notificationEmail = "ops@x.io";
        ExportJobEntity processing = exportJob("e2", ExportJobEntity.ExportStatus.PROCESSING);
        ExportJobEntity completed = exportJob("e3", ExportJobEntity.ExportStatus.COMPLETED);
        lenient().when(exportJobRepo.findById("e1")).thenReturn(Optional.of(pending));
        lenient().when(exportJobRepo.findById("e2")).thenReturn(Optional.of(processing));
        lenient().when(exportJobRepo.findById("e3")).thenReturn(Optional.of(completed));
        lenient().when(exportJobRepo.findById("none")).thenReturn(Optional.empty());

        ExportJobEntity created = exportService.createExportJob(
            "g", "env", "u1", "users",
            LocalDateTime.now().minusDays(1), LocalDateTime.now(),
            "json", Map.of("country", "CN"), Map.of("source", "events"),
            List.of("id", "name"), "gzip", true, "u1@x.io");
        assertEquals("u1", created.userId);
        assertEquals("json", created.exportFormat);
        assertEquals(ExportJobEntity.ExportStatus.PENDING, created.exportStatus);
        assertTrue(created.fileName.startsWith("users_g_2"));
        assertTrue(created.filters.contains("CN"));
        assertTrue(created.dataSource.contains("events"));
        assertTrue(created.columns.contains("id"));

        assertThrows(IllegalArgumentException.class, () -> exportService.processExportJob("none"));
        assertThrows(IllegalStateException.class, () -> exportService.processExportJob("e3"));

        ExportJobEntity processed = exportService.processExportJob("e1");
        assertEquals(ExportJobEntity.ExportStatus.COMPLETED, processed.exportStatus);
        assertNotNull(processed.filePath);
        assertTrue(processed.fileSizeBytes > 0);
        assertEquals(Integer.valueOf(100), processed.progressPercent);
        assertNotNull(processed.expiresAt);

        assertThrows(IllegalArgumentException.class, () -> exportService.cancelExportJob("none", "why"));
        ExportJobEntity cancelled = exportService.cancelExportJob("e2", "wrong target");
        assertEquals(ExportJobEntity.ExportStatus.CANCELLED, cancelled.exportStatus);
        assertEquals("wrong target", cancelled.statusMessage);
        ExportJobEntity untouched = exportService.cancelExportJob("e3", "nope");
        assertEquals(ExportJobEntity.ExportStatus.COMPLETED, untouched.exportStatus);
    }

    @Test
    @DisplayName("导出：待处理批量执行、超时标记、统计与用户统计")
    void exportPendingTimeoutAndStats() {
        lenient().when(exportJobRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ExportJobEntity pending = exportJob("e1", ExportJobEntity.ExportStatus.PENDING);
        lenient().when(exportJobRepo.findPending()).thenReturn(List.of(pending));
        lenient().when(exportJobRepo.findById("e1")).thenReturn(Optional.of(pending));

        exportService.processPendingExports();
        assertEquals(ExportJobEntity.ExportStatus.COMPLETED, pending.exportStatus);

        ExportJobEntity stale = exportJob("e9", ExportJobEntity.ExportStatus.PROCESSING);
        stale.startedAt = LocalDateTime.now().minusHours(2);
        ExportJobEntity fresh = exportJob("e8", ExportJobEntity.ExportStatus.PROCESSING);
        fresh.startedAt = LocalDateTime.now();
        lenient().when(exportJobRepo.findProcessing()).thenReturn(List.of(stale, fresh));
        exportService.checkTimeoutExports();
        assertEquals(ExportJobEntity.ExportStatus.FAILED, stale.exportStatus);
        assertEquals("Export timeout", stale.errorMessage);
        assertEquals(ExportJobEntity.ExportStatus.PROCESSING, fresh.exportStatus);

        lenient().when(exportJobRepo.deleteExpired(any())).thenReturn(2);
        exportService.cleanupExpiredExports();

        ExportJobEntity ok = exportJob("e3", ExportJobEntity.ExportStatus.COMPLETED);
        ok.exportFormat = "csv";
        ok.fileSizeBytes = 100L;
        ExportJobEntity failed = exportJob("e4", ExportJobEntity.ExportStatus.FAILED);
        failed.exportType = "sessions";
        ExportJobEntity running = exportJob("e5", ExportJobEntity.ExportStatus.PROCESSING);
        running.exportFormat = null;
        lenient().when(exportJobRepo.findByGameId("g")).thenReturn(List.of(ok, failed, running));
        lenient().when(exportJobRepo.sumFileSizeByGameId("g")).thenReturn(100L);

        Map<String, Object> stats = exportService.getExportStats("g");
        assertEquals(3L, stats.get("totalJobs"));
        assertEquals(1L, stats.get("completedJobs"));
        assertEquals(1L, stats.get("failedJobs"));
        assertEquals(1L, stats.get("processingJobs"));
        assertEquals(100L, stats.get("totalFileSizeBytes"));

        lenient().when(exportJobRepo.countByUserId("u1")).thenReturn(2L);
        lenient().when(exportJobRepo.countCompletedByUserId("u1")).thenReturn(1L);
        lenient().when(exportJobRepo.findByUserId("u1")).thenReturn(List.of(ok));
        Map<String, Object> userStats = exportService.getUserExportStats("u1");
        assertEquals(2L, userStats.get("totalExports"));
        assertEquals(1L, userStats.get("completedExports"));
        assertEquals(1, ((List<?>) userStats.get("recentExports")).size());
    }

    // ===== Cohort =====

    @Mock
    private CohortRepo cohortRepo;

    @InjectMocks
    private CohortService cohortService;

    private CohortEntity cohort(String id, String name, CohortEntity.CohortStatus status) {
        CohortEntity c = new CohortEntity();
        c.id = id;
        c.gameId = "g";
        c.name = name;
        c.cohortType = CohortEntity.CohortType.ACQUISITION;
        c.startDate = LocalDate.now().minusDays(7);
        c.endDate = LocalDate.now();
        c.status = status;
        return c;
    }

    @Test
    @DisplayName("Cohort：创建全参/计算主流程/状态校验/结果解析三分支")
    void cohortCreateCalculateAndResults() {
        lenient().when(cohortRepo.findByGameIdAndName("g", "dup")).thenReturn(Optional.of(cohort("c0", "dup", CohortEntity.CohortStatus.PENDING)));
        lenient().when(cohortRepo.findByGameIdAndName(eq("g2"), anyString())).thenReturn(Optional.empty());
        lenient().when(cohortRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        CohortEntity pending = cohort("c1", "new-cohort", CohortEntity.CohortStatus.PENDING);
        CohortEntity completed = cohort("c2", "done", CohortEntity.CohortStatus.COMPLETED);
        completed.resultData = "{\"cohortCount\":42}";
        CohortEntity badJson = cohort("c3", "bad", CohortEntity.CohortStatus.COMPLETED);
        badJson.resultData = "not-json";
        lenient().when(cohortRepo.findById("c1")).thenReturn(Optional.of(pending));
        lenient().when(cohortRepo.findById("c2")).thenReturn(Optional.of(completed));
        lenient().when(cohortRepo.findById("c3")).thenReturn(Optional.of(badJson));
        lenient().when(cohortRepo.findById("none")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> cohortService.createCohort(
            "g", null, "dup", "Dup", null, CohortEntity.CohortType.BEHAVIORAL,
            LocalDate.now(), LocalDate.now().plusDays(7), "week", "engagement", "session_count",
            null, null, "ops"));

        CohortEntity created = cohortService.createCohort(
            "g2", "env", "fresh", "Fresh", "desc", null,
            LocalDate.now(), LocalDate.now().plusDays(30), null, null, null,
            Map.of("event", "login"), List.of(1, 7, 30), "ops");
        assertEquals("fresh", created.name);
        assertEquals(CohortEntity.CohortType.ACQUISITION, created.cohortType);
        assertEquals("day", created.timeUnit);
        assertEquals("retention", created.analysisType);
        assertEquals("return_rate", created.metricType);
        assertEquals(CohortEntity.CohortStatus.PENDING, created.status);
        assertTrue(created.behaviorDefinition.contains("login"));
        assertTrue(created.retentionPeriods.contains("7"));

        assertEquals("no_results", cohortService.getCohortResults("c1").get("status"));
        assertThrows(IllegalArgumentException.class, () -> cohortService.calculateCohort("none"));
        assertThrows(IllegalStateException.class, () -> cohortService.calculateCohort("c2"));

        CohortEntity calculated = cohortService.calculateCohort("c1");
        assertEquals(CohortEntity.CohortStatus.COMPLETED, calculated.status);
        assertTrue(calculated.cohortCount >= 1000);
        assertNotNull(calculated.resultData);
        assertNotNull(calculated.resultSummary);
        assertEquals(Long.valueOf(1L), calculated.totalCalculations);

        Map<String, Object> results = cohortService.getCohortResults("c2");
        assertEquals(42, results.get("cohortCount"));
        assertEquals("parse_error", cohortService.getCohortResults("c3").get("status"));
        assertThrows(IllegalArgumentException.class, () -> cohortService.getCohortResults("none"));
        assertEquals("c2", cohortService.getCohort("c2").id);
    }

    @Test
    @DisplayName("Cohort：批量待计算、游戏统计与查询列表")
    void cohortBatchAndStats() {
        lenient().when(cohortRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        CohortEntity p1 = cohort("c1", "p1", CohortEntity.CohortStatus.PENDING);
        CohortEntity done = cohort("c2", "done", CohortEntity.CohortStatus.COMPLETED);
        done.cohortCount = 500L;
        done.analysisType = "retention";
        lenient().when(cohortRepo.findPending()).thenReturn(List.of(p1));
        lenient().when(cohortRepo.findById("c1")).thenReturn(Optional.of(p1));

        cohortService.processPendingCohorts();
        assertEquals(CohortEntity.CohortStatus.COMPLETED, p1.status);

        lenient().when(cohortRepo.findByGameId("g")).thenReturn(List.of(p1, done));
        lenient().when(cohortRepo.findCompletedByGameId("g")).thenReturn(List.of(done));
        lenient().when(cohortRepo.findRecent("g")).thenReturn(List.of(done));
        lenient().when(cohortRepo.search(eq("g"), anyString())).thenReturn(List.of(done));

        Map<String, Object> stats = cohortService.getCohortStats("g");
        assertEquals(2, stats.get("totalCohorts"));
        assertEquals(1, stats.get("completedCohorts"));
        assertEquals(0L, stats.get("pendingCohorts"));
        assertEquals(500L, stats.get("totalUsersAnalyzed"));

        assertEquals(2, cohortService.getGameCohorts("g").size());
        assertEquals(1, cohortService.getCompletedCohorts("g").size());
        assertEquals(1, cohortService.getRecentCohorts("g").size());
        assertEquals(1, cohortService.searchCohorts("g", "do").size());
    }

    // ===== 维护/配置/开关 =====

    @Mock
    private MaintenanceWindowRepo maintenanceWindowRepo;

    @Mock
    private SystemConfigRepo systemConfigRepo;

    @Mock
    private FeatureFlagRepo featureFlagRepo;

    @InjectMocks
    private MaintenanceService maintenanceService;

    private MaintenanceWindowEntity window(MaintenanceWindowEntity.MaintenanceStatus status) {
        MaintenanceWindowEntity w = new MaintenanceWindowEntity();
        w.id = "mw1";
        w.title = "升级";
        w.maintenanceStatus = status;
        w.scheduledStart = LocalDateTime.now().minusHours(1);
        w.scheduledEnd = LocalDateTime.now().plusHours(1);
        return w;
    }

    @Test
    @DisplayName("维护窗口：创建（普通/紧急）/开始/完成/取消与状态校验")
    void maintenanceWindowLifecycle() {
        lenient().when(maintenanceWindowRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        MaintenanceWindowEntity pending = window(MaintenanceWindowEntity.MaintenanceStatus.PENDING);
        MaintenanceWindowEntity inProgress = window(MaintenanceWindowEntity.MaintenanceStatus.IN_PROGRESS);
        MaintenanceWindowEntity scheduled = window(MaintenanceWindowEntity.MaintenanceStatus.SCHEDULED);
        lenient().when(maintenanceWindowRepo.findById("w-pending")).thenReturn(Optional.of(pending));
        lenient().when(maintenanceWindowRepo.findById("w-progress")).thenReturn(Optional.of(inProgress));
        lenient().when(maintenanceWindowRepo.findById("w-scheduled")).thenReturn(Optional.of(scheduled));
        lenient().when(maintenanceWindowRepo.findById("none")).thenReturn(Optional.empty());

        MaintenanceWindowEntity normal = maintenanceService.createMaintenanceWindow(
            "例行维护", "desc", MaintenanceWindowEntity.MaintenanceType.PATCHING,
            MaintenanceWindowEntity.ImpactScope.GAME, "g", "env",
            LocalDateTime.now(), LocalDateTime.now().plusHours(2), "ops");
        assertEquals(MaintenanceWindowEntity.MaintenanceStatus.SCHEDULED, normal.maintenanceStatus);

        MaintenanceWindowEntity emergency = maintenanceService.createMaintenanceWindow(
            "紧急修复", null, MaintenanceWindowEntity.MaintenanceType.EMERGENCY,
            MaintenanceWindowEntity.ImpactScope.GLOBAL, null, null,
            LocalDateTime.now(), LocalDateTime.now().plusHours(1), "ops");
        assertEquals(MaintenanceWindowEntity.MaintenanceStatus.PENDING, emergency.maintenanceStatus);

        assertThrows(IllegalArgumentException.class, () -> maintenanceService.startMaintenance("none"));
        assertThrows(IllegalStateException.class, () -> maintenanceService.startMaintenance("w-scheduled"));
        MaintenanceWindowEntity started = maintenanceService.startMaintenance("w-pending");
        assertEquals(MaintenanceWindowEntity.MaintenanceStatus.IN_PROGRESS, started.maintenanceStatus);
        assertNotNull(started.actualStart);

        assertThrows(IllegalStateException.class, () -> maintenanceService.completeMaintenance("w-scheduled", null));
        MaintenanceWindowEntity finished = maintenanceService.completeMaintenance("w-progress", "done notes");
        assertEquals(MaintenanceWindowEntity.MaintenanceStatus.COMPLETED, finished.maintenanceStatus);
        assertEquals("done notes", finished.completionNotes);
        assertEquals(Integer.valueOf(100), finished.progressPercent);

        assertThrows(IllegalArgumentException.class, () -> maintenanceService.cancelMaintenance("none", "r"));
        MaintenanceWindowEntity cancelled = maintenanceService.cancelMaintenance("w-scheduled", "计划变更");
        assertEquals(MaintenanceWindowEntity.MaintenanceStatus.CANCELLED, cancelled.maintenanceStatus);
    }

    @Test
    @DisplayName("维护：配置更新（含只读）/开关启用禁用/百分比/命中判断/系统状态")
    void maintenanceConfigAndFeatureFlags() {
        lenient().when(systemConfigRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(featureFlagRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SystemConfigEntity editable = new SystemConfigEntity();
        editable.id = "sc1";
        editable.configKey = "max_export_rows";
        editable.configValue = "1000";
        SystemConfigEntity readonly = new SystemConfigEntity();
        readonly.id = "sc2";
        readonly.configKey = "readonly_key";
        readonly.isReadonly = true;
        lenient().when(systemConfigRepo.findByKey("max_export_rows")).thenReturn(Optional.of(editable));
        lenient().when(systemConfigRepo.findByKey("readonly_key")).thenReturn(Optional.of(readonly));
        lenient().when(systemConfigRepo.findByKey("none")).thenReturn(Optional.empty());

        SystemConfigEntity updated = maintenanceService.setConfigValue("max_export_rows", "2000", "ops");
        assertEquals("2000", updated.getRawValue());
        assertEquals(Integer.valueOf(2), updated.version);
        assertEquals("ops", updated.lastModifiedBy);
        assertThrows(IllegalArgumentException.class, () -> maintenanceService.setConfigValue("none", "v", "ops"));
        assertThrows(IllegalStateException.class, () -> maintenanceService.setConfigValue("readonly_key", "v", "ops"));

        FeatureFlagEntity flag = new FeatureFlagEntity();
        flag.id = "ff1";
        flag.flagKey = "new_ui";
        flag.flagStatus = FeatureFlagEntity.FlagStatus.DISABLED;
        FeatureFlagEntity expired = new FeatureFlagEntity();
        expired.id = "ff2";
        expired.flagKey = "tmp_feature";
        expired.flagStatus = FeatureFlagEntity.FlagStatus.ENABLED;
        expired.expiryDate = LocalDateTime.now().minusDays(1);
        FeatureFlagEntity ok = new FeatureFlagEntity();
        ok.id = "ff3";
        ok.flagKey = "stable_feature";
        ok.flagStatus = FeatureFlagEntity.FlagStatus.ENABLED;
        lenient().when(featureFlagRepo.findByKey("new_ui")).thenReturn(Optional.of(flag));
        lenient().when(featureFlagRepo.findByKey("tmp_feature")).thenReturn(Optional.of(expired));
        lenient().when(featureFlagRepo.findByKey("stable_feature")).thenReturn(Optional.of(ok));
        lenient().when(featureFlagRepo.findByKey("none")).thenReturn(Optional.empty());

        assertEquals(FeatureFlagEntity.FlagStatus.ENABLED, maintenanceService.enableFeature("new_ui", "ops").flagStatus);
        FeatureFlagEntity pct = maintenanceService.setFeaturePercentage("new_ui", 150, "ops");
        assertEquals(Integer.valueOf(100), pct.percentageValue);
        assertEquals(FeatureFlagEntity.FlagStatus.ENABLED, pct.flagStatus);
        FeatureFlagEntity partial = maintenanceService.setFeaturePercentage("new_ui", 30, "ops");
        assertEquals(FeatureFlagEntity.FlagStatus.STAGED_ROLLOUT, partial.flagStatus);
        FeatureFlagEntity zero = maintenanceService.setFeaturePercentage("new_ui", -5, "ops");
        assertEquals(FeatureFlagEntity.FlagStatus.DISABLED, zero.flagStatus);
        assertEquals(FeatureFlagEntity.FlagStatus.DISABLED, maintenanceService.disableFeature("new_ui", "ops").flagStatus);
        assertThrows(IllegalArgumentException.class, () -> maintenanceService.enableFeature("none", "ops"));
        assertThrows(IllegalArgumentException.class, () -> maintenanceService.disableFeature("none", "ops"));
        assertThrows(IllegalArgumentException.class, () -> maintenanceService.setFeaturePercentage("none", 50, "ops"));

        MaintenanceWindowEntity global = window(MaintenanceWindowEntity.MaintenanceStatus.IN_PROGRESS);
        lenient().when(maintenanceWindowRepo.findActive()).thenReturn(List.of(global));
        assertTrue(maintenanceService.isGameInMaintenance("any"));
        assertTrue(maintenanceService.isFeatureEnabled("stable_feature", null, null));
        assertFalse(maintenanceService.isFeatureEnabled("tmp_feature", null, null));
        assertFalse(maintenanceService.isFeatureEnabled("new_ui", null, null));

        lenient().when(maintenanceWindowRepo.findUpcoming(any())).thenReturn(List.of());
        lenient().when(featureFlagRepo.countByStatus(FeatureFlagEntity.FlagStatus.ENABLED)).thenReturn(2L);
        lenient().when(featureFlagRepo.count()).thenReturn(3L);
        Map<String, Object> status = maintenanceService.getSystemStatus();
        assertEquals(1L, status.get("activeMaintenances"));
        assertEquals(2L, status.get("enabledFeatures"));
        assertEquals(3L, status.get("totalFeatures"));
        assertEquals(Boolean.TRUE, status.get("maintenanceMode"));
    }

    @Test
    @DisplayName("维护：定时任务（待开始/应结束/开关自动启用禁用过期）")
    void maintenanceScheduledTasks() {
        lenient().when(maintenanceWindowRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(featureFlagRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        MaintenanceWindowEntity toStart = window(MaintenanceWindowEntity.MaintenanceStatus.SCHEDULED);
        lenient().when(maintenanceWindowRepo.findPending(any())).thenReturn(List.of(toStart));
        maintenanceService.checkPendingMaintenances();
        assertEquals(MaintenanceWindowEntity.MaintenanceStatus.PENDING, toStart.maintenanceStatus);

        MaintenanceWindowEntity toEnd = window(MaintenanceWindowEntity.MaintenanceStatus.IN_PROGRESS);
        lenient().when(maintenanceWindowRepo.findShouldEnd(any())).thenReturn(List.of(toEnd));
        maintenanceService.checkEndingMaintenances();

        FeatureFlagEntity enable = new FeatureFlagEntity();
        enable.flagKey = "f-enable";
        enable.flagStatus = FeatureFlagEntity.FlagStatus.DISABLED;
        FeatureFlagEntity disable = new FeatureFlagEntity();
        disable.flagKey = "f-disable";
        disable.flagStatus = FeatureFlagEntity.FlagStatus.ENABLED;
        FeatureFlagEntity expired = new FeatureFlagEntity();
        expired.flagKey = "f-expired";
        expired.flagStatus = FeatureFlagEntity.FlagStatus.ENABLED;
        lenient().when(featureFlagRepo.findScheduledToEnable(any())).thenReturn(List.of(enable));
        lenient().when(featureFlagRepo.findScheduledToDisable(any())).thenReturn(List.of(disable));
        lenient().when(featureFlagRepo.findExpired(any())).thenReturn(List.of(expired));

        maintenanceService.checkScheduledFeatureFlags();
        assertEquals(FeatureFlagEntity.FlagStatus.ENABLED, enable.flagStatus);
        assertEquals("system", enable.lastModifiedBy);
        assertEquals(FeatureFlagEntity.FlagStatus.DISABLED, disable.flagStatus);
        assertEquals(FeatureFlagEntity.FlagStatus.DISABLED, expired.flagStatus);
    }

    // ===== 存储配置 =====

    @Mock
    private StorageProfileRepo storageProfileRepo;

    @Mock
    private GameEnvironmentRepo environmentRepo;

    @InjectMocks
    private StorageProfileService storageProfileService;

    private StorageProfileEntity storageProfile(String id, String name, StorageProfileEntity.IsolationStrategy strategy) {
        StorageProfileEntity e = new StorageProfileEntity();
        e.id = id;
        e.name = name;
        e.displayName = name;
        e.isolationStrategy = strategy;
        e.active = true;
        return e;
    }

    @Test
    @DisplayName("存储配置：创建（slug 生成/重复校验/空名校验）与更新")
    void storageProfileCreateAndUpdate() {
        lenient().when(storageProfileRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(storageProfileRepo.existsById("prod-dedicated-cluster")).thenReturn(false);
        lenient().when(storageProfileRepo.existsById("sp-exist")).thenReturn(true);
        lenient().when(storageProfileRepo.existsByNameAndDeletedAtIsNull("dup-name")).thenReturn(true);
        lenient().when(storageProfileRepo.existsByNameAndDeletedAtIsNull("brand-new")).thenReturn(false);

        StorageProfileDTO dto = new StorageProfileDTO();
        dto.name = "Prod Dedicated Cluster";
        dto.kafkaCluster = "k1";
        StorageProfileDTO created = storageProfileService.createStorageProfile(dto);
        assertEquals("prod-dedicated-cluster", created.id);
        assertEquals(StorageProfileEntity.IsolationStrategy.SHARED, created.isolationStrategy);
        assertEquals(Boolean.TRUE, created.active);

        StorageProfileDTO dupId = new StorageProfileDTO();
        dupId.id = "sp-exist";
        dupId.name = "whatever";
        assertThrows(IllegalArgumentException.class, () -> storageProfileService.createStorageProfile(dupId));

        StorageProfileDTO dupName = new StorageProfileDTO();
        dupName.id = "sp-new";
        dupName.name = "dup-name";
        assertThrows(IllegalArgumentException.class, () -> storageProfileService.createStorageProfile(dupName));

        StorageProfileDTO blank = new StorageProfileDTO();
        blank.id = null;
        blank.name = "   ";
        assertThrows(IllegalArgumentException.class, () -> storageProfileService.createStorageProfile(blank));

        StorageProfileEntity entity = storageProfile("sp1", "old-name", StorageProfileEntity.IsolationStrategy.SHARED);
        lenient().when(storageProfileRepo.findById("sp1")).thenReturn(Optional.of(entity));
        lenient().when(storageProfileRepo.findById("none")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> storageProfileService.updateStorageProfile("none", dto));

        StorageProfileDTO update = new StorageProfileDTO();
        update.name = "brand-new";
        update.displayName = "Brand New";
        update.isolationStrategy = StorageProfileEntity.IsolationStrategy.DEDICATED;
        StorageProfileDTO updated = storageProfileService.updateStorageProfile("sp1", update);
        assertEquals("brand-new", updated.name);
        assertEquals(StorageProfileEntity.IsolationStrategy.DEDICATED, updated.isolationStrategy);

        StorageProfileDTO conflict = new StorageProfileDTO();
        conflict.name = "dup-name";
        assertThrows(IllegalArgumentException.class, () -> storageProfileService.updateStorageProfile("sp1", conflict));

        StorageProfileEntity deleted = storageProfile("sp-gone", "gone", StorageProfileEntity.IsolationStrategy.SHARED);
        deleted.deletedAt = LocalDateTime.now();
        lenient().when(storageProfileRepo.findById("sp-gone")).thenReturn(Optional.of(deleted));
        assertThrows(IllegalArgumentException.class, () -> storageProfileService.updateStorageProfile("sp-gone", update));
    }

    @Test
    @DisplayName("存储配置：删除（占用校验）与各类查询（详情/列表/策略/活跃/分页）")
    void storageProfileDeleteAndQueries() {
        lenient().when(storageProfileRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        StorageProfileEntity shared = storageProfile("sp1", "shared-pool", StorageProfileEntity.IsolationStrategy.SHARED);
        StorageProfileEntity dedicated = storageProfile("sp2", "dedicated-pool", StorageProfileEntity.IsolationStrategy.DEDICATED);
        StorageProfileEntity deleted = storageProfile("sp3", "deleted-pool", StorageProfileEntity.IsolationStrategy.SHARED);
        deleted.deletedAt = LocalDateTime.now();
        lenient().when(storageProfileRepo.findById("sp1")).thenReturn(Optional.of(shared));
        lenient().when(storageProfileRepo.findById("sp2")).thenReturn(Optional.of(dedicated));
        lenient().when(storageProfileRepo.findById("none")).thenReturn(Optional.empty());
        lenient().when(environmentRepo.findByStorageProfileIdAndDeletedAtIsNull(anyString())).thenReturn(List.of());
        lenient().when(environmentRepo.findByStorageProfileIdAndDeletedAtIsNull("sp1")).thenReturn(List.of(new GameEnvironmentEntity(), new GameEnvironmentEntity()));

        assertThrows(IllegalArgumentException.class, () -> storageProfileService.deleteStorageProfile("none"));
        assertThrows(IllegalStateException.class, () -> storageProfileService.deleteStorageProfile("sp1"));

        assertEquals(2, storageProfileService.getStorageProfile("sp1").orElseThrow().totalEnvironments);
        assertTrue(storageProfileService.getStorageProfile("none").isEmpty());

        lenient().when(storageProfileRepo.findAll()).thenReturn(List.of(deleted, dedicated, shared));
        List<StorageProfileDTO> all = storageProfileService.getStorageProfiles();
        assertEquals(2, all.size());
        assertEquals("dedicated-pool", all.get(0).name);
        assertEquals(1, storageProfileService.getStorageProfilesByStrategy(StorageProfileEntity.IsolationStrategy.DEDICATED).size());
        assertEquals(1, storageProfileService.getStorageProfilesByStrategy(StorageProfileEntity.IsolationStrategy.SHARED).size());

        lenient().when(storageProfileRepo.findByActiveTrueAndDeletedAtIsNullOrderByNameAsc()).thenReturn(List.of(shared));
        assertEquals(1, storageProfileService.getActiveStorageProfiles().size());

        lenient().when(storageProfileRepo.findAll(any(org.springframework.data.domain.Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(shared, dedicated)));
        org.springframework.data.domain.Page<StorageProfileDTO> page =
            storageProfileService.getStorageProfiles(PageRequest.of(0, 10));
        assertEquals(2, page.getTotalElements());
        assertEquals("shared-pool", page.getContent().get(0).name);

        storageProfileService.deleteStorageProfile("sp2");
        assertNotNull(dedicated.deletedAt);
        assertFalse(dedicated.active);
    }

    // ===== 分析 =====

    @Mock
    private RevenueAnalysisRepo revenueAnalysisRepo;

    @Mock
    private AdAnalysisRepo adAnalysisRepo;

    @Mock
    private SessionAnalysisRepo sessionAnalysisRepo;

    @Mock
    private PerformanceMetricRepo performanceMetricRepo;

    @Mock
    private SocialAnalyticsRepo socialAnalyticsRepo;

    @InjectMocks
    private AnalyticsService analyticsService;

    @Test
    @DisplayName("分析：收入概览/ARPU 趋势/平台分布与广告概览、网络明细")
    void analyticsRevenueAndAd() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 1, 31);
        lenient().when(revenueAnalysisRepo.getDailyRevenueSummary("g", start, end)).thenReturn(List.of(
            new Object[]{start, 100.0, 60.0, 30.0},
            new Object[]{end, null, null, null}));

        Map<String, Object> overview = analyticsService.getRevenueOverview("g", start, end);
        assertEquals(100.0, overview.get("totalRevenue"));
        assertEquals(60.0, overview.get("iapRevenue"));
        assertEquals(30.0, overview.get("adRevenue"));
        assertEquals(10.0, overview.get("subscriptionRevenue"));
        assertEquals(2, overview.get("days"));

        RevenueAnalysisEntity rec = new RevenueAnalysisEntity();
        rec.gameId = "g";
        rec.analysisDate = start;
        rec.arpu = 1.5;
        rec.arppu = 15.0;
        rec.payingUsers = 10L;
        rec.totalUsers = 100L;
        lenient().when(revenueAnalysisRepo.findByGameIdAndAnalysisDateBetween("g", start, end)).thenReturn(List.of(rec));
        List<Map<String, Object>> trends = analyticsService.getArpuTrends("g", start, end);
        assertEquals(1, trends.size());
        assertEquals(1.5, trends.get(0).get("arpu"));
        assertEquals(10L, trends.get(0).get("payingUsers"));

        lenient().when(revenueAnalysisRepo.getRevenueByPlatform("g", start)).thenReturn(List.of(
            new Object[]{"ios", 80.0, 1.0, 10.0},
            new Object[]{"android", 20.0, 0.5, 5.0}));
        List<Map<String, Object>> byPlatform = analyticsService.getRevenueByPlatform("g", start);
        assertEquals(2, byPlatform.size());
        assertEquals("ios", byPlatform.get(0).get("platform"));

        lenient().when(adAnalysisRepo.getAdPerformanceByNetwork("g", start, end)).thenReturn(List.of(
            new Object[]{"admob", 50.0, 1000L, 5.0, 0.9},
            new Object[]{"unity", null, null, null, null}));
        Map<String, Object> adOverview = analyticsService.getAdPerformanceOverview("g", start, end);
        assertEquals(50.0, adOverview.get("totalRevenue"));
        assertEquals(1000L, adOverview.get("totalImpressions"));
        assertEquals(2, adOverview.get("networkCount"));
        assertEquals(2.5, adOverview.get("avgEcpm"));

        List<Map<String, Object>> networks = analyticsService.getAdPerformanceByNetwork("g", start, end);
        assertEquals(2, networks.size());
        assertEquals("admob", networks.get(0).get("network"));
        assertEquals(1000L, networks.get(0).get("impressions"));
    }

    @Test
    @DisplayName("分析：会话概览与趋势/性能概览/崩溃分组/社交概览与留存影响")
    void analyticsSessionPerformanceSocial() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 1, 2);
        LocalDateTime tsStart = start.atStartOfDay();
        LocalDateTime tsEnd = end.atStartOfDay().plusDays(1);

        lenient().when(sessionAnalysisRepo.getSessionTrends("g", start, end)).thenReturn(List.of(
            new Object[]{start, 30.0, 12.0, 0.2},
            new Object[]{end, 60.0, 24.0, null}));
        Map<String, Object> sessionOverview = analyticsService.getSessionOverview("g", start, end);
        assertEquals(2, sessionOverview.get("days"));
        assertEquals(45.0, sessionOverview.get("avgSessionDuration"));
        assertEquals(18.0, sessionOverview.get("avgEventsPerSession"));
        assertEquals(0.1, sessionOverview.get("avgBounceRate"));

        List<Map<String, Object>> sessionTrends = analyticsService.getSessionTrends("g", start, end);
        assertEquals(2, sessionTrends.size());
        assertEquals(30.0, sessionTrends.get(0).get("avgDuration"));

        lenient().when(performanceMetricRepo.getPerformanceSummary("g", tsStart, tsEnd)).thenReturn(List.of(
            new Object[]{"fps", 55.5, 100L},
            new Object[]{"memory", null, null}));
        Map<String, Object> perf = analyticsService.getPerformanceOverview("g", tsStart, tsEnd);
        Map<?, ?> fps = (Map<?, ?>) perf.get("fps");
        assertEquals(55.5, fps.get("avgValue"));
        assertEquals(100L, fps.get("count"));
        Map<?, ?> memory = (Map<?, ?>) perf.get("memory");
        assertEquals(0.0, memory.get("avgValue"));

        lenient().when(performanceMetricRepo.getCrashGroups("g")).thenReturn(List.<Object[]>of(
            new Object[]{"hash-1", 10L, tsStart, tsEnd}));
        List<Map<String, Object>> crashes = analyticsService.getCrashGroups("g");
        assertEquals(1, crashes.size());
        assertEquals("hash-1", crashes.get(0).get("crashHash"));

        lenient().when(socialAnalyticsRepo.getSocialTrends("g", start, end)).thenReturn(List.of(
            new Object[]{start, 100L, 10L, 0.5},
            new Object[]{end, 50L, 5L, 0.3}));
        Map<String, Object> social = analyticsService.getSocialOverview("g", start, end);
        assertEquals(150L, social.get("totalFriendships"));
        assertEquals(15L, social.get("totalGuilds"));
        assertEquals(0.4, (double) social.get("avgViralCoefficient"), 1e-9);

        lenient().when(socialAnalyticsRepo.getSocialRetentionImpact("g", start)).thenReturn(new Object[]{0.4, 0.25});
        Map<String, Object> impact = analyticsService.getSocialRetentionImpact("g", start);
        assertEquals(0.4, impact.get("socialUsersD7Retention"));
        assertEquals(0.25, impact.get("nonSocialUsersD7Retention"));
        assertEquals(0.15, (double) impact.get("retentionLift"), 1e-9);
    }
}
