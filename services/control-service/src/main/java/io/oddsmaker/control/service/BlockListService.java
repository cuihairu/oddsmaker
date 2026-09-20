package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 封禁名单服务
 * 网关层强制拦截的核心实现
 */
@Service
@Transactional
public class BlockListService {

    private static final Logger logger = LoggerFactory.getLogger(BlockListService.class);

    @Autowired
    private BlockListRepo blockListRepo;

    @Autowired
    private RiskCaseRepo riskCaseRepo;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private WebhookService webhookService;

    /**
     * 检查目标是否被封禁
     */
    @Transactional(readOnly = true)
    public boolean isBlocked(String gameId, String targetType, String targetValue) {
        return isBlocked(gameId, null, targetType, targetValue);
    }

    /**
     * 检查目标是否被封禁（带环境）
     */
    @Transactional(readOnly = true)
    public boolean isBlocked(String gameId, String environmentId, String targetType, String targetValue) {
        LocalDateTime now = LocalDateTime.now();

        // 检查特定环境的封禁
        if (environmentId != null) {
            var envBlock = blockListRepo.findActiveBlock(gameId, targetType, targetValue, now);
            if (envBlock.isPresent()) {
                BlockListEntity block = envBlock.get();
                if (environmentId.equals(block.environmentId) || block.environmentId == null) {
                    recordHit(block.id);
                    return true;
                }
            }
        }

        // 检查全局封禁
        var globalBlock = blockListRepo.findActiveBlock(gameId, targetType, targetValue, now);
        if (globalBlock.isPresent()) {
            BlockListEntity block = globalBlock.get();
            if (block.environmentId == null) {
                recordHit(block.id);
                return true;
            }
        }

        return false;
    }

    /**
     * 添加封禁
     */
    public BlockListEntity addBlock(String gameId, String environmentId, String targetType, String targetValue,
                                    String blockReason, String blockCategory, BlockListEntity.BlockType blockType,
                                    boolean isPermanent, Integer durationMinutes, String blockedBy,
                                    String riskCaseId, String notes) {

        // 检查是否已存在
        LocalDateTime now = LocalDateTime.now();
        var existing = blockListRepo.findActiveBlock(gameId, targetType, targetValue, now);
        if (existing.isPresent()) {
            logger.warn("Target {}:{} is already blocked", targetType, targetValue);
            return existing.get();
        }

        BlockListEntity block = new BlockListEntity();
        block.id = "bl_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        block.gameId = gameId;
        block.environmentId = environmentId;
        block.targetType = targetType;
        block.targetValue = targetValue;
        block.targetName = targetValue; // 可以后续通过用户服务获取真实名称
        block.blockReason = blockReason;
        block.blockCategory = blockCategory != null ? blockCategory : "security";
        block.blockType = blockType != null ? blockType : BlockListEntity.BlockType.HARD;
        block.isPermanent = isPermanent;
        block.blockedBy = blockedBy;
        block.blockedAt = now;
        block.riskCaseId = riskCaseId;
        block.notes = notes;

        // 设置过期时间
        if (!isPermanent && durationMinutes != null && durationMinutes > 0) {
            block.expiresAt = now.plusMinutes(durationMinutes);
        }

        block = blockListRepo.save(block);

        // 记录审计日志
        auditLogService.log(
            AuditLogEntity.AuditAction.BLOCK,
            targetType,
            targetValue,
            targetValue,
            blockReason,
            AuditLogEntity.AuditResult.SUCCESS,
            blockedBy,
            null,
            null,
            null,
            null,
            Map.of(
                "gameId", gameId,
                "blockType", blockType.name(),
                "isPermanent", isPermanent,
                "durationMinutes", durationMinutes != null ? durationMinutes : 0
            )
        );

        logger.info("Added block for {}:{} in game {}", targetType, targetValue, gameId);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event_type", WebhookService.EVENT_BLOCK);
        payload.put("block_id", block.id);
        payload.put("game_id", block.gameId);
        payload.put("environment_id", block.environmentId);
        payload.put("target", Map.of(
            "type", block.targetType,
            "id", block.targetValue,
            "name", block.targetName != null ? block.targetName : block.targetValue
        ));
        payload.put("block_type", block.blockType != null ? block.blockType.name() : null);
        payload.put("is_permanent", block.isPermanent);
        payload.put("expires_at", block.expiresAt != null ? block.expiresAt.toString() : null);
        payload.put("block_reason", block.blockReason);
        payload.put("block_category", block.blockCategory);
        payload.put("risk_case_id", block.riskCaseId);
        payload.put("blocked_by", block.blockedBy);
        payload.put("blocked_at", block.blockedAt.toString());
        try {
            webhookService.sendCustomWebhook(gameId, WebhookService.EVENT_BLOCK, payload);
        } catch (Exception e) {
            logger.warn("Block webhook dispatch failed: {}", e.getMessage());
        }

        return block;
    }

