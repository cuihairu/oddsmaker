package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.AuditLogEntity;
import io.oddsmaker.control.jpa.AuditLogRepo;
import io.oddsmaker.control.jpa.GameEntity;
import io.oddsmaker.control.jpa.GameRepo;
import io.oddsmaker.control.jpa.IdentityEntity;
import io.oddsmaker.control.jpa.IdentityLinkEntity;
import io.oddsmaker.control.jpa.IdentityLinkRepo;
import io.oddsmaker.control.jpa.IdentityRepo;
import io.oddsmaker.control.jpa.MailClaimRepo;
import io.oddsmaker.control.jpa.MailEntity;
import io.oddsmaker.control.jpa.MailRepo;
import io.oddsmaker.control.jpa.SDKKeyEntity;
import io.oddsmaker.control.jpa.SDKKeyRepo;
import io.oddsmaker.control.jpa.SDKVersionEntity;
import io.oddsmaker.control.jpa.SDKVersionRepo;
import io.oddsmaker.control.jpa.TelemetryConfigEntity;
import io.oddsmaker.control.jpa.TelemetryConfigRepo;
import io.oddsmaker.control.jpa.UserEntity;
import io.oddsmaker.control.jpa.UserRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户与开发者门户域深度单元测试（纯 Mockito，互补既有测试的未覆盖分支）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("用户/开发者门户深度测试")
class UserPortalDeepTest {

    @Mock
    private UserRepo userRepo;
    @Mock
    private AuditLogRepo auditLogRepo;
    @Mock
    private PasswordEncoder passwordEncoder;
    @InjectMocks
    private UserService userService;

    @Mock
    private SDKKeyRepo sdkKeyRepo;
    @Mock
    private SDKVersionRepo sdkVersionRepo;
    @Mock
    private TelemetryConfigRepo telemetryConfigRepo;
    @Mock
    private AuditLogService auditLogService;
    @InjectMocks
    private DeveloperPortalService developerPortalService;

    @Mock
    private IdentityRepo identityRepo;
    @Mock
    private IdentityLinkRepo identityLinkRepo;
    @InjectMocks
    private IdentityService identityService;

    @Mock
    private MailRepo mailRepo;
    @Mock
    private MailClaimRepo claimRepo;
    @Mock
    private GameRepo gameRepo;
    @Mock
    private AuditLogService auditLog;
    @InjectMocks
    private MailService mailService;

    private UserEntity user(String id, String username) {
        UserEntity u = new UserEntity();
        u.id = id;
        u.username = username;
        u.email = username + "@example.com";
        u.displayName = username.toUpperCase();
        u.status = UserEntity.UserStatus.ACTIVE;
        u.roles = Set.of(UserEntity.UserRole.VIEWER);
        return u;
    }

    private SDKKeyEntity sdkKey(String id, String publicKey) {
        SDKKeyEntity key = new SDKKeyEntity();
        key.id = id;
        key.gameId = "game_1";
        key.environment = "prod";
        key.keyName = "ops-key";
        key.platform = SDKKeyEntity.SDKPlatform.UNITY;
        key.publicKey = publicKey;
        key.keyStatus = SDKKeyEntity.KeyStatus.ACTIVE;
        key.deliveryMode = SDKKeyEntity.DeliveryMode.REALTIME;
        key.createdBy = "dev1";
        return key;
    }

    private IdentityEntity identity(String id, String gameId) {
        IdentityEntity e = new IdentityEntity();
        e.id = id;
        e.gameId = gameId;
        e.primaryId = "prim_" + id;
        e.deviceId = "dev_" + id;
        e.userId = "u_" + id;
        e.playerId = "p_" + id;
        return e;
    }

    private IdentityLinkEntity link(String id, String identityId, String type, String linkedId) {
        IdentityLinkEntity l = new IdentityLinkEntity();
        l.id = id;
        l.identityId = identityId;
        l.linkedIdentityType = type;
        l.linkedId = linkedId;
        return l;
    }

    private MailEntity mail(MailEntity.Scope scope, String recipients) {
        MailEntity m = new MailEntity();
        m.gameId = "game_1";
        m.title = "标题";
        m.content = "内容";
        m.scope = scope;
        m.recipients = recipients;
        return m;
    }

    private GameEntity game() {
        GameEntity g = new GameEntity();
        g.id = "game_1";
        return g;
    }

    // ==================== UserService ====================

