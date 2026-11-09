package io.oddsmaker.control.jpa;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖率冲刺第二轮·身份/安全/风控域（LAY-002 拆分）：审计日志、身份、黑名单、风控案件与规则、角色权限、SSO/MFA、安全策略、会话分析。与 EntitiesSmokeCoverageTest（空构造冒烟）、EntitiesDeep* 拆分类（主要状态方法）互补。
 */
class EntitiesSweep2SecurityTest {

    @Test
    @DisplayName("AuditLogEntity：认证/数据操作判定与全量操作描述映射")
    void auditLogEntity() {
        AuditLogEntity l = new AuditLogEntity();
        l.username = "王小明";
        l.resourceType = "game";

        l.action = AuditLogEntity.AuditAction.LOGIN;
        assertTrue(l.isAuthAction());
        assertFalse(l.isDataAction());
        l.action = AuditLogEntity.AuditAction.LOGIN_FAILED;
        assertTrue(l.isAuthAction());
        l.action = AuditLogEntity.AuditAction.EXPORT;
        assertTrue(l.isDataAction());
        assertFalse(l.isAuthAction());
        l.action = AuditLogEntity.AuditAction.READ;
        assertFalse(l.isDataAction());

        assertTrue(l.isSuccess());
        assertFalse(l.isFailure());
        l.status = AuditLogEntity.AuditStatus.FAILURE;
        assertTrue(l.isFailure());
        assertFalse(l.isSuccess());

        for (AuditLogEntity.AuditAction action : AuditLogEntity.AuditAction.values()) {
            l.action = action;
            assertNotNull(l.getActionDescription());
        }
        l.action = AuditLogEntity.AuditAction.CREATE;
        assertEquals("创建", l.getActionDescription());
        l.action = AuditLogEntity.AuditAction.LOGIN_FAILED;
        assertEquals("登录失败", l.getActionDescription());
        l.action = AuditLogEntity.AuditAction.GRANT_PERMISSION;
        assertEquals("授予权限", l.getActionDescription());
        l.action = AuditLogEntity.AuditAction.SYSTEM_MAINTENANCE;
        assertEquals("系统维护", l.getActionDescription());
    }

    @Test
    @DisplayName("IdentityEntity：主标识切换的显示ID回退/活跃判定/活动记录")
    void identityEntity() {
        IdentityEntity i = new IdentityEntity();
        i.gameId = "g1";
        i.primaryId = "dev-原始标识";

        assertTrue(i.isActive());
        assertFalse(i.isMerged());
        assertFalse(i.hasUserId());
        assertFalse(i.isBoundToUser());
        assertEquals("dev-原始标识", i.getDisplayId());

        i.status = IdentityEntity.IdentityStatus.MERGED;
        assertTrue(i.isMerged());
        assertFalse(i.isActive());
        i.status = IdentityEntity.IdentityStatus.ACTIVE;
        i.deletedAt = LocalDateTime.now();
        assertFalse(i.isActive());
        i.deletedAt = null;

        i.userId = "";
        assertFalse(i.hasUserId());
        i.userId = "u-100";
        assertTrue(i.hasUserId());
        assertTrue(i.isBoundToUser());
        i.playerId = "p-200";

        i.primaryIdentityType = IdentityEntity.IdentityType.USER;
        assertEquals("u-100", i.getDisplayId());
        i.userId = null;
        assertEquals("dev-原始标识", i.getDisplayId());
        i.primaryIdentityType = IdentityEntity.IdentityType.PLAYER;
        assertEquals("p-200", i.getDisplayId());
        i.playerId = null;
        assertEquals("dev-原始标识", i.getDisplayId());
        i.primaryIdentityType = IdentityEntity.IdentityType.CHARACTER;
        i.characterId = "c-300";
        assertEquals("c-300", i.getDisplayId());
        i.characterId = null;
        assertEquals("dev-原始标识", i.getDisplayId());
        i.primaryIdentityType = IdentityEntity.IdentityType.DEVICE;
        i.deviceId = "dev-400";
        assertEquals("dev-400", i.getDisplayId());
        i.deviceId = null;
        assertEquals("dev-原始标识", i.getDisplayId());

        assertEquals(0, i.getDaysSinceFirstSeen());
        assertEquals(0, i.getDaysSinceLastSeen());
        i.firstSeenAt = LocalDateTime.now().minusDays(5);
        assertTrue(i.getDaysSinceFirstSeen() >= 4);
        i.lastSeenAt = LocalDateTime.now().minusDays(3);
        assertTrue(i.getDaysSinceLastSeen() >= 2);
        assertTrue(i.isInactive(2));
        i.lastSeenAt = LocalDateTime.now();
        assertFalse(i.isInactive(2));

    }

