package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import io.oddsmaker.control.jpa.GameEntity;
import io.oddsmaker.control.jpa.GameRepo;
import io.oddsmaker.control.jpa.IdentityEntity;
import io.oddsmaker.control.jpa.IdentityRepo;
import io.oddsmaker.control.jpa.PlayerExportJobEntity;
import io.oddsmaker.control.jpa.PlayerExportJobRepo;
import io.oddsmaker.control.jpa.PlayerLoginLogEntity;
import io.oddsmaker.control.jpa.PlayerLoginLogRepo;
import io.oddsmaker.control.jpa.PlayerPaymentEntity;
import io.oddsmaker.control.jpa.PlayerPaymentRepo;
import io.oddsmaker.control.jpa.RedeemRecordEntity;
import io.oddsmaker.control.jpa.RedeemRecordRepo;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * 玩家数据导出测试：任务创建校验、json/csv 文件生成、下载与过期、sweep/cleanup。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("玩家数据导出测试")
class PlayerExportServiceTest {

    @Mock
    private PlayerExportJobRepo jobRepo;

    @Mock
    private GameRepo gameRepo;

    @Mock
    private IdentityRepo identityRepo;

    @Mock
    private PlayerPaymentRepo paymentRepo;

    @Mock
    private PlayerLoginLogRepo loginLogRepo;

    @Mock
    private RedeemRecordRepo redeemRecordRepo;

    @Mock
    private AuditLogService auditLog;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @InjectMocks
    private PlayerExportService service;

    @TempDir
    Path tempDir;

    private final GameEntity gameDemo = new GameEntity();

    @BeforeEach
    void setUp() {
        gameDemo.id = "game_demo";
        gameDemo.name = "Demo";
        ReflectionTestUtils.setField(service, "storageDir", tempDir.toString());
        ReflectionTestUtils.setField(service, "retentionHours", 72);
    }

    // ========== 创建 ==========

    @Test
    @DisplayName("创建：playerId 必填、游戏必须存在、format/sections 校验")
    void createValidatesInput() {
        when(gameRepo.findById("game_demo")).thenReturn(Optional.of(gameDemo));
        when(gameRepo.findById("game_nope")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
            () -> service.create("game_demo", " ", null, null, null, "ops1"));
        assertThrows(IllegalArgumentException.class,
            () -> service.create("game_nope", "p1", null, null, null, "ops1"));
        assertThrows(IllegalArgumentException.class,
            () -> service.create("game_demo", "p1", null, "excel", null, "ops1"));
        assertThrows(IllegalArgumentException.class,
            () -> service.create("game_demo", "p1", null, null, List.of("profile", "hack"), "ops1"));
    }