    @Test
    @DisplayName("createUser：空ID/空状态/空角色时填充默认值，null邮箱跳过重复检查")
    void createUser_AppliesDefaults() {
        UserEntity u = new UserEntity();
        u.username = "deepuser";
        u.id = "   ";

        lenient().when(userRepo.existsByUsername("deepuser")).thenReturn(false);
        lenient().when(userRepo.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        UserEntity created = userService.createUser(u, "op_1");

        assertTrue(created.id.startsWith("user_"));
        assertEquals(21, created.id.length());
        assertEquals(UserEntity.UserStatus.ACTIVE, created.status);
        assertEquals(Set.of(UserEntity.UserRole.VIEWER), created.roles);
        verify(userRepo, never()).existsByEmail(any());
        verify(auditLogRepo).save(any(AuditLogEntity.class));
    }

    @Test
    @DisplayName("updateUser：邮箱被其他用户占用时拒绝")
    void updateUser_EmailOwnedByOther_Throws() {
        UserEntity existing = user("user_1", "alice");
        UserEntity updates = new UserEntity();
        updates.email = "taken@example.com";

        lenient().when(userRepo.findById("user_1")).thenReturn(Optional.of(existing));
        lenient().when(userRepo.existsByEmail("taken@example.com")).thenReturn(true);

        assertThrows(IllegalArgumentException.class,
            () -> userService.updateUser("user_1", updates, "op_1"));
        verify(userRepo, never()).save(any());
    }

    @Test
    @DisplayName("updateUser：邮箱与当前一致时不触发重复检查")
    void updateUser_SameEmail_SkipsDuplicateCheck() {
        UserEntity existing = user("user_1", "alice");
        UserEntity updates = new UserEntity();
        updates.email = existing.email;

        lenient().when(userRepo.findById("user_1")).thenReturn(Optional.of(existing));
        lenient().when(userRepo.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        UserEntity result = userService.updateUser("user_1", updates, "op_1");

        verify(userRepo, never()).existsByEmail(any());
        assertEquals("alice@example.com", result.email);
        verify(auditLogRepo).save(any(AuditLogEntity.class));
    }

    @Test
    @DisplayName("updateUser：全字段更新生效")
    void updateUser_AllFieldsApplied() {
        UserEntity existing = user("user_1", "alice");
        UserEntity updates = new UserEntity();
        updates.displayName = "新名称";
        updates.email = "new@example.com";
        updates.avatar = "https://avatar";
        updates.timezone = "Asia/Shanghai";
        updates.language = "zh";
        updates.status = UserEntity.UserStatus.PENDING;
        updates.roles = Set.of(UserEntity.UserRole.ADMIN, UserEntity.UserRole.DEVELOPER);

        lenient().when(userRepo.findById("user_1")).thenReturn(Optional.of(existing));
        lenient().when(userRepo.existsByEmail("new@example.com")).thenReturn(false);
        lenient().when(userRepo.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        UserEntity result = userService.updateUser("user_1", updates, "op_1");

        assertEquals("新名称", result.displayName);
        assertEquals("new@example.com", result.email);
        assertEquals("https://avatar", result.avatar);
        assertEquals("Asia/Shanghai", result.timezone);
        assertEquals("zh", result.language);
        assertEquals(UserEntity.UserStatus.PENDING, result.status);
        assertEquals(updates.roles, result.roles);
        verify(auditLogRepo).save(any(AuditLogEntity.class));
    }

    @Test
    @DisplayName("updateRoles/lockUser/unlockUser：用户不存在抛异常")
    void userMutations_NotFound_Throw() {
        lenient().when(userRepo.findById("ghost")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
            () -> userService.updateRoles("ghost", Set.of(UserEntity.UserRole.ADMIN), "op_1"));
        assertThrows(IllegalArgumentException.class,
            () -> userService.lockUser("ghost", "op_1"));
        assertThrows(IllegalArgumentException.class,
            () -> userService.unlockUser("ghost", "op_1"));
    }

    @Test
    @DisplayName("toggleTwoFactor：开启保留密钥，关闭清空密钥并记录审计")
    void toggleTwoFactor_EnableAndDisable() {
        UserEntity existing = user("user_1", "alice");
        existing.twoFactorSecret = "SECRET";

        lenient().when(userRepo.findById("user_1")).thenReturn(Optional.of(existing));
        lenient().when(userRepo.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        UserEntity enabled = userService.toggleTwoFactor("user_1", true, "op_1");
        assertTrue(enabled.twoFactorEnabled);
        assertEquals("SECRET", enabled.twoFactorSecret);

        UserEntity disabled = userService.toggleTwoFactor("user_1", false, "op_1");
        assertFalse(disabled.twoFactorEnabled);
        assertNull(disabled.twoFactorSecret);

        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogRepo, org.mockito.Mockito.times(2)).save(captor.capture());
        assertEquals(AuditLogEntity.AuditAction.ENABLE, captor.getAllValues().get(0).action);
        assertEquals(AuditLogEntity.AuditAction.DISABLE, captor.getAllValues().get(1).action);
    }

    @Test
    @DisplayName("toggleTwoFactor/recordLogin：用户不存在抛异常")
    void toggleTwoFactorAndRecordLogin_NotFound_Throw() {
        lenient().when(userRepo.findById("ghost")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
            () -> userService.toggleTwoFactor("ghost", true, "op_1"));
        assertThrows(IllegalArgumentException.class,
            () -> userService.recordLogin("ghost", "1.1.1.1"));
    }

    @Test
    @DisplayName("recordLogin：loginCount 从 null 起累加")
    void recordLogin_NullLoginCountIncrements() {
        UserEntity existing = user("user_1", "alice");
        existing.loginCount = null;

        lenient().when(userRepo.findById("user_1")).thenReturn(Optional.of(existing));
        lenient().when(userRepo.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        userService.recordLogin("user_1", "10.0.0.1");

        assertEquals(1L, existing.loginCount);
        assertEquals("10.0.0.1", existing.lastLoginIp);
        assertNotNull(existing.lastLoginAt);
        verify(auditLogRepo).save(any(AuditLogEntity.class));
    }

    @Test
    @DisplayName("recordLogout：成功记录审计；用户不存在抛异常")
    void recordLogout_SuccessAndNotFound() {
        UserEntity existing = user("user_1", "alice");
        lenient().when(userRepo.findById("user_1")).thenReturn(Optional.of(existing));
        lenient().when(userRepo.findById("ghost")).thenReturn(Optional.empty());

        userService.recordLogout("user_1", "10.0.0.1");
        verify(auditLogRepo).save(any(AuditLogEntity.class));

        assertThrows(IllegalArgumentException.class, () -> userService.recordLogout("ghost", "10.0.0.1"));
    }

    @Test
    @DisplayName("findByEmail/findByKeycloakId：命中且过滤已删除")
    void findByEmailAndKeycloakId_FilterDeleted() {
        UserEntity deleted = user("user_2", "bob");
        deleted.deletedAt = LocalDateTime.now();

        lenient().when(userRepo.findByEmail("alice@example.com")).thenReturn(Optional.of(user("user_1", "alice")));
        lenient().when(userRepo.findByEmail("bob@example.com")).thenReturn(Optional.of(deleted));
        lenient().when(userRepo.findByKeycloakId("kc_1")).thenReturn(Optional.of(user("user_1", "alice")));
        lenient().when(userRepo.findByKeycloakId("kc_2")).thenReturn(Optional.of(deleted));

        assertTrue(userService.findByEmail("alice@example.com").isPresent());
        assertFalse(userService.findByEmail("bob@example.com").isPresent());
        assertTrue(userService.findByKeycloakId("kc_1").isPresent());
        assertFalse(userService.findByKeycloakId("kc_2").isPresent());
    }

    @Test
    @DisplayName("getUserStatistics：有统计返回首行Map，无统计返回空Map")
    void getUserStatistics_EmptyAndData() {
        Map<String, Object> row = Map.of("totalUsers", 10L, "activeUsers", 8L);
        lenient().when(userRepo.getUserStatistics(any(LocalDateTime.class)))
            .thenReturn(List.of(row))
            .thenReturn(List.of());

        assertEquals(row, userService.getUserStatistics());
        assertTrue(userService.getUserStatistics().isEmpty());
    }

    @Test
    @DisplayName("getRecentlyLoggedInUsers：透传仓库查询")
    void getRecentlyLoggedInUsers_Delegates() {
        UserEntity existing = user("user_1", "alice");
        lenient().when(userRepo.findRecentlyLoggedIn(any())).thenReturn(List.of(existing));

        List<UserEntity> result = userService.getRecentlyLoggedInUsers(5);

        assertEquals(1, result.size());
        assertSame(existing, result.get(0));
    }

    // ==================== DeveloperPortalService ====================

    @Test
    @DisplayName("createSDKKey：applyKeyConfig 全量配置生效（含 JSON 序列化字段）")
    void createSDKKey_FullKeyConfigApplied() {
        Map<String, Object> config = new HashMap<>();
        config.put("rateLimitRpm", 2000);
        config.put("rateLimitRps", 200);
        config.put("batchSizeLimit", 100);
        config.put("batchIntervalMs", 500);
        config.put("maxEventSizeBytes", 1024);
        config.put("maxBatchSizeBytes", 2048);
        config.put("enableCompression", false);
        config.put("enableEncryption", true);
        config.put("sdkVersionConstraint", ">=1.0.0");
        config.put("minSdkVersion", "1.0.0");
        config.put("maxSdkVersion", "2.0.0");
        config.put("allowedDomains", List.of("example.com"));
        config.put("allowedIps", List.of("10.0.0.1"));
        config.put("retryPolicy", Map.of("maxRetries", 3));
        config.put("offlineConfig", Map.of("enabled", true));
        config.put("flushConfig", Map.of("intervalMs", 100));
        config.put("telemetryConfig", Map.of("sampleRate", 0.5));
        config.put("customConfig", Map.of("k", "v"));

        lenient().when(sdkKeyRepo.save(any(SDKKeyEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        SDKKeyEntity key = developerPortalService.createSDKKey("game_1", "prod", "deep-key",
            SDKKeyEntity.SDKPlatform.UNITY, SDKKeyEntity.DeliveryMode.BATCH, config, "dev1");

        assertEquals(2000, key.rateLimitRpm);
        assertEquals(200, key.rateLimitRps);
        assertEquals(100, key.batchSizeLimit);
        assertEquals(500, key.batchIntervalMs);
        assertEquals(1024, key.maxEventSizeBytes);
        assertEquals(2048, key.maxBatchSizeBytes);
        assertFalse(key.enableCompression);
        assertTrue(key.enableEncryption);
        assertEquals(">=1.0.0", key.sdkVersionConstraint);
        assertEquals("1.0.0", key.minSdkVersion);
        assertEquals("2.0.0", key.maxSdkVersion);
        assertEquals("[\"example.com\"]", key.allowedDomains);
        assertEquals("[\"10.0.0.1\"]", key.allowedIps);
        assertEquals("{\"maxRetries\":3}", key.retryPolicy);
        assertEquals("{\"enabled\":true}", key.offlineConfig);
        assertEquals("{\"intervalMs\":100}", key.flushConfig);
        assertEquals("{\"sampleRate\":0.5}", key.telemetryConfig);
        assertEquals("{\"k\":\"v\"}", key.customConfig);
        assertTrue(key.publicKey.startsWith("pk_"));
        verify(auditLogService).logCreate(eq("sdk_key"), anyString(), eq("deep-key"),
            eq("dev1"), eq("dev1"), isNull(), anyMap());
    }

    @Test
    @DisplayName("createSDKKey：config 为 null 时不应用任何配置")
    void createSDKKey_NullConfig_KeepsDefaults() {
        lenient().when(sdkKeyRepo.save(any(SDKKeyEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        SDKKeyEntity key = developerPortalService.createSDKKey("game_1", "prod", "plain",
            SDKKeyEntity.SDKPlatform.WEB, SDKKeyEntity.DeliveryMode.REALTIME, null, "dev1");

        assertEquals(1000, key.rateLimitRpm);
        assertEquals(500, key.batchSizeLimit);
        assertNull(key.allowedDomains);
    }

    @Test
    @DisplayName("updateSDKKey：keyName/deliveryMode/config 深分支")
    void updateSDKKey_DeepBranches() {
        SDKKeyEntity key = sdkKey("sdk_1", "pk_1");
        lenient().when(sdkKeyRepo.findById("sdk_1")).thenReturn(Optional.of(key));
        lenient().when(sdkKeyRepo.save(any(SDKKeyEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> updates = new HashMap<>();
        updates.put("keyName", "renamed");
        updates.put("deliveryMode", "HYBRID");
        updates.put("rateLimitRpm", 999);
        updates.put("minSdkVersion", "2.0.0");
        updates.put("allowedDomains", List.of("x.io"));

        SDKKeyEntity result = developerPortalService.updateSDKKey("sdk_1", updates, "ops1");

        assertEquals("renamed", result.keyName);
        assertEquals(SDKKeyEntity.DeliveryMode.HYBRID, result.deliveryMode);
        assertEquals(999, result.rateLimitRpm);
        assertEquals("2.0.0", result.minSdkVersion);
        assertEquals("[\"x.io\"]", result.allowedDomains);
        verify(auditLogService).logUpdate(eq("sdk_key"), eq("sdk_1"), eq("renamed"),
            eq("ops1"), eq("ops1"), isNull(), anyMap());
    }

    @Test
    @DisplayName("recordKeyEvent：带错误与不带错误分支")
    void recordKeyEvent_WithAndWithoutError() {
        SDKKeyEntity key = sdkKey("sdk_1", "pk_1");
        lenient().when(sdkKeyRepo.findByPublicKeyAndDeletedAtIsNull("pk_1")).thenReturn(Optional.of(key));
        lenient().when(sdkKeyRepo.save(any(SDKKeyEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        developerPortalService.recordKeyEvent("pk_1", 10, true, "boom");
        assertEquals(10L, key.totalEventsSent);
        assertEquals(1L, key.totalErrors);
        assertEquals("boom", key.lastErrorMessage);
        assertNotNull(key.lastErrorAt);

        developerPortalService.recordKeyEvent("pk_1", 5, true, null);
        assertEquals(15L, key.totalEventsSent);
        assertEquals(1L, key.totalErrors);

        developerPortalService.recordKeyEvent("pk_1", 1, false, "ignored");
        assertEquals(16L, key.totalEventsSent);
        assertEquals(1L, key.totalErrors);
        assertEquals("boom", key.lastErrorMessage);
    }

    @Test
    @DisplayName("getSDKKey/getSDKKeyByPublicKey：不存在抛异常")
    void getSDKKey_NotFound_Throws() {
        lenient().when(sdkKeyRepo.findById("ghost")).thenReturn(Optional.empty());
        lenient().when(sdkKeyRepo.findByPublicKeyAndDeletedAtIsNull("pk_ghost")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> developerPortalService.getSDKKey("ghost"));
        assertThrows(IllegalArgumentException.class,
            () -> developerPortalService.getSDKKeyByPublicKey("pk_ghost"));
    }

    @Test
    @DisplayName("validateSDKKey：不存在/暂停/过期/环境不匹配/环境为null 分支")
    void validateSDKKey_Branches() {
        SDKKeyEntity suspended = sdkKey("k1", "pk_s");
        suspended.suspend();
        SDKKeyEntity expired = sdkKey("k2", "pk_e");
        expired.expiresAt = LocalDateTime.now().minusDays(1);
        SDKKeyEntity noEnv = sdkKey("k3", "pk_n");
        noEnv.environment = null;

        lenient().when(sdkKeyRepo.findByPublicKeyAndDeletedAtIsNull("pk_none")).thenReturn(Optional.empty());
        lenient().when(sdkKeyRepo.findByPublicKeyAndDeletedAtIsNull("pk_s")).thenReturn(Optional.of(suspended));
        lenient().when(sdkKeyRepo.findByPublicKeyAndDeletedAtIsNull("pk_e")).thenReturn(Optional.of(expired));
        lenient().when(sdkKeyRepo.findByPublicKeyAndDeletedAtIsNull("pk_1")).thenReturn(Optional.of(sdkKey("k4", "pk_1")));
        lenient().when(sdkKeyRepo.findByPublicKeyAndDeletedAtIsNull("pk_n")).thenReturn(Optional.of(noEnv));

        assertFalse(developerPortalService.validateSDKKey("pk_none", "game_1", "prod"));
        assertFalse(developerPortalService.validateSDKKey("pk_s", "game_1", "prod"));
        assertFalse(developerPortalService.validateSDKKey("pk_e", "game_1", "prod"));
        assertFalse(developerPortalService.validateSDKKey("pk_1", "game_1", "dev"));
        assertTrue(developerPortalService.validateSDKKey("pk_n", "game_1", "prod"));
        assertTrue(developerPortalService.validateSDKKey("pk_n", "game_1", null));
    }

    @Test
    @DisplayName("createTelemetryConfig：applyTelemetryConfig 全量字段生效")
    void createTelemetryConfig_FullConfigApplied() {
        Map<String, Object> config = new HashMap<>();
        config.put("deliveryMode", "HYBRID");
        config.put("batchSize", 128);
        config.put("batchIntervalMs", 250);
        config.put("maxQueueSize", 4096);
        config.put("flushOnBackground", false);
        config.put("flushOnAppClose", false);
        config.put("enableCompression", false);
        config.put("compressionAlgorithm", "ZSTD");
        config.put("compressionLevel", 9);
        config.put("compressionThresholdBytes", 512);
        config.put("enableEncryption", true);
        config.put("encryptionAlgorithm", "AES-GCM");
        config.put("encryptionKeyId", "key-1");
        config.put("maxRetries", 5);
        config.put("retryIntervalMs", 2000);
        config.put("retryBackoffMultiplier", 3.0);
        config.put("maxRetryIntervalMs", 60000);
        config.put("retryOnStatusCodes", "500,502");
        config.put("enableOfflineStorage", false);
        config.put("offlineStorageMaxMb", 100);
        config.put("offlineStorageTtlHours", 24);
        config.put("offlineBatchSize", 50);
        config.put("enableTelemetry", false);
        config.put("telemetryIntervalMs", 30000);
        config.put("reportErrors", false);
        config.put("reportPerformance", false);
        config.put("sampleRate", 0.5);
        config.put("connectionTimeoutMs", 5000);
        config.put("readTimeoutMs", 10000);
        config.put("writeTimeoutMs", 15000);
        config.put("customConfig", Map.of("k", "v"));

        lenient().when(telemetryConfigRepo.save(any(TelemetryConfigEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        TelemetryConfigEntity created = developerPortalService.createTelemetryConfig(
            "game_1", "prod", "deep", TelemetryConfigEntity.ConfigType.BATCH, "d", false, config, "dev1");

        assertEquals("HYBRID", created.deliveryMode);
        assertEquals(128, created.batchSize);
        assertEquals(250, created.batchIntervalMs);
        assertEquals(4096, created.maxQueueSize);
        assertFalse(created.flushOnBackground);
        assertFalse(created.flushOnAppClose);
        assertFalse(created.enableCompression);
        assertEquals("ZSTD", created.compressionAlgorithm);
        assertEquals(9, created.compressionLevel);
        assertEquals(512, created.compressionThresholdBytes);
        assertTrue(created.enableEncryption);
        assertEquals("AES-GCM", created.encryptionAlgorithm);
        assertEquals("key-1", created.encryptionKeyId);
        assertEquals(5, created.maxRetries);
        assertEquals(2000, created.retryIntervalMs);
        assertEquals(3.0, created.retryBackoffMultiplier);
        assertEquals(60000, created.maxRetryIntervalMs);
        assertEquals("500,502", created.retryOnStatusCodes);
        assertFalse(created.enableOfflineStorage);
        assertEquals(100, created.offlineStorageMaxMb);
        assertEquals(24, created.offlineStorageTtlHours);
        assertEquals(50, created.offlineBatchSize);
        assertFalse(created.enableTelemetry);
        assertEquals(30000, created.telemetryIntervalMs);
        assertFalse(created.reportErrors);
        assertFalse(created.reportPerformance);
        assertEquals(0.5, created.sampleRate);
        assertEquals(5000, created.connectionTimeoutMs);
        assertEquals(10000, created.readTimeoutMs);
        assertEquals(15000, created.writeTimeoutMs);
        assertEquals("{\"k\":\"v\"}", created.customConfig);
        assertTrue(created.id.startsWith("telem_"));
    }

    @Test
    @DisplayName("updateTelemetryConfig：基础字段 + 遥测字段更新")
    void updateTelemetryConfig_AppliesUpdates() {
        TelemetryConfigEntity config = new TelemetryConfigEntity();
        config.id = "tc_1";
        config.gameId = "game_1";
        config.configName = "old";
        config.configType = TelemetryConfigEntity.ConfigType.BATCH;

        lenient().when(telemetryConfigRepo.findById("tc_1")).thenReturn(Optional.of(config));
        lenient().when(telemetryConfigRepo.save(any(TelemetryConfigEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> updates = new HashMap<>();
        updates.put("configName", "renamed");
        updates.put("description", "new-desc");
        updates.put("isDefault", true);
        updates.put("priority", 10);
        updates.put("batchSize", 256);
        updates.put("sampleRate", 0.25);
        updates.put("retryBackoffMultiplier", 1.5);
        updates.put("retryOnStatusCodes", "429");

        TelemetryConfigEntity result = developerPortalService.updateTelemetryConfig("tc_1", updates, "ops1");

        assertEquals("renamed", result.configName);
        assertEquals("new-desc", result.description);
        assertTrue(result.isDefault);
        assertEquals(10, result.priority);
        assertEquals(256, result.batchSize);
        assertEquals(0.25, result.sampleRate);
        assertEquals(1.5, result.retryBackoffMultiplier);
        assertEquals("429", result.retryOnStatusCodes);
        verify(auditLogService).logUpdate(eq("telemetry_config"), eq("tc_1"), eq("renamed"),
            eq("ops1"), eq("ops1"), isNull(), anyMap());
    }

    @Test
    @DisplayName("getTelemetryConfig：不存在抛异常")
    void getTelemetryConfig_NotFound_Throws() {
        lenient().when(telemetryConfigRepo.findById("ghost")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
            () -> developerPortalService.getTelemetryConfig("ghost"));
    }

    @Test
    @DisplayName("getEffectiveConfig：环境级 > 游戏级 > 全局 > 默认配置 回退链")
    void getEffectiveConfig_FallbackChain() {
        TelemetryConfigEntity envConfig = new TelemetryConfigEntity();
        envConfig.id = "tc_env";
        envConfig.configType = TelemetryConfigEntity.ConfigType.BATCH;
        TelemetryConfigEntity gameConfig = new TelemetryConfigEntity();
        gameConfig.id = "tc_game";
        gameConfig.configType = TelemetryConfigEntity.ConfigType.BATCH;
        TelemetryConfigEntity globalConfig = new TelemetryConfigEntity();
        globalConfig.id = "tc_global";
        globalConfig.configType = TelemetryConfigEntity.ConfigType.BATCH;
        TelemetryConfigEntity otherType = new TelemetryConfigEntity();
        otherType.id = "tc_other";
        otherType.configType = TelemetryConfigEntity.ConfigType.COMPRESSION;

        lenient().when(telemetryConfigRepo.findActiveByGameIdAndEnvironmentIdAndType(
            eq("game_1"), eq("prod"), eq(TelemetryConfigEntity.ConfigType.BATCH)))
            .thenReturn(List.of(envConfig))
            .thenReturn(List.of())
            .thenReturn(List.of())
            .thenReturn(List.of());
        lenient().when(telemetryConfigRepo.findActiveByGameIdAndType(
            eq("game_1"), eq(TelemetryConfigEntity.ConfigType.BATCH)))
            .thenReturn(List.of(gameConfig))
            .thenReturn(List.of())
            .thenReturn(List.of());
        lenient().when(telemetryConfigRepo.findActiveGlobalConfigs())
            .thenReturn(List.of(globalConfig))
            .thenReturn(List.of(otherType));

        assertSame(envConfig, developerPortalService.getEffectiveConfig(
            "game_1", "prod", TelemetryConfigEntity.ConfigType.BATCH));
        assertSame(gameConfig, developerPortalService.getEffectiveConfig(
            "game_1", "prod", TelemetryConfigEntity.ConfigType.BATCH));
        assertSame(globalConfig, developerPortalService.getEffectiveConfig(
            "game_1", "prod", TelemetryConfigEntity.ConfigType.BATCH));

        TelemetryConfigEntity fallback = developerPortalService.getEffectiveConfig(
            "game_1", "prod", TelemetryConfigEntity.ConfigType.BATCH);
        assertEquals(TelemetryConfigEntity.ConfigType.BATCH, fallback.configType);
        assertTrue(fallback.isDefault);
        assertTrue(fallback.isGlobal());
        assertEquals("REALTIME", fallback.deliveryMode);
        assertEquals(500, fallback.batchSize);
        assertEquals(3000, fallback.batchIntervalMs);
        assertEquals(10000, fallback.maxQueueSize);
        assertTrue(fallback.flushOnBackground);
        assertTrue(fallback.flushOnAppClose);
        assertTrue(fallback.enableCompression);
        assertEquals("GZIP", fallback.compressionAlgorithm);
        assertEquals(6, fallback.compressionLevel);
        assertEquals(1024, fallback.compressionThresholdBytes);
        assertFalse(fallback.enableEncryption);
        assertEquals(3, fallback.maxRetries);
        assertEquals(1000, fallback.retryIntervalMs);
        assertEquals(2.0, fallback.retryBackoffMultiplier);
        assertEquals(30000, fallback.maxRetryIntervalMs);
        assertTrue(fallback.enableOfflineStorage);
        assertEquals(50, fallback.offlineStorageMaxMb);
        assertEquals(72, fallback.offlineStorageTtlHours);
        assertEquals(100, fallback.offlineBatchSize);
        assertTrue(fallback.enableTelemetry);
        assertEquals(60000, fallback.telemetryIntervalMs);
        assertTrue(fallback.reportErrors);
        assertTrue(fallback.reportPerformance);
        assertEquals(1.0, fallback.sampleRate);
        assertEquals(10000, fallback.connectionTimeoutMs);
        assertEquals(30000, fallback.readTimeoutMs);
        assertEquals(30000, fallback.writeTimeoutMs);
    }

    @Test
    @DisplayName("getSDKStatistics：聚合计数与最新版本")
    void getSDKStatistics_Aggregates() {
        SDKVersionEntity version = new SDKVersionEntity();
        version.id = "sv_1";
        version.platform = SDKVersionEntity.SDKPlatform.UNITY;
        version.version = "1.2.3";
        version.versionStatus = SDKVersionEntity.VersionStatus.RELEASED;
        version.releasedAt = LocalDateTime.now();
        version.totalDownloads = 7L;

        lenient().when(sdkKeyRepo.countByPlatform())
            .thenReturn(List.<Object[]>of(new Object[]{SDKKeyEntity.SDKPlatform.UNITY, 3L}));
        lenient().when(sdkKeyRepo.countByStatus())
            .thenReturn(List.<Object[]>of(new Object[]{SDKKeyEntity.KeyStatus.ACTIVE, 2L}));
        lenient().when(sdkVersionRepo.countByPlatform())
            .thenReturn(List.<Object[]>of(new Object[]{SDKVersionEntity.SDKPlatform.UNITY, 1L}));
        lenient().when(sdkVersionRepo.countByStatus())
            .thenReturn(List.<Object[]>of(new Object[]{SDKVersionEntity.VersionStatus.RELEASED, 1L}));
        lenient().when(sdkVersionRepo.findLatestByPlatform(SDKVersionEntity.SDKPlatform.UNITY))
            .thenReturn(Optional.of(version));

        Map<String, Object> stats = developerPortalService.getSDKStatistics();

        assertEquals(Map.of("UNITY", 3L), stats.get("keysByPlatform"));
        assertEquals(Map.of("ACTIVE", 2L), stats.get("keysByStatus"));
        assertEquals(Map.of("UNITY", 1L), stats.get("versionsByPlatform"));
        assertEquals(Map.of("RELEASED", 1L), stats.get("versionsByStatus"));
        @SuppressWarnings("unchecked")
        Map<String, Object> latest = (Map<String, Object>) stats.get("latest_UNITY");
        assertEquals("1.2.3", latest.get("version"));
        assertEquals(7L, latest.get("totalDownloads"));
        assertNotNull(latest.get("releasedAt"));
        assertFalse(stats.containsKey("latest_WEB"));
    }

    // ==================== IdentityService ====================

    @Test
    @DisplayName("getIdentity：gameId 匹配/null 透传，不匹配与已删除过滤")
    void getIdentity_Filters() {
        IdentityEntity e = identity("id_1", "game_1");
        IdentityEntity deleted = identity("id_2", "game_1");
        deleted.deletedAt = LocalDateTime.now();

        lenient().when(identityRepo.findById("id_1")).thenReturn(Optional.of(e));
        lenient().when(identityRepo.findById("id_2")).thenReturn(Optional.of(deleted));

        assertTrue(identityService.getIdentity("game_1", "id_1").isPresent());
        assertTrue(identityService.getIdentity(null, "id_1").isPresent());
        assertFalse(identityService.getIdentity("game_2", "id_1").isPresent());
        assertFalse(identityService.getIdentity("game_1", "id_2").isPresent());
        assertFalse(identityService.getIdentity("game_1", "ghost").isPresent());
    }

    @Test
    @DisplayName("findByDevice/findByPlayer/findByUser/getLinks：透传仓库查询")
    void identityQueries_Delegate() {
        IdentityEntity e = identity("id_1", "game_1");
        IdentityLinkEntity l = link("l_1", "id_1", "device_id", "dev_1");

        lenient().when(identityRepo.findByDeviceId("game_1", "dev_1")).thenReturn(Optional.of(e));
        lenient().when(identityRepo.findByPlayerId("game_1", "p_id_1")).thenReturn(Optional.of(e));
        lenient().when(identityRepo.findByUserId("game_1", "u_id_1")).thenReturn(List.of(e));
        lenient().when(identityLinkRepo.findByIdentityId("id_1")).thenReturn(List.of(l));

        assertEquals(Optional.of(e), identityService.findByDevice("game_1", "dev_1"));
        assertEquals(Optional.of(e), identityService.findByPlayer("game_1", "p_id_1"));
        assertEquals(List.of(e), identityService.findByUser("game_1", "u_id_1"));
        assertEquals(List.of(l), identityService.getLinks("id_1"));
    }

    @Test
    @DisplayName("findByIdentifier：去重 + gameId 不匹配/已删除过滤")
    void findByIdentifier_DedupAndFilters() {
        IdentityEntity main = identity("id_1", "game_1");
        IdentityEntity otherGame = identity("id_2", "game_2");
        IdentityEntity deleted = identity("id_3", "game_1");
        deleted.deletedAt = LocalDateTime.now();

        IdentityLinkEntity linkMain1 = link("l_1", "id_1", "device_id", "dev_1");
        IdentityLinkEntity linkMain2 = link("l_2", "id_1", "user_id", "dev_1");
        IdentityLinkEntity linkOther = link("l_3", "id_2", "player_id", "dev_1");
        IdentityLinkEntity linkDeleted = link("l_4", "id_3", "character_id", "dev_1");

        lenient().when(identityLinkRepo.findByTypeAndId("device_id", "dev_1"))
            .thenReturn(List.of(linkMain1, linkMain2, linkOther, linkDeleted));
        lenient().when(identityRepo.findById("id_1")).thenReturn(Optional.of(main));
        lenient().when(identityRepo.findById("id_2")).thenReturn(Optional.of(otherGame));
        lenient().when(identityRepo.findById("id_3")).thenReturn(Optional.of(deleted));

        List<IdentityEntity> result = identityService.findByIdentifier("game_1", "device_id", "dev_1");
        assertEquals(1, result.size());
        assertSame(main, result.get(0));

        List<IdentityEntity> allGames = identityService.findByIdentifier(null, "device_id", "dev_1");
        assertEquals(2, allGames.size());
        assertTrue(allGames.contains(main));
        assertTrue(allGames.contains(otherGame));

        assertTrue(identityService.findByIdentifier("game_1", "device_id", "unknown").isEmpty());
    }

    // ==================== MailService ====================

    @Test
    @DisplayName("create：游戏不存在/标题缺失/内容缺失 拒绝")
    void create_ValidationFailures() {
        MailEntity noGame = mail(MailEntity.Scope.ALL, null);
        noGame.gameId = "ghost_game";
        lenient().when(gameRepo.findById("ghost_game")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> mailService.create(noGame, "op_1"));

        lenient().when(gameRepo.findById("game_1")).thenReturn(Optional.of(game()));
        MailEntity noTitle = mail(MailEntity.Scope.ALL, null);
        noTitle.title = " ";
        assertThrows(IllegalArgumentException.class, () -> mailService.create(noTitle, "op_1"));

        MailEntity noContent = mail(MailEntity.Scope.ALL, null);
        noContent.content = "";
        assertThrows(IllegalArgumentException.class, () -> mailService.create(noContent, "op_1"));

        verify(mailRepo, never()).save(any());
    }

    @Test
    @DisplayName("create：个人邮件成功，标题去除首尾空白")
    void create_IndividualMailTrimsTitle() {
        lenient().when(gameRepo.findById("game_1")).thenReturn(Optional.of(game()));
        lenient().when(mailRepo.save(any(MailEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        MailEntity m = mail(MailEntity.Scope.INDIVIDUAL, "alice, bob");
        m.title = "  补偿邮件  ";

        MailEntity created = mailService.create(m, "op_1");

        assertEquals("补偿邮件", created.title);
        assertEquals(MailEntity.Status.DRAFT, created.status);
        assertEquals("op_1", created.createdBy);
        assertTrue(created.id.startsWith("mail_"));
    }

    @Test
    @DisplayName("send：草稿发送成功；邮件不存在/已过期 拒绝")
    void send_SuccessAndFailures() {
        MailEntity draft = mail(MailEntity.Scope.ALL, null);
        draft.id = "mail_1";
        lenient().when(mailRepo.findByIdAndDeletedAtIsNull("mail_1")).thenReturn(Optional.of(draft));
        lenient().when(mailRepo.save(any(MailEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        MailEntity sent = mailService.send("mail_1", "op_1");
        assertEquals(MailEntity.Status.SENT, sent.status);
        assertNotNull(sent.sentAt);
        verify(auditLog).logUpdate(eq("op_mail"), eq("mail_1"), eq("标题"),
            eq("op_1"), eq("op_1"), isNull(), anyMap());

        lenient().when(mailRepo.findByIdAndDeletedAtIsNull("ghost")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> mailService.send("ghost", "op_1"));

        MailEntity expiredDraft = mail(MailEntity.Scope.ALL, null);
        expiredDraft.id = "mail_2";
        expiredDraft.expireAt = LocalDateTime.now().minusMinutes(1);
        lenient().when(mailRepo.findByIdAndDeletedAtIsNull("mail_2")).thenReturn(Optional.of(expiredDraft));
        assertThrows(IllegalStateException.class, () -> mailService.send("mail_2", "op_1"));
    }

    @Test
    @DisplayName("delete：不存在返回 false；已发送拒绝；草稿软删除成功")
    void delete_Branches() {
        lenient().when(mailRepo.findByIdAndDeletedAtIsNull("ghost")).thenReturn(Optional.empty());
        assertFalse(mailService.delete("ghost", "op_1"));

        MailEntity sent = mail(MailEntity.Scope.ALL, null);
        sent.id = "mail_1";
        sent.status = MailEntity.Status.SENT;
        lenient().when(mailRepo.findByIdAndDeletedAtIsNull("mail_1")).thenReturn(Optional.of(sent));
        assertThrows(IllegalStateException.class, () -> mailService.delete("mail_1", "op_1"));

        MailEntity draft = mail(MailEntity.Scope.ALL, null);
        draft.id = "mail_2";
        lenient().when(mailRepo.findByIdAndDeletedAtIsNull("mail_2")).thenReturn(Optional.of(draft));
        assertTrue(mailService.delete("mail_2", "op_1"));
        assertNotNull(draft.deletedAt);
        verify(auditLog).logDelete(eq("op_mail"), eq("mail_2"), eq("标题"), eq("op_1"), eq("op_1"), isNull());
    }

    @Test
    @DisplayName("list/get：游戏校验与软删除过滤")
    void listAndGet() {
        MailEntity m = mail(MailEntity.Scope.ALL, null);
        m.id = "mail_1";
        lenient().when(gameRepo.findById("game_1")).thenReturn(Optional.of(game()));
        lenient().when(gameRepo.findById("ghost_game")).thenReturn(Optional.empty());
        lenient().when(mailRepo.findByGameIdAndDeletedAtIsNullOrderByCreatedAtDesc("game_1"))
            .thenReturn(List.of(m));
        lenient().when(mailRepo.findByIdAndDeletedAtIsNull("mail_1")).thenReturn(Optional.of(m));

        assertEquals(List.of(m), mailService.list("game_1"));
        assertThrows(IllegalArgumentException.class, () -> mailService.list("ghost_game"));
        assertSame(m, mailService.get("mail_1"));
        assertNull(mailService.get("ghost"));
    }

    @Test
    @DisplayName("inbox：游戏校验/playerKey 必填/精确收件人过滤防子串误命中")
    void inbox_ValidationAndExactFilter() {
        lenient().when(gameRepo.findById("game_1")).thenReturn(Optional.of(game()));
        lenient().when(gameRepo.findById("ghost_game")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> mailService.inbox("ghost_game", null, "alice"));
        assertThrows(IllegalArgumentException.class, () -> mailService.inbox("game_1", null, "  "));

        MailEntity exact = mail(MailEntity.Scope.INDIVIDUAL, "alice, bob");
        exact.id = "mail_1";
        exact.status = MailEntity.Status.SENT;
        MailEntity substring = mail(MailEntity.Scope.INDIVIDUAL, "malice");
        substring.id = "mail_2";
        substring.status = MailEntity.Status.SENT;

        lenient().when(mailRepo.findInbox(eq("game_1"), eq(""), eq("alice"), any(LocalDateTime.class)))
            .thenReturn(List.of(exact, substring));

        List<MailEntity> inbox = mailService.inbox("game_1", null, "alice");

        assertEquals(1, inbox.size());
        assertSame(exact, inbox.get(0));
    }

    @Test
    @DisplayName("claim：playerKey 必填、邮件不存在拒绝")
    void claim_ValidationFailures() {
        assertThrows(IllegalArgumentException.class, () -> mailService.claim("mail_1", "  "));

        lenient().when(claimRepo.findByMailIdAndPlayerKey("ghost", "p")).thenReturn(Optional.empty());
        lenient().when(mailRepo.findByIdAndDeletedAtIsNull("ghost")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> mailService.claim("ghost", "p"));
        verify(claimRepo, never()).save(any());
    }

    @Test
    @DisplayName("claim：草稿邮件不可领取返回 null")
    void claim_DraftMailReturnsNull() {
        MailEntity draft = mail(MailEntity.Scope.ALL, null);
        draft.id = "mail_1";
        draft.status = MailEntity.Status.DRAFT;

        lenient().when(claimRepo.findByMailIdAndPlayerKey("mail_1", "p")).thenReturn(Optional.empty());
        lenient().when(mailRepo.findByIdAndDeletedAtIsNull("mail_1")).thenReturn(Optional.of(draft));

        assertNull(mailService.claim("mail_1", "p"));
        verify(claimRepo, never()).save(any());
    }

    @Test
    @DisplayName("claim：已删除邮件不可领取返回 null")
    void claim_DeletedMailReturnsNull() {
        MailEntity deleted = mail(MailEntity.Scope.ALL, null);
        deleted.id = "mail_1";
        deleted.status = MailEntity.Status.SENT;
        deleted.deletedAt = LocalDateTime.now();

        lenient().when(claimRepo.findByMailIdAndPlayerKey("mail_1", "p")).thenReturn(Optional.empty());
        lenient().when(mailRepo.findByIdAndDeletedAtIsNull("mail_1")).thenReturn(Optional.of(deleted));

        assertNull(mailService.claim("mail_1", "p"));
    }
}
