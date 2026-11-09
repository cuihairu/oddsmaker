package io.oddsmaker.control.jpa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LAY-017 自 EntitiesBranchTopUpTest 拆分：RiskCase/SecuritySession/Role/BlockList：案例侧、会话 onCreate 预置、角色侧、封禁侧。
 * BRANCH 收口补测：按 jacoco miss 分支逐行补对侧。
 * 与 EntitiesDeep* 拆分类/EntitiesSweep2 系列拆分类互补——本类只打已miss的分支侧
 * （三元 null 侧、装箱 Boolean null 侧、枚举 == 对侧、@PrePersist false 侧、
 * 多值 || 的未测值、名单/灰度/条件求值的短路侧）。
 * 字段全 public：null 侧直接赋 null（Hibernate 加载 NULL 列在生产可达）。
 * onCreate 为 protected：同包直调覆盖 false 侧。
 */
@DisplayName("JPA 实体分支对侧补测（BRANCH 收口）")
class EntitiesBranchSecurityTest {

    @Test
    @DisplayName("RiskCase：isPending 对侧、isBlocked 合取侧、isResolved 或侧、解决时长 null 侧")
    void riskCaseSides() {
        RiskCaseEntity c = new RiskCaseEntity();
        c.executionStatus = RiskCaseEntity.ExecutionStatus.PENDING;
        assertTrue(c.isPending());
        c.executionStatus = RiskCaseEntity.ExecutionStatus.EXECUTED;
        assertFalse(c.isPending());

        // isBlocked：BLOCK+EXECUTED 全真；BLOCK+PENDING 半真；REVIEW+EXECUTED 半真
        c.actionTaken = RiskCaseEntity.ActionType.BLOCK;
        c.executionStatus = RiskCaseEntity.ExecutionStatus.EXECUTED;
        assertTrue(c.isBlocked());
        c.executionStatus = RiskCaseEntity.ExecutionStatus.PENDING;
        assertFalse(c.isBlocked());
        c.actionTaken = RiskCaseEntity.ActionType.REVIEW;
        c.executionStatus = RiskCaseEntity.ExecutionStatus.EXECUTED;
        assertFalse(c.isBlocked());

        // isResolved：resolvedAt 非空（第一侧）与 confirmed_benign（第二侧）
        c.resolvedAt = LocalDateTime.now();
        assertTrue(c.isResolved());
        c.resolvedAt = null;
        c.disposition = "confirmed_benign";
        assertTrue(c.isResolved());
        c.disposition = "confirmed_fraud";
        assertFalse(c.isResolved());

        // getResolutionTimeMinutes：resolvedAt/createdAt null 侧
        assertEquals(0, c.getResolutionTimeMinutes());   // resolvedAt null
        LocalDateTime base = LocalDateTime.of(2025, 1, 1, 0, 0);
        c.resolvedAt = base.plusMinutes(5);
        c.createdAt = null;
        assertEquals(0, c.getResolutionTimeMinutes());   // createdAt null
        c.createdAt = base;
        assertEquals(5, c.getResolutionTimeMinutes());
    }

    @Test
    @DisplayName("SecuritySession：onCreate 四字段全预设不覆盖")
    void securitySessionOnCreatePresets() {
        SecuritySessionEntity s = new SecuritySessionEntity();
        LocalDateTime preset = LocalDateTime.of(2025, 1, 1, 0, 0);
        s.createdAt = preset;
        s.loginAt = preset;
        s.lastActivityAt = preset;
        s.sessionStatus = SecuritySessionEntity.SessionStatus.EXPIRED;
        s.onCreate();
        assertEquals(preset, s.createdAt);
        assertEquals(preset, s.loginAt);
        assertEquals(preset, s.lastActivityAt);
        assertEquals(SecuritySessionEntity.SessionStatus.EXPIRED, s.sessionStatus);
    }

    @Test
    @DisplayName("Role：isEnabled/isSystem 三态、hasPermission null 名单与命中/未命中")
    void roleSides() {
        RoleEntity r = new RoleEntity();
        r.enabled = null;
        assertFalse(r.isEnabled());
        r.enabled = false;
        assertFalse(r.isEnabled());
        r.enabled = true;
        assertTrue(r.isEnabled());
        r.system = null;
        assertFalse(r.isSystem());
        r.system = true;
        assertTrue(r.isSystem());

        // hasPermission(resourceType, action)：名单 null 侧
        r.permissions = null;
        assertFalse(r.hasPermission("game", PermissionEntity.PermissionAction.READ));
        // 命中/未命中（action 枚举取真实值）
        PermissionEntity.PermissionAction act = PermissionEntity.PermissionAction.values()[0];
        PermissionEntity pe = new PermissionEntity();
        pe.resourceType = "game";
        pe.action = act;
        pe.enabled = true;
        r.permissions = Set.of(pe);
        assertTrue(r.hasPermission("game", act));
        assertFalse(r.hasPermission("user", act));
        PermissionEntity.PermissionAction other = PermissionEntity.PermissionAction.values()[0] == act
            ? PermissionEntity.PermissionAction.values()[1] : PermissionEntity.PermissionAction.values()[0];
        assertFalse(r.hasPermission("game", other));
    }

    @Test
    @DisplayName("BlockList：硬封禁对侧")
    void blockListSides() {
        BlockListEntity b = new BlockListEntity();
        b.blockType = BlockListEntity.BlockType.HARD;
        assertTrue(b.isHardBlock());
        b.blockType = BlockListEntity.BlockType.SOFT;
        assertFalse(b.isHardBlock());
        b.blockType = null;
        assertFalse(b.isHardBlock());
    }

}
