package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.jpa.ReportEntity;
import io.oddsmaker.control.jpa.ReportExecutionEntity;
import io.oddsmaker.control.jpa.ReportExecutionRepo;
import io.oddsmaker.control.jpa.ReportRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 运营域 Service 深度单元测试（LAY-012 拆分）：报表创建/发布主路径与重名校验、执行主路径（含异步模拟）/统计/历史与排行、游戏概览统计/超时标记/过期清理。与 ReportServiceTest 互补。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("运营域 Service 深度测试")
class OpsServicesReportTest {

    @Mock
    private AuditLogService auditLogService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private ReportRepo reportRepo;

    @Mock
    private ClickHouseClient clickHouse;

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

        // 真化后执行走 ClickHouse 白名单聚合——stub 返回单行结果
        lenient().when(clickHouse.isAvailable()).thenReturn(true);
        lenient().when(clickHouse.query(anyString(), any(Object[].class)))
            .thenReturn(List.of(Map.of("bucket", "2026-09-19 00:00:00", "a_events", 5L, "a_players", 3L)));

        ReportExecutionEntity execution = reportService.executeReport("r1", "ops", null, Map.of("p", 1), Map.of("f", 2));
        assertEquals("r1", execution.reportId);
        assertEquals("manual", execution.triggerType);
        assertTrue(execution.parameters.contains("\"p\""));
        assertTrue(execution.filters.contains("\"f\""));
        assertEquals(ReportExecutionEntity.ExecutionStatus.COMPLETED, execution.executionStatus);
        // 行数/结果数据为真实查询返回（旧实现是 Math.random 假行数）
        assertEquals(Long.valueOf(1L), execution.rowCount);
        assertNotNull(execution.resultSummary);
        assertNotNull(execution.resultData);
        assertNotNull(execution.queryTimeMs);
        assertEquals(ReportEntity.ReportStatus.PUBLISHED, r.status);
        assertEquals(Long.valueOf(1L), r.totalRuns);
        // 成功侧回写完成状态（旧实现只写 running 从不收口）
        assertEquals("completed", r.lastRunStatus);

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

        // 无超时执行（空列表侧）：静默返回不追加保存（前段已 save 过一次）
        lenient().when(executionRepo.findTimeout(any())).thenReturn(List.of());
        assertDoesNotThrow(reportService::checkTimeoutExecutions);
        verify(executionRepo, org.mockito.Mockito.times(1)).save(any());

        reportService.cleanupExpiredExecutions();
        org.mockito.Mockito.verify(executionRepo).deleteExpired(any());
    }

}
