package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.GameEntity;
import io.oddsmaker.control.jpa.GameRepo;
import io.oddsmaker.control.jpa.IdentityEntity;
import io.oddsmaker.control.jpa.IdentityRepo;
import io.oddsmaker.control.jpa.PlayerLoginLogEntity;
import io.oddsmaker.control.jpa.PlayerLoginLogRepo;
import io.oddsmaker.control.jpa.PlayerPaymentEntity;
import io.oddsmaker.control.jpa.PlayerPaymentRepo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 玩家数据查询：按 playerId 跨游戏聚合基本数据（identity）、充值记录、登录日志。
 * 数据来源：充值/登录由游戏服经 ingest API 上报（订单号幂等），基本数据复用 Identity 体系。
 */
@Service
public class PlayerDataQueryService {

    private static final int MAX_LIMIT = 500;

    @Autowired
    private GameRepo gameRepo;

    @Autowired
    private IdentityRepo identityRepo;

    @Autowired
    private PlayerPaymentRepo paymentRepo;

    @Autowired
    private PlayerLoginLogRepo loginLogRepo;

    /**
     * 跨游戏玩家档案：每个未删除游戏一条（有 identity 则含基本数据与充值/登录汇总）。
     * 游戏级权限过滤由 Controller 层完成（canAccessGame）。
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> profile(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId is required");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (GameEntity game : gameRepo.findByDeletedAtIsNull()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("gameId", game.id);
            row.put("gameName", game.displayName != null ? game.displayName : game.name);
            identityRepo.findByPlayerId(game.id, playerId).ifPresentOrElse(identity -> {
                row.put("found", true);
                row.put("identityId", identity.id);
                row.put("primaryId", identity.primaryId);
                row.put("userId", identity.userId);
                row.put("deviceId", identity.deviceId);
                row.put("deviceType", identity.deviceType);
                row.put("environmentId", identity.environmentId);
                row.put("status", identity.status.name());
                row.put("firstSeenAt", identity.firstSeenAt);
                row.put("lastSeenAt", identity.lastSeenAt);
                row.put("sessionCount", identity.sessionCount);
                row.put("eventCount", identity.eventCount);
                row.put("totalPaidAmount", paymentRepo.sumCompletedAmount(game.id, playerId));
                row.put("paidOrderCount", paymentRepo.countByGameIdAndPlayerIdAndStatus(
                    game.id, playerId, PlayerPaymentEntity.Status.COMPLETED));
                row.put("loginCount", loginLogRepo.countByGameIdAndPlayerId(game.id, playerId));
                loginLogRepo.findFirstByGameIdAndPlayerIdOrderByLoginAtDesc(game.id, playerId)
                    .ifPresent(last -> row.put("lastLoginAt", last.loginAt));
            }, () -> row.put("found", false));
            out.add(row);
        }
        return out;
    }

    /** 充值记录 + 汇总（仅 COMPLETED 计入累计） */
    @Transactional(readOnly = true)
    public Map<String, Object> payments(String gameId, String playerId, int limit) {
        requireGame(gameId);
        List<PlayerPaymentEntity> all =
            paymentRepo.findByGameIdAndPlayerIdOrderByPaidAtDesc(gameId, playerId);
        long completed = paymentRepo.countByGameIdAndPlayerIdAndStatus(
            gameId, playerId, PlayerPaymentEntity.Status.COMPLETED);
        BigDecimal totalAmount = paymentRepo.sumCompletedAmount(gameId, playerId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("gameId", gameId);
        out.put("playerId", playerId);
        out.put("totalAmount", totalAmount);
        out.put("completedCount", completed);
        out.put("totalCount", all.size());
        out.put("payments", cap(all, limit));
        return out;
    }

    /** 登录日志（最近优先） */
    @Transactional(readOnly = true)
    public Map<String, Object> loginLogs(String gameId, String playerId, int limit) {
        requireGame(gameId);
        List<PlayerLoginLogEntity> all =
            loginLogRepo.findByGameIdAndPlayerIdOrderByLoginAtDesc(gameId, playerId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("gameId", gameId);
        out.put("playerId", playerId);
        out.put("totalCount", all.size());
        out.put("loginCount", all.size());
        loginLogRepo.findFirstByGameIdAndPlayerIdOrderByLoginAtDesc(gameId, playerId)
            .ifPresent(last -> out.put("lastLoginAt", last.loginAt));
        out.put("logs", cap(all, limit));
        return out;
    }

    /** 游戏服上报充值流水：(gameId, orderId) 幂等，重复上报返回既有记录并标记 duplicate */
    @Transactional
    public PlayerPaymentEntity ingestPayment(PlayerPaymentEntity payment) {
        if (payment.gameId == null || payment.gameId.isBlank()) {
            throw new IllegalArgumentException("gameId is required");
        }
        requireGame(payment.gameId);
        if (payment.playerId == null || payment.playerId.isBlank()) {
            throw new IllegalArgumentException("playerId is required");
        }
        if (payment.orderId == null || payment.orderId.isBlank()) {
            throw new IllegalArgumentException("orderId is required");
        }
        if (payment.amount == null || payment.amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        if (payment.status == null) {
            payment.status = PlayerPaymentEntity.Status.COMPLETED;
        }
        if (payment.paidAt == null) {
            payment.paidAt = LocalDateTime.now();
        }

        PlayerPaymentEntity existing = paymentRepo.findByGameIdAndOrderId(payment.gameId, payment.orderId)
            .orElse(null);
        if (existing != null) {
            existing.duplicate = true;
            return existing;
        }
        payment.id = "pp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        try {
            return paymentRepo.save(payment);
        } catch (DataIntegrityViolationException e) {
            // 并发同订单上报：唯一约束兜底，返回既有记录
            PlayerPaymentEntity winner = paymentRepo.findByGameIdAndOrderId(payment.gameId, payment.orderId)
                .orElseThrow(() -> e);
            winner.duplicate = true;
            return winner;
        }
    }

    /** 游戏服上报登录日志（追加） */
    @Transactional
    public PlayerLoginLogEntity ingestLogin(PlayerLoginLogEntity log) {
        if (log.gameId == null || log.gameId.isBlank()) {
            throw new IllegalArgumentException("gameId is required");
        }
        requireGame(log.gameId);
        if (log.playerId == null || log.playerId.isBlank()) {
            throw new IllegalArgumentException("playerId is required");
        }
        if (log.loginAt == null) {
            log.loginAt = LocalDateTime.now();
        }
        log.id = "ll_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        return loginLogRepo.save(log);
    }

    private static <T> List<T> cap(List<T> list, int limit) {
        if (limit <= 0) {
            limit = 50;
        }
        return list.size() <= limit ? list : new ArrayList<>(list.subList(0, Math.min(limit, MAX_LIMIT)));
    }

    private void requireGame(String gameId) {
        gameRepo.findById(gameId)
            .filter(g -> g.deletedAt == null)
            .orElseThrow(() -> new IllegalArgumentException("Game not found: " + gameId));
    }
}
