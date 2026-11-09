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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 覆盖率最终冲刺（第三轮，LAY-016 拆分）：DeveloperPortalService——SDK 密钥 suspend/activate/revoke/delete 生命周期与列表查询、SDK 版本创建/查询/下载统计、遥测配置激活/停用/归档/删除与查询。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("覆盖率最终冲刺测试（第三轮）")
class FinalSweep2DeveloperPortalTest {

    @Mock
    private SDKKeyRepo sdkKeyRepo;

    @Mock
    private SDKVersionRepo sdkVersionRepo;

    @Mock
    private TelemetryConfigRepo telemetryConfigRepo;

    @Mock
    private AuditLogService auditLog;

    @InjectMocks
    private DeveloperPortalService developerPortalService;

    @Test
    @DisplayName("开发者门户：SDK 密钥 suspend/activate/revoke/delete 生命周期与列表查询")
    void portalSdkKeyLifecycle() {
        SDKKeyEntity key = new SDKKeyEntity();
        key.id = "sdk_1";
        key.keyName = "主密钥";
        key.gameId = "game_1";
        key.environment = "prod";
        lenient().when(sdkKeyRepo.findById("sdk_1")).thenReturn(Optional.of(key));
        lenient().when(sdkKeyRepo.save(any(SDKKeyEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        SDKKeyEntity suspended = developerPortalService.suspendSDKKey("sdk_1", "op");
        assertEquals(SDKKeyEntity.KeyStatus.SUSPENDED, suspended.keyStatus);
        verify(auditLog).logUpdate(eq("sdk_key"), eq("sdk_1"), eq("主密钥"),
            eq("op"), eq("op"), isNull(), anyMap());

        assertEquals(SDKKeyEntity.KeyStatus.ACTIVE,
            developerPortalService.activateSDKKey("sdk_1", "op").keyStatus);
        assertEquals(SDKKeyEntity.KeyStatus.REVOKED,
            developerPortalService.revokeSDKKey("sdk_1", "op").keyStatus);

        developerPortalService.deleteSDKKey("sdk_1", "op");
        assertNotNull(key.deletedAt);
        verify(auditLog).logDelete(eq("sdk_key"), eq("sdk_1"), eq("主密钥"),
            eq("op"), eq("op"), isNull());

        lenient().when(sdkKeyRepo.findByGameIdAndDeletedAtIsNull("game_1")).thenReturn(List.of(key));
        lenient().when(sdkKeyRepo.findByGameIdAndEnvironmentAndDeletedAtIsNull("game_1", "prod"))
            .thenReturn(List.of(key));
        assertEquals(1, developerPortalService.getGameSDKKeys("game_1", null).size());
        assertEquals(1, developerPortalService.getGameSDKKeys("game_1", "prod").size());

        lenient().when(sdkKeyRepo.findById("sdk_missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
            () -> developerPortalService.suspendSDKKey("sdk_missing", "op"));
    }

    @Test
    @DisplayName("开发者门户：SDK 版本创建/查询/下载统计与遥测配置激活/停用/归档/删除")
    void portalSdkVersionAndTelemetryLifecycle() {
        lenient().when(sdkVersionRepo.existsByPlatformAndVersion(
            SDKVersionEntity.SDKPlatform.UNITY, "2.0.0")).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () -> developerPortalService.createSDKVersion(
            SDKVersionEntity.SDKPlatform.UNITY, "2.0.0",
            SDKVersionEntity.ChangeType.MINOR, null, null, "op"));

        lenient().when(sdkVersionRepo.existsByPlatformAndVersion(
            SDKVersionEntity.SDKPlatform.UNITY, "2.1.0")).thenReturn(false);
        lenient().when(sdkVersionRepo.save(any(SDKVersionEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        SDKVersionEntity version = developerPortalService.createSDKVersion(
            SDKVersionEntity.SDKPlatform.UNITY, "2.1.0",
            SDKVersionEntity.ChangeType.MINOR, "notes", "changelog", "op");
        assertTrue(version.id.startsWith("sdkver_"));
        verify(auditLog).logCreate(eq("sdk_version"), eq(version.id), eq("2.1.0"),
            eq("op"), eq("op"), isNull(), anyMap());

        lenient().when(sdkVersionRepo.findById("sv_missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
            () -> developerPortalService.getSDKVersion("sv_missing"));

        lenient().when(sdkVersionRepo.findByPlatformOrderByCreatedAtDesc(SDKVersionEntity.SDKPlatform.UNITY))
            .thenReturn(List.of(version));
        lenient().when(sdkVersionRepo.findByPlatformAndVersionStatusOrderByCreatedAtDesc(
            eq(SDKVersionEntity.SDKPlatform.UNITY), eq(SDKVersionEntity.VersionStatus.RELEASED)))
            .thenReturn(List.of());
        assertEquals(1, developerPortalService
            .getPlatformVersions(SDKVersionEntity.SDKPlatform.UNITY, null).size());
        assertEquals(0, developerPortalService
            .getPlatformVersions(SDKVersionEntity.SDKPlatform.UNITY, "RELEASED").size());

        lenient().when(sdkVersionRepo.findLatestByPlatform(SDKVersionEntity.SDKPlatform.UNITY))
            .thenReturn(Optional.of(version));
        assertSame(version, developerPortalService.getLatestVersion(SDKVersionEntity.SDKPlatform.UNITY));
        lenient().when(sdkVersionRepo.findLatestByPlatform(SDKVersionEntity.SDKPlatform.ANDROID))
            .thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
            () -> developerPortalService.getLatestVersion(SDKVersionEntity.SDKPlatform.ANDROID));

        lenient().when(sdkVersionRepo.findById("sv_1")).thenReturn(Optional.of(version));
        developerPortalService.recordDownload("sv_1");
        assertEquals(1L, version.totalDownloads);
        developerPortalService.updateActiveInstallations("sv_1", 500L);
        assertEquals(500L, version.activeInstallations);
        // createSDKVersion + recordDownload + updateActiveInstallations 共 3 次落库
        verify(sdkVersionRepo, times(3)).save(version);

        TelemetryConfigEntity config = new TelemetryConfigEntity();
        config.id = "tc_1";
        config.configName = "默认批量";
        lenient().when(telemetryConfigRepo.findById("tc_1")).thenReturn(Optional.of(config));
        lenient().when(telemetryConfigRepo.save(any(TelemetryConfigEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        assertEquals(TelemetryConfigEntity.ConfigStatus.ACTIVE,
            developerPortalService.activateTelemetryConfig("tc_1", "op").configStatus);
        assertEquals(TelemetryConfigEntity.ConfigStatus.INACTIVE,
            developerPortalService.deactivateTelemetryConfig("tc_1", "op").configStatus);
        assertEquals(TelemetryConfigEntity.ConfigStatus.ARCHIVED,
            developerPortalService.archiveTelemetryConfig("tc_1", "op").configStatus);
        developerPortalService.deleteTelemetryConfig("tc_1", "op");
        assertNotNull(config.deletedAt);
        verify(auditLog).logDelete(eq("telemetry_config"), eq("tc_1"), eq("默认批量"),
            eq("op"), eq("op"), isNull());

        lenient().when(telemetryConfigRepo.findByGameIdAndDeletedAtIsNull("game_1"))
            .thenReturn(List.of(config));
        lenient().when(telemetryConfigRepo.findByGameIdAndEnvironmentIdAndDeletedAtIsNull("game_1", "prod"))
            .thenReturn(List.of(config));
        assertEquals(1, developerPortalService.getGameTelemetryConfigs("game_1", null).size());
        assertEquals(1, developerPortalService.getGameTelemetryConfigs("game_1", "prod").size());
    }

}
