package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.EventDefinitionEntity;
import io.oddsmaker.control.jpa.EventDefinitionRepo;
import io.oddsmaker.control.jpa.EventPropertyDefinitionEntity;
import io.oddsmaker.control.jpa.EventPropertyDefinitionRepo;
import io.oddsmaker.control.jpa.GameEntity;
import io.oddsmaker.control.jpa.GameEnvironmentRepo;
import io.oddsmaker.control.jpa.GameRepo;
import io.oddsmaker.control.jpa.TrackingPlanEntity;
import io.oddsmaker.control.jpa.TrackingPlanRepo;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Service 层覆盖率收尾测试（LAY-006 拆分）：追踪计划删除级联事件定义、计划列表查询、事件/属性定义读取。与 TrackingPlanServiceTest / TrackingPlanServiceDeepTest 等互补。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("追踪计划 Service 深度测试（删除/列表/定义读取）")
class ServicesFinalSweepTrackingPlanTest {

    @Mock
    private GameRepo gameRepo;

    @Mock
    private GameEnvironmentRepo gameEnvironmentRepo;

    @Mock
    private TrackingPlanRepo trackingPlanRepo;

    @Mock
    private EventDefinitionRepo eventDefinitionRepo;

    @Mock
    private EventPropertyDefinitionRepo propertyDefinitionRepo;

    @Mock
    private AuditLogService auditLog;

    @InjectMocks
    private TrackingPlanService trackingPlanService;

    private static GameEntity game(String id, GameEntity.GameStatus status) {
        GameEntity g = new GameEntity();
        g.id = id;
        g.name = "Game-" + id;
        g.status = status;
        return g;
    }

    private static TrackingPlanEntity plan(String id, TrackingPlanEntity.PlanStatus status) {
        TrackingPlanEntity p = new TrackingPlanEntity();
        p.id = id;
        p.gameId = "game_1";
        p.name = "v1";
        p.status = status;
        return p;
    }

    private static EventDefinitionEntity eventDef(String id, String planId) {
        EventDefinitionEntity e = new EventDefinitionEntity();
        e.id = id;
        e.trackingPlanId = planId;
        e.eventName = "level_complete";
        e.eventType = "progression";
        return e;
    }

    @Test
    @DisplayName("追踪计划：deleteEventDefinition 软删除并刷新计数，非草稿/缺失被拒绝")
    void trackingPlanDeleteEventDefinition() {
        TrackingPlanEntity draft = plan("tp_1", TrackingPlanEntity.PlanStatus.DRAFT);
        EventDefinitionEntity def = eventDef("ed_1", "tp_1");
        lenient().when(eventDefinitionRepo.findById("ed_1")).thenReturn(Optional.of(def));
        lenient().when(trackingPlanRepo.findByIdAndDeletedAtIsNull("tp_1")).thenReturn(Optional.of(draft));
        lenient().when(eventDefinitionRepo.countByTrackingPlanId("tp_1")).thenReturn(0L);
        lenient().when(eventDefinitionRepo.save(any(EventDefinitionEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(trackingPlanRepo.save(any(TrackingPlanEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        trackingPlanService.deleteEventDefinition("ed_1");

        assertNotNull(def.deletedAt);
        assertEquals(EventDefinitionEntity.DefinitionStatus.DEPRECATED, def.status);
        assertEquals(0, draft.totalEvents);

        def.deletedAt = null;
        lenient().when(trackingPlanRepo.findByIdAndDeletedAtIsNull("tp_1"))
            .thenReturn(Optional.of(plan("tp_1", TrackingPlanEntity.PlanStatus.ACTIVE)));
        assertThrows(IllegalStateException.class,
            () -> trackingPlanService.deleteEventDefinition("ed_1"));

        lenient().when(eventDefinitionRepo.findById("ed_missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
            () -> trackingPlanService.deleteEventDefinition("ed_missing"));
    }

    @Test
    @DisplayName("追踪计划：三个列表查询（游戏全量/活跃/按环境）")
    void trackingPlanListQueries() {
        GameEntity g = game("game_1", GameEntity.GameStatus.LIVE);
        TrackingPlanEntity p = plan("tp_1", TrackingPlanEntity.PlanStatus.ACTIVE);
        lenient().when(gameRepo.findById("game_1")).thenReturn(Optional.of(g));
        lenient().when(gameRepo.findById("game_x")).thenReturn(Optional.empty());
        lenient().when(trackingPlanRepo.findByGameIdAndDeletedAtIsNullOrderByCreatedAtDesc("game_1"))
            .thenReturn(List.of(p));
        lenient().when(trackingPlanRepo.findActiveByGameId("game_1")).thenReturn(List.of(p));
        lenient().when(trackingPlanRepo.findByGameIdAndEnvironment("game_1", "env_prod")).thenReturn(List.of(p));

        assertEquals(1, trackingPlanService.listTrackingPlans("game_1").size());
        assertEquals(1, trackingPlanService.getActiveTrackingPlans("game_1").size());
        assertEquals(1, trackingPlanService.getTrackingPlansForEnvironment("game_1", "env_prod").size());

        assertThrows(IllegalArgumentException.class,
            () -> trackingPlanService.listTrackingPlans("game_x"));
        assertThrows(IllegalArgumentException.class,
            () -> trackingPlanService.getActiveTrackingPlans("game_x"));
    }

    @Test
    @DisplayName("追踪计划：getEventDefinition 过滤已删除，listPropertyDefinitions 校验事件存在")
    void trackingPlanDefinitionReads() {
        EventDefinitionEntity def = eventDef("ed_1", "tp_1");
        lenient().when(eventDefinitionRepo.findById("ed_1")).thenReturn(Optional.of(def));
        EventDefinitionEntity deleted = eventDef("ed_2", "tp_1");
        deleted.deletedAt = LocalDateTime.now();
        lenient().when(eventDefinitionRepo.findById("ed_2")).thenReturn(Optional.of(deleted));
        lenient().when(eventDefinitionRepo.findById("ed_missing")).thenReturn(Optional.empty());

        assertTrue(trackingPlanService.getEventDefinition("ed_1").isPresent());
        assertTrue(trackingPlanService.getEventDefinition("ed_2").isEmpty());
        assertTrue(trackingPlanService.getEventDefinition("ed_missing").isEmpty());

        EventPropertyDefinitionEntity prop = new EventPropertyDefinitionEntity();
        prop.id = "epd_1";
        prop.eventDefinitionId = "ed_1";
        prop.propertyName = "level_name";
        lenient().when(propertyDefinitionRepo
            .findByEventDefinitionIdAndDeletedAtIsNullOrderByDisplayOrderAsc("ed_1")).thenReturn(List.of(prop));

        assertEquals(1, trackingPlanService.listPropertyDefinitions("ed_1").size());
        assertThrows(IllegalArgumentException.class,
            () -> trackingPlanService.listPropertyDefinitions("ed_missing"));
    }

}
