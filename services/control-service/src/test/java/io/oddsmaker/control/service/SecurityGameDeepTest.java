package io.oddsmaker.control.service;

import io.oddsmaker.control.dto.EnvironmentDTO;
import io.oddsmaker.control.dto.GameDTO;
import io.oddsmaker.control.jpa.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 安全与游戏域 Service 深度单元测试（纯 Mockito，不启动 Spring 上下文）。
 * 与 GameServiceTest / DashboardServicesTest 互补：覆盖 MFA/SSO/会话、环境管理、
 * 发布状态流转、健康检查、告警、管道执行与风控大屏的非空数据行分支。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("安全与游戏域 Service 深度测试")
class SecurityGameDeepTest {

    // ===== 共享 Mock =====

    @Mock
    private AuditLogService auditLog;

    // ===== SecurityService =====

    @Mock
    private MFAConfigRepo mfaConfigRepo;

    @Mock
    private SSOConfigRepo ssoConfigRepo;

    @Mock
    private SecuritySessionRepo securitySessionRepo;

    @Mock
    private SecurityPolicyRepo securityPolicyRepo;

    @InjectMocks
    private SecurityService securityService;

    // ===== GameService =====

    @Mock
    private GameRepo gameRepo;

    @Mock
    private GameEnvironmentRepo gameEnvironmentRepo;

    @Mock
    private ApiKeyRepo apiKeyRepo;

    @Mock
    private StorageProfileRepo storageProfileRepo;

    @InjectMocks
    private GameService gameService;

    // ===== HealthMonitorService =====

    @Mock
    private HealthCheckRepo healthCheckRepo;

    @Mock
    private HealthMetricRepo healthMetricRepo;

    @Mock
    private SystemAlertRepo systemAlertRepo;

    @InjectMocks
    private HealthMonitorService healthMonitorService;

    // ===== PipelineService =====

    @Mock
    private PipelineRepo pipelineRepo;

    @Mock
    private PipelineJobRepo pipelineJobRepo;

    @Mock
    private DataQualityRuleRepo dataQualityRuleRepo;

    @InjectMocks
    private PipelineService pipelineService;

    // ===== RiskDashboardService =====

    @Mock
    private RiskCaseRepo riskCaseRepo;

    @Mock
    private RiskRuleRepo riskRuleRepo;

    @Mock
    private BlockListRepo blockListRepo;

    @Mock
    private FlinkJobRepo flinkJobRepo;

    @InjectMocks
    private RiskDashboardService riskDashboardService;

    // =========================================================
    // SecurityService
    // =========================================================

