package io.oddsmaker.control.service;

import io.oddsmaker.control.jpa.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 安全服务
 * 管理MFA、SSO和安全会话
 */
@Service
@Transactional
public class SecurityService {

    private static final Logger logger = LoggerFactory.getLogger(SecurityService.class);

    @Autowired
    private MFAConfigRepo mfaConfigRepo;

    @Autowired
    private SSOConfigRepo ssoConfigRepo;

    @Autowired
    private SecuritySessionRepo securitySessionRepo;

    @Autowired
    private SecurityPolicyRepo securityPolicyRepo;

    @Autowired
    private AuditLogService auditLogService;

    // ============== MFA Methods ==============

    /**
     * 为用户启用MFA
     */
    public MFAConfigEntity enableMFA(String userId, MFAConfigEntity.MFAMethod method,
                                     String phoneNumber, String emailAddress) {
        // 收窄：仅 TOTP 可开通——SMS/EMAIL/PUSH 等无投递通道（无短信网关/SMTP/推送服务），
        // 配置成功也永远收不到验证码；HARDWARE_TOKEN/BIOMETRIC 无登记通道。与 IntegrationService authType 收窄同款逻辑。
        if (method != MFAConfigEntity.MFAMethod.TOTP) {
            throw new IllegalArgumentException("MFA method " + method
                + " is not supported: no delivery/enrollment channel is integrated for it"
                + " (a code would silently never arrive). Use TOTP");
        }

        // 检查是否已存在相同方法
        Optional<MFAConfigEntity> existing = mfaConfigRepo.findByUserIdAndMethod(userId, method);
        if (existing.isPresent() && existing.get().isEnabled()) {
            throw new IllegalStateException("MFA method already enabled: " + method);
        }

        MFAConfigEntity config = new MFAConfigEntity();
        config.id = "mfa_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        config.userId = userId;
        config.mfaMethod = method;
        config.phoneNumber = phoneNumber;
        config.emailAddress = emailAddress;
        config.mfaStatus = MFAConfigEntity.MFAStatus.PENDING;

        // 生成 RFC 6238 TOTP 密钥（Base32，authenticator app 可直接配对）
        config.secretKey = TotpUtil.generateSecret();
        config.qrCodeUrl = generateQRCodeUrl(config.secretKey, userId);

        config = mfaConfigRepo.save(config);

        logger.info("Initiated MFA setup for user: {} with method: {}", userId, method);
        return config;
    }

    /**
     * 验证并激活MFA
     */
    public MFAConfigEntity verifyAndActivateMFA(String configId, String code) {
        MFAConfigEntity config = mfaConfigRepo.findById(configId)
            .orElseThrow(() -> new IllegalArgumentException("MFA config not found: " + configId));

        if (!config.isPending()) {
            throw new IllegalStateException("MFA config is not pending: " + config.mfaStatus);
        }

        boolean isValid = verifyMFACode(config, code);

        if (isValid) {
            config.enable();

            // 检查是否为主要方法（用户的第一个MFA）
            List<MFAConfigEntity> userConfigs = mfaConfigRepo.findByUserId(config.userId);
            if (userConfigs.stream().noneMatch(MFAConfigEntity::isPrimary)) {
                config.markAsPrimary();
            }

            config = mfaConfigRepo.save(config);

            // 记录审计日志
            auditLogService.logCreate("mfa_config", config.id, config.mfaMethod.name(), config.userId, null, null, (String) null);

            logger.info("MFA enabled for user: {} with method: {}", config.userId, config.mfaMethod);
        } else {
            config.recordFailedVerification();
            mfaConfigRepo.save(config);

            if (config.hasExceededAttempts()) {
                config.lock();
                mfaConfigRepo.save(config);
                logger.warn("MFA config locked due to failed attempts: {}", configId);
            }

            throw new IllegalArgumentException("Invalid MFA code");
        }

        return config;
    }

    /**
     * 禁用MFA
     */
    public void disableMFA(String configId, String userId) {
        MFAConfigEntity config = mfaConfigRepo.findById(configId)
            .orElseThrow(() -> new IllegalArgumentException("MFA config not found: " + configId));

        if (!config.userId.equals(userId)) {
            throw new IllegalArgumentException("MFA config does not belong to user");
        }

        config.disable();
        mfaConfigRepo.save(config);

        // 记录审计日志
        auditLogService.logDelete("mfa_config", configId, config.mfaMethod.name(), userId, null, null);

        logger.info("MFA disabled for user: {} method: {}", userId, config.mfaMethod);
    }

    /**
     * 验证MFA代码
     */
    public boolean verifyMFA(String userId, String code) {
        List<MFAConfigEntity> configs = mfaConfigRepo.findEnabledByUserId(userId);

        for (MFAConfigEntity config : configs) {
            if (verifyMFACode(config, code)) {
                config.recordVerification();
                config.lastUsedAt = LocalDateTime.now();
                mfaConfigRepo.save(config);
                return true;
            }
        }

        return false;
    }

