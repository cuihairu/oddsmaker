package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 玩家数据导出工具：按 (gameId, playerId) 打包玩家全量数据
 * （identity 档案 + 充值流水 + 登录日志 + 兑换记录）。
 * json 导出单文件；csv 按分区打包 zip。sweep 每 30 秒处理 PENDING 任务，
 * cleanup 每日将到期任务标记 EXPIRED 并删除文件。创建与完成全程审计。
 */
@Service
public class PlayerExportService {

    private static final Logger logger = LoggerFactory.getLogger(PlayerExportService.class);

    /** 支持的导出分区（与查询 API 路径风格一致） */
    public static final List<String> ALL_SECTIONS =
        List.of("profile", "payments", "login-logs", "redeem-records");

    private static final Set<String> FORMATS = Set.of("json", "csv");
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    @Autowired
    private PlayerExportJobRepo jobRepo;

    @Autowired
    private GameRepo gameRepo;

    @Autowired
    private IdentityRepo identityRepo;

    @Autowired
    private PlayerPaymentRepo paymentRepo;

    @Autowired
    private PlayerLoginLogRepo loginLogRepo;

    @Autowired
    private RedeemRecordRepo redeemRecordRepo;

    @Autowired
    private AuditLogService auditLog;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${oddsmaker.player-export.storage-dir:data/player-exports}")
    private String storageDir;

    @Value("${oddsmaker.player-export.retention-hours:72}")
    private int retentionHours;

    /** 导出文件（下载用） */
    public record ExportedFile(String fileName, String contentType, byte[] content) {}

    /** 创建导出任务：默认全分区 json；分区子集可选，格式 json/csv */
    @Transactional
    public PlayerExportJobEntity create(String gameId, String playerId, String environmentId,
                                        String format, List<String> sections, String requestedBy) {
        requireGame(gameId);
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId is required");
        }
        String exportFormat = format == null || format.isBlank() ? "json" : format.toLowerCase();
        if (!FORMATS.contains(exportFormat)) {
            throw new IllegalArgumentException("format must be one of " + FORMATS + ": " + format);
        }
        List<String> selected = normalizeSections(sections);

        PlayerExportJobEntity job = new PlayerExportJobEntity();
        job.id = "pex_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        job.gameId = gameId;
        job.environmentId = environmentId;
        job.playerId = playerId;
        job.exportFormat = exportFormat;
        job.sections = toJson(selected);
        job.status = PlayerExportJobEntity.Status.PENDING;
        job.requestedBy = requestedBy;
        int hours = retentionHours > 0 ? retentionHours : 72;
        job.expiresAt = LocalDateTime.now().plusHours(hours);
        job.fileName = "player-" + sanitize(playerId) + "-" + FILE_TS.format(LocalDateTime.now())
            + ("csv".equals(exportFormat) ? ".zip" : ".json");

