package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.PlayerLoginLogEntity;
import io.oddsmaker.control.jpa.PlayerPaymentEntity;
import io.oddsmaker.control.security.AccessGuard;
import io.oddsmaker.control.service.PlayerDataQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 玩家数据查询 API（客服/运营）。
 * 查询：GET /api/player-data/{playerId}/profile|payments|login-logs；
 * 上报（游戏服）：POST /api/player-data/payments、POST /api/player-data/login-logs。
 */
@RestController
public class PlayerDataQueryController {

    private final PlayerDataQueryService playerDataQueryService;
    private final AccessGuard accessGuard;

    public PlayerDataQueryController(PlayerDataQueryService playerDataQueryService, AccessGuard accessGuard) {
        this.playerDataQueryService = playerDataQueryService;
        this.accessGuard = accessGuard;
    }

    /** 跨游戏玩家档案：仅返回当前用户具备 game:read 权限的游戏 */
    @GetMapping("/api/player-data/{playerId}/profile")
    public ResponseEntity<List<Map<String, Object>>> profile(@PathVariable String playerId) {
        List<Map<String, Object>> profile = playerDataQueryService.profile(playerId);
        List<Map<String, Object>> visible = profile.stream()
            .filter(row -> accessGuard.canAccessGame((String) row.get("gameId"), "game:read"))
            .collect(Collectors.toList());
        return ResponseEntity.ok(visible);
    }

    /** 充值记录 + 汇总（仅 COMPLETED 计入累计） */
    @GetMapping("/api/player-data/{playerId}/payments")
    public ResponseEntity<Map<String, Object>> payments(@PathVariable String playerId,
                                                        @RequestParam String gameId,
                                                        @RequestParam(value = "limit", defaultValue = "50") int limit) {
        accessGuard.requireGamePermission(gameId, "game:read");
        return ResponseEntity.ok(playerDataQueryService.payments(gameId, playerId, limit));
    }

    /** 登录日志（最近优先） */
    @GetMapping("/api/player-data/{playerId}/login-logs")
    public ResponseEntity<Map<String, Object>> loginLogs(@PathVariable String playerId,
                                                         @RequestParam String gameId,
                                                         @RequestParam(value = "limit", defaultValue = "50") int limit) {
        accessGuard.requireGamePermission(gameId, "game:read");
        return ResponseEntity.ok(playerDataQueryService.loginLogs(gameId, playerId, limit));
    }

    /** 游戏服上报充值流水：(gameId, orderId) 幂等 */
    @PostMapping("/api/player-data/payments")
    public ResponseEntity<PlayerPaymentEntity> ingestPayment(@RequestBody PlayerPaymentEntity payment) {
        accessGuard.requireGamePermission(payment.gameId, "game:read");
        return ResponseEntity.ok(playerDataQueryService.ingestPayment(payment));
    }

    /** 游戏服上报登录日志 */
    @PostMapping("/api/player-data/login-logs")
    public ResponseEntity<PlayerLoginLogEntity> ingestLogin(@RequestBody PlayerLoginLogEntity log) {
        accessGuard.requireGamePermission(log.gameId, "game:read");
        return ResponseEntity.ok(playerDataQueryService.ingestLogin(log));
    }
}
