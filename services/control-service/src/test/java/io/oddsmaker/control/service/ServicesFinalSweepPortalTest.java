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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Service 层覆盖率收尾测试（LAY-006 拆分）：有效配置回退链、版本生命周期守卫、SDK 密钥非法状态拒绝。与 DeveloperPortalServiceTest 等互补。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("开发者门户 Service 深度测试（配置回退/版本/密钥校验）")
class ServicesFinalSweepPortalTest {

    @Mock
    private SDKKeyRepo sdkKeyRepo;

    @Mock
    private SDKVersionRepo sdkVersionRepo;

    @Mock
    private TelemetryConfigRepo telemetryConfigRepo;

    @Mock
    private AuditLogService auditLog;

    @Mock
    private WebhookService webhookService;

    @InjectMocks
    private DeveloperPortalService developerPortalService;

    private static TelemetryConfigEntity telemetryConfig(String id) {
        TelemetryConfigEntity c = new TelemetryConfigEntity();
        c.id = id;
        c.gameId = "game_1";
        c.configType = TelemetryConfigEntity.ConfigType.BATCH;
        c.configStatus = TelemetryConfigEntity.ConfigStatus.ACTIVE;
        return c;
    }

    @Test
    @DisplayName("开发者门户：getEffectiveConfig 依次回退到游戏级/全局/内置默认配置")
    void portalEffectiveConfigFallbackChain() {
        TelemetryConfigEntity gameLevel = telemetryConfig("tc_game");
        lenient().when(telemetryConfigRepo.findActiveByGameIdAndEnvironmentIdAndType(
            eq("game_1"), eq("prod"), any())).thenReturn(List.of());
        lenient().when(telemetryConfigRepo.findActiveByGameIdAndType(eq("game_1"), any()))
            .thenReturn(List.of(gameLevel));
        assertEquals("tc_game", developerPortalService.getEffectiveConfig(
            "game_1", "prod", TelemetryConfigEntity.ConfigType.BATCH).id);

        TelemetryConfigEntity global = telemetryConfig("tc_global");
        lenient().when(telemetryConfigRepo.findActiveByGameIdAndType(eq("game_1"), any()))
            .thenReturn(List.of());
        lenient().when(telemetryConfigRepo.findActiveGlobalConfigs()).thenReturn(List.of(global));
        assertEquals("tc_global", developerPortalService.getEffectiveConfig(
            "game_1", "prod", TelemetryConfigEntity.ConfigType.BATCH).id);

        lenient().when(telemetryConfigRepo.findActiveGlobalConfigs()).thenReturn(List.of());
        TelemetryConfigEntity fallback = developerPortalService.getEffectiveConfig(
            "game_1", "prod", TelemetryConfigEntity.ConfigType.BATCH);
        assertNull(fallback.id);
        assertTrue(fallback.isDefault);
        assertEquals("REALTIME", fallback.deliveryMode);
        assertEquals(500, fallback.batchSize);
        assertEquals("GZIP", fallback.compressionAlgorithm);
        assertEquals(1.0, fallback.sampleRate);
    }

    @Test
    @DisplayName("开发者门户：版本发布/弃用/退役前置状态校验")
    void portalVersionLifecycleGuards() {
        SDKVersionEntity draft = new SDKVersionEntity();
        draft.id = "sv_1";
        draft.version = "2.0.0";
        draft.platform = SDKVersionEntity.SDKPlatform.UNITY;
        draft.versionStatus = SDKVersionEntity.VersionStatus.DRAFT;
        lenient().when(sdkVersionRepo.findById("sv_1")).thenReturn(Optional.of(draft));
        lenient().when(sdkVersionRepo.save(any(SDKVersionEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        SDKVersionEntity released = developerPortalService.releaseVersion(
            "sv_1", "https://dl/2.0.0", "com.x.sdk", "maven", "sha256", 1024L, "rel");
        assertEquals(SDKVersionEntity.VersionStatus.RELEASED, released.versionStatus);
        assertEquals("sha256", released.checksumSha256);
        assertEquals(1024L, released.fileSizeBytes);
        verify(auditLog).logUpdate(eq("sdk_version"), eq("sv_1"), eq("2.0.0"),
            eq("rel"), eq("rel"), isNull(), anyMap());

        assertThrows(IllegalStateException.class,
            () -> developerPortalService.releaseVersion("sv_1", null, null, null, null, null, "rel"));

        SDKVersionEntity unreleased = new SDKVersionEntity();
        unreleased.id = "sv_2";
        unreleased.versionStatus = SDKVersionEntity.VersionStatus.BETA;
        lenient().when(sdkVersionRepo.findById("sv_2")).thenReturn(Optional.of(unreleased));
        assertThrows(IllegalStateException.class,
            () -> developerPortalService.deprecateVersion("sv_2", "notice", "op"));

        SDKVersionEntity notDeprecated = new SDKVersionEntity();
        notDeprecated.id = "sv_3";
        notDeprecated.versionStatus = SDKVersionEntity.VersionStatus.RELEASED;
        lenient().when(sdkVersionRepo.findById("sv_3")).thenReturn(Optional.of(notDeprecated));
        assertThrows(IllegalStateException.class,
            () -> developerPortalService.retireVersion("sv_3", "op"));
    }

    @Test
    @DisplayName("开发者门户：validateSDKKey 拒绝未知公钥/非活跃密钥/环境不匹配")
    void portalValidateSdkKeyRejectsInvalidStates() {
        SDKKeyEntity suspended = new SDKKeyEntity();
        suspended.id = "sdk_1";
        suspended.gameId = "game_1";
        suspended.environment = "prod";
        suspended.keyStatus = SDKKeyEntity.KeyStatus.SUSPENDED;
        lenient().when(sdkKeyRepo.findByPublicKeyAndDeletedAtIsNull("pk_susp")).thenReturn(Optional.of(suspended));
        assertFalse(developerPortalService.validateSDKKey("pk_susp", "game_1", "prod"));

        SDKKeyEntity envMismatch = new SDKKeyEntity();
        envMismatch.gameId = "game_1";
        envMismatch.environment = "prod";
        envMismatch.keyStatus = SDKKeyEntity.KeyStatus.ACTIVE;
        lenient().when(sdkKeyRepo.findByPublicKeyAndDeletedAtIsNull("pk_env")).thenReturn(Optional.of(envMismatch));
        assertFalse(developerPortalService.validateSDKKey("pk_env", "game_1", "dev"));
        assertTrue(developerPortalService.validateSDKKey("pk_env", "game_1", "prod"));

        lenient().when(sdkKeyRepo.findByPublicKeyAndDeletedAtIsNull("pk_ghost")).thenReturn(Optional.empty());
        assertFalse(developerPortalService.validateSDKKey("pk_ghost", "game_1", "prod"));
    }

}
