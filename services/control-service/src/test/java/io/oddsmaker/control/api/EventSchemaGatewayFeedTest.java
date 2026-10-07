package io.oddsmaker.control.api;

import io.oddsmaker.control.jpa.ApiKeyEntity;
import io.oddsmaker.control.jpa.ApiKeyRepo;
import io.oddsmaker.control.jpa.EventDefinitionEntity;
import io.oddsmaker.control.jpa.EventDefinitionRepo;
import io.oddsmaker.control.jpa.GameEnvironmentEntity;
import io.oddsmaker.control.jpa.GameEnvironmentRepo;
import io.oddsmaker.control.jpa.GameEntity;
import io.oddsmaker.control.jpa.GameRepo;
import io.oddsmaker.control.jpa.StorageProfileRepo;
import io.oddsmaker.control.jpa.TrackingPlanEntity;
import io.oddsmaker.control.jpa.TrackingPlanRepo;
import io.oddsmaker.control.service.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * B7 §4.4 Schema 事件面下发：/internal/api-keys feed 携带 rejectUnknownEvents + eventNames——
 * 环境绑定 ACTIVE Schema 优先、回退全局版、无 Schema 不下发（Gateway 侧 null=不启用）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EventSchema 网关事件面下发")
class EventSchemaGatewayFeedTest {

    @Mock
    private ApiKeyRepo keyRepo;

    @Mock
    private GameRepo gameRepo;

    @Mock
    private GameEnvironmentRepo envRepo;

    @Mock
    private StorageProfileRepo storageProfileRepo;

    @Mock
    private TrackingPlanRepo trackingPlanRepo;

    @Mock
    private EventDefinitionRepo eventDefinitionRepo;

    @Mock
    private AuditLogService auditLog;

    @InjectMocks
    private ControlService service;

    private final ApiKeyEntity key = new ApiKeyEntity();
    private final GameEnvironmentEntity env = new GameEnvironmentEntity();

    @BeforeEach
    void setUp() {
        key.apiKey = "ak_feed";
        key.gameId = "g";
        key.environmentId = "env_prod";
        key.status = ApiKeyEntity.ApiKeyStatus.ACTIVE;
        env.id = "env_prod";
        env.gameId = "g";
        env.name = "prod";
        lenient().when(keyRepo.findById("ak_feed")).thenReturn(Optional.of(key));
        lenient().when(envRepo.findById("env_prod")).thenReturn(Optional.of(env));
    }

    private static TrackingPlanEntity activePlan(String id, String environmentId,
                                                 boolean rejectUnknown, LocalDateTime activatedAt) {
        TrackingPlanEntity plan = new TrackingPlanEntity();
        plan.id = id;
        plan.gameId = "g";
        plan.name = id;
        plan.status = TrackingPlanEntity.PlanStatus.ACTIVE;
        plan.environmentId = environmentId;
        plan.rejectUnknownEvents = rejectUnknown;
        plan.activatedAt = activatedAt;
        return plan;
    }

    private static EventDefinitionEntity def(String id, String planId, String eventName) {
        EventDefinitionEntity d = new EventDefinitionEntity();
        d.id = id;
        d.trackingPlanId = planId;
        d.eventName = eventName;
        d.status = EventDefinitionEntity.DefinitionStatus.ACTIVE;
        return d;
    }

    @Test
    @DisplayName("环境绑定 Schema 优先于全局：开关与事件清单取环境级")
    void envBoundPlanPreferred() {
        TrackingPlanEntity envPlan = activePlan("sch_env", "env_prod", true,
                LocalDateTime.now().minusHours(1));
        TrackingPlanEntity globalPlan = activePlan("sch_global", null, false,
                LocalDateTime.now().minusDays(1));
        when(trackingPlanRepo.findActiveByGameId("g")).thenReturn(List.of(envPlan, globalPlan));
        when(eventDefinitionRepo.findActiveByTrackingPlanId("sch_env"))
            .thenReturn(List.of(def("e1", "sch_env", "bet_settle"),
                    def("e2", "sch_env", "bet_place"),
                    def("e3", "sch_env", "bet_place")));   // 重名去重

        Models.InternalApiKeyResp resp = service.getActiveKeyForGateway("ak_feed");

        assertEquals(Boolean.TRUE, resp.rejectUnknownEvents);
        // 事件名去重 + 字典序
        assertEquals(List.of("bet_place", "bet_settle"), resp.eventNames);
    }

    @Test
    @DisplayName("无环境绑定版时回退全局版")
    void globalFallbackWhenNoEnvBound() {
        TrackingPlanEntity globalPlan = activePlan("sch_global", null, false,
                LocalDateTime.now().minusDays(1));
        when(trackingPlanRepo.findActiveByGameId("g")).thenReturn(List.of(globalPlan));
        when(eventDefinitionRepo.findActiveByTrackingPlanId("sch_global"))
            .thenReturn(List.of(def("e1", "sch_global", "level_start")));

        Models.InternalApiKeyResp resp = service.getActiveKeyForGateway("ak_feed");

        assertEquals(Boolean.FALSE, resp.rejectUnknownEvents);
        assertEquals(List.of("level_start"), resp.eventNames);
    }

    @Test
    @DisplayName("无 Schema 不下发：两字段保持 null（网关侧 null=不启用）")
    void noPlanFieldsNull() {
        when(trackingPlanRepo.findActiveByGameId("g")).thenReturn(List.of());

        Models.InternalApiKeyResp resp = service.getActiveKeyForGateway("ak_feed");

        assertNull(resp.rejectUnknownEvents);
        assertNull(resp.eventNames);
    }

    @Test
    @DisplayName("他环境绑定版不算本 key 的 Schema（environmentId 不匹配走全局回退）")
    void otherEnvironmentIgnored() {
        TrackingPlanEntity otherEnvPlan = activePlan("sch_other", "env_qa", true,
                LocalDateTime.now().minusHours(1));
        when(trackingPlanRepo.findActiveByGameId("g")).thenReturn(List.of(otherEnvPlan));

        Models.InternalApiKeyResp resp = service.getActiveKeyForGateway("ak_feed");

        assertNull(resp.rejectUnknownEvents);
        assertNull(resp.eventNames);
    }

    @Test
    @DisplayName("未激活（activatedAt=null）的行被过滤，不参与 Schema 选择")
    void nullActivatedAtFiltered() {
        TrackingPlanEntity stale = activePlan("sch_stale", "env_prod", true, null);
        when(trackingPlanRepo.findActiveByGameId("g")).thenReturn(List.of(stale));

        Models.InternalApiKeyResp resp = service.getActiveKeyForGateway("ak_feed");

        assertNull(resp.rejectUnknownEvents);
        assertTrue(resp.eventNames == null);
    }
}
