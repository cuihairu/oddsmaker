package io.oddsmaker.control.jpa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JPA 实体业务方法深度覆盖（LAY-010 拆分）：FeatureFlag 可用性/过期/黑白名单/灰度推进/条件求值/名单精确成员判断全分支。
 */
@DisplayName("JPA 实体填充字段后的业务方法分支覆盖")
class EntitiesDeepFeatureFlagTest {

    @Test
    @DisplayName("FeatureFlagEntity：用户可用性/过期/计划启用/灰度推进分支")
    void featureFlagEntity() {
        FeatureFlagEntity f = new FeatureFlagEntity();
        f.flagKey = "new-ui";
        f.flagName = "新UI";
        f.createdBy = "admin";
        f.defaultValue = false;

        // 禁用状态：任何用户不可用
        assertFalse(f.isEnabled());
        assertTrue(f.isDisabled());
        assertFalse(f.isAvailableForUser("u1", "g1"));

        // 启用 + 未过期 + 无白名单：全部可用
        f.enable();
        assertTrue(f.isEnabled());
        assertTrue(f.isAvailableForUser("u1", "g1"));

        // 过期分支
        f.expiryDate = LocalDateTime.now().minusDays(1);
        assertTrue(f.isExpired());
        assertFalse(f.isAvailableForUser("u1", "g1"));
        f.expiryDate = LocalDateTime.now().plusDays(1);
        assertFalse(f.isExpired());

        // 用户黑名单分支
        f.blacklistUsers = "u1,u2";
        assertFalse(f.isAvailableForUser("u1", "g1"));
        assertTrue(f.isAvailableForUser("u3", "g1"));

        // 游戏黑名单分支
        f.blacklistUsers = null;
        f.blacklistGames = "g1";
        assertFalse(f.isAvailableForUser("u1", "g1"));
        assertTrue(f.isAvailableForUser("u1", "g2"));
        f.blacklistGames = null;

        // 用户白名单命中/未命中
        f.whitelistUsers = "u1,u2";
        assertTrue(f.isAvailableForUser("u2", "g1"));
        assertFalse(f.isAvailableForUser("u9", "g1"));
        f.whitelistUsers = null;

        // 游戏白名单命中/未命中
        f.whitelistGames = "g1,g2";
        assertTrue(f.isAvailableForUser("u1", "g2"));
        assertFalse(f.isAvailableForUser("u1", "g9"));
        f.whitelistGames = null;

        // 条件启用/灰度：走 defaultValue 分支
        f.flagStatus = FeatureFlagEntity.FlagStatus.CONDITIONAL;
        assertTrue(f.isConditional());
        f.defaultValue = true;
        assertTrue(f.isAvailableForUser("u1", "g1"));
        f.flagStatus = FeatureFlagEntity.FlagStatus.STAGED_ROLLOUT;
        assertTrue(f.isStagedRollout());
        f.defaultValue = false;
        assertFalse(f.isAvailableForUser("u1", "g1"));


        // 百分比设置：边界钳制与状态联动
        f.setPercentage(150);
        assertEquals(100, f.percentageValue);
        assertTrue(f.isEnabled());
        f.setPercentage(-10);
        assertEquals(0, f.percentageValue);
        assertTrue(f.isDisabled());
        f.setPercentage(50);
        assertEquals(50, f.percentageValue);
        assertTrue(f.isStagedRollout());

        // 灰度推进：仅灰度态且有步骤时步数递增
        f.rolloutSteps = "[10,50,100]";
        f.currentStep = 0;
        f.advanceRollout();
        assertEquals(1, f.currentStep);
        f.advanceRollout();
        assertEquals(2, f.currentStep);
        f.rolloutSteps = null;
        f.advanceRollout();
        assertEquals(2, f.currentStep); // 无步骤不推进
        f.disable();
        f.rolloutSteps = "[10,50,100]";
        f.advanceRollout();
        assertEquals(2, f.currentStep); // 非灰度态不推进

        // 灰度分桶：FNV-1a 确定性（锚定向量硬编码防哈希漂移），公式与 ExperimentSplitter 同源
        f.setPercentage(50); // 回到 STAGED_ROLLOUT，pct=50
        // hash32("new-ui:u7")=0x18abaef4 → bucket=28 < 50 命中；hash32("new-ui:u5")=0x1aabb21a → bucket=66 不命中
        assertTrue(f.isAvailableForUser("u7", "g1"));
        assertFalse(f.isAvailableForUser("u5", "g1"));
        // 重复调用稳定（确定性分桶）
        assertTrue(f.isAvailableForUser("u7", "g1"));
        assertFalse(f.isAvailableForUser("u5", "g1"));
        // pct 0/100 边界（手动保持灰度态，不借 ENABLED 捷径）
        f.setPercentage(0);
        assertFalse(f.isAvailableForUser("u7", "g1"));
        f.setPercentage(100);
        f.flagStatus = FeatureFlagEntity.FlagStatus.STAGED_ROLLOUT;
        assertTrue(f.isAvailableForUser("u1", "g1")); // bucket<100 恒真
        f.setPercentage(50);

        // 白名单优先于分桶：5% 灰度下 u1（bucket=90）不命中，但白名单透传
        f.setPercentage(5);
        assertFalse(f.isAvailableForUser("u1", "g1"));
        f.whitelistUsers = "u1";
        assertTrue(f.isAvailableForUser("u1", "g1"));
        f.whitelistUsers = null;

        // userId null/blank 回落 defaultValue（不参与分桶）
        f.defaultValue = true;
        assertTrue(f.isAvailableForUser(null, "g1"));
        assertTrue(f.isAvailableForUser("  ", "g1"));
        f.defaultValue = false;
        assertFalse(f.isAvailableForUser(null, "g1"));
        assertFalse(f.isAvailableForUser("  ", "g1"));

        // percentageValue null 防御（按 0 处理，回落 defaultValue）
        f.percentageValue = null;
        assertFalse(f.isAvailableForUser("u5", "g1"));
        f.percentageValue = 5;

        // 灰度推进回写：步进按 rolloutSteps 更新百分比，末步 100 自动 ENABLED
        f.flagStatus = FeatureFlagEntity.FlagStatus.STAGED_ROLLOUT;
        f.rolloutSteps = "[10,50,100]";
        f.currentStep = 0;
        f.setPercentage(5);
        f.advanceRollout();
        assertEquals(1, f.currentStep);
        assertEquals(10, f.percentageValue);
        f.advanceRollout();
        assertEquals(2, f.currentStep);
        assertEquals(50, f.percentageValue);
        f.advanceRollout();
        assertEquals(3, f.currentStep);
        assertEquals(100, f.percentageValue);
        assertTrue(f.isEnabled()); // 末步自动启用
        f.flagStatus = FeatureFlagEntity.FlagStatus.STAGED_ROLLOUT;
        f.advanceRollout(); // 超出步骤数：仅递增不回写
        assertEquals(4, f.currentStep);
        assertEquals(100, f.percentageValue);

        // 回写容错：非法 JSON/越界步骤/空白 → 仅递增不回写
        f.currentStep = 0;
        f.setPercentage(5);
        f.rolloutSteps = "not-json";
        f.advanceRollout();
        assertEquals(1, f.currentStep);
        assertEquals(5, f.percentageValue);
        f.rolloutSteps = "[10,200]";
        f.advanceRollout();
        assertEquals(2, f.currentStep);
        assertEquals(5, f.percentageValue);
        f.rolloutSteps = " ";
        f.advanceRollout();
        assertEquals(3, f.currentStep);
        assertEquals(5, f.percentageValue);
    }