        job = jobRepo.save(job);
        auditLog.logDataExport("player_export", job.id, job.fileName, requestedBy, requestedBy, null);
        logger.info("Created player export job: {} game: {} player: {} sections: {}",
            job.id, gameId, playerId, selected);
        return job;
    }

    /** 处理导出任务：PENDING→PROCESSING→COMPLETED/FAILED，生成文件落盘 */
    @Transactional
    public PlayerExportJobEntity process(String jobId) {
        PlayerExportJobEntity job = jobRepo.findById(jobId)
            .orElseThrow(() -> new IllegalArgumentException("Export job not found: " + jobId));
        if (job.status != PlayerExportJobEntity.Status.PENDING) {
            throw new IllegalStateException("Export job is not PENDING: " + job.status);
        }

        job.status = PlayerExportJobEntity.Status.PROCESSING;
        job.startedAt = LocalDateTime.now();
        jobRepo.save(job);

        try {
            Map<String, Object> data = new LinkedHashMap<>();
            long rows = 0;
            for (String section : fromJson(job.sections)) {
                switch (section) {
                    case "profile" -> {
                        data.put(section, profile(job.gameId, job.playerId));
                        rows++;
                    }
                    case "payments" -> {
                        List<PlayerPaymentEntity> payments =
                            paymentRepo.findByGameIdAndPlayerIdOrderByPaidAtDesc(job.gameId, job.playerId);
                        data.put(section, payments);
                        rows += payments.size();
                    }
                    case "login-logs" -> {
                        List<PlayerLoginLogEntity> logs =
                            loginLogRepo.findByGameIdAndPlayerIdOrderByLoginAtDesc(job.gameId, job.playerId);
                        data.put(section, logs);
                        rows += logs.size();
                    }
                    case "redeem-records" -> {
                        List<RedeemRecordEntity> records =
                            redeemRecordRepo.findByGameIdAndPlayerKeyOrderByRedeemedAtDesc(job.gameId, job.playerId);
                        data.put(section, records);
                        rows += records.size();
                    }
                    default -> { /* 不可能：创建时已校验 */ }
                }
            }

            Path file = writeFile(job, data);
            job.fileSizeBytes = Files.size(file);
            job.rowCount = rows;
            job.status = PlayerExportJobEntity.Status.COMPLETED;
            job.completedAt = LocalDateTime.now();
            jobRepo.save(job);
            logger.info("Completed player export job: {} - {} ({} bytes, {} rows)",
                job.id, job.fileName, job.fileSizeBytes, rows);
            return job;
        } catch (Exception e) {
            job.status = PlayerExportJobEntity.Status.FAILED;
            job.errorMessage = e.getMessage();
            job.completedAt = LocalDateTime.now();
            jobRepo.save(job);
            logger.error("Failed player export job: {} - {}", job.id, e.getMessage(), e);
            throw new IllegalStateException("Export failed: " + e.getMessage(), e);
        }
    }

    @Transactional(readOnly = true)
    public PlayerExportJobEntity get(String jobId) {
        return jobRepo.findById(jobId)
            .orElseThrow(() -> new IllegalArgumentException("Export job not found: " + jobId));
    }

    /** 导出任务列表：playerId 缺省时返回整个游戏 */
    @Transactional(readOnly = true)
    public List<PlayerExportJobEntity> list(String gameId, String playerId) {
        requireGame(gameId);
        if (playerId == null || playerId.isBlank()) {
            return jobRepo.findByGameIdOrderByCreatedAtDesc(gameId);
        }
        return jobRepo.findByGameIdAndPlayerIdOrderByCreatedAtDesc(gameId, playerId);
    }

    /** 下载导出文件：仅 COMPLETED 且未过期 */
    @Transactional(readOnly = true)
    public ExportedFile download(String jobId) {
        PlayerExportJobEntity job = get(jobId);
        if (job.status != PlayerExportJobEntity.Status.COMPLETED) {
            throw new IllegalStateException("Export job is not COMPLETED: " + job.status);
        }
        if (job.expiresAt != null && !LocalDateTime.now().isBefore(job.expiresAt)) {
            throw new IllegalStateException("Export file has expired: " + job.fileName);
        }
        if (job.filePath == null) {
            throw new IllegalStateException("Export file is missing: " + job.fileName);
        }
        Path file = Paths.get(job.filePath);
        if (!Files.exists(file)) {
            throw new IllegalStateException("Export file is missing: " + job.fileName);
        }
        try {
            String contentType = "csv".equals(job.exportFormat) ? "application/zip" : "application/json";
            return new ExportedFile(job.fileName, contentType, Files.readAllBytes(file));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read export file: " + job.fileName, e);
        }
    }

    /** 每 30 秒处理 PENDING 导出任务 */
    @Scheduled(fixedDelay = 30_000)
    public void sweep() {
        try {
            for (PlayerExportJobEntity job : jobRepo.findByStatusOrderByCreatedAtAsc(PlayerExportJobEntity.Status.PENDING)) {
                try {
                    process(job.id);
                } catch (Exception e) {
                    logger.error("Sweep failed export job {}: {}", job.id, e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.error("Player export sweep failed", e);
        }
    }

    /** 每日 04:30 清理：到期任务标记 EXPIRED 并删除文件 */
    @Scheduled(cron = "0 30 4 * * ?")
    @Transactional
    public void cleanup() {
        try {
            List<PlayerExportJobEntity> expired = jobRepo.findByStatusAndExpiresAtBefore(
                PlayerExportJobEntity.Status.COMPLETED, LocalDateTime.now());
            for (PlayerExportJobEntity job : expired) {
                if (job.filePath != null) {
                    try {
                        Files.deleteIfExists(Paths.get(job.filePath));
                    } catch (IOException e) {
                        logger.warn("Failed to delete export file {}: {}", job.filePath, e.getMessage());
                    }
                }
                job.status = PlayerExportJobEntity.Status.EXPIRED;
                jobRepo.save(job);
            }
            if (!expired.isEmpty()) {
                logger.info("Expired {} player export jobs", expired.size());
            }
        } catch (Exception e) {
            logger.error("Player export cleanup failed", e);
        }
    }

    // ========== 数据分区 ==========

    /** 玩家档案：identity 基本数据 + 充值/登录汇总（口径与 PlayerDataQueryService 一致） */
    private Map<String, Object> profile(String gameId, String playerId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("gameId", gameId);
        out.put("playerId", playerId);
        out.put("found", false);
        identityRepo.findByPlayerId(gameId, playerId).ifPresent(identity -> {
            out.put("found", true);
            out.put("identityId", identity.id);
            out.put("primaryId", identity.primaryId);
            out.put("userId", identity.userId);
            out.put("characterId", identity.characterId);
            out.put("deviceId", identity.deviceId);
            out.put("deviceType", identity.deviceType);
            out.put("environmentId", identity.environmentId);
            out.put("status", identity.status.name());
            out.put("firstSeenAt", identity.firstSeenAt);
            out.put("lastSeenAt", identity.lastSeenAt);
            out.put("sessionCount", identity.sessionCount);
            out.put("eventCount", identity.eventCount);
            out.put("totalPaidAmount", paymentRepo.sumCompletedAmount(gameId, playerId));
            out.put("paidOrderCount", paymentRepo.countByGameIdAndPlayerIdAndStatus(
                gameId, playerId, PlayerPaymentEntity.Status.COMPLETED));
            out.put("loginCount", loginLogRepo.countByGameIdAndPlayerId(gameId, playerId));
            loginLogRepo.findFirstByGameIdAndPlayerIdOrderByLoginAtDesc(gameId, playerId)
                .ifPresent(last -> out.put("lastLoginAt", last.loginAt));
        });
        return out;
    }

    // ========== 文件生成 ==========

    private Path writeFile(PlayerExportJobEntity job, Map<String, Object> data) throws IOException {
        Path dir = Paths.get(storageDir, job.gameId);
        Files.createDirectories(dir);
        Path file = dir.resolve(job.fileName);
        if ("csv".equals(job.exportFormat)) {
            writeZip(file, data);
        } else {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("exportId", job.id);
            payload.put("gameId", job.gameId);
            payload.put("environmentId", job.environmentId);
            payload.put("playerId", job.playerId);
            payload.put("requestedBy", job.requestedBy);
            payload.put("generatedAt", LocalDateTime.now().toString());
            payload.put("sections", data);
            Files.write(file, objectMapper.writeValueAsBytes(payload));
        }
        job.filePath = file.toString();
        return file;
    }

    /** csv 格式：每分区一个 CSV 打包 zip */
    private void writeZip(Path file, Map<String, Object> data) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            for (Map.Entry<String, Object> section : data.entrySet()) {
                zip.putNextEntry(new ZipEntry(section.getKey() + ".csv"));
                zip.write(sectionCsv(section.getKey(), section.getValue()).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private String sectionCsv(String section, Object value) {
        List<String> header;
        List<List<String>> rows = new ArrayList<>();
        switch (section) {
            case "profile" -> {
                Map<String, Object> p = (Map<String, Object>) value;
                header = new ArrayList<>(p.keySet());
                rows.add(p.values().stream().map(PlayerExportService::csvCell).toList());
            }
            case "payments" -> {
                header = List.of("orderId", "platform", "productId", "amount", "currency",
                    "status", "paidAt", "environmentId");
                for (Object o : (List<?>) value) {
                    PlayerPaymentEntity e = (PlayerPaymentEntity) o;
                    rows.add(List.of(csvCell(e.orderId), csvCell(e.platform), csvCell(e.productId),
                        csvCell(e.amount), csvCell(e.currency), csvCell(e.status),
                        csvCell(e.paidAt), csvCell(e.environmentId)));
                }
            }
            case "login-logs" -> {
                header = List.of("loginAt", "deviceId", "deviceType", "platform",
                    "appVersion", "ipAddress", "country", "environmentId");
                for (Object o : (List<?>) value) {
                    PlayerLoginLogEntity e = (PlayerLoginLogEntity) o;
                    rows.add(List.of(csvCell(e.loginAt), csvCell(e.deviceId), csvCell(e.deviceType),
                        csvCell(e.platform), csvCell(e.appVersion), csvCell(e.ipAddress),
                        csvCell(e.country), csvCell(e.environmentId)));
                }
            }
            case "redeem-records" -> {
                header = List.of("batchId", "code", "seq", "reward", "redeemedAt");
                for (Object o : (List<?>) value) {
                    RedeemRecordEntity e = (RedeemRecordEntity) o;
                    rows.add(List.of(csvCell(e.batchId), csvCell(e.code), csvCell(e.seq),
                        csvCell(e.reward), csvCell(e.redeemedAt)));
                }
            }
            default -> header = List.of();
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(",", header)).append('\n');
        for (List<String> row : rows) {
            sb.append(String.join(",", row)).append('\n');
        }
        return sb.toString();
    }

    private static String csvCell(Object value) {
        if (value == null) {
            return "";
        }
        String s = value.toString();
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }

    // ========== 辅助 ==========

    private static List<String> normalizeSections(List<String> sections) {
        if (sections == null || sections.isEmpty()) {
            return ALL_SECTIONS;
        }
        Set<String> selected = new LinkedHashSet<>();
        for (String section : sections) {
            if (!ALL_SECTIONS.contains(section)) {
                throw new IllegalArgumentException("Unknown section: " + section
                    + ", allowed: " + ALL_SECTIONS);
            }
            selected.add(section);
        }
        return ALL_SECTIONS.stream().filter(selected::contains).toList();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize sections", e);
        }
    }

    private List<String> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return ALL_SECTIONS;
        }
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                .constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse sections: " + json, e);
        }
    }

    private static String sanitize(String playerId) {
        String s = playerId.replaceAll("[^a-zA-Z0-9_-]", "_");
        return s.length() > 40 ? s.substring(0, 40) : s;
    }

    private void requireGame(String gameId) {
        GameEntity game = gameRepo.findById(gameId)
            .filter(g -> g.deletedAt == null)
            .orElseThrow(() -> new IllegalArgumentException("Game not found: " + gameId));
    }
}
