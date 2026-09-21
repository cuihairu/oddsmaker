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

    // ===== 分支对侧补充（BRANCH 收口）=====

    @Test
    @DisplayName("创建：title/content 缺失（null 与空白两侧）拒绝")
    void createRequiresTitleAndContent() {
        stubGame();
        AnnouncementEntity noTitle = draft();
        noTitle.title = null;
        assertThrows(IllegalArgumentException.class, () -> service.create(noTitle, "op_1"));
        AnnouncementEntity blankTitle = draft();
        blankTitle.title = "   ";
        assertThrows(IllegalArgumentException.class, () -> service.create(blankTitle, "op_1"));

        AnnouncementEntity noContent = draft();
        noContent.content = null;
        assertThrows(IllegalArgumentException.class, () -> service.create(noContent, "op_1"));
        AnnouncementEntity blankContent = draft();
        blankContent.content = "  ";
        assertThrows(IllegalArgumentException.class, () -> service.create(blankContent, "op_1"));
        verify(announcementRepo, never()).save(any());
    }

    @Test
    @DisplayName("创建：环境已删除或属于其他游戏（filter 两侧）拒绝或回落")
    void createEnvironmentFilterSides() {
        stubGame();
        // 环境存在但属于其他游戏：gameId.equals 为 false → 拒绝
        AnnouncementEntity foreign = draft();
        foreign.environmentId = "env_foreign";
        var otherGameEnv = new io.oddsmaker.control.jpa.GameEnvironmentEntity();
        otherGameEnv.id = "env_foreign";
        otherGameEnv.gameId = "game_other";
        when(environmentRepo.findById("env_foreign")).thenReturn(Optional.of(otherGameEnv));
        assertThrows(IllegalArgumentException.class, () -> service.create(foreign, "op_1"));

        // 环境已软删：deletedAt != null → 同样拒绝
        AnnouncementEntity deletedEnv = draft();
        deletedEnv.environmentId = "env_del";
        var delEnv = new io.oddsmaker.control.jpa.GameEnvironmentEntity();
        delEnv.id = "env_del";
        delEnv.gameId = "game_demo";
        delEnv.deletedAt = LocalDateTime.now();
        when(environmentRepo.findById("env_del")).thenReturn(Optional.of(delEnv));
        assertThrows(IllegalArgumentException.class, () -> service.create(deletedEnv, "op_1"));
    }

    @Test
    @DisplayName("更新：字段可选更新（null/空白跳过，非空写入）")
    void updateSelectivelyAppliesFields() {
        AnnouncementEntity a = draft();
        a.id = "ann_u";
        a.status = AnnouncementEntity.Status.DRAFT;
        when(announcementRepo.findById("ann_u")).thenReturn(Optional.of(a));
        when(announcementRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // title/content 为 null/空白 → 跳过；channel/priority 非空 → 写入
        AnnouncementEntity req = new AnnouncementEntity();
        req.content = "  ";
        req.channel = AnnouncementEntity.Channel.MARQUEE;
        req.priority = 5;
        AnnouncementEntity updated = service.update("ann_u", req, "op_1");
        assertEquals("维护公告", updated.title);       // 未变
        assertEquals("今晚 02:00 停服维护", updated.content); // 空白跳过
        assertEquals(AnnouncementEntity.Channel.MARQUEE, updated.channel);
        assertEquals(5, updated.priority.intValue());

        // title 非空 → 写入（title != null && !isBlank 的 true 侧）。
        // channel/priority 初始化器（LOBBY/0）在 req2 上同样非 null，不置 null 会把
        // 前段写入的 MARQUEE/5 冲回默认值，破坏后续 nullPatch 段的保留断言
        AnnouncementEntity req2 = new AnnouncementEntity();
        req2.title = "新标题";
        req2.channel = null;
        req2.priority = null;
        assertEquals("新标题", service.update("ann_u", req2, "op_1").title);

        // channel/priority 有初始化器（LOBBY/0）：HTTP 链路恒非 null，
        // null 跳过侧须显式置 null 直达——原值保留（channel=LOBBY/priority=0 保持不变）
        AnnouncementEntity nullPatch = new AnnouncementEntity();
        nullPatch.channel = null;
        nullPatch.priority = null;
        AnnouncementEntity kept = service.update("ann_u", nullPatch, "op_1");
        assertEquals(AnnouncementEntity.Channel.MARQUEE, kept.channel);  // 前段写入的 MARQUEE 保留
        assertEquals(5, kept.priority.intValue());                       // 前段写入的 5 保留
    }

    @Test
    @DisplayName("查询：软删公告视为不存在（filter deletedAt 侧）")
    void deletedAnnouncementTreatedAsMissing() {
        AnnouncementEntity a = draft();
        a.id = "ann_del";
        a.deletedAt = LocalDateTime.now();
        when(announcementRepo.findById("ann_del")).thenReturn(Optional.of(a));
        assertThrows(IllegalArgumentException.class, () -> service.publish("ann_del", "op_1"));
    }

    @Test
    @DisplayName("查询：软删游戏拒绝（requireGame filter deletedAt 侧）")
    void deletedGameRejected() {
        game.deletedAt = LocalDateTime.now();
        when(gameRepo.findById("game_demo")).thenReturn(Optional.of(game));
        assertThrows(IllegalArgumentException.class, () -> service.list("game_demo"));
    }

    @Test
    @DisplayName("活跃列表：environmentId 为 null（全环境）与已删环境回落名解析")
    void listActiveNullEnvironmentAndDeletedEnvFallback() {
        stubGame();
        // environmentId == null → resolveEnvironmentId 直接返回 ""
        when(announcementRepo.findActive(eq("game_demo"), eq(""), any(LocalDateTime.class)))
            .thenReturn(List.of());
        service.listActive("game_demo", null);
        verify(announcementRepo).findActive(eq("game_demo"), eq(""), any(LocalDateTime.class));

        // 按 ID 查到但环境已软删 → 回落按名解析；名也无 → 保守透传原值
        var deleted = new io.oddsmaker.control.jpa.GameEnvironmentEntity();
        deleted.id = "env_x";
        deleted.gameId = "game_demo";
        deleted.deletedAt = LocalDateTime.now();
        when(environmentRepo.findById("env_x")).thenReturn(Optional.of(deleted));
        when(environmentRepo.findByGameIdAndNameAndDeletedAtIsNull(eq("game_demo"), any()))
            .thenReturn(List.of());
        when(announcementRepo.findActive(eq("game_demo"), eq("env_x"), any(LocalDateTime.class)))
            .thenReturn(List.of());
        service.listActive("game_demo", "env_x");
        verify(announcementRepo).findActive(eq("game_demo"), eq("env_x"), any(LocalDateTime.class));

        // 环境活着但属于别家游戏（gameId.equals false 侧）→ 同样回落按名解析，名也无 → 透传
        var foreign = new io.oddsmaker.control.jpa.GameEnvironmentEntity();
        foreign.id = "env_foreign_id";
        foreign.gameId = "game_other";
        when(environmentRepo.findById("env_foreign_id")).thenReturn(Optional.of(foreign));
        when(environmentRepo.findByGameIdAndNameAndDeletedAtIsNull(eq("game_demo"), eq("env_foreign_id")))
            .thenReturn(List.of());
        when(announcementRepo.findActive(eq("game_demo"), eq("env_foreign_id"), any(LocalDateTime.class)))
            .thenReturn(List.of());
        service.listActive("game_demo", "env_foreign_id");
        verify(announcementRepo).findActive(eq("game_demo"), eq("env_foreign_id"), any(LocalDateTime.class));
    }

}
