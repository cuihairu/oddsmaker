package io.oddsmaker.control.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import io.oddsmaker.control.jpa.GameEntity;
import io.oddsmaker.control.jpa.GameRepo;
import io.oddsmaker.control.jpa.IdentityEntity;
import io.oddsmaker.control.jpa.IdentityRepo;
import io.oddsmaker.control.jpa.PlayerLoginLogEntity;
import io.oddsmaker.control.jpa.PlayerLoginLogRepo;
import io.oddsmaker.control.jpa.PlayerPaymentEntity;
import io.oddsmaker.control.jpa.PlayerPaymentRepo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 玩家数据查询测试：跨游戏档案聚合、充值汇总（仅 COMPLETED）、登录日志、上报幂等与校验。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("玩家数据查询测试")
class PlayerDataQueryServiceTest {

    @Mock
    private GameRepo gameRepo;

    @Mock
    private IdentityRepo identityRepo;

    @Mock
    private PlayerPaymentRepo paymentRepo;

    @Mock
    private PlayerLoginLogRepo loginLogRepo;

    @InjectMocks
    private PlayerDataQueryService service;

    private final GameEntity gameDemo = new GameEntity();
    private final GameEntity gameOther = new GameEntity();

    @BeforeEach
    void setUp() {
        gameDemo.id = "game_demo";
        gameDemo.name = "Demo";
        gameOther.id = "game_other";
        gameOther.name = "Other";
    }

    @Test
    @DisplayName("档案：跨游戏聚合基本数据与充值/登录汇总，未注册游戏 found=false")
    void profileAggregatesAcrossGames() {
        when(gameRepo.findByDeletedAtIsNull()).thenReturn(List.of(gameDemo, gameOther));

        IdentityEntity identity = new IdentityEntity();
        identity.id = "id_1";
        identity.gameId = "game_demo";
        identity.playerId = "p1";
        identity.primaryId = "p1";
        identity.deviceId = "dev_1";
        identity.deviceType = "ios";
        identity.firstSeenAt = LocalDateTime.now().minusDays(10);
        identity.lastSeenAt = LocalDateTime.now().minusHours(1);
        identity.sessionCount = 7;
        identity.eventCount = 1234L;
        when(identityRepo.findByPlayerId("game_demo", "p1")).thenReturn(Optional.of(identity));
        when(identityRepo.findByPlayerId("game_other", "p1")).thenReturn(Optional.empty());

        when(paymentRepo.sumCompletedAmount("game_demo", "p1")).thenReturn(new BigDecimal("99.50"));
        when(paymentRepo.countByGameIdAndPlayerIdAndStatus("game_demo", "p1", PlayerPaymentEntity.Status.COMPLETED)).thenReturn(2L);
        when(loginLogRepo.countByGameIdAndPlayerId("game_demo", "p1")).thenReturn(5L);
        PlayerLoginLogEntity last = new PlayerLoginLogEntity();
        last.loginAt = LocalDateTime.now().minusHours(1);
        when(loginLogRepo.findFirstByGameIdAndPlayerIdOrderByLoginAtDesc("game_demo", "p1")).thenReturn(Optional.of(last));

        List<java.util.Map<String, Object>> profile = service.profile("p1");

        assertEquals(2, profile.size());
        java.util.Map<String, Object> demo = profile.get(0);
        assertEquals("game_demo", demo.get("gameId"));
        assertEquals("Demo", demo.get("gameName"));
        assertEquals(true, demo.get("found"));
        assertEquals("id_1", demo.get("identityId"));
        assertEquals("dev_1", demo.get("deviceId"));
        assertEquals(new BigDecimal("99.50"), demo.get("totalPaidAmount"));
        assertEquals(2L, demo.get("paidOrderCount"));
        assertEquals(5L, demo.get("loginCount"));
        assertNotNull(demo.get("lastLoginAt"));

        java.util.Map<String, Object> other = profile.get(1);
        assertEquals("game_other", other.get("gameId"));
        assertEquals(false, other.get("found"));
    }

    @Test
    @DisplayName("档案：playerId 必填")
    void profileRequiresPlayerId() {
        assertThrows(IllegalArgumentException.class, () -> service.profile(" "));
    }

    private static PlayerPaymentEntity payment(String orderId, String amount, PlayerPaymentEntity.Status status) {
        PlayerPaymentEntity p = new PlayerPaymentEntity();
        p.gameId = "game_demo";
        p.playerId = "p1";
        p.orderId = orderId;
        p.amount = new BigDecimal(amount);
        p.status = status;
        p.paidAt = LocalDateTime.now();
        return p;
    }

    @Test
    @DisplayName("充值：列表限额 + 汇总仅统计 COMPLETED")
    void paymentsSummaryAndLimit() {
        when(gameRepo.findById("game_demo")).thenReturn(Optional.of(gameDemo));
        List<PlayerPaymentEntity> records = List.of(
            payment("o3", "30", PlayerPaymentEntity.Status.COMPLETED),
            payment("o2", "20", PlayerPaymentEntity.Status.COMPLETED),
            payment("o1", "10", PlayerPaymentEntity.Status.REFUNDED));
        when(paymentRepo.findByGameIdAndPlayerIdOrderByPaidAtDesc("game_demo", "p1")).thenReturn(records);
        when(paymentRepo.countByGameIdAndPlayerIdAndStatus("game_demo", "p1", PlayerPaymentEntity.Status.COMPLETED)).thenReturn(2L);
        when(paymentRepo.sumCompletedAmount("game_demo", "p1")).thenReturn(new BigDecimal("50.00"));

        java.util.Map<String, Object> out = service.payments("game_demo", "p1", 2);

        assertEquals(new BigDecimal("50.00"), out.get("totalAmount"));
        assertEquals(2L, out.get("completedCount"));
        assertEquals(3, out.get("totalCount"));
        assertEquals(2, ((List<?>) out.get("payments")).size());  // limit 生效
    }

