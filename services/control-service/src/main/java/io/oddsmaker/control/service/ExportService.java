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
 * 管理用户数据导出请求和文件生成
 */
@Service
@Transactional
public class ExportService {

    private static final Logger logger = LoggerFactory.getLogger(ExportService.class);

    @Autowired
    private ExportJobRepo exportJobRepo;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private WebhookService webhookService;

    @Autowired
    private ObjectMapper objectMapper;

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
            // 模拟导出处理
            Thread.sleep(100);  // 模拟处理时间

            // 生成模拟文件
            String filePath = generateFilePath(job);
            long fileSize = simulateExport(job);

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
            int deleted = exportJobRepo.deleteExpired(expireAt);

            // TODO: 删除实际文件

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

    private String generateFilePath(ExportJobEntity job) {
        return String.format("/exports/%s/%s", job.gameId, job.fileName);
    }

    private long simulateExport(ExportJobEntity job) {
        // 模拟导出数据量
        long rows = (long) (Math.random() * 100000) + 1000;
        job.totalRows = rows;

        // 根据格式计算文件大小
        long bytesPerRow = switch (job.exportFormat.toLowerCase()) {
            case "json" -> 200L;
            case "excel" -> 150L;
            case "csv" -> 100L;
            default -> 100L;
        };

        return rows * bytesPerRow;
    }

    private void sendCompletionNotification(ExportJobEntity job) {
        // TODO: 实现邮件通知
        logger.info("Export completion notification sent to: {} for job: {}", job.notificationEmail, job.id);
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
