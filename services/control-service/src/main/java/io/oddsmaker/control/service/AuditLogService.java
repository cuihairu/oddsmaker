package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.jpa.AuditLogEntity;
import io.oddsmaker.control.jpa.AuditLogRepo;
import io.oddsmaker.control.jpa.UserEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 审计日志服务
 */
@Service
public class AuditLogService {

    private static final Logger logger = LoggerFactory.getLogger(AuditLogService.class);

    @Autowired
    private AuditLogRepo auditLogRepo;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${oddsmaker.audit.retention-days:365}")
    private int retentionDays;

    /**
     * 记录审计日志
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(AuditLogEntity.AuditAction action, String resourceType, String resourceId, String resourceName, String actionDescription, AuditLogEntity.AuditResult result, String userId, String userName, String userEmail, String clientIp, String userAgent, Map<String, Object> changes) {
        AuditLogEntity log = new AuditLogEntity();
        log.id = "al_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        log.action = action;
        log.resourceType = resourceType;
        log.resourceId = resourceId;
        log.resourceName = resourceName;
        log.actionDescription = actionDescription;
        log.result = result;
        log.userId = userId;
        log.userName = userName;
        log.userEmail = userEmail;
        log.clientIp = clientIp;
        log.userAgent = userAgent;
        log.createdAt = LocalDateTime.now();

        // 设置过期时间
        if (retentionDays > 0) {
            log.expireAt = LocalDateTime.now().plusDays(retentionDays);
        }

        // 序列化变更内容
        if (changes != null && !changes.isEmpty()) {
            try {
                log.changes = objectMapper.writeValueAsString(changes);
            } catch (Exception e) {
                logger.warn("Failed to serialize audit log changes", e);
                log.changes = "{}";
            }
        }

        auditLogRepo.save(log);

        // 如果是需要立即告警的事件，发送告警
        if (log.requiresImmediateAlert()) {
            sendImmediateAlert(log);
        }
    }

    /**
     * 记录成功的操作
     */
    public void logSuccess(AuditLogEntity.AuditAction action, String resourceType, String resourceId, String resourceName, String userId, String userName, String clientIp) {
        log(action, resourceType, resourceId, resourceName, null, AuditLogEntity.AuditResult.SUCCESS, userId, userName, null, clientIp, null, null);
    }

    /**
     * 记录失败的操作
     */
    public void logFailure(AuditLogEntity.AuditAction action, String resourceType, String resourceId, String resourceName, String errorMessage, String userId, String userName, String clientIp) {
        log(action, resourceType, resourceId, resourceName, errorMessage, AuditLogEntity.AuditResult.FAILURE, userId, userName, null, clientIp, null, null);
    }

    /**
     * 记录资源创建
     */
    public void logCreate(String resourceType, String resourceId, String resourceName, String userId, String userName, String clientIp, Map<String, Object> changes) {
        log(AuditLogEntity.AuditAction.CREATE, resourceType, resourceId, resourceName, null, AuditLogEntity.AuditResult.SUCCESS, userId, userName, null, clientIp, null, changes);
    }

    /**
     * 记录资源更新
     */
    public void logUpdate(String resourceType, String resourceId, String resourceName, String userId, String userName, String clientIp, Map<String, Object> changes) {
        log(AuditLogEntity.AuditAction.UPDATE, resourceType, resourceId, resourceName, null, AuditLogEntity.AuditResult.SUCCESS, userId, userName, null, clientIp, null, changes);
    }

    /**
     * 记录资源删除
     */
    public void logDelete(String resourceType, String resourceId, String resourceName, String userId, String userName, String clientIp) {
        log(AuditLogEntity.AuditAction.DELETE, resourceType, resourceId, resourceName, null, AuditLogEntity.AuditResult.SUCCESS, userId, userName, null, clientIp, null, null);
    }

    /**
     * 记录登录
     */
    public void logLogin(UserEntity user, String clientIp, String userAgent, boolean success) {
        AuditLogEntity.AuditResult result = success ? AuditLogEntity.AuditResult.SUCCESS : AuditLogEntity.AuditResult.FAILURE;
        log(AuditLogEntity.AuditAction.LOGIN, "user", user.id, user.name + " (" + user.email + ")",
            success ? "Login successful" : "Login failed", result,
            String.valueOf(user.id), user.name, user.email, clientIp, userAgent, null);
    }