    @Test
    @DisplayName("充值：游戏必须存在")
    void paymentsRequiresGame() {
        when(gameRepo.findById("game_nope")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.payments("game_nope", "p1", 50));
    }

    @Test
    @DisplayName("登录：最近优先 + lastLoginAt + 限额")
    void loginLogsLatestFirst() {
        when(gameRepo.findById("game_demo")).thenReturn(Optional.of(gameDemo));
        PlayerLoginLogEntity latest = new PlayerLoginLogEntity();
        latest.loginAt = LocalDateTime.now();
        PlayerLoginLogEntity earlier = new PlayerLoginLogEntity();
        earlier.loginAt = LocalDateTime.now().minusDays(1);
        when(loginLogRepo.findByGameIdAndPlayerIdOrderByLoginAtDesc("game_demo", "p1"))
            .thenReturn(List.of(latest, earlier));
        when(loginLogRepo.findFirstByGameIdAndPlayerIdOrderByLoginAtDesc("game_demo", "p1"))
            .thenReturn(Optional.of(latest));

        java.util.Map<String, Object> out = service.loginLogs("game_demo", "p1", 1);

        assertEquals(2, out.get("loginCount"));
        assertEquals(latest.loginAt, out.get("lastLoginAt"));
        assertEquals(1, ((List<?>) out.get("logs")).size());
    }

    @Test
    @DisplayName("上报充值：重复订单幂等返回既有记录（duplicate 标记），不重复落库")
    void ingestPaymentIdempotent() {
        when(gameRepo.findById("game_demo")).thenReturn(Optional.of(gameDemo));
        PlayerPaymentEntity existing = payment("o1", "10", PlayerPaymentEntity.Status.COMPLETED);
        when(paymentRepo.findByGameIdAndOrderId("game_demo", "o1")).thenReturn(Optional.of(existing));

        PlayerPaymentEntity result = service.ingestPayment(payment("o1", "10", PlayerPaymentEntity.Status.COMPLETED));

        assertSame(existing, result);
        assertTrue(result.duplicate);
        verify(paymentRepo, never()).save(any());
    }

    @Test
    @DisplayName("上报充值：并发同订单唯一约束冲突兜底返回既有记录")
    void ingestPaymentConcurrentDuplicate() {
        when(gameRepo.findById("game_demo")).thenReturn(Optional.of(gameDemo));
        PlayerPaymentEntity winner = payment("o1", "10", PlayerPaymentEntity.Status.COMPLETED);
        when(paymentRepo.findByGameIdAndOrderId("game_demo", "o1"))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(winner));
        when(paymentRepo.save(any())).thenThrow(new DataIntegrityViolationException("duplicate"));

        PlayerPaymentEntity result = service.ingestPayment(payment("o1", "10", PlayerPaymentEntity.Status.COMPLETED));

        assertSame(winner, result);
        assertTrue(result.duplicate);
    }

    @Test
    @DisplayName("上报充值：参数校验与默认值（status/paidAt）")
    void ingestPaymentValidatesAndFillsDefaults() {
        when(gameRepo.findById("game_demo")).thenReturn(Optional.of(gameDemo));
        when(paymentRepo.findByGameIdAndOrderId(eq("game_demo"), any())).thenReturn(Optional.empty());
        when(paymentRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PlayerPaymentEntity p = payment("o9", "5", null);
        p.status = null;
        p.paidAt = null;
        PlayerPaymentEntity saved = service.ingestPayment(p);
        assertEquals(PlayerPaymentEntity.Status.COMPLETED, saved.status);
        assertNotNull(saved.paidAt);
        assertTrue(saved.id.startsWith("pp_"));

        assertThrows(IllegalArgumentException.class,
            () -> service.ingestPayment(payment("", "5", PlayerPaymentEntity.Status.COMPLETED)));
        assertThrows(IllegalArgumentException.class,
            () -> service.ingestPayment(payment("o8", "0", PlayerPaymentEntity.Status.COMPLETED)));

        PlayerPaymentEntity badGame = payment("o7", "5", PlayerPaymentEntity.Status.COMPLETED);
        badGame.gameId = "game_nope";
        when(gameRepo.findById("game_nope")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.ingestPayment(badGame));
    }

    @Test
    @DisplayName("上报登录：赋 ID、默认 loginAt、playerId 必填")
    void ingestLoginSavesWithDefaults() {
        when(gameRepo.findById("game_demo")).thenReturn(Optional.of(gameDemo));
        when(loginLogRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PlayerLoginLogEntity log = new PlayerLoginLogEntity();
        log.gameId = "game_demo";
        log.playerId = "p1";
        PlayerLoginLogEntity saved = service.ingestLogin(log);
        assertTrue(saved.id.startsWith("ll_"));
        assertNotNull(saved.loginAt);

        PlayerLoginLogEntity noPlayer = new PlayerLoginLogEntity();
        noPlayer.gameId = "game_demo";
        assertThrows(IllegalArgumentException.class, () -> service.ingestLogin(noPlayer));
    }
}
