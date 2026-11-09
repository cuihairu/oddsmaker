package io.oddsmaker.control.jpa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LAY-017 自 EntitiesBranchTopUpTest 拆分：FeatureFlag：ENABLED/条件白名单全短路侧、条件求值算子与 toValueList 值型侧、灰度步进回写与非 Legal 防御侧、onCreate 预置、parseRolloutSteps 与取值转换。
 * BRANCH 收口补测：按 jacoco miss 分支逐行补对侧。
 * 与 EntitiesDeep* 拆分类/EntitiesSweep2 系列拆分类互补——本类只打已miss的分支侧
 * （三元 null 侧、装箱 Boolean null 侧、枚举 == 对侧、@PrePersist false 侧、
 * 多值 || 的未测值、名单/灰度/条件求值的短路侧）。
 * 字段全 public：null 侧直接赋 null（Hibernate 加载 NULL 列在生产可达）。
 * onCreate 为 protected：同包直调覆盖 false 侧。
 */
@DisplayName("JPA 实体分支对侧补测（BRANCH 收口）")
class EntitiesBranchFeatureFlagTest {

    @Test
    @DisplayName("FeatureFlag：ENABLED 态白名单全短路侧——userId null/名单 null/名单空/命中/未命中")
    void featureFlagEnabledWhitelistSides() {
        FeatureFlagEntity f = new FeatureFlagEntity();
        f.flagStatus = FeatureFlagEntity.FlagStatus.ENABLED;

        // userId null 侧：跳过用户白名单，无游戏白名单 → 放行
        f.whitelistUsers = "[\"u1\"]";
        assertTrue(f.isAvailableForUser(null, null));

        // whitelistUsers null 侧 → 放行
        f.whitelistUsers = null;
        assertTrue(f.isAvailableForUser("u1", null));

        // whitelistUsers 空串侧（isEmpty）→ 放行
        f.whitelistUsers = "";
        assertTrue(f.isAvailableForUser("u1", null));

        // 全真侧：名单命中 → true；未命中 → false
        f.whitelistUsers = "[\"u1\"]";
        assertTrue(f.isAvailableForUser("u1", null));
        f.whitelistUsers = "[\"u2\"]";
        assertFalse(f.isAvailableForUser("u1", null));

        // 游戏白名单同四侧（userId 不在用户白名单、名单为 null 时进入游戏判定）
        f.whitelistUsers = null;
        f.whitelistGames = "[\"g1\"]";
        assertTrue(f.isAvailableForUser(null, "g1"));   // 命中
        assertFalse(f.isAvailableForUser(null, "g2"));  // 未命中
        f.whitelistGames = "[\"g1\"]";
        assertTrue(f.isAvailableForUser(null, null));   // gameId null 侧 → 放行
        f.whitelistGames = null;
        assertTrue(f.isAvailableForUser(null, "g1"));   // 名单 null 侧 → 放行
        f.whitelistGames = "";
        assertTrue(f.isAvailableForUser(null, "g1"));   // 名单空侧 → 放行
    }

    @Test
    @DisplayName("FeatureFlag：CONDITIONAL 态白名单先行四侧——命中放行/未命中回落/名单 null/空/userId null")
    void featureFlagConditionalWhitelistSides() {
        FeatureFlagEntity f = new FeatureFlagEntity();
        f.flagStatus = FeatureFlagEntity.FlagStatus.CONDITIONAL;
        f.defaultValue = false;
        f.conditions = null; // parseConditions null → 回落 defaultValue

        // 命中 → 恒 true（白名单先行）
        f.whitelistUsers = "[\"u1\"]";
        assertTrue(f.isAvailableForUser("u1", null));
        // 未命中 → 回落条件求值 → conditions null → defaultValue false
        f.whitelistUsers = "[\"u2\"]";
        assertFalse(f.isAvailableForUser("u1", null));
        // userId null 侧：白名单短路跳过 → defaultValue
        assertFalse(f.isAvailableForUser(null, null));
        // whitelistUsers null 侧 / 空串侧
        f.whitelistUsers = null;
        assertFalse(f.isAvailableForUser("u1", null));
        f.whitelistUsers = "";
        assertFalse(f.isAvailableForUser("u1", null));
    }

