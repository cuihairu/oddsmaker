package io.oddsmaker.control.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.oddsmaker.control.dto.EventDefinitionDTO;
import io.oddsmaker.control.dto.EventPropertyDefinitionDTO;
import io.oddsmaker.control.dto.TrackingPlanDTO;
import io.oddsmaker.control.jpa.EventDefinitionEntity;
import io.oddsmaker.control.jpa.EventDefinitionRepo;
import io.oddsmaker.control.jpa.EventPropertyDefinitionEntity;
import io.oddsmaker.control.jpa.EventPropertyDefinitionRepo;
import io.oddsmaker.control.jpa.GameEntity;
import io.oddsmaker.control.jpa.GameEnvironmentEntity;
import io.oddsmaker.control.jpa.GameRepo;
import io.oddsmaker.control.jpa.GameEnvironmentRepo;
import io.oddsmaker.control.jpa.TrackingPlanEntity;
import io.oddsmaker.control.jpa.TrackingPlanRepo;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * TrackingPlanService 单元测试：字段字典规格（枚举/数组/cardinality 上限）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TrackingPlanService 单元测试")
class TrackingPlanServiceTest {

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
    private AuditLogService auditLog;

    @InjectMocks
    private TrackingPlanService service;

    private static TrackingPlanEntity draftPlan(String planId) {
        TrackingPlanEntity plan = new TrackingPlanEntity();
        plan.id = planId;
        plan.gameId = "game_1";
        plan.status = TrackingPlanEntity.PlanStatus.DRAFT;
        return plan;
    }

    private static EventDefinitionEntity eventDef(String id, String planId) {
        EventDefinitionEntity def = new EventDefinitionEntity();
        def.id = id;
        def.trackingPlanId = planId;
        def.eventName = "level_complete";
        def.eventType = "progression";
        return def;
    }

    private void stubDraftContext(String eventId, String planId) {
        EventDefinitionEntity def = eventDef(eventId, planId);
        when(eventDefinitionRepo.findById(eventId)).thenReturn(Optional.of(def));
        when(trackingPlanRepo.findByIdAndDeletedAtIsNull(planId)).thenReturn(Optional.of(draftPlan(planId)));
    }

    @Test
    @DisplayName("枚举属性：合法 allowedValues 创建成功")
    void enumPropertyWithValidValuesCreated() {
        stubDraftContext("evd_1", "tp_1");
        when(propertyDefinitionRepo.findByEventDefinitionIdAndPropertyName("evd_1", "result")).thenReturn(Optional.empty());
        when(propertyDefinitionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        EventPropertyDefinitionDTO dto = new EventPropertyDefinitionDTO();
        dto.propertyName = "result";
        dto.type = EventPropertyDefinitionEntity.PropertyType.ENUM;
        dto.allowedValues = "[\"win\",\"lose\",\"draw\"]";
        dto.cardinalityLimit = 3;

        EventPropertyDefinitionDTO created = service.createPropertyDefinition("evd_1", dto);

        assertEquals("result", created.propertyName);
        assertEquals(3, created.cardinalityLimit);
        verify(auditLog).logCreate(eq("event_property"), any(), eq("result"),
            eq("api"), eq("api"), isNull(), anyMap());
    }

    @Test
    @DisplayName("枚举属性：缺少 allowedValues 被拒绝")
    void enumPropertyWithoutValuesRejected() {
        stubDraftContext("evd_2", "tp_1");
        when(propertyDefinitionRepo.findByEventDefinitionIdAndPropertyName("evd_2", "state")).thenReturn(Optional.empty());

        EventPropertyDefinitionDTO dto = new EventPropertyDefinitionDTO();
        dto.propertyName = "state";
        dto.type = EventPropertyDefinitionEntity.PropertyType.ENUM;

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> service.createPropertyDefinition("evd_2", dto));
        assertTrue(ex.getMessage().contains("allowedValues"));
        verify(propertyDefinitionRepo, never()).save(any());
    }

