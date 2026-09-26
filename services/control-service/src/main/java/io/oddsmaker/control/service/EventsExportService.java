package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * 全量原始数据导出（P7-4，竞品差距收敛）。
 *
 * events 按日分区导出为 JSONL（gzip 可选）到本地导出目录
 * （oddsmaker.export.storage-dir，与 ExportService 共用；对象存储由运维
 * 挂载/同步该目录实现——归档基建在架构内），供 Superset/Metabase 下钻。
 *
 * 每个分区附带 manifest.json（行数/字节数/SHA-256），导出为原子替换写。
 * 分批游标分页（event_id keyset），内存占用与分区大小无关。
 */
@Service
public class EventsExportService {

    private static final Logger logger = LoggerFactory.getLogger(EventsExportService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 导出列（与 schema/sql/clickhouse/schema.sql events 表对齐） */
    static final List<String> COLUMNS = List.of(
            "game_id", "environment", "event_date", "ts_server", "ts_client",
            "event_id", "event_type", "event_name",
            "user_id", "device_id", "player_id", "character_id", "session_id",
            "platform", "app_version", "sdk_version", "country", "client_ip_hash", "user_agent",
            "server_id", "guild_id", "match_id", "level_id", "game_mode", "difficulty", "progression_path",
            "order_id", "product_id", "revenue_amount", "revenue_currency", "receipt_hash",
            "virtual_currency", "virtual_amount", "flow_type", "item_id", "operation_id", "operation_type",
            "resource_id", "resource_amount",
            "ad_network", "ad_placement", "ad_format", "ad_impression_id",
            "experiments", "attribution", "risk_context", "props_json");

    private static final int BATCH_SIZE = 10_000;
    private static final long MAX_ROWS = 5_000_000L;
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private final ClickHouseClient ch;
    private final String storageDir;

    public EventsExportService(ClickHouseClient ch,
                               @Value("${oddsmaker.export.storage-dir:data/exports}") String storageDir) {
        this.ch = ch;
        this.storageDir = storageDir;
    }

    // ---------------- 导出一个日分区 ----------------

    public Map<String, Object> exportDay(String gameId, String environment, String date, boolean compress) {
        if (gameId == null || gameId.isBlank()) {
            throw new BusinessException("INVALID_GAME: " + "缺少游戏 ID");
        }
        if (environment == null || environment.isBlank() || environment.length() > 100) {
            throw new BusinessException("INVALID_ENV: " + "非法环境: " + environment);
        }
        LocalDate day = parseDate(date);
        if (!ch.isAvailable()) {
            throw new BusinessException("CH_UNAVAILABLE: " + "ClickHouse 未配置，无法导出");
        }

        Path dir = partitionDir(gameId, environment, day);
        String fileName = "events-" + environment + ".jsonl" + (compress ? ".gz" : "");
        Path target = dir.resolve(fileName);
        Path tmp = dir.resolve(fileName + ".tmp");

        long rows = 0;
        String sha256;
        try {
            Files.createDirectories(dir);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (OutputStream fileOut = Files.newOutputStream(tmp);
                 OutputStream gzipOut = compress ? new GZIPOutputStream(fileOut) : fileOut;
                 OutputStream digestOut = new java.security.DigestOutputStream(gzipOut, digest);
                 PrintWriter writer = new PrintWriter(new BufferedWriter(new java.io.OutputStreamWriter(digestOut, StandardCharsets.UTF_8), 1 << 16))) {

                String lastId = "";
                while (true) {
                    List<Map<String, Object>> batch = fetchBatch(gameId, environment, day, lastId);
                    if (batch.isEmpty()) break;
                    for (Map<String, Object> row : batch) {
                        writer.println(JSON.writeValueAsString(toJsonRow(row)));
                        rows++;
                    }
                    lastId = String.valueOf(batch.get(batch.size() - 1).get("event_id"));
                    if (batch.size() < BATCH_SIZE) break;
                    if (rows > MAX_ROWS) {
                        throw new BusinessException("TOO_MANY_ROWS: " + "超出单分区导出上限 " + MAX_ROWS);
                    }
                }
            }
            sha256 = HexFormat.of().formatHex(digest.digest());
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (BusinessException e) {
            cleanupQuietly(tmp);
            throw e;
        } catch (Exception e) {
            cleanupQuietly(tmp);
            logger.error("Events export failed: game={} env={} date={}", gameId, environment, date, e);
            throw new BusinessException("EXPORT_FAILED: " + "导出失败: " + e.getMessage());
        }

        long bytes = target.toFile().length();
        Map<String, Object> manifest = writeManifest(dir, gameId, environment, day, rows, bytes, sha256, compress);
        logger.info("Events exported: game={} env={} date={} rows={} bytes={}", gameId, environment, date, rows, bytes);
        return manifest;
    }

    private List<Map<String, Object>> fetchBatch(String gameId, String environment, LocalDate day, String lastId) {
        String cols = String.join(", ", COLUMNS);
        String sql = "SELECT " + cols + " FROM events"
                + " WHERE game_id = ? AND environment = ? AND event_date = ? AND event_id > ?"
                + " ORDER BY event_id LIMIT " + BATCH_SIZE;
        return ch.query(sql, gameId, environment, java.sql.Date.valueOf(day), lastId);
    }

    /** 行 → 有序 JSON：Timestamp 转 ISO、BigDecimal 转纯数字串，Map 原生序列化 */
    private Map<String, Object> toJsonRow(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String col : COLUMNS) {
            Object v = row.get(col);
            if (v instanceof Timestamp ts) {
                out.put(col, ts.toInstant().toString());
            } else if (v instanceof java.math.BigDecimal bd) {
                out.put(col, bd.toPlainString());
            } else if (v instanceof java.sql.Date d) {
                out.put(col, d.toLocalDate().toString());
            } else {
                out.put(col, v);
            }
        }
        return out;
    }

    private Map<String, Object> writeManifest(Path dir, String gameId, String environment, LocalDate day,
                                              long rows, long bytes, String sha256, boolean compress) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("game_id", gameId);
        manifest.put("environment", environment);
        manifest.put("date", day.format(ISO_DATE));
        manifest.put("format", "jsonl" + (compress ? "+gzip" : ""));
        manifest.put("rows", rows);
        manifest.put("bytes", bytes);
        manifest.put("sha256", sha256);
        manifest.put("generated_at", LocalDateTime.now().toString());
        try {
            Files.write(dir.resolve("manifest.json"), JSON.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(manifest));
        } catch (Exception e) {
            throw new BusinessException("MANIFEST_FAILED: " + "manifest 写入失败");
        }
        return manifest;
    }

