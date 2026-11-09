package io.oddsmaker.control.jpa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LAY-017 自 EntitiesBranchTopUpTest 拆分：WebhookConfig/WebhookLog/SdkKey/SdkVersion/Mail：计数器自增与过滤链、状态流转侧、密钥过期与模式侧、版本侧、邮件不可领取侧。
 * BRANCH 收口补测：按 jacoco miss 分支逐行补对侧。
 * 与 EntitiesDeep* 拆分类/EntitiesSweep2 系列拆分类互补——本类只打已miss的分支侧
 * （三元 null 侧、装箱 Boolean null 侧、枚举 == 对侧、@PrePersist false 侧、
 * 多值 || 的未测值、名单/灰度/条件求值的短路侧）。
 * 字段全 public：null 侧直接赋 null（Hibernate 加载 NULL 列在生产可达）。
 * onCreate 为 protected：同包直调覆盖 false 侧。
 */
@DisplayName("JPA 实体分支对侧补测（BRANCH 收口）")
class EntitiesBranchDeliveryTest {

    @Test
    @DisplayName("WebhookConfig：计数三元 null 侧与事件/风险等级过滤侧")
    void webhookConfigCounterAndFilterSides() {
        WebhookConfigEntity w = new WebhookConfigEntity();
        w.status = WebhookConfigEntity.WebhookStatus.ACTIVE;

        // getSuccessRate：totalSent null → 0；totalSuccess null → 0
        w.totalSent = null;
        w.totalSuccess = null;
        assertEquals(0.0, w.getSuccessRate());
        w.totalSent = 10L;
        assertEquals(0.0, w.getSuccessRate());   // totalSuccess null 侧
        w.totalSuccess = 4L;
        assertEquals(0.4, w.getSuccessRate());

        // recordSuccess：null 起点递增
        WebhookConfigEntity s = new WebhookConfigEntity();
        s.totalSent = null;
        s.totalSuccess = null;
        s.recordSuccess();
        assertEquals(1L, s.totalSent);
        assertEquals(1L, s.totalSuccess);

        // recordFailure：null 起点递增 + totalSuccess null 时连续失败判 FAILED
        WebhookConfigEntity f = new WebhookConfigEntity();
        f.totalSent = null;
        f.totalFailed = null;
        f.totalSuccess = null;
        f.recordFailure("err");   // totalFailed=1, consecutive=1-0
        assertEquals(1L, f.totalSent);
        assertEquals(1L, f.totalFailed);
        f.totalFailed = 5L;       // consecutive = 5 - 0(totalSuccess null) → FAILED
        f.recordFailure("err");
        assertEquals(WebhookConfigEntity.WebhookStatus.FAILED, f.status);

        // shouldSendForEvent：eventTypes null/空直通、非空匹配/不匹配
        WebhookConfigEntity e = new WebhookConfigEntity();
        e.status = WebhookConfigEntity.WebhookStatus.ACTIVE;
        e.eventTypes = null;
        e.riskLevels = null;
        assertTrue(e.shouldSendForEvent("any", "any"));   // null 名单直通
        e.eventTypes = "";
        assertTrue(e.shouldSendForEvent("any", "any"));   // 空名单直通
        e.eventTypes = "risk_high, risk_low";
        assertTrue(e.shouldSendForEvent("RISK_HIGH", null));  // trim+ignoreCase 命中
        assertFalse(e.shouldSendForEvent("other", null));     // 不匹配拒绝
        e.riskLevels = "";
        assertTrue(e.shouldSendForEvent("RISK_HIGH", "any")); // riskLevels 空直通
        e.riskLevels = "high";
        assertTrue(e.shouldSendForEvent("RISK_HIGH", "HIGH"));
        assertFalse(e.shouldSendForEvent("RISK_HIGH", "low"));
    }

