package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.GameEntity;
import io.oddsmaker.control.jpa.GameRepo;
import io.oddsmaker.control.jpa.RedeemCodeBatchEntity;
import io.oddsmaker.control.jpa.RedeemCodeBatchRepo;
import io.oddsmaker.control.jpa.RedeemCodeEntity;
import io.oddsmaker.control.jpa.RedeemCodeRepo;
import io.oddsmaker.control.jpa.RedeemRecordRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Service 层覆盖率收尾测试（LAY-006 拆分）：兑换码批次查询与停用、共享码生成与创建守卫。与 RedeemCodeServiceTest 等互补。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("兑换码 Service 深度测试（批次查询/共享码生成）")
class ServicesFinalSweepRedeemTest {

    @Mock
    private RedeemCodeBatchRepo batchRepo;

    @Mock
    private RedeemCodeRepo codeRepo;

    @Mock
    private RedeemRecordRepo redeemRecordRepo;

    @Mock
    private GameRepo gameRepo;

    @Mock
    private AuditLogService auditLog;

    @InjectMocks
    private RedeemCodeService redeemCodeService;

    private static GameEntity game(String id, GameEntity.GameStatus status) {
        GameEntity g = new GameEntity();
        g.id = id;
        g.name = "Game-" + id;
        g.status = status;
        return g;
    }

    private static RedeemCodeBatchEntity batch(RedeemCodeBatchEntity.CodeType type, int total, int perUserLimit) {
        RedeemCodeBatchEntity b = new RedeemCodeBatchEntity();
        b.gameId = "game_demo";
        b.name = "活动批次";
        b.reward = "[{\"type\":\"item\",\"id\":\"gem\",\"count\":10}]";
        b.codeType = type;
        b.total = total;
        b.perUserLimit = perUserLimit;
        b.status = RedeemCodeBatchEntity.Status.ACTIVE;
        return b;
    }

    @Test
    @DisplayName("兑换码：listBatches/getBatch/disable 与游戏校验")
    void redeemBatchQueriesAndDisable() {
        GameEntity g = game("game_demo", GameEntity.GameStatus.LIVE);
        RedeemCodeBatchEntity b = batch(RedeemCodeBatchEntity.CodeType.SHARED, 0, 1);
        b.id = "rb_1";
        lenient().when(gameRepo.findById("game_demo")).thenReturn(Optional.of(g));
        lenient().when(gameRepo.findById("game_x")).thenReturn(Optional.empty());
        lenient().when(batchRepo.findByGameIdAndDeletedAtIsNullOrderByCreatedAtDesc("game_demo"))
            .thenReturn(List.of(b));
        lenient().when(batchRepo.findByIdAndDeletedAtIsNull("rb_1")).thenReturn(Optional.of(b));
        lenient().when(batchRepo.findByIdAndDeletedAtIsNull("rb_missing")).thenReturn(Optional.empty());
        lenient().when(batchRepo.save(any(RedeemCodeBatchEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        assertEquals(1, redeemCodeService.listBatches("game_demo").size());
        assertThrows(IllegalArgumentException.class, () -> redeemCodeService.listBatches("game_x"));

        assertSame(b, redeemCodeService.getBatch("rb_1"));
        assertNull(redeemCodeService.getBatch("rb_missing"));

        RedeemCodeBatchEntity disabled = redeemCodeService.disable("rb_1", "op");
        assertEquals(RedeemCodeBatchEntity.Status.DISABLED, disabled.status);
        verify(auditLog).logUpdate(eq("redeem_batch"), eq("rb_1"), eq("活动批次"),
            eq("op"), eq("op"), isNull(), anyMap());
    }

    @Test
    @DisplayName("兑换码：SHARED 未指定码时生成，UNIQUE 超上限与入参校验")
    void redeemCreateSharedGeneratedCodeAndGuards() {
        GameEntity g = game("game_demo", GameEntity.GameStatus.LIVE);
        lenient().when(gameRepo.findById("game_demo")).thenReturn(Optional.of(g));
        lenient().when(batchRepo.save(any(RedeemCodeBatchEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(codeRepo.save(any(RedeemCodeEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(codeRepo.findByCode(anyString())).thenReturn(Optional.empty());

        RedeemCodeBatchEntity created = redeemCodeService.createBatch(
            batch(RedeemCodeBatchEntity.CodeType.SHARED, 0, 1), null, 0, null, "op");

        ArgumentCaptor<RedeemCodeEntity> codeCaptor = ArgumentCaptor.forClass(RedeemCodeEntity.class);
        verify(codeRepo).save(codeCaptor.capture());
        assertNotNull(codeCaptor.getValue().code);
        assertFalse(codeCaptor.getValue().code.isBlank());
        assertEquals(created.id, codeCaptor.getValue().batchId);

        RedeemCodeBatchEntity huge = batch(RedeemCodeBatchEntity.CodeType.UNIQUE, 1_000_001, 1);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> redeemCodeService.createBatch(huge, null, 12, null, "op"));
        assertTrue(ex.getMessage().contains("total must be between"));

        assertThrows(IllegalArgumentException.class, () -> redeemCodeService.redeem("  ", "p1"));
        assertThrows(IllegalArgumentException.class, () -> redeemCodeService.redeem("CODE", " "));

        lenient().when(batchRepo.findByIdAndDeletedAtIsNull("rb_missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> redeemCodeService.listCodes("rb_missing"));
    }

}
