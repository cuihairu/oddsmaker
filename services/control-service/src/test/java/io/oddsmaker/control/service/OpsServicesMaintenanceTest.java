package io.oddsmaker.control.service;

import io.oddsmaker.control.experiment.ExperimentSplitter;
import io.oddsmaker.control.jpa.FeatureFlagEntity;
import io.oddsmaker.control.jpa.FeatureFlagRepo;
import io.oddsmaker.control.jpa.MaintenanceWindowEntity;
import io.oddsmaker.control.jpa.MaintenanceWindowRepo;
import io.oddsmaker.control.jpa.SystemConfigEntity;
import io.oddsmaker.control.jpa.SystemConfigRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 运营域 Service 深度单元测试（LAY-012 拆分）：维护窗口生命周期、配置更新/开关启用禁用/百分比/命中判断/系统状态、功能开关灰度分桶 check 与 advanceRollout 步进、定时任务、维护窗口 webhook 派发异常吞掉。与 MaintenanceServiceTest 互补。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("运营域 Service 深度测试")
class OpsServicesMaintenanceTest {

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private MaintenanceWindowRepo maintenanceWindowRepo;

    @Mock
    private SystemConfigRepo systemConfigRepo;

    @Mock
    private FeatureFlagRepo featureFlagRepo;

    @Mock
    private WebhookService webhookService;

    @InjectMocks
    private MaintenanceService maintenanceService;

    private MaintenanceWindowEntity window(MaintenanceWindowEntity.MaintenanceStatus status) {
        MaintenanceWindowEntity w = new MaintenanceWindowEntity();
        w.id = "mw1";
        w.title = "升级";
        w.maintenanceStatus = status;
        w.scheduledStart = LocalDateTime.now().minusHours(1);
        w.scheduledEnd = LocalDateTime.now().plusHours(1);
        return w;
    }