    @Test
    @DisplayName("FeatureFlagEntity：CONDITIONAL 最小条件求值（AND/attribute/op/回落）")
    void featureFlagConditions() {
        FeatureFlagEntity f = new FeatureFlagEntity();
        f.flagKey = "cond-flag";
        f.flagName = "条件开关";
        f.createdBy = "admin";
        f.flagStatus = FeatureFlagEntity.FlagStatus.CONDITIONAL;
        f.defaultValue = true;

        // conditions null/空/非法 JSON/空数组/对象 → 回落 defaultValue（保守）
        assertTrue(f.isAvailableForUser("u1", "g1"));
        f.conditions = "";
        assertTrue(f.isAvailableForUser("u1", "g1"));
        f.conditions = "not-json";
        assertTrue(f.isAvailableForUser("u1", "g1"));
        f.conditions = "[]";
        assertTrue(f.isAvailableForUser("u1", "g1"));
        f.conditions = "{}";
        assertTrue(f.isAvailableForUser("u1", "g1"));

        // game_id in：命中/未命中
        f.conditions = "[{\"attribute\":\"game_id\",\"op\":\"in\",\"value\":[\"g1\",\"g2\"]}]";
        assertTrue(f.isAvailableForUser("u1", "g1"));
        assertFalse(f.isAvailableForUser("u1", "g9"));

        // user_id eq：标量 value 命中/未命中
        f.conditions = "[{\"attribute\":\"user_id\",\"op\":\"eq\",\"value\":\"u1\"}]";
        assertTrue(f.isAvailableForUser("u1", "g1"));
        assertFalse(f.isAvailableForUser("u2", "g1"));

        // op 大小写不敏感 + 数值标量/数组 value 转字符串比对
        f.conditions = "[{\"attribute\":\"game_id\",\"op\":\"IN\",\"value\":[7,8]}]";
        assertTrue(f.isAvailableForUser("u1", "7"));
        assertFalse(f.isAvailableForUser("u1", "9"));

        // ne / not_in
        f.conditions = "[{\"attribute\":\"user_id\",\"op\":\"ne\",\"value\":\"u1\"}]";
        assertFalse(f.isAvailableForUser("u1", "g1"));
        assertTrue(f.isAvailableForUser("u2", "g1"));
        f.conditions = "[{\"attribute\":\"game_id\",\"op\":\"not_in\",\"value\":[\"g1\"]}]";
        assertTrue(f.isAvailableForUser("u1", "g2"));
        assertFalse(f.isAvailableForUser("u1", "g1"));

        // 多条件 AND
        f.conditions = "[{\"attribute\":\"user_id\",\"op\":\"eq\",\"value\":\"u1\"},"
            + "{\"attribute\":\"game_id\",\"op\":\"eq\",\"value\":\"g1\"}]";
        assertTrue(f.isAvailableForUser("u1", "g1"));
        assertFalse(f.isAvailableForUser("u2", "g1"));
        assertFalse(f.isAvailableForUser("u1", "g2"));

        // 未知 attribute / 未知 op / value 空数组 / 入参 null → 该条件 false（明确不放行，不回落）
        f.conditions = "[{\"attribute\":\"device_id\",\"op\":\"eq\",\"value\":\"d1\"}]";
        assertFalse(f.isAvailableForUser("u1", "g1"));
        f.conditions = "[{\"attribute\":\"game_id\",\"op\":\"regex\",\"value\":\"g.*\"}]";
        assertFalse(f.isAvailableForUser("u1", "g1"));
        f.conditions = "[{\"attribute\":\"user_id\",\"op\":\"eq\",\"value\":[]}]";
        assertFalse(f.isAvailableForUser("u1", "g1"));
        f.conditions = "[{\"attribute\":\"user_id\",\"op\":\"eq\",\"value\":\"u1\"}]";
        assertFalse(f.isAvailableForUser(null, "g1"));
        f.conditions = "[{\"attribute\":\"game_id\",\"op\":\"eq\",\"value\":\"g1\"}]";
        assertFalse(f.isAvailableForUser("u1", null));

        // 防御分支：null 元素 / 缺 attribute → 条件 false；裸数值标量 value 转字符串比对
        f.conditions = "[null]";
        assertFalse(f.isAvailableForUser("u1", "g1"));
        f.conditions = "[{\"op\":\"eq\",\"value\":\"u1\"}]";
        assertFalse(f.isAvailableForUser("u1", "g1"));
        f.conditions = "[{\"attribute\":\"game_id\",\"op\":\"eq\",\"value\":7}]";
        assertTrue(f.isAvailableForUser("u1", "7"));
        assertFalse(f.isAvailableForUser("u1", "9"));

        // 条件态白名单优先：条件未命中但白名单用户透传
        f.conditions = "[{\"attribute\":\"game_id\",\"op\":\"eq\",\"value\":\"g9\"}]";
        assertFalse(f.isAvailableForUser("u1", "g1"));
        f.whitelistUsers = "u1";
        assertTrue(f.isAvailableForUser("u1", "g1"));
        f.whitelistUsers = null;

        // conditions 空且 defaultValue=false → false
        f.defaultValue = false;
        f.conditions = null;
        assertFalse(f.isAvailableForUser("u1", "g1"));
    }