    /**
     * 从风险案例创建封禁
     */
    public BlockListEntity createBlockFromRiskCase(String riskCaseId, String blockedBy) {
        RiskCaseEntity riskCase = riskCaseRepo.findById(riskCaseId)
            .orElseThrow(() -> new IllegalArgumentException("Risk case not found: " + riskCaseId));

        // 确定封禁时长
        Integer durationMinutes = null;
        boolean isPermanent = false;

        if (riskCase.riskLevel == RiskCaseEntity.RiskLevel.CRITICAL) {
            isPermanent = true;
        } else {
            durationMinutes = getDefaultDuration(riskCase.riskLevel);
        }

        return addBlock(
            riskCase.gameId,
            riskCase.environmentId,
            riskCase.targetType,
            riskCase.targetId,
            "Risk case: " + riskCase.caseNumber,
            "fraud",
            BlockListEntity.BlockType.HARD,
            isPermanent,
            durationMinutes,
            blockedBy,
            riskCaseId,
            "Auto-created from risk case"
        );
    }

    /**
     * 解除封禁
     */
    public void unblock(String blockId, String unblockedBy, String reason) {
        BlockListEntity block = blockListRepo.findById(blockId)
            .orElseThrow(() -> new IllegalArgumentException("Block not found: " + blockId));

        if (!block.isActive()) {
            logger.warn("Block {} is not active", blockId);
            return;
        }

        block.unblock(unblockedBy, reason);
        blockListRepo.save(block);

        // 记录审计日志
        auditLogService.log(
            AuditLogEntity.AuditAction.UNBLOCK,
            block.targetType,
            block.targetValue,
            block.targetValue,
            reason,
            AuditLogEntity.AuditResult.SUCCESS,
            unblockedBy,
            null,
            null,
            null,
            null,
            Map.of(
                "gameId", block.gameId,
                "originalReason", block.blockReason
            )
        );

        logger.info("Unblocked {}:{} by {}", block.targetType, block.targetValue, unblockedBy);
    }

    /**
     * 批量解除封禁
     */
    public int batchUnblock(List<String> blockIds, String unblockedBy, String reason) {
        int count = 0;
        for (String blockId : blockIds) {
            try {
                unblock(blockId, unblockedBy, reason);
                count++;
            } catch (Exception e) {
                logger.error("Failed to unblock {}: {}", blockId, e.getMessage());
            }
        }
        return count;
    }

    /**
     * 获取游戏的活跃封禁
     */
    @Transactional(readOnly = true)
    public List<BlockListEntity> getActiveBlocks(String gameId) {
        return blockListRepo.findActiveBlocks(gameId, LocalDateTime.now());
    }

    /**
     * 获取封禁详情
     */
    @Transactional(readOnly = true)
    public BlockListEntity getBlock(String blockId) {
        return blockListRepo.findById(blockId)
            .orElseThrow(() -> new IllegalArgumentException("Block not found: " + blockId));
    }

    /**
     * 获取游戏的封禁统计
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getBlockStats(String gameId) {
        LocalDateTime now = LocalDateTime.now();
        List<BlockListEntity> activeBlocks = blockListRepo.findActiveBlocks(gameId, now);

        return Map.of(
            "totalActive", activeBlocks.size(),
            "byType", activeBlocks.stream().collect(Collectors.groupingBy(b -> b.targetType, Collectors.counting())),
            "byCategory", activeBlocks.stream().collect(Collectors.groupingBy(b -> b.blockCategory != null ? b.blockCategory : "unknown", Collectors.counting())),
            "hardBlocks", activeBlocks.stream().filter(b -> b.isHardBlock()).count(),
            "softBlocks", activeBlocks.stream().filter(b -> b.isSoftBlock()).count(),
            "shadowBlocks", activeBlocks.stream().filter(b -> b.isShadowBlock()).count(),
            "permanentBlocks", activeBlocks.stream().filter(b -> Boolean.TRUE.equals(b.isPermanent)).count()
        );
    }

    /**
     * 搜索封禁
     */
    @Transactional(readOnly = true)
    public List<BlockListEntity> searchBlocks(String gameId, String query) {
        return blockListRepo.search(gameId, query);
    }

    /**
     * 根据目标类型获取封禁列表
     */
    @Transactional(readOnly = true)
    public List<BlockListEntity> getBlocksByType(String gameId, String targetType) {
        return blockListRepo.findByGameIdAndTargetType(gameId, targetType);
    }

    /**
     * 定期清理过期封禁（每小时执行）
     */
    @Scheduled(cron = "0 0 * * * ?")
    @Transactional
    @Scheduled(fixedDelay = 60000)  // 每分钟检查一次：过期封禁自动解封
    public void cleanupExpiredBlocks() {
        try {
            List<BlockListEntity> expiredBlocks = blockListRepo.findExpiredBlocks(LocalDateTime.now());
            if (!expiredBlocks.isEmpty()) {
                List<String> expiredIds = expiredBlocks.stream().map(b -> b.id).toList();
                int updated = blockListRepo.batchUnblock(expiredIds, LocalDateTime.now());
                logger.info("Auto-unblocked {} expired blocks", updated);
            }
        } catch (Exception e) {
            logger.error("Failed to cleanup expired blocks", e);
        }
    }

    // 私有辅助方法

    private void recordHit(String blockId) {
        try {
            blockListRepo.recordHit(blockId, LocalDateTime.now());
        } catch (Exception e) {
            logger.warn("Failed to record hit for block {}", blockId);
        }
    }

    private Integer getDefaultDuration(RiskCaseEntity.RiskLevel riskLevel) {
        return switch (riskLevel) {
            case LOW -> 60;         // 1 hour
            case MEDIUM -> 1440;    // 1 day
            case HIGH -> 10080;     // 1 week
            case CRITICAL -> null;  // permanent
        };
    }
}
