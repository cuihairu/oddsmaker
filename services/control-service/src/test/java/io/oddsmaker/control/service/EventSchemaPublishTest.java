package io.oddsmaker.control.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.oddsmaker.control.dto.SchemaCompatibilityDTO;
import io.oddsmaker.control.dto.TrackingPlanDTO;
import io.oddsmaker.control.jpa.EventDefinitionEntity;
import io.oddsmaker.control.jpa.EventDefinitionRepo;
import io.oddsmaker.control.jpa.EventPropertyDefinitionRepo;
import io.oddsmaker.control.jpa.GameRepo;
import io.oddsmaker.control.jpa.GameEnvironmentRepo;
import io.oddsmaker.control.jpa.TrackingPlanEntity;
import io.oddsmaker.control.jpa.TrackingPlanRepo;
import io.oddsmaker.control.service.AuditLogService;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * B7 EventSchema 版本发布与兼容检查（计划书 §2.3）：publish 兼容门 +
 * compatibilityCheck diff 分类 + 新字段（compatibility/piiPolicy/retentionDays/
 * samplingRate/ownerId）DTO 往返。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EventSchema 版本发布与兼容检查")
class EventSchemaPublishTest {

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

    // ===== 桩件 =====

    private static TrackingPlanEntity plan(String id, TrackingPlanEntity.Compatibility compat) {
        TrackingPlanEntity plan = new TrackingPlanEntity();
        plan.id = id;
        plan.gameId = "game_1";          // 环境未绑：isGlobal，基线匹配 environmentId=null
        plan.name = "schema-" + id;
        plan.status = TrackingPlanEntity.PlanStatus.DRAFT;
        plan.compatibility = compat;
        return plan;
    }

    private static EventDefinitionEntity event(String id, String planId, String name) {
        EventDefinitionEntity def = new EventDefinitionEntity();
        def.id = id;
        def.trackingPlanId = planId;
        def.eventName = name;
        def.eventType = "progression";
        def.status = EventDefinitionEntity.DefinitionStatus.ACTIVE;
        return def;
    }