    @Test
    @DisplayName("SDKKey：过期判定 expiresAt null/过去/将来、投递模式、onCreate false 侧")
    void sdkKeyExpiryAndModeSides() {
        SDKKeyEntity k = new SDKKeyEntity();
        k.keyStatus = SDKKeyEntity.KeyStatus.ACTIVE;
        // isExpired：expiresAt null → false（&& 左真右假侧）
        k.expiresAt = null;
        assertFalse(k.isExpired());
        // 过去时间 → true；将来 → false
        k.expiresAt = LocalDateTime.now().minusDays(1);
        assertTrue(k.isExpired());
        k.expiresAt = LocalDateTime.now().plusDays(1);
        assertFalse(k.isExpired());

        // 投递模式三态
        k.deliveryMode = null;
        assertFalse(k.isRealtime());
        k.deliveryMode = SDKKeyEntity.DeliveryMode.REALTIME;
        assertTrue(k.isRealtime());
        k.deliveryMode = SDKKeyEntity.DeliveryMode.BATCH;
        assertFalse(k.isRealtime());
        assertTrue(k.isBatch());
        k.deliveryMode = SDKKeyEntity.DeliveryMode.HYBRID;
        assertTrue(k.isHybrid());

        // onCreate false 侧：三字段全预设
        SDKKeyEntity p = new SDKKeyEntity();
        LocalDateTime preset = LocalDateTime.of(2025, 1, 1, 0, 0);
        p.createdAt = preset;
        p.keyStatus = SDKKeyEntity.KeyStatus.SUSPENDED;
        p.deliveryMode = SDKKeyEntity.DeliveryMode.BATCH;
        p.onCreate();
        assertEquals(preset, p.createdAt);
        assertEquals(SDKKeyEntity.KeyStatus.SUSPENDED, p.keyStatus);
        assertEquals(SDKKeyEntity.DeliveryMode.BATCH, p.deliveryMode);
    }

    @Test
    @DisplayName("WebhookLog：TIMEOUT/RETRYING 等 || 未测值")
    void webhookLogStatusSides() {
        WebhookLogEntity l = new WebhookLogEntity();
        l.deliveryStatus = WebhookLogEntity.DeliveryStatus.TIMEOUT;
        assertTrue(l.isFailed());
        assertTrue(l.shouldRetry());
        l.deliveryStatus = WebhookLogEntity.DeliveryStatus.FAILED;
        assertTrue(l.isFailed());
        assertTrue(l.shouldRetry());
        l.deliveryStatus = WebhookLogEntity.DeliveryStatus.RETRYING;
        assertTrue(l.isPending());
        l.deliveryStatus = WebhookLogEntity.DeliveryStatus.PENDING;
        assertTrue(l.isPending());
        l.deliveryStatus = WebhookLogEntity.DeliveryStatus.SENDING;
        assertFalse(l.isPending());
        l.deliveryStatus = WebhookLogEntity.DeliveryStatus.SUCCESS;
        assertFalse(l.isFailed());
        assertFalse(l.isPending());
        l.deliveryStatus = WebhookLogEntity.DeliveryStatus.CANCELLED;
        assertFalse(l.isFailed());
    }

    @Test
    @DisplayName("SDKVersion：isDraft/isPatch 对侧")
    void sdkVersionSides() {
        SDKVersionEntity v = new SDKVersionEntity();
        v.versionStatus = SDKVersionEntity.VersionStatus.DRAFT;
        assertTrue(v.isDraft());
        v.versionStatus = SDKVersionEntity.VersionStatus.RELEASED;
        assertFalse(v.isDraft());
        v.changeType = SDKVersionEntity.ChangeType.PATCH;
        assertTrue(v.isPatch());
        v.changeType = SDKVersionEntity.ChangeType.MAJOR;
        assertFalse(v.isPatch());
    }

    @Test
    @DisplayName("Mail：不可领取态（未发送）visibleTo 直接 false")
    void mailNotClaimableSide() {
        MailEntity m = new MailEntity();
        m.status = MailEntity.Status.DRAFT;
        assertFalse(m.visibleTo("p1", LocalDateTime.now()));   // !claimable true 侧
        m.status = MailEntity.Status.SENT;
        m.expireAt = LocalDateTime.now().minusMinutes(1);      // 已过期同样不可领取
        assertFalse(m.visibleTo("p1", LocalDateTime.now()));
    }

}
