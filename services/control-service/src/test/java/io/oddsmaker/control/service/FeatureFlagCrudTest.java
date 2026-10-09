package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.AuditLogEntity;
import io.oddsmaker.control.jpa.FeatureFlagEntity;
import io.oddsmaker.control.jpa.FeatureFlagRepo;
import io.oddsmaker.control.jpa.MaintenanceWindowRepo;
import io.oddsmaker.control.jpa.SystemConfigRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 功能开关创建/全字段更新测试（补齐此前仅 enable/disable/percentage/advance 的写面）。
 *
 * 覆盖：创建默认态（恒 DISABLED）、名单 JSON 序列化、非法 JSON 拒绝、重复 key 拒绝、
 * 更新提供才写（空数组=清空名单、空白串=清空 JSON 字段）、无字段更新拒绝、审计动作。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("功能开关创建/更新测试")
class FeatureFlagCrudTest {

    @Mock
    private MaintenanceWindowRepo maintenanceWindowRepo;

    @Mock
    private SystemConfigRepo systemConfigRepo;

    @Mock
    private FeatureFlagRepo featureFlagRepo;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private WebhookService webhookService;

    private MaintenanceService service;

    @BeforeEach
    void setUp() {
        service = new MaintenanceService();
        ReflectionTestUtils.setField(service, "maintenanceWindowRepo", maintenanceWindowRepo);
        ReflectionTestUtils.setField(service, "systemConfigRepo", systemConfigRepo);
        ReflectionTestUtils.setField(service, "featureFlagRepo", featureFlagRepo);
        ReflectionTestUtils.setField(service, "auditLogService", auditLogService);
        ReflectionTestUtils.setField(service, "webhookService", webhookService);
    }

    private static FeatureFlagEntity savedFlag() {
        FeatureFlagEntity f = new FeatureFlagEntity();
        f.flagKey = "new_flag";
        f.flagName = "新开关";
        return f;
    }

