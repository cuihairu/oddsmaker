package io.oddsmaker.control.service;

import io.oddsmaker.control.dto.EventDefinitionDTO;
import io.oddsmaker.control.dto.EventPropertyDefinitionDTO;
import io.oddsmaker.control.dto.TrackingPlanDTO;
import io.oddsmaker.control.jpa.EventDefinitionEntity;
import io.oddsmaker.control.jpa.EventDefinitionRepo;
import io.oddsmaker.control.jpa.EventPropertyDefinitionEntity;
import io.oddsmaker.control.jpa.EventPropertyDefinitionRepo;
import io.oddsmaker.control.jpa.GameEntity;
import io.oddsmaker.control.jpa.GameEnvironmentEntity;
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
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/**
 * ML 模型与实验平台 Service 深度单元测试（LAY-005 拆分）：追踪计划 CRUD 与 draft/active 状态机、事件定义校验、属性定义校验、级联删除、游戏信息回填。与 MLModelServiceTest / ExperimentServiceTest / TrackingPlanServiceTest / PermissionServiceTest 互补。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("追踪计划 Service 深度测试（计划/事件/属性状态机）")
class TrackingPlanServiceDeepTest {

    // ==================== TrackingPlanService 依赖 ====================

    @Mock
    private TrackingPlanRepo trackingPlanRepo;

    @Mock
    private EventDefinitionRepo eventDefinitionRepo;

    @Mock
    private EventPropertyDefinitionRepo propertyDefinitionRepo;

    @Mock
    private GameRepo gameRepo;

    @Mock
    private GameEnvironmentRepo environmentRepo;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private TrackingPlanService trackingPlanService;

    private static GameEntity game() {
        GameEntity g = new GameEntity();
        g.id = "game_1";
        g.name = "Demo Game";
        return g;
    }

    private static GameEnvironmentEntity env(String id) {
        GameEnvironmentEntity e = new GameEnvironmentEntity();
        e.id = id;
        e.gameId = "game_1";
        e.name = "prod";
        return e;
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
    @DisplayName("createTrackingPlan - 成功创建草稿计划")
    void createTrackingPlan_success() {
        lenient().when(gameRepo.findById("game_1")).thenReturn(Optional.of(game()));
        lenient().when(trackingPlanRepo.findByGameIdAndDeletedAtIsNullOrderByCreatedAtDesc("game_1"))
            .thenReturn(List.of());
        lenient().when(trackingPlanRepo.save(any(TrackingPlanEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        TrackingPlanDTO dto = new TrackingPlanDTO();
        dto.name = "2026-Q1";
        dto.displayName = "2026 Q1 Plan";
        dto.version = "1.0.0";

        TrackingPlanDTO created = trackingPlanService.createTrackingPlan("game_1", dto);

        assertThat(created.id).startsWith("tp_");
        assertThat(created.status).isEqualTo(TrackingPlanEntity.PlanStatus.DRAFT);
        assertThat(created.gameId).isEqualTo("game_1");
        assertThat(created.totalEvents).isZero();
    }

    @Test
    @DisplayName("createTrackingPlan - 游戏不存在抛参数异常")
    void createTrackingPlan_gameNotFound() {
        lenient().when(gameRepo.findById("game_x")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> trackingPlanService.createTrackingPlan("game_x", new TrackingPlanDTO()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Game not found");
    }

    @Test
    @DisplayName("createTrackingPlan - 名称重复抛参数异常")
    void createTrackingPlan_duplicateNameThrows() {
        lenient().when(gameRepo.findById("game_1")).thenReturn(Optional.of(game()));
        lenient().when(trackingPlanRepo.findByGameIdAndDeletedAtIsNullOrderByCreatedAtDesc("game_1"))
            .thenReturn(List.of(plan("tp_0", TrackingPlanEntity.PlanStatus.ACTIVE)));

        TrackingPlanDTO dto = new TrackingPlanDTO();
        dto.name = "v1";

        assertThatThrownBy(() -> trackingPlanService.createTrackingPlan("game_1", dto))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("name already exists");
    }

    @Test
    @DisplayName("createTrackingPlan - 绑定存在环境成功")
    void createTrackingPlan_withEnvironment() {
        lenient().when(gameRepo.findById("game_1")).thenReturn(Optional.of(game()));
        lenient().when(trackingPlanRepo.findByGameIdAndDeletedAtIsNullOrderByCreatedAtDesc("game_1"))
            .thenReturn(List.of());
        lenient().when(environmentRepo.findById("env_1")).thenReturn(Optional.of(env("env_1")));
        lenient().when(trackingPlanRepo.save(any(TrackingPlanEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        TrackingPlanDTO dto = new TrackingPlanDTO();
        dto.name = "prod-plan";
        dto.environmentId = "env_1";

        TrackingPlanDTO created = trackingPlanService.createTrackingPlan("game_1", dto);

        assertThat(created.environmentId).isEqualTo("env_1");
    }

    @Test
    @DisplayName("createTrackingPlan - 绑定不存在环境抛参数异常")
    void createTrackingPlan_environmentNotFound() {
        lenient().when(gameRepo.findById("game_1")).thenReturn(Optional.of(game()));
        lenient().when(trackingPlanRepo.findByGameIdAndDeletedAtIsNullOrderByCreatedAtDesc("game_1"))
            .thenReturn(List.of());
        lenient().when(environmentRepo.findById("env_x")).thenReturn(Optional.empty());

        TrackingPlanDTO dto = new TrackingPlanDTO();
        dto.name = "prod-plan";
        dto.environmentId = "env_x";

        assertThatThrownBy(() -> trackingPlanService.createTrackingPlan("game_1", dto))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Environment not found");
    }

    @Test
    @DisplayName("updateTrackingPlan - 草稿可更新展示字段")
    void updateTrackingPlan_success() {
        TrackingPlanEntity draft = plan("tp_1", TrackingPlanEntity.PlanStatus.DRAFT);
        lenient().when(trackingPlanRepo.findByIdAndDeletedAtIsNull("tp_1")).thenReturn(Optional.of(draft));
        lenient().when(trackingPlanRepo.save(any(TrackingPlanEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        TrackingPlanDTO dto = new TrackingPlanDTO();
        dto.displayName = "New Display";
        dto.description = "updated";
        dto.version = "1.1.0";

        TrackingPlanDTO updated = trackingPlanService.updateTrackingPlan("tp_1", dto);

        assertThat(updated.displayName).isEqualTo("New Display");
        assertThat(updated.version).isEqualTo("1.1.0");
        assertThat(draft.updatedAt).isNotNull();
    }

    @Test
    @DisplayName("updateTrackingPlan - 非草稿状态抛状态异常")
    void updateTrackingPlan_notDraftThrows() {
        lenient().when(trackingPlanRepo.findByIdAndDeletedAtIsNull("tp_1"))
            .thenReturn(Optional.of(plan("tp_1", TrackingPlanEntity.PlanStatus.ACTIVE)));

        assertThatThrownBy(() -> trackingPlanService.updateTrackingPlan("tp_1", new TrackingPlanDTO()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Only draft tracking plans can be edited");
    }

    @Test
    @DisplayName("updateTrackingPlan - 计划不存在抛参数异常")
    void updateTrackingPlan_notFoundThrows() {
        lenient().when(trackingPlanRepo.findByIdAndDeletedAtIsNull("tp_x")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> trackingPlanService.updateTrackingPlan("tp_x", new TrackingPlanDTO()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Tracking plan not found");
    }

    @Test
    @DisplayName("activateTrackingPlan - 激活并刷新事件计数")
    void activateTrackingPlan_success_updatesCounts() {
        TrackingPlanEntity draft = plan("tp_1", TrackingPlanEntity.PlanStatus.DRAFT);
        EventDefinitionEntity active = eventDef("ed_a", "tp_1");
        EventDefinitionEntity disabled = eventDef("ed_b", "tp_1");
        disabled.status = EventDefinitionEntity.DefinitionStatus.DISABLED;
        lenient().when(trackingPlanRepo.findByIdAndDeletedAtIsNull("tp_1")).thenReturn(Optional.of(draft));
        lenient().when(trackingPlanRepo.save(any(TrackingPlanEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(eventDefinitionRepo.countByTrackingPlanId("tp_1")).thenReturn(3L);
        lenient().when(eventDefinitionRepo.findActiveByTrackingPlanId("tp_1"))
            .thenReturn(List.of(active, disabled));

        TrackingPlanDTO result = trackingPlanService.activateTrackingPlan("tp_1", "ops");

        assertThat(result.status).isEqualTo(TrackingPlanEntity.PlanStatus.ACTIVE);
        assertThat(result.activatedBy).isEqualTo("ops");
        assertThat(result.activatedAt).isNotNull();
        assertThat(result.totalEvents).isEqualTo(3);
        assertThat(result.activeEvents).isEqualTo(1);
    }

    @Test
    @DisplayName("activateTrackingPlan - 非草稿状态抛状态异常")
    void activateTrackingPlan_notDraftThrows() {
        lenient().when(trackingPlanRepo.findByIdAndDeletedAtIsNull("tp_1"))
            .thenReturn(Optional.of(plan("tp_1", TrackingPlanEntity.PlanStatus.ACTIVE)));

        assertThatThrownBy(() -> trackingPlanService.activateTrackingPlan("tp_1", "ops"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Only draft plans can be activated");
    }

    @Test
    @DisplayName("deactivateTrackingPlan - 活跃计划置为弃用")
    void deactivateTrackingPlan_success() {
        TrackingPlanEntity active = plan("tp_1", TrackingPlanEntity.PlanStatus.ACTIVE);
        lenient().when(trackingPlanRepo.findByIdAndDeletedAtIsNull("tp_1")).thenReturn(Optional.of(active));
        lenient().when(trackingPlanRepo.save(any(TrackingPlanEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        TrackingPlanDTO result = trackingPlanService.deactivateTrackingPlan("tp_1");

        assertThat(result.status).isEqualTo(TrackingPlanEntity.PlanStatus.DEPRECATED);
        assertThat(result.deactivatedAt).isNotNull();
    }

    @Test
    @DisplayName("deactivateTrackingPlan - 非活跃状态抛状态异常")
    void deactivateTrackingPlan_notActiveThrows() {
        lenient().when(trackingPlanRepo.findByIdAndDeletedAtIsNull("tp_1"))
            .thenReturn(Optional.of(plan("tp_1", TrackingPlanEntity.PlanStatus.DRAFT)));

        assertThatThrownBy(() -> trackingPlanService.deactivateTrackingPlan("tp_1"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Only active plans can be deactivated");
    }

    @Test
    @DisplayName("deleteTrackingPlan - 活跃计划拒绝删除")
    void deleteTrackingPlan_activeThrows() {
        lenient().when(trackingPlanRepo.findByIdAndDeletedAtIsNull("tp_1"))
            .thenReturn(Optional.of(plan("tp_1", TrackingPlanEntity.PlanStatus.ACTIVE)));

        assertThatThrownBy(() -> trackingPlanService.deleteTrackingPlan("tp_1"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Cannot delete active tracking plan");
    }

    @Test
    @DisplayName("deleteTrackingPlan - 软删除并级联弃用事件定义")
    void deleteTrackingPlan_cascadesToEventDefinitions() {
        TrackingPlanEntity deprecated = plan("tp_1", TrackingPlanEntity.PlanStatus.DEPRECATED);
        EventDefinitionEntity def = eventDef("ed_1", "tp_1");
        lenient().when(trackingPlanRepo.findByIdAndDeletedAtIsNull("tp_1")).thenReturn(Optional.of(deprecated));
        lenient().when(trackingPlanRepo.save(any(TrackingPlanEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(eventDefinitionRepo.findByTrackingPlanIdAndDeletedAtIsNullOrderByEventNameAsc("tp_1"))
            .thenReturn(List.of(def));
        lenient().when(eventDefinitionRepo.save(any(EventDefinitionEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        trackingPlanService.deleteTrackingPlan("tp_1");

        assertThat(deprecated.deletedAt).isNotNull();
        assertThat(deprecated.status).isEqualTo(TrackingPlanEntity.PlanStatus.DEPRECATED);
        assertThat(def.deletedAt).isNotNull();
        assertThat(def.status).isEqualTo(EventDefinitionEntity.DefinitionStatus.DEPRECATED);
        verify(eventDefinitionRepo).save(def);
    }

    @Test
    @DisplayName("getTrackingPlan - 回填游戏与环境名称")
    void getTrackingPlan_enrichesGameInfo() {
        TrackingPlanEntity p = plan("tp_1", TrackingPlanEntity.PlanStatus.DRAFT);
        p.environmentId = "env_1";
        lenient().when(trackingPlanRepo.findByIdAndDeletedAtIsNull("tp_1")).thenReturn(Optional.of(p));
        lenient().when(gameRepo.findById("game_1")).thenReturn(Optional.of(game()));
        lenient().when(environmentRepo.findById("env_1")).thenReturn(Optional.of(env("env_1")));

        Optional<TrackingPlanDTO> dto = trackingPlanService.getTrackingPlan("tp_1");

        assertThat(dto).isPresent();
        assertThat(dto.get().gameName).isEqualTo("Demo Game");
        assertThat(dto.get().environmentName).isEqualTo("prod");
    }

    @Test
    @DisplayName("listEventDefinitions - 映射为 DTO 列表")
    void listEventDefinitions_mapsDtos() {
        lenient().when(trackingPlanRepo.findByIdAndDeletedAtIsNull("tp_1"))
            .thenReturn(Optional.of(plan("tp_1", TrackingPlanEntity.PlanStatus.DRAFT)));
        lenient().when(eventDefinitionRepo.findByTrackingPlanIdAndDeletedAtIsNullOrderByEventNameAsc("tp_1"))
            .thenReturn(List.of(eventDef("ed_a", "tp_1"), eventDef("ed_b", "tp_1")));

        assertThat(trackingPlanService.listEventDefinitions("tp_1")).hasSize(2);
    }

    @Test
    @DisplayName("createEventDefinition - 草稿计划内创建并刷新计数")
    void createEventDefinition_success() {
        TrackingPlanEntity draft = plan("tp_1", TrackingPlanEntity.PlanStatus.DRAFT);
        lenient().when(trackingPlanRepo.findByIdAndDeletedAtIsNull("tp_1")).thenReturn(Optional.of(draft));
        lenient().when(eventDefinitionRepo.existsByTrackingPlanIdAndEventName("tp_1", "level_start"))
            .thenReturn(false);
        lenient().when(eventDefinitionRepo.save(any(EventDefinitionEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(eventDefinitionRepo.countByTrackingPlanId("tp_1")).thenReturn(1L);
        lenient().when(trackingPlanRepo.save(any(TrackingPlanEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        EventDefinitionDTO dto = new EventDefinitionDTO();
        dto.eventName = "level_start";
        dto.eventType = "progression";

        EventDefinitionDTO created = trackingPlanService.createEventDefinition("tp_1", dto);

        assertThat(created.id).startsWith("ed_");
        assertThat(created.eventName).isEqualTo("level_start");
        assertThat(created.usageCount).isZero();
        assertThat(draft.totalEvents).isEqualTo(1);
    }

    @Test
    @DisplayName("createEventDefinition - 非草稿计划抛状态异常")
    void createEventDefinition_notDraftThrows() {
        lenient().when(trackingPlanRepo.findByIdAndDeletedAtIsNull("tp_1"))
            .thenReturn(Optional.of(plan("tp_1", TrackingPlanEntity.PlanStatus.ACTIVE)));

        assertThatThrownBy(() -> trackingPlanService.createEventDefinition("tp_1", new EventDefinitionDTO()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("draft tracking plans");
    }

    @Test
    @DisplayName("createEventDefinition - 事件名重复抛参数异常")
    void createEventDefinition_duplicateEventNameThrows() {
        lenient().when(trackingPlanRepo.findByIdAndDeletedAtIsNull("tp_1"))
            .thenReturn(Optional.of(plan("tp_1", TrackingPlanEntity.PlanStatus.DRAFT)));
        lenient().when(eventDefinitionRepo.existsByTrackingPlanIdAndEventName("tp_1", "level_complete"))
            .thenReturn(true);

        EventDefinitionDTO dto = new EventDefinitionDTO();
        dto.eventName = "level_complete";
        dto.eventType = "progression";

        assertThatThrownBy(() -> trackingPlanService.createEventDefinition("tp_1", dto))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Event name already exists");
    }

    @Test
    @DisplayName("updateEventDefinition - 草稿计划内更新展示名")
    void updateEventDefinition_success() {
        EventDefinitionEntity def = eventDef("ed_1", "tp_1");
        lenient().when(eventDefinitionRepo.findById("ed_1")).thenReturn(Optional.of(def));
        lenient().when(trackingPlanRepo.findByIdAndDeletedAtIsNull("tp_1"))
            .thenReturn(Optional.of(plan("tp_1", TrackingPlanEntity.PlanStatus.DRAFT)));
        lenient().when(eventDefinitionRepo.save(any(EventDefinitionEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        EventDefinitionDTO dto = new EventDefinitionDTO();
        dto.displayName = "Level Completed";
        dto.description = "desc";

        EventDefinitionDTO updated = trackingPlanService.updateEventDefinition("ed_1", dto);

        assertThat(updated.displayName).isEqualTo("Level Completed");
        assertThat(def.updatedAt).isNotNull();
    }

    @Test
    @DisplayName("updateEventDefinition - 非草稿计划抛状态异常")
    void updateEventDefinition_notDraftThrows() {
        lenient().when(eventDefinitionRepo.findById("ed_1")).thenReturn(Optional.of(eventDef("ed_1", "tp_1")));
        lenient().when(trackingPlanRepo.findByIdAndDeletedAtIsNull("tp_1"))
            .thenReturn(Optional.of(plan("tp_1", TrackingPlanEntity.PlanStatus.ACTIVE)));

        assertThatThrownBy(() -> trackingPlanService.updateEventDefinition("ed_1", new EventDefinitionDTO()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("draft tracking plans");
    }

    @Test
    @DisplayName("updateEventDefinition - 事件不存在抛参数异常")
    void updateEventDefinition_notFoundThrows() {
        lenient().when(eventDefinitionRepo.findById("ed_x")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> trackingPlanService.updateEventDefinition("ed_x", new EventDefinitionDTO()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Event definition not found");
    }

    @Test
    @DisplayName("createPropertyDefinition - 非草稿计划抛状态异常")
    void createPropertyDefinition_notDraftThrows() {
        lenient().when(eventDefinitionRepo.findById("ed_1")).thenReturn(Optional.of(eventDef("ed_1", "tp_1")));
        lenient().when(trackingPlanRepo.findByIdAndDeletedAtIsNull("tp_1"))
            .thenReturn(Optional.of(plan("tp_1", TrackingPlanEntity.PlanStatus.ACTIVE)));

        assertThatThrownBy(() -> trackingPlanService.createPropertyDefinition("ed_1", new EventPropertyDefinitionDTO()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("draft tracking plans");
    }

    @Test
    @DisplayName("createPropertyDefinition - 属性名重复抛参数异常")
    void createPropertyDefinition_duplicatePropertyNameThrows() {
        lenient().when(eventDefinitionRepo.findById("ed_1")).thenReturn(Optional.of(eventDef("ed_1", "tp_1")));
        lenient().when(trackingPlanRepo.findByIdAndDeletedAtIsNull("tp_1"))
            .thenReturn(Optional.of(plan("tp_1", TrackingPlanEntity.PlanStatus.DRAFT)));
        lenient().when(propertyDefinitionRepo.findByEventDefinitionIdAndPropertyName("ed_1", "tier"))
            .thenReturn(Optional.of(new EventPropertyDefinitionEntity()));

        EventPropertyDefinitionDTO dto = new EventPropertyDefinitionDTO();
        dto.propertyName = "tier";
        dto.type = EventPropertyDefinitionEntity.PropertyType.STRING;

        assertThatThrownBy(() -> trackingPlanService.createPropertyDefinition("ed_1", dto))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Property name already exists");
    }

    @Test
    @DisplayName("updatePropertyDefinition - 属性属于其他事件时抛参数异常")
    void updatePropertyDefinition_wrongEventThrows() {
        lenient().when(eventDefinitionRepo.findById("ed_1")).thenReturn(Optional.of(eventDef("ed_1", "tp_1")));
        lenient().when(trackingPlanRepo.findByIdAndDeletedAtIsNull("tp_1"))
            .thenReturn(Optional.of(plan("tp_1", TrackingPlanEntity.PlanStatus.DRAFT)));
        EventPropertyDefinitionEntity other = new EventPropertyDefinitionEntity();
        other.id = "epd_1";
        other.eventDefinitionId = "ed_other";
        lenient().when(propertyDefinitionRepo.findById("epd_1")).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> trackingPlanService.updatePropertyDefinition(
            "ed_1", "epd_1", new EventPropertyDefinitionDTO()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Property definition not found");
    }

}
