package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.SDKKeyEntity;
import io.oddsmaker.control.jpa.SDKKeyRepo;
import io.oddsmaker.control.jpa.SDKVersionEntity;
import io.oddsmaker.control.jpa.SDKVersionRepo;
import io.oddsmaker.control.jpa.TelemetryConfigEntity;
import io.oddsmaker.control.jpa.TelemetryConfigRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 开发者门户 Service 测试：SDK 密钥/版本/遥测配置主路径与状态迁移。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("开发者门户 Service 测试")
class DeveloperPortalServiceTest {

    @Mock
    private SDKKeyRepo sdkKeyRepo;

    @Mock
    private SDKVersionRepo sdkVersionRepo;

    @Mock
    private TelemetryConfigRepo telemetryConfigRepo;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private WebhookService webhookService;

    @InjectMocks
    private DeveloperPortalService service;

    private SDKKeyEntity key(String id) {
        SDKKeyEntity key = new SDKKeyEntity();
        key.id = id;
        key.gameId = "g";
        key.environment = "prod";
        key.keyName = "ops";
        key.platform = SDKKeyEntity.SDKPlatform.UNITY;
        key.publicKey = "pk_" + id;
        key.keyStatus = SDKKeyEntity.KeyStatus.ACTIVE;
        return key;
    }

