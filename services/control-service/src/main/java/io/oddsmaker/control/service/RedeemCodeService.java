package io.oddsmaker.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oddsmaker.control.jpa.GameRepo;
import io.oddsmaker.control.jpa.RedeemCodeBatchEntity;
import io.oddsmaker.control.jpa.RedeemCodeBatchRepo;
import io.oddsmaker.control.jpa.RedeemCodeEntity;
import io.oddsmaker.control.jpa.RedeemCodeRepo;
import io.oddsmaker.control.jpa.RedeemRecordEntity;
import io.oddsmaker.control.jpa.RedeemRecordRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 兑换码系统：批量生成 / 兑换 / 防刷 / 过期。
 *
 * 防刷三层：每玩家限领（perUserLimit，记录表计数）、批次总量（total）、
 * 有效期与批次状态；UNIQUE 码另用条件更新原子核销防并发重复兑换。
 */
@Service
public class RedeemCodeService {

    private static final Logger logger = LoggerFactory.getLogger(RedeemCodeService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private RedeemCodeBatchRepo batchRepo;

    @Autowired
    private RedeemCodeRepo codeRepo;

    @Autowired
    private RedeemRecordRepo recordRepo;

    @Autowired
    private GameRepo gameRepo;

    @Autowired
    private AuditLogService auditLog;

    @Transactional(readOnly = true)
    public List<RedeemCodeBatchEntity> listBatches(String gameId) {
        requireGame(gameId);
        return batchRepo.findByGameIdAndDeletedAtIsNullOrderByCreatedAtDesc(gameId);
    }

    @Transactional(readOnly = true)
    public RedeemCodeBatchEntity getBatch(String id) {
        return batchRepo.findByIdAndDeletedAtIsNull(id).orElse(null);
    }

    /**
     * 创建批次并生成码：UNIQUE 批量生成一次性码；SHARED 生成单一通用码。
     */
    @Transactional
    public RedeemCodeBatchEntity createBatch(RedeemCodeBatchEntity batch,
                                             String codePrefix,
                                             int codeLength,
                                             String sharedCode,
                                             String operator) {
        requireGame(batch.gameId);
        if (batch.name == null || batch.name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        validateReward(batch.reward);
        if (batch.perUserLimit < 1) {
            throw new IllegalArgumentException("perUserLimit must be >= 1");
        }

        batch.id = "rb_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        batch.status = RedeemCodeBatchEntity.Status.ACTIVE;

        if (batch.codeType == RedeemCodeBatchEntity.CodeType.UNIQUE) {
            if (batch.total < 1 || batch.total > 1_000_000) {
                throw new IllegalArgumentException("total must be between 1 and 1000000 for UNIQUE batch");
            }
            batchRepo.save(batch);
            List<String> codes = CodeGenerator.generate(batch.total,
                codeLength > 0 ? codeLength : 12, codePrefix);
            for (String code : codes) {
                RedeemCodeEntity entity = new RedeemCodeEntity();
                entity.id = "rc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
                entity.batchId = batch.id;
                entity.code = code;
                codeRepo.save(entity);
            }
            logger.info("Generated {} unique codes for batch {}", codes.size(), batch.id);
        } else {
            // SHARED：单一通用码（指定或生成），total 限定总兑换次数（0=不限）
            batchRepo.save(batch);
            String code = sharedCode != null && !sharedCode.isBlank()
                ? sharedCode.trim().toUpperCase()
                : CodeGenerator.generate(1, codeLength > 0 ? codeLength : 12, codePrefix).get(0);
            if (codeRepo.findByCode(code).isPresent()) {
                throw new IllegalArgumentException("Code already exists: " + code);
            }
            RedeemCodeEntity entity = new RedeemCodeEntity();
            entity.id = "rc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
            entity.batchId = batch.id;
            entity.code = code;
            codeRepo.save(entity);
        }

        auditLog.logCreate("redeem_batch", batch.id, batch.name, operator, operator, null,
            Map.of("gameId", batch.gameId, "codeType", batch.codeType.name(),
                "total", String.valueOf(batch.total),
                "perUserLimit", String.valueOf(batch.perUserLimit)));
        return batch;
    }

    /** 停用批次（不可再兑换） */
    @Transactional
    public RedeemCodeBatchEntity disable(String id, String operator) {
        RedeemCodeBatchEntity batch = requireBatch(id);
        batch.status = RedeemCodeBatchEntity.Status.DISABLED;
        RedeemCodeBatchEntity saved = batchRepo.save(batch);
        auditLog.logUpdate("redeem_batch", saved.id, saved.name, operator, operator, null,
            Map.of("gameId", saved.gameId, "action", "disable"));
        return saved;
    }

    /**
     * 兑换（游戏服入口）：先校验码所属游戏再兑换，防跨游戏探测他人批次码。
     */
    @Transactional
    public RedeemRecordEntity redeem(String gameId, String code, String playerKey) {
        requireGame(gameId);
        String normalized = code == null ? "" : code.trim().toUpperCase();
        RedeemCodeEntity codeEntity = codeRepo.findByCode(normalized)
            .orElseThrow(() -> new IllegalArgumentException("invalid_code"));
        RedeemCodeBatchEntity batch = batchRepo.findByIdAndDeletedAtIsNull(codeEntity.batchId)
            .orElseThrow(() -> new IllegalArgumentException("invalid_code"));
        if (!gameId.equals(batch.gameId)) {
            // 不泄露其他游戏批次是否存在，统一按无效码处理
            throw new IllegalArgumentException("invalid_code");
        }
        return redeem(code, playerKey);
    }

    /**
     * 兑换：返回发放凭据（奖励快照）；失败抛异常说明原因。
     * 幂等语义：同批同次序重复请求由 (batch, player, seq) 唯一约束兜底。
     */
    @Transactional
    public RedeemRecordEntity redeem(String code, String playerKey) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code is required");
        }
        if (playerKey == null || playerKey.isBlank()) {
            throw new IllegalArgumentException("playerKey is required");
        }
        playerKey = playerKey.trim();
        LocalDateTime now = LocalDateTime.now();

        RedeemCodeEntity codeEntity = codeRepo.findByCode(code.trim().toUpperCase())
            .orElseThrow(() -> new IllegalArgumentException("invalid_code"));
        RedeemCodeBatchEntity batch = requireBatch(codeEntity.batchId);

        // 1. 批次可用性：状态 + 过期
        if (!batch.redeemable(now)) {
            throw new IllegalStateException("batch_not_redeemable");
        }

        // 2. 防刷：每玩家限领
        List<RedeemRecordEntity> mine = recordRepo.findByBatchIdAndPlayerKey(batch.id, playerKey);
        if (mine.size() >= batch.perUserLimit) {
            throw new IllegalStateException("per_user_limit_reached");
        }

        // 3. 防刷：批次总量（SHARED 主要约束；UNIQUE 由码数天然限制）
        if (batch.total > 0 && batch.codeType == RedeemCodeBatchEntity.CodeType.SHARED) {
            if (recordRepo.countByBatchId(batch.id) >= batch.total) {
                throw new IllegalStateException("batch_exhausted");
            }
        }

        // 4. UNIQUE 码原子核销（条件更新防并发重复兑换）
        if (batch.codeType == RedeemCodeBatchEntity.CodeType.UNIQUE) {
            if (codeEntity.status == RedeemCodeEntity.Status.REDEEMED) {
                throw new IllegalStateException("code_already_redeemed");
            }
            if (codeRepo.redeemIfAvailable(codeEntity.id, playerKey) != 1) {
                throw new IllegalStateException("code_already_redeemed");
            }
        }

        // 5. 记录兑换（奖励快照固化）
        RedeemRecordEntity record = new RedeemRecordEntity();
        record.id = "rr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        record.batchId = batch.id;
        record.gameId = batch.gameId;
        record.playerKey = playerKey;
        record.seq = mine.size() + 1;
        record.code = codeEntity.code;
        record.reward = batch.reward;
        record.redeemedAt = now;
        try {
            return recordRepo.save(record);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // (batch, player, seq) 冲突：并发同次序请求，语义上兑换成功过
            throw new IllegalStateException("duplicate_redeem");
        }
    }

