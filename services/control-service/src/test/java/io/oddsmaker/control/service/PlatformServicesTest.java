package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.BlockListRepo;
import io.oddsmaker.control.jpa.CohortRepo;
import io.oddsmaker.control.jpa.ExportJobRepo;
import io.oddsmaker.control.jpa.MaintenanceWindowRepo;
import io.oddsmaker.control.jpa.RiskCaseRepo;
import io.oddsmaker.control.jpa.ReportRepo;
import io.oddsmaker.control.jpa.ReportExecutionRepo;
import io.oddsmaker.control.jpa.SystemConfigRepo;
import io.oddsmaker.control.jpa.FeatureFlagRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 运营平台类 Service 测试：黑名单/Cohort/维护窗口/报表/导出（空数据源下的主路径与校验）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("运营平台类 Service 测试")
class PlatformServicesTest {

    @Mock
    private AuditLogService auditLog;

    // ===== 黑名单 =====

    @Mock
    private BlockListRepo blockListRepo;

    @Mock
    private RiskCaseRepo riskCaseRepo;

    @InjectMocks
    private BlockListService blockListService;

    @Test
    @DisplayName("黑名单：查询/过滤/统计与未命中")
    void blockListMainPaths() {
        lenient().when(blockListRepo.findActiveBlock(eq("g"), anyString(), anyString(), any()))
            .thenReturn(java.util.Optional.empty());
        lenient().when(blockListRepo.findActiveBlocks(eq("g"), any())).thenReturn(List.of());
        lenient().when(blockListRepo.search(eq("g"), anyString())).thenReturn(List.of());
        lenient().when(blockListRepo.findByGameIdAndTargetType("g", "device")).thenReturn(List.of());

        org.junit.jupiter.api.Assertions.assertFalse(blockListService.isBlocked("g", "device", "d1"));
        blockListService.getActiveBlocks("g");
        blockListService.searchBlocks("g", "q");
        blockListService.getBlocksByType("g", "device");
        assertNotNull(blockListService.getBlockStats("g"));
        blockListService.cleanupExpiredBlocks();  // 过期清理空跑
        verify(blockListRepo).findExpiredBlocks(any());
    }

    // ===== Cohort =====

    @Mock
    private CohortRepo cohortRepo;

    @InjectMocks
    private CohortService cohortService;

    @Test
    @DisplayName("Cohort：查询/统计/搜索与计算入口")
    void cohortMainPaths() {
        lenient().when(cohortRepo.findByGameId("g")).thenReturn(List.of());
        lenient().when(cohortRepo.findCompletedByGameId("g")).thenReturn(List.of());
        lenient().when(cohortRepo.findRecent("g")).thenReturn(List.of());
        lenient().when(cohortRepo.search(eq("g"), anyString())).thenReturn(List.of());
        lenient().when(cohortRepo.findById("c1")).thenReturn(java.util.Optional.empty());

        cohortService.getGameCohorts("g");
        cohortService.getCompletedCohorts("g");
        cohortService.getRecentCohorts("g");
        cohortService.searchCohorts("g", "q");
        assertNotNull(cohortService.getCohortStats("g"));
        assertThrows(IllegalArgumentException.class, () -> cohortService.getCohort("c1"));
        cohortService.processPendingCohorts();
    }

    // ===== 维护窗口/系统配置/功能开关 =====

    @Mock
    private MaintenanceWindowRepo maintenanceWindowRepo;

    @Mock
    private SystemConfigRepo systemConfigRepo;

    @Mock
    private FeatureFlagRepo featureFlagRepo;

    @InjectMocks
    private MaintenanceService maintenanceService;

    @Test
    @DisplayName("维护：查询/配置/开关主路径")
    void maintenanceMainPaths() {
        lenient().when(maintenanceWindowRepo.findActive()).thenReturn(List.of());
        lenient().when(maintenanceWindowRepo.findUpcoming(any())).thenReturn(List.of());
        lenient().when(systemConfigRepo.findAll()).thenReturn(List.of());
        lenient().when(systemConfigRepo.findByKey("k")).thenReturn(java.util.Optional.empty());
        lenient().when(featureFlagRepo.findAll()).thenReturn(List.of());
        lenient().when(featureFlagRepo.findByKey("flag")).thenReturn(java.util.Optional.empty());

        maintenanceService.getActiveMaintenances();
        maintenanceService.getUpcomingMaintenances();
        maintenanceService.getAllConfigs();
        maintenanceService.getPublicConfigs();
        org.junit.jupiter.api.Assertions.assertNull(maintenanceService.getConfigValue("k"));
        org.junit.jupiter.api.Assertions.assertEquals("dft", maintenanceService.getConfigValue("k", "dft"));
        org.junit.jupiter.api.Assertions.assertFalse(maintenanceService.isGameInMaintenance("g"));
        org.junit.jupiter.api.Assertions.assertFalse(maintenanceService.isFeatureEnabled("flag", null, null));
        maintenanceService.getAllFeatureFlags();
        maintenanceService.getEnabledFeatureFlags();
        maintenanceService.checkPendingMaintenances();
        maintenanceService.checkEndingMaintenances();
        maintenanceService.checkScheduledFeatureFlags();
        assertNotNull(maintenanceService.getSystemStatus());
    }

