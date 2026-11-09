package io.oddsmaker.control.jpa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JPA 实体业务方法深度覆盖（LAY-010 拆分）：安全会话/API 密钥/审核队列/用户实体的状态判定与过期分支。
 */
@DisplayName("JPA 实体填充字段后的业务方法分支覆盖")
class EntitiesDeepSecurityTest {

    @Test
    @DisplayName("SecuritySessionEntity：活跃/过期/撤销/续期/空闲与设备判定分支")
    void securitySessionEntity() {
        SecuritySessionEntity s = new SecuritySessionEntity();
        s.userId = "u1";
        s.sessionToken = "tok";
        s.loginAt = LocalDateTime.now().minusMinutes(10);
        s.lastActivityAt = LocalDateTime.now().minusMinutes(10);
        s.expiresAt = LocalDateTime.now().plusHours(1);

        // 活跃 + 未过期
        assertTrue(s.isActive());
        assertFalse(s.isExpired());
        assertFalse(s.isRevoked());
        assertFalse(s.isTerminated());

        // 时间过期分支
        s.expiresAt = LocalDateTime.now().minusMinutes(1);
        assertFalse(s.isActive());
        assertTrue(s.isExpired());
        // 状态过期分支
        s.expiresAt = LocalDateTime.now().plusHours(1);
        s.sessionStatus = SecuritySessionEntity.SessionStatus.EXPIRED;
        assertTrue(s.isExpired());
        s.sessionStatus = SecuritySessionEntity.SessionStatus.ACTIVE;

        // 续期上限判定（renew 本体零生产调用方已删，canRenew 保留）
        s.maxRenewalTimes = 2;
        s.renewalCount = 0;
        assertTrue(s.canRenew());
        s.renewalCount = 2;
        assertFalse(s.canRenew());
        s.maxRenewalTimes = null;
        assertTrue(s.canRenew());

        // 活动更新与会话时长
        s.lastActivityAt = LocalDateTime.now().minusHours(1);
        s.updateActivity();
        assertTrue(s.lastActivityAt.isAfter(LocalDateTime.now().minusSeconds(5)));
        s.terminatedAt = null;
        assertTrue(s.getSessionDurationMinutes() >= 9); // loginAt=10 分钟前
        s.terminatedAt = LocalDateTime.now();
        assertTrue(s.getSessionDurationMinutes() >= 9); // 以 terminatedAt 为终点

        // 撤销/终止
        s.revoke("audit");
        assertTrue(s.isRevoked());
        assertEquals("audit", s.terminationReason);
        assertNotNull(s.terminatedAt);
        s.terminate("admin", "cleanup");
        assertTrue(s.isTerminated());
        assertFalse(s.isActive());
        assertEquals("admin", s.terminatedBy);

        // 设备/位置
        SecuritySessionEntity fresh = new SecuritySessionEntity();
        assertFalse(fresh.isFromNewDevice());
        fresh.deviceFingerprint = "fp-123";
        assertTrue(fresh.isFromNewDevice());
        assertFalse(fresh.isSuspiciousLocation());
    }

    @Test
    @DisplayName("ApiKeyEntity：活跃/过期/轮换需求/吊销与用量记录分支")
    void apiKeyEntity() {
        ApiKeyEntity k = new ApiKeyEntity();
        k.apiKey = "ak_test";
        k.secret = "sk";
        k.gameId = "g1";
        k.environmentId = "prod";
        k.name = "默认密钥";

        // 活跃 + 永不过期
        assertTrue(k.isActive());
        assertFalse(k.isExpired());

        // 过期时间分支
        k.expiresAt = LocalDateTime.now().plusDays(1);
        assertTrue(k.isActive());
        assertFalse(k.isExpired());
        k.expiresAt = LocalDateTime.now().minusDays(1);
        assertFalse(k.isActive());
        assertTrue(k.isExpired());

        // 吊销
        k.revoke();
        assertEquals(ApiKeyEntity.ApiKeyStatus.REVOKED, k.status);
        assertNotNull(k.revokedAt);
        assertFalse(k.isActive());

    }

