package io.oddsmaker.control.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.oddsmaker.control.jpa.AnnouncementEntity;
import io.oddsmaker.control.jpa.AnnouncementRepo;
import io.oddsmaker.control.jpa.GameEntity;
import io.oddsmaker.control.jpa.GameRepo;
import io.oddsmaker.control.jpa.GameEnvironmentRepo;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * 公告系统测试：生命周期（创建/定时发布/自动下线）与状态机约束。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("公告系统测试")
class AnnouncementServiceTest {

    @Mock
    private AnnouncementRepo announcementRepo;

    @Mock
    private GameRepo gameRepo;

    @Mock
    private GameEnvironmentRepo environmentRepo;

    @Mock
    private AuditLogService auditLog;

    @InjectMocks
    private AnnouncementService service;

    private GameEntity game;

    @BeforeEach
    void setUp() {
        game = new GameEntity();
        game.id = "game_demo";
        game.name = "Demo";
    }

    private static AnnouncementEntity draft() {
        AnnouncementEntity a = new AnnouncementEntity();
        a.gameId = "game_demo";
        a.title = "维护公告";
        a.content = "今晚 02:00 停服维护";
        return a;
    }

    private void stubGame() {
        when(gameRepo.findById("game_demo")).thenReturn(Optional.of(game));
    }

