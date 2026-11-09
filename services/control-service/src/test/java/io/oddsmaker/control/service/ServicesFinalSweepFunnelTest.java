package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.FunnelConfigEntity;
import io.oddsmaker.control.jpa.FunnelConfigRepo;
import io.oddsmaker.control.jpa.FunnelStepEntity;
import io.oddsmaker.control.jpa.FunnelStepRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Service 层覆盖率收尾测试（LAY-006 拆分）：漏斗创建应用默认值、更新守卫与步骤重建、查询与步骤守卫。与 FunnelConfigServiceTest 等互补。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("漏斗配置 Service 深度测试（默认值/守卫/步骤重建）")
class ServicesFinalSweepFunnelTest {

    @Mock
    private FunnelConfigRepo funnelConfigRepo;

    @Mock
    private FunnelStepRepo funnelStepRepo;

    @Mock
    private AuditLogService auditLog;

    @InjectMocks
    private FunnelConfigService funnelConfigService;

    @Test
    @DisplayName("漏斗：createFunnel 填充默认 type/userKey/enabled 并保留指定 ID")
    void funnelCreateAppliesDefaults() {
        FunnelConfigEntity funnel = new FunnelConfigEntity();
        funnel.id = "funnel_custom";
        funnel.gameId = "game_1";
        funnel.name = "Onboarding";

        lenient().when(funnelConfigRepo.existsByGameIdAndNameAndDeletedAtIsNull("game_1", "Onboarding"))
            .thenReturn(false);
        lenient().when(funnelConfigRepo.save(any(FunnelConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        FunnelConfigEntity created = funnelConfigService.createFunnel(funnel);

        assertEquals("funnel_custom", created.id);
        assertEquals(FunnelConfigEntity.FunnelType.STANDARD, created.type);
        assertEquals("user_id", created.userKey);
        assertTrue(created.enabled);
    }

    @Test
    @DisplayName("漏斗：updateFunnel 重名拒绝、steps 重建、deleteFunnel 缺失抛异常")
    void funnelUpdateGuardsAndStepRebuild() {
        FunnelConfigEntity existing = new FunnelConfigEntity();
        existing.id = "funnel_1";
        existing.gameId = "game_1";
        existing.name = "Old";
        existing.enabled = true;
        lenient().when(funnelConfigRepo.findById("funnel_1")).thenReturn(Optional.of(existing));
        lenient().when(funnelConfigRepo.save(any(FunnelConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        FunnelConfigEntity rename = new FunnelConfigEntity();
        rename.name = "Taken";
        lenient().when(funnelConfigRepo.existsByGameIdAndNameAndDeletedAtIsNull("game_1", "Taken"))
            .thenReturn(true);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> funnelConfigService.updateFunnel("funnel_1", rename));
        assertTrue(ex.getMessage().contains("already exists"));

        FunnelConfigEntity withSteps = new FunnelConfigEntity();
        FunnelStepEntity step = new FunnelStepEntity();
        step.name = "S2";
        step.eventName = "level_complete";
        withSteps.steps = List.of(step);
        FunnelConfigEntity updated = funnelConfigService.updateFunnel("funnel_1", withSteps);
        assertEquals(1, updated.steps.size());
        verify(funnelStepRepo).deleteByFunnelId("funnel_1");
        verify(funnelStepRepo).save(step);
        assertSame(existing, step.funnel);

        lenient().when(funnelConfigRepo.findById("funnel_missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
            () -> funnelConfigService.deleteFunnel("funnel_missing"));
    }

    @Test
    @DisplayName("漏斗：类型过滤/计数查询与 addStep·updateStep·deleteStep 缺失守卫")
    void funnelQueriesAndStepGuards() {
        FunnelConfigEntity funnel = new FunnelConfigEntity();
        funnel.id = "funnel_1";
        funnel.gameId = "game_1";
        funnel.name = "F";
        funnel.enabled = true;
        lenient().when(funnelConfigRepo.findByGameIdAndTypeAndDeletedAtIsNull(
            "game_1", FunnelConfigEntity.FunnelType.STANDARD)).thenReturn(List.of(funnel));
        lenient().when(funnelConfigRepo.countByGameIdAndDeletedAtIsNull("game_1")).thenReturn(5L);
        lenient().when(funnelConfigRepo.countByGameIdAndEnabledTrueAndDeletedAtIsNull("game_1")).thenReturn(2L);

        assertEquals(1, funnelConfigService.findByGameIdAndType(
            "game_1", FunnelConfigEntity.FunnelType.STANDARD).size());
        assertEquals(5L, funnelConfigService.getFunnelCount("game_1"));
        assertEquals(2L, funnelConfigService.getEnabledFunnelCount("game_1"));

        lenient().when(funnelConfigRepo.findById("funnel_missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
            () -> funnelConfigService.addStep("funnel_missing", new FunnelStepEntity()));

        lenient().when(funnelStepRepo.findById("s99")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
            () -> funnelConfigService.updateStep("s99", new FunnelStepEntity()));
        assertThrows(IllegalArgumentException.class, () -> funnelConfigService.deleteStep("s99"));
    }

}