    @Test
    @DisplayName("枚举属性：重复枚举值被拒绝")
    void enumPropertyWithDuplicateValuesRejected() {
        stubDraftContext("evd_3", "tp_1");
        when(propertyDefinitionRepo.findByEventDefinitionIdAndPropertyName("evd_3", "grade")).thenReturn(Optional.empty());

        EventPropertyDefinitionDTO dto = new EventPropertyDefinitionDTO();
        dto.propertyName = "grade";
        dto.type = EventPropertyDefinitionEntity.PropertyType.ENUM;
        dto.allowedValues = "[\"a\",\"b\",\"a\"]";

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> service.createPropertyDefinition("evd_3", dto));
        assertTrue(ex.getMessage().contains("duplicates"));
    }

    @Test
    @DisplayName("cardinality 上限：小于枚举候选数被拒绝")
    void cardinalityBelowEnumSizeRejected() {
        stubDraftContext("evd_4", "tp_1");
        when(propertyDefinitionRepo.findByEventDefinitionIdAndPropertyName("evd_4", "tier")).thenReturn(Optional.empty());

        EventPropertyDefinitionDTO dto = new EventPropertyDefinitionDTO();
        dto.propertyName = "tier";
        dto.type = EventPropertyDefinitionEntity.PropertyType.ENUM;
        dto.allowedValues = "[\"gold\",\"silver\",\"bronze\"]";
        dto.cardinalityLimit = 2;

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> service.createPropertyDefinition("evd_4", dto));
        assertTrue(ex.getMessage().contains("cardinalityLimit"));
    }

    @Test
    @DisplayName("cardinality 上限：非正值被拒绝")
    void nonPositiveCardinalityRejected() {
        stubDraftContext("evd_5", "tp_1");
        when(propertyDefinitionRepo.findByEventDefinitionIdAndPropertyName("evd_5", "level_name")).thenReturn(Optional.empty());

        EventPropertyDefinitionDTO dto = new EventPropertyDefinitionDTO();
        dto.propertyName = "level_name";
        dto.type = EventPropertyDefinitionEntity.PropertyType.STRING;
        dto.cardinalityLimit = 0;

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> service.createPropertyDefinition("evd_5", dto));
        assertTrue(ex.getMessage().contains("cardinalityLimit"));
    }

    @Test
    @DisplayName("普通字符串属性可设置独立 cardinality 上限")
    void stringPropertyWithCardinalityLimitAccepted() {
        stubDraftContext("evd_6", "tp_1");
        when(propertyDefinitionRepo.findByEventDefinitionIdAndPropertyName("evd_6", "level_name")).thenReturn(Optional.empty());
        when(propertyDefinitionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        EventPropertyDefinitionDTO dto = new EventPropertyDefinitionDTO();
        dto.propertyName = "level_name";
        dto.type = EventPropertyDefinitionEntity.PropertyType.STRING;
        dto.maxLength = 64;
        dto.cardinalityLimit = 5000;

        EventPropertyDefinitionDTO created = service.createPropertyDefinition("evd_6", dto);
        assertEquals(5000, created.cardinalityLimit);
    }

    @Test
    @DisplayName("数组属性：缺少元素类型被拒绝")
    void arrayPropertyWithoutElementTypeRejected() {
        stubDraftContext("evd_7", "tp_1");
        when(propertyDefinitionRepo.findByEventDefinitionIdAndPropertyName("evd_7", "tags")).thenReturn(Optional.empty());

        EventPropertyDefinitionDTO dto = new EventPropertyDefinitionDTO();
        dto.propertyName = "tags";
        dto.type = EventPropertyDefinitionEntity.PropertyType.ARRAY;

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> service.createPropertyDefinition("evd_7", dto));
        assertTrue(ex.getMessage().contains("arrayElementType"));
    }

    @Test
    @DisplayName("数组属性：声明合法元素类型通过校验（null/isBlank 均不命中的 false 侧）")
    void arrayPropertyWithElementTypeAccepted() {
        stubDraftContext("evd_7b", "tp_1");
        when(propertyDefinitionRepo.findByEventDefinitionIdAndPropertyName("evd_7b", "tags")).thenReturn(Optional.empty());
        when(propertyDefinitionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        EventPropertyDefinitionDTO dto = new EventPropertyDefinitionDTO();
        dto.propertyName = "tags";
        dto.type = EventPropertyDefinitionEntity.PropertyType.ARRAY;
        dto.arrayElementType = "string";   // 非空合法元素类型 → 校验通过

        EventPropertyDefinitionDTO created = service.createPropertyDefinition("evd_7b", dto);
        assertEquals("string", created.arrayElementType);
    }

    @Test
    @DisplayName("属性更新：draft 计划内可收紧 cardinality 上限")
    void propertyUpdateChangesCardinality() {
        stubDraftContext("evd_8", "tp_1");
        EventPropertyDefinitionEntity existing = new EventPropertyDefinitionEntity();
        existing.id = "epd_8";
        existing.eventDefinitionId = "evd_8";
        existing.propertyName = "level_name";
        existing.type = EventPropertyDefinitionEntity.PropertyType.STRING;
        existing.cardinalityLimit = 5000;
        when(propertyDefinitionRepo.findById("epd_8")).thenReturn(Optional.of(existing));
        when(propertyDefinitionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        EventPropertyDefinitionDTO dto = new EventPropertyDefinitionDTO();
        dto.cardinalityLimit = 1000;

        EventPropertyDefinitionDTO updated = service.updatePropertyDefinition("evd_8", "epd_8", dto);
        assertEquals(1000, updated.cardinalityLimit);
        verify(auditLog).logUpdate(eq("event_property"), eq("epd_8"), eq("level_name"),
            eq("api"), eq("api"), isNull(), anyMap());
    }

    @Test
    @DisplayName("属性更新：ENUM 补充校验同样生效")
    void propertyUpdateToEnumValidated() {
        stubDraftContext("evd_9", "tp_1");
        EventPropertyDefinitionEntity existing = new EventPropertyDefinitionEntity();
        existing.id = "epd_9";
        existing.eventDefinitionId = "evd_9";
        existing.propertyName = "result";
        existing.type = EventPropertyDefinitionEntity.PropertyType.STRING;
        when(propertyDefinitionRepo.findById("epd_9")).thenReturn(Optional.of(existing));
        when(propertyDefinitionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        EventPropertyDefinitionDTO dto = new EventPropertyDefinitionDTO();
        dto.type = EventPropertyDefinitionEntity.PropertyType.ENUM;
        dto.allowedValues = "[\"ok\"]";
        dto.cardinalityLimit = 5; // > 1，合法

        EventPropertyDefinitionDTO updated = service.updatePropertyDefinition("evd_9", "epd_9", dto);
        assertEquals(EventPropertyDefinitionEntity.PropertyType.ENUM, updated.type);
    }

    @Test
    @DisplayName("属性删除：软删除并记录审计")
    void propertyDeleteSoftDeletes() {
        stubDraftContext("evd_10", "tp_1");
        EventPropertyDefinitionEntity existing = new EventPropertyDefinitionEntity();
        existing.id = "epd_10";
        existing.eventDefinitionId = "evd_10";
        existing.propertyName = "legacy";
        when(propertyDefinitionRepo.findById("epd_10")).thenReturn(Optional.of(existing));
        when(propertyDefinitionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.deletePropertyDefinition("evd_10", "epd_10");

        assertNotNull(existing.deletedAt);
        verify(auditLog).logDelete("event_property", "epd_10", "legacy", "api", "api", null);
    }

    // ===== 分支对侧补充（BRANCH 收口）=====

    @Test
    @DisplayName("属性更新/删除：软删属性或跨事件属性视为不存在（filter 两侧）")
    void propertyUpdateDeleteFilterSides() {
        stubDraftContext("evd_f1", "tp_1");

        // deletedAt != null：filter 第一条件 false
        EventPropertyDefinitionEntity deleted = new EventPropertyDefinitionEntity();
        deleted.id = "epd_del";
        deleted.eventDefinitionId = "evd_f1";
        deleted.propertyName = "old";
        deleted.deletedAt = java.time.LocalDateTime.now();
        when(propertyDefinitionRepo.findById("epd_del")).thenReturn(Optional.of(deleted));
        assertThrows(IllegalArgumentException.class,
            () -> service.updatePropertyDefinition("evd_f1", "epd_del", new EventPropertyDefinitionDTO()));

        // eventDefinitionId 不匹配：第二条件 false
        EventPropertyDefinitionEntity foreign = new EventPropertyDefinitionEntity();
        foreign.id = "epd_other";
        foreign.eventDefinitionId = "evd_other";
        foreign.propertyName = "foreign";
        when(propertyDefinitionRepo.findById("epd_other")).thenReturn(Optional.of(foreign));
        assertThrows(IllegalArgumentException.class,
            () -> service.updatePropertyDefinition("evd_f1", "epd_other", new EventPropertyDefinitionDTO()));
        assertThrows(IllegalArgumentException.class,
            () -> service.deletePropertyDefinition("evd_f1", "epd_other"));

        // 软删属性同样不可删
        assertThrows(IllegalArgumentException.class,
            () -> service.deletePropertyDefinition("evd_f1", "epd_del"));
        verify(propertyDefinitionRepo, never()).save(any());
    }

    @Test
    @DisplayName("规格校验对侧：ARRAY 空白元素类型拒绝；ENUM 无 cardinality 上限通过")
    void validationSidesForArrayBlankAndEnumNoCardinality() {
        stubDraftContext("evd_f2", "tp_1");
        when(propertyDefinitionRepo.findByEventDefinitionIdAndPropertyName("evd_f2", "tags"))
            .thenReturn(Optional.empty());

        // arrayElementType 空白串（isBlank 侧）
        EventPropertyDefinitionDTO blankArr = new EventPropertyDefinitionDTO();
        blankArr.propertyName = "tags";
        blankArr.type = EventPropertyDefinitionEntity.PropertyType.ARRAY;
        blankArr.arrayElementType = "   ";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> service.createPropertyDefinition("evd_f2", blankArr));
        assertTrue(ex.getMessage().contains("arrayElementType"));

        // ENUM + 合法 allowedValues + cardinalityLimit 缺省（null 侧）→ 通过
        when(propertyDefinitionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        EventPropertyDefinitionDTO en = new EventPropertyDefinitionDTO();
        en.propertyName = "result";
        en.type = EventPropertyDefinitionEntity.PropertyType.ENUM;
        en.allowedValues = "[\"win\",\"lose\"]";
        en.arrayElementType = null;  // 非 ARRAY：type 判定 false 侧
        EventPropertyDefinitionDTO created = service.createPropertyDefinition("evd_f2", en);
        assertNull(created.cardinalityLimit);
    }

    @Test
    @DisplayName("环境校验：软删环境与跨游戏环境拒绝（requireEnvironment filter 两侧）")
    void createTrackingPlanEnvironmentSides() {
        GameEntity game = new GameEntity();
        game.id = "game_1";
        when(gameRepo.findById("game_1")).thenReturn(Optional.of(game));
        when(trackingPlanRepo.findByGameIdAndDeletedAtIsNullOrderByCreatedAtDesc("game_1"))
            .thenReturn(java.util.Collections.emptyList());
        when(trackingPlanRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // 环境属于其他游戏：gameId.equals false 侧
        TrackingPlanDTO foreign = new TrackingPlanDTO();
        foreign.name = "plan-foreign";
        foreign.environmentId = "env_other";
        GameEnvironmentEntity otherEnv = new GameEnvironmentEntity();
        otherEnv.id = "env_other";
        otherEnv.gameId = "game_other";
        when(environmentRepo.findById("env_other")).thenReturn(Optional.of(otherEnv));
        assertThrows(IllegalArgumentException.class, () -> service.createTrackingPlan("game_1", foreign));

        // 环境已软删：deletedAt 侧
        TrackingPlanDTO deletedEnvPlan = new TrackingPlanDTO();
        deletedEnvPlan.name = "plan-del";
        deletedEnvPlan.environmentId = "env_del";
        GameEnvironmentEntity deletedEnv = new GameEnvironmentEntity();
        deletedEnv.id = "env_del";
        deletedEnv.gameId = "game_1";
        deletedEnv.deletedAt = java.time.LocalDateTime.now();
        when(environmentRepo.findById("env_del")).thenReturn(Optional.of(deletedEnv));
        assertThrows(IllegalArgumentException.class, () -> service.createTrackingPlan("game_1", deletedEnvPlan));

        // 不指定环境：跳过 requireEnvironment
        TrackingPlanDTO noEnv = new TrackingPlanDTO();
        noEnv.name = "plan-open";
        assertNotNull(service.createTrackingPlan("game_1", noEnv).id);
    }

    @Test
    @DisplayName("软删游戏与软删事件定义拒绝（requireGame/requireEventDefinition filter 侧）")
    void deletedGameAndEventDefinitionRejected() {
        GameEntity game = new GameEntity();
        game.id = "game_1";
        game.deletedAt = java.time.LocalDateTime.now();
        when(gameRepo.findById("game_1")).thenReturn(Optional.of(game));
        assertThrows(IllegalArgumentException.class, () -> service.listTrackingPlans("game_1"));

        EventDefinitionEntity def = eventDef("evd_del", "tp_1");
        def.deletedAt = java.time.LocalDateTime.now();
        when(eventDefinitionRepo.findById("evd_del")).thenReturn(Optional.of(def));
        assertThrows(IllegalArgumentException.class,
            () -> service.createPropertyDefinition("evd_del", new EventPropertyDefinitionDTO()));
    }

    @Test
    @DisplayName("enrichWithGameInfo：gameId/environmentId 缺省跳过查找（null 侧）")
    void enrichSkipsWhenIdsAbsent() {
        // gameId 与 environmentId 均为 null 的计划：两个 if 均走 false 侧，不查 repo
        TrackingPlanEntity bare = new TrackingPlanEntity();
        bare.id = "tp_bare";
        bare.name = "bare";
        when(trackingPlanRepo.findByIdAndDeletedAtIsNull("tp_bare")).thenReturn(Optional.of(bare));
        var dto = service.getTrackingPlan("tp_bare");
        assertTrue(dto.isPresent());
        assertNull(dto.get().gameName);
        assertNull(dto.get().environmentName);

        // 均存在的计划：ifPresent 命中写入名称
        TrackingPlanEntity full = new TrackingPlanEntity();
        full.id = "tp_full";
        full.name = "full";
        full.gameId = "game_1";
        full.environmentId = "env_prod";
        GameEntity g = new GameEntity();
        g.id = "game_1";
        g.name = "Game One";
        GameEnvironmentEntity env = new GameEnvironmentEntity();
        env.id = "env_prod";
        env.name = "prod";
        when(trackingPlanRepo.findByIdAndDeletedAtIsNull("tp_full")).thenReturn(Optional.of(full));
        when(gameRepo.findById("game_1")).thenReturn(Optional.of(g));
        when(environmentRepo.findById("env_prod")).thenReturn(Optional.of(env));
        var dto2 = service.getTrackingPlan("tp_full");
        assertEquals("Game One", dto2.get().gameName);
        assertEquals("prod", dto2.get().environmentName);
    }
}