    @Test
    @DisplayName("维护窗口：创建（普通/紧急）/开始/完成/取消与状态校验")
    void maintenanceWindowLifecycle() {
        lenient().when(maintenanceWindowRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        MaintenanceWindowEntity pending = window(MaintenanceWindowEntity.MaintenanceStatus.PENDING);
        MaintenanceWindowEntity inProgress = window(MaintenanceWindowEntity.MaintenanceStatus.IN_PROGRESS);
        MaintenanceWindowEntity scheduled = window(MaintenanceWindowEntity.MaintenanceStatus.SCHEDULED);
        lenient().when(maintenanceWindowRepo.findById("w-pending")).thenReturn(Optional.of(pending));
        lenient().when(maintenanceWindowRepo.findById("w-progress")).thenReturn(Optional.of(inProgress));
        lenient().when(maintenanceWindowRepo.findById("w-scheduled")).thenReturn(Optional.of(scheduled));
        lenient().when(maintenanceWindowRepo.findById("none")).thenReturn(Optional.empty());

        MaintenanceWindowEntity normal = maintenanceService.createMaintenanceWindow(
            "例行维护", "desc", MaintenanceWindowEntity.MaintenanceType.PATCHING,
            MaintenanceWindowEntity.ImpactScope.GAME, "g", "env",
            LocalDateTime.now(), LocalDateTime.now().plusHours(2), "ops");
        assertEquals(MaintenanceWindowEntity.MaintenanceStatus.SCHEDULED, normal.maintenanceStatus);

        MaintenanceWindowEntity emergency = maintenanceService.createMaintenanceWindow(
            "紧急修复", null, MaintenanceWindowEntity.MaintenanceType.EMERGENCY,
            MaintenanceWindowEntity.ImpactScope.GLOBAL, null, null,
            LocalDateTime.now(), LocalDateTime.now().plusHours(1), "ops");
        assertEquals(MaintenanceWindowEntity.MaintenanceStatus.PENDING, emergency.maintenanceStatus);

        assertThrows(IllegalArgumentException.class, () -> maintenanceService.startMaintenance("none"));
        assertThrows(IllegalStateException.class, () -> maintenanceService.startMaintenance("w-scheduled"));
        MaintenanceWindowEntity started = maintenanceService.startMaintenance("w-pending");
        assertEquals(MaintenanceWindowEntity.MaintenanceStatus.IN_PROGRESS, started.maintenanceStatus);
        assertNotNull(started.actualStart);

        assertThrows(IllegalStateException.class, () -> maintenanceService.completeMaintenance("w-scheduled", null));
        MaintenanceWindowEntity finished = maintenanceService.completeMaintenance("w-progress", "done notes");
        assertEquals(MaintenanceWindowEntity.MaintenanceStatus.COMPLETED, finished.maintenanceStatus);
        assertEquals("done notes", finished.completionNotes);
        assertEquals(Integer.valueOf(100), finished.progressPercent);

        assertThrows(IllegalArgumentException.class, () -> maintenanceService.cancelMaintenance("none", "r"));
        MaintenanceWindowEntity cancelled = maintenanceService.cancelMaintenance("w-scheduled", "计划变更");
        assertEquals(MaintenanceWindowEntity.MaintenanceStatus.CANCELLED, cancelled.maintenanceStatus);
    }

    @Test
    @DisplayName("维护：配置更新（含只读）/开关启用禁用/百分比/命中判断/系统状态")
    void maintenanceConfigAndFeatureFlags() {
        lenient().when(systemConfigRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(featureFlagRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SystemConfigEntity editable = new SystemConfigEntity();
        editable.id = "sc1";
        editable.configKey = "max_export_rows";
        editable.configValue = "1000";
        SystemConfigEntity readonly = new SystemConfigEntity();
        readonly.id = "sc2";
        readonly.configKey = "readonly_key";
        readonly.isReadonly = true;
        lenient().when(systemConfigRepo.findByKey("max_export_rows")).thenReturn(Optional.of(editable));
        lenient().when(systemConfigRepo.findByKey("readonly_key")).thenReturn(Optional.of(readonly));
        lenient().when(systemConfigRepo.findByKey("none")).thenReturn(Optional.empty());

        SystemConfigEntity updated = maintenanceService.setConfigValue("max_export_rows", "2000", "ops");
        assertEquals("2000", updated.getRawValue());
        assertEquals(Integer.valueOf(2), updated.version);
        assertEquals("ops", updated.lastModifiedBy);
        assertThrows(IllegalArgumentException.class, () -> maintenanceService.setConfigValue("none", "v", "ops"));
        assertThrows(IllegalStateException.class, () -> maintenanceService.setConfigValue("readonly_key", "v", "ops"));

        FeatureFlagEntity flag = new FeatureFlagEntity();
        flag.id = "ff1";
        flag.flagKey = "new_ui";
        flag.flagStatus = FeatureFlagEntity.FlagStatus.DISABLED;
        FeatureFlagEntity expired = new FeatureFlagEntity();
        expired.id = "ff2";
        expired.flagKey = "tmp_feature";
        expired.flagStatus = FeatureFlagEntity.FlagStatus.ENABLED;
        expired.expiryDate = LocalDateTime.now().minusDays(1);
        FeatureFlagEntity ok = new FeatureFlagEntity();
        ok.id = "ff3";
        ok.flagKey = "stable_feature";
        ok.flagStatus = FeatureFlagEntity.FlagStatus.ENABLED;
        lenient().when(featureFlagRepo.findByKey("new_ui")).thenReturn(Optional.of(flag));
        lenient().when(featureFlagRepo.findByKey("tmp_feature")).thenReturn(Optional.of(expired));
        lenient().when(featureFlagRepo.findByKey("stable_feature")).thenReturn(Optional.of(ok));
        lenient().when(featureFlagRepo.findByKey("none")).thenReturn(Optional.empty());

        assertEquals(FeatureFlagEntity.FlagStatus.ENABLED, maintenanceService.enableFeature("new_ui", "ops").flagStatus);
        FeatureFlagEntity pct = maintenanceService.setFeaturePercentage("new_ui", 150, "ops");
        assertEquals(Integer.valueOf(100), pct.percentageValue);
        assertEquals(FeatureFlagEntity.FlagStatus.ENABLED, pct.flagStatus);
        FeatureFlagEntity partial = maintenanceService.setFeaturePercentage("new_ui", 30, "ops");
        assertEquals(FeatureFlagEntity.FlagStatus.STAGED_ROLLOUT, partial.flagStatus);
        FeatureFlagEntity zero = maintenanceService.setFeaturePercentage("new_ui", -5, "ops");
        assertEquals(FeatureFlagEntity.FlagStatus.DISABLED, zero.flagStatus);
        assertEquals(FeatureFlagEntity.FlagStatus.DISABLED, maintenanceService.disableFeature("new_ui", "ops").flagStatus);
        assertThrows(IllegalArgumentException.class, () -> maintenanceService.enableFeature("none", "ops"));
        assertThrows(IllegalArgumentException.class, () -> maintenanceService.disableFeature("none", "ops"));
        assertThrows(IllegalArgumentException.class, () -> maintenanceService.setFeaturePercentage("none", 50, "ops"));

        MaintenanceWindowEntity global = window(MaintenanceWindowEntity.MaintenanceStatus.IN_PROGRESS);
        lenient().when(maintenanceWindowRepo.findActive()).thenReturn(List.of(global));
        assertTrue(maintenanceService.isGameInMaintenance("any"));
        assertTrue(maintenanceService.isFeatureEnabled("stable_feature", null, null));
        assertFalse(maintenanceService.isFeatureEnabled("tmp_feature", null, null));
        assertFalse(maintenanceService.isFeatureEnabled("new_ui", null, null));

        lenient().when(maintenanceWindowRepo.findUpcoming(any())).thenReturn(List.of());
        lenient().when(featureFlagRepo.countByStatus(FeatureFlagEntity.FlagStatus.ENABLED)).thenReturn(2L);
        lenient().when(featureFlagRepo.count()).thenReturn(3L);
        Map<String, Object> status = maintenanceService.getSystemStatus();
        assertEquals(1L, status.get("activeMaintenances"));
        assertEquals(2L, status.get("enabledFeatures"));
        assertEquals(3L, status.get("totalFeatures"));
        assertEquals(Boolean.TRUE, status.get("maintenanceMode"));
    }

    @Test
    @DisplayName("功能开关：check 命中走灰度分桶（FNV-1a 确定性）+ 白名单先行 + userId 空回落")
    void featureFlagCheckBucketing() {
        FeatureFlagEntity flag = new FeatureFlagEntity();
        flag.id = "ff10";
        flag.flagKey = "rollout-flag";
        flag.createdBy = "admin";
        flag.defaultValue = false;
        flag.setPercentage(50); // 进入 STAGED_ROLLOUT，pct=50
        when(featureFlagRepo.findByKey("rollout-flag")).thenReturn(Optional.of(flag));

        // FNV-1a 锚定向量：hash32("rollout-flag:r3")=0x68678acc → bucket=4 < 50 命中；
        // hash32("rollout-flag:r4")=0x67678939 → bucket=85 >= 50 不命中
        assertTrue(maintenanceService.isFeatureEnabled("rollout-flag", "r3", "g1"));
        assertFalse(maintenanceService.isFeatureEnabled("rollout-flag", "r4", "g1"));
        // 重复调用稳定（确定性分桶），且与公式独立复算一致
        assertTrue(maintenanceService.isFeatureEnabled("rollout-flag", "r3", "g1"));
        assertFalse(maintenanceService.isFeatureEnabled("rollout-flag", "r4", "g1"));
        assertEquals(
            Integer.toUnsignedLong(ExperimentSplitter.hash32("rollout-flag:r3")) % 100 < 50,
            maintenanceService.isFeatureEnabled("rollout-flag", "r3", "g1"));

        // pct=100 全量放行 / pct=0 全不放行（回落 defaultValue=false）
        flag.setPercentage(100);
        assertTrue(maintenanceService.isFeatureEnabled("rollout-flag", "r4", "g1"));
        flag.setPercentage(0);
        assertFalse(maintenanceService.isFeatureEnabled("rollout-flag", "r4", "g1"));

        // 白名单先行：5% 灰度下 r4（bucket=85）不命中，白名单透传
        flag.setPercentage(5);
        assertFalse(maintenanceService.isFeatureEnabled("rollout-flag", "r4", "g1"));
        flag.whitelistUsers = "r4";
        assertTrue(maintenanceService.isFeatureEnabled("rollout-flag", "r4", "g1"));

        // userId null → 回落 defaultValue（不参与分桶）
        flag.whitelistUsers = null;
        assertFalse(maintenanceService.isFeatureEnabled("rollout-flag", null, "g1"));
        flag.defaultValue = true;
        assertTrue(maintenanceService.isFeatureEnabled("rollout-flag", null, "g1"));

        // 未知 flag → false
        assertFalse(maintenanceService.isFeatureEnabled("none", "r3", "g1"));
    }

    @Test
    @DisplayName("功能开关：advanceFeatureRollout 步进回写/末步全量/非灰度与无步骤拒绝")
    void featureFlagAdvanceRollout() {
        lenient().when(featureFlagRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FeatureFlagEntity flag = new FeatureFlagEntity();
        flag.id = "ff20";
        flag.flagKey = "gradual";
        flag.createdBy = "admin";
        flag.setPercentage(10); // 进入 STAGED_ROLLOUT，与步骤 [10,50,100] 第 1 步对齐
        flag.rolloutSteps = "[10,50,100]";
        flag.currentStep = 1;
        when(featureFlagRepo.findByKey("gradual")).thenReturn(Optional.of(flag));

        // 步进：第 1 步 → 第 2 步，百分比按步骤回写
        FeatureFlagEntity advanced = maintenanceService.advanceFeatureRollout("gradual", "ops");
        assertEquals(2, advanced.currentStep);
        assertEquals(Integer.valueOf(50), advanced.percentageValue);
        assertEquals(FeatureFlagEntity.FlagStatus.STAGED_ROLLOUT, advanced.flagStatus);
        assertEquals("ops", advanced.lastModifiedBy);

        // 末步：回写 100 自动转 ENABLED
        FeatureFlagEntity done = maintenanceService.advanceFeatureRollout("gradual", "ops");
        assertEquals(3, done.currentStep);
        assertEquals(Integer.valueOf(100), done.percentageValue);
        assertEquals(FeatureFlagEntity.FlagStatus.ENABLED, done.flagStatus);

        // 已非灰度态再推进 → 拒绝
        assertThrows(IllegalArgumentException.class, () -> maintenanceService.advanceFeatureRollout("gradual", "ops"));

        // 无步骤列表 → 拒绝
        FeatureFlagEntity bare = new FeatureFlagEntity();
        bare.id = "ff21";
        bare.flagKey = "bare";
        bare.createdBy = "admin";
        bare.setPercentage(50);
        when(featureFlagRepo.findByKey("bare")).thenReturn(Optional.of(bare));
        assertThrows(IllegalArgumentException.class, () -> maintenanceService.advanceFeatureRollout("bare", "ops"));

        // 不存在 → 拒绝
        assertThrows(IllegalArgumentException.class, () -> maintenanceService.advanceFeatureRollout("none", "ops"));
    }

    @Test
    @DisplayName("维护：定时任务（待开始/应结束/开关自动启用禁用过期）")
    void maintenanceScheduledTasks() {
        lenient().when(maintenanceWindowRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(featureFlagRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        MaintenanceWindowEntity toStart = window(MaintenanceWindowEntity.MaintenanceStatus.SCHEDULED);
        toStart.gameId = "g1";
        lenient().when(maintenanceWindowRepo.findPendingUnnotified(any())).thenReturn(List.of(toStart));
        maintenanceService.checkPendingMaintenances();
        // 一次性通知守卫：状态机不动，notificationSent 置位 + 派发一次
        assertEquals(MaintenanceWindowEntity.MaintenanceStatus.SCHEDULED, toStart.maintenanceStatus);
        assertEquals(Boolean.TRUE, toStart.notificationSent);
        assertNotNull(toStart.notificationSentAt);
        verify(webhookService).sendCustomWebhook(eq("g1"), eq(WebhookService.EVENT_MAINTENANCE_UPCOMING), anyMap());

        MaintenanceWindowEntity toEnd = window(MaintenanceWindowEntity.MaintenanceStatus.IN_PROGRESS);
        toEnd.gameId = "g1";
        lenient().when(maintenanceWindowRepo.findShouldEndUnnotified(any())).thenReturn(List.of(toEnd));
        maintenanceService.checkEndingMaintenances();
        assertEquals(Boolean.TRUE, toEnd.endNotificationSent);
        verify(webhookService).sendCustomWebhook(eq("g1"), eq(WebhookService.EVENT_MAINTENANCE_ENDING), anyMap());

        FeatureFlagEntity enable = new FeatureFlagEntity();
        enable.flagKey = "f-enable";
        enable.flagStatus = FeatureFlagEntity.FlagStatus.DISABLED;
        FeatureFlagEntity disable = new FeatureFlagEntity();
        disable.flagKey = "f-disable";
        disable.flagStatus = FeatureFlagEntity.FlagStatus.ENABLED;
        FeatureFlagEntity expired = new FeatureFlagEntity();
        expired.flagKey = "f-expired";
        expired.flagStatus = FeatureFlagEntity.FlagStatus.ENABLED;
        lenient().when(featureFlagRepo.findScheduledToEnable(any())).thenReturn(List.of(enable));
        lenient().when(featureFlagRepo.findScheduledToDisable(any())).thenReturn(List.of(disable));
        lenient().when(featureFlagRepo.findExpired(any())).thenReturn(List.of(expired));

        maintenanceService.checkScheduledFeatureFlags();
        assertEquals(FeatureFlagEntity.FlagStatus.ENABLED, enable.flagStatus);
        assertEquals("system", enable.lastModifiedBy);
        assertEquals(FeatureFlagEntity.FlagStatus.DISABLED, disable.flagStatus);
        assertEquals(FeatureFlagEntity.FlagStatus.DISABLED, expired.flagStatus);
    }

    @Test
    @DisplayName("Webhook 接线：维护窗口派发异常被吞，一次性通知标志照常置位")
    void webhookDispatchFailureSwallowedForMaintenance() {
        lenient().when(maintenanceWindowRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new RuntimeException("wh down")).when(webhookService)
            .sendCustomWebhook(anyString(), anyString(), anyMap());

        MaintenanceWindowEntity toStart = window(MaintenanceWindowEntity.MaintenanceStatus.SCHEDULED);
        toStart.gameId = "g1";
        lenient().when(maintenanceWindowRepo.findPendingUnnotified(any())).thenReturn(List.of(toStart));
        assertDoesNotThrow(() -> maintenanceService.checkPendingMaintenances());
        assertEquals(Boolean.TRUE, toStart.notificationSent);
        assertNotNull(toStart.notificationSentAt);

        MaintenanceWindowEntity toEnd = window(MaintenanceWindowEntity.MaintenanceStatus.IN_PROGRESS);
        toEnd.gameId = "g1";
        lenient().when(maintenanceWindowRepo.findShouldEndUnnotified(any())).thenReturn(List.of(toEnd));
        assertDoesNotThrow(() -> maintenanceService.checkEndingMaintenances());
        assertEquals(Boolean.TRUE, toEnd.endNotificationSent);

        verify(webhookService, times(2)).sendCustomWebhook(eq("g1"), anyString(), anyMap());
    }

}