    @Test
    @DisplayName("BlockListEntity：封禁生效/过期/剩余时长/命中记录与描述拼接")
    void blockListEntity() {
        LocalDateTime now = LocalDateTime.now();
        BlockListEntity b = new BlockListEntity();
        b.gameId = "g1";
        b.targetType = "device_id";
        b.targetValue = "dev-9f00";
        b.blockedBy = "风控-赵七";

        assertTrue(b.isHardBlock());
        assertFalse(b.isSoftBlock());
        assertFalse(b.isShadowBlock());
        assertTrue(b.isActive());
        assertFalse(b.isExpired());
        assertEquals(0, b.getRemainingMinutes());

        b.blockType = BlockListEntity.BlockType.SOFT;
        assertTrue(b.isSoftBlock());
        b.blockType = BlockListEntity.BlockType.SHADOW;
        assertTrue(b.isShadowBlock());
        b.blockType = BlockListEntity.BlockType.HARD;

        b.deletedAt = now;
        assertFalse(b.isActive());
        b.deletedAt = null;
        b.unblockedAt = now;
        assertFalse(b.isActive());
        b.unblockedAt = null;

        b.isPermanent = true;
        assertTrue(b.isActive());
        assertFalse(b.isExpired());
        assertEquals(-1, b.getRemainingMinutes());
        b.isPermanent = false;

        b.expiresAt = now.plusMinutes(10);
        assertTrue(b.isActive());
        assertFalse(b.isExpired());
        assertTrue(b.getRemainingMinutes() >= 9);
        b.expiresAt = now.minusMinutes(1);
        assertFalse(b.isActive());
        assertTrue(b.isExpired());
        assertEquals(0, b.getRemainingMinutes());
        b.expiresAt = null;
        assertFalse(b.isExpired());

        b.hitCount = null;
        b.recordHit();
        assertEquals(1L, b.hitCount);
        assertNotNull(b.lastHitAt);
        b.recordHit();
        assertEquals(2L, b.hitCount);

        b.unblock("管理员", "误封申诉通过");
        assertEquals("管理员", b.unblockedBy);
        assertNotNull(b.unblockedAt);
        assertEquals("误封申诉通过", b.unblockReason);
        assertFalse(b.isActive());

        BlockListEntity d = new BlockListEntity();
        d.targetType = "ip";
        d.targetValue = "1.2.3.4";
        assertEquals("hard ip: 1.2.3.4", d.getBlockDescription());
        d.blockReason = "恶意刷量";
        assertTrue(d.getBlockDescription().contains("恶意刷量"));
        d.isPermanent = true;
        assertTrue(d.getBlockDescription().contains("(permanent)"));
        d.isPermanent = false;
        d.expiresAt = now.plusDays(1);
        assertTrue(d.getBlockDescription().contains("expires: "));
    }

