package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.AuditLogEntity;
import io.oddsmaker.control.jpa.AuditLogRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 审计日志服务
 * 提供审计日志的记录和查询功能
 */
@Service
@Transactional
public class AuditLogService {

    private static final Logger logger = LoggerFactory.getLogger(AuditLogService.class);

    @Autowired
    private AuditLogRepo auditLogRepo;

    /**
     * 记录审计日志
     */
    public AuditLogEntity log(AuditLogEntity auditLog) {
        logger.debug("Recording audit log: {} - {} - {}",
            auditLog.action, auditLog.resourceType, auditLog.resourceId);

        return auditLogRepo.save(auditLog);
    }

    /**
     * 通用审计日志重载（供风控/封禁/审核队列等安全操作使用）
     * 参数顺序对齐 BlockListService/ReviewQueueService/FlinkJobService/ReportService 的调用。
     */
    public AuditLogEntity log(AuditLogEntity.AuditAction action,
            String resourceType, String resourceId, String resourceName,
            String details, AuditLogEntity.AuditResult result,
            String actorUserId, String oldValue, String newValue,
            String ip, String userAgent, Map<String, ?> metadata) {

        AuditLogEntity auditLog = new AuditLogEntity();
        auditLog.userId = actorUserId;
        auditLog.username = actorUserId;
        auditLog.action = action;
        auditLog.resourceType = resourceType;
        auditLog.resourceId = resourceId;
        auditLog.resourceName = resourceName;
        auditLog.details = details;
        auditLog.oldValue = oldValue;
        auditLog.newValue = newValue;
        auditLog.ipAddress = ip;
        auditLog.userAgent = userAgent;
        if (metadata != null) {
            Object gameId = metadata.get("gameId");
            if (gameId != null) auditLog.gameId = gameId.toString();
            Object env = metadata.get("environment");
            if (env != null) auditLog.environment = env.toString();
        }
        auditLog.status = result == AuditLogEntity.AuditResult.SUCCESS
            ? AuditLogEntity.AuditStatus.SUCCESS
            : (result == AuditLogEntity.AuditResult.FAILURE
                ? AuditLogEntity.AuditStatus.FAILURE
                : AuditLogEntity.AuditStatus.PARTIAL);

        return log(auditLog);
    }

    /**
     * 记录创建操作
     */
    public AuditLogEntity logCreate(String userId, String username, String resourceType, 
            String resourceId, String resourceName, String newValue, String ip) {
        
        AuditLogEntity auditLog = new AuditLogEntity();
        auditLog.userId = userId;
        auditLog.username = username;
        auditLog.action = AuditLogEntity.AuditAction.CREATE;
        auditLog.resourceType = resourceType;
        auditLog.resourceId = resourceId;
        auditLog.resourceName = resourceName;
        auditLog.newValue = newValue;
        auditLog.ipAddress = ip;
        auditLog.status = AuditLogEntity.AuditStatus.SUCCESS;
        
        return log(auditLog);
    }

    /**
     * 记录更新操作
     */
    public AuditLogEntity logUpdate(String userId, String username, String resourceType, 
            String resourceId, String resourceName, String oldValue, String newValue, String ip) {
        
        AuditLogEntity auditLog = new AuditLogEntity();
        auditLog.userId = userId;
        auditLog.username = username;
        auditLog.action = AuditLogEntity.AuditAction.UPDATE;
        auditLog.resourceType = resourceType;
        auditLog.resourceId = resourceId;
        auditLog.resourceName = resourceName;
        auditLog.oldValue = oldValue;
        auditLog.newValue = newValue;
        auditLog.ipAddress = ip;
        auditLog.status = AuditLogEntity.AuditStatus.SUCCESS;
        
        return log(auditLog);
    }