    @Test
    @DisplayName("SDK 密钥：创建/查询/校验/状态迁移")
    void sdkKeyLifecycle() {
        lenient().when(sdkKeyRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SDKKeyEntity created = service.createSDKKey("g", "prod", "ops",
            SDKKeyEntity.SDKPlatform.UNITY, SDKKeyEntity.DeliveryMode.BATCH, Map.of(), "dev1");
        assertTrue(created.id.startsWith("sdk_"));
        assertNotNull(created.publicKey);

        when(sdkKeyRepo.findById("sdk_1")).thenReturn(Optional.of(key("sdk_1")));
        lenient().when(sdkKeyRepo.findByPublicKeyAndDeletedAtIsNull("pk_sdk_1")).thenReturn(Optional.of(key("sdk_1")));
        when(sdkKeyRepo.findByGameIdAndDeletedAtIsNull("g")).thenReturn(List.of(key("sdk_1")));
        lenient().when(sdkKeyRepo.findByGameIdAndEnvironmentAndDeletedAtIsNull("g", "prod")).thenReturn(List.of(key("sdk_1")));

        assertNotNull(service.getSDKKey("sdk_1"));
        assertEquals(1, service.getGameSDKKeys("g", null).size());
        assertEquals(1, service.getGameSDKKeys("g", "prod").size());
        assertTrue(service.validateSDKKey("pk_sdk_1", "g", "prod"));
        assertFalse(service.validateSDKKey("pk_sdk_1", "other", "prod"));

        assertNotNull(service.suspendSDKKey("sdk_1", "ops1"));
        assertNotNull(service.activateSDKKey("sdk_1", "ops1"));
        assertNotNull(service.revokeSDKKey("sdk_1", "ops1"));
        assertNotNull(service.updateSDKKey("sdk_1", Map.of("keyName", "new-name"), "ops1"));
        service.deleteSDKKey("sdk_1", "ops1");
    }

    @Test
    @DisplayName("SDK 版本：创建/查询/生命周期与重复拒绝")
    void sdkVersionLifecycle() {
        when(sdkVersionRepo.existsByPlatformAndVersion(SDKVersionEntity.SDKPlatform.UNITY, "1.0.0"))
            .thenReturn(false)
            .thenReturn(true);
        lenient().when(sdkVersionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SDKVersionEntity created = service.createSDKVersion(SDKVersionEntity.SDKPlatform.UNITY, "1.0.0",
            SDKVersionEntity.ChangeType.MINOR, "notes", "log", "dev1");
        assertNotNull(created.id);
        // 重复版本拒绝
        assertThrows(IllegalArgumentException.class, () -> service.createSDKVersion(
            SDKVersionEntity.SDKPlatform.UNITY, "1.0.0", SDKVersionEntity.ChangeType.MINOR, "n", "l", "dev1"));

        SDKVersionEntity version = new SDKVersionEntity();
        version.id = "sv_1";
        version.platform = SDKVersionEntity.SDKPlatform.UNITY;
        version.version = "1.0.0";
        version.versionStatus = SDKVersionEntity.VersionStatus.RELEASED;
        when(sdkVersionRepo.findById("sv_1")).thenReturn(Optional.of(version));
        lenient().when(sdkVersionRepo.findByPlatformOrderByCreatedAtDesc(SDKVersionEntity.SDKPlatform.UNITY))
            .thenReturn(List.of(version));
        lenient().when(sdkVersionRepo.findByPlatformAndVersionStatusOrderByCreatedAtDesc(
            eq(SDKVersionEntity.SDKPlatform.UNITY), any())).thenReturn(List.of(version));
        lenient().when(sdkVersionRepo.findLatestByPlatform(SDKVersionEntity.SDKPlatform.UNITY))
            .thenReturn(Optional.of(version));
        lenient().when(sdkVersionRepo.findRetiringSoon(any())).thenReturn(List.of());
        lenient().when(sdkVersionRepo.countByStatus()).thenReturn(List.of());

        assertNotNull(service.getSDKVersion("sv_1"));
        assertEquals(1, service.getPlatformVersions(SDKVersionEntity.SDKPlatform.UNITY, null).size());
        assertNotNull(service.getLatestVersion(SDKVersionEntity.SDKPlatform.UNITY));
        assertNotNull(service.deprecateVersion("sv_1", "notice", "ops1"));
        assertNotNull(service.retireVersion("sv_1", "ops1"));
        service.recordDownload("sv_1");
        service.updateActiveInstallations("sv_1", 100L);
        service.checkRetiringVersions();
    }

    @Test
    @DisplayName("Webhook 接线：即将退役版本派发 sdk_version_retiring（DEFAULT 平台级）；派发异常被吞")
    @SuppressWarnings("unchecked")
    void checkRetiringVersionsDispatchesWebhook() {
        SDKVersionEntity retiring = new SDKVersionEntity();
        retiring.id = "sv_r1";
        retiring.platform = SDKVersionEntity.SDKPlatform.UNITY;
        retiring.version = "1.0.0";
        retiring.versionStatus = SDKVersionEntity.VersionStatus.RELEASED;
        retiring.retirementDate = LocalDateTime.now().plusDays(10).plusHours(2);
        when(sdkVersionRepo.findRetiringSoon(any())).thenReturn(List.of(retiring));

        service.checkRetiringVersions();

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(webhookService).sendCustomWebhook(eq("DEFAULT"),
            eq(WebhookService.EVENT_SDK_VERSION_RETIRING), payload.capture());
        assertEquals("1.0.0", payload.getValue().get("version"));
        assertEquals("UNITY", payload.getValue().get("platform"));
        assertEquals("RELEASED", payload.getValue().get("version_status"));
        assertEquals(10L, payload.getValue().get("days_until_retiring"));

        // 派发异常被吞：不中断后续版本的退役检查
        doThrow(new RuntimeException("wh down")).when(webhookService)
            .sendCustomWebhook(anyString(), anyString(), anyMap());
        assertDoesNotThrow(() -> service.checkRetiringVersions());
        verify(webhookService, times(2)).sendCustomWebhook(anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("遥测配置：创建/查询/激活/停用/归档")
    void telemetryLifecycle() {
        lenient().when(telemetryConfigRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TelemetryConfigEntity created = service.createTelemetryConfig(
            "g", "prod", "batch-config", TelemetryConfigEntity.ConfigType.BATCH, "d", false, Map.of(), "dev1");
        assertNotNull(created.id);

        TelemetryConfigEntity config = new TelemetryConfigEntity();
        config.id = "tc_1";
        config.gameId = "g";
        config.environmentId = "prod";
        config.configType = TelemetryConfigEntity.ConfigType.BATCH;
        config.configStatus = TelemetryConfigEntity.ConfigStatus.ACTIVE;
        when(telemetryConfigRepo.findById("tc_1")).thenReturn(Optional.of(config));
        lenient().when(telemetryConfigRepo.findByGameIdAndDeletedAtIsNull("g")).thenReturn(List.of(config));
        lenient().when(telemetryConfigRepo.findByGameIdAndEnvironmentIdAndDeletedAtIsNull("g", "prod"))
            .thenReturn(List.of(config));
        lenient().when(telemetryConfigRepo.findActiveByGameIdAndEnvironmentIdAndType(
            eq("g"), eq("prod"), any())).thenReturn(List.of(config));
        lenient().when(telemetryConfigRepo.findActiveByGameIdAndType(eq("g"), any())).thenReturn(List.of(config));
        lenient().when(telemetryConfigRepo.findActiveGlobalConfigs()).thenReturn(List.of(config));

        assertNotNull(service.getTelemetryConfig("tc_1"));
        assertEquals(1, service.getGameTelemetryConfigs("g", null).size());
        assertEquals(1, service.getGameTelemetryConfigs("g", "prod").size());
        assertNotNull(service.getEffectiveConfig("g", "prod", TelemetryConfigEntity.ConfigType.BATCH));
        assertNotNull(service.updateTelemetryConfig("tc_1", Map.of("description", "d"), "ops1"));
        assertNotNull(service.activateTelemetryConfig("tc_1", "ops1"));
        assertNotNull(service.deactivateTelemetryConfig("tc_1", "ops1"));
        assertNotNull(service.archiveTelemetryConfig("tc_1", "ops1"));
        service.deleteTelemetryConfig("tc_1", "ops1");
        service.cleanupExpiredKeys();
    }

    @Test
    @DisplayName("SDK 统计：平台/状态计数聚合")
    void sdkStatistics() {
        lenient().when(sdkKeyRepo.countByStatus()).thenReturn(List.of());
        lenient().when(sdkKeyRepo.countByPlatform()).thenReturn(List.of());
        lenient().when(sdkVersionRepo.countByPlatform()).thenReturn(List.of());
        lenient().when(sdkKeyRepo.findExpired()).thenReturn(List.of());

        Map<String, Object> stats = service.getSDKStatistics();
        assertNotNull(stats);
    }



    @Test
    @DisplayName("分支对侧：updateSDKKey 空更新、遥测 config null 跳过应用、退役扫描 null 字段派发")
    void developerPortalBranchSides() {
        // 空 updates：两个 containsKey 均 false，密钥字段不动
        lenient().when(sdkKeyRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sdkKeyRepo.findById("sdk_b")).thenReturn(Optional.of(key("sdk_b")));
        assertNotNull(service.updateSDKKey("sdk_b", java.util.Map.of(), "ops1"));

        // createTelemetryConfig config=null → 跳过 applyTelemetryConfig
        lenient().when(telemetryConfigRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        TelemetryConfigEntity bare = service.createTelemetryConfig(
            "g", "prod", "bare", TelemetryConfigEntity.ConfigType.BATCH, "d", false, null, "dev1");
        assertNotNull(bare.id);

        // checkRetiringVersions：platform/versionStatus/retirementDate 全 null 三元侧
        SDKVersionEntity retiring = new SDKVersionEntity();
        retiring.version = "1.0.0";
        retiring.platform = null;
        retiring.versionStatus = null;
        retiring.retirementDate = null;
        when(sdkVersionRepo.findRetiringSoon(any(java.time.LocalDateTime.class)))
            .thenReturn(java.util.List.of(retiring));
        assertDoesNotThrow(() -> service.checkRetiringVersions());
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> cap =
            org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(webhookService).sendCustomWebhook(eq("DEFAULT"),
            eq(WebhookService.EVENT_SDK_VERSION_RETIRING), cap.capture());
        org.junit.jupiter.api.Assertions.assertNull(cap.getValue().get("platform"));
        org.junit.jupiter.api.Assertions.assertNull(cap.getValue().get("version_status"));
        org.junit.jupiter.api.Assertions.assertNull(cap.getValue().get("retirement_date"));
        assertEquals(-1L, cap.getValue().get("days_until_retiring"));
    }
}