    @Test
    @DisplayName("RiskCaseEntity：执行状态/风险等级/处置与解除封禁分支")
    void riskCaseEntity() {
        LocalDateTime now = LocalDateTime.now();
        RiskCaseEntity c = new RiskCaseEntity();
        c.riskRuleId = "rule-1";
        c.gameId = "g1";
        c.caseNumber = "CASE_20260909_001";
        c.targetType = "player_id";
        c.targetId = "p-100";
        c.createdAt = now.minusHours(2);

        assertTrue(c.isPending());
        assertFalse(c.isExecuted());
        assertFalse(c.isFailed());
        assertFalse(c.needsReview());
        assertFalse(c.isHighRisk());
        assertFalse(c.isBlocked());
        assertFalse(c.isUnblocked());
        assertFalse(c.isConfirmedFraud());
        assertFalse(c.isConfirmedBenign());
        assertFalse(c.isResolved());
        assertEquals(0, c.getResolutionTimeMinutes());

        c.actionTaken = RiskCaseEntity.ActionType.REVIEW;
        assertTrue(c.needsReview());
        c.actionTaken = RiskCaseEntity.ActionType.BLOCK;
        assertTrue(c.needsReview());
        assertFalse(c.isBlocked());
        c.executionStatus = RiskCaseEntity.ExecutionStatus.EXECUTED;
        c.executedAt = LocalDateTime.now();
        assertTrue(c.isExecuted());

        c.riskLevel = RiskCaseEntity.RiskLevel.HIGH;
        assertTrue(c.isHighRisk());
        c.riskLevel = RiskCaseEntity.RiskLevel.CRITICAL;
        assertTrue(c.isHighRisk());

        RiskCaseEntity f = new RiskCaseEntity();
        f.riskRuleId = "rule-1";
        f.gameId = "g1";
        f.caseNumber = "CASE_20260909_002";
        f.targetType = "device_id";
        f.targetId = "dev-1";
        f.markAsFailed("动作执行异常");
        assertTrue(f.isFailed());
        assertEquals("动作执行异常", f.executionError);

        c.disposition = "confirmed_fraud";
        assertTrue(c.isConfirmedFraud());
        assertFalse(c.isConfirmedBenign());
        c.disposition = "confirmed_benign";
        assertTrue(c.isConfirmedBenign());
        assertTrue(c.isResolved());

        c.unblock("审核员-甲", "误判解除");
        assertTrue(c.isUnblocked());
        assertEquals("审核员-甲", c.unblockedBy);
        assertEquals("误判解除", c.unblockReason);

        c.completeReview("审核员-乙", "证据充分", "confirmed_fraud");
        assertEquals("completed", c.reviewStatus);
        assertEquals("审核员-乙", c.reviewedBy);
        assertEquals("confirmed_fraud", c.disposition);
        assertNotNull(c.resolvedAt);
        assertTrue(c.getResolutionTimeMinutes() >= 119);

        assertTrue(c.getCaseTitle().contains("p-100"));
        assertTrue(c.getCaseTitle().contains("block"));
    }

    @Test
    @DisplayName("RiskRuleEntity：规则启用/自动封禁/冷却窗口与动作描述")
    void riskRuleEntity() {
        RiskRuleEntity r = new RiskRuleEntity();
        r.gameId = "g1";
        r.name = "高频充值风控";

        assertTrue(r.isActive());
        assertTrue(r.isGlobal());
        assertFalse(r.isAutoBlockEnabled());
        assertFalse(r.isInTestMode());
        assertTrue(r.needsReview());
        assertFalse(r.isHighRisk());
        assertFalse(r.isCriticalRisk());

        r.deletedAt = LocalDateTime.now();
        assertFalse(r.isActive());
        r.deletedAt = null;
        r.environmentId = "env-1";
        assertFalse(r.isGlobal());

        r.enableAutoBlock = true;
        assertFalse(r.isAutoBlockEnabled());
        r.actionType = RiskRuleEntity.ActionType.BLOCK;
        assertTrue(r.isAutoBlockEnabled());
        r.enableAutoBlock = null;
        assertFalse(r.isAutoBlockEnabled());

        r.testMode = null;
        assertFalse(r.isInTestMode());
        r.testMode = true;
        assertTrue(r.isInTestMode());

        r.enableReviewQueue = false;
        assertFalse(r.needsReview());
        r.actionType = RiskRuleEntity.ActionType.REVIEW;
        assertTrue(r.needsReview());
        r.actionType = RiskRuleEntity.ActionType.ALERT;

        r.riskLevel = RiskRuleEntity.RiskLevel.HIGH;
        assertTrue(r.isHighRisk());
        r.riskLevel = RiskRuleEntity.RiskLevel.CRITICAL;
        assertTrue(r.isHighRisk());
        assertTrue(r.isCriticalRisk());

        assertFalse(r.isInCooldown(null));
        r.cooldownMinutes = 0;
        assertFalse(r.isInCooldown(LocalDateTime.now().minusMinutes(1)));
        r.cooldownMinutes = null;
        assertFalse(r.isInCooldown(LocalDateTime.now()));
        r.cooldownMinutes = 60;
        assertFalse(r.isInCooldown(null));
        assertTrue(r.isInCooldown(LocalDateTime.now().minusMinutes(30)));
        assertTrue(r.isInCooldown(LocalDateTime.now().minusMinutes(2)));  // 仍在 60min 冷却内
        assertFalse(r.isInCooldown(LocalDateTime.now().minusMinutes(90)));

        r.actionType = RiskRuleEntity.ActionType.BLOCK;
        r.blockDuration = 60;
        assertEquals("block for 60 minutes", r.getActionDescription());
        r.blockDuration = null;
        assertEquals("block", r.getActionDescription());
        r.actionType = RiskRuleEntity.ActionType.ALERT;
        assertEquals("alert", r.getActionDescription());
    }