    /**
     * 记录删除操作
     */
    public AuditLogEntity logDelete(String userId, String username, String resourceType, 
            String resourceId, String resourceName, String oldValue, String ip) {
        
        AuditLogEntity auditLog = new AuditLogEntity();
        auditLog.userId = userId;
        auditLog.username = username;
        auditLog.action = AuditLogEntity.AuditAction.DELETE;
        auditLog.resourceType = resourceType;
        auditLog.resourceId = resourceId;
        auditLog.resourceName = resourceName;
        auditLog.oldValue = oldValue;
        auditLog.ipAddress = ip;
        auditLog.status = AuditLogEntity.AuditStatus.SUCCESS;
        
        return log(auditLog);
    }

    /**
     * 记录登录操作
     */
    public AuditLogEntity logLogin(String userId, String username, String ip, String userAgent) {
        
        AuditLogEntity auditLog = new AuditLogEntity();
        auditLog.userId = userId;
        auditLog.username = username;
        auditLog.action = AuditLogEntity.AuditAction.LOGIN;
        auditLog.resourceType = "user";
        auditLog.resourceId = userId;
        auditLog.resourceName = username;
        auditLog.ipAddress = ip;
        auditLog.userAgent = userAgent;
        auditLog.status = AuditLogEntity.AuditStatus.SUCCESS;
        
        return log(auditLog);
    }

    /**
     * 记录登录失败
     */
    public AuditLogEntity logLoginFailed(String username, String ip, String userAgent, String errorMessage) {
        
        AuditLogEntity auditLog = new AuditLogEntity();
        auditLog.username = username;
        auditLog.action = AuditLogEntity.AuditAction.LOGIN_FAILED;
        auditLog.resourceType = "user";
        auditLog.resourceName = username;
        auditLog.ipAddress = ip;
        auditLog.userAgent = userAgent;
        auditLog.status = AuditLogEntity.AuditStatus.FAILURE;
        auditLog.errorMessage = errorMessage;
        
        return log(auditLog);
    }

    /**
     * 记录登出操作
     */
    public AuditLogEntity logLogout(String userId, String username, String ip) {
        
        AuditLogEntity auditLog = new AuditLogEntity();
        auditLog.userId = userId;
        auditLog.username = username;
        auditLog.action = AuditLogEntity.AuditAction.LOGOUT;
        auditLog.resourceType = "user";
        auditLog.resourceId = userId;
        auditLog.resourceName = username;
        auditLog.ipAddress = ip;
        auditLog.status = AuditLogEntity.AuditStatus.SUCCESS;
        
        return log(auditLog);
    }

    /**
     * 记录权限变更
     */
    public AuditLogEntity logPermissionChange(String userId, String username, String targetUserId,
            String targetUsername, String action, String oldValue, String newValue, String ip) {

        AuditLogEntity auditLog = new AuditLogEntity();
        auditLog.userId = userId;
        auditLog.username = username;
        auditLog.action = AuditLogEntity.AuditAction.valueOf(action);
        auditLog.resourceType = "user";
        auditLog.resourceId = targetUserId;
        auditLog.resourceName = targetUsername;
        auditLog.oldValue = oldValue;
        auditLog.newValue = newValue;
        auditLog.ipAddress = ip;
        auditLog.status = AuditLogEntity.AuditStatus.SUCCESS;

        return log(auditLog);
    }

    // ========== 便捷重载：resourceType-first 模式（供其他服务统一调用） ==========

    /**
     * 创建审计日志（resourceType-first 参数顺序，带 metadata）
     */
    public AuditLogEntity logCreate(String resourceType, String resourceId, String resourceName,
            String userId, String username, String ip, Map<String, ?> metadata) {
        AuditLogEntity auditLog = new AuditLogEntity();
        auditLog.userId = userId;
        auditLog.username = username != null ? username : userId;
        auditLog.action = AuditLogEntity.AuditAction.CREATE;
        auditLog.resourceType = resourceType;
        auditLog.resourceId = resourceId;
        auditLog.resourceName = resourceName;
        auditLog.ipAddress = ip;
        if (metadata != null) {
            Object gameId = metadata.get("gameId");
            if (gameId != null) auditLog.gameId = gameId.toString();
        }
        auditLog.status = AuditLogEntity.AuditStatus.SUCCESS;
        return log(auditLog);
    }