    @Test
    @DisplayName("创建：无排期为草稿，审计记录")
    void createWithoutScheduleIsDraft() {
        stubGame();
        when(announcementRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AnnouncementEntity created = service.create(draft(), "op_1");

        assertEquals(AnnouncementEntity.Status.DRAFT, created.status);
        assertNotNull(created.id);
        verify(auditLog).logCreate(eq("announcement"), eq(created.id), eq("维护公告"),
            eq("op_1"), eq("op_1"), isNull(), anyMap());
    }

    @Test
    @DisplayName("创建：带未来时间为 SCHEDULED")
    void createWithFutureScheduleIsScheduled() {
        stubGame();
        when(announcementRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        AnnouncementEntity a = draft();
        a.scheduledAt = LocalDateTime.now().plusHours(2);

        AnnouncementEntity created = service.create(a, "op_1");
        assertEquals(AnnouncementEntity.Status.SCHEDULED, created.status);
    }

    @Test
    @DisplayName("创建：过去时间被拒绝")
    void createWithPastScheduleRejected() {
        stubGame();
        AnnouncementEntity a = draft();
        a.scheduledAt = LocalDateTime.now().minusHours(1);
        assertThrows(IllegalArgumentException.class, () -> service.create(a, "op_1"));
        verify(announcementRepo, never()).save(any());
    }

    @Test
    @DisplayName("创建：环境不属于游戏被拒绝")
    void createWithForeignEnvironmentRejected() {
        stubGame();
        AnnouncementEntity a = draft();
        a.environmentId = "env_other_game";
        when(environmentRepo.findById("env_other_game")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.create(a, "op_1"));
    }

    @Test
    @DisplayName("发布：草稿立即置为 PUBLISHED")
    void publishDraft() {
        AnnouncementEntity a = draft();
        a.id = "ann_1";
        when(announcementRepo.findById("ann_1")).thenReturn(Optional.of(a));
        when(announcementRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AnnouncementEntity published = service.publish("ann_1", "op_1");
        assertEquals(AnnouncementEntity.Status.PUBLISHED, published.status);
        assertNotNull(published.publishedAt);
    }

    @Test
    @DisplayName("发布：已下线公告不可再发布")
    void offlineAnnouncementCannotBeRepublished() {
        AnnouncementEntity a = draft();
        a.id = "ann_2";
        a.status = AnnouncementEntity.Status.OFFLINE;
        when(announcementRepo.findById("ann_2")).thenReturn(Optional.of(a));

        assertThrows(IllegalStateException.class, () -> service.publish("ann_2", "op_1"));
    }

    @Test
    @DisplayName("下线：仅 PUBLISHED 可下线")
    void onlyPublishedCanGoOffline() {
        AnnouncementEntity a = draft();
        a.id = "ann_3";
        a.status = AnnouncementEntity.Status.DRAFT;
        when(announcementRepo.findById("ann_3")).thenReturn(Optional.of(a));
        assertThrows(IllegalStateException.class, () -> service.offline("ann_3", "op_1"));
    }

    @Test
    @DisplayName("删除：已发布公告必须先下线")
    void publishedCannotBeDeleted() {
        AnnouncementEntity a = draft();
        a.id = "ann_4";
        a.status = AnnouncementEntity.Status.PUBLISHED;
        when(announcementRepo.findById("ann_4")).thenReturn(Optional.of(a));
        assertThrows(IllegalStateException.class, () -> service.delete("ann_4", "op_1"));
    }

    @Test
    @DisplayName("定时扫描：到期 SCHEDULED 自动发布")
    void sweepPublishesDueAnnouncements() {
        AnnouncementEntity due = draft();
        due.id = "ann_due";
        due.status = AnnouncementEntity.Status.SCHEDULED;
        due.scheduledAt = LocalDateTime.now().minusMinutes(1);
        when(announcementRepo.findByStatusAndScheduledAtLessThanEqualAndDeletedAtIsNull(
            eq(AnnouncementEntity.Status.SCHEDULED), any(LocalDateTime.class)))
            .thenReturn(List.of(due));
        when(announcementRepo.findByStatusAndAutoOfflineAtLessThanEqualAndDeletedAtIsNull(
            eq(AnnouncementEntity.Status.PUBLISHED), any(LocalDateTime.class)))
            .thenReturn(List.of());
        when(announcementRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.sweep();

        assertEquals(AnnouncementEntity.Status.PUBLISHED, due.status);
        assertNotNull(due.publishedAt);
        assertNull(due.scheduledAt);
    }

    @Test
    @DisplayName("定时扫描：到下线时间的公告自动下线")
    void sweepOfflinesExpiredAnnouncements() {
        AnnouncementEntity published = draft();
        published.id = "ann_exp";
        published.status = AnnouncementEntity.Status.PUBLISHED;
        published.autoOfflineAt = LocalDateTime.now().minusMinutes(5);
        when(announcementRepo.findByStatusAndScheduledAtLessThanEqualAndDeletedAtIsNull(
            eq(AnnouncementEntity.Status.SCHEDULED), any(LocalDateTime.class)))
            .thenReturn(List.of());
        when(announcementRepo.findByStatusAndAutoOfflineAtLessThanEqualAndDeletedAtIsNull(
            eq(AnnouncementEntity.Status.PUBLISHED), any(LocalDateTime.class)))
            .thenReturn(List.of(published));
        when(announcementRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.sweep();

        assertEquals(AnnouncementEntity.Status.OFFLINE, published.status);
        assertNotNull(published.offlineAt);
    }

    @Test
    @DisplayName("活跃列表：传环境名可解析为环境ID")
    void listActiveResolvesEnvironmentName() {
        stubGame();
        var env = new io.oddsmaker.control.jpa.GameEnvironmentEntity();
        env.id = "env_demo_prod";
        env.gameId = "game_demo";
        env.name = "prod";
        when(environmentRepo.findById("prod")).thenReturn(Optional.empty());
        when(environmentRepo.findByGameIdAndNameAndDeletedAtIsNull("game_demo", "prod"))
            .thenReturn(List.of(env));
        when(announcementRepo.findActive(eq("game_demo"), eq("env_demo_prod"), any(LocalDateTime.class)))
            .thenReturn(List.of());

        service.listActive("game_demo", "prod");
        verify(announcementRepo).findActive(eq("game_demo"), eq("env_demo_prod"), any(LocalDateTime.class));
    }

}