    @Test
    @DisplayName("RoleEntity：启用/内置判定/权限匹配两个重载与权限ID集合")
    void roleEntity() {
        RoleEntity role = new RoleEntity();
        role.id = "analyst";
        role.name = "数据分析师";
        role.description = "只读分析权限";

        assertTrue(role.isEnabled());
        assertFalse(role.isSystem());
        role.enabled = null;
        assertFalse(role.isEnabled());
        role.enabled = true;
        role.system = true;
        assertTrue(role.isSystem());

        assertFalse(role.hasPermission("game:read"));
        assertTrue(role.getPermissionIds().isEmpty());

        PermissionEntity p1 = new PermissionEntity();
        p1.id = "game:read";
        p1.name = "读取游戏";
        p1.type = PermissionEntity.PermissionType.DATA;
        p1.resourceType = "game";
        p1.action = PermissionEntity.PermissionAction.READ;
        p1.scope = PermissionEntity.PermissionScope.GAME;
        p1.enabled = true;

        PermissionEntity p2 = new PermissionEntity();
        p2.id = "game:delete";
        p2.name = "删除游戏";
        p2.type = PermissionEntity.PermissionType.SYSTEM;
        p2.resourceType = "game";
        p2.action = PermissionEntity.PermissionAction.DELETE;
        p2.scope = PermissionEntity.PermissionScope.GLOBAL;
        p2.enabled = false;

        PermissionEntity p3 = new PermissionEntity();
        p3.id = "weird:manage";
        p3.name = "无资源类型权限";
        p3.type = PermissionEntity.PermissionType.API;
        p3.action = PermissionEntity.PermissionAction.MANAGE;
        p3.scope = PermissionEntity.PermissionScope.GLOBAL;
        p3.enabled = true;

        role.permissions = Set.of(p1, p2, p3);
        assertTrue(role.hasPermission("game:read"));
        assertFalse(role.hasPermission("game:delete"));
        assertFalse(role.hasPermission("不存在"));
        assertTrue(role.hasPermission("game", PermissionEntity.PermissionAction.READ));
        assertFalse(role.hasPermission("game", PermissionEntity.PermissionAction.DELETE));
        assertFalse(role.hasPermission("environment", PermissionEntity.PermissionAction.READ));
        assertTrue(role.getPermissionIds().contains("weird:manage"));
        assertEquals(3, role.getPermissionIds().size());
    }