    /**
     * 更新审计日志（resourceType-first 参数顺序，带 metadata）
     */
    public AuditLogEntity logUpdate(String resourceType, String resourceId, String resourceName,
            String userId, String username, String ip, Map<String, ?> metadata) {
        AuditLogEntity auditLog = new AuditLogEntity();
        auditLog.userId = userId;
        auditLog.username = username != null ? username : userId;
        auditLog.action = AuditLogEntity.AuditAction.UPDATE;
        auditLog.resourceType = resourceType;
        auditLog.resourceId = resourceId;
        auditLog.resourceName = resourceName;
        auditLog.ipAddress = ip;
        if (metadata != null) {
            Object gameId = metadata.get("gameId");
            if (gameId != null) auditLog.gameId = gameId.toString();
        }
        auditLog.status = AuditLogEntity.AuditStatus.SUCCESS;
        return log(auditLog);
    }

    /**
     * 删除审计日志（resourceType-first 参数顺序）
     */
    public AuditLogEntity logDelete(String resourceType, String resourceId, String resourceName,
            String userId, String username, String ip) {
        AuditLogEntity auditLog = new AuditLogEntity();
        auditLog.userId = userId;
        auditLog.username = username != null ? username : userId;
        auditLog.action = AuditLogEntity.AuditAction.DELETE;
        auditLog.resourceType = resourceType;
        auditLog.resourceId = resourceId;
        auditLog.resourceName = resourceName;
        auditLog.ipAddress = ip;
        auditLog.status = AuditLogEntity.AuditStatus.SUCCESS;
        return log(auditLog);
    }

    // ========== 导出/集成日志 ==========

    /**
     * 记录数据导出操作
     */
    public AuditLogEntity logDataExport(String exportType, String jobId, String fileName,
            String userId, String username, String ip) {
        AuditLogEntity auditLog = new AuditLogEntity();
        auditLog.userId = userId;
        auditLog.username = username != null ? username : userId;
        auditLog.action = AuditLogEntity.AuditAction.EXPORT;
        auditLog.resourceType = exportType;
        auditLog.resourceId = jobId;
        auditLog.resourceName = fileName;
        auditLog.ipAddress = ip;
        auditLog.status = AuditLogEntity.AuditStatus.SUCCESS;
        return log(auditLog);
    }

    /**
     * 记录集成创建
     */
    public AuditLogEntity logIntegrationCreate(String integrationId, String name, String type,
            String createdBy, String gameId) {
        AuditLogEntity auditLog = new AuditLogEntity();
        auditLog.userId = createdBy;
        auditLog.username = createdBy;
        auditLog.action = AuditLogEntity.AuditAction.CREATE;
        auditLog.resourceType = type;
        auditLog.resourceId = integrationId;
        auditLog.resourceName = name;
        auditLog.gameId = gameId;
        auditLog.status = AuditLogEntity.AuditStatus.SUCCESS;
        return log(auditLog);
    }

    /**
     * 记录集成调用
     */
    public AuditLogEntity logIntegrationCall(String integrationId, String eventType,
            String result, String gameId) {
        AuditLogEntity auditLog = new AuditLogEntity();
        auditLog.action = AuditLogEntity.AuditAction.READ;
        auditLog.resourceType = "integration_call";
        auditLog.resourceId = integrationId;
        auditLog.details = eventType;
        auditLog.gameId = gameId;
        auditLog.status = "SUCCESS".equals(result)
            ? AuditLogEntity.AuditStatus.SUCCESS
            : AuditLogEntity.AuditStatus.FAILURE;
        return log(auditLog);
    }