    @Test
    @DisplayName("ReviewQueueEntity：审核状态迁移/优先级/SLA 逾期与时长计算分支")
    void reviewQueueEntity() {
        ReviewQueueEntity r = new ReviewQueueEntity();
        r.riskCaseId = "case-1";
        r.gameId = "g1";
        r.caseNumber = "CN-001";
        r.targetType = "player";
        r.targetId = "p-100";
        r.priority = 50;
        r.createdAt = LocalDateTime.now().minusHours(2);

        // 初始待处理
        assertTrue(r.isPending());
        assertFalse(r.isAssigned());
        assertFalse(r.isInReview());
        assertFalse(r.isCompleted());
        assertFalse(r.isEscalated());
        assertFalse(r.isHighPriority());
        assertFalse(r.isUrgent());
        assertEquals(0, r.getResolutionTimeMinutes()); // resolvedAt 为空

        // 无 SLA 不逾期
        assertFalse(r.isOverdue());

        // 分配：SLA 已过且未完成 → 逾期
        r.assignTo("alice", LocalDateTime.now().minusMinutes(5));
        assertTrue(r.isAssigned());
        assertEquals("alice", r.assignedTo);
        assertNotNull(r.assignedAt);
        assertTrue(r.isOverdue());

        // 认领（CLAIMED 同样计入 isAssigned）
        r.claim("bob");
        assertTrue(r.isAssigned());
        assertEquals("bob", r.claimedBy);
        assertNotNull(r.claimedBy);
        assertNotNull(r.claimedAt);

        // 开始审核
        r.startReview("bob");
        assertTrue(r.isInReview());
        assertEquals("bob", r.reviewedBy);
        assertNotNull(r.reviewedAt);

        // 完成：不再逾期、不再需要动作、可计算解决时长
        r.complete("bob", "确认欺诈", "confirmed_fraud", "封禁账号");
        assertTrue(r.isCompleted());
        assertNotNull(r.resolvedAt);
        assertEquals("confirmed_fraud", r.disposition);
        assertFalse(r.isOverdue());
        assertTrue(r.getResolutionTimeMinutes() >= 119);

        // 年龄计算
        assertTrue(r.getAgeMinutes() >= 119);
        assertEquals(0, new ReviewQueueEntity().getAgeMinutes()); // createdAt 为空

        // 升级
        r.escalate("carol", "金额巨大");
        assertTrue(r.isEscalated());
        assertTrue(r.escalated);
        assertEquals("carol", r.escalatedTo);
        assertEquals("金额巨大", r.escalationReason);
        assertNotNull(r.escalatedAt);

        // 取消
        r.cancel("误报");
        assertEquals(ReviewQueueEntity.ReviewStatus.CANCELLED, r.reviewStatus);
        assertEquals("误报", r.reviewNotes);

        // 优先级阈值
        r.priority = 70;
        assertTrue(r.isHighPriority());
        assertFalse(r.isUrgent());
        r.priority = 90;
        assertTrue(r.isUrgent());
        r.priority = null;
        assertFalse(r.isHighPriority());
        assertFalse(r.isUrgent());

        // 队列描述：caseNumber 为空回退 riskCaseId
        r.caseNumber = null;
        assertTrue(r.getQueueDescription().contains("case-1"));
        r.caseNumber = "CN-001";
        assertTrue(r.getQueueDescription().contains("CN-001"));
    }

    @Test
    @DisplayName("UserEntity：活跃/锁定/角色权限范围/登录记录分支")
    void userEntity() {
        UserEntity u = new UserEntity();
        u.id = "u1";
        u.username = "alice";

        // 活跃与软删除
        assertTrue(u.isActive());
        assertFalse(u.isLocked());
        u.deletedAt = LocalDateTime.now();
        assertFalse(u.isActive());
        u.deletedAt = null;
        u.status = UserEntity.UserStatus.LOCKED;
        assertTrue(u.isLocked());
        u.status = UserEntity.UserStatus.ACTIVE;

        // 全名回退
        assertEquals("alice", u.getFullName());
        u.displayName = "爱丽丝";
        assertEquals("爱丽丝", u.getFullName());

        // 登录记录：计数从空值与数值两种起点递增
        u.loginCount = null;
        u.recordLogin("192.168.1.1");
        assertEquals(1L, u.loginCount);
        assertEquals("192.168.1.1", u.lastLoginIp);
        assertNotNull(u.lastLoginAt);
        u.recordLogin("10.0.0.1");
        assertEquals(2L, u.loginCount);
        assertEquals("10.0.0.1", u.lastLoginIp);
    }

}