    @Test
    @DisplayName("SSOConfigEntity：协议判定/状态迁移/自动开户开关与生命周期回调")
    void ssoConfigEntity() {
        SSOConfigEntity s = new SSOConfigEntity();
        s.name = "企业 Okta 登录";
        s.createdBy = "管理员";

        assertTrue(s.isDisabled());
        assertFalse(s.isActive());
        assertFalse(s.isTesting());
        assertFalse(s.isSAML());
        assertFalse(s.isOAuth());
        assertFalse(s.isOIDC());
        assertFalse(s.isAutoProvisionEnabled());

        s.ssoProtocol = SSOConfigEntity.SSOProtocol.SAML2;
        assertTrue(s.isSAML());
        assertFalse(s.isOAuth());
        s.ssoProtocol = SSOConfigEntity.SSOProtocol.OAUTH2;
        assertTrue(s.isOAuth());
        assertFalse(s.isOIDC());
        s.ssoProtocol = SSOConfigEntity.SSOProtocol.OIDC;
        assertTrue(s.isOAuth());
        assertTrue(s.isOIDC());

        s.errorMessage = "旧错误";
        s.activate();
        assertTrue(s.isActive());
        assertNull(s.errorMessage);
        s.ssoStatus = SSOConfigEntity.SSOStatus.TESTING;
        assertTrue(s.isTesting());
        s.disable();
        assertTrue(s.isDisabled());

        s.autoProvision = null;
        assertFalse(s.isAutoProvisionEnabled());
        s.autoProvision = true;
        assertTrue(s.isAutoProvisionEnabled());

        SSOConfigEntity fresh = new SSOConfigEntity();
        assertNull(fresh.createdAt);
        fresh.onCreate();
        assertNotNull(fresh.createdAt);
        assertEquals(SSOConfigEntity.SSOStatus.DISABLED, fresh.ssoStatus);
        fresh.ssoStatus = null;
        LocalDateTime preset = LocalDateTime.now().minusHours(2);
        fresh.createdAt = preset;
        fresh.onCreate();
        assertEquals(preset, fresh.createdAt);
        assertEquals(SSOConfigEntity.SSOStatus.DISABLED, fresh.ssoStatus);
        fresh.onUpdate();
        assertNotNull(fresh.updatedAt);
    }

    @Test
    @DisplayName("SecurityPolicyEntity：五种策略类型/范围判定/开关防御与生命周期回调")
    void securityPolicyEntity() {
        SecurityPolicyEntity p = new SecurityPolicyEntity();
        p.policyName = "全局密码策略";
        p.createdBy = "安全-吴九";
        p.policyType = SecurityPolicyEntity.PolicyType.PASSWORD;

        assertTrue(p.isPasswordPolicy());
        assertFalse(p.isSessionPolicy());
        assertFalse(p.isMFAPolicy());
        assertFalse(p.isAccessPolicy());
        assertFalse(p.isApiPolicy());
        p.policyType = SecurityPolicyEntity.PolicyType.SESSION;
        assertTrue(p.isSessionPolicy());
        p.policyType = SecurityPolicyEntity.PolicyType.MFA;
        assertTrue(p.isMFAPolicy());
        p.policyType = SecurityPolicyEntity.PolicyType.ACCESS;
        assertTrue(p.isAccessPolicy());
        p.policyType = SecurityPolicyEntity.PolicyType.API;
        assertTrue(p.isApiPolicy());

        assertTrue(p.isGlobal());
        assertFalse(p.isGameLevel());
        p.policyScope = SecurityPolicyEntity.PolicyScope.GAME;
        assertTrue(p.isGameLevel());
        assertFalse(p.isGlobal());

        assertTrue(p.isEnabled());
        p.enabled = null;
        assertFalse(p.isEnabled());
        p.enabled = true;
        p.deletedAt = LocalDateTime.now();
        assertFalse(p.isEnabled());
        p.deletedAt = null;

        p.requireIpWhitelist = null;
        assertFalse(p.isIpWhitelistRequired());
        p.requireIpWhitelist = true;
        assertTrue(p.isIpWhitelistRequired());

        SecurityPolicyEntity fresh = new SecurityPolicyEntity();
        fresh.onCreate();
        assertNotNull(fresh.createdAt);
        assertEquals(Boolean.TRUE, fresh.enabled);
        fresh.enabled = null;
        LocalDateTime preset = LocalDateTime.now().minusHours(1);
        fresh.createdAt = preset;
        fresh.onCreate();
        assertEquals(preset, fresh.createdAt);
        assertEquals(Boolean.TRUE, fresh.enabled);
        fresh.onUpdate();
        assertNotNull(fresh.updatedAt);
    }