    @Test
    @DisplayName("维护：公共配置聚合非空映射；定时检查异常被顶层 catch 吞掉")
    void maintenanceResilienceAndPublicConfigs() {
        io.oddsmaker.control.jpa.SystemConfigEntity pub = new io.oddsmaker.control.jpa.SystemConfigEntity();
        pub.configKey = "site.name";
        pub.configValue = "Oddsmaker";
        lenient().when(systemConfigRepo.findPublic()).thenReturn(List.of(pub));
        java.util.Map<String, String> configs = maintenanceService.getPublicConfigs();
        org.junit.jupiter.api.Assertions.assertEquals("Oddsmaker", configs.get("site.name"));

        // 三个定时检查：repo 抛异常各自吞掉
        lenient().when(maintenanceWindowRepo.findPending(any())).thenThrow(new IllegalStateException("boom"));
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> maintenanceService.checkPendingMaintenances());
        lenient().when(maintenanceWindowRepo.findShouldEnd(any())).thenThrow(new IllegalStateException("boom"));
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> maintenanceService.checkEndingMaintenances());
        lenient().when(featureFlagRepo.findScheduledToEnable(any())).thenThrow(new IllegalStateException("boom"));
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> maintenanceService.checkScheduledFeatureFlags());
    }

    // ===== 报表 =====

    @Mock
    private ReportRepo reportRepo;

    @Mock
    private ReportExecutionRepo executionRepo;

    @Spy
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    @InjectMocks
    private ReportService reportService;

    @Test
    @DisplayName("报表：查询/概览/搜索主路径")
    void reportMainPaths() {
        lenient().when(reportRepo.findByGameId("g")).thenReturn(List.of());
        lenient().when(reportRepo.findPublishedByGameId("g")).thenReturn(List.of());
        lenient().when(reportRepo.search(eq("g"), anyString())).thenReturn(List.of());
        lenient().when(executionRepo.findByReportId("r1")).thenReturn(List.of());

        reportService.getGameReports("g");
        reportService.getPublishedReports("g");
        reportService.searchReports("g", "q");
        assertNotNull(reportService.getGameReportOverview("g"));
        assertThrows(IllegalArgumentException.class, () -> reportService.getReport("r1"));
    }

    @Test
    @DisplayName("报表：参数序列化失败吞掉仍执行；超时/清理任务异常被吞")
    void reportResilienceBranches() {
        // executeReport：parameters 不可序列化 → catch 吞掉，执行记录照常创建并异步模拟完成
        io.oddsmaker.control.jpa.ReportEntity report = new io.oddsmaker.control.jpa.ReportEntity();
        report.id = "r1";
        report.gameId = "g";
        when(reportRepo.findById("r1")).thenReturn(java.util.Optional.of(report));
        when(executionRepo.save(any(io.oddsmaker.control.jpa.ReportExecutionEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        when(reportRepo.save(any(io.oddsmaker.control.jpa.ReportEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        java.util.Map<String, Object> bad = new java.util.HashMap<>();
        bad.put("bad", new Object());
        io.oddsmaker.control.jpa.ReportExecutionEntity execution =
            reportService.executeReport("r1", "op", null, bad, null);
        org.junit.jupiter.api.Assertions.assertNotNull(execution);

        // checkTimeoutExecutions：repo 抛异常被顶层 catch 吞掉
        when(executionRepo.findTimeout(any(java.time.LocalDateTime.class)))
            .thenThrow(new IllegalStateException("db down"));
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> reportService.checkTimeoutExecutions());

        // cleanupExpiredExecutions：repo 抛异常被顶层 catch 吞掉
        when(executionRepo.deleteExpired(any(java.time.LocalDateTime.class)))
            .thenThrow(new IllegalStateException("db down"));
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> reportService.cleanupExpiredExecutions());
    }

    // ===== 导出 =====

    @Mock
    private ExportJobRepo exportJobRepo;

    @InjectMocks
    private ExportService exportService;

    @Test
    @DisplayName("导出：查询/统计与空任务清理")
    void exportMainPaths() {
        lenient().when(exportJobRepo.findByGameId("g")).thenReturn(List.of());
        lenient().when(exportJobRepo.findByUserId("u1")).thenReturn(List.of());
        lenient().when(exportJobRepo.findPending()).thenReturn(List.of());
        lenient().when(exportJobRepo.findProcessing()).thenReturn(List.of());
        lenient().when(exportJobRepo.findExpired(any())).thenReturn(List.of());

        exportService.getGameExports("g");
        exportService.getUserExports("u1");
        assertNotNull(exportService.getExportStats("g"));
        assertNotNull(exportService.getUserExportStats("u1"));
        exportService.processPendingExports();
        exportService.checkTimeoutExports();
        exportService.cleanupExpiredExports();
        assertThrows(IllegalArgumentException.class, () -> exportService.getExportJob("e1"));
    }
}