    /**
     * 检查用户是否启用了MFA
     */
    @Transactional(readOnly = true)
    public boolean isUserMFAEnabled(String userId) {
        return mfaConfigRepo.isMFAEnabledForUser(userId);
    }

    /**
     * 获取用户的MFA配置
     */
    @Transactional(readOnly = true)
    public List<MFAConfigEntity> getUserMFAConfigs(String userId) {
        return mfaConfigRepo.findByUserId(userId);
    }

    // ============== SSO Methods ==============

    /**
     * 创建SSO配置
     */
    public SSOConfigEntity createSSOConfig(String name, String description,
                                          SSOConfigEntity.SSOProtocol protocol,
                                          Map<String, Object> config, String createdBy) {
        SSOConfigEntity sso = new SSOConfigEntity();
        sso.id = "sso_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        sso.name = name;
        sso.description = description;
        sso.ssoProtocol = protocol;
        sso.ssoStatus = SSOConfigEntity.SSOStatus.DISABLED;
        sso.createdBy = createdBy;

        // 根据协议设置配置
        if (protocol == SSOConfigEntity.SSOProtocol.SAML2) {
            sso.samlIdpEntityId = (String) config.get("idpEntityId");
            sso.samlIdpSsoUrl = (String) config.get("idpSsoUrl");
            sso.samlIdpCert = (String) config.get("idpCert");
            sso.samlSpEntityId = (String) config.get("spEntityId");
            sso.samlAcsUrl = (String) config.get("acsUrl");
        } else if (protocol == SSOConfigEntity.SSOProtocol.OAUTH2 || protocol == SSOConfigEntity.SSOProtocol.OIDC) {
            sso.oauthProvider = (String) config.get("provider");
            sso.oauthClientId = (String) config.get("clientId");
            sso.oauthClientSecret = (String) config.get("clientSecret");
            sso.oauthAuthorizationUrl = (String) config.get("authorizationUrl");
            sso.oauthTokenUrl = (String) config.get("tokenUrl");
            sso.oauthScopes = (String) config.get("scopes");
            sso.oauthCallbackUrl = (String) config.get("callbackUrl");
        }

        sso = ssoConfigRepo.save(sso);

        // 记录审计日志
        auditLogService.logCreate("sso_config", sso.id, name, createdBy, createdBy, null,
            Map.of("protocol", protocol));

        logger.info("Created SSO config: {} - {}", sso.id, name);
        return sso;
    }

    /**
     * 激活SSO配置
     */
    public SSOConfigEntity activateSSO(String configId) {
        SSOConfigEntity sso = ssoConfigRepo.findById(configId)
            .orElseThrow(() -> new IllegalArgumentException("SSO config not found: " + configId));

        sso.activate();
        return ssoConfigRepo.save(sso);
    }

    /**
     * 获取活跃的SSO配置
     */
    @Transactional(readOnly = true)
    public List<SSOConfigEntity> getActiveSSOConfigs() {
        return ssoConfigRepo.findActive();
    }

    // ============== Security Session Methods ==============

    /**
     * 创建会话
     */
    public SecuritySessionEntity createSession(String userId, SecuritySessionEntity.AuthMethod authMethod,
                                              String ipAddress, String userAgent, int timeoutMinutes) {
        SecuritySessionEntity session = new SecuritySessionEntity();
        session.id = "sess_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        session.userId = userId;
        session.sessionToken = generateSessionToken();
        session.sessionStatus = SecuritySessionEntity.SessionStatus.ACTIVE;
        session.authMethod = authMethod;
        session.ipAddress = ipAddress;
        session.userAgent = userAgent;
        session.loginAt = LocalDateTime.now();
        session.lastActivityAt = LocalDateTime.now();
        session.expiresAt = LocalDateTime.now().plusMinutes(timeoutMinutes);
        session.deviceFingerprint = generateDeviceFingerprint(userAgent, ipAddress);

        session = securitySessionRepo.save(session);

        logger.info("Created session for user: {} from IP: {}", userId, ipAddress);
        return session;
    }

    /**
     * 获取会话
     */
    @Transactional(readOnly = true)
    public SecuritySessionEntity getSession(String token) {
        return securitySessionRepo.findByToken(token)
            .orElse(null);
    }

    /**
     * 验证会话
     */
    public SecuritySessionEntity validateSession(String token) {
        SecuritySessionEntity session = getSession(token);
        if (session == null || !session.isActive()) {
            return null;
        }

        session.updateActivity();
        return securitySessionRepo.save(session);
    }

