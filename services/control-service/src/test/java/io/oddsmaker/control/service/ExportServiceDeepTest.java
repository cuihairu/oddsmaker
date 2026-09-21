package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.jpa.ExportJobEntity;
import io.oddsmaker.control.jpa.ExportJobRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * ExportService 真实导出做真后的深度测试：
 * exportType→表映射 SQL 形态、CSV/JSON/gzip 真落盘内容、
 * 格式/压缩/列名守卫与清理真删文件。
 */
class ExportServiceDeepTest {

    private ExportService service;
    private ClickHouseClient ch;
    private ExportJobRepo repo;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        service = new ExportService();
        ch = mock(ClickHouseClient.class);
        repo = mock(ExportJobRepo.class);
        ReflectionTestUtils.setField(service, "exportJobRepo", repo);
        ReflectionTestUtils.setField(service, "auditLogService", mock(AuditLogService.class));
        ReflectionTestUtils.setField(service, "webhookService", mock(WebhookService.class));
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "clickHouse", ch);
        ReflectionTestUtils.setField(service, "storageDir", tempDir.toString());
        ReflectionTestUtils.setField(service, "maxRows", 1000);
        when(repo.save(any(ExportJobEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private ExportJobEntity job(String exportType, String format, String compression) {
        ExportJobEntity j = new ExportJobEntity();
        j.id = "ex_" + exportType;
        j.gameId = "g1";
        j.userId = "u1";
        j.exportType = exportType;
        j.exportFormat = format;
        j.compression = compression;
        j.exportStatus = ExportJobEntity.ExportStatus.PENDING;
        j.fileName = exportType + "." + format;
        when(repo.findById(j.id)).thenReturn(java.util.Optional.of(j));
        return j;
    }

    /** 处理任务并捕获下发 CH 的 SQL 与参数。 */
    private Captured process(ExportJobEntity j, List<Map<String, Object>> rows) {
        when(ch.query(anyString(), any(Object[].class))).thenReturn(rows);
        ExportJobEntity out = service.processExportJob(j.id);
        assertEquals(ExportJobEntity.ExportStatus.COMPLETED, out.exportStatus);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(ch, atLeastOnce()).query(sql.capture(), args.capture());
        int last = sql.getAllValues().size() - 1;
        return new Captured(out, sql.getAllValues().get(last), args.getAllValues().get(last));
    }

    private record Captured(ExportJobEntity job, String sql, Object[] args) {}

    // ==================== exportType → 表映射 ====================

    @Test
    @DisplayName("四种 exportType 映射正确表与时间列（含时间窗形态）")
    void exportTypeTableMapping() {
        ExportJobEntity withWindow = job("events", "json", null);
        withWindow.startTime = LocalDateTime.of(2026, 1, 1, 0, 0);
        withWindow.endTime = LocalDateTime.of(2026, 2, 1, 0, 0);
        var events = process(withWindow, List.of(Map.of("event_id", "e1")));
        assertEquals("SELECT * FROM events WHERE game_id = ? AND ts_server >= ? AND ts_server < ? ORDER BY ts_server LIMIT ?",
            events.sql());
        assertEquals("g1", events.args()[0]);
        assertEquals(1000, events.args()[events.args().length - 1]);

        var users = process(job("users", "csv", null), List.of());
        assertTrue(users.sql().startsWith("SELECT * FROM identities WHERE game_id = ?"));
        assertTrue(users.sql().contains("ORDER BY first_seen"));

        var sessions = process(job("sessions", "csv", null), List.of());
        assertTrue(sessions.sql().contains("FROM sessions"));
        assertTrue(sessions.sql().contains("ORDER BY session_start"));

        var risk = process(job("risk_cases", "csv", null), List.of());
        assertTrue(risk.sql().contains("FROM risk_events"));
        assertTrue(risk.sql().contains("ORDER BY ts"));
    }

    @Test
    @DisplayName("未知 exportType → 诚实 FAILED（不再无差别模拟成功）")
    void unknownExportTypeFails() {
        ExportJobEntity j = job("reports", "csv", null);
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> service.processExportJob(j.id));
        assertEquals(ExportJobEntity.ExportStatus.FAILED, j.exportStatus);
        assertTrue(ex.getCause().getMessage().contains("exportType must be one of"));
        assertTrue(j.errorMessage.contains("reports"));
    }

    // ==================== 落盘内容 ====================

    @Test
    @DisplayName("CSV 真写：表头 + 行内容 + RFC4180 转义")
    void csvContentWritten() throws Exception {
        java.util.Map<String, Object> escaped = new java.util.LinkedHashMap<>();
        escaped.put("event_id", "e,1");
        escaped.put("event_type", "login\"x");
        escaped.put("score", 10);
        java.util.Map<String, Object> plain = new java.util.LinkedHashMap<>();
        plain.put("event_id", "e2");
        plain.put("event_type", "plain");
        plain.put("score", 20);
        when(ch.query(anyString(), any(Object[].class))).thenReturn(List.of(escaped, plain));
        ExportJobEntity j = job("events", "csv", null);
        ExportJobEntity out = service.processExportJob(j.id);

        String content = Files.readString(Path.of(out.filePath));
        String[] lines = content.split("\n");
        assertEquals(3, lines.length);
        assertEquals("event_id,event_type,score", lines[0]);   // 表头 = 首行列集
        assertEquals("\"e,1\",\"login\"\"x\",10", lines[1]);   // 逗号/引号转义
        assertEquals("e2,plain,20", lines[2]);
        assertEquals(2L, out.totalRows);
        assertEquals(2L, out.exportedRows);
        assertEquals(Files.size(Path.of(out.filePath)), out.fileSizeBytes);  // 实际文件大小
    }

    @Test
    @DisplayName("JSON 真写：数组内容与行一致")
    void jsonContentWritten() throws Exception {
        var c = process(job("events", "json", null),
            List.of(Map.of("event_id", "e1", "event_type", "login")));
        String content = Files.readString(Path.of(c.job().filePath));
        assertTrue(content.startsWith("["));
        assertTrue(content.contains("\"event_id\":\"e1\""));
        assertTrue(content.contains("\"event_type\":\"login\""));
    }

    @Test
    @DisplayName("gzip 真压缩：文件加 .gz 且解压读回内容一致")
    void gzipReallyCompresses() throws Exception {
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("event_id", "e1");
        row.put("event_type", "login");
        when(ch.query(anyString(), any(Object[].class))).thenReturn(List.of(row));
        ExportJobEntity j = job("events", "csv", "gzip");
        ExportJobEntity out = service.processExportJob(j.id);

        assertTrue(out.filePath.endsWith(".gz"));
        try (GZIPInputStream gz = new GZIPInputStream(Files.newInputStream(Path.of(out.filePath)))) {
            String content = new String(gz.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(content.contains("event_id,event_type"));
            assertTrue(content.contains("e1,login"));
        }
    }

    @Test
    @DisplayName("空结果：csv 0 行无表头、json 为 []")
    void emptyResults() throws Exception {
        ExportJobEntity csv = job("events", "csv", null);
        var c1 = process(csv, List.of());
        assertEquals(0, Files.size(Path.of(c1.job().filePath)));   // 无表头 0 字节
        assertEquals(0L, c1.job().totalRows);

        ExportJobEntity json = job("users", "json", null);
        var c2 = process(json, List.of());
        assertEquals("[]", Files.readString(Path.of(c2.job().filePath)));
    }

    @Test
    @DisplayName("指定 columns 进 SELECT 与表头；非法列名拒绝")
    void columnSelectionAndGuard() throws Exception {
        ExportJobEntity j = job("events", "csv", null);
        j.columns = "[\"event_id\", \"event_type\"]";
        var c = process(j, List.of(Map.of("event_id", "e1", "event_type", "login")));
        assertTrue(c.sql().startsWith("SELECT event_id, event_type FROM events"));
        String content = Files.readString(Path.of(c.job().filePath));
        assertEquals("event_id,event_type\ne1,login\n", content);  // 表头只用指定列

        ExportJobEntity bad = job("events", "csv", null);
        bad.columns = "[\"event_id, (SELECT 1)\"]";
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> service.processExportJob(bad.id));
        assertEquals(ExportJobEntity.ExportStatus.FAILED, bad.exportStatus);
        assertTrue(ex.getCause().getMessage().contains("column must match"));
    }

    // ==================== 格式与压缩守卫 ====================

    @Test
    @DisplayName("excel/parquet 无导出通道、zip 压缩不支持 → 诚实 FAILED")
    void unsupportedFormatAndCompression() {
        for (String[] bad : new String[][]{{"excel", "none"}, {"parquet", "none"}, {"csv", "zip"}}) {
            ExportJobEntity j = job("events", bad[0], bad[1]);
            assertThrows(RuntimeException.class, () -> service.processExportJob(j.id));
            assertEquals(ExportJobEntity.ExportStatus.FAILED, j.exportStatus);
        }
    }

    // ==================== 截断与异常 ====================

    @Test
    @DisplayName("达 maxRows 上限截断并在 statusMessage 标注")
    void truncationMarked() {
        ReflectionTestUtils.setField(service, "maxRows", 2);
        List<Map<String, Object>> rows = List.of(
            Map.of("event_id", "e1"), Map.of("event_id", "e2"));
        var c = process(job("events", "json", null), rows);
        assertEquals(2L, c.job().totalRows);
        assertEquals("truncated at max-rows cap (2)", c.job().statusMessage);
    }

    @Test
    @DisplayName("CH 异常 → job 诚实 FAILED 且上抛（不再洗白）")
    void clickHouseFailurePropagates() {
        ExportJobEntity j = job("events", "csv", null);
        when(ch.query(anyString(), any(Object[].class))).thenThrow(new RuntimeException("CH down"));
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> service.processExportJob(j.id));
        assertEquals(ExportJobEntity.ExportStatus.FAILED, j.exportStatus);
        assertEquals("CH down", ex.getCause().getMessage());
    }

    // ==================== 清理真删文件 ====================

    @Test
    @DisplayName("cleanupExpiredExports：先删实际文件再删任务记录")
    void cleanupDeletesRealFiles() throws Exception {
        Path file = tempDir.resolve("g1").resolve("events.csv");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "event_id\ne1\n");
        ExportJobEntity expired = new ExportJobEntity();
        expired.id = "ex_old";
        expired.filePath = file.toString();
        when(repo.findExpired(any(LocalDateTime.class))).thenReturn(List.of(expired));
        when(repo.deleteExpired(any(LocalDateTime.class))).thenReturn(1);

        service.cleanupExpiredExports();

        assertFalse(Files.exists(file));                       // 实际文件已删（原 TODO）
        verify(repo).deleteExpired(any(LocalDateTime.class));  // 任务记录随后删

        // 文件删除失败不阻塞记录清理
        ExportJobEntity missing = new ExportJobEntity();
        missing.id = "ex_gone";
        missing.filePath = tempDir.resolve("no-such-file.csv").toString();
        when(repo.findExpired(any(LocalDateTime.class))).thenReturn(List.of(missing));
        assertDoesNotThrow(() -> service.cleanupExpiredExports());
        verify(repo, times(2)).deleteExpired(any(LocalDateTime.class));
    }

    // ==================== 缺省兜底与残余分支 ====================

    @Test
    @DisplayName("createExportJob：type/format 缺省兜底与文件名生成")
    void createDefaultsAndFileName() {
        ExportJobEntity a = service.createExportJob("g", "env", "u", null,
            null, null, null, null, null, null, null, false, null);
        assertTrue(a.fileName.startsWith("export_g_"));  // exportType null → "export"
        assertEquals("csv", a.exportFormat);             // format null → csv
        ExportJobEntity b = service.createExportJob("g", "env", "u", "events",
            null, null, "json", null, null, null, null, false, null);
        assertTrue(b.fileName.startsWith("events_g_"));
        assertEquals("json", b.exportFormat);
    }

    @Test
    @DisplayName("getExportJob：不存在 IAE")
    void getExportJobNotFound() {
        assertThrows(IllegalArgumentException.class, () -> service.getExportJob("nope"));
    }

    @Test
    @DisplayName("getExportStats：null 类型过滤 + 文件量 null 兜底")
    void statsNullBranches() {
        ExportJobEntity ok = new ExportJobEntity();
        ok.exportStatus = ExportJobEntity.ExportStatus.COMPLETED;
        // exportType null → byType 过滤分支
        when(repo.findByGameId("g")).thenReturn(List.of(ok));
        when(repo.sumFileSizeByGameId("g")).thenReturn(null);
        Map<String, Object> stats = service.getExportStats("g");
        assertEquals(0L, stats.get("totalFileSizeBytes"));
        assertEquals(0, ((Map<?, ?>) stats.get("byType")).size());
    }

    @Test
    @DisplayName("cleanup：filePath null 跳过、删目录失败不阻塞记录清理")
    void cleanupEdgeCases() throws Exception {
        Path dir = tempDir.resolve("nonempty");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("keep.txt"), "x");  // 非空目录 → deleteIfExists 抛 IOException
        ExportJobEntity noPath = new ExportJobEntity();
        noPath.id = "ex_nopath";            // filePath null → continue 分支
        ExportJobEntity badPath = new ExportJobEntity();
        badPath.id = "ex_dir";
        badPath.filePath = dir.toString();  // 删除失败 → warn 不阻塞
        when(repo.findExpired(any(LocalDateTime.class))).thenReturn(List.of(noPath, badPath));
        when(repo.deleteExpired(any(LocalDateTime.class))).thenReturn(2);

        assertDoesNotThrow(() -> service.cleanupExpiredExports());
        assertTrue(Files.exists(dir));
        verify(repo).deleteExpired(any(LocalDateTime.class));
    }

    @Test
    @DisplayName("exportType null / format null 的兜底通道")
    void nullTypeAndFormatFallbacks() {
        ExportJobEntity nullType = job(null, "csv", null);
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> service.processExportJob(nullType.id));
        assertTrue(ex.getCause().getMessage().contains("exportType must be one of"));

        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("event_id", "e1");
        when(ch.query(anyString(), any(Object[].class))).thenReturn(List.of(row));
        ExportJobEntity nullFormat = job("events", null, null);  // format null → 默认 csv 通道
        ExportJobEntity out = service.processExportJob(nullFormat.id);
        assertEquals(ExportJobEntity.ExportStatus.COMPLETED, out.exportStatus);
    }

    @Test
    @DisplayName("columns：非数组 JSON 与坏 JSON 拒绝")
    void columnsJsonGuards() {
        ExportJobEntity nonArray = job("events", "csv", null);
        nonArray.columns = "{}";
        RuntimeException e1 = assertThrows(RuntimeException.class,
            () -> service.processExportJob(nonArray.id));
        assertTrue(e1.getCause().getMessage().contains("must be a JSON array"));

        ExportJobEntity badJson = job("events", "csv", null);
        badJson.columns = "not-json";
        RuntimeException e2 = assertThrows(RuntimeException.class,
            () -> service.processExportJob(badJson.id));
        assertTrue(e2.getCause().getMessage().contains("not valid JSON"));
    }

    @Test
    @DisplayName("CSV 值分型：null→空串、复合值 JSON 化+转义、不可序列化→String.valueOf")
    void csvValueVariants() throws Exception {
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("null_col", null);
        row.put("arr", List.of(1, 2));       // 复合值 → JSON 化 → 含逗号 → 引号转义
        row.put("mystery", new Object());    // FAIL_ON_EMPTY_BEANS 序列化失败 → String.valueOf 兜底
        when(ch.query(anyString(), any(Object[].class))).thenReturn(List.of(row));
        ExportJobEntity j = job("events", "csv", null);
        ExportJobEntity out = service.processExportJob(j.id);

        String[] lines = Files.readString(Path.of(out.filePath)).split("\n");
        assertEquals("null_col,arr,mystery", lines[0]);
        assertTrue(lines[1].startsWith(",\"[1,2]\","), "line=" + lines[1]);  // 空首列 + JSON 化转义次列
        assertTrue(lines[1].contains("\"[1,2]\""), "line=" + lines[1]);
        assertTrue(lines[1].contains(",java.lang.Object@"), "line=" + lines[1]);
    }

    // ===== 分支对侧补充（BRANCH 收口）=====

    @Test
    @DisplayName("完成通知：notify+email 命中（诚实跳过）与 email 缺省跳过")
    void notifyCompletionBranches() {
        ExportJobEntity notified = job("events", "csv", null);
        notified.notifyOnComplete = true;
        notified.notificationEmail = "ops@example.com";
        process(notified, List.of());   // 两条件均 true → sendCompletionNotification（无通道仅日志）

        ExportJobEntity noEmail = job("events", "json", null);
        noEmail.notifyOnComplete = true;  // notificationEmail null → 第二条件 false
        process(noEmail, List.of());
    }

    @Test
    @DisplayName("checkTimeoutExports：startedAt null、未超时、已超时三形态")
    void timeoutSides() {
        ExportJobEntity nullStart = new ExportJobEntity();
        nullStart.id = "ex_t1";
        nullStart.exportStatus = ExportJobEntity.ExportStatus.PROCESSING;
        ExportJobEntity fresh = new ExportJobEntity();
        fresh.id = "ex_t2";
        fresh.startedAt = java.time.LocalDateTime.now();
        fresh.exportStatus = ExportJobEntity.ExportStatus.PROCESSING;
        ExportJobEntity stale = new ExportJobEntity();
        stale.id = "ex_t3";
        stale.startedAt = java.time.LocalDateTime.now().minusHours(2);
        stale.exportStatus = ExportJobEntity.ExportStatus.PROCESSING;
        when(repo.findProcessing()).thenReturn(List.of(nullStart, fresh, stale));

        service.checkTimeoutExports();

        assertEquals(ExportJobEntity.ExportStatus.PROCESSING, fresh.exportStatus);
        assertEquals(ExportJobEntity.ExportStatus.FAILED, stale.exportStatus);
        verify(repo).save(stale);  // 仅超时项落库
    }

    @Test
    @DisplayName("compression 空白等同 none；columns 空白回落 SELECT *")
    void blankCompressionAndColumns() {
        ExportJobEntity blankCols = job("events", "csv", "   ");
        blankCols.columns = "   ";
        var out = process(blankCols, List.of());
        assertFalse(out.job().filePath.endsWith(".gz"));       // compression isBlank → none
        assertTrue(out.sql().startsWith("SELECT * FROM"));     // columns isBlank → 空列表
    }

    @Test
    @DisplayName("csvValue：Boolean 直写；csvEscape：换行单元格加引号包裹")
    void csvBooleanAndNewlineSides() throws Exception {
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("event_id", "e1");
        row.put("flag", Boolean.TRUE);
        java.util.Map<String, Object> nl = new java.util.LinkedHashMap<>();
        nl.put("event_id", "e\n2");
        nl.put("flag", Boolean.FALSE);
        when(ch.query(anyString(), any(Object[].class))).thenReturn(List.of(row, nl));
        ExportJobEntity j = job("events", "csv", null);
        ExportJobEntity out = service.processExportJob(j.id);

        String content = Files.readString(Path.of(out.filePath));
        assertTrue(content.contains(",true"));    // Boolean instanceof 侧直写
        assertTrue(content.contains("\"e\n2\"")); // \n → 引号包裹
    }

    @Test
    @DisplayName("csvEscape：\\r 单独触发引号包裹（前三条件均不命中的第四侧）")
    void csvCarriageReturnSide() throws Exception {
        java.util.Map<String, Object> cr = new java.util.LinkedHashMap<>();
        cr.put("event_id", "e\r3");   // 不含 , " \n，仅 \r → 第四条件 true 侧
        when(ch.query(anyString(), any(Object[].class))).thenReturn(List.of(cr));
        ExportJobEntity out = service.processExportJob(job("events", "csv", null).id);

        String content = Files.readString(Path.of(out.filePath));
        assertTrue(content.contains("\"e\r3\""));
    }

    @Test
    @DisplayName("generateFileName：exportFormat null 兜底 csv（初始化器+创建兜底外的 null 直达侧）")
    void generateFileNameNullFormatSide() {
        // exportFormat 有 "csv" 初始化器且 createExportJob 先兜底再生成文件名，
        // 公开链路恒非 null——null 侧经反射直调私有方法直达
        ExportJobEntity j = new ExportJobEntity();
        j.gameId = "g1";
        j.exportType = "events";
        j.exportFormat = null;
        String name = org.springframework.test.util.ReflectionTestUtils
            .invokeMethod(service, "generateFileName", j);
        assertTrue(name.endsWith(".csv"), name);
        assertTrue(name.startsWith("events_g1_"), name);
    }
}
