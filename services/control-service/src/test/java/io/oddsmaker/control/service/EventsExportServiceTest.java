package io.oddsmaker.control.service;

import io.oddsmaker.control.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 全量原始数据导出测试：分批游标分页、JSONL/gzip 产出、manifest、分区发现与校验。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("全量原始数据导出服务测试")
class EventsExportServiceTest {

    @Mock
    private ClickHouseClient ch;

    @TempDir
    Path tempDir;

    private EventsExportService service;

    @BeforeEach
    void setUp() {
        service = new EventsExportService(ch, tempDir.toString());
    }

    private Map<String, Object> row(String eventId) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("game_id", "game_a");
        r.put("environment", "prod");
        r.put("event_date", java.sql.Date.valueOf("2026-09-20"));
        r.put("ts_server", Timestamp.from(Instant.parse("2026-09-20T08:00:00Z")));
        r.put("event_id", eventId);
        r.put("event_name", "purchase");
        r.put("revenue_amount", new java.math.BigDecimal("1.50"));
        r.put("props_json", "{}");
        return r;
    }

    @Test
    @DisplayName("exportDay：JSONL 分批写出 + manifest（行数/SHA-256/字节数）+ 游标分页 SQL")
    void exportDayWritesJsonlAndManifest() throws Exception {
        when(ch.isAvailable()).thenReturn(true);
        // 两批：第一批 BATCH_SIZE 不会触发（返回 2 < BATCH_SIZE 即停）
        when(ch.query(anyString(), any(Object[].class)))
                .thenReturn(List.of(row("e1"), row("e2")));

        Map<String, Object> manifest = service.exportDay("game_a", "prod", "2026-09-20", false);

        assertEquals(2L, manifest.get("rows"));
        assertEquals("jsonl", manifest.get("format"));
        assertNotNull(manifest.get("sha256"));
        assertEquals(64, String.valueOf(manifest.get("sha256")).length());

        Path file = tempDir.resolve("game_a/events/dt=2026-09-20/events-prod.jsonl");
        assertTrue(Files.exists(file));
        List<String> lines = Files.readAllLines(file);
        assertEquals(2, lines.size());
        // Timestamp → ISO、BigDecimal → 纯数字串
        assertTrue(lines.get(0).contains("2026-09-20T08:00:00Z"));
        assertTrue(lines.get(0).contains("\"revenue_amount\":\"1.50\""));
        // manifest 与数据同目录
        assertTrue(Files.exists(file.getParent().resolve("manifest.json")));
        // keyset 分页条件与参数化
        verify(ch).query(contains("event_id > ? ORDER BY event_id"),
                eq("game_a"), eq("prod"), eq(java.sql.Date.valueOf("2026-09-20")), eq(""));
    }

    @Test
    @DisplayName("exportDay：gzip 输出可解压回相同行数")
    void exportDayGzip() throws Exception {
        when(ch.isAvailable()).thenReturn(true);
        when(ch.query(anyString(), any(Object[].class)))
                .thenReturn(List.of(row("e1"), row("e2"), row("e3")));

        Map<String, Object> manifest = service.exportDay("game_a", "prod", "2026-09-20", true);

        assertEquals("jsonl+gzip", manifest.get("format"));
        Path gz = tempDir.resolve("game_a/events/dt=2026-09-20/events-prod.jsonl.gz");
        assertTrue(Files.exists(gz));
        try (GZIPInputStream in = new GZIPInputStream(Files.newInputStream(gz))) {
            String body = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(3, body.split("\n").length);
        }
        // 无裸 jsonl 残留
        assertFalse(Files.exists(gz.getParent().resolve("events-prod.jsonl")));
    }

    @Test
    @DisplayName("exportDay：非法日期 / 缺环境 / CH 不可用 均被拒")
    void exportDayRejectsInvalidInput() {
        when(ch.isAvailable()).thenReturn(true);
        assertThrows(BusinessException.class, () -> service.exportDay("game_a", "prod", "2026/09/20", false));
        assertThrows(BusinessException.class, () -> service.exportDay("game_a", " ", "2026-09-20", false));

        when(ch.isAvailable()).thenReturn(false);
        assertThrows(BusinessException.class, () -> service.exportDay("game_a", "prod", "2026-09-20", false));
    }

    @Test
    @DisplayName("listDays：按日期倒序 + 环境过滤，无分区返回空")
    void listDaysSortedAndFiltered() {
        when(ch.isAvailable()).thenReturn(true);
        when(ch.query(anyString(), any(Object[].class))).thenReturn(List.of(row("e1")));

        service.exportDay("game_a", "prod", "2026-09-20", false);
        service.exportDay("game_a", "prod", "2026-09-21", false);

        List<Map<String, Object>> days = service.listDays("game_a", "prod");
        assertEquals(2, days.size());
        assertEquals("2026-09-21", days.get(0).get("date"));
        assertEquals("2026-09-20", days.get(1).get("date"));

        // 环境不匹配 → 空
        assertTrue(service.listDays("game_a", "dev").isEmpty());
        // 未知游戏 → 空
        assertTrue(service.listDays("game_b", "prod").isEmpty());
    }
}