    @Test
    @DisplayName("SessionAnalysisEntity：会话指标填充与 onCreate")
    void sessionAnalysisEntity() {
        SessionAnalysisEntity s = new SessionAnalysisEntity();
        s.gameId = "g1";
        s.environment = "prod";
        s.analysisDate = LocalDate.now().minusDays(1);
        s.totalSessions = 45000L;
        s.uniqueUsers = 21000L;
        s.avgSessionDuration = 1_800_000L;
        s.medianSessionDuration = 1_200_000L;
        s.p95SessionDuration = 5_400_000L;
        s.avgEventsPerSession = 42.5;
        s.avgPagesPerSession = 8.3;
        s.bounceSessions = 5000L;
        s.bounceRate = 0.11;
        s.highQualitySessions = 18000L;
        s.mediumQualitySessions = 15000L;
        s.lowQualitySessions = 12000L;
        s.platform = "android";
        s.sessionSource = "organic";

        assertEquals(45000L, s.totalSessions);
        assertEquals(0.11, s.bounceRate, 0.0001);
        assertEquals("organic", s.sessionSource);

        assertNull(s.createdAt);
        s.onCreate();
        assertNotNull(s.createdAt);
        LocalDateTime preset = LocalDateTime.now().minusHours(1);
        s.createdAt = preset;
        s.onCreate();
        assertEquals(preset, s.createdAt);
    }

    @Test
    @DisplayName("UserRoleEntity：启用/过期/有效判定与三种作用域")
    void userRoleEntity() {
        UserRoleEntity u = new UserRoleEntity();
        u.userId = "u-1";
        u.roleId = "analyst";

        assertTrue(u.isEnabled());
        assertFalse(u.isExpired());
        assertTrue(u.isValid());
        assertTrue(u.isGlobal());
        assertFalse(u.isGameScoped());
        assertFalse(u.isEnvironmentScoped());

        u.enabled = null;
        assertFalse(u.isEnabled());
        assertFalse(u.isValid());
        u.enabled = false;
        assertFalse(u.isValid());
        u.enabled = true;

        u.expiresAt = LocalDateTime.now().minusMinutes(1);
        assertTrue(u.isExpired());
        assertFalse(u.isValid());
        u.expiresAt = LocalDateTime.now().plusDays(1);
        assertFalse(u.isExpired());
        assertTrue(u.isValid());

        u.gameId = "g1";
        assertFalse(u.isGlobal());
        assertTrue(u.isGameScoped());
        assertFalse(u.isEnvironmentScoped());
        u.environment = "prod";
        assertTrue(u.isEnvironmentScoped());
        assertFalse(u.isGameScoped());
    }

    @Test
    @DisplayName("IdentityLinkEntity：链接活跃/过期/强度判定/确认撤销与最后活跃天数")
    void identityLinkEntity() {
        IdentityLinkEntity l = new IdentityLinkEntity();
        l.identityId = "id-1";
        l.linkedIdentityType = "device_id";
        l.linkedId = "dev-77";
        l.linkStrength = 0.5;  // 默认 1.0 会命中强链接判定

        assertTrue(l.isActive());
        assertFalse(l.isConfirmed());
        assertFalse(l.isExpired());
        assertFalse(l.isStrongLink());
        assertFalse(l.isOwnedRelation());

        l.linkStrength = 0.9;
        assertTrue(l.isStrongLink());
        l.linkStrength = 0.5;
        assertFalse(l.isStrongLink());
        l.linkStrength = null;
        assertFalse(l.isStrongLink());
        l.linkStrength = 1.0;

        l.linkType = IdentityLinkEntity.LinkType.OWNED;
        assertTrue(l.isOwnedRelation());
        l.linkType = IdentityLinkEntity.LinkType.ASSOCIATED;
        assertFalse(l.isOwnedRelation());

        l.expiredAt = LocalDateTime.now().plusDays(1);
        assertFalse(l.isExpired());
        assertTrue(l.isActive());
        l.expiredAt = LocalDateTime.now().minusMinutes(1);
        assertTrue(l.isExpired());
        assertFalse(l.isActive());
        l.expiredAt = null;
        assertTrue(l.isActive());

        l.deletedAt = LocalDateTime.now();
        assertFalse(l.isActive());
        l.deletedAt = null;

        l.verificationStatus = IdentityLinkEntity.VerificationStatus.CONFIRMED;
        assertTrue(l.isConfirmed());

        l.lastSeenAt = LocalDateTime.now();
        assertEquals(0L, l.getDaysSinceLastSeen());
        l.lastSeenAt = LocalDateTime.now().minusDays(3);
        assertTrue(l.getDaysSinceLastSeen() >= 2);

        l.revoke();
        assertEquals(IdentityLinkEntity.LinkStatus.REVOKED, l.status);
        assertNotNull(l.expiredAt);
        assertFalse(l.isActive());
    }

