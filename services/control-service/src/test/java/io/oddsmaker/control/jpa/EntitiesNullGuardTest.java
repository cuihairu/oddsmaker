package io.oddsmaker.control.jpa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 实体字段为 null 时的安全降级守卫：业务谓词对未初始化状态全部返回 false，
 * 落到 defaultValue 兜底；@PrePersist 生命周期回调补齐缺失字段。
 */
@DisplayName("实体 null 字段安全降级与 @PrePersist 守卫")
class EntitiesNullGuardTest {

    @Test
    @DisplayName("FeatureFlagEntity：flagStatus 为 null 时安全回落 defaultValue 并在 onCreate 补 DISABLED")
    void featureFlagNullStatusFallsBack() {
        FeatureFlagEntity f = new FeatureFlagEntity();
        f.flagStatus = null;

        // 四个状态谓词（ENABLED/DISABLED/CONDITIONAL/STAGED_ROLLOUT）对 null 全为 false，
        // isAvailableForUser 走方法尾 defaultValue 兜底
        f.defaultValue = false;
        assertFalse(f.isAvailableForUser("u1", "g1"));
        f.defaultValue = true;
        assertTrue(f.isAvailableForUser("u1", "g1"));

        // @PrePersist：null 状态持久化前补 DISABLED，createdAt 补当前时间
        LocalDateTime before = LocalDateTime.now();
        f.onCreate();
        assertEquals(FeatureFlagEntity.FlagStatus.DISABLED, f.flagStatus);
        assertNotNull(f.createdAt);
        assertFalse(f.createdAt.isBefore(before));
    }

    @Test
    @DisplayName("SDKVersionEntity：onCreate 为 null 字段补 DRAFT/PATCH 与创建时间")
    void sdkVersionOnCreateFillsDefaults() {
        SDKVersionEntity s = new SDKVersionEntity();
        s.createdAt = null;
        s.versionStatus = null;
        s.changeType = null;

        s.onCreate();
        assertNotNull(s.createdAt);
        assertEquals(SDKVersionEntity.VersionStatus.DRAFT, s.versionStatus);
        assertEquals(SDKVersionEntity.ChangeType.PATCH, s.changeType);

        // 已有值不被覆盖
        LocalDateTime created = LocalDateTime.now().minusDays(1);
        s.createdAt = created;
        s.versionStatus = SDKVersionEntity.VersionStatus.RELEASED;
        s.onCreate();
        assertEquals(created, s.createdAt);
        assertEquals(SDKVersionEntity.VersionStatus.RELEASED, s.versionStatus);
    }
}