    @Test
    @DisplayName("FeatureFlagEntity：黑白名单 JSON 数组精确成员判断（子串误伤回归）与非 JSON 回退")
    void featureFlagListMembership() {
        FeatureFlagEntity f = new FeatureFlagEntity();
        f.flagKey = "list-flag";
        f.flagName = "名单开关";
        f.createdBy = "admin";
        f.defaultValue = false;

        // 用户黑名单 JSON 数组：["u10"] 含 "u1" 子串但不含成员 "u1" → 不再误拒（回归核心）
        f.enable();
        f.blacklistUsers = "[\"u10\"]";
        assertTrue(f.isAvailableForUser("u1", "g1"));
        assertFalse(f.isAvailableForUser("u10", "g1"));

        // 多元素名单：u1 命中拒绝、u10 命中拒绝、u2 放行
        f.blacklistUsers = "[\"u1\",\"u10\"]";
        assertFalse(f.isAvailableForUser("u1", "g1"));
        assertFalse(f.isAvailableForUser("u10", "g1"));
        assertTrue(f.isAvailableForUser("u2", "g1"));

        // 游戏黑名单 JSON 数组：["g10"] 含 "g1" 子串但 "g1" 不是成员 → 不再误拒（回归核心）
        f.blacklistUsers = null;
        f.blacklistGames = "[\"g10\"]";
        assertFalse(f.isAvailableForUser("u1", "g10"));
        assertTrue(f.isAvailableForUser("u1", "g1"));
        assertTrue(f.isAvailableForUser("u1", "g2"));

        // ENABLED 态用户白名单：精确命中（u10 在名单、u1 不在——旧子串匹配下 u1 被误放）
        f.blacklistGames = null;
        f.whitelistUsers = "[\"u10\"]";
        assertTrue(f.isAvailableForUser("u10", "g1"));
        assertFalse(f.isAvailableForUser("u1", "g1"));

        // 游戏白名单
        f.whitelistUsers = null;
        f.whitelistGames = "[\"g1\",\"g10\"]";
        assertTrue(f.isAvailableForUser("u1", "g10"));
        assertFalse(f.isAvailableForUser("u1", "g9"));

        // 非 JSON 存量文本（逗号分隔）→ 保守回退子串匹配（不回归既有行为）
        f.whitelistGames = null;
        f.whitelistUsers = "u1,u2";
        assertTrue(f.isAvailableForUser("u1", "g1"));
        assertFalse(f.isAvailableForUser("u3", "g1"));

        // 名单 null → 不在名单（黑名单放行 / 白名单不启用）
        f.whitelistUsers = null;
        f.blacklistUsers = null;
        assertTrue(f.isAvailableForUser("u1", "g1"));

        // 灰度态白名单 JSON 先行透传：u5 分桶 66 > 5 不命中，但白名单 ["u5"] 透传
        f.setPercentage(5);
        f.flagStatus = FeatureFlagEntity.FlagStatus.STAGED_ROLLOUT;
        assertFalse(f.isAvailableForUser("u5", "g1"));
        f.whitelistUsers = "[\"u5\"]";
        assertTrue(f.isAvailableForUser("u5", "g1"));
    }

}
