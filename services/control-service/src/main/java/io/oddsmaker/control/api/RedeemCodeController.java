package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.RedeemCodeBatchEntity;
import io.oddsmaker.control.jpa.RedeemCodeEntity;
import io.oddsmaker.control.jpa.RedeemRecordEntity;
import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.RedeemCodeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 兑换码 API。
 * 运营端：/api/games/{gameId}/redeem-batches（创建/列表/详情/停用/码导出）；
 * 游戏服：/api/redeem-codes/redeem（兑换，返回奖励快照凭据）、/api/redeem-codes/history（玩家兑换历史）。
 */
@RestController
public class RedeemCodeController {

    private final RedeemCodeService redeemCodeService;
    private final AccessGuard accessGuard;

    public RedeemCodeController(RedeemCodeService redeemCodeService, AccessGuard accessGuard) {
        this.redeemCodeService = redeemCodeService;
        this.accessGuard = accessGuard;
    }

    @GetMapping("/api/games/{gameId}/redeem-batches")
    public ResponseEntity<List<RedeemCodeBatchEntity>> list(@PathVariable String gameId) {
        accessGuard.requireGamePermission(gameId, "game:read");
        return ResponseEntity.ok(redeemCodeService.listBatches(gameId));
    }

    @GetMapping("/api/games/{gameId}/redeem-batches/{id}")
    public ResponseEntity<RedeemCodeBatchEntity> get(@PathVariable String gameId, @PathVariable String id) {
        accessGuard.requireGamePermission(gameId, "game:read");
        RedeemCodeBatchEntity batch = redeemCodeService.getBatch(id);
        if (batch == null || !batch.gameId.equals(gameId)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(batch);
    }

    /**
     * 创建批次并生成码。UNIQUE：批量一次性码（codePrefix/codeLength 可选）；
     * SHARED：单一通用码（sharedCode 指定，缺省生成）。
     */
    @PostMapping("/api/games/{gameId}/redeem-batches")
    public ResponseEntity<RedeemCodeBatchEntity> create(@PathVariable String gameId,
                                                        @RequestBody RedeemCodeBatchEntity batch,
                                                        @RequestParam(value = "codePrefix", required = false) String codePrefix,
                                                        @RequestParam(value = "codeLength", defaultValue = "12") int codeLength,
                                                        @RequestParam(value = "sharedCode", required = false) String sharedCode) {
        accessGuard.requireGamePermission(gameId, "game:update");
        batch.gameId = gameId;
        return ResponseEntity.ok(redeemCodeService.createBatch(batch, codePrefix, codeLength, sharedCode, currentOperator()));
    }

    /** 停用批次（不可再兑换，已兑换记录保留） */
    @PostMapping("/api/games/{gameId}/redeem-batches/{id}/disable")
    public ResponseEntity<RedeemCodeBatchEntity> disable(@PathVariable String gameId, @PathVariable String id) {
        accessGuard.requireGamePermission(gameId, "game:update");
        RedeemCodeBatchEntity batch = redeemCodeService.getBatch(id);
        if (batch == null || !batch.gameId.equals(gameId)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(redeemCodeService.disable(id, currentOperator()));
    }

    /** 批次码列表（运营导出发码） */
    @GetMapping("/api/games/{gameId}/redeem-batches/{id}/codes")
    public ResponseEntity<List<RedeemCodeEntity>> codes(@PathVariable String gameId, @PathVariable String id) {
        accessGuard.requireGamePermission(gameId, "game:read");
        RedeemCodeBatchEntity batch = redeemCodeService.getBatch(id);
        if (batch == null || !batch.gameId.equals(gameId)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(redeemCodeService.listCodes(id));
    }

    /** 游戏服兑换：成功返回发放凭据（奖励快照）；状态类失败（限领/过期/已兑换/领完）返回 409 + 错误码 */
    @PostMapping("/api/redeem-codes/redeem")
    public ResponseEntity<Map<String, Object>> redeem(@RequestParam String gameId,
                                                      @RequestParam String playerKey,
                                                      @RequestParam String code) {
        accessGuard.requireGamePermission(gameId, "game:read");
        try {
            RedeemRecordEntity record = redeemCodeService.redeem(gameId, code, playerKey);
            return ResponseEntity.ok(Map.of(
                "recordId", record.id,
                "batchId", record.batchId,
                "gameId", record.gameId,
                "playerKey", record.playerKey,
                "code", record.code,
                "reward", record.reward,
                "redeemedAt", record.redeemedAt.toString()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage(), "code", code));
        }
    }

    /** 玩家兑换历史（客服/玩家自查） */
    @GetMapping("/api/redeem-codes/history")
    public ResponseEntity<List<RedeemRecordEntity>> history(@RequestParam String gameId,
                                                            @RequestParam String playerKey) {
        accessGuard.requireGamePermission(gameId, "game:read");
        return ResponseEntity.ok(redeemCodeService.playerHistory(gameId, playerKey));
    }

    private String currentOperator() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
            .getContext().getAuthentication();
        return auth != null ? auth.getName() : "api";
    }
}