    @Test
    @DisplayName("创建：恒 DISABLED + 默认值补齐 + 名单 JSON 序列化 + CREATE 审计")
    void createHappyPath() {
        when(featureFlagRepo.findByKey("new_flag")).thenReturn(Optional.empty());
        when(featureFlagRepo.save(any(FeatureFlagEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        MaintenanceService.FeatureFlagCreate req = new MaintenanceService.FeatureFlagCreate();
        req.flagKey = "new_flag";
        req.flagName = "新开关";
        req.flagType = FeatureFlagEntity.FlagType.WHITELIST;
        req.whitelistUsers = List.of("u1", "u10");
        req.blacklistGames = List.of();
        req.createdBy = "admin";

        FeatureFlagEntity out = service.createFeatureFlag(req);

        assertEquals(FeatureFlagEntity.FlagStatus.DISABLED, out.flagStatus);
        assertEquals(FeatureFlagEntity.FlagType.WHITELIST, out.flagType);
        assertEquals(false, out.defaultValue);
        assertEquals(0, out.percentageValue);
        assertEquals("[\"u1\",\"u10\"]", out.whitelistUsers);
        assertEquals("[]", out.blacklistGames);
        assertNull(out.conditions);
        assertEquals("admin", out.createdBy);

        ArgumentCaptor<FeatureFlagEntity> cap = ArgumentCaptor.forClass(FeatureFlagEntity.class);
        verify(featureFlagRepo).save(cap.capture());
        assertEquals("new_flag", cap.getValue().flagKey);
        verify(auditLogService).log(org.mockito.ArgumentMatchers.eq(AuditLogEntity.AuditAction.CREATE),
                org.mockito.ArgumentMatchers.eq("feature_flag"),
                org.mockito.ArgumentMatchers.eq("new_flag"),
                org.mockito.ArgumentMatchers.eq("新开关"),
                anyString(), any(AuditLogEntity.AuditResult.class), any(),
                any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("创建拒绝：空 key/空名、重复 key、非法 conditions/rolloutSteps JSON")
    void createRejections() {
        MaintenanceService.FeatureFlagCreate req = new MaintenanceService.FeatureFlagCreate();
        req.flagName = "n";
        assertThrows(IllegalArgumentException.class, () -> service.createFeatureFlag(req));
        req.flagKey = "k";
        req.flagName = " ";
        assertThrows(IllegalArgumentException.class, () -> service.createFeatureFlag(req));

        req.flagName = "n";
        when(featureFlagRepo.findByKey("k")).thenReturn(Optional.of(savedFlag()));
        assertThrows(IllegalStateException.class, () -> service.createFeatureFlag(req));

        when(featureFlagRepo.findByKey("k2")).thenReturn(Optional.empty());
        req.flagKey = "k2";
        req.conditions = "{not json";
        assertThrows(IllegalArgumentException.class, () -> service.createFeatureFlag(req));
        req.conditions = null;
        req.rolloutSteps = "[0,";
        assertThrows(IllegalArgumentException.class, () -> service.createFeatureFlag(req));

        verify(auditLogService, never()).log(any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("创建：rolloutSteps/conditions 合法 JSON 通过；percentageValue 钳制 0-100")
    void createValidationPass() {
        when(featureFlagRepo.findByKey("k3")).thenReturn(Optional.empty());
        when(featureFlagRepo.save(any(FeatureFlagEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        MaintenanceService.FeatureFlagCreate req = new MaintenanceService.FeatureFlagCreate();
        req.flagKey = "k3";
        req.flagName = "灰度开关";
        req.rolloutSteps = "[10,50,100]";
        req.conditions = "[{\"attribute\":\"game_id\",\"op\":\"eq\",\"value\":\"g1\"}]";
        req.percentageValue = 250;

        FeatureFlagEntity out = service.createFeatureFlag(req);
        assertEquals("[10,50,100]", out.rolloutSteps);
        assertEquals(100, out.percentageValue);
        assertEquals(FeatureFlagEntity.FlagStatus.DISABLED, out.flagStatus);
    }

    @Test
    @DisplayName("更新：提供才写——未提字段保留、空数组清名单、空白串清 JSON 字段、lastModifiedBy 落列")
    void updateWritesProvidedOnly() {
        FeatureFlagEntity existing = savedFlag();
        existing.flagType = FeatureFlagEntity.FlagType.BOOLEAN;
        existing.whitelistUsers = "[\"u1\"]";
        existing.conditions = "[{\"attribute\":\"game_id\",\"op\":\"eq\",\"value\":\"g1\"}]";
        existing.rolloutSteps = "[10]";
        existing.owner = "old_owner";
        when(featureFlagRepo.findByKey("new_flag")).thenReturn(Optional.of(existing));
        when(featureFlagRepo.save(any(FeatureFlagEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        MaintenanceService.FeatureFlagUpdate req = new MaintenanceService.FeatureFlagUpdate();
        req.flagName = "改名";
        req.whitelistUsers = List.of();
        req.conditions = "";
        req.owner = "new_owner";
        req.modifiedBy = "alice";

        FeatureFlagEntity out = service.updateFeatureFlag("new_flag", req);

        assertEquals("改名", out.flagName);
        assertEquals("[]", out.whitelistUsers);
        assertNull(out.conditions);
        assertEquals("new_owner", out.owner);
        assertEquals("alice", out.lastModifiedBy);
        assertEquals("[10]", out.rolloutSteps);          // 未提供 → 保留
        assertEquals(FeatureFlagEntity.FlagType.BOOLEAN, out.flagType);  // 未提供 → 保留
        assertEquals(FeatureFlagEntity.FlagStatus.DISABLED, out.flagStatus);  // 更新不触碰状态机

        verify(auditLogService).log(org.mockito.ArgumentMatchers.eq(AuditLogEntity.AuditAction.UPDATE),
                org.mockito.ArgumentMatchers.eq("feature_flag"),
                org.mockito.ArgumentMatchers.eq("new_flag"),
                org.mockito.ArgumentMatchers.eq("改名"),
                anyString(), any(AuditLogEntity.AuditResult.class), any(),
                any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("更新拒绝：不存在 / 全空字段体")
    void updateRejections() {
        when(featureFlagRepo.findByKey("missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
                () -> service.updateFeatureFlag("missing", new MaintenanceService.FeatureFlagUpdate()));

        when(featureFlagRepo.findByKey("new_flag")).thenReturn(Optional.of(savedFlag()));
        assertThrows(IllegalArgumentException.class,
                () -> service.updateFeatureFlag("new_flag", new MaintenanceService.FeatureFlagUpdate()));

        MaintenanceService.FeatureFlagUpdate blankName = new MaintenanceService.FeatureFlagUpdate();
        blankName.flagName = "  ";
        assertThrows(IllegalArgumentException.class, () -> service.updateFeatureFlag("new_flag", blankName));

        verify(auditLogService, never()).log(any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("名单成员判断语义回归：序列化后的 JSON 数组按成员匹配，u1 不误伤 u10")
    void whitelistMemberSemantics() {
        when(featureFlagRepo.findByKey("k4")).thenReturn(Optional.empty());
        when(featureFlagRepo.save(any(FeatureFlagEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        MaintenanceService.FeatureFlagCreate req = new MaintenanceService.FeatureFlagCreate();
        req.flagKey = "k4";
        req.flagName = "名单语义";
        req.flagType = FeatureFlagEntity.FlagType.WHITELIST;
        req.whitelistUsers = List.of("u1", "u10");

        FeatureFlagEntity out = service.createFeatureFlag(req);
        // 新建恒 DISABLED（语义上恒不可用）；置 ENABLED 后名单按成员匹配
        out.flagStatus = FeatureFlagEntity.FlagStatus.ENABLED;
        assertTrue(out.isAvailableForUser("u1", null));
        assertTrue(out.isAvailableForUser("u10", null));
        assertFalse(out.isAvailableForUser("u100", null));
    }
}
