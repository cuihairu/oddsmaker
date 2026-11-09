package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.jpa.ExportJobEntity;
import io.oddsmaker.control.jpa.ExportJobRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 运营域 Service 深度单元测试（LAY-012 拆分）：导出创建全参/处理全流程/取消与状态校验、待处理批量执行/超时标记/统计、columns 序列化失败吞掉/excel 字节系数/定时任务异常吞掉。与 ExportServiceTest 互补。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("运营域 Service 深度测试")
class OpsServicesExportTest {

    @Mock
    private AuditLogService auditLogService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private ExportJobRepo exportJobRepo;

    @Mock
    private ClickHouseClient clickHouse;

    @Mock
    private WebhookService webhookService;

    @InjectMocks
    private ExportService exportService;

    private ExportJobEntity exportJob(String id, ExportJobEntity.ExportStatus status) {
        ExportJobEntity j = new ExportJobEntity();
        j.id = id;
        j.gameId = "g";
        j.userId = "u1";
        j.exportType = "events";
        j.exportStatus = status;
        j.fileName = id + ".csv";
        return j;
    }

    @Test
    @DisplayName("导出：创建全参/处理全流程/取消与状态校验")
    void exportCreateProcessCancel() throws Exception {
        lenient().when(exportJobRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        String tmpDir = java.nio.file.Files.createTempDirectory("ops-export").toString();
        org.springframework.test.util.ReflectionTestUtils.setField(exportService, "storageDir", tmpDir);
        org.springframework.test.util.ReflectionTestUtils.setField(exportService, "maxRows", 1000);
        lenient().when(clickHouse.query(anyString(), any(Object[].class)))
            .thenReturn(List.of(Map.of("event_id", "e-1", "event_type", "login")));
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
        // 真实落盘：文件存在、行数与大小为实际值
        assertEquals(1L, processed.totalRows);
        assertEquals(1L, processed.exportedRows);
        assertTrue(java.nio.file.Path.of(processed.filePath).toFile().isFile());
        // 导出完成派发 export_complete（gameId 取任务归属）
        verify(webhookService).sendCustomWebhook(eq("g"), eq(WebhookService.EVENT_EXPORT_COMPLETE), anyMap());

        assertThrows(IllegalArgumentException.class, () -> exportService.cancelExportJob("none", "why"));
        ExportJobEntity cancelled = exportService.cancelExportJob("e2", "wrong target");
        assertEquals(ExportJobEntity.ExportStatus.CANCELLED, cancelled.exportStatus);
        assertEquals("wrong target", cancelled.statusMessage);
        ExportJobEntity untouched = exportService.cancelExportJob("e3", "nope");
        assertEquals(ExportJobEntity.ExportStatus.COMPLETED, untouched.exportStatus);
    }

    @Test
    @DisplayName("导出：待处理批量执行、超时标记、统计与用户统计")
    void exportPendingTimeoutAndStats() throws Exception {
        lenient().when(exportJobRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        String tmpDir = java.nio.file.Files.createTempDirectory("ops-export2").toString();
        org.springframework.test.util.ReflectionTestUtils.setField(exportService, "storageDir", tmpDir);
        org.springframework.test.util.ReflectionTestUtils.setField(exportService, "maxRows", 1000);
        lenient().when(clickHouse.query(anyString(), any(Object[].class))).thenReturn(List.of());
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

    @Test
    @DisplayName("导出：columns 序列化失败吞掉、excel 格式字节系数、定时任务异常吞掉")
    void exportResilienceBranches() {
        lenient().when(exportJobRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // columns 不可序列化 → catch 吞掉，创建仍成功
        ExportJobEntity created = exportService.createExportJob(
            "g", "env", "u1", "users",
            LocalDateTime.now().minusDays(1), LocalDateTime.now(),
            "json", null, null,
            (List) java.util.List.of(new Object()), null, false, null);
        assertNotNull(created);

        // excel 无真实导出通道 → 诚实 FAILED（假成功换诚实失败）
        ExportJobEntity excel = exportJob("e_excel", ExportJobEntity.ExportStatus.PENDING);
        excel.exportFormat = "excel";
        lenient().when(exportJobRepo.findById("e_excel")).thenReturn(Optional.of(excel));
        assertThrows(RuntimeException.class, () -> exportService.processExportJob("e_excel"));
        assertEquals(ExportJobEntity.ExportStatus.FAILED, excel.exportStatus);
        assertTrue(excel.errorMessage.contains("no real export channel"));

        // 定时任务：repo 抛异常各自吞掉
        lenient().when(exportJobRepo.deleteExpired(any())).thenThrow(new IllegalStateException("boom"));
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> exportService.cleanupExpiredExports());
        lenient().when(exportJobRepo.findProcessing()).thenThrow(new IllegalStateException("boom"));
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> exportService.checkTimeoutExports());
    }

}
