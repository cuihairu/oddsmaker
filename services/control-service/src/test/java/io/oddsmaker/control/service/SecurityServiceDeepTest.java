package io.oddsmaker.control.service;

import io.oddsmaker.control.dto.GameDTO;
import io.oddsmaker.control.jpa.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.Instant;
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
 * 安全与游戏域 Service 深度单元测试（LAY-004 拆分）：MFA 开通/激活/验证、SSO 映射、会话生命周期与设备指纹、安全策略/MFA 查询、分支对侧。与 GameServiceTest / DashboardServicesTest 互补。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("安全域 Service 深度测试（MFA/SSO/会话）")
class SecurityServiceDeepTest {

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
        assertTrue(config.secretKey.matches("[A-Z2-7]{32}"), "应为 RFC 4648 Base32 密钥: " + config.secretKey);
        assertTrue(config.qrCodeUrl.startsWith("otpauth://totp/Oddsmaker:u1?"));
        assertTrue(config.qrCodeUrl.contains("secret=" + config.secretKey));
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
    @DisplayName("enableMFA：已禁用的历史配置允许重新开通；SMS 无投递通道拒绝开通（收窄）")
    void enableMfaRejectsMethodsWithoutDeliveryChannel() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> securityService.enableMFA(
                "u1", MFAConfigEntity.MFAMethod.SMS, "13800000000", null));
        assertTrue(ex.getMessage().contains("SMS"), "消息应含被拒方法: " + ex.getMessage());
        verify(mfaConfigRepo, never()).save(any());

        // TOTP 已禁用的历史配置允许重新开通（复核 existing 分支在收窄后仍生效）
        MFAConfigEntity disabled = new MFAConfigEntity();
        disabled.mfaMethod = MFAConfigEntity.MFAMethod.TOTP;
        disabled.mfaStatus = MFAConfigEntity.MFAStatus.DISABLED;
        lenient().when(mfaConfigRepo.findByUserIdAndMethod("u1", MFAConfigEntity.MFAMethod.TOTP))
            .thenReturn(Optional.of(disabled));
        lenient().when(mfaConfigRepo.save(any(MFAConfigEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        MFAConfigEntity reEnrolled = securityService.enableMFA("u1", MFAConfigEntity.MFAMethod.TOTP, null, null);
        assertEquals(MFAConfigEntity.MFAStatus.PENDING, reEnrolled.mfaStatus);
        assertNotNull(reEnrolled.secretKey);
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
    @DisplayName("verifyAndActivateMFA：真 TOTP 当前码验证通过后启用并标记为主要方法")
    void verifyAndActivateMfaSuccessMarksPrimary() {
        MFAConfigEntity config = new MFAConfigEntity();
        config.id = "mfa_1";
        config.userId = "u1";
        config.mfaMethod = MFAConfigEntity.MFAMethod.TOTP;
        config.mfaStatus = MFAConfigEntity.MFAStatus.PENDING;
        config.secretKey = TotpUtil.generateSecret();
        lenient().when(mfaConfigRepo.findById("mfa_1")).thenReturn(Optional.of(config));
        lenient().when(mfaConfigRepo.findByUserId("u1")).thenReturn(List.of(config));
        lenient().when(mfaConfigRepo.save(any(MFAConfigEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        String currentCode = TotpUtil.currentCode(config.secretKey, Instant.now());
        MFAConfigEntity activated = securityService.verifyAndActivateMFA("mfa_1", currentCode);

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
    @DisplayName("verifyMFA：真 TOTP 当前码验证通过并记录使用时间；过期窗的码拒绝")
    void verifyMfaSucceedsWithRealTotpCode() {
        MFAConfigEntity enabled = new MFAConfigEntity();
        enabled.mfaMethod = MFAConfigEntity.MFAMethod.TOTP;
        enabled.mfaStatus = MFAConfigEntity.MFAStatus.ENABLED;
        enabled.secretKey = TotpUtil.generateSecret();
        lenient().when(mfaConfigRepo.findEnabledByUserId("u1")).thenReturn(List.of(enabled));
        lenient().when(mfaConfigRepo.save(any(MFAConfigEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        // 任意 6 位数字不再通过（旧实现假成功的复现输入）
        assertFalse(securityService.verifyMFA("u1", "654321"));
        // ±2 窗口外的历史码拒绝
        String stale = TotpUtil.currentCode(enabled.secretKey, Instant.now().minusSeconds(120));
        assertFalse(securityService.verifyMFA("u1", stale));

        String currentCode = TotpUtil.currentCode(enabled.secretKey, Instant.now());
        assertTrue(securityService.verifyMFA("u1", currentCode));
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
        // SHA-256 十六进制：64 位小写 hex 且确定性（替代旧 hashCode 指纹）
        String fingerprint = session.deviceFingerprint;
        assertTrue(fingerprint.matches("[0-9a-f]{64}"), "应为 SHA-256 hex: " + fingerprint);
        assertEquals(fingerprint, org.springframework.test.util.ReflectionTestUtils.invokeMethod(
            securityService, "generateDeviceFingerprint", "Mozilla/5.0", "10.0.0.1"));
        assertNotNull(session.expiresAt);
        assertTrue(session.expiresAt.isAfter(session.loginAt));
        assertTrue(session.isActive());
    }

    @Test
    @DisplayName("generateDeviceFingerprint：SHA-256 异常防御兜底转 IllegalStateException")
    void deviceFingerprintWrapsDigestFailure() {
        try (var mockedDigest = org.mockito.Mockito.mockStatic(java.security.MessageDigest.class)) {
            mockedDigest.when(() -> java.security.MessageDigest.getInstance("SHA-256"))
                .thenThrow(new java.security.NoSuchAlgorithmException("boom"));
            assertThrows(IllegalStateException.class,
                () -> org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                    securityService, "generateDeviceFingerprint", "ua", "1.2.3.4"));
        }
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

    @Test
    @DisplayName("分支对侧：已有主 MFA 不重复标记、失败未达上限不锁、OAUTH2 映射、清扫组合、null UA 指纹")
    void securityBranchSides() {
        // 用户已有 primary 的 MFA → 新激活方法不再 markAsPrimary（noneMatch false 侧）
        MFAConfigEntity primary = new MFAConfigEntity();
        primary.id = "mfa_p";
        primary.userId = "u1";
        primary.mfaMethod = MFAConfigEntity.MFAMethod.TOTP;
        primary.mfaStatus = MFAConfigEntity.MFAStatus.ENABLED;
        primary.markAsPrimary();
        MFAConfigEntity second = new MFAConfigEntity();
        second.id = "mfa_second";
        second.userId = "u1";
        second.mfaMethod = MFAConfigEntity.MFAMethod.TOTP;
        second.mfaStatus = MFAConfigEntity.MFAStatus.PENDING;
        second.secretKey = TotpUtil.generateSecret();
        lenient().when(mfaConfigRepo.findById("mfa_second")).thenReturn(Optional.of(second));
        lenient().when(mfaConfigRepo.findByUserId("u1")).thenReturn(List.of(primary, second));
        lenient().when(mfaConfigRepo.save(any(MFAConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        MFAConfigEntity activated = securityService.verifyAndActivateMFA("mfa_second",
            TotpUtil.currentCode(second.secretKey, Instant.now()));
        assertEquals(MFAConfigEntity.MFAStatus.ENABLED, activated.mfaStatus);
        assertFalse(activated.isPrimary());

        // 验证失败但未达上限 → 记次不锁定（hasExceededAttempts false 侧）
        MFAConfigEntity fresh = new MFAConfigEntity();
        fresh.id = "mfa_fresh";
        fresh.userId = "u2";
        fresh.mfaMethod = MFAConfigEntity.MFAMethod.TOTP;
        fresh.mfaStatus = MFAConfigEntity.MFAStatus.PENDING;
        fresh.verificationAttempts = 0;
        lenient().when(mfaConfigRepo.findById("mfa_fresh")).thenReturn(Optional.of(fresh));
        assertThrows(IllegalArgumentException.class,
            () -> securityService.verifyAndActivateMFA("mfa_fresh", "bad-code"));
        assertEquals(1, fresh.verificationAttempts);
        assertFalse(fresh.mfaStatus == MFAConfigEntity.MFAStatus.LOCKED);

        // OAUTH2 协议（protocol == OAUTH2 true 侧）
        lenient().when(ssoConfigRepo.save(any(SSOConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("provider", "github");
        cfg.put("clientId", "cid");
        SSOConfigEntity oauth = securityService.createSSOConfig(
            "GH", "oauth", SSOConfigEntity.SSOProtocol.OAUTH2, cfg, "admin");
        assertEquals("github", oauth.oauthProvider);

        // 过期会话空但空闲会话非空（|| 第二条件 true 侧）
        lenient().when(securitySessionRepo.findExpired(any(LocalDateTime.class))).thenReturn(List.of());
        SecuritySessionEntity idle = new SecuritySessionEntity();
        idle.sessionStatus = SecuritySessionEntity.SessionStatus.ACTIVE;
        lenient().when(securitySessionRepo.findIdleExpired(any(LocalDateTime.class))).thenReturn(List.of(idle));
        lenient().when(securitySessionRepo.save(any(SecuritySessionEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        securityService.cleanupExpiredSessions();
        assertEquals(SecuritySessionEntity.SessionStatus.REVOKED, idle.sessionStatus);

        // deleteExpired 返回 0（deleted > 0 false 侧）
        lenient().when(securitySessionRepo.deleteExpired(any(LocalDateTime.class))).thenReturn(0);
        securityService.deleteOldSessions();

        // userAgent null → 归一化为空串（三元 null 侧）
        SecuritySessionEntity nullUa = securityService.createSession(
            "u1", SecuritySessionEntity.AuthMethod.SSO, "10.0.0.2", null, 30);
        assertTrue(nullUa.deviceFingerprint.matches("[0-9a-f]{64}"));
    }
}