    @Test
    @DisplayName("FeatureFlag：条件求值——op 非 String、attribute 缺失、value 标量/列表的 Number/Boolean 元素")
    void featureFlagConditionEvalSides() {
        FeatureFlagEntity f = new FeatureFlagEntity();
        f.flagStatus = FeatureFlagEntity.FlagStatus.CONDITIONAL;
        f.defaultValue = false;

        // op 为数字 → instanceof String false → op null → 条件 false
        f.conditions = "[{\"attribute\":\"user_id\",\"op\":5,\"value\":[\"u1\"]}]";
        assertFalse(f.isAvailableForUser("u1", null));
        // attribute 缺失 → attribute null → 条件 false
        f.conditions = "[{\"op\":\"eq\",\"value\":[\"u1\"]}]";
        assertFalse(f.isAvailableForUser("u1", null));
        // 未知 op → false
        f.conditions = "[{\"attribute\":\"user_id\",\"op\":\"gt\",\"value\":[\"u1\"]}]";
        assertFalse(f.isAvailableForUser("u1", null));
        // value 空数组 → expected 空 → false
        f.conditions = "[{\"attribute\":\"user_id\",\"op\":\"eq\",\"value\":[]}]";
        assertFalse(f.isAvailableForUser("u1", null));

        // toValueList 列表元素 Number/Boolean 侧（ne 语义：不含即放行）
        f.conditions = "[{\"attribute\":\"user_id\",\"op\":\"ne\",\"value\":[5, true, \"x\"]}]";
        assertTrue(f.isAvailableForUser("u9", null));  // Number+Boolean 元素入列但不命中
        // value 标量 Number / Boolean 侧
        f.conditions = "[{\"attribute\":\"user_id\",\"op\":\"eq\",\"value\":5}]";
        assertTrue(f.isAvailableForUser("5", null));
        f.conditions = "[{\"attribute\":\"user_id\",\"op\":\"eq\",\"value\":true}]";
        assertTrue(f.isAvailableForUser("true", null));
        // op 大小写不敏感（EQ 大写）
        f.conditions = "[{\"attribute\":\"game_id\",\"op\":\"EQ\",\"value\":[\"g1\"]}]";
        assertTrue(f.isAvailableForUser("u1", "g1"));
        // ne 语义：命中列表则不放行
        f.conditions = "[{\"attribute\":\"user_id\",\"op\":\"ne\",\"value\":[\"u1\"]}]";
        assertFalse(f.isAvailableForUser("u1", null));
    }

    @Test
    @DisplayName("FeatureFlag：advanceRollout 步骤合法性——null/空白/越界元素/null 元素/越步/负步")
    void featureFlagRolloutStepSides() {
        FeatureFlagEntity f = new FeatureFlagEntity();
        f.flagStatus = FeatureFlagEntity.FlagStatus.STAGED_ROLLOUT;

        // rolloutSteps null → 外层守卫直接跳过（连 currentStep 都不递增）
        f.rolloutSteps = null;
        f.currentStep = 0;
        f.advanceRollout();
        assertEquals(0, f.currentStep);
        assertEquals(0, f.percentageValue);

        // rolloutSteps 空白 → 同上（isBlank 侧）
        f.rolloutSteps = "   ";
        f.currentStep = 0;
        f.advanceRollout();
        assertEquals(1, f.currentStep);

        // 步骤含 null 元素 → 非法不回写
        f.rolloutSteps = "[null]";
        f.currentStep = 0;
        f.advanceRollout();
        assertEquals(1, f.currentStep);
        assertEquals(0, f.percentageValue);

        // 步骤负值 / 超 100 → 非法不回写
        f.rolloutSteps = "[-1]";
        f.currentStep = 0;
        f.advanceRollout();
        assertEquals(1, f.currentStep);
        f.rolloutSteps = "[101]";
        f.currentStep = 0;
        f.advanceRollout();
        assertEquals(1, f.currentStep);

        // 合法范围内 → 回写百分比
        f.rolloutSteps = "[50,100]";
        f.currentStep = 0;
        f.advanceRollout();
        assertEquals(1, f.currentStep);
        assertEquals(50, f.percentageValue);

        // 超出步骤数上限 → 仅递增不回写（currentStep <= size false 侧）
        f.currentStep = 2;
        f.advanceRollout();
        assertEquals(3, f.currentStep);
        assertEquals(50, f.percentageValue);

        // currentStep 负值递增后仍 < 1 → 不回写（currentStep >= 1 false 侧）
        f.currentStep = -2;
        f.advanceRollout();
        assertEquals(-1, f.currentStep);
        assertEquals(50, f.percentageValue);
    }

    @Test
    @DisplayName("FeatureFlag：onCreate false 侧——createdAt 已设不覆盖")
    void featureFlagOnCreatePresets() {
        FeatureFlagEntity f = new FeatureFlagEntity();
        LocalDateTime preset = LocalDateTime.of(2025, 1, 1, 0, 0);
        f.createdAt = preset;
        f.onCreate();
        assertEquals(preset, f.createdAt);
    }

    @Test
    @DisplayName("FeatureFlag：parseRolloutSteps null 侧（调用方已守卫，反射直调）与不可识别 value 元素侧")
    void featureFlagParseAndValueSides() {
        FeatureFlagEntity f = new FeatureFlagEntity();
        // advanceRollout 的 rolloutSteps != null 守卫使 parseRolloutSteps 的 null 侧
        // 在当前调用图不可达，反射直调覆盖该防御侧
        f.rolloutSteps = null;
        assertNull(org.springframework.test.util.ReflectionTestUtils.invokeMethod(f, "parseRolloutSteps"));

        // toValueList 列表元素为嵌套结构（非 String/Number/Boolean）→ 忽略
        f.flagStatus = FeatureFlagEntity.FlagStatus.CONDITIONAL;
        f.conditions = "[{\"attribute\":\"user_id\",\"op\":\"ne\",\"value\":[[1], 5]}]";
        assertTrue(f.isAvailableForUser("u9", null));   // 嵌套元素忽略、5 入列、u9 不在列 → ne 放行
        // value 本身为对象（非 List/String/Number/Boolean）→ 空列表 → 条件 false
        f.conditions = "[{\"attribute\":\"user_id\",\"op\":\"eq\",\"value\":{\"k\":1}}]";
        assertFalse(f.isAvailableForUser("u9", null));
    }

}
