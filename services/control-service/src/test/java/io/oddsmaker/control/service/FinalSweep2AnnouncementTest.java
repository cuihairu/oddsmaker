package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.AnnouncementEntity;
import io.oddsmaker.control.jpa.AnnouncementRepo;
import io.oddsmaker.control.jpa.GameEntity;
import io.oddsmaker.control.jpa.GameEnvironmentEntity;
import io.oddsmaker.control.jpa.GameEnvironmentRepo;
import io.oddsmaker.control.jpa.GameRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.LocalDateTime;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 覆盖率最终冲刺（第三轮，LAY-016 拆分）：AnnouncementService——list/get 查询与 create 全环境入参守卫、update 全字段与排期分支、publish/schedule/offline/delete 生命周期残余、listActive 环境直传与空环境。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("覆盖率最终冲刺测试（第三轮）")
class FinalSweep2AnnouncementTest {

    @Mock
    private AnnouncementRepo announcementRepo;

    @Mock
    private GameRepo gameRepo;

    @Mock
    private GameEnvironmentRepo gameEnvironmentRepo;

    @Mock
    private AuditLogService auditLog;

    @InjectMocks
    private AnnouncementService announcementService;

    private static GameEntity game(String id) {
        GameEntity g = new GameEntity();
        g.id = id;
        g.name = "Game-" + id;
        return g;
    }

    private static GameEnvironmentEntity environment(String gameId, String id, String name) {
        GameEnvironmentEntity e = new GameEnvironmentEntity();
        e.id = id;
        e.gameId = gameId;
        e.name = name;
        e.status = GameEnvironmentEntity.EnvironmentStatus.ACTIVE;
        return e;
    }