    @Test
    @DisplayName("创建：默认全分区 json、PENDING、72h 过期、审计记录")
    void createDefaults() throws Exception {
        when(gameRepo.findById("game_demo")).thenReturn(Optional.of(gameDemo));
        when(jobRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PlayerExportJobEntity job = service.create("game_demo", "p1", "env_prod", null, null, "ops1");

        assertTrue(job.id.startsWith("pex_"));
        assertEquals("json", job.exportFormat);
        assertEquals(PlayerExportJobEntity.Status.PENDING, job.status);
        assertTrue(job.fileName.endsWith(".json"));
        assertTrue(job.expiresAt.isAfter(LocalDateTime.now().plusHours(71)));
        assertEquals(List.of("profile", "payments", "login-logs", "redeem-records"),
            objectMapper.readValue(job.sections, List.class));
        verify(auditLog).logDataExport(eq("player_export"), eq(job.id), eq(job.fileName),
            eq("ops1"), eq("ops1"), isNull());
    }

    @Test
    @DisplayName("创建：csv 格式 zip 文件名 + 分区子集")
    void createCsvSubset() throws Exception {
        when(gameRepo.findById("game_demo")).thenReturn(Optional.of(gameDemo));
        when(jobRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PlayerExportJobEntity job = service.create("game_demo", "p1", null, "CSV", List.of("payments", "profile"), "ops1");

        assertEquals("csv", job.exportFormat);
        assertTrue(job.fileName.endsWith(".zip"));
        assertEquals(List.of("profile", "payments"),
            objectMapper.readValue(job.sections, List.class));
    }

    // ========== 处理：json ==========

    private static IdentityEntity identity() {
        IdentityEntity identity = new IdentityEntity();
        identity.id = "id_1";
        identity.gameId = "game_demo";
        identity.playerId = "p1";
        identity.primaryId = "p1";
        identity.deviceId = "dev_1";
        identity.deviceType = "ios";
        identity.firstSeenAt = LocalDateTime.now().minusDays(30);
        identity.lastSeenAt = LocalDateTime.now().minusHours(2);
        identity.sessionCount = 9;
        identity.eventCount = 4321L;
        return identity;
    }

    private static PlayerPaymentEntity payment(String orderId, String amount, PlayerPaymentEntity.Status status) {
        PlayerPaymentEntity p = new PlayerPaymentEntity();
        p.gameId = "game_demo";
        p.playerId = "p1";
        p.orderId = orderId;
        p.amount = new BigDecimal(amount);
        p.status = status;
        p.paidAt = LocalDateTime.now().minusDays(1);
        return p;
    }

    private static PlayerLoginLogEntity loginLog() {
        PlayerLoginLogEntity log = new PlayerLoginLogEntity();
        log.gameId = "game_demo";
        log.playerId = "p1";
        log.deviceId = "dev_1";
        log.deviceType = "ios";
        log.loginAt = LocalDateTime.now().minusHours(2);
        return log;
    }

    private static RedeemRecordEntity redeemRecord() {
        RedeemRecordEntity r = new RedeemRecordEntity();
        r.batchId = "batch_1";
        r.gameId = "game_demo";
        r.playerKey = "p1";
        r.seq = 1;
        r.code = "ABCDE23456F";
        r.reward = "{\"gold\":100}";
        r.redeemedAt = LocalDateTime.now().minusDays(3);
        return r;
    }

    private void stubFullPlayerData() {
        when(identityRepo.findByPlayerId("game_demo", "p1")).thenReturn(Optional.of(identity()));
        when(paymentRepo.findByGameIdAndPlayerIdOrderByPaidAtDesc("game_demo", "p1"))
            .thenReturn(List.of(payment("o1", "30", PlayerPaymentEntity.Status.COMPLETED),
                payment("o2", "10", PlayerPaymentEntity.Status.REFUNDED)));
        when(paymentRepo.sumCompletedAmount("game_demo", "p1")).thenReturn(new BigDecimal("30.00"));
        when(paymentRepo.countByGameIdAndPlayerIdAndStatus("game_demo", "p1", PlayerPaymentEntity.Status.COMPLETED)).thenReturn(1L);
        when(loginLogRepo.findByGameIdAndPlayerIdOrderByLoginAtDesc("game_demo", "p1"))
            .thenReturn(List.of(loginLog(), loginLog()));
        when(loginLogRepo.countByGameIdAndPlayerId("game_demo", "p1")).thenReturn(2L);
        PlayerLoginLogEntity last = loginLog();
        when(loginLogRepo.findFirstByGameIdAndPlayerIdOrderByLoginAtDesc("game_demo", "p1"))
            .thenReturn(Optional.of(last));
        when(redeemRecordRepo.findByGameIdAndPlayerKeyOrderByRedeemedAtDesc("game_demo", "p1"))
            .thenReturn(List.of(redeemRecord()));
    }

    @Test
    @DisplayName("处理：json 全分区导出落盘，档案含汇总，行数=档案1+充值2+登录2+兑换1")
    void processJsonExport() throws Exception {
        when(gameRepo.findById("game_demo")).thenReturn(Optional.of(gameDemo));
        when(jobRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        PlayerExportJobEntity job = service.create("game_demo", "p1", null, null, null, "ops1");
        when(jobRepo.findById(job.id)).thenReturn(Optional.of(job));
        stubFullPlayerData();

        PlayerExportJobEntity done = service.process(job.id);

        assertEquals(PlayerExportJobEntity.Status.COMPLETED, done.status);
        assertNotNull(done.completedAt);
        assertEquals(6L, done.rowCount);
        assertTrue(done.fileSizeBytes > 0);
        assertTrue(Files.exists(Path.of(done.filePath)));

        JsonNode root = objectMapper.readTree(Files.readString(Path.of(done.filePath), StandardCharsets.UTF_8));
        assertEquals(job.id, root.get("exportId").asText());
        assertEquals("p1", root.get("playerId").asText());
        assertTrue(root.get("sections").get("profile").get("found").asBoolean());
        assertEquals("dev_1", root.get("sections").get("profile").get("deviceId").asText());
        assertEquals(30, root.get("sections").get("profile").get("totalPaidAmount").decimalValue().intValueExact());
        assertEquals(2, root.get("sections").get("payments").size());
        assertEquals("o1", root.get("sections").get("payments").get(0).get("orderId").asText());
        assertEquals(2, root.get("sections").get("login-logs").size());
        assertEquals("ABCDE23456F", root.get("sections").get("redeem-records").get(0).get("code").asText());
    }

    @Test
    @DisplayName("处理：csv 导出为 zip，每分区一个 CSV 且内容含关键列")
    void processCsvZipExport() throws Exception {
        when(gameRepo.findById("game_demo")).thenReturn(Optional.of(gameDemo));
        when(jobRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        PlayerExportJobEntity job = service.create("game_demo", "p1", null, "csv", null, "ops1");
        when(jobRepo.findById(job.id)).thenReturn(Optional.of(job));
        stubFullPlayerData();

        PlayerExportJobEntity done = service.process(job.id);

        assertEquals(PlayerExportJobEntity.Status.COMPLETED, done.status);
        try (ZipFile zip = new ZipFile(Path.of(done.filePath).toFile())) {
            assertEquals(4, zip.size());
            String payments = new String(zip.getInputStream(zip.getEntry("payments.csv")).readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(payments.contains("o1"));
            assertTrue(payments.contains("orderId"));
            String profile = new String(zip.getInputStream(zip.getEntry("profile.csv")).readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(profile.contains("dev_1"));
            assertTrue(profile.contains("totalPaidAmount"));
            String redeems = new String(zip.getInputStream(zip.getEntry("redeem-records.csv")).readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(redeems.contains("ABCDE23456F"));
            assertNotNull(zip.getEntry("login-logs.csv"));
        }
    }

    @Test
    @DisplayName("处理：数据访问异常标记 FAILED 并抛出")
    void processFailureMarksFailed() {
        when(gameRepo.findById("game_demo")).thenReturn(Optional.of(gameDemo));
        when(jobRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        PlayerExportJobEntity job = service.create("game_demo", "p1", null, null, List.of("payments"), "ops1");
        when(jobRepo.findById(job.id)).thenReturn(Optional.of(job));
        when(paymentRepo.findByGameIdAndPlayerIdOrderByPaidAtDesc("game_demo", "p1"))
            .thenThrow(new RuntimeException("db down"));

        assertThrows(IllegalStateException.class, () -> service.process(job.id));

        assertEquals(PlayerExportJobEntity.Status.FAILED, job.status);
        assertTrue(job.errorMessage.contains("db down"));
    }

    @Test
    @DisplayName("处理：仅 PENDING 可处理")
    void processRejectsNonPending() {
        PlayerExportJobEntity job = new PlayerExportJobEntity();
        job.id = "pex_x";
        job.gameId = "game_demo";
        job.playerId = "p1";
        job.status = PlayerExportJobEntity.Status.COMPLETED;
        when(jobRepo.findById("pex_x")).thenReturn(Optional.of(job));

        assertThrows(IllegalStateException.class, () -> service.process("pex_x"));
    }

    // ========== 下载 ==========

    @Test
    @DisplayName("下载：返回文件字节与类型；未完成/已过期拒绝")
    void downloadRules() throws Exception {
        when(gameRepo.findById("game_demo")).thenReturn(Optional.of(gameDemo));
        when(jobRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        PlayerExportJobEntity job = service.create("game_demo", "p1", null, null, null, "ops1");
        when(jobRepo.findById(job.id)).thenReturn(Optional.of(job));
        stubFullPlayerData();
        service.process(job.id);

        PlayerExportService.ExportedFile file = service.download(job.id);
        assertEquals(job.fileName, file.fileName());
        assertEquals("application/json", file.contentType());
        assertArrayEquals(Files.readAllBytes(Path.of(job.filePath)), file.content());

        // 未完成
        PlayerExportJobEntity pending = service.create("game_demo", "p2", null, null, List.of("payments"), "ops1");
        when(jobRepo.findById(pending.id)).thenReturn(Optional.of(pending));
        assertThrows(IllegalStateException.class, () -> service.download(pending.id));

        // 已过期（sweep 未及清理时兜底拒绝）
        job.expiresAt = LocalDateTime.now().minusHours(1);
        assertThrows(IllegalStateException.class, () -> service.download(job.id));
    }

    @Test
    @DisplayName("下载：文件读取 IO 失败（路径是目录）转 IllegalStateException")
    void downloadReadFailure() throws Exception {
        PlayerExportJobEntity job = new PlayerExportJobEntity();
        job.id = "pex_dir";
        job.fileName = "export.json";
        job.exportFormat = "json";
        job.status = PlayerExportJobEntity.Status.COMPLETED;
        job.expiresAt = LocalDateTime.now().plusHours(1);
        java.nio.file.Path dir = tempDir.resolve("as-directory");
        java.nio.file.Files.createDirectories(dir);
        job.filePath = dir.toString();
        when(jobRepo.findById("pex_dir")).thenReturn(Optional.of(job));

        IllegalStateException ex =
            assertThrows(IllegalStateException.class, () -> service.download("pex_dir"));
        assertTrue(ex.getMessage().contains("Failed to read export file"));
    }

    // ========== 列表 / sweep / cleanup ==========

    @Test
    @DisplayName("列表：按玩家过滤，playerId 缺省返回整个游戏；游戏必须存在")
    void listRules() {
        when(gameRepo.findById("game_demo")).thenReturn(Optional.of(gameDemo));
        when(gameRepo.findById("game_nope")).thenReturn(Optional.empty());
        when(jobRepo.findByGameIdAndPlayerIdOrderByCreatedAtDesc("game_demo", "p1")).thenReturn(List.of());
        when(jobRepo.findByGameIdOrderByCreatedAtDesc("game_demo")).thenReturn(List.of());

        assertEquals(0, service.list("game_demo", "p1").size());
        assertEquals(0, service.list("game_demo", null).size());
        assertThrows(IllegalArgumentException.class, () -> service.list("game_nope", null));
    }

    @Test
    @DisplayName("sweep：处理 PENDING 任务生成文件")
    void sweepProcessesPending() {
        when(gameRepo.findById("game_demo")).thenReturn(Optional.of(gameDemo));
        when(jobRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        PlayerExportJobEntity job = service.create("game_demo", "p1", null, null, List.of("payments"), "ops1");
        when(jobRepo.findById(job.id)).thenReturn(Optional.of(job));
        when(jobRepo.findByStatusOrderByCreatedAtAsc(PlayerExportJobEntity.Status.PENDING))
            .thenReturn(List.of(job));
        when(paymentRepo.findByGameIdAndPlayerIdOrderByPaidAtDesc("game_demo", "p1"))
            .thenReturn(List.of(payment("o1", "30", PlayerPaymentEntity.Status.COMPLETED)));

        service.sweep();

        assertEquals(PlayerExportJobEntity.Status.COMPLETED, job.status);
        assertTrue(Files.exists(Path.of(job.filePath)));
    }

    @Test
    @DisplayName("cleanup：到期任务标记 EXPIRED 并删除文件")
    void cleanupExpiresAndDeletesFiles() throws Exception {
        Path file = tempDir.resolve("gone.json");
        Files.write(file, "{}".getBytes(StandardCharsets.UTF_8));

        PlayerExportJobEntity job = new PlayerExportJobEntity();
        job.id = "pex_old";
        job.gameId = "game_demo";
        job.playerId = "p1";
        job.status = PlayerExportJobEntity.Status.COMPLETED;
        job.filePath = file.toString();
        job.expiresAt = LocalDateTime.now().minusHours(1);
        when(jobRepo.findByStatusAndExpiresAtBefore(eq(PlayerExportJobEntity.Status.COMPLETED), any()))
            .thenReturn(List.of(job));

        service.cleanup();

        assertEquals(PlayerExportJobEntity.Status.EXPIRED, job.status);
        assertFalse(Files.exists(file));
    }

    // ===== 分支对侧补充（BRANCH 收口）=====

    @Test
    @DisplayName("创建：playerId/format/sections 空值侧与超长 playerId 截断、软删游戏拒绝")
    void createSidesAndLongPlayerId() {
        when(gameRepo.findById("game_demo")).thenReturn(Optional.of(gameDemo));
        when(jobRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // playerId null 侧
        assertThrows(IllegalArgumentException.class,
            () -> service.create("game_demo", null, null, null, null, "ops1"));
        // format 空白串 → 默认 json（isBlank 侧）
        assertEquals("json", service.create("game_demo", "p1", null, "  ", null, "ops1").exportFormat);
        // sections 空列表 → 全分区（isEmpty 侧）
        PlayerExportJobEntity job = service.create("game_demo", "p1", null, null, List.of(), "ops1");
        assertEquals(4, job.sections.split(",").length);
        // 超 40 字符 playerId 截断入文件名（length > 40 侧）
        String longId = "p".repeat(50);
        PlayerExportJobEntity longJob = service.create("game_demo", longId, null, null, null, "ops1");
        assertTrue(longJob.fileName.startsWith("player-" + "p".repeat(40) + "-"));

        // 软删游戏拒绝（requireGame filter deletedAt 侧）
        gameDemo.deletedAt = LocalDateTime.now();
        assertThrows(IllegalArgumentException.class,
            () -> service.create("game_demo", "p9", null, null, null, "ops1"));
    }

    @Test
    @DisplayName("处理：手工任务的未知分区走 default 跳过、sections null 回落全分区")
    void processUnknownSectionAndNullSections() throws Exception {
        when(jobRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // 未知分区（default 分支）：跳过，0 行仍 COMPLETED
        PlayerExportJobEntity job = new PlayerExportJobEntity();
        job.id = "pex_weird";
        job.gameId = "game_demo";
        job.playerId = "p1";
        job.exportFormat = "json";
        job.sections = "[\"weird\"]";
        job.status = PlayerExportJobEntity.Status.PENDING;
        job.fileName = "weird.json";
        when(jobRepo.findById("pex_weird")).thenReturn(Optional.of(job));
        PlayerExportJobEntity done = service.process("pex_weird");
        assertEquals(PlayerExportJobEntity.Status.COMPLETED, done.status);
        assertEquals(0L, done.rowCount);

        // sections null（fromJson null 侧）→ 回落全分区
        PlayerExportJobEntity nullSections = new PlayerExportJobEntity();
        nullSections.id = "pex_nullsec";
        nullSections.gameId = "game_demo";
        nullSections.playerId = "p1";
        nullSections.exportFormat = "json";
        nullSections.sections = null;
        nullSections.status = PlayerExportJobEntity.Status.PENDING;
        nullSections.fileName = "null-sec.json";
        when(jobRepo.findById("pex_nullsec")).thenReturn(Optional.of(nullSections));
        // profile 分区（identity 不存在 → found=false）足够驱动全分区路径
        when(identityRepo.findByPlayerId("game_demo", "p1")).thenReturn(Optional.empty());
        when(paymentRepo.findByGameIdAndPlayerIdOrderByPaidAtDesc("game_demo", "p1")).thenReturn(List.of());
        when(loginLogRepo.findByGameIdAndPlayerIdOrderByLoginAtDesc("game_demo", "p1")).thenReturn(List.of());
        when(redeemRecordRepo.findByGameIdAndPlayerKeyOrderByRedeemedAtDesc("game_demo", "p1")).thenReturn(List.of());
        PlayerExportJobEntity done2 = service.process("pex_nullsec");
        assertEquals(PlayerExportJobEntity.Status.COMPLETED, done2.status);
        assertEquals(1L, done2.rowCount);  // profile 档案计 1 行，其余三空分区 0 行

        // sections 纯空白（fromJson isBlank 侧）→ 同样回落全分区
        PlayerExportJobEntity blankSections = new PlayerExportJobEntity();
        blankSections.id = "pex_blanksec";
        blankSections.gameId = "game_demo";
        blankSections.playerId = "p1";
        blankSections.exportFormat = "json";
        blankSections.sections = "   ";
        blankSections.status = PlayerExportJobEntity.Status.PENDING;
        blankSections.fileName = "blank-sec.json";
        when(jobRepo.findById("pex_blanksec")).thenReturn(Optional.of(blankSections));
        PlayerExportJobEntity done3 = service.process("pex_blanksec");
        assertEquals(PlayerExportJobEntity.Status.COMPLETED, done3.status);
        assertEquals(1L, done3.rowCount);  // 与 null sections 同：全分区、profile 计 1 行
    }

    @Test
    @DisplayName("CSV 单元格转义：逗号/引号/换行/回车四种特殊字符各自触发包裹")
    void csvCellEscapesAllSpecialCharacters() throws Exception {
        when(gameRepo.findById("game_demo")).thenReturn(Optional.of(gameDemo));
        when(jobRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        PlayerExportJobEntity job = service.create("game_demo", "p1", null, "csv", null, "ops1");
        when(jobRepo.findById(job.id)).thenReturn(Optional.of(job));

        // 四分区各注入一种特殊字符（不含其他三种），覆盖 || 链每个条件的独立 true 侧：
        // profile.deviceId 含逗号；payments.orderId 含引号；loginLogs.deviceId 含 \n；redeem.code 含 \r
        IdentityEntity idn = identity();
        idn.deviceId = "dev,1";
        when(identityRepo.findByPlayerId("game_demo", "p1")).thenReturn(Optional.of(idn));
        when(paymentRepo.findByGameIdAndPlayerIdOrderByPaidAtDesc("game_demo", "p1"))
            .thenReturn(List.of(payment("o\"1", "10", PlayerPaymentEntity.Status.COMPLETED)));
        when(paymentRepo.sumCompletedAmount("game_demo", "p1")).thenReturn(new BigDecimal("10.00"));
        when(paymentRepo.countByGameIdAndPlayerIdAndStatus("game_demo", "p1", PlayerPaymentEntity.Status.COMPLETED)).thenReturn(1L);
        PlayerLoginLogEntity withNl = loginLog();
        withNl.deviceId = "dev\n2";
        when(loginLogRepo.findByGameIdAndPlayerIdOrderByLoginAtDesc("game_demo", "p1"))
            .thenReturn(List.of(withNl));
        when(loginLogRepo.countByGameIdAndPlayerId("game_demo", "p1")).thenReturn(1L);
        when(loginLogRepo.findFirstByGameIdAndPlayerIdOrderByLoginAtDesc("game_demo", "p1"))
            .thenReturn(Optional.of(withNl));
        RedeemRecordEntity withCr = redeemRecord();
        withCr.code = "AB\rCD";
        when(redeemRecordRepo.findByGameIdAndPlayerKeyOrderByRedeemedAtDesc("game_demo", "p1"))
            .thenReturn(List.of(withCr));

        PlayerExportJobEntity done = service.process(job.id);
        assertEquals(PlayerExportJobEntity.Status.COMPLETED, done.status);
        try (ZipFile zip = new ZipFile(Path.of(done.filePath).toFile())) {
            String profile = new String(zip.getInputStream(zip.getEntry("profile.csv")).readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(profile.contains("\"dev,1\""), profile);   // 逗号 → 包裹
            String payments = new String(zip.getInputStream(zip.getEntry("payments.csv")).readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(payments.contains("\"o\"\"1\""), payments); // 引号 → 包裹+翻倍
            String logins = new String(zip.getInputStream(zip.getEntry("login-logs.csv")).readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(logins.contains("\"dev\n2\""), logins);    // \n → 包裹
            String redeems = new String(zip.getInputStream(zip.getEntry("redeem-records.csv")).readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(redeems.contains("\"AB\rCD\""), redeems);  // \r → 包裹
        }
    }

    @Test
    @DisplayName("下载：expiresAt null 的完成任务可下载")
    void downloadNullExpiresAt() throws Exception {
        Path file = tempDir.resolve("no-exp.json");
        Files.write(file, "{}".getBytes(StandardCharsets.UTF_8));
        PlayerExportJobEntity job = new PlayerExportJobEntity();
        job.id = "pex_noexp";
        job.fileName = "no-exp.json";
        job.exportFormat = "json";
        job.status = PlayerExportJobEntity.Status.COMPLETED;
        job.expiresAt = null;
        job.filePath = file.toString();
        when(jobRepo.findById("pex_noexp")).thenReturn(Optional.of(job));

        PlayerExportService.ExportedFile out = service.download("pex_noexp");
        assertEquals("application/json", out.contentType());
    }

    @Test
    @DisplayName("csv：含逗号/引号的单元格按 RFC4180 转义加引号")
    void csvSpecialCharsQuoted() throws Exception {
        when(jobRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        PlayerExportJobEntity job = new PlayerExportJobEntity();
        job.id = "pex_csvq";
        job.gameId = "game_demo";
        job.playerId = "p1";
        job.exportFormat = "csv";
        job.sections = "[\"payments\"]";
        job.status = PlayerExportJobEntity.Status.PENDING;
        job.fileName = "quoted.zip";
        when(jobRepo.findById("pex_csvq")).thenReturn(Optional.of(job));
        PlayerPaymentEntity tricky = payment("o,1", "30", PlayerPaymentEntity.Status.COMPLETED);
        tricky.productId = "say \"hi\"";
        when(paymentRepo.findByGameIdAndPlayerIdOrderByPaidAtDesc("game_demo", "p1"))
            .thenReturn(List.of(tricky));

        service.process("pex_csvq");

        try (ZipFile zip = new ZipFile(Path.of(job.filePath).toFile())) {
            String csv = new String(zip.getInputStream(zip.getEntry("payments.csv")).readAllBytes(),
                StandardCharsets.UTF_8);
            assertTrue(csv.contains("\"o,1\""));           // 逗号 → 加引号
            assertTrue(csv.contains("\"say \"\"hi\"\"\"")); // 引号 → 双写转义
        }
    }

    @Test
    @DisplayName("cleanup：无到期任务（empty 侧）安静返回")
    void cleanupNoExpiredJobsQuiet() {
        when(jobRepo.findByStatusAndExpiresAtBefore(eq(PlayerExportJobEntity.Status.COMPLETED), any()))
            .thenReturn(List.of());
        assertDoesNotThrow(() -> service.cleanup());
        verify(jobRepo, never()).save(any());
    }
}