    @Test
    @DisplayName("enableMFA：TOTP 首次开通生成密钥与二维码并处于 PENDING")
    void enableMfaTotpGeneratesSecretAndQr() {
        lenient().when(mfaConfigRepo.findByUserIdAndMethod("u1", MFAConfigEntity.MFAMethod.TOTP))
            .thenReturn(Optional.empty());
        lenient().when(mfaConfigRepo.save(any(MFAConfigEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        MFAConfigEntity config = securityService.enableMFA(
            "u1", MFAConfigEntity.MFAMethod.TOTP, null, "u1@x.io");

        assertTrue(config.id.startsWith("mfa_"));
        assertEquals(MFAConfigEntity.MFAStatus.PENDING, config.mfaStatus);
        assertEquals("u1@x.io", config.emailAddress);
        assertNotNull(config.secretKey);
        assertEquals(16, config.secretKey.length());
        assertTrue(config.qrCodeUrl.startsWith("otpauth://totp/Oddsmaker:u1?"));
    }

    @Test
    @DisplayName("enableMFA：已启用的方法不可重复开通")
    void enableMfaRejectsAlreadyEnabledMethod() {
        MFAConfigEntity existing = new MFAConfigEntity();
        existing.mfaMethod = MFAConfigEntity.MFAMethod.TOTP;
        existing.mfaStatus = MFAConfigEntity.MFAStatus.ENABLED;
        lenient().when(mfaConfigRepo.findByUserIdAndMethod("u1", MFAConfigEntity.MFAMethod.TOTP))
            .thenReturn(Optional.of(existing));

        assertThrows(IllegalStateException.class,
            () -> securityService.enableMFA("u1", MFAConfigEntity.MFAMethod.TOTP, null, null));
        verify(mfaConfigRepo, never()).save(any());
    }

    @Test
    @DisplayName("enableMFA：已禁用的历史配置允许重新开通，SMS 不生成密钥")
    void enableMfaReEnrollsAfterDisableWithoutSecret() {
        MFAConfigEntity disabled = new MFAConfigEntity();
        disabled.mfaMethod = MFAConfigEntity.MFAMethod.SMS;
        disabled.mfaStatus = MFAConfigEntity.MFAStatus.DISABLED;
        lenient().when(mfaConfigRepo.findByUserIdAndMethod("u1", MFAConfigEntity.MFAMethod.SMS))
            .thenReturn(Optional.of(disabled));
        lenient().when(mfaConfigRepo.save(any(MFAConfigEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        MFAConfigEntity config = securityService.enableMFA(
            "u1", MFAConfigEntity.MFAMethod.SMS, "13800000000", null);

        assertEquals(MFAConfigEntity.MFAStatus.PENDING, config.mfaStatus);
        assertEquals("13800000000", config.phoneNumber);
        assertNull(config.secretKey);
        assertNull(config.qrCodeUrl);
    }

    @Test
    @DisplayName("verifyAndActivateMFA：配置不存在抛出 IllegalArgumentException")
    void verifyAndActivateMfaFailsOnMissingConfig() {
        lenient().when(mfaConfigRepo.findById("mfa_x")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
            () -> securityService.verifyAndActivateMFA("mfa_x", "123456"));
    }

    @Test
    @DisplayName("verifyAndActivateMFA：非 PENDING 状态拒绝激活")
    void verifyAndActivateMfaRejectsNonPendingState() {
        MFAConfigEntity config = new MFAConfigEntity();
        config.id = "mfa_1";
        config.userId = "u1";
        config.mfaMethod = MFAConfigEntity.MFAMethod.TOTP;
        config.mfaStatus = MFAConfigEntity.MFAStatus.ENABLED;
        lenient().when(mfaConfigRepo.findById("mfa_1")).thenReturn(Optional.of(config));

        assertThrows(IllegalStateException.class,
            () -> securityService.verifyAndActivateMFA("mfa_1", "123456"));
    }

    @Test
    @DisplayName("verifyAndActivateMFA：验证通过后启用并标记为主要方法")
    void verifyAndActivateMfaSuccessMarksPrimary() {
        MFAConfigEntity config = new MFAConfigEntity();
        config.id = "mfa_1";
        config.userId = "u1";
        config.mfaMethod = MFAConfigEntity.MFAMethod.TOTP;
        config.mfaStatus = MFAConfigEntity.MFAStatus.PENDING;
        lenient().when(mfaConfigRepo.findById("mfa_1")).thenReturn(Optional.of(config));
        lenient().when(mfaConfigRepo.findByUserId("u1")).thenReturn(List.of(config));
        lenient().when(mfaConfigRepo.save(any(MFAConfigEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        MFAConfigEntity activated = securityService.verifyAndActivateMFA("mfa_1", "123456");

        assertEquals(MFAConfigEntity.MFAStatus.ENABLED, activated.mfaStatus);
        assertTrue(activated.isPrimary());
        assertNotNull(activated.enrolledAt);
        verify(auditLog).logCreate("mfa_config", "mfa_1", "TOTP", "u1",
            (String) null, (String) null, (String) null);
    }

    @Test
    @DisplayName("verifyAndActivateMFA：连续验证失败达上限后锁定配置")
    void verifyAndActivateMfaLocksAfterRepeatedFailures() {
        MFAConfigEntity config = new MFAConfigEntity();
        config.id = "mfa_2";
        config.userId = "u1";
        config.mfaMethod = MFAConfigEntity.MFAMethod.TOTP;
        config.mfaStatus = MFAConfigEntity.MFAStatus.PENDING;
        config.verificationAttempts = 2;
        lenient().when(mfaConfigRepo.findById("mfa_2")).thenReturn(Optional.of(config));
        lenient().when(mfaConfigRepo.save(any(MFAConfigEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        assertThrows(IllegalArgumentException.class,
            () -> securityService.verifyAndActivateMFA("mfa_2", "bad-code"));

        assertEquals(3, config.verificationAttempts);
        assertEquals(MFAConfigEntity.MFAStatus.LOCKED, config.mfaStatus);
        verify(mfaConfigRepo, times(2)).save(config);
    }

    @Test
    @DisplayName("verifyMFA：有效六位数字码验证通过并记录使用时间")
    void verifyMfaSucceedsWithSixDigitCode() {
        MFAConfigEntity enabled = new MFAConfigEntity();
        enabled.mfaMethod = MFAConfigEntity.MFAMethod.TOTP;
        enabled.mfaStatus = MFAConfigEntity.MFAStatus.ENABLED;
        lenient().when(mfaConfigRepo.findEnabledByUserId("u1")).thenReturn(List.of(enabled));
        lenient().when(mfaConfigRepo.save(any(MFAConfigEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        assertTrue(securityService.verifyMFA("u1", "654321"));
        assertNotNull(enabled.lastUsedAt);
        assertNotNull(enabled.lastVerifiedAt);
        verify(mfaConfigRepo).save(enabled);
    }

    @Test
    @DisplayName("verifyMFA：格式错误的验证码返回 false 且不落库")
    void verifyMfaFailsWithMalformedCode() {
        MFAConfigEntity enabled = new MFAConfigEntity();
        enabled.mfaMethod = MFAConfigEntity.MFAMethod.SMS;
        enabled.mfaStatus = MFAConfigEntity.MFAStatus.ENABLED;
        lenient().when(mfaConfigRepo.findEnabledByUserId("u1")).thenReturn(List.of(enabled));

        assertFalse(securityService.verifyMFA("u1", "12"));
        verify(mfaConfigRepo, never()).save(any());
    }

    @Test
    @DisplayName("verifyMFA：无任何启用配置时返回 false")
    void verifyMfaFailsWithoutConfigs() {
        lenient().when(mfaConfigRepo.findEnabledByUserId("u2")).thenReturn(List.of());

        assertFalse(securityService.verifyMFA("u2", "123456"));
    }

    @Test
    @DisplayName("createSSOConfig：SAML2 协议映射 IdP/SP 字段并记录审计")
    void createSsoConfigMapsSamlFields() {
        lenient().when(ssoConfigRepo.save(any(SSOConfigEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> cfg = new HashMap<>();
        cfg.put("idpEntityId", "idp-eid");
        cfg.put("idpSsoUrl", "https://idp/sso");
        cfg.put("idpCert", "cert-data");
        cfg.put("spEntityId", "sp-eid");
        cfg.put("acsUrl", "https://sp/acs");

        SSOConfigEntity sso = securityService.createSSOConfig(
            "Okta SAML", "corp sso", SSOConfigEntity.SSOProtocol.SAML2, cfg, "admin");

        assertTrue(sso.id.startsWith("sso_"));
        assertEquals(SSOConfigEntity.SSOStatus.DISABLED, sso.ssoStatus);
        assertEquals("idp-eid", sso.samlIdpEntityId);
        assertEquals("https://idp/sso", sso.samlIdpSsoUrl);
        assertEquals("cert-data", sso.samlIdpCert);
        assertEquals("sp-eid", sso.samlSpEntityId);
        assertEquals("https://sp/acs", sso.samlAcsUrl);
        assertNull(sso.oauthClientId);
        verify(auditLog).logCreate(eq("sso_config"), anyString(), eq("Okta SAML"),
            eq("admin"), eq("admin"), isNull(), anyMap());
    }

    @Test
    @DisplayName("createSSOConfig：OIDC 协议映射 OAuth 字段")
    void createSsoConfigMapsOidcFields() {
        lenient().when(ssoConfigRepo.save(any(SSOConfigEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> cfg = new HashMap<>();
        cfg.put("provider", "google");
        cfg.put("clientId", "cid");
        cfg.put("clientSecret", "secret");
        cfg.put("authorizationUrl", "https://google/auth");
        cfg.put("tokenUrl", "https://google/token");
        cfg.put("scopes", "openid email");
        cfg.put("callbackUrl", "https://sp/cb");

        SSOConfigEntity sso = securityService.createSSOConfig(
            "Google OIDC", null, SSOConfigEntity.SSOProtocol.OIDC, cfg, "admin");

        assertEquals("google", sso.oauthProvider);
        assertEquals("cid", sso.oauthClientId);
        assertEquals("secret", sso.oauthClientSecret);
        assertEquals("https://google/auth", sso.oauthAuthorizationUrl);
        assertEquals("https://google/token", sso.oauthTokenUrl);
        assertEquals("openid email", sso.oauthScopes);
        assertEquals("https://sp/cb", sso.oauthCallbackUrl);
        assertNull(sso.samlIdpEntityId);
    }

    @Test
    @DisplayName("createSSOConfig：CAS 协议不填充任何协议字段")
    void createSsoConfigCasLeavesProtocolFieldsEmpty() {
        lenient().when(ssoConfigRepo.save(any(SSOConfigEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        SSOConfigEntity sso = securityService.createSSOConfig(
            "CAS", null, SSOConfigEntity.SSOProtocol.CAS, Map.of(), "admin");

        assertNull(sso.samlIdpEntityId);
        assertNull(sso.oauthClientId);
        assertEquals(SSOConfigEntity.SSOStatus.DISABLED, sso.ssoStatus);
    }

    @Test
    @DisplayName("activateSSO：配置不存在抛出异常，存在则置为 ACTIVE")
    void activateSsoTogglesStatus() {
        lenient().when(ssoConfigRepo.findById("sso_missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> securityService.activateSSO("sso_missing"));

        SSOConfigEntity sso = new SSOConfigEntity();
        sso.id = "sso_1";
        sso.ssoStatus = SSOConfigEntity.SSOStatus.DISABLED;
        sso.errorMessage = "old error";
        lenient().when(ssoConfigRepo.findById("sso_1")).thenReturn(Optional.of(sso));
        lenient().when(ssoConfigRepo.save(any(SSOConfigEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        SSOConfigEntity activated = securityService.activateSSO("sso_1");

        assertEquals(SSOConfigEntity.SSOStatus.ACTIVE, activated.ssoStatus);
        assertNull(activated.errorMessage);
    }

    @Test
    @DisplayName("createSession：生成令牌/指纹/过期时间")
    void createSessionGeneratesTokenAndFingerprint() {
        lenient().when(securitySessionRepo.save(any(SecuritySessionEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        SecuritySessionEntity session = securityService.createSession(
            "u1", SecuritySessionEntity.AuthMethod.SSO, "10.0.0.1", "Mozilla/5.0", 30);

        assertTrue(session.id.startsWith("sess_"));
        assertEquals(64, session.sessionToken.length());
        assertEquals(SecuritySessionEntity.SessionStatus.ACTIVE, session.sessionStatus);
        assertEquals(SecuritySessionEntity.AuthMethod.SSO, session.authMethod);
        assertNotNull(session.deviceFingerprint);
        assertNotNull(session.expiresAt);
        assertTrue(session.expiresAt.isAfter(session.loginAt));
        assertTrue(session.isActive());
    }

    @Test
    @DisplayName("validateSession：令牌不存在/已终止/已过期均返回 null")
    void validateSessionReturnsNullForInvalidStates() {
        lenient().when(securitySessionRepo.findByToken("t_missing")).thenReturn(Optional.empty());
        assertNull(securityService.validateSession("t_missing"));

        SecuritySessionEntity terminated = new SecuritySessionEntity();
        terminated.sessionStatus = SecuritySessionEntity.SessionStatus.TERMINATED;
        lenient().when(securitySessionRepo.findByToken("t_terminated")).thenReturn(Optional.of(terminated));
        assertNull(securityService.validateSession("t_terminated"));

        SecuritySessionEntity expired = new SecuritySessionEntity();
        expired.sessionStatus = SecuritySessionEntity.SessionStatus.ACTIVE;
        expired.expiresAt = LocalDateTime.now().minusMinutes(5);
        lenient().when(securitySessionRepo.findByToken("t_expired")).thenReturn(Optional.of(expired));
        assertNull(securityService.validateSession("t_expired"));

        verify(securitySessionRepo, never()).save(any());
    }

    @Test
    @DisplayName("validateSession：活跃会话刷新活动时间并保存")
    void validateSessionRefreshesActiveSession() {
        SecuritySessionEntity active = new SecuritySessionEntity();
        active.sessionStatus = SecuritySessionEntity.SessionStatus.ACTIVE;
        active.expiresAt = LocalDateTime.now().plusMinutes(10);
        active.lastActivityAt = LocalDateTime.now().minusMinutes(9);
        lenient().when(securitySessionRepo.findByToken("t_ok")).thenReturn(Optional.of(active));
        lenient().when(securitySessionRepo.save(any(SecuritySessionEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        SecuritySessionEntity result = securityService.validateSession("t_ok");

        assertSame(active, result);
        assertTrue(result.lastActivityAt.isAfter(LocalDateTime.now().minusMinutes(1)));
        verify(securitySessionRepo).save(active);
    }

    @Test
    @DisplayName("terminateSession：不存在抛异常，存在则置 TERMINATED")
    void terminateSessionById() {
        lenient().when(securitySessionRepo.findById("sess_missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
            () -> securityService.terminateSession("sess_missing", "admin", "cleanup"));

        SecuritySessionEntity session = new SecuritySessionEntity();
        session.id = "sess_1";
        session.sessionStatus = SecuritySessionEntity.SessionStatus.ACTIVE;
        lenient().when(securitySessionRepo.findById("sess_1")).thenReturn(Optional.of(session));
        lenient().when(securitySessionRepo.save(any(SecuritySessionEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        securityService.terminateSession("sess_1", "admin", "policy violation");

        assertEquals(SecuritySessionEntity.SessionStatus.TERMINATED, session.sessionStatus);
        assertEquals("admin", session.terminatedBy);
        assertEquals("policy violation", session.terminationReason);
        assertNotNull(session.terminatedAt);
    }

    @Test
    @DisplayName("terminateAllUserSessions：批量终止用户全部活跃会话")
    void terminateAllUserSessions() {
        SecuritySessionEntity s1 = new SecuritySessionEntity();
        s1.sessionStatus = SecuritySessionEntity.SessionStatus.ACTIVE;
        SecuritySessionEntity s2 = new SecuritySessionEntity();
        s2.sessionStatus = SecuritySessionEntity.SessionStatus.ACTIVE;
        lenient().when(securitySessionRepo.findActiveByUserId("u1")).thenReturn(List.of(s1, s2));
        lenient().when(securitySessionRepo.save(any(SecuritySessionEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        securityService.terminateAllUserSessions("u1", "admin", "logout-all");

        assertEquals(SecuritySessionEntity.SessionStatus.TERMINATED, s1.sessionStatus);
        assertEquals(SecuritySessionEntity.SessionStatus.TERMINATED, s2.sessionStatus);
        verify(securitySessionRepo, times(2)).save(any(SecuritySessionEntity.class));
    }

    @Test
    @DisplayName("cleanupExpiredSessions：过期会话置 EXPIRED，空闲会话置 REVOKED")
    void cleanupExpiredSessionsMarksStatuses() {
        SecuritySessionEntity expired = new SecuritySessionEntity();
        expired.sessionStatus = SecuritySessionEntity.SessionStatus.ACTIVE;
        SecuritySessionEntity idle = new SecuritySessionEntity();
        idle.sessionStatus = SecuritySessionEntity.SessionStatus.ACTIVE;
        lenient().when(securitySessionRepo.findExpired(any(LocalDateTime.class))).thenReturn(List.of(expired));
        lenient().when(securitySessionRepo.findIdleExpired(any(LocalDateTime.class))).thenReturn(List.of(idle));
        lenient().when(securitySessionRepo.save(any(SecuritySessionEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        assertDoesNotThrow(() -> securityService.cleanupExpiredSessions());

        assertEquals(SecuritySessionEntity.SessionStatus.EXPIRED, expired.sessionStatus);
        assertEquals(SecuritySessionEntity.SessionStatus.REVOKED, idle.sessionStatus);
        assertEquals("Idle timeout", idle.terminationReason);
        verify(securitySessionRepo, times(2)).save(any(SecuritySessionEntity.class));
    }

    @Test
    @DisplayName("deleteOldSessions：删除30天前的过期会话")
    void deleteOldSessionsInvokesRepo() {
        lenient().when(securitySessionRepo.deleteExpired(any(LocalDateTime.class))).thenReturn(3);

        assertDoesNotThrow(() -> securityService.deleteOldSessions());

        verify(securitySessionRepo).deleteExpired(any(LocalDateTime.class));
    }

    @Test
    @DisplayName("MFA/策略查询：读取接口直通 Repo")
    void securityPolicyAndMfaQueries() {
        MFAConfigEntity cfg = new MFAConfigEntity();
        lenient().when(mfaConfigRepo.findByUserId("u1")).thenReturn(List.of(cfg));
        lenient().when(mfaConfigRepo.isMFAEnabledForUser("u1")).thenReturn(true);
        lenient().when(securityPolicyRepo.findPasswordPolicyForGame("g1")).thenReturn(List.of());
        lenient().when(securityPolicyRepo.findSessionPolicyForGame("g1")).thenReturn(List.of());
        lenient().when(securityPolicyRepo.findMFAPolicyForGame("g1")).thenReturn(List.of());
        lenient().when(securityPolicyRepo.isMFARequiredForGame("g1")).thenReturn(true);

        assertTrue(securityService.isUserMFAEnabled("u1"));
        assertEquals(1, securityService.getUserMFAConfigs("u1").size());
        assertTrue(securityService.getPasswordPolicies("g1").isEmpty());
        assertTrue(securityService.getSessionPolicies("g1").isEmpty());
        assertTrue(securityService.getMFAPolicies("g1").isEmpty());
        assertTrue(securityService.isMFARequired("g1"));
    }

    // =========================================================
    // GameService（与 GameServiceTest 互补）
    // =========================================================

    @Test
    @DisplayName("createGame：指定 ID 已存在时拒绝")
    void createGameRejectsDuplicateId() {
        GameDTO dto = new GameDTO();
        dto.id = "game_dup";
        dto.name = "Dup";
        lenient().when(gameRepo.existsById("game_dup")).thenReturn(true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> gameService.createGame(dto));
        assertTrue(ex.getMessage().contains("already exists"));
        verify(gameRepo, never()).save(any());
    }

    @Test
    @DisplayName("createGame：复用调用方指定的 ID 并创建默认环境")
    void createGameKeepsCallerProvidedId() {
        GameDTO dto = new GameDTO();
        dto.id = "game_custom";
        dto.name = "Custom";
        lenient().when(gameRepo.existsById("game_custom")).thenReturn(false);
        lenient().when(gameRepo.save(any(GameEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(storageProfileRepo.existsById(anyString())).thenReturn(true);
        lenient().when(gameEnvironmentRepo.save(any(GameEnvironmentEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        GameDTO created = gameService.createGame(dto);

        assertEquals("game_custom", created.id);
        assertEquals(GameEntity.GameStatus.DEVELOPMENT, created.status);
        // 默认环境：dev / staging / prod
        ArgumentCaptor<GameEnvironmentEntity> envCaptor = ArgumentCaptor.forClass(GameEnvironmentEntity.class);
        verify(gameEnvironmentRepo, times(3)).save(envCaptor.capture());
        long devCount = envCaptor.getAllValues().stream()
            .filter(e -> e.type == GameEnvironmentEntity.EnvironmentType.DEVELOPMENT).count();
        long prodCount = envCaptor.getAllValues().stream()
            .filter(e -> e.type == GameEnvironmentEntity.EnvironmentType.PRODUCTION).count();
        assertEquals(1L, devCount);
        assertEquals(1L, prodCount);
    }

    @Test
    @DisplayName("getGame：未删除游戏回填统计，已删除游戏返回空")
    void getGameEnrichesStatisticsOrEmpty() {
        GameEntity existing = game("g1", GameEntity.GameStatus.TESTING);
        lenient().when(gameRepo.findById("g1")).thenReturn(Optional.of(existing));
        lenient().when(gameEnvironmentRepo.countByGameIdAndDeletedAtIsNull("g1")).thenReturn(2L);
        lenient().when(apiKeyRepo.countByGameIdAndStatus("g1", ApiKeyEntity.ApiKeyStatus.ACTIVE)).thenReturn(1L);

        Optional<GameDTO> found = gameService.getGame("g1");
        assertTrue(found.isPresent());
        assertEquals(2, found.get().totalEnvironments);
        assertEquals(1, found.get().totalApiKeys);

        GameEntity deleted = game("g2", GameEntity.GameStatus.DISCONTINUED);
        deleted.deletedAt = LocalDateTime.now();
        lenient().when(gameRepo.findById("g2")).thenReturn(Optional.of(deleted));
        assertTrue(gameService.getGame("g2").isEmpty());
    }

    @Test
    @DisplayName("getGameStatistics：空结果与聚合结果两条分支")
    void getGameStatisticsHandlesEmptyAndRow() {
        lenient().when(gameRepo.getGameStatistics()).thenReturn(List.of());
        Map<String, Object> empty = gameService.getGameStatistics();
        assertEquals(0L, empty.get("totalGames"));
        assertEquals(0L, empty.get("liveGames"));

        lenient().when(gameRepo.getGameStatistics()).thenReturn(List.<Object>of(Map.of(
            "totalGames", 3L, "liveGames", 1L, "devGames", 1L, "testGames", 1L, "multiplayerGames", 0L)));
        Map<String, Object> stats = gameService.getGameStatistics();
        assertEquals(3L, stats.get("totalGames"));
        assertEquals(1L, stats.get("liveGames"));
    }

    @Test
    @DisplayName("createEnvironment：默认值填充（归一化名称/显示名/存储路由/库名）")
    void createEnvironmentAppliesDefaults() {
        GameEntity existing = game("g1", GameEntity.GameStatus.DEVELOPMENT);
        lenient().when(gameRepo.findById("g1")).thenReturn(Optional.of(existing));
        lenient().when(gameEnvironmentRepo.findByGameIdAndNameAndDeletedAtIsNull("g1", "qa"))
            .thenReturn(List.of());
        lenient().when(storageProfileRepo.existsById(anyString())).thenReturn(true);
        lenient().when(gameEnvironmentRepo.save(any(GameEnvironmentEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        EnvironmentDTO dto = new EnvironmentDTO();
        dto.name = " QA ";

        EnvironmentDTO created = gameService.createEnvironment("g1", dto);

        assertEquals("env_g1_qa", created.id);
        assertEquals("qa", created.name);
        assertEquals("QA", created.displayName);
        assertEquals(GameEnvironmentEntity.EnvironmentType.TESTING, created.type);
        assertEquals("shared-nonprod", created.storageProfileId);
        assertEquals("g1_qa", created.dataNamespace);
        assertEquals("g1_qa", created.kafkaTopicPrefix);
        assertEquals("game_g1_qa", created.databaseName);
        verify(auditLog).logCreate(eq("environment"), eq("env_g1_qa"), eq("qa"),
            eq("api"), eq("api"), isNull(), anyMap());
    }

    @Test
    @DisplayName("createEnvironment：重名/空白名/未知存储路由均被拒绝")
    void createEnvironmentRejectsInvalidInput() {
        GameEntity existing = game("g1", GameEntity.GameStatus.DEVELOPMENT);
        lenient().when(gameRepo.findById("g1")).thenReturn(Optional.of(existing));

        EnvironmentDTO blank = new EnvironmentDTO();
        blank.name = "   ";
        assertThrows(IllegalArgumentException.class, () -> gameService.createEnvironment("g1", blank));

        lenient().when(gameEnvironmentRepo.findByGameIdAndNameAndDeletedAtIsNull("g1", "qa"))
            .thenReturn(List.of(environment("g1", "qa")));
        EnvironmentDTO dup = new EnvironmentDTO();
        dup.name = "qa";
        IllegalArgumentException dupEx = assertThrows(IllegalArgumentException.class,
            () -> gameService.createEnvironment("g1", dup));
        assertTrue(dupEx.getMessage().contains("already exists"));

        lenient().when(gameEnvironmentRepo.findByGameIdAndNameAndDeletedAtIsNull("g1", "prod"))
            .thenReturn(List.of());
        lenient().when(storageProfileRepo.existsById(anyString())).thenReturn(false);
        EnvironmentDTO badProfile = new EnvironmentDTO();
        badProfile.name = "prod";
        badProfile.storageProfileId = "ghost-profile";
        IllegalArgumentException profileEx = assertThrows(IllegalArgumentException.class,
            () -> gameService.createEnvironment("g1", badProfile));
        assertTrue(profileEx.getMessage().contains("Storage profile not found"));

        verify(gameEnvironmentRepo, never()).save(any());
    }

    @Test
    @DisplayName("listEnvironments：游戏存在返回 DTO，不存在抛异常")
    void listEnvironmentsRequiresGame() {
        GameEntity existing = game("g1", GameEntity.GameStatus.DEVELOPMENT);
        lenient().when(gameRepo.findById("g1")).thenReturn(Optional.of(existing));
        lenient().when(gameEnvironmentRepo.findByGameIdAndDeletedAtIsNull("g1"))
            .thenReturn(List.of(environment("g1", "qa")));

        List<EnvironmentDTO> envs = gameService.listEnvironments("g1");
        assertEquals(1, envs.size());
        assertEquals("qa", envs.get(0).name);

        lenient().when(gameRepo.findById("g_missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> gameService.listEnvironments("g_missing"));
    }

    @Test
    @DisplayName("updateEnvironment：更新采样率并校验存储路由")
    void updateEnvironmentAppliesChanges() {
        GameEnvironmentEntity existing = environment("g1", "qa");
        existing.sampleRate = 1.0;
        lenient().when(gameEnvironmentRepo.findByGameIdAndNameAndDeletedAtIsNull("g1", "qa"))
            .thenReturn(List.of(existing));
        lenient().when(storageProfileRepo.existsById("shared-nonprod")).thenReturn(true);
        lenient().when(gameEnvironmentRepo.save(any(GameEnvironmentEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        EnvironmentDTO dto = new EnvironmentDTO();
        dto.sampleRate = 0.25;

        EnvironmentDTO updated = gameService.updateEnvironment("g1", "qa", dto);

        assertEquals(0.25, updated.sampleRate);
        assertEquals("qa", updated.name);
        verify(auditLog).logUpdate(eq("environment"), eq("env_g1_qa"), eq("qa"),
            eq("api"), eq("api"), isNull(), anyMap());
    }

    @Test
    @DisplayName("updateEnvironment：环境不存在抛异常")
    void updateEnvironmentRejectsMissing() {
        lenient().when(gameEnvironmentRepo.findByGameIdAndNameAndDeletedAtIsNull("g1", "ghost"))
            .thenReturn(List.of());

        EnvironmentDTO dto = new EnvironmentDTO();
        dto.sampleRate = 0.5;
        assertThrows(IllegalArgumentException.class,
            () -> gameService.updateEnvironment("g1", "ghost", dto));
        verify(gameEnvironmentRepo, never()).save(any());
    }

    @Test
    @DisplayName("deleteEnvironment：被活跃 API Key 占用时拒绝删除")
    void deleteEnvironmentBlockedByActiveKey() {
        GameEntity existing = game("g1", GameEntity.GameStatus.DEVELOPMENT);
        lenient().when(gameRepo.findById("g1")).thenReturn(Optional.of(existing));
        GameEnvironmentEntity env = environment("g1", "qa");
        lenient().when(gameEnvironmentRepo.findByGameIdAndNameAndDeletedAtIsNull("g1", "qa"))
            .thenReturn(List.of(env));

        ApiKeyEntity key = new ApiKeyEntity();
        key.environmentId = env.id;
        lenient().when(apiKeyRepo.findByGameIdAndStatus("g1", ApiKeyEntity.ApiKeyStatus.ACTIVE))
            .thenReturn(List.of(key));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> gameService.deleteEnvironment("g1", "qa"));
        assertTrue(ex.getMessage().contains("API key"));
        verify(gameEnvironmentRepo, never()).save(any());
    }

    @Test
    @DisplayName("deleteEnvironment：软删除并置 INACTIVE")
    void deleteEnvironmentSoftDeletes() {
        GameEntity existing = game("g1", GameEntity.GameStatus.DEVELOPMENT);
        lenient().when(gameRepo.findById("g1")).thenReturn(Optional.of(existing));
        GameEnvironmentEntity env = environment("g1", "qa");
        lenient().when(gameEnvironmentRepo.findByGameIdAndNameAndDeletedAtIsNull("g1", "qa"))
            .thenReturn(List.of(env));

        ApiKeyEntity otherEnvKey = new ApiKeyEntity();
        otherEnvKey.environmentId = "env_g1_prod";
        lenient().when(apiKeyRepo.findByGameIdAndStatus("g1", ApiKeyEntity.ApiKeyStatus.ACTIVE))
            .thenReturn(List.of(otherEnvKey));
        lenient().when(gameEnvironmentRepo.save(any(GameEnvironmentEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        gameService.deleteEnvironment("g1", "qa");

        assertNotNull(env.deletedAt);
        assertEquals(GameEnvironmentEntity.EnvironmentStatus.INACTIVE, env.status);
        verify(auditLog).logDelete("environment", "env_g1_qa", "qa", "api", "api", null);
    }

    @Test
    @DisplayName("publishGame：缺版本号/已上线/不存在均被拒绝")
    void publishGameRejectsInvalidStates() {
        lenient().when(gameRepo.findById("g_none")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> gameService.publishGame("g_none"));

        GameEntity live = game("g_live", GameEntity.GameStatus.LIVE);
        lenient().when(gameRepo.findById("g_live")).thenReturn(Optional.of(live));
        assertThrows(IllegalStateException.class, () -> gameService.publishGame("g_live"));

        GameEntity noVersion = game("g_nov", GameEntity.GameStatus.TESTING);
        noVersion.platforms = Set.of(GameEntity.GamePlatform.WEB);
        lenient().when(gameRepo.findById("g_nov")).thenReturn(Optional.of(noVersion));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> gameService.publishGame("g_nov"));
        assertTrue(ex.getMessage().contains("version"));

        verify(gameRepo, never()).save(any());
    }

    @Test
    @DisplayName("publishGame：TESTING 游戏满足条件后上线并补发 releaseDate")
    void publishGameSetsLiveAndReleaseDate() {
        GameEntity ready = game("g1", GameEntity.GameStatus.TESTING);
        ready.platforms = Set.of(GameEntity.GamePlatform.WEB);
        ready.currentVersion = "1.0.0";
        lenient().when(gameRepo.findById("g1")).thenReturn(Optional.of(ready));
        lenient().when(gameRepo.save(any(GameEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        GameDTO published = gameService.publishGame("g1");

        assertEquals(GameEntity.GameStatus.LIVE, published.status);
        assertNotNull(published.releaseDate);
        verify(auditLog).logUpdate(eq("game"), eq("g1"), eq("Game-g1"),
            eq("api"), eq("api"), isNull(), anyMap());
    }

    @Test
    @DisplayName("unpublishGame：非 LIVE 拒绝，LIVE 转为 MAINTENANCE")
    void unpublishGameTogglesToMaintenance() {
        GameEntity dev = game("g_dev", GameEntity.GameStatus.DEVELOPMENT);
        lenient().when(gameRepo.findById("g_dev")).thenReturn(Optional.of(dev));
        assertThrows(IllegalStateException.class, () -> gameService.unpublishGame("g_dev"));

        GameEntity live = game("g1", GameEntity.GameStatus.LIVE);
        lenient().when(gameRepo.findById("g1")).thenReturn(Optional.of(live));
        lenient().when(gameRepo.save(any(GameEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        GameDTO result = gameService.unpublishGame("g1");

        assertEquals(GameEntity.GameStatus.MAINTENANCE, result.status);
        verify(auditLog).logUpdate(eq("game"), eq("g1"), eq("Game-g1"),
            eq("api"), eq("api"), isNull(), anyMap());
    }

    @Test
    @DisplayName("deleteGame：游戏不存在抛异常")
    void deleteGameRejectsMissingGame() {
        lenient().when(gameRepo.findById("g_none")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> gameService.deleteGame("g_none"));
        verify(gameRepo, never()).save(any());
    }

    // =========================================================
    // HealthMonitorService
    // =========================================================

    @Test
    @DisplayName("performHealthCheck：检查项不存在抛出异常")
    void performHealthCheckFailsOnMissingCheck() {
        lenient().when(healthCheckRepo.findByName("ghost")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
            () -> healthMonitorService.performHealthCheck("ghost"));
    }

    @Test
    @DisplayName("performHealthCheck：记录响应时间/检查次数与状态")
    void performHealthCheckRecordsResult() {
        HealthCheckEntity check = new HealthCheckEntity();
        check.checkName = "primary-db";
        check.checkType = HealthCheckEntity.CheckType.DATABASE;
        lenient().when(healthCheckRepo.findByName("primary-db")).thenReturn(Optional.of(check));
        lenient().when(healthCheckRepo.save(any(HealthCheckEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        HealthCheckEntity result = healthMonitorService.performHealthCheck("primary-db");

        assertEquals(1, result.totalChecks);
        assertNotNull(result.responseTimeMs);
        assertNotNull(result.lastCheckedAt);
        assertNotNull(result.statusMessage);
        assertTrue(result.healthStatus == HealthCheckEntity.HealthStatus.HEALTHY
            || result.healthStatus == HealthCheckEntity.HealthStatus.UNHEALTHY);
        if (result.isUnhealthy()) {
            assertEquals(1, result.failedChecks.intValue());
            assertEquals(1, result.consecutiveFailures.intValue());
        }
    }

    @Test
    @DisplayName("collectMetric：CPU 正常值设置阈值且不产生告警")
    void collectMetricNormalValueDoesNotAlert() {
        lenient().when(healthMetricRepo.save(any(HealthMetricEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        HealthMetricEntity metric = healthMonitorService.collectMetric(
            HealthMetricEntity.MetricType.CPU_USAGE, "node-1", 50.0);

        assertTrue(metric.id.startsWith("hm_"));
        assertEquals("cpu_usage", metric.metricName);
        assertEquals("percent", metric.unit);
        assertEquals(70.0, metric.warningThreshold);
        assertEquals(90.0, metric.criticalThreshold);
        assertFalse(metric.isAnomaly);
        verify(systemAlertRepo, never()).save(any());
    }

    @Test
    @DisplayName("collectMetric：CPU 超阈值创建 CRITICAL 告警")
    void collectMetricCriticalCpuCreatesAlert() {
        lenient().when(healthMetricRepo.save(any(HealthMetricEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        HealthMetricEntity metric = healthMonitorService.collectMetric(
            HealthMetricEntity.MetricType.CPU_USAGE, "node-1", 95.5);

        assertTrue(metric.isAnomaly);
        assertTrue(metric.isCritical());

        ArgumentCaptor<SystemAlertEntity> captor = ArgumentCaptor.forClass(SystemAlertEntity.class);
        verify(systemAlertRepo).save(captor.capture());
        SystemAlertEntity alert = captor.getValue();
        assertTrue(alert.id.startsWith("alert_"));
        assertEquals(SystemAlertEntity.AlertType.HIGH_CPU, alert.alertType);
        assertEquals(SystemAlertEntity.Severity.CRITICAL, alert.severity);
        assertEquals(SystemAlertEntity.AlertStatus.OPEN, alert.alertStatus);
        assertEquals("node-1", alert.source);
        assertEquals(95.5, alert.metricValue);
        assertEquals(90.0, alert.thresholdValue);
        assertEquals("gt", alert.condition);
        assertTrue(alert.title.contains("CPU_USAGE"));
    }

    @Test
    @DisplayName("collectMetric：P95 延迟超阈值创建 SLOW_RESPONSE 告警")
    void collectMetricCriticalLatencyCreatesAlert() {
        lenient().when(healthMetricRepo.save(any(HealthMetricEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        HealthMetricEntity metric = healthMonitorService.collectMetric(
            HealthMetricEntity.MetricType.LATENCY_P95, "api", 1500.0);

        assertEquals("ms", metric.unit);
        assertTrue(metric.isAnomaly);

        ArgumentCaptor<SystemAlertEntity> captor = ArgumentCaptor.forClass(SystemAlertEntity.class);
        verify(systemAlertRepo).save(captor.capture());
        assertEquals(SystemAlertEntity.AlertType.SLOW_RESPONSE, captor.getValue().alertType);
    }

    @Test
    @DisplayName("collectMetric：无阈值类型走默认分支且不告警")
    void collectMetricDefaultTypeHasNoThreshold() {
        lenient().when(healthMetricRepo.save(any(HealthMetricEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        HealthMetricEntity metric = healthMonitorService.collectMetric(
            HealthMetricEntity.MetricType.REQUEST_RATE, "api", 42.0);

        assertEquals("count", metric.unit);
        assertNull(metric.criticalThreshold);
        assertFalse(metric.isAnomaly);
        verify(systemAlertRepo, never()).save(any());
    }

    @Test
    @DisplayName("acknowledgeAlert：不存在抛异常，存在则置 ACKNOWLEDGED")
    void acknowledgeAlertUpdatesStatus() {
        lenient().when(systemAlertRepo.findById("alert_missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
            () -> healthMonitorService.acknowledgeAlert("alert_missing", "ops", null));

        SystemAlertEntity alert = new SystemAlertEntity();
        alert.alertStatus = SystemAlertEntity.AlertStatus.OPEN;
        lenient().when(systemAlertRepo.findById("alert_1")).thenReturn(Optional.of(alert));
        lenient().when(systemAlertRepo.save(any(SystemAlertEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        SystemAlertEntity result = healthMonitorService.acknowledgeAlert("alert_1", "ops", "checking");

        assertEquals(SystemAlertEntity.AlertStatus.ACKNOWLEDGED, result.alertStatus);
        assertEquals("ops", result.acknowledgedBy);
        assertEquals("checking", result.acknowledgementComment);
        assertNotNull(result.acknowledgedAt);
    }

    @Test
    @DisplayName("resolveAlert：不存在抛异常，存在则置 RESOLVED")
    void resolveAlertUpdatesStatus() {
        lenient().when(systemAlertRepo.findById("alert_missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
            () -> healthMonitorService.resolveAlert("alert_missing", "ops", null));

        SystemAlertEntity alert = new SystemAlertEntity();
        alert.alertStatus = SystemAlertEntity.AlertStatus.ACKNOWLEDGED;
        lenient().when(systemAlertRepo.findById("alert_2")).thenReturn(Optional.of(alert));
        lenient().when(systemAlertRepo.save(any(SystemAlertEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        SystemAlertEntity result = healthMonitorService.resolveAlert("alert_2", "ops", "fixed");

        assertEquals(SystemAlertEntity.AlertStatus.RESOLVED, result.alertStatus);
        assertEquals("ops", result.resolvedBy);
        assertEquals("fixed", result.resolutionComment);
        assertNotNull(result.resolvedAt);
    }

    @Test
    @DisplayName("getSystemHealth：全部健康时整体 HEALTHY")
    void getSystemHealthAllHealthy() {
        HealthCheckEntity db = new HealthCheckEntity();
        db.checkName = "db";
        db.markAsHealthy("ok");
        HealthCheckEntity cache = new HealthCheckEntity();
        cache.checkName = "cache";
        cache.markAsHealthy("ok");
        lenient().when(healthCheckRepo.findEnabled()).thenReturn(List.of(db, cache));
        lenient().when(healthCheckRepo.findUnhealthy()).thenReturn(List.of());
        lenient().when(healthCheckRepo.findSlowResponses()).thenReturn(List.of());

        Map<String, Object> health = healthMonitorService.getSystemHealth();

        assertEquals(HealthCheckEntity.HealthStatus.HEALTHY, health.get("overallStatus"));
        assertEquals(2L, health.get("totalChecks"));
        assertEquals(2L, health.get("healthy"));
        assertEquals(100.0, (double) health.get("healthPercent"), 0.001);
        assertEquals(0, ((List<?>) health.get("unhealthyServices")).size());
    }

    @Test
    @DisplayName("getSystemHealth：存在不健康服务时整体 UNHEALTHY")
    void getSystemHealthUnhealthy() {
        HealthCheckEntity ok = new HealthCheckEntity();
        ok.checkName = "cache";
        ok.markAsHealthy("ok");
        HealthCheckEntity bad = new HealthCheckEntity();
        bad.checkName = "db";
        bad.markAsUnhealthy("down");
        lenient().when(healthCheckRepo.findEnabled()).thenReturn(List.of(ok));
        lenient().when(healthCheckRepo.findUnhealthy()).thenReturn(List.of(bad));
        lenient().when(healthCheckRepo.findSlowResponses()).thenReturn(List.of());

        Map<String, Object> health = healthMonitorService.getSystemHealth();

        assertEquals(HealthCheckEntity.HealthStatus.UNHEALTHY, health.get("overallStatus"));
        assertEquals(1L, health.get("unhealthy"));
        assertEquals(List.of("db"), health.get("unhealthyServices"));
    }

    @Test
    @DisplayName("getSystemHealth：慢响应导致整体 DEGRADED")
    void getSystemHealthDegradedBySlowResponses() {
        HealthCheckEntity ok = new HealthCheckEntity();
        ok.checkName = "db";
        ok.markAsHealthy("ok");
        HealthCheckEntity slow = new HealthCheckEntity();
        slow.checkName = "search";
        slow.markAsHealthy("slow but alive");
        lenient().when(healthCheckRepo.findEnabled()).thenReturn(List.of(ok));
        lenient().when(healthCheckRepo.findUnhealthy()).thenReturn(List.of());
        lenient().when(healthCheckRepo.findSlowResponses()).thenReturn(List.of(slow));

        Map<String, Object> health = healthMonitorService.getSystemHealth();

        assertEquals(HealthCheckEntity.HealthStatus.DEGRADED, health.get("overallStatus"));
        assertEquals(0, ((List<?>) health.get("unhealthyServices")).size());
        assertEquals(1, ((Number) health.get("slowResponses")).intValue());
    }

    @Test
    @DisplayName("getAlertStats：各状态与严重级别计数聚合")
    void getAlertStatsAggregatesCounts() {
        lenient().when(systemAlertRepo.countByStatus(SystemAlertEntity.AlertStatus.OPEN)).thenReturn(1L);
        lenient().when(systemAlertRepo.countByStatus(SystemAlertEntity.AlertStatus.ACKNOWLEDGED)).thenReturn(2L);
        lenient().when(systemAlertRepo.countByStatus(SystemAlertEntity.AlertStatus.INVESTIGATING)).thenReturn(3L);
        lenient().when(systemAlertRepo.countByStatus(SystemAlertEntity.AlertStatus.RESOLVED)).thenReturn(4L);
        lenient().when(systemAlertRepo.countActiveBySeverity(SystemAlertEntity.Severity.CRITICAL)).thenReturn(2L);
        lenient().when(systemAlertRepo.countActiveBySeverity(SystemAlertEntity.Severity.EMERGENCY)).thenReturn(1L);

        Map<String, Object> stats = healthMonitorService.getAlertStats();

        assertEquals(1L, stats.get("open"));
        assertEquals(2L, stats.get("acknowledged"));
        assertEquals(3L, stats.get("investigating"));
        assertEquals(4L, stats.get("resolved"));
        assertEquals(6L, stats.get("active"));
        assertEquals(3L, stats.get("highPriority"));
    }

    @Test
    @DisplayName("performScheduledHealthChecks：到期检查被执行，失败项被吞掉")
    void performScheduledHealthChecksProcessesDueChecks() {
        HealthCheckEntity due = new HealthCheckEntity();
        due.checkName = "db";
        due.checkType = HealthCheckEntity.CheckType.DATABASE;
        HealthCheckEntity ghost = new HealthCheckEntity();
        ghost.checkName = "ghost";
        ghost.checkType = HealthCheckEntity.CheckType.KAFKA;
        lenient().when(healthCheckRepo.findDueChecks(any(LocalDateTime.class)))
            .thenReturn(List.of(due, ghost));
        lenient().when(healthCheckRepo.findByName("db")).thenReturn(Optional.of(due));
        lenient().when(healthCheckRepo.findByName("ghost")).thenReturn(Optional.empty());
        lenient().when(healthCheckRepo.save(any(HealthCheckEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        assertDoesNotThrow(() -> healthMonitorService.performScheduledHealthChecks());

        assertEquals(1, due.totalChecks);
        assertNotNull(due.lastCheckedAt);
        verify(healthCheckRepo, times(1)).save(any(HealthCheckEntity.class));
    }

    @Test
    @DisplayName("collectSystemMetrics：一次采集五类指标")
    void collectSystemMetricsSavesFiveMetrics() {
        lenient().when(healthMetricRepo.save(any(HealthMetricEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        assertDoesNotThrow(() -> healthMonitorService.collectSystemMetrics());

        verify(healthMetricRepo, times(5)).save(any(HealthMetricEntity.class));
    }

    @Test
    @DisplayName("查询接口：健康检查/最近指标/活跃告警直通 Repo")
    void healthMonitorReadQueries() {
        HealthCheckEntity check = new HealthCheckEntity();
        check.checkName = "db";
        lenient().when(healthCheckRepo.findByName("db")).thenReturn(Optional.of(check));
        lenient().when(healthCheckRepo.findAll()).thenReturn(List.of(check));
        lenient().when(healthMetricRepo.findRecentByType(eq(HealthMetricEntity.MetricType.CPU_USAGE), any(LocalDateTime.class)))
            .thenReturn(List.of());
        SystemAlertEntity alert = new SystemAlertEntity();
        lenient().when(systemAlertRepo.findActive()).thenReturn(List.of(alert));

        assertThrows(IllegalArgumentException.class, () -> healthMonitorService.getHealthCheck("ghost"));
        assertEquals("db", healthMonitorService.getHealthCheck("db").checkName);
        assertEquals(1, healthMonitorService.getHealthChecks().size());
        assertTrue(healthMonitorService.getRecentMetrics(HealthMetricEntity.MetricType.CPU_USAGE, null).isEmpty());
        assertEquals(1, healthMonitorService.getActiveAlerts().size());
    }

    @Test
    @DisplayName("定期清理：过期指标与已关闭告警")
    void healthMonitorScheduledCleanups() {
        lenient().when(healthMetricRepo.deleteExpired(any(LocalDateTime.class))).thenReturn(5);
        lenient().when(systemAlertRepo.deleteClosedBefore(any(LocalDateTime.class))).thenReturn(2);

        assertDoesNotThrow(() -> healthMonitorService.cleanupExpiredMetrics());
        assertDoesNotThrow(() -> healthMonitorService.cleanupClosedAlerts());

        verify(healthMetricRepo).deleteExpired(any(LocalDateTime.class));
        verify(systemAlertRepo).deleteClosedBefore(any(LocalDateTime.class));
    }

    // =========================================================
    // PipelineService
    // =========================================================

    @Test
    @DisplayName("createPipeline：序列化三类配置并记录审计")
    void createPipelineSerializesConfigs() {
        lenient().when(pipelineRepo.save(any(PipelineEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        PipelineEntity pipeline = pipelineService.createPipeline(
            "g1", "env_g1_prod", "nightly-etl", PipelineEntity.PipelineType.ETL, "nightly job",
            Map.of("source", "kafka"), Map.of("op", "dedup"), Map.of("dest", "clickhouse"), "admin");

        assertTrue(pipeline.id.startsWith("pipe_"));
        assertEquals(PipelineEntity.PipelineStatus.DRAFT, pipeline.pipelineStatus);
        assertEquals("admin", pipeline.createdBy);
        assertTrue(pipeline.sourceConfig.contains("kafka"));
        assertTrue(pipeline.transformConfig.contains("dedup"));
        assertTrue(pipeline.destinationConfig.contains("clickhouse"));
        verify(auditLog).logCreate(eq("pipeline"), eq(pipeline.id), eq("nightly-etl"),
            eq("admin"), eq("admin"), isNull(), anyMap());
    }

    @Test
    @DisplayName("createPipeline：null 配置保持为空")
    void createPipelineWithNullConfigs() {
        lenient().when(pipelineRepo.save(any(PipelineEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        PipelineEntity pipeline = pipelineService.createPipeline(
            "g1", null, "empty", PipelineEntity.PipelineType.STREAMING, null,
            null, null, null, "admin");

        assertNull(pipeline.sourceConfig);
        assertNull(pipeline.transformConfig);
        assertNull(pipeline.destinationConfig);
        assertNull(pipeline.environmentId);
    }

    @Test
    @DisplayName("getPipeline：不存在抛出异常")
    void getPipelineRejectsMissing() {
        lenient().when(pipelineRepo.findById("pipe_missing")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> pipelineService.getPipeline("pipe_missing"));
    }

    @Test
    @DisplayName("activate/pause/stopPipeline：状态流转；不存在抛异常")
    void pipelineLifecycleTransitions() {
        PipelineEntity pipeline = new PipelineEntity();
        pipeline.id = "p1";
        pipeline.pipelineName = "etl";
        pipeline.pipelineStatus = PipelineEntity.PipelineStatus.DRAFT;
        lenient().when(pipelineRepo.findById("p1")).thenReturn(Optional.of(pipeline));
        lenient().when(pipelineRepo.save(any(PipelineEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        assertEquals(PipelineEntity.PipelineStatus.ACTIVE, pipelineService.activatePipeline("p1").pipelineStatus);
        assertEquals(PipelineEntity.PipelineStatus.PAUSED, pipelineService.pausePipeline("p1").pipelineStatus);
        assertEquals(PipelineEntity.PipelineStatus.STOPPED, pipelineService.stopPipeline("p1").pipelineStatus);

        lenient().when(pipelineRepo.findById("pipe_missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> pipelineService.activatePipeline("pipe_missing"));
    }

    @Test
    @DisplayName("executePipeline：不存在/草稿/禁用均被拒绝")
    void executePipelineRejectsInvalidStates() {
        lenient().when(pipelineRepo.findById("pipe_missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
            () -> pipelineService.executePipeline("pipe_missing", "tester"));

        PipelineEntity draft = activePipeline("p_draft");
        draft.pipelineStatus = PipelineEntity.PipelineStatus.DRAFT;
        lenient().when(pipelineRepo.findById("p_draft")).thenReturn(Optional.of(draft));
        IllegalStateException draftEx = assertThrows(IllegalStateException.class,
            () -> pipelineService.executePipeline("p_draft", "tester"));
        assertTrue(draftEx.getMessage().contains("not active"));

        PipelineEntity disabled = activePipeline("p_disabled");
        disabled.enabled = false;
        lenient().when(pipelineRepo.findById("p_disabled")).thenReturn(Optional.of(disabled));
        assertThrows(IllegalStateException.class,
            () -> pipelineService.executePipeline("p_disabled", "tester"));

        verify(pipelineJobRepo, never()).save(any());
    }

    @Test
    @DisplayName("executePipeline：活跃管道执行完成并评估质量规则")
    void executePipelineCompletesJob() {
        PipelineEntity pipeline = activePipeline("p1");
        lenient().when(pipelineRepo.findById("p1")).thenReturn(Optional.of(pipeline));
        lenient().when(pipelineJobRepo.save(any(PipelineJobEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(pipelineRepo.save(any(PipelineEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        DataQualityRuleEntity rule = new DataQualityRuleEntity();
        rule.ruleName = "not-null";
        rule.ruleStatus = DataQualityRuleEntity.RuleStatus.ACTIVE;
        rule.enabled = true;
        rule.actionOnFailure = "stop";
        lenient().when(dataQualityRuleRepo.findByPipelineId("p1")).thenReturn(List.of(rule));

        PipelineJobEntity job = pipelineService.executePipeline("p1", "tester");

        assertTrue(job.id.startsWith("pjob_"));
        assertEquals("tester", job.triggeredBy);
        assertEquals("manual", job.triggerType);
        assertEquals(PipelineJobEntity.JobStatus.COMPLETED, job.jobStatus);
        assertNotNull(job.startedAt);
        assertNotNull(job.completedAt);
        assertTrue(job.processedRows >= 1000);
        assertEquals(1, pipeline.runCount);
        assertEquals(1, pipeline.successCount);
        assertNotNull(pipeline.lastRunAt);
        assertEquals(1, rule.totalEvaluations);
        verify(dataQualityRuleRepo).save(rule);
        verify(pipelineRepo).save(pipeline);
    }

    @Test
    @DisplayName("getPipelineStats：运行统计与任务状态聚合")
    void getPipelineStatsAggregates() {
        PipelineEntity pipeline = activePipeline("p1");
        pipeline.runCount = 10;
        pipeline.successCount = 8;
        pipeline.failureCount = 2;
        pipeline.lastRunAt = LocalDateTime.now().minusHours(1);
        pipeline.lastSuccessAt = LocalDateTime.now().minusHours(1);
        lenient().when(pipelineRepo.findById("p1")).thenReturn(Optional.of(pipeline));

        PipelineJobEntity done = new PipelineJobEntity();
        done.jobStatus = PipelineJobEntity.JobStatus.COMPLETED;
        PipelineJobEntity failed = new PipelineJobEntity();
        failed.jobStatus = PipelineJobEntity.JobStatus.FAILED;
        lenient().when(pipelineJobRepo.findByPipelineId("p1")).thenReturn(List.of(done, failed));

        Map<String, Object> stats = pipelineService.getPipelineStats("p1");

        assertEquals(PipelineEntity.PipelineStatus.ACTIVE, stats.get("pipelineStatus"));
        assertEquals(10, stats.get("totalRuns"));
        assertEquals(8, stats.get("successCount"));
        assertEquals(2, stats.get("failureCount"));
        assertEquals(80.0, (double) stats.get("successRate"), 0.0001);
        assertEquals(2L, stats.get("totalJobs"));
        assertEquals(1L, stats.get("completedJobs"));
        assertEquals(1L, stats.get("failedJobs"));
        assertEquals(pipeline.lastRunAt, stats.get("lastRun"));
    }

    @Test
    @DisplayName("createQualityRule：序列化规则定义并记录审计")
    void createQualityRuleSerializesDefinition() {
        lenient().when(dataQualityRuleRepo.save(any(DataQualityRuleEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        DataQualityRuleEntity rule = pipelineService.createQualityRule(
            "g1", "p1", "not-null-user", DataQualityRuleEntity.RuleType.COMPLETENESS,
            DataQualityRuleEntity.Severity.ERROR, "events", "user_id",
            Map.of("min", 1), "admin");

        assertTrue(rule.id.startsWith("dqr_"));
        assertEquals(DataQualityRuleEntity.Severity.ERROR, rule.severity);
        assertEquals("events", rule.targetTable);
        assertEquals("user_id", rule.targetColumn);
        assertTrue(rule.ruleDefinition.contains("min"));
        verify(auditLog).logCreate(eq("data_quality_rule"), eq(rule.id), eq("not-null-user"),
            eq("admin"), eq("admin"), isNull(), anyMap());
    }

    @Test
    @DisplayName("executeScheduledPipelines：到期管道被调度执行")
    void executeScheduledPipelinesRunsDuePipeline() {
        PipelineEntity pipeline = activePipeline("p1");
        lenient().when(pipelineRepo.findScheduledPipelines(any(LocalDateTime.class)))
            .thenReturn(List.of(pipeline));
        lenient().when(pipelineJobRepo.save(any(PipelineJobEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(pipelineRepo.save(any(PipelineEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        assertDoesNotThrow(() -> pipelineService.executeScheduledPipelines());

        ArgumentCaptor<PipelineJobEntity> captor = ArgumentCaptor.forClass(PipelineJobEntity.class);
        // 创建任务一次 + executePipelineJob 收尾一次
        verify(pipelineJobRepo, times(2)).save(captor.capture());
        assertEquals("schedule", captor.getValue().triggerType);
        assertEquals("system", captor.getValue().triggeredBy);
        assertEquals(PipelineJobEntity.JobStatus.COMPLETED, captor.getValue().jobStatus);
        assertEquals(1, pipeline.runCount);
    }

    @Test
    @DisplayName("cleanupOldJobs：删除90天前的完成任务")
    void cleanupOldJobsInvokesRepo() {
        lenient().when(pipelineJobRepo.deleteCompletedBefore(any(LocalDateTime.class))).thenReturn(7);

        assertDoesNotThrow(() -> pipelineService.cleanupOldJobs());

        verify(pipelineJobRepo).deleteCompletedBefore(any(LocalDateTime.class));
    }

    // =========================================================
    // RiskDashboardService（非空数据行分支）
    // =========================================================

    @Test
    @DisplayName("getOverview：案例分组/高风险/待审核/封禁/作业计数")
    void getOverviewAggregatesRealData() {
        RiskCaseEntity blocked = riskCase("c1", RiskCaseEntity.RiskLevel.HIGH,
            RiskCaseEntity.ActionType.BLOCK, RiskCaseEntity.ExecutionStatus.EXECUTED, null);
        RiskCaseEntity pendingReview = riskCase("c2", RiskCaseEntity.RiskLevel.MEDIUM,
            RiskCaseEntity.ActionType.ALERT, RiskCaseEntity.ExecutionStatus.PENDING, "pending");
        lenient().when(riskCaseRepo.countByGameIdSince(eq("g1"), any(LocalDateTime.class))).thenReturn(5L);
        lenient().when(riskCaseRepo.findByGameId("g1")).thenReturn(List.of(blocked, pendingReview));
        lenient().when(blockListRepo.findActiveBlocks(eq("g1"), any(LocalDateTime.class)))
            .thenReturn(List.of(blockEntity("b1", true)));
        lenient().when(flinkJobRepo.findRunningJobs("g1"))
            .thenReturn(List.of(flinkJob("j1", FlinkJobEntity.JobStatus.RUNNING)));

        Map<String, Object> overview = riskDashboardService.getOverview("g1", LocalDateTime.now().minusDays(1));

        assertEquals(5L, overview.get("totalCases"));
        assertEquals(1L, overview.get("highRiskCases"));
        assertEquals(1L, overview.get("pendingReview"));
        assertEquals(1, overview.get("activeBlocks"));
        assertEquals(1, overview.get("runningJobs"));
        Map<?, ?> byRiskLevel = (Map<?, ?>) overview.get("byRiskLevel");
        assertEquals(1L, byRiskLevel.get("HIGH"));
        assertEquals(1L, byRiskLevel.get("MEDIUM"));
        Map<?, ?> byActionType = (Map<?, ?>) overview.get("byActionType");
        assertEquals(1L, byActionType.get("BLOCK"));
    }

    @Test
    @DisplayName("getRiskTrends：按时间桶统计高风险/封禁/已审核案例")
    void getRiskTrendsAggregatesBuckets() {
        LocalDateTime since = LocalDateTime.now().minusHours(3);
        RiskCaseEntity blockedHigh = riskCase("c1", RiskCaseEntity.RiskLevel.HIGH,
            RiskCaseEntity.ActionType.BLOCK, RiskCaseEntity.ExecutionStatus.EXECUTED, null);
        RiskCaseEntity reviewed = riskCase("c2", RiskCaseEntity.RiskLevel.MEDIUM,
            RiskCaseEntity.ActionType.ALERT, RiskCaseEntity.ExecutionStatus.PENDING, "pending");
        lenient().when(riskCaseRepo.findByGameIdAndTimeRange(eq("g1"), any(LocalDateTime.class), any(LocalDateTime.class)))
            .thenReturn(List.of(blockedHigh, reviewed));

        List<Map<String, Object>> trends = riskDashboardService.getRiskTrends("g1", since, 24);

        assertEquals(1, trends.size());
        Map<String, Object> row = trends.get(0);
        assertEquals(2L, row.get("totalCases"));
        assertEquals(1L, row.get("highRiskCases"));
        assertEquals(1L, row.get("blockedCases"));
        assertEquals(1L, row.get("reviewedCases"));
    }

    @Test
    @DisplayName("getHighRiskTargets：Object[] 行映射且受 limit 截断")
    void getHighRiskTargetsMapsRows() {
        List<Object[]> rows = List.<Object[]>of(
            new Object[]{"user_id", "u-77", 5L},
            new Object[]{"device_id", "d-1", 3L});
        lenient().when(riskCaseRepo.findFrequentTargets(any(LocalDateTime.class), anyLong()))
            .thenReturn(rows);

        List<Map<String, Object>> targets = riskDashboardService.getHighRiskTargets(
            "g1", LocalDateTime.now().minusDays(1), 1);

        assertEquals(1, targets.size());
        assertEquals("user_id", targets.get(0).get("targetType"));
        assertEquals("u-77", targets.get(0).get("targetId"));
        assertEquals(5L, targets.get(0).get("caseCount"));
    }

    @Test
    @DisplayName("getRulePerformance：触发/拦截计数、blockRate 与名称回退")
    void getRulePerformanceComputesRates() {
        RiskRuleEntity withStats = new RiskRuleEntity();
        withStats.id = "r1";
        withStats.name = "speed-hack";
        withStats.displayName = null;
        withStats.category = RiskRuleEntity.RuleCategory.BEHAVIOR;
        withStats.riskLevel = RiskRuleEntity.RiskLevel.HIGH;
        withStats.actionType = RiskRuleEntity.ActionType.BLOCK;
        withStats.totalTriggeredCount = 10L;
        withStats.totalBlockedCount = 4L;
        withStats.lastTriggeredAt = LocalDateTime.now().minusHours(1);

        RiskRuleEntity withoutStats = new RiskRuleEntity();
        withoutStats.id = "r2";
        withoutStats.name = "payment-spike";
        withoutStats.displayName = "Payment Spike";
        withoutStats.category = RiskRuleEntity.RuleCategory.PAYMENT;
        withoutStats.riskLevel = RiskRuleEntity.RiskLevel.MEDIUM;
        withoutStats.actionType = RiskRuleEntity.ActionType.REVIEW;
        withoutStats.totalTriggeredCount = null;
        withoutStats.totalBlockedCount = null;

        lenient().when(riskRuleRepo.findActiveByGameId("g1")).thenReturn(List.of(withStats, withoutStats));
        lenient().when(riskCaseRepo.findByRiskRuleId("r1")).thenReturn(List.of(
            riskCase("c1", RiskCaseEntity.RiskLevel.HIGH, RiskCaseEntity.ActionType.BLOCK,
                RiskCaseEntity.ExecutionStatus.EXECUTED, null),
            riskCase("c2", RiskCaseEntity.RiskLevel.MEDIUM, RiskCaseEntity.ActionType.BLOCK,
                RiskCaseEntity.ExecutionStatus.PENDING, null)));
        lenient().when(riskCaseRepo.findByRiskRuleId("r2")).thenReturn(List.of());

        List<Map<String, Object>> performance = riskDashboardService.getRulePerformance("g1");

        assertEquals(2, performance.size());
        Map<String, Object> first = performance.get(0);
        assertEquals("r1", first.get("ruleId"));
        assertEquals("speed-hack", first.get("ruleName"));
        assertEquals("BEHAVIOR", first.get("category"));
        assertEquals(10L, first.get("triggerCount"));
        assertEquals(4L, first.get("blockCount"));
        assertEquals(0.4, (double) first.get("blockRate"), 0.0001);
        assertEquals(2, first.get("recentCases"));
        assertEquals(withStats.lastTriggeredAt, first.get("lastTriggered"));

        Map<String, Object> second = performance.get(1);
        assertEquals("Payment Spike", second.get("ruleName"));
        assertEquals(0L, second.get("triggerCount"));
        assertEquals(0.0, (double) second.get("blockRate"), 0.0001);
        assertEquals(0, second.get("recentCases"));
    }

    @Test
    @DisplayName("getBlockStats：永久/临时封禁、分类回退与命中合计")
    void getBlockStatsAggregatesBlocks() {
        BlockListEntity permanent = blockEntity("b1", true);
        permanent.targetType = "user_id";
        permanent.blockCategory = "fraud";
        permanent.blockType = BlockListEntity.BlockType.HARD;
        permanent.hitCount = 5L;

        BlockListEntity temporary = blockEntity("b2", false);
        temporary.targetType = "device_id";
        temporary.blockCategory = null;
        temporary.blockType = BlockListEntity.BlockType.SOFT;
        temporary.hitCount = 3L;

        lenient().when(blockListRepo.findActiveBlocks(eq("g1"), any(LocalDateTime.class)))
            .thenReturn(List.of(permanent, temporary));

        Map<String, Object> stats = riskDashboardService.getBlockStats("g1");

        assertEquals(2, stats.get("totalActive"));
        assertEquals(1L, stats.get("permanentBlocks"));
        assertEquals(1L, stats.get("temporaryBlocks"));
        assertEquals(8L, stats.get("totalHits"));
        Map<?, ?> byCategory = (Map<?, ?>) stats.get("byCategory");
        assertEquals(1L, byCategory.get("fraud"));
        assertEquals(1L, byCategory.get("unknown"));
        Map<?, ?> byBlockType = (Map<?, ?>) stats.get("byBlockType");
        assertEquals(1L, byBlockType.get("HARD"));
        assertEquals(1L, byBlockType.get("SOFT"));
    }

    @Test
    @DisplayName("getJobStats：事件/案例/动作合计与状态、类型分组")
    void getJobStatsAggregatesJobs() {
        FlinkJobEntity running = flinkJob("j1", FlinkJobEntity.JobStatus.RUNNING);
        running.jobType = "risk_evaluation";
        running.totalEventsProcessed = 1000L;
        running.totalRiskCasesCreated = 10L;
        running.totalActionsExecuted = 5L;

        FlinkJobEntity failed = flinkJob("j2", FlinkJobEntity.JobStatus.FAILED);
        failed.jobType = "fraud_detection";
        failed.totalEventsProcessed = null;
        failed.totalRiskCasesCreated = null;
        failed.totalActionsExecuted = null;

        lenient().when(flinkJobRepo.findByGameId("g1")).thenReturn(List.of(running, failed));
        lenient().when(flinkJobRepo.findRunningJobs("g1")).thenReturn(List.of(running));

        Map<String, Object> stats = riskDashboardService.getJobStats("g1");

        assertEquals(2, stats.get("totalJobs"));
        assertEquals(1, stats.get("runningJobs"));
        assertEquals(1L, stats.get("stoppedJobs"));
        assertEquals(1L, stats.get("failedJobs"));
        assertEquals(1000L, stats.get("totalEventsProcessed"));
        assertEquals(10L, stats.get("totalRiskCasesCreated"));
        assertEquals(5L, stats.get("totalActionsExecuted"));
        Map<?, ?> byStatus = (Map<?, ?>) stats.get("byStatus");
        assertEquals(1L, byStatus.get("RUNNING"));
        assertEquals(1L, byStatus.get("FAILED"));
        Map<?, ?> byType = (Map<?, ?>) stats.get("byType");
        assertEquals(1L, byType.get("risk_evaluation"));
        assertEquals(1L, byType.get("fraud_detection"));
    }

    @Test
    @DisplayName("getRecentCases：字段映射、reviewStatus 空值回退与 limit 截断")
    void getRecentCasesMapsFields() {
        RiskCaseEntity withReview = riskCase("c1", RiskCaseEntity.RiskLevel.HIGH,
            RiskCaseEntity.ActionType.BLOCK, RiskCaseEntity.ExecutionStatus.EXECUTED, "pending");
        withReview.createdAt = LocalDateTime.now().minusMinutes(30);
        RiskCaseEntity noReview = riskCase("c2", RiskCaseEntity.RiskLevel.LOW,
            RiskCaseEntity.ActionType.ALERT, RiskCaseEntity.ExecutionStatus.PENDING, null);
        noReview.createdAt = LocalDateTime.now();
        lenient().when(riskCaseRepo.findByGameId("g1")).thenReturn(List.of(withReview, noReview));

        List<Map<String, Object>> cases = riskDashboardService.getRecentCases("g1", 1);

        assertEquals(1, cases.size());
        Map<String, Object> row = cases.get(0);
        assertEquals("c1", row.get("caseId"));
        assertEquals("CASE_1", row.get("caseNumber"));
        assertEquals("user_id", row.get("targetType"));
        assertEquals("t-1", row.get("targetId"));
        assertEquals("HIGH", row.get("riskLevel"));
        assertEquals("BLOCK", row.get("actionTaken"));
        assertEquals("EXECUTED", row.get("status"));
        assertEquals("pending", row.get("reviewStatus"));
    }

    @Test
    @DisplayName("getReviewQueueStats：待审数量/高优先级与平均等待时间")
    void getReviewQueueStatsComputesAverages() {
        RiskCaseEntity high = riskCase("c1", RiskCaseEntity.RiskLevel.HIGH,
            RiskCaseEntity.ActionType.BLOCK, RiskCaseEntity.ExecutionStatus.EXECUTED, "pending");
        high.createdAt = LocalDateTime.now().minusMinutes(10);
        RiskCaseEntity low = riskCase("c2", RiskCaseEntity.RiskLevel.MEDIUM,
            RiskCaseEntity.ActionType.REVIEW, RiskCaseEntity.ExecutionStatus.PENDING, "pending");
        low.createdAt = LocalDateTime.now().minusMinutes(10);
        lenient().when(riskCaseRepo.findPendingReview("g1")).thenReturn(List.of(high, low));

        Map<String, Object> stats = riskDashboardService.getReviewQueueStats("g1");

        assertEquals(2L, stats.get("totalPending"));
        assertEquals(1L, stats.get("highPriority"));
        double avgWait = (double) stats.get("avgWaitMinutes");
        assertTrue(avgWait >= 9.0 && avgWait <= 11.0);
        Map<?, ?> byRiskLevel = (Map<?, ?>) stats.get("byRiskLevel");
        assertEquals(1L, byRiskLevel.get("HIGH"));
        assertEquals(1L, byRiskLevel.get("MEDIUM"));
    }

    // =========================================================
    // 辅助构造
    // =========================================================

    private GameEntity game(String id, GameEntity.GameStatus status) {
        GameEntity g = new GameEntity();
        g.id = id;
        g.name = "Game-" + id;
        g.status = status;
        return g;
    }

    private GameEnvironmentEntity environment(String gameId, String name) {
        GameEnvironmentEntity e = new GameEnvironmentEntity();
        e.id = "env_" + gameId + "_" + name;
        e.gameId = gameId;
        e.name = name;
        e.displayName = name;
        e.type = GameEnvironmentEntity.EnvironmentType.TESTING;
        e.status = GameEnvironmentEntity.EnvironmentStatus.ACTIVE;
        e.storageProfileId = "shared-nonprod";
        return e;
    }

    private PipelineEntity activePipeline(String id) {
        PipelineEntity p = new PipelineEntity();
        p.id = id;
        p.gameId = "g1";
        p.environmentId = "env_g1_prod";
        p.pipelineName = "nightly-etl";
        p.pipelineType = PipelineEntity.PipelineType.ETL;
        p.pipelineStatus = PipelineEntity.PipelineStatus.ACTIVE;
        p.enabled = true;
        p.createdBy = "admin";
        return p;
    }

    private RiskCaseEntity riskCase(String id, RiskCaseEntity.RiskLevel level,
                                    RiskCaseEntity.ActionType action,
                                    RiskCaseEntity.ExecutionStatus status,
                                    String reviewStatus) {
        RiskCaseEntity c = new RiskCaseEntity();
        c.id = id;
        c.riskRuleId = "r1";
        c.gameId = "g1";
        c.caseNumber = "CASE_1";
        c.targetType = "user_id";
        c.targetId = "t-1";
        c.riskLevel = level;
        c.actionTaken = action;
        c.executionStatus = status;
        c.reviewStatus = reviewStatus;
        c.createdAt = LocalDateTime.now();
        return c;
    }

    private BlockListEntity blockEntity(String id, boolean permanent) {
        BlockListEntity b = new BlockListEntity();
        b.id = id;
        b.gameId = "g1";
        b.targetType = "user_id";
        b.targetValue = "t-1";
        b.blockType = BlockListEntity.BlockType.HARD;
        b.isPermanent = permanent;
        b.hitCount = 0L;
        return b;
    }

    private FlinkJobEntity flinkJob(String id, FlinkJobEntity.JobStatus status) {
        FlinkJobEntity j = new FlinkJobEntity();
        j.id = id;
        j.gameId = "g1";
        j.name = "job-" + id;
        j.jobType = "risk_evaluation";
        j.status = status;
        j.totalEventsProcessed = 0L;
        j.totalRiskCasesCreated = 0L;
        j.totalActionsExecuted = 0L;
        return j;
    }
}