    /** 玩家兑换历史（客服/玩家自查） */
    @Transactional(readOnly = true)
    public List<RedeemRecordEntity> playerHistory(String gameId, String playerKey) {
        requireGame(gameId);
        return recordRepo.findByGameIdAndPlayerKeyOrderByRedeemedAtDesc(gameId, playerKey);
    }

    /** 批次码列表（运营导出发码） */
    @Transactional(readOnly = true)
    public List<RedeemCodeEntity> listCodes(String batchId) {
        requireBatch(batchId);
        return codeRepo.findByBatchId(batchId);
    }

    private void validateReward(String reward) {
        if (reward == null || reward.isBlank()) {
            throw new IllegalArgumentException("reward is required");
        }
        try {
            List<?> list = JSON.readValue(reward, List.class);
            for (Object item : list) {
                if (!(item instanceof Map)) {
                    throw new IllegalArgumentException("reward item must be an object");
                }
                Map<?, ?> m = (Map<?, ?>) item;
                if (m.get("type") == null || m.get("id") == null) {
                    throw new IllegalArgumentException("reward item requires type and id");
                }
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("reward must be a JSON array", e);
        }
    }

    private RedeemCodeBatchEntity requireBatch(String id) {
        return batchRepo.findByIdAndDeletedAtIsNull(id)
            .orElseThrow(() -> new IllegalArgumentException("Batch not found: " + id));
    }

    private void requireGame(String gameId) {
        gameRepo.findById(gameId)
            .filter(g -> g.deletedAt == null)
            .orElseThrow(() -> new IllegalArgumentException("Game not found: " + gameId));
    }
}