    /**
     * 记录API密钥生成
     */
    public void logApiKeyGeneration(String apiKeyId, String gameName, String userId, String userName, String clientIp) {
        log(AuditLogEntity.AuditAction.API_KEY_GENERATE, "api_key", apiKeyId, gameName,
            "API key generated", AuditLogEntity.AuditResult.SUCCESS,
            userId, userName, null, clientIp, null, null);
    }

    /**
     * 记录API密钥查看（敏感操作）
     */
    public void logApiKeyReveal(String apiKeyId, String gameName, String userId, String userName, String clientIp) {
        log(AuditLogEntity.AuditAction.API_KEY_REVEAL, "api_key", apiKeyId, gameName,
            "API key revealed (sensitive operation)", AuditLogEntity.AuditResult.SUCCESS,
            userId, userName, null, clientIp, null, null);
    }

    /**
     * 记录数据导出
     */
    public void logDataExport(String resourceType, String resourceId, String resourceName, String userId, String userName, String clientIp) {
        log(AuditLogEntity.AuditAction.EXPORT_DATA, resourceType, resourceId, resourceName,
            "Data exported", AuditLogEntity.AuditResult.SUCCESS,
            userId, userName, null, clientIp, null, null);
    }

    /**
     * 记录权限变更
     */
    public void logGrantPermission(String targetUserId, String targetUserName, String roleId, String roleName, String userId, String userName, String clientIp) {
        log(AuditLogEntity.AuditAction.GRANT, "role", roleId, roleName,
            "Granted role to user: " + targetUserName, AuditLogEntity.AuditResult.SUCCESS,
            userId, userName, null, clientIp, null,
            Map.of("targetUserId", targetUserId, "targetUserName", targetUserName));
    }

    /**
     * 发送立即告警
     */
    private void sendImmediateAlert(AuditLogEntity log) {
        try {
            // TODO: 实现告警发送逻辑（邮件、Slack、Webhook等）
            logger.warn("Security alert requiring immediate attention: {}", log.getFullDescription());

            // 标记为已告警
            auditLogRepo.markAsAlerted(log.id);
        } catch (Exception e) {
            logger.error("Failed to send immediate alert for audit log: " + log.id, e);
        }
    }

    /**
     * 查询用户的审计日志
     */
    @Transactional(readOnly = true)
    public List<AuditLogEntity> getUserAuditLogs(String userId) {
        return auditLogRepo.findByUserId(userId);
    }

    /**
     * 查询资源的审计日志
     */
    @Transactional(readOnly = true)
    public List<AuditLogEntity> getResourceAuditLogs(String resourceType, String resourceId) {
        return auditLogRepo.findByResource(resourceType, resourceId);
    }

    /**
     * 查询游戏的审计日志
     */
    @Transactional(readOnly = true)
    public List<AuditLogEntity> getGameAuditLogs(String gameId) {
        return auditLogRepo.findByGameId(gameId);
    }

    /**
     * 查询安全事件
     */
    @Transactional(readOnly = true)
    public List<AuditLogEntity> getSecurityEvents() {
        return auditLogRepo.findSecurityEvents();
    }

    /**
     * 查询敏感操作
     */
    @Transactional(readOnly = true)
    public List<AuditLogEntity> getSensitiveActions() {
        return auditLogRepo.findSensitiveActions();
    }

    /**
     * 查询失败的操作
     */
    @Transactional(readOnly = true)
    public List<AuditLogEntity> getFailedOperations() {
        return auditLogRepo.findFailedOperations();
    }

    /**
     * 定期清理过期日志（每天凌晨执行）
     */
    @Scheduled(cron = "0 0 0 * * ?")
    @Transactional
    public void cleanupExpiredLogs() {
        try {
            int deleted = auditLogRepo.deleteExpiredLogs(LocalDateTime.now());
            if (deleted > 0) {
                logger.info("Cleaned up {} expired audit logs", deleted);
            }
        } catch (Exception e) {
            logger.error("Failed to cleanup expired audit logs", e);
        }
    }

    /**
     * 统计用户操作次数
     */
    @Transactional(readOnly = true)
    public long countUserOperations(String userId, LocalDateTime since) {
        return auditLogRepo.countOperationsByUser(userId, since != null ? since : LocalDateTime.now().minusDays(30));
    }

    /**
     * 统计用户失败操作次数
     */
    @Transactional(readOnly = true)
    public long countUserFailedOperations(String userId, LocalDateTime since) {
        return auditLogRepo.countFailedOperationsByUser(userId, since != null ? since : LocalDateTime.now().minusDays(30));
    }
}