    @Test
    @DisplayName("MFAConfigEntity：MFA 状态迁移/方式判定/尝试次数上限与生命周期回调")
    void mfaConfigEntity() {
        MFAConfigEntity m = new MFAConfigEntity();
        m.userId = "u-1";
        m.mfaMethod = MFAConfigEntity.MFAMethod.TOTP;

        assertFalse(m.isEnabled());
        assertFalse(m.isPending());
        assertFalse(m.isLocked());
        assertFalse(m.isRecoveryMode());
        assertFalse(m.isPrimary());
        assertTrue(m.isTOTP());
        assertFalse(m.isSMS());
        assertFalse(m.isEmail());
        assertFalse(m.hasExceededAttempts());

        m.mfaMethod = MFAConfigEntity.MFAMethod.SMS;
        assertTrue(m.isSMS());
        m.mfaMethod = MFAConfigEntity.MFAMethod.EMAIL;
        assertTrue(m.isEmail());
        m.mfaMethod = MFAConfigEntity.MFAMethod.TOTP;

        m.mfaStatus = MFAConfigEntity.MFAStatus.PENDING;
        assertTrue(m.isPending());
        m.mfaStatus = MFAConfigEntity.MFAStatus.LOCKED;
        assertTrue(m.isLocked());
        m.mfaStatus = MFAConfigEntity.MFAStatus.RECOVERY_MODE;
        assertTrue(m.isRecoveryMode());

        m.recordFailedVerification();
        m.recordFailedVerification();
        assertFalse(m.hasExceededAttempts());
        m.recordFailedVerification();
        assertTrue(m.hasExceededAttempts());
        m.verificationAttempts = null;
        assertFalse(m.hasExceededAttempts());

        m.enable();
        assertTrue(m.isEnabled());
        assertEquals(0, m.verificationAttempts);
        assertNotNull(m.enrolledAt);
        m.recordVerification();
        assertNotNull(m.lastVerifiedAt);
        m.markAsPrimary();
        assertTrue(m.isPrimary());
        m.lock();
        assertTrue(m.isLocked());
        m.unlock();
        assertTrue(m.isEnabled());
        assertEquals(0, m.verificationAttempts);
        m.disable();
        assertFalse(m.isEnabled());
        m.isPrimary = null;
        assertFalse(m.isPrimary());

        MFAConfigEntity fresh = new MFAConfigEntity();
        assertNull(fresh.createdAt);
        fresh.onCreate();
        assertNotNull(fresh.createdAt);
        assertEquals(MFAConfigEntity.MFAStatus.DISABLED, fresh.mfaStatus);
        fresh.mfaStatus = null;
        LocalDateTime preset = LocalDateTime.now().minusHours(1);
        fresh.createdAt = preset;
        fresh.onCreate();
        assertEquals(preset, fresh.createdAt);
        assertEquals(MFAConfigEntity.MFAStatus.DISABLED, fresh.mfaStatus);
        fresh.onUpdate();
        assertNotNull(fresh.updatedAt);
    }

    @Test
    @DisplayName("PermissionEntity：启用/内置/作用域判定与全权限名拼接")
    void permissionEntity() {
        PermissionEntity p = new PermissionEntity();
        p.id = "game:export";
        p.name = "导出游戏数据";
        p.type = PermissionEntity.PermissionType.DATA;
        p.resourceType = "game";
        p.action = PermissionEntity.PermissionAction.EXPORT;
        p.scope = PermissionEntity.PermissionScope.GAME;

        assertTrue(p.isEnabled());
        assertFalse(p.isSystem());
        assertFalse(p.isGlobal());
        assertTrue(p.isGameScoped());
        assertFalse(p.isEnvironmentScoped());
        assertEquals("game:export", p.getFullPermission());

        p.enabled = null;
        assertFalse(p.isEnabled());
        p.enabled = true;
        p.system = true;
        assertTrue(p.isSystem());

        p.scope = PermissionEntity.PermissionScope.GLOBAL;
        assertTrue(p.isGlobal());
        p.scope = PermissionEntity.PermissionScope.ENVIRONMENT;
        assertTrue(p.isEnvironmentScoped());
    }
}