    private static AnnouncementEntity announcement(String id, AnnouncementEntity.Status status) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.id = id;
        a.gameId = "game_demo";
        a.title = "维护公告";
        a.content = "今晚停服维护";
        a.status = status;
        return a;
    }

    @Test
    @DisplayName("公告：list/get 查询与 create 全环境分支及入参守卫")
    void announcementListGetAndCreateGuards() {
        GameEntity demo = game("game_demo");
        lenient().when(gameRepo.findById("game_demo")).thenReturn(Optional.of(demo));
        lenient().when(gameRepo.findById("game_x")).thenReturn(Optional.empty());

        AnnouncementEntity a = announcement("ann_1", AnnouncementEntity.Status.DRAFT);
        lenient().when(announcementRepo
            .findByGameIdAndDeletedAtIsNullOrderByPriorityDescCreatedAtDesc("game_demo"))
            .thenReturn(List.of(a));
        assertEquals(1, announcementService.list("game_demo").size());
        assertThrows(IllegalArgumentException.class, () -> announcementService.list("game_x"));

        AnnouncementEntity deleted = announcement("ann_2", AnnouncementEntity.Status.DRAFT);
        deleted.deletedAt = LocalDateTime.now();
        lenient().when(announcementRepo.findById("ann_1")).thenReturn(Optional.of(a));
        lenient().when(announcementRepo.findById("ann_2")).thenReturn(Optional.of(deleted));
        assertSame(a, announcementService.get("ann_1"));
        assertNull(announcementService.get("ann_2"));
        assertNull(announcementService.get("ann_ghost"));

        lenient().when(announcementRepo.save(any(AnnouncementEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        AnnouncementEntity blankEnv = announcement(null, null);
        blankEnv.gameId = "game_demo";
        blankEnv.environmentId = "   ";
        AnnouncementEntity created = announcementService.create(blankEnv, "op_1");
        assertNull(created.environmentId);
        assertTrue(created.id.startsWith("ann_"));
        assertEquals("op_1", created.createdBy);

        AnnouncementEntity noTitle = announcement(null, null);
        noTitle.gameId = "game_demo";
        noTitle.title = "  ";
        assertThrows(IllegalArgumentException.class, () -> announcementService.create(noTitle, "op_1"));

        AnnouncementEntity noContent = announcement(null, null);
        noContent.gameId = "game_demo";
        noContent.content = " ";
        assertThrows(IllegalArgumentException.class, () -> announcementService.create(noContent, "op_1"));

        AnnouncementEntity noGame = announcement(null, null);
        noGame.gameId = "game_x";
        assertThrows(IllegalArgumentException.class, () -> announcementService.create(noGame, "op_1"));
        verify(announcementRepo, never()).save(noGame);
    }

    @Test
    @DisplayName("公告：update 全字段与排期分支及生命周期残余")
    void announcementUpdateAndLifecycle() {
        AnnouncementEntity published = announcement("ann_p", AnnouncementEntity.Status.PUBLISHED);
        lenient().when(announcementRepo.findById("ann_p")).thenReturn(Optional.of(published));
        assertThrows(IllegalStateException.class,
            () -> announcementService.update("ann_p", new AnnouncementEntity(), "op"));

        lenient().when(announcementRepo.findById("ann_missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
            () -> announcementService.update("ann_missing", new AnnouncementEntity(), "op"));

        AnnouncementEntity draft = announcement("ann_1", AnnouncementEntity.Status.DRAFT);
        lenient().when(announcementRepo.findById("ann_1")).thenReturn(Optional.of(draft));
        lenient().when(announcementRepo.save(any(AnnouncementEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        AnnouncementEntity req = new AnnouncementEntity();
        req.title = "  新标题  ";
        req.content = "新内容";
        req.channel = AnnouncementEntity.Channel.MARQUEE;
        req.priority = 5;
        req.autoOfflineAt = LocalDateTime.now().plusDays(1);
        req.scheduledAt = LocalDateTime.now().plusHours(1);
        AnnouncementEntity updated = announcementService.update("ann_1", req, "op");
        assertEquals("新标题", updated.title);
        assertEquals("新内容", updated.content);
        assertEquals(AnnouncementEntity.Channel.MARQUEE, updated.channel);
        assertEquals(5, updated.priority);
        assertNotNull(updated.autoOfflineAt);
        assertEquals(AnnouncementEntity.Status.SCHEDULED, updated.status);
        assertNotNull(updated.scheduledAt);

        AnnouncementEntity pastSchedule = new AnnouncementEntity();
        pastSchedule.scheduledAt = LocalDateTime.now().minusMinutes(1);
        assertThrows(IllegalArgumentException.class,
            () -> announcementService.update("ann_1", pastSchedule, "op"));

        AnnouncementEntity blankTitle = new AnnouncementEntity();
        blankTitle.title = "   ";
        assertEquals("新标题", announcementService.update("ann_1", blankTitle, "op").title);

        // publish：已发布不可重复发布
        assertThrows(IllegalStateException.class,
            () -> announcementService.publish("ann_p", "op"));

        // schedule：草稿排期成功，过去时间/空时间拒绝，已发布拒绝
        AnnouncementEntity draftForSchedule = announcement("ann_s", AnnouncementEntity.Status.DRAFT);
        lenient().when(announcementRepo.findById("ann_s")).thenReturn(Optional.of(draftForSchedule));
        AnnouncementEntity scheduled = announcementService.schedule(
            "ann_s", LocalDateTime.now().plusHours(2), "op");
        assertEquals(AnnouncementEntity.Status.SCHEDULED, scheduled.status);
        assertThrows(IllegalArgumentException.class,
            () -> announcementService.schedule("ann_s", LocalDateTime.now().minusHours(1), "op"));
        assertThrows(IllegalArgumentException.class,
            () -> announcementService.schedule("ann_s", null, "op"));
        assertThrows(IllegalStateException.class,
            () -> announcementService.schedule("ann_p", LocalDateTime.now().plusHours(1), "op"));

        // offline：PUBLISHED → OFFLINE
        AnnouncementEntity toOffline = announcement("ann_o", AnnouncementEntity.Status.PUBLISHED);
        lenient().when(announcementRepo.findById("ann_o")).thenReturn(Optional.of(toOffline));
        AnnouncementEntity offlined = announcementService.offline("ann_o", "op");
        assertEquals(AnnouncementEntity.Status.OFFLINE, offlined.status);
        assertNotNull(offlined.offlineAt);

        // delete：草稿成功、不存在返回 false
        AnnouncementEntity deletable = announcement("ann_d", AnnouncementEntity.Status.DRAFT);
        lenient().when(announcementRepo.findById("ann_d")).thenReturn(Optional.of(deletable));
        assertTrue(announcementService.delete("ann_d", "op"));
        assertNotNull(deletable.deletedAt);
        lenient().when(announcementRepo.findById("ann_ghost")).thenReturn(Optional.empty());
        assertFalse(announcementService.delete("ann_ghost", "op"));

        // listActive：环境 ID 直传命中与空环境
        GameEntity demo = game("game_demo");
        GameEnvironmentEntity prod = environment("game_demo", "env_prod", "prod");
        lenient().when(gameRepo.findById("game_demo")).thenReturn(Optional.of(demo));
        lenient().when(gameEnvironmentRepo.findById("env_prod")).thenReturn(Optional.of(prod));
        lenient().when(announcementRepo.findActive(eq("game_demo"), anyString(), any(LocalDateTime.class)))
            .thenReturn(List.of());

        announcementService.listActive("game_demo", "env_prod");
        verify(announcementRepo).findActive(eq("game_demo"), eq("env_prod"), any(LocalDateTime.class));
        announcementService.listActive("game_demo", "   ");
        verify(announcementRepo).findActive(eq("game_demo"), eq(""), any(LocalDateTime.class));
    }

}
