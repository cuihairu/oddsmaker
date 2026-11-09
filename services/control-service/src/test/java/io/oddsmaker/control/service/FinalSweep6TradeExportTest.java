package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.jpa.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 覆盖率最终冲刺 6（LAY-007 拆分）：PlayerPaymentService 边界分支，ExportService 失败与待处理分支，PlayerExportService sweep/清理/JSON 反序列化/下载分支。与 PlayerPaymentServiceTest / ExportServiceTest / PlayerExportServiceTest 互补。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("覆盖率冲刺 6：支付/导出作业/玩家导出")
class FinalSweep6TradeExportTest {

    @TempDir
    Path tempDir;

    @Mock
    private AuditLogService auditLog;

    @Mock
    private GameRepo gameRepo;

    @Mock
    private PlayerPaymentRepo paymentRepo;

    @Mock
    private IdentityRepo identityRepo;

    @Mock
    private PlayerLoginLogRepo loginLogRepo;

    @Mock
    private ExportJobRepo exportJobRepo;

    @Mock
    private PlayerExportJobRepo playerExportJobRepo;

    @Mock
    private RedeemRecordRepo redeemRecordRepo;

    private PlayerExportService newPlayerExportService() {
        PlayerExportService service = new PlayerExportService();
        ReflectionTestUtils.setField(service, "jobRepo", playerExportJobRepo);
        ReflectionTestUtils.setField(service, "gameRepo", gameRepo);
        ReflectionTestUtils.setField(service, "identityRepo", identityRepo);
        ReflectionTestUtils.setField(service, "paymentRepo", paymentRepo);
        ReflectionTestUtils.setField(service, "loginLogRepo", loginLogRepo);
        ReflectionTestUtils.setField(service, "redeemRecordRepo", redeemRecordRepo);
        ReflectionTestUtils.setField(service, "auditLog", auditLog);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "storageDir", tempDir.toString());
        ReflectionTestUtils.setField(service, "retentionHours", 72);
        return service;
    }

    private PlayerExportJobEntity exportJob(String id, PlayerExportJobEntity.Status status, String sections) {
        PlayerExportJobEntity job = new PlayerExportJobEntity();
        job.id = id;
        job.gameId = "game_demo";
        job.playerId = "p1";
        job.status = status;
        job.sections = sections;
        job.exportFormat = "json";
        job.fileName = id + ".json";
        job.expiresAt = LocalDateTime.now().plusHours(24);
        return job;
    }

    @Test
    @DisplayName("ingestPayment：playerId/orderId/amount 缺失分支与冲突兜底重抛")
    void paymentIngestEdgeBranches() {
        PlayerDataQueryService service = new PlayerDataQueryService();
        ReflectionTestUtils.setField(service, "gameRepo", gameRepo);
        ReflectionTestUtils.setField(service, "identityRepo", identityRepo);
        ReflectionTestUtils.setField(service, "paymentRepo", paymentRepo);
        ReflectionTestUtils.setField(service, "loginLogRepo", loginLogRepo);

        GameEntity game = new GameEntity();
        game.id = "game_demo";
        when(gameRepo.findById("game_demo")).thenReturn(Optional.of(game));

        PlayerPaymentEntity noPlayer = new PlayerPaymentEntity();
        noPlayer.gameId = "game_demo";
        noPlayer.orderId = "o1";
        noPlayer.amount = new BigDecimal("5");
        assertThrows(IllegalArgumentException.class, () -> service.ingestPayment(noPlayer));

        PlayerPaymentEntity noOrder = new PlayerPaymentEntity();
        noOrder.gameId = "game_demo";
        noOrder.playerId = "p1";
        noOrder.amount = new BigDecimal("5");
        assertThrows(IllegalArgumentException.class, () -> service.ingestPayment(noOrder));

        PlayerPaymentEntity noAmount = new PlayerPaymentEntity();
        noAmount.gameId = "game_demo";
        noAmount.playerId = "p1";
        noAmount.orderId = "o1";
        assertThrows(IllegalArgumentException.class, () -> service.ingestPayment(noAmount));

        // 并发冲突且兜底查询无既有记录 → 重抛 DataIntegrityViolationException
        PlayerPaymentEntity conflict = new PlayerPaymentEntity();
        conflict.gameId = "game_demo";
        conflict.playerId = "p1";
        conflict.orderId = "o1";
        conflict.amount = new BigDecimal("5");
        when(paymentRepo.findByGameIdAndOrderId("game_demo", "o1"))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.empty());
        when(paymentRepo.save(any(PlayerPaymentEntity.class)))
            .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate"));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
            () -> service.ingestPayment(conflict));
    }

    @Test
    @DisplayName("processExportJob：完成保存抛异常 → markAsFailed + RuntimeException")
    void exportProcessJobFailureBranch() {
        ExportService service = new ExportService();
        ReflectionTestUtils.setField(service, "exportJobRepo", exportJobRepo);
        ReflectionTestUtils.setField(service, "auditLogService", auditLog);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());

        ExportJobEntity job = new ExportJobEntity();
        job.id = "ex_1";
        job.gameId = "g";
        job.exportStatus = ExportJobEntity.ExportStatus.PENDING;
        job.exportType = "events";
        when(exportJobRepo.findById("ex_1")).thenReturn(Optional.of(job));
        when(exportJobRepo.save(any(ExportJobEntity.class)))
            .thenReturn(job)
            .thenThrow(new RuntimeException("db down"))
            .thenReturn(job);

        assertThrows(RuntimeException.class, () -> service.processExportJob("ex_1"));
        assertEquals(ExportJobEntity.ExportStatus.FAILED, job.exportStatus);
    }

    @Test
    @DisplayName("processPendingExports：单任务失败内层 catch 与外层 catch")
    void exportProcessPendingExportsBranches() {
        ExportService service = new ExportService();
        ReflectionTestUtils.setField(service, "exportJobRepo", exportJobRepo);
        ReflectionTestUtils.setField(service, "auditLogService", auditLog);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());

        ExportJobEntity done = new ExportJobEntity();
        done.id = "ex_done";
        done.exportStatus = ExportJobEntity.ExportStatus.COMPLETED;
        when(exportJobRepo.findPending()).thenReturn(List.of(done));
        when(exportJobRepo.findById("ex_done")).thenReturn(Optional.of(done));
        service.processPendingExports();  // 非 PENDING → ISE 被内层 catch

        when(exportJobRepo.findPending()).thenThrow(new RuntimeException("boom"));
        service.processPendingExports();  // 外层 catch
    }

    @Test
    @DisplayName("sweep：单任务失败跳过继续处理 + 外层异常兜底")
    void playerExportSweepBranches() {
        PlayerExportService service = newPlayerExportService();

        PlayerExportJobEntity bad = exportJob("pex_bad", PlayerExportJobEntity.Status.COMPLETED, "[\"payments\"]");
        PlayerExportJobEntity good = exportJob("pex_good", PlayerExportJobEntity.Status.PENDING, "[\"payments\"]");
        when(playerExportJobRepo.findByStatusOrderByCreatedAtAsc(PlayerExportJobEntity.Status.PENDING))
            .thenReturn(List.of(bad, good));
        when(playerExportJobRepo.findById("pex_bad")).thenReturn(Optional.of(bad));
        when(playerExportJobRepo.findById("pex_good")).thenReturn(Optional.of(good));
        when(playerExportJobRepo.save(any(PlayerExportJobEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        when(paymentRepo.findByGameIdAndPlayerIdOrderByPaidAtDesc("game_demo", "p1"))
            .thenReturn(List.of());

        service.sweep();

        assertEquals(PlayerExportJobEntity.Status.COMPLETED, good.status);
        assertNotNull(good.filePath);

        when(playerExportJobRepo.findByStatusOrderByCreatedAtAsc(PlayerExportJobEntity.Status.PENDING))
            .thenThrow(new RuntimeException("boom"));
        service.sweep();  // 外层 catch
    }

    @Test
    @DisplayName("cleanup：filePath 为空跳过删除 + 目录删除 IOException + 外层异常")
    void playerExportCleanupBranches() throws Exception {
        PlayerExportService service = newPlayerExportService();

        PlayerExportJobEntity noFile = exportJob("pex_nofile", PlayerExportJobEntity.Status.COMPLETED, null);
        noFile.filePath = null;
        noFile.expiresAt = LocalDateTime.now().minusHours(1);

        Path dir = tempDir.resolve("nonEmptyDir");
        Files.createDirectories(dir);
        Files.write(dir.resolve("f.txt"), "x".getBytes());
        PlayerExportJobEntity dirJob = exportJob("pex_dir", PlayerExportJobEntity.Status.COMPLETED, null);
        dirJob.filePath = dir.toString();
        dirJob.expiresAt = LocalDateTime.now().minusHours(1);

        when(playerExportJobRepo.findByStatusAndExpiresAtBefore(
            eq(PlayerExportJobEntity.Status.COMPLETED), any(LocalDateTime.class)))
            .thenReturn(List.of(noFile, dirJob));
        when(playerExportJobRepo.save(any(PlayerExportJobEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        service.cleanup();

        assertEquals(PlayerExportJobEntity.Status.EXPIRED, noFile.status);
        assertEquals(PlayerExportJobEntity.Status.EXPIRED, dirJob.status);

        when(playerExportJobRepo.findByStatusAndExpiresAtBefore(
            eq(PlayerExportJobEntity.Status.COMPLETED), any(LocalDateTime.class)))
            .thenThrow(new RuntimeException("boom"));
        service.cleanup();  // 外层 catch
    }

    @Test
    @DisplayName("fromJson：sections 空 → 全分区 + 非法 JSON → 失败")
    void playerExportFromJsonBranches() {
        PlayerExportService service = newPlayerExportService();

        PlayerExportJobEntity full = exportJob("pex_full", PlayerExportJobEntity.Status.PENDING, null);
        when(playerExportJobRepo.findById("pex_full")).thenReturn(Optional.of(full));
        when(playerExportJobRepo.save(any(PlayerExportJobEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        when(identityRepo.findByPlayerId("game_demo", "p1")).thenReturn(Optional.empty());
        when(paymentRepo.findByGameIdAndPlayerIdOrderByPaidAtDesc("game_demo", "p1")).thenReturn(List.of());
        when(loginLogRepo.findByGameIdAndPlayerIdOrderByLoginAtDesc("game_demo", "p1")).thenReturn(List.of());
        when(redeemRecordRepo.findByGameIdAndPlayerKeyOrderByRedeemedAtDesc("game_demo", "p1"))
            .thenReturn(List.of());

        PlayerExportJobEntity processed = service.process("pex_full");
        assertEquals(PlayerExportJobEntity.Status.COMPLETED, processed.status);
        assertNotNull(processed.filePath);
        assertTrue(Files.exists(Path.of(processed.filePath)));

        PlayerExportJobEntity badSections =
            exportJob("pex_badjson", PlayerExportJobEntity.Status.PENDING, "not-json");
        when(playerExportJobRepo.findById("pex_badjson")).thenReturn(Optional.of(badSections));
        assertThrows(IllegalStateException.class, () -> service.process("pex_badjson"));
        assertEquals(PlayerExportJobEntity.Status.FAILED, badSections.status);
    }

    @Test
    @DisplayName("download：filePath 缺失与文件不存在分支")
    void playerExportDownloadBranches() {
        PlayerExportService service = newPlayerExportService();

        PlayerExportJobEntity missingPath =
            exportJob("pex_nopath", PlayerExportJobEntity.Status.COMPLETED, null);
        missingPath.filePath = null;
        when(playerExportJobRepo.findById("pex_nopath")).thenReturn(Optional.of(missingPath));
        IllegalStateException noPathEx = assertThrows(IllegalStateException.class,
            () -> service.download("pex_nopath"));
        assertTrue(noPathEx.getMessage().contains("missing"));

        PlayerExportJobEntity goneFile = exportJob("pex_gone", PlayerExportJobEntity.Status.COMPLETED, null);
        goneFile.filePath = tempDir.resolve("nope.json").toString();
        when(playerExportJobRepo.findById("pex_gone")).thenReturn(Optional.of(goneFile));
        IllegalStateException goneEx = assertThrows(IllegalStateException.class,
            () -> service.download("pex_gone"));
        assertTrue(goneEx.getMessage().contains("missing"));
    }

}
