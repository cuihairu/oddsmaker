package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.jpa.GameEntity;
import io.oddsmaker.control.jpa.GameRepo;
import io.oddsmaker.control.jpa.IdentityRepo;
import io.oddsmaker.control.jpa.PlayerExportJobEntity;
import io.oddsmaker.control.jpa.PlayerExportJobRepo;
import io.oddsmaker.control.jpa.PlayerLoginLogRepo;
import io.oddsmaker.control.jpa.PlayerPaymentRepo;
import io.oddsmaker.control.jpa.RedeemRecordRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Service 层覆盖率收尾测试（LAY-006 拆分）：导出作业获取与列表分支、下载守卫、创建归一化分区与保留期。与 PlayerExportServiceTest 等互补。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("玩家导出 Service 深度测试（列表/下载/创建归一化）")
class ServicesFinalSweepExportTest {

    @Mock
    private PlayerExportJobRepo jobRepo;

    @Mock
    private IdentityRepo identityRepo;

    @Mock
    private PlayerPaymentRepo paymentRepo;

    @Mock
    private PlayerLoginLogRepo loginLogRepo;

    @Mock
    private RedeemRecordRepo redeemRecordRepo;

    @Mock
    private GameRepo gameRepo;

    @Mock
    private AuditLogService auditLog;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private PlayerExportService playerExportService;

    @TempDir
    Path tempDir;

@BeforeEach
void setUp() {
        ReflectionTestUtils.setField(playerExportService, "storageDir", tempDir.toString());
        ReflectionTestUtils.setField(playerExportService, "retentionHours", 72);
}

    private static GameEntity game(String id, GameEntity.GameStatus status) {
        GameEntity g = new GameEntity();
        g.id = id;
        g.name = "Game-" + id;
        g.status = status;
        return g;
    }

    @Test
    @DisplayName("玩家导出：get 缺失抛异常，list 空 playerId 返回整游戏列表")
    void playerExportGetAndListBranches() {
        GameEntity g = game("game_demo", GameEntity.GameStatus.LIVE);
        lenient().when(gameRepo.findById("game_demo")).thenReturn(Optional.of(g));
        lenient().when(jobRepo.findById("pex_missing")).thenReturn(Optional.empty());
        lenient().when(jobRepo.findByGameIdOrderByCreatedAtDesc("game_demo"))
            .thenReturn(List.of(new PlayerExportJobEntity()));

        assertThrows(IllegalArgumentException.class, () -> playerExportService.get("pex_missing"));
        assertEquals(1, playerExportService.list("game_demo", "  ").size());
        verify(jobRepo).findByGameIdOrderByCreatedAtDesc("game_demo");
        verify(jobRepo, never()).findByGameIdAndPlayerIdOrderByCreatedAtDesc(anyString(), anyString());
    }

    @Test
    @DisplayName("玩家导出：download 对缺失文件/未知路径的守卫与 csv 类型")
    void playerExportDownloadGuards() throws Exception {
        PlayerExportJobEntity missingPath = new PlayerExportJobEntity();
        missingPath.id = "pex_1";
        missingPath.fileName = "a.zip";
        missingPath.status = PlayerExportJobEntity.Status.COMPLETED;
        missingPath.expiresAt = LocalDateTime.now().plusHours(1);
        lenient().when(jobRepo.findById("pex_1")).thenReturn(Optional.of(missingPath));

        IllegalStateException noPath = assertThrows(IllegalStateException.class,
            () -> playerExportService.download("pex_1"));
        assertTrue(noPath.getMessage().contains("missing"));

        PlayerExportJobEntity ghostFile = new PlayerExportJobEntity();
        ghostFile.id = "pex_2";
        ghostFile.fileName = "b.zip";
        ghostFile.exportFormat = "csv";
        ghostFile.status = PlayerExportJobEntity.Status.COMPLETED;
        ghostFile.expiresAt = LocalDateTime.now().plusHours(1);
        ghostFile.filePath = tempDir.resolve("ghost.zip").toString();
        lenient().when(jobRepo.findById("pex_2")).thenReturn(Optional.of(ghostFile));
        assertThrows(IllegalStateException.class, () -> playerExportService.download("pex_2"));

        PlayerExportJobEntity ready = new PlayerExportJobEntity();
        ready.id = "pex_3";
        ready.fileName = "c.zip";
        ready.exportFormat = "csv";
        ready.status = PlayerExportJobEntity.Status.COMPLETED;
        ready.expiresAt = LocalDateTime.now().plusHours(1);
        Path file = tempDir.resolve("c.zip");
        Files.write(file, "PK-dummy".getBytes(StandardCharsets.UTF_8));
        ready.filePath = file.toString();
        lenient().when(jobRepo.findById("pex_3")).thenReturn(Optional.of(ready));

        PlayerExportService.ExportedFile exported = playerExportService.download("pex_3");
        assertEquals("c.zip", exported.fileName());
        assertEquals("application/zip", exported.contentType());
        assertEquals("PK-dummy", new String(exported.content(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("玩家导出：create 分区去重按标准顺序，retentionHours<=0 回退 72 小时")
    void playerExportCreateNormalizesSectionsAndRetention() throws Exception {
        GameEntity g = game("game_demo", GameEntity.GameStatus.LIVE);
        lenient().when(gameRepo.findById("game_demo")).thenReturn(Optional.of(g));
        lenient().when(jobRepo.save(any(PlayerExportJobEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        PlayerExportJobEntity job = playerExportService.create(
            "game_demo", "p1", null, null, List.of("redeem-records", "profile", "redeem-records"), "ops");
        assertEquals(List.of("profile", "redeem-records"),
            objectMapper.readValue(job.sections, List.class));

        ReflectionTestUtils.setField(playerExportService, "retentionHours", 0);
        PlayerExportJobEntity fallback = playerExportService.create(
            "game_demo", "p1", null, null, null, "ops");
        assertTrue(fallback.expiresAt.isAfter(LocalDateTime.now().plusHours(71)));
        ReflectionTestUtils.setField(playerExportService, "retentionHours", 72);
    }

}