    /**
     * 记录集成禁用
     */
    public AuditLogEntity logIntegrationDisable(String integrationId, String name, String gameId) {
        AuditLogEntity auditLog = new AuditLogEntity();
        auditLog.action = AuditLogEntity.AuditAction.DISABLE;
        auditLog.resourceType = "integration";
        auditLog.resourceId = integrationId;
        auditLog.resourceName = name;
        auditLog.gameId = gameId;
        auditLog.status = AuditLogEntity.AuditStatus.SUCCESS;
        return log(auditLog);
    }

    /**
     * 记录集成删除
     */
    public AuditLogEntity logIntegrationDelete(String integrationId, String name, String gameId) {
        AuditLogEntity auditLog = new AuditLogEntity();
        auditLog.action = AuditLogEntity.AuditAction.DELETE;
        auditLog.resourceType = "integration";
        auditLog.resourceId = integrationId;
        auditLog.resourceName = name;
        auditLog.gameId = gameId;
        auditLog.status = AuditLogEntity.AuditStatus.SUCCESS;
        return log(auditLog);
    }

    /**
     * 根据ID查找审计日志
     */
    public AuditLogEntity findById(String id) {
        return auditLogRepo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Audit log not found: " + id));
    }

    /**
     * 分页查询审计日志
     */
    public Page<AuditLogEntity> listAuditLogs(Pageable pageable) {
        return auditLogRepo.findRecentLogs(pageable);
    }

    /**
     * 根据用户ID查找审计日志
     */
    public Page<AuditLogEntity> findByUserId(String userId, Pageable pageable) {
        return auditLogRepo.findByUserIdOrderByCreatedAtDesc(userId, pageable);
    }

    /**
     * 根据资源类型和ID查找审计日志
     */
    public Page<AuditLogEntity> findByResource(String resourceType, String resourceId, Pageable pageable) {
        return auditLogRepo.findByResourceTypeAndResourceIdOrderByCreatedAtDesc(
            resourceType, resourceId, pageable);
    }

    /**
     * 根据操作类型查找审计日志
     */
    public Page<AuditLogEntity> findByAction(AuditLogEntity.AuditAction action, Pageable pageable) {
        return auditLogRepo.findByActionOrderByCreatedAtDesc(action, pageable);
    }

    /**
     * 根据状态查找审计日志
     */
    public Page<AuditLogEntity> findByStatus(AuditLogEntity.AuditStatus status, Pageable pageable) {
        return auditLogRepo.findByStatusOrderByCreatedAtDesc(status, pageable);
    }

    /**
     * 根据时间范围查找审计日志
     */
    public Page<AuditLogEntity> findByTimeRange(LocalDateTime start, LocalDateTime end, Pageable pageable) {
        return auditLogRepo.findByCreatedAtBetweenOrderByCreatedAtDesc(start, end, pageable);
    }

    /**
     * 根据游戏ID查找审计日志
     */
    public Page<AuditLogEntity> findByGameId(String gameId, Pageable pageable) {
        return auditLogRepo.findByGameIdOrderByCreatedAtDesc(gameId, pageable);
    }

    /**
     * 搜索审计日志
     */
    public Page<AuditLogEntity> searchLogs(String query, Pageable pageable) {
        return auditLogRepo.searchLogs(query, pageable);
    }

    /**
     * 获取审计日志统计信息
     */
    public Map<String, Object> getAuditStatistics(int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        
        return Map.of(
            "totalLogs", auditLogRepo.countLogsSince(since),
            "actionsByUser", auditLogRepo.countActionsByUser(since),
            "actionsByResourceType", auditLogRepo.countActionsByResourceType(since),
            "actionsByActionType", auditLogRepo.countActionsByActionType(since),
            "actionsByHour", auditLogRepo.countActionsByHour(since)
        );
    }

    /**
     * 清理旧的审计日志
     */
    @Transactional
    public int cleanupOldLogs(int daysToKeep) {
        LocalDateTime before = LocalDateTime.now().minusDays(daysToKeep);
        int deleted = auditLogRepo.deleteLogsBefore(before);
        logger.info("Cleaned up {} audit logs older than {}", deleted, before);
        return deleted;
    }
}