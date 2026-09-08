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
import io.oddsmaker.control.jpa.RedeemCodeBatchEntity;
import io.oddsmaker.control.jpa.RedeemCodeBatchRepo;
import io.oddsmaker.control.jpa.RedeemCodeEntity;
import io.oddsmaker.control.jpa.RedeemCodeRepo;
import io.oddsmaker.control.jpa.RedeemRecordEntity;
import io.oddsmaker.control.jpa.RedeemRecordRepo;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * 兑换码系统测试：批量生成、SHARED 通用码、兑换防刷（限领/过期/领完/重复兑换）、跨游戏隔离。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("兑换码系统测试")
class RedeemCodeServiceTest {

    @Mock
    private RedeemCodeBatchRepo batchRepo;

    @Mock
    private RedeemCodeRepo codeRepo;

    @Mock
    private RedeemRecordRepo recordRepo;

    @Mock
    private GameRepo gameRepo;

    @Mock
    private AuditLogService auditLog;

    @InjectMocks
    private RedeemCodeService service;

    private final GameEntity game = new GameEntity();

    @BeforeEach
    void setUp() {
        game.id = "game_demo";
    }

    private static RedeemCodeBatchEntity batch(RedeemCodeBatchEntity.CodeType type, int total, int perUserLimit) {
        RedeemCodeBatchEntity b = new RedeemCodeBatchEntity();
        b.gameId = "game_demo";
        b.name = "新年活动兑换码";
        b.reward = "[{\"type\":\"item\",\"id\":\"gem\",\"count\":100}]";
        b.codeType = type;
        b.total = total;
        b.perUserLimit = perUserLimit;
        return b;
    }

    private static RedeemCodeEntity code(String batchId, String c, RedeemCodeEntity.Status status) {
        RedeemCodeEntity e = new RedeemCodeEntity();
        e.id = "rc_x";
        e.batchId = batchId;
        e.code = c;
        e.status = status;
        return e;
    }

    private void stubGame() {
        when(gameRepo.findById("game_demo")).thenReturn(Optional.of(game));
    }

    @Test
    @DisplayName("创建：UNIQUE 批次生成 total 个一次性码")
    void createUniqueBatchGeneratesCodes() {
        stubGame();
        when(batchRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(codeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RedeemCodeBatchEntity created = service.createBatch(
            batch(RedeemCodeBatchEntity.CodeType.UNIQUE, 5, 1), "NY26-", 12, null, "op_1");

        assertNotNull(created.id);
        assertTrue(created.id.startsWith("rb_"));
        assertEquals(RedeemCodeBatchEntity.Status.ACTIVE, created.status);
        verify(codeRepo, times(5)).save(any(RedeemCodeEntity.class));
        verify(codeRepo, times(5)).save(argThat(c -> c.batchId.equals(created.id)
            && c.status == RedeemCodeEntity.Status.AVAILABLE
            && c.code.startsWith("NY26-")));
        verify(auditLog).logCreate(eq("redeem_batch"), eq(created.id), eq("新年活动兑换码"),
            eq("op_1"), eq("op_1"), isNull(), anyMap());
    }

    @Test
    @DisplayName("创建：SHARED 批次使用指定通用码（去空白大写）")
    void createSharedBatchUsesProvidedCode() {
        stubGame();
        when(batchRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(codeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(codeRepo.findByCode("NY2026")).thenReturn(Optional.empty());

        RedeemCodeBatchEntity created = service.createBatch(
            batch(RedeemCodeBatchEntity.CodeType.SHARED, 0, 1), null, 0, " ny2026 ", "op_1");

        verify(codeRepo, times(1)).save(argThat(c -> c.code.equals("NY2026")));
    }

    @Test
    @DisplayName("创建：SHARED 码与已有码冲突拒绝")
    void createSharedBatchRejectsDuplicateCode() {
        stubGame();
        when(batchRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(codeRepo.findByCode("NY2026")).thenReturn(Optional.of(code("rb_other", "NY2026", RedeemCodeEntity.Status.AVAILABLE)));

        assertThrows(IllegalArgumentException.class, () -> service.createBatch(
            batch(RedeemCodeBatchEntity.CodeType.SHARED, 0, 1), null, 0, "NY2026", "op_1"));
    }

    @Test
    @DisplayName("创建：name/reward/perUserLimit/total 校验")
    void createValidatesInput() {
        stubGame();

        RedeemCodeBatchEntity noName = batch(RedeemCodeBatchEntity.CodeType.UNIQUE, 5, 1);
        noName.name = " ";
        assertThrows(IllegalArgumentException.class,
            () -> service.createBatch(noName, null, 0, null, "op_1"));

        RedeemCodeBatchEntity badLimit = batch(RedeemCodeBatchEntity.CodeType.UNIQUE, 5, 0);
        assertThrows(IllegalArgumentException.class,
            () -> service.createBatch(badLimit, null, 0, null, "op_1"));

        RedeemCodeBatchEntity badTotal = batch(RedeemCodeBatchEntity.CodeType.UNIQUE, 0, 1);
        assertThrows(IllegalArgumentException.class,
            () -> service.createBatch(badTotal, null, 0, null, "op_1"));

        RedeemCodeBatchEntity notJson = batch(RedeemCodeBatchEntity.CodeType.UNIQUE, 5, 1);
        notJson.reward = "not-json";
        assertThrows(IllegalArgumentException.class,
            () -> service.createBatch(notJson, null, 0, null, "op_1"));

        RedeemCodeBatchEntity missingId = batch(RedeemCodeBatchEntity.CodeType.UNIQUE, 5, 1);
        missingId.reward = "[{\"type\":\"item\"}]";
        assertThrows(IllegalArgumentException.class,
            () -> service.createBatch(missingId, null, 0, null, "op_1"));

        RedeemCodeBatchEntity notObject = batch(RedeemCodeBatchEntity.CodeType.UNIQUE, 5, 1);
        notObject.reward = "[\"plain\"]";
        assertThrows(IllegalArgumentException.class,
            () -> service.createBatch(notObject, null, 0, null, "op_1"));

        verify(batchRepo, never()).save(any());
    }

    @Test
    @DisplayName("兑换：UNIQUE 码成功，返回奖励快照凭据")
    void redeemUniqueCodeSuccess() {
        RedeemCodeBatchEntity b = batch(RedeemCodeBatchEntity.CodeType.UNIQUE, 1, 1);
        b.id = "rb_1";
        RedeemCodeEntity c = code(b.id, "ABC234", RedeemCodeEntity.Status.AVAILABLE);

        when(codeRepo.findByCode("ABC234")).thenReturn(Optional.of(c));
        when(batchRepo.findByIdAndDeletedAtIsNull(b.id)).thenReturn(Optional.of(b));
        when(recordRepo.findByBatchIdAndPlayerKey(b.id, "player_1")).thenReturn(List.of());
        when(codeRepo.redeemIfAvailable(c.id, "player_1")).thenReturn(1);
        when(recordRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RedeemRecordEntity record = service.redeem(" abc234 ", " player_1 ");

        assertEquals(b.id, record.batchId);
        assertEquals("game_demo", record.gameId);
        assertEquals("player_1", record.playerKey);
        assertEquals(1, record.seq);
        assertEquals("ABC234", record.code);
        assertEquals(b.reward, record.reward);  // 奖励快照固化
        assertNotNull(record.redeemedAt);
    }

    @Test
    @DisplayName("兑换：无效码拒绝")
    void redeemRejectsInvalidCode() {
        when(codeRepo.findByCode("NOPE")).thenReturn(Optional.empty());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> service.redeem("nope", "player_1"));
        assertEquals("invalid_code", ex.getMessage());
    }

    @Test
    @DisplayName("兑换：停用/过期批次拒绝")
    void redeemRejectsUnredeemableBatch() {
        RedeemCodeBatchEntity disabled = batch(RedeemCodeBatchEntity.CodeType.SHARED, 0, 1);
        disabled.id = "rb_off";
        disabled.status = RedeemCodeBatchEntity.Status.DISABLED;
        when(codeRepo.findByCode("ABC234")).thenReturn(Optional.of(code(disabled.id, "ABC234", RedeemCodeEntity.Status.AVAILABLE)));
        when(batchRepo.findByIdAndDeletedAtIsNull(disabled.id)).thenReturn(Optional.of(disabled));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> service.redeem("ABC234", "player_1"));
        assertEquals("batch_not_redeemable", ex.getMessage());

        RedeemCodeBatchEntity expired = batch(RedeemCodeBatchEntity.CodeType.SHARED, 0, 1);
        expired.id = "rb_exp";
        expired.expiresAt = LocalDateTime.now().minusMinutes(1);
        when(codeRepo.findByCode("DEF345")).thenReturn(Optional.of(code(expired.id, "DEF345", RedeemCodeEntity.Status.AVAILABLE)));
        when(batchRepo.findByIdAndDeletedAtIsNull(expired.id)).thenReturn(Optional.of(expired));

        IllegalStateException ex2 = assertThrows(IllegalStateException.class,
            () -> service.redeem("DEF345", "player_1"));
        assertEquals("batch_not_redeemable", ex2.getMessage());
    }

    @Test
    @DisplayName("兑换：每玩家限领达到上限拒绝")
    void redeemRejectsPerUserLimitReached() {
        RedeemCodeBatchEntity b = batch(RedeemCodeBatchEntity.CodeType.SHARED, 0, 1);
        b.id = "rb_lim";
        when(codeRepo.findByCode("ABC234")).thenReturn(Optional.of(code(b.id, "ABC234", RedeemCodeEntity.Status.AVAILABLE)));
        when(batchRepo.findByIdAndDeletedAtIsNull(b.id)).thenReturn(Optional.of(b));
        when(recordRepo.findByBatchIdAndPlayerKey(b.id, "player_1"))
            .thenReturn(List.of(new RedeemRecordEntity()));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> service.redeem("ABC234", "player_1"));
        assertEquals("per_user_limit_reached", ex.getMessage());
    }

    @Test
    @DisplayName("兑换：SHARED 批次总次数领完拒绝")
    void redeemRejectsExhaustedSharedBatch() {
        RedeemCodeBatchEntity b = batch(RedeemCodeBatchEntity.CodeType.SHARED, 2, 1);
        b.id = "rb_sh";
        when(codeRepo.findByCode("ABC234")).thenReturn(Optional.of(code(b.id, "ABC234", RedeemCodeEntity.Status.AVAILABLE)));
        when(batchRepo.findByIdAndDeletedAtIsNull(b.id)).thenReturn(Optional.of(b));
        when(recordRepo.findByBatchIdAndPlayerKey(b.id, "player_new")).thenReturn(List.of());
        when(recordRepo.countByBatchId(b.id)).thenReturn(2L);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> service.redeem("ABC234", "player_new"));
        assertEquals("batch_exhausted", ex.getMessage());
    }

    @Test
    @DisplayName("兑换：已核销码与并发条件更新失败均拒绝")
    void redeemRejectsAlreadyRedeemedCode() {
        RedeemCodeBatchEntity b = batch(RedeemCodeBatchEntity.CodeType.UNIQUE, 1, 1);
        b.id = "rb_u";
        RedeemCodeEntity used = code(b.id, "ABC234", RedeemCodeEntity.Status.REDEEMED);
        when(codeRepo.findByCode("ABC234")).thenReturn(Optional.of(used));
        when(batchRepo.findByIdAndDeletedAtIsNull(b.id)).thenReturn(Optional.of(b));
        when(recordRepo.findByBatchIdAndPlayerKey(b.id, "player_1")).thenReturn(List.of());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> service.redeem("ABC234", "player_1"));
        assertEquals("code_already_redeemed", ex.getMessage());

        // 状态仍 AVAILABLE 但条件更新落败（并发抢先核销）
        RedeemCodeEntity racing = code(b.id, "DEF345", RedeemCodeEntity.Status.AVAILABLE);
        when(codeRepo.findByCode("DEF345")).thenReturn(Optional.of(racing));
        when(codeRepo.redeemIfAvailable(racing.id, "player_2")).thenReturn(0);

        IllegalStateException ex2 = assertThrows(IllegalStateException.class,
            () -> service.redeem("DEF345", "player_2"));
        assertEquals("code_already_redeemed", ex2.getMessage());
        verify(recordRepo, never()).save(any());
    }

    @Test
    @DisplayName("兑换：同批同次序唯一约束冲突（并发重复请求）按重复兑换拒绝")
    void redeemDuplicateSeqConflict() {
        RedeemCodeBatchEntity b = batch(RedeemCodeBatchEntity.CodeType.SHARED, 0, 1);
        b.id = "rb_dup";
        when(codeRepo.findByCode("ABC234")).thenReturn(Optional.of(code(b.id, "ABC234", RedeemCodeEntity.Status.AVAILABLE)));
        when(batchRepo.findByIdAndDeletedAtIsNull(b.id)).thenReturn(Optional.of(b));
        when(recordRepo.findByBatchIdAndPlayerKey(b.id, "player_1")).thenReturn(List.of());
        when(recordRepo.save(any())).thenThrow(new DataIntegrityViolationException("duplicate"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> service.redeem("ABC234", "player_1"));
        assertEquals("duplicate_redeem", ex.getMessage());
    }

    @Test
    @DisplayName("兑换：跨游戏的码统一按无效码处理，不泄露其他游戏批次")
    void redeemRejectsCodeFromOtherGame() {
        stubGame();
        RedeemCodeBatchEntity other = batch(RedeemCodeBatchEntity.CodeType.SHARED, 0, 1);
        other.id = "rb_other_game";
        other.gameId = "game_other";
        when(gameRepo.findById("game_demo")).thenReturn(Optional.of(game));
        when(codeRepo.findByCode("ABC234")).thenReturn(Optional.of(code(other.id, "ABC234", RedeemCodeEntity.Status.AVAILABLE)));
        when(batchRepo.findByIdAndDeletedAtIsNull(other.id)).thenReturn(Optional.of(other));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> service.redeem("game_demo", "ABC234", "player_1"));
        assertEquals("invalid_code", ex.getMessage());
        verify(recordRepo, never()).save(any());
    }

    @Test
    @DisplayName("游戏服入口：同游戏批次正常兑换")
    void redeemWithGameIdSuccess() {
        stubGame();
        RedeemCodeBatchEntity b = batch(RedeemCodeBatchEntity.CodeType.UNIQUE, 1, 1);
        b.id = "rb_mine";
        RedeemCodeEntity c = code(b.id, "ABC234", RedeemCodeEntity.Status.AVAILABLE);
        when(codeRepo.findByCode("ABC234")).thenReturn(Optional.of(c));
        when(batchRepo.findByIdAndDeletedAtIsNull(b.id)).thenReturn(Optional.of(b));
        when(recordRepo.findByBatchIdAndPlayerKey(b.id, "player_1")).thenReturn(List.of());
        when(codeRepo.redeemIfAvailable(c.id, "player_1")).thenReturn(1);
        when(recordRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RedeemRecordEntity record = service.redeem("game_demo", "ABC234", "player_1");
        assertEquals("game_demo", record.gameId);
    }

    @Test
    @DisplayName("查询：玩家历史与批次码列表")
    void listHistoryAndCodes() {
        stubGame();
        RedeemCodeBatchEntity b = batch(RedeemCodeBatchEntity.CodeType.UNIQUE, 2, 1);
        b.id = "rb_q";
        when(recordRepo.findByGameIdAndPlayerKeyOrderByRedeemedAtDesc("game_demo", "player_1"))
            .thenReturn(List.of(new RedeemRecordEntity()));
        when(batchRepo.findByIdAndDeletedAtIsNull(b.id)).thenReturn(Optional.of(b));
        when(codeRepo.findByBatchId(b.id)).thenReturn(List.of(
            code(b.id, "A", RedeemCodeEntity.Status.AVAILABLE),
            code(b.id, "B", RedeemCodeEntity.Status.REDEEMED)));

        assertEquals(1, service.playerHistory("game_demo", "player_1").size());
        assertEquals(2, service.listCodes(b.id).size());
    }
}