    /**
     * 终止会话
     */
    public void terminateSession(String sessionId, String terminatedBy, String reason) {
        SecuritySessionEntity session = securitySessionRepo.findById(sessionId)
            .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));

        session.terminate(terminatedBy, reason);
        securitySessionRepo.save(session);

        logger.info("Terminated session: {} by: {} reason: {}", sessionId, terminatedBy, reason);
    }

    /**
     * 终止用户的所有会话
     */
    public void terminateAllUserSessions(String userId, String terminatedBy, String reason) {
        List<SecuritySessionEntity> sessions = securitySessionRepo.findActiveByUserId(userId);

        for (SecuritySessionEntity session : sessions) {
            session.terminate(terminatedBy, reason);
            securitySessionRepo.save(session);
        }

        logger.info("Terminated all {} sessions for user: {}", sessions.size(), userId);
    }

    // ============== Security Policy Methods ==============

    /**
     * 获取密码策略
     */
    @Transactional(readOnly = true)
    public List<SecurityPolicyEntity> getPasswordPolicies(String gameId) {
        return securityPolicyRepo.findPasswordPolicyForGame(gameId);
    }

    /**
     * 获取会话策略
     */
    @Transactional(readOnly = true)
    public List<SecurityPolicyEntity> getSessionPolicies(String gameId) {
        return securityPolicyRepo.findSessionPolicyForGame(gameId);
    }

    /**
     * 获取MFA策略
     */
    @Transactional(readOnly = true)
    public List<SecurityPolicyEntity> getMFAPolicies(String gameId) {
        return securityPolicyRepo.findMFAPolicyForGame(gameId);
    }

    /**
     * 检查游戏是否需要MFA
     */
    @Transactional(readOnly = true)
    public boolean isMFARequired(String gameId) {
        return securityPolicyRepo.isMFARequiredForGame(gameId);
    }

    // ============== Scheduled Tasks ==============

    /**
     * 定期清理过期会话
     */
    @Scheduled(fixedDelay = 300000)  // 每5分钟执行一次
    public void cleanupExpiredSessions() {
        try {
            LocalDateTime now = LocalDateTime.now();

            // 清理过期会话
            List<SecuritySessionEntity> expired = securitySessionRepo.findExpired(now);
            for (SecuritySessionEntity session : expired) {
                session.sessionStatus = SecuritySessionEntity.SessionStatus.EXPIRED;
                securitySessionRepo.save(session);
            }

            // 清理空闲超时会话
            LocalDateTime idleSince = now.minusMinutes(15);  // 默认15分钟空闲超时
            List<SecuritySessionEntity> idleSessions = securitySessionRepo.findIdleExpired(idleSince);
            for (SecuritySessionEntity session : idleSessions) {
                session.revoke("Idle timeout");
                securitySessionRepo.save(session);
            }

            if (!expired.isEmpty() || !idleSessions.isEmpty()) {
                logger.debug("Cleaned up {} expired and {} idle sessions", expired.size(), idleSessions.size());
            }
        } catch (Exception e) {
            logger.error("Failed to cleanup expired sessions", e);
        }
    }

    /**
     * 定期删除很久以前过期的会话
     */
    @Scheduled(cron = "0 0 4 * * ?")  // 每天凌晨4点执行
    public void deleteOldSessions() {
        try {
            LocalDateTime expireBefore = LocalDateTime.now().minusDays(30);
            int deleted = securitySessionRepo.deleteExpired(expireBefore);

            if (deleted > 0) {
                logger.info("Deleted {} old sessions", deleted);
            }
        } catch (Exception e) {
            logger.error("Failed to delete old sessions", e);
        }
    }

    // 私有辅助方法

    private String generateQRCodeUrl(String secret, String userId) {
        return String.format("otpauth://totp/Oddsmaker:%s?secret=%s&issuer=Oddsmaker", userId, secret);
    }

    /**
     * TOTP 走 RFC 6238 真验证（±1 窗口、常数时间比较）；
     * 其他方法无投递通道（enableMFA 已收窄拒绝开通），DB 直插的异常配置一律不通过。
     */
    private boolean verifyMFACode(MFAConfigEntity config, String code) {
        if (config.mfaMethod == MFAConfigEntity.MFAMethod.TOTP) {
            return TotpUtil.verify(config.secretKey, code, Instant.now());
        }
        return false;
    }

    private String generateSessionToken() {
        return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    }

    private String generateDeviceFingerprint(String userAgent, String ipAddress) {
        String normalizedUA = userAgent != null ? userAgent.replaceAll("\\s+", "") : "";
        // SHA-256 + 分隔符（裸拼接有 u1a|b2 与 u1|ab2 歧义碰撞）；hashCode 碰撞面大且跨 JVM 不稳定
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((normalizedUA + "|" + ipAddress).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 每个 JDK 必有——防御兜底，可达性仅存于异常 JVM
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