    private void stubPlan(TrackingPlanEntity draft) {
        when(trackingPlanRepo.findByIdAndDeletedAtIsNull(draft.id)).thenReturn(Optional.of(draft));
        when(trackingPlanRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(eventDefinitionRepo.countByTrackingPlanId(draft.id)).thenReturn(0L);
        when(eventDefinitionRepo.findActiveByTrackingPlanId(draft.id)).thenReturn(List.of());
    }

    private void stubBaseline(TrackingPlanEntity draft, TrackingPlanEntity baseline,
                              List<EventDefinitionEntity> mine, List<EventDefinitionEntity> base) {
        when(trackingPlanRepo.findByIdAndDeletedAtIsNull(draft.id)).thenReturn(Optional.of(draft));
        when(trackingPlanRepo.findActiveByGameId(draft.gameId)).thenReturn(List.of(baseline));
        when(eventDefinitionRepo.findActiveByTrackingPlanId(draft.id)).thenReturn(mine);
        when(eventDefinitionRepo.findActiveByTrackingPlanId(baseline.id)).thenReturn(base);
    }

    /** 兼容检查通过后走到 activate+save+updateEventCounts 所需的成功侧桩 */
    private void stubPublishSuccess(TrackingPlanEntity draft) {
        when(trackingPlanRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(eventDefinitionRepo.countByTrackingPlanId(draft.id)).thenReturn(0L);
    }

    private static TrackingPlanEntity activeBaseline(String id) {
        TrackingPlanEntity plan = plan(id, TrackingPlanEntity.Compatibility.NONE);
        plan.status = TrackingPlanEntity.PlanStatus.ACTIVE;
        plan.activatedAt = java.time.LocalDateTime.now().minusDays(1);
        return plan;
    }

    // ===== publish 兼容门 =====

    @Test
    @DisplayName("publish：NONE 策略跳过兼容检查直接激活")
    void publishNoneSkipsCheckAndActivates() {
        TrackingPlanEntity draft = plan("sch_none", TrackingPlanEntity.Compatibility.NONE);
        stubPlan(draft);

        TrackingPlanDTO out = service.publishTrackingPlan("sch_none", "alice");

        assertEquals(TrackingPlanEntity.PlanStatus.ACTIVE, out.status);
        assertEquals("alice", out.activatedBy);
        assertNotNull(out.activatedAt);
        verify(trackingPlanRepo).save(draft);
        // NONE 不应触发基线事件集读取（兼容检查短路）
        verify(eventDefinitionRepo, never()).findActiveByTrackingPlanId("baseline_x");
    }

    @Test
    @DisplayName("publish：BACKWARD 且 removed 非空 → 拒绝，状态保持 DRAFT，不落库")
    void publishBackwardWithRemovedRejected() {
        TrackingPlanEntity draft = plan("sch_bwd", TrackingPlanEntity.Compatibility.BACKWARD);
        TrackingPlanEntity baseline = activeBaseline("sch_base");
        stubBaseline(draft, baseline,
                List.of(event("e1", "sch_bwd", "bet_place")),
                List.of(event("b1", "sch_base", "bet_place"),
                        event("b2", "sch_base", "bet_settle")));

        assertThrows(IllegalArgumentException.class,
                () -> service.publishTrackingPlan("sch_bwd", "alice"));

        assertEquals(TrackingPlanEntity.PlanStatus.DRAFT, draft.status);
        verify(trackingPlanRepo, never()).save(any());
    }

    @Test
    @DisplayName("publish：BACKWARD 仅新增事件 → 通过并激活")
    void publishBackwardAddOnlyPublishes() {
        TrackingPlanEntity draft = plan("sch_bwd2", TrackingPlanEntity.Compatibility.BACKWARD);
        TrackingPlanEntity baseline = activeBaseline("sch_base");
        stubBaseline(draft, baseline,
                List.of(event("e1", "sch_bwd2", "bet_place"),
                        event("e2", "sch_bwd2", "bet_cancel")),
                List.of(event("b1", "sch_base", "bet_place")));
        stubPublishSuccess(draft);

        TrackingPlanDTO out = service.publishTrackingPlan("sch_bwd2", "bob");

        assertEquals(TrackingPlanEntity.PlanStatus.ACTIVE, out.status);
        verify(trackingPlanRepo).save(draft);
    }

    @Test
    @DisplayName("publish：FORWARD 且 added 非空 → 拒绝（旧消费方不识别新增）")
    void publishForwardWithAddedRejected() {
        TrackingPlanEntity draft = plan("sch_fwd", TrackingPlanEntity.Compatibility.FORWARD);
        TrackingPlanEntity baseline = activeBaseline("sch_base");
        stubBaseline(draft, baseline,
                List.of(event("e1", "sch_fwd", "bet_place"),
                        event("e2", "sch_fwd", "bet_new")),
                List.of(event("b1", "sch_base", "bet_place")));

        assertThrows(IllegalArgumentException.class,
                () -> service.publishTrackingPlan("sch_fwd", "alice"));
        assertEquals(TrackingPlanEntity.PlanStatus.DRAFT, draft.status);
    }

    @Test
    @DisplayName("publish：FULL 双向都检查——仅新增也拒绝")
    void publishFullEnforcesBothDirections() {
        TrackingPlanEntity draft = plan("sch_full", TrackingPlanEntity.Compatibility.FULL);
        TrackingPlanEntity baseline = activeBaseline("sch_base");
        stubBaseline(draft, baseline,
                List.of(event("e1", "sch_full", "bet_place"),
                        event("e2", "sch_full", "bet_new")),
                List.of(event("b1", "sch_base", "bet_place")));

        assertThrows(IllegalArgumentException.class,
                () -> service.publishTrackingPlan("sch_full", "alice"));
        assertEquals(TrackingPlanEntity.PlanStatus.DRAFT, draft.status);

        // 同事件集（无增无删）→ FULL 通过
        TrackingPlanEntity draft2 = plan("sch_full2", TrackingPlanEntity.Compatibility.FULL);
        TrackingPlanEntity baseline2 = activeBaseline("sch_base2");
        stubBaseline(draft2, baseline2,
                List.of(event("f1", "sch_full2", "bet_place")),
                List.of(event("g1", "sch_base2", "bet_place")));
        stubPublishSuccess(draft2);
        TrackingPlanDTO out = service.publishTrackingPlan("sch_full2", "alice");
        assertEquals(TrackingPlanEntity.PlanStatus.ACTIVE, out.status);
    }

    @Test
    @DisplayName("publish：同名事件签名变化（类型/必填/重要性）不阻塞 BACKWARD 发布，但记入 changedEvents")
    void changedEventsInfoOnlyForBackward() {
        TrackingPlanEntity draft = plan("sch_chg", TrackingPlanEntity.Compatibility.BACKWARD);
        TrackingPlanEntity baseline = activeBaseline("sch_base");
        EventDefinitionEntity mineBet = event("e1", "sch_chg", "bet_place");
        mineBet.importance = EventDefinitionEntity.Importance.CRITICAL;   // 重要性变了
        EventDefinitionEntity baseBet = event("b1", "sch_base", "bet_place");
        baseBet.importance = EventDefinitionEntity.Importance.NORMAL;
        stubBaseline(draft, baseline, List.of(mineBet), List.of(baseBet));
        stubPublishSuccess(draft);

        // changed 不算 BACKWARD 违例 → 发布通过
        TrackingPlanDTO out = service.publishTrackingPlan("sch_chg", "carol");
        assertEquals(TrackingPlanEntity.PlanStatus.ACTIVE, out.status);

        // 只读检查端点能看到 changedEvents 分类
        TrackingPlanEntity draft3 = plan("sch_chg3", TrackingPlanEntity.Compatibility.BACKWARD);
        TrackingPlanEntity baseline3 = activeBaseline("sch_base3");
        stubBaseline(draft3, baseline3, List.of(mineBet), List.of(baseBet));
        SchemaCompatibilityDTO check = service.compatibilityCheck("sch_chg3");
        assertEquals(List.of("bet_place"), check.changedEvents);
        assertTrue(check.addedEvents.isEmpty());
        assertTrue(check.removedEvents.isEmpty());
        assertTrue(check.compatible);
    }

    // ===== compatibilityCheck =====

    @Test
    @DisplayName("compatibilityCheck：无基线 → 恒兼容且 baselineId=null")
    void checkWithoutBaselineCompatible() {
        TrackingPlanEntity draft = plan("sch_nobase", TrackingPlanEntity.Compatibility.FULL);
        when(trackingPlanRepo.findByIdAndDeletedAtIsNull("sch_nobase")).thenReturn(Optional.of(draft));
        when(trackingPlanRepo.findActiveByGameId("game_1")).thenReturn(List.of());

        SchemaCompatibilityDTO out = service.compatibilityCheck("sch_nobase");

        assertTrue(out.compatible);
        assertNull(out.baselineId);
        assertEquals(TrackingPlanEntity.Compatibility.FULL, out.mode);
    }

    @Test
    @DisplayName("compatibilityCheck：diff 三分类（added/removed/changed）与策略判定")
    void checkClassifiesDiffAndVerdict() {
        TrackingPlanEntity draft = plan("sch_diff", TrackingPlanEntity.Compatibility.FULL);
        TrackingPlanEntity baseline = activeBaseline("sch_base");
        EventDefinitionEntity kept = event("e1", "sch_diff", "bet_place");
        EventDefinitionEntity renamed = event("e2", "sch_diff", "bet_new");          // added
        EventDefinitionEntity typed = event("e3", "sch_diff", "bet_settle");
        typed.eventType = "transaction";                                             // changed
        EventDefinitionEntity baseKept = event("b1", "sch_base", "bet_place");
        EventDefinitionEntity baseTyped = event("b2", "sch_base", "bet_settle");
        EventDefinitionEntity dropped = event("b3", "sch_base", "bet_legacy");       // removed
        stubBaseline(draft, baseline, List.of(kept, renamed, typed),
                List.of(baseKept, baseTyped, dropped));

        SchemaCompatibilityDTO out = service.compatibilityCheck("sch_diff");

        assertEquals("sch_base", out.baselineId);
        assertEquals(List.of("bet_new"), out.addedEvents);
        assertEquals(List.of("bet_legacy"), out.removedEvents);
        assertEquals(List.of("bet_settle"), out.changedEvents);
        assertFalse(out.compatible);   // FULL：双向均违例
    }

    @Test
    @DisplayName("compatibilityCheck：基线取同 game+env 最近 activatedAt，排除自身与他环境")
    void checkBaselineSelection() {
        TrackingPlanEntity draft = plan("sch_sel", TrackingPlanEntity.Compatibility.BACKWARD);
        draft.activatedAt = java.time.LocalDateTime.now();   // 同 id 的 ACTIVE 自身：仅靠 id 排除
        TrackingPlanEntity oldBase = activeBaseline("sch_old");
        oldBase.activatedAt = java.time.LocalDateTime.now().minusDays(7);
        TrackingPlanEntity recentBase = activeBaseline("sch_recent");
        TrackingPlanEntity otherEnv = activeBaseline("sch_env");
        otherEnv.environmentId = "env_prod";    // 环境不同：不算基线
        when(trackingPlanRepo.findByIdAndDeletedAtIsNull("sch_sel")).thenReturn(Optional.of(draft));
        when(trackingPlanRepo.findActiveByGameId("game_1"))
                .thenReturn(List.of(oldBase, draft, otherEnv, recentBase));
        when(eventDefinitionRepo.findActiveByTrackingPlanId("sch_sel")).thenReturn(List.of());
        when(eventDefinitionRepo.findActiveByTrackingPlanId("sch_recent")).thenReturn(List.of());

        SchemaCompatibilityDTO out = service.compatibilityCheck("sch_sel");

        assertEquals("sch_recent", out.baselineId);
        assertTrue(out.compatible);
    }

    // ===== 新字段 DTO 往返与默认值 =====

    @Test
    @DisplayName("DTO 往返：5 个 B7 字段 entity↔dto 映射 + updateEntity null 不覆盖")
    void dtoRoundTripAndNullSafeUpdate() {
        TrackingPlanEntity entity = plan("sch_rt", TrackingPlanEntity.Compatibility.BACKWARD);
        entity.piiPolicy = "{\"email\":\"hash\"}";
        entity.retentionDays = 90;
        entity.samplingRate = new java.math.BigDecimal("0.50");
        entity.ownerId = "team-data";

        TrackingPlanDTO dto = new TrackingPlanDTO(entity);
        assertEquals(TrackingPlanEntity.Compatibility.BACKWARD, dto.compatibility);
        assertEquals("{\"email\":\"hash\"}", dto.piiPolicy);
        assertEquals(90, dto.retentionDays);
        assertEquals(new java.math.BigDecimal("0.50"), dto.samplingRate);
        assertEquals("team-data", dto.ownerId);

        // null 字段不覆盖既有值（updateEntity 逐字段 null 守卫）
        TrackingPlanEntity target = plan("sch_rt2", TrackingPlanEntity.Compatibility.NONE);
        TrackingPlanDTO partial = new TrackingPlanDTO();
        partial.retentionDays = 30;
        partial.updateEntity(target);
        assertEquals(30, target.retentionDays);
        assertEquals(TrackingPlanEntity.Compatibility.NONE, target.compatibility);
        assertNull(target.piiPolicy);
        assertNull(target.ownerId);

        // toEntity 缺省 compatibility → NONE
        TrackingPlanDTO blank = new TrackingPlanDTO();
        blank.name = "schema-blank";
        assertEquals(TrackingPlanEntity.Compatibility.NONE, blank.toEntity().compatibility);
    }
}