    // ---------------- 分区发现 ----------------

    public List<Map<String, Object>> listDays(String gameId, String environment) {
        Path root = Path.of(storageDir, gameId, "events");
        List<Map<String, Object>> days = new ArrayList<>();
        if (!Files.isDirectory(root)) return days;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root, "dt=????-??-??")) {
            for (Path partition : stream) {
                Map<String, Object> info = readManifest(partition, gameId, environment);
                if (info != null) days.add(info);
            }
        } catch (Exception e) {
            logger.warn("Scan export partitions failed: game={}", gameId, e);
        }
        days.sort((a, b) -> String.valueOf(b.get("date")).compareTo(String.valueOf(a.get("date"))));
        return days;
    }

    /** 读分区 manifest；environment 不匹配或 manifest 缺失/损坏则忽略 */
    private Map<String, Object> readManifest(Path partition, String gameId, String environment) {
        try {
            Map<String, Object> m = JSON.readValue(partition.resolve("manifest.json").toFile(),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
            if (environment != null && !environment.isBlank()
                    && !environment.equals(String.valueOf(m.get("environment")))) {
                return null;
            }
            m.putIfAbsent("game_id", gameId);
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------- 内部工具 ----------------

    private Path partitionDir(String gameId, String environment, LocalDate day) {
        return Path.of(storageDir, gameId, "events", "dt=" + day.format(ISO_DATE));
    }

    private LocalDate parseDate(String date) {
        if (date == null) {
            throw new BusinessException("INVALID_DATE: " + "缺少日期");
        }
        try {
            return LocalDate.parse(date, ISO_DATE);
        } catch (Exception e) {
            throw new BusinessException("INVALID_DATE: " + "日期需为 yyyy-MM-dd: " + date);
        }
    }

    private void cleanupQuietly(Path tmp) {
        try {
            Files.deleteIfExists(tmp);
        } catch (Exception ignore) {
            // 清理失败不影响错误上报
        }
    }
}
