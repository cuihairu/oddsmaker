package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.jpa.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 数据导出服务
 * 管理用户数据导出请求和文件生成。
 *
 * <p>导出执行是真实的：按 exportType 白名单映射到 ClickHouse 明细表，
 * 时间窗/列名白名单校验后参数化查询，逐行写 CSV/JSON 文件落盘，
 * totalRows/fileSizeBytes 均为实际值；CH 异常诚实 FAILED。
 */
@Service
@Transactional
public class ExportService {

    private static final Logger logger = LoggerFactory.getLogger(ExportService.class);

    /** exportType → ClickHouse 明细表与时间列（实体注释枚举的白名单收窄；reports 是聚合产物不直出）。 */
    private static final Map<String, ExportTable> EXPORT_TABLES = Map.of(
        "events", new ExportTable("events", "ts_server"),
        "users", new ExportTable("identities", "first_seen"),
        "sessions", new ExportTable("sessions", "session_start"),
        "risk_cases", new ExportTable("risk_events", "ts"));

    /** 列名形态白名单（列无法参数化，只能严格校验后拼接）。 */
    private static final java.util.regex.Pattern IDENTIFIER =
        java.util.regex.Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]{0,63}$");

    private record ExportTable(String table, String timeColumn) {}

    @Autowired
    private ExportJobRepo exportJobRepo;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private WebhookService webhookService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ClickHouseClient clickHouse;

    /** 导出文件落盘根目录。 */
    @org.springframework.beans.factory.annotation.Value("${oddsmaker.export.storage-dir:data/exports}")
    private String storageDir;

    /** 单次导出行数上限：结果集全量进内存，防大表 OOM（截断在 statusMessage 标注）。 */
    @org.springframework.beans.factory.annotation.Value("${oddsmaker.export.max-rows:100000}")
    private int maxRows;

    /**
     * 创建导出任务
     */
    public ExportJobEntity createExportJob(String gameId, String environmentId, String userId,
                                          String exportType, LocalDateTime startTime, LocalDateTime endTime,
                                          String exportFormat, Map<String, Object> filters,
                                          Map<String, Object> dataSource, List<String> columns,
                                          String compression, Boolean notifyOnComplete, String notificationEmail) {

        ExportJobEntity job = new ExportJobEntity();
        job.id = "ex_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        job.gameId = gameId;
        job.environmentId = environmentId;
        job.userId = userId;
        job.exportType = exportType;
        job.startTime = startTime;
        job.endTime = endTime;
        job.exportFormat = exportFormat != null ? exportFormat : "csv";
        job.compression = compression;
        job.notifyOnComplete = notifyOnComplete;
        job.notificationEmail = notificationEmail;
        job.exportStatus = ExportJobEntity.ExportStatus.PENDING;

        // 生成文件名
        job.fileName = generateFileName(job);

        try {
            if (filters != null) {
                job.filters = objectMapper.writeValueAsString(filters);
            }
            if (dataSource != null) {
                job.dataSource = objectMapper.writeValueAsString(dataSource);
            }
            if (columns != null) {
                job.columns = objectMapper.writeValueAsString(columns);
            }
        } catch (Exception e) {
            logger.error("Failed to serialize export config", e);
        }

        job = exportJobRepo.save(job);

        // 记录审计日志
        auditLogService.logDataExport(exportType, job.id, job.fileName, userId, userId, null);

        logger.info("Created export job: {} for user: {} in game: {}", job.id, userId, gameId);
        return job;
    }

    /**
     * 处理导出任务
     */
    public ExportJobEntity processExportJob(String exportJobId) {
        ExportJobEntity job = exportJobRepo.findById(exportJobId)
            .orElseThrow(() -> new IllegalArgumentException("Export job not found: " + exportJobId));

        if (!job.isPending()) {
            throw new IllegalStateException("Export job is not in PENDING status: " + job.exportStatus);
        }

        job.markAsProcessing();
        exportJobRepo.save(job);

        try {
            // 真实导出：CH 查询 → 文件落盘
            String filePath = exportToFile(job);
            long fileSize = java.nio.file.Files.size(java.nio.file.Path.of(filePath));

            job.markAsCompleted(filePath, fileSize, job.totalRows);
            exportJobRepo.save(job);

            logger.info("Completed export job: {} - {} ({} bytes)", job.id, job.fileName, fileSize);

            // 完成派发（任务状态机保证一次性；notifyOnComplete 是邮件语义，保持独立）
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("event_type", WebhookService.EVENT_EXPORT_COMPLETE);
            payload.put("job_id", job.id);
            payload.put("game_id", job.gameId);
            payload.put("user_id", job.userId);
            payload.put("export_type", job.exportType);
            payload.put("export_format", job.exportFormat);
            payload.put("total_rows", job.totalRows);
            payload.put("file_size_bytes", fileSize);
            payload.put("file_name", job.fileName);
            payload.put("completed_at", LocalDateTime.now().toString());
            try {
                webhookService.sendCustomWebhook(job.gameId, WebhookService.EVENT_EXPORT_COMPLETE, payload);
            } catch (Exception e) {
                logger.warn("Export complete webhook dispatch failed: {}", e.getMessage());
            }

            if (Boolean.TRUE.equals(job.notifyOnComplete) && job.notificationEmail != null) {
                sendCompletionNotification(job);
            }

        } catch (Exception e) {
            job.markAsFailed(e.getMessage());
            exportJobRepo.save(job);
            logger.error("Failed to process export job: {} - {}", job.id, e.getMessage(), e);
            throw new RuntimeException("Failed to process export job", e);
        }

        return job;
    }

    /**
     * 获取导出任务详情
     */
    @Transactional(readOnly = true)
    public ExportJobEntity getExportJob(String exportJobId) {
        return exportJobRepo.findById(exportJobId)
            .orElseThrow(() -> new IllegalArgumentException("Export job not found: " + exportJobId));
    }

    /**
     * 获取用户的导出任务列表
     */
    @Transactional(readOnly = true)
    public List<ExportJobEntity> getUserExports(String userId) {
        return exportJobRepo.findByUserId(userId);
    }

    /**
     * 获取游戏的导出任务列表
     */
    @Transactional(readOnly = true)
    public List<ExportJobEntity> getGameExports(String gameId) {
        return exportJobRepo.findByGameId(gameId);
    }

    /**
     * 取消导出任务
     */
    public ExportJobEntity cancelExportJob(String exportJobId, String reason) {
        ExportJobEntity job = exportJobRepo.findById(exportJobId)
            .orElseThrow(() -> new IllegalArgumentException("Export job not found: " + exportJobId));

        if (job.isProcessing()) {
            job.markAsCancelled(reason);
            exportJobRepo.save(job);

            logger.info("Cancelled export job: {} - reason: {}", job.id, reason);
        }

        return job;
    }

    /**
     * 获取导出统计
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getExportStats(String gameId) {
        List<ExportJobEntity> allJobs = exportJobRepo.findByGameId(gameId);

        long totalJobs = allJobs.size();
        long completedJobs = allJobs.stream().filter(ExportJobEntity::isCompleted).count();
        long failedJobs = allJobs.stream().filter(ExportJobEntity::isFailed).count();
        long processingJobs = allJobs.stream().filter(ExportJobEntity::isProcessing).count();

        Long totalFileSize = exportJobRepo.sumFileSizeByGameId(gameId);

        Map<String, Long> byType = allJobs.stream()
            .filter(j -> j.exportType != null)
            .collect(Collectors.groupingBy(j -> j.exportType, Collectors.counting()));

        Map<String, Long> byFormat = allJobs.stream()
            .filter(j -> j.exportFormat != null)
            .collect(Collectors.groupingBy(j -> j.exportFormat, Collectors.counting()));

        Map<String, Long> byStatus = allJobs.stream()
            .collect(Collectors.groupingBy(j -> j.exportStatus.name(), Collectors.counting()));

        return Map.of(
            "totalJobs", totalJobs,
            "completedJobs", completedJobs,
            "failedJobs", failedJobs,
            "processingJobs", processingJobs,
            "totalFileSizeBytes", totalFileSize != null ? totalFileSize : 0L,
            "successRate", totalJobs > 0 ? (double) completedJobs / totalJobs : 0.0,
            "byType", byType,
            "byFormat", byFormat,
            "byStatus", byStatus
        );
    }

    /**
     * 定期处理待处理的导出任务
     */
    @Scheduled(fixedDelay = 30000)  // 每30秒执行一次
    public void processPendingExports() {
        try {
            List<ExportJobEntity> pendingJobs = exportJobRepo.findPending();

            for (ExportJobEntity job : pendingJobs) {
                try {
                    processExportJob(job.id);
                } catch (Exception e) {
                    logger.error("Failed to process export job {}: {}", job.id, e.getMessage());
                }
            }

            if (!pendingJobs.isEmpty()) {
                logger.debug("Processed {} pending export jobs", pendingJobs.size());
            }
        } catch (Exception e) {
            logger.error("Failed to process pending exports", e);
        }
    }

    /**
     * 定期清理过期的导出文件
     */
    @Scheduled(cron = "0 0 4 * * ?")  // 每天凌晨4点执行
    @Transactional
    public void cleanupExpiredExports() {
        try {
            LocalDateTime expireAt = LocalDateTime.now().minusDays(30);  // 保留30天
            // 先按到期清单删实际文件（deleteExpired 删行后 filePath 不可再得），再删任务记录
            List<ExportJobEntity> expired = exportJobRepo.findExpired(expireAt);
            for (ExportJobEntity job : expired) {
                if (job.filePath == null) {
                    continue;
                }
                try {
                    java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(job.filePath));
                } catch (Exception e) {
                    logger.warn("Failed to delete expired export file {}: {}", job.filePath, e.getMessage());
                }
            }
            int deleted = exportJobRepo.deleteExpired(expireAt);

            if (deleted > 0) {
                logger.info("Cleaned up {} expired export jobs", deleted);
            }
        } catch (Exception e) {
            logger.error("Failed to cleanup expired exports", e);
        }
    }

    /**
     * 定期检查超时的任务
     */
    @Scheduled(fixedDelay = 60000)  // 每分钟执行一次
    @Transactional
    public void checkTimeoutExports() {
        try {
            LocalDateTime timeout = LocalDateTime.now().minusHours(1);  // 1小时超时
            List<ExportJobEntity> processingJobs = exportJobRepo.findProcessing();

            for (ExportJobEntity job : processingJobs) {
                if (job.startedAt != null && job.startedAt.isBefore(timeout)) {
                    job.markAsFailed("Export timeout");
                    exportJobRepo.save(job);
                    logger.warn("Export job timed out: {}", job.id);
                }
            }
        } catch (Exception e) {
            logger.error("Failed to check timeout exports", e);
        }
    }

    // 私有辅助方法

    private String generateFileName(ExportJobEntity job) {
        String timestamp = LocalDateTime.now().toLocalDate().toString().replace("-", "");
        return String.format("%s_%s_%s.%s",
            job.exportType != null ? job.exportType : "export",
            job.gameId,
            timestamp,
            job.exportFormat != null ? job.exportFormat : "csv"
        );
    }

    /**
     * 真实导出：按 exportType 白名单查 ClickHouse 明细，写 CSV/JSON 文件落盘。
     * 表名走白名单、列名走标识符正则、值全 ? 参数化；行数达 maxRows 上限截断并标注。
     * 格式/压缩通道不支持、CH 异常均抛出（processExportJob 诚实 FAILED）。
     *
     * @return 落盘文件的绝对路径
     */
    private String exportToFile(ExportJobEntity job) throws Exception {
        ExportTable target = EXPORT_TABLES.get(job.exportType == null ? "" : job.exportType.trim());
        if (target == null) {
            throw new IllegalArgumentException(
                "exportType must be one of " + EXPORT_TABLES.keySet() + ", got: " + job.exportType);
        }

        String format = job.exportFormat == null ? "csv" : job.exportFormat.toLowerCase(Locale.ROOT).trim();
        if (!format.equals("csv") && !format.equals("json")) {
            throw new IllegalArgumentException(
                "exportFormat '" + job.exportFormat + "' has no real export channel (csv|json only)");
        }

        String compression = job.compression == null || job.compression.isBlank()
            ? "none" : job.compression.toLowerCase(Locale.ROOT).trim();
        boolean gzip;
        switch (compression) {
            case "none" -> gzip = false;
            case "gzip" -> gzip = true;
            default -> throw new IllegalArgumentException(
                "compression '" + job.compression + "' not supported for single-file export (none|gzip)");
        }

        List<String> columns = parseColumns(job.columns);

        StringBuilder sql = new StringBuilder("SELECT ")
            .append(columns.isEmpty() ? "*" : String.join(", ", columns))
            .append(" FROM ").append(target.table())
            .append(" WHERE game_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(job.gameId);
        if (job.startTime != null) {
            sql.append(" AND ").append(target.timeColumn()).append(" >= ?");
            args.add(job.startTime);
        }
        if (job.endTime != null) {
            sql.append(" AND ").append(target.timeColumn()).append(" < ?");
            args.add(job.endTime);
        }
        sql.append(" ORDER BY ").append(target.timeColumn()).append(" LIMIT ?");
        args.add(maxRows);

        List<Map<String, Object>> rows = clickHouse.query(sql.toString(), args.toArray());
        if (rows.size() >= maxRows) {
            job.statusMessage = "truncated at max-rows cap (" + maxRows + ")";
        }
        job.totalRows = (long) rows.size();

        java.nio.file.Path dir = java.nio.file.Path.of(storageDir, job.gameId);
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Path file = dir.resolve(gzip ? job.fileName + ".gz" : job.fileName);

        try (java.io.OutputStream os = new java.io.FileOutputStream(file.toFile());
             java.io.Writer writer = new java.io.OutputStreamWriter(
                 gzip ? new java.util.zip.GZIPOutputStream(os) : os, java.nio.charset.StandardCharsets.UTF_8)) {
            if (format.equals("json")) {
                objectMapper.writeValue(writer, rows);
            } else {
                writeCsv(writer, rows, columns);
            }
        }

        logger.info("Exported {} rows for job: {} -> {}", rows.size(), job.id, file);
        return file.toString();
    }

    /** 解析 columns JSON 数组；null/空白 → 空表（SELECT * 全列）。每项过标识符正则。 */
    private List<String> parseColumns(String columnsJson) {
        if (columnsJson == null || columnsJson.isBlank()) {
            return List.of();
        }
        try {
            com.fasterxml.jackson.databind.JsonNode array = objectMapper.readTree(columnsJson);
            if (!array.isArray()) {
                throw new IllegalArgumentException("columns must be a JSON array: " + columnsJson);
            }
            List<String> columns = new ArrayList<>();
            array.forEach(node -> {
                String col = node.asText();
                if (!IDENTIFIER.matcher(col).matches()) {
                    throw new IllegalArgumentException(
                        "column must match ^[a-zA-Z_][a-zA-Z0-9_]{0,63}$, got: " + col);
                }
                columns.add(col);
            });
            return columns;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("columns is not valid JSON: " + columnsJson, e);
        }
    }

    /** RFC 4180 CSV：含逗号/引号/换行的值加引号并将引号翻倍；表头取指定列或首行列集。 */
    private void writeCsv(java.io.Writer writer, List<Map<String, Object>> rows, List<String> columns)
            throws java.io.IOException {
        List<String> header = columns.isEmpty()
            ? (rows.isEmpty() ? List.of() : new ArrayList<>(rows.get(0).keySet()))
            : columns;
        if (!header.isEmpty()) {
            writer.write(String.join(",", header.stream().map(ExportService::csvEscape).toList()) + "\n");
        }
        for (Map<String, Object> row : rows) {
            List<String> cells = new ArrayList<>();
            for (String col : header) {
                cells.add(csvEscape(csvValue(row.get(col))));
            }
            writer.write(String.join(",", cells) + "\n");
        }
    }

    private String csvValue(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof String || v instanceof Number || v instanceof Boolean) {
            return String.valueOf(v);
        }
        try {
            return objectMapper.writeValueAsString(v);  // 数组/Map 等复合值序列化为 JSON 字面量
        } catch (Exception e) {
            return String.valueOf(v);
        }
    }

    private static String csvEscape(String v) {
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    private void sendCompletionNotification(ExportJobEntity job) {
        // 无邮件投递通道：不假装已发送（对齐 SSO/IntegrationService 诚实化先例）
        logger.warn("Email delivery channel is not configured; completion notification skipped for job: {} (email: {})",
            job.id, job.notificationEmail);
    }

    /**
     * 获取用户的导出统计
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getUserExportStats(String userId) {
        long totalExports = exportJobRepo.countByUserId(userId);
        long completedExports = exportJobRepo.countCompletedByUserId(userId);

        List<ExportJobEntity> recentExports = exportJobRepo.findByUserId(userId).stream()
            .limit(10)
            .collect(Collectors.toList());

        return Map.of(
            "totalExports", totalExports,
            "completedExports", completedExports,
            "successRate", totalExports > 0 ? (double) completedExports / totalExports : 0.0,
            "recentExports", recentExports
        );
    }
}
