package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.BlockListEntity;
import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.BlockListService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 封禁名单API控制器
 * 提供封禁管理的API接口；鉴权走 AccessGuard 行内风格（game:read / risk:manage，全部 game 级）。
 * 历史形态为 @PreAuthorize hasAuthority('READ_GAME:'+gameId) 拼接式，其中 getBlock 引用了签名中不存在的
 * #gameId（SpEL 求值即抛异常）——现改为先反查实体再按其 gameId 鉴权，顺带修复该悬空引用。
 * 全仓只签发 ROLE_* authority，方法安全开启后这些注解恒 403——故换成权限种子（V0.9.8）+ AccessGuard 解析。
 */
@RestController
@RequestMapping("/api/block-lists")
public class BlockListController {

    private static final Logger logger = LoggerFactory.getLogger(BlockListController.class);

    @Autowired
    private BlockListService blockListService;

    @Autowired
    private AccessGuard accessGuard;

    /**
     * 检查目标是否被封禁
     */
    @GetMapping("/check")
    public ResponseEntity<Map<String, Object>> checkBlock(
            @RequestParam String gameId,
            @RequestParam String targetType,
            @RequestParam String targetValue) {
        accessGuard.requireGamePermission(gameId, "game:read");

        boolean blocked = blockListService.isBlocked(gameId, targetType, targetValue);
        return ResponseEntity.ok(Map.of(
            "blocked", blocked,
            "gameId", gameId,
            "targetType", targetType,
            "targetValue", targetValue
        ));
    }

    /**
     * 获取游戏的活跃封禁列表
     */
    @GetMapping("/active/{gameId}")
    public ResponseEntity<List<BlockListEntity>> getActiveBlocks(@PathVariable String gameId) {
        accessGuard.requireGamePermission(gameId, "game:read");
        List<BlockListEntity> blocks = blockListService.getActiveBlocks(gameId);
        return ResponseEntity.ok(blocks);
    }

    /**
     * 获取封禁详情（按实体的 gameId 鉴权——原注解引用了签名中不存在的 #gameId）
     */
    @GetMapping("/{blockId}")
    public ResponseEntity<BlockListEntity> getBlock(@PathVariable String blockId) {
        BlockListEntity block = blockListService.getBlock(blockId);
        accessGuard.requireGamePermission(block.gameId, "game:read");
        return ResponseEntity.ok(block);
    }

    /**
     * 添加封禁
     */
    @PostMapping
    public ResponseEntity<BlockListEntity> addBlock(@RequestBody BlockRequest request) {
        accessGuard.requireGamePermission(request.gameId, "risk:manage");
        BlockListEntity block = blockListService.addBlock(
            request.gameId,
            request.environmentId,
            request.targetType,
            request.targetValue,
            request.blockReason,
            request.blockCategory,
            request.blockType != null ? BlockListEntity.BlockType.valueOf(request.blockType) : BlockListEntity.BlockType.HARD,
            request.isPermanent != null ? request.isPermanent : false,
            request.durationMinutes,
            request.blockedBy,
            request.riskCaseId,
            request.notes
        );
        return ResponseEntity.ok(block);
    }

    /**
     * 解除封禁
     */
    @PostMapping("/{blockId}/unblock")
    public ResponseEntity<Void> unblock(
            @PathVariable String blockId,
            @RequestParam String gameId,
            @RequestBody UnblockRequest request) {
        accessGuard.requireGamePermission(gameId, "risk:manage");
        blockListService.unblock(blockId, request.unblockedBy, request.reason);
        return ResponseEntity.ok().build();
    }

    /**
     * 批量解除封禁
     */
    @PostMapping("/batch-unblock")
    public ResponseEntity<Map<String, Object>> batchUnblock(
            @RequestParam String gameId,
            @RequestBody BatchUnblockRequest request) {
        accessGuard.requireGamePermission(gameId, "risk:manage");
        int count = blockListService.batchUnblock(request.blockIds, request.unblockedBy, request.reason);
        return ResponseEntity.ok(Map.of("unblocked", count));
    }

    /**
     * 从风险案例创建封禁
     */
    @PostMapping("/from-risk-case/{riskCaseId}")
    public ResponseEntity<BlockListEntity> createFromRiskCase(
            @PathVariable String riskCaseId,
            @RequestParam String gameId,
            @RequestParam String blockedBy) {
        accessGuard.requireGamePermission(gameId, "risk:manage");
        BlockListEntity block = blockListService.createBlockFromRiskCase(riskCaseId, blockedBy);
        return ResponseEntity.ok(block);
    }

    /**
     * 获取封禁统计
     */
    @GetMapping("/stats/{gameId}")
    public ResponseEntity<Map<String, Object>> getBlockStats(@PathVariable String gameId) {
        accessGuard.requireGamePermission(gameId, "game:read");
        Map<String, Object> stats = blockListService.getBlockStats(gameId);
        return ResponseEntity.ok(stats);
    }

    /**
     * 搜索封禁
     */
    @GetMapping("/search/{gameId}")
    public ResponseEntity<List<BlockListEntity>> searchBlocks(
            @PathVariable String gameId,
            @RequestParam String query) {
        accessGuard.requireGamePermission(gameId, "game:read");
        List<BlockListEntity> blocks = blockListService.searchBlocks(gameId, query);
        return ResponseEntity.ok(blocks);
    }

    /**
     * 根据类型获取封禁列表
     */
    @GetMapping("/by-type/{gameId}/{targetType}")
    public ResponseEntity<List<BlockListEntity>> getBlocksByType(
            @PathVariable String gameId,
            @PathVariable String targetType) {
        accessGuard.requireGamePermission(gameId, "game:read");
        List<BlockListEntity> blocks = blockListService.getBlocksByType(gameId, targetType);
        return ResponseEntity.ok(blocks);
    }

    // Request DTOs

    public static class BlockRequest {
        public String gameId;
        public String environmentId;
        public String targetType;  // device_id, user_id, player_id, ip, ip_range, account_id
        public String targetValue;
        public String blockReason;
        public String blockCategory;  // fraud, cheating, abuse, tos_violation, security
        public String blockType;      // HARD, SOFT, TEMPORARY, SHADOW
        public Boolean isPermanent;
        public Integer durationMinutes;
        public String blockedBy;
        public String riskCaseId;
        public String notes;
    }

    public static class UnblockRequest {
        public String unblockedBy;
        public String reason;
    }

    public static class BatchUnblockRequest {
        public List<String> blockIds;
        public String unblockedBy;
        public String reason;
    }
}
