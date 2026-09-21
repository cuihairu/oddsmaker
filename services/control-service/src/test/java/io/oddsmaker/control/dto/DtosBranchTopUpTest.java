package io.oddsmaker.control.dto;

import io.oddsmaker.control.jpa.EventDefinitionEntity;
import io.oddsmaker.control.jpa.StorageProfileEntity;
import io.oddsmaker.control.jpa.TrackingPlanEntity;
import io.oddsmaker.control.jpa.UserEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DTO 分支对侧补测（BRANCH 收口）：updateEntity 的 null 跳过侧、
 * 装箱 Boolean null 侧、displayName 回落的空白侧。
 */
@DisplayName("DTO 分支对侧补测（BRANCH 收口）")
class DtosBranchTopUpTest {

    @Test
    @DisplayName("UserDTO：updateEntity null 字段不覆盖、displayName 回落空白侧、装箱 Boolean null 侧")
    void userDtoSides() {
        UserDTO dto = new UserDTO();
        UserEntity e = new UserEntity();
        e.email = "old@x.io";
        dto.email = null;   // null 侧：不覆盖
        dto.updateEntity(e);
        assertEquals("old@x.io", e.email);
        dto.email = "new@x.io";
        dto.updateEntity(e);
        assertEquals("new@x.io", e.email);

        // getDisplayName：displayName null + name 非空 → name；name 空白 → email 回落
        UserDTO d = new UserDTO();
        d.email = "a@b.io";
        d.displayName = null;
        d.name = "real";
        assertEquals("real", d.getDisplayName());   // 154 真侧（name 非空非空白）
        d.name = "   ";
        assertEquals("a@b.io", d.getDisplayName()); // 154 空白侧 → email
        d.name = null;
        assertEquals("a@b.io", d.getDisplayName()); // name null → email
        d.displayName = "show";
        assertEquals("show", d.getDisplayName());

        // isEmailVerified/isTwoFactorEnabled 三态
        d.emailVerified = null;
        assertFalse(d.isEmailVerified());
        d.emailVerified = false;
        assertFalse(d.isEmailVerified());
        d.emailVerified = true;
        assertTrue(d.isEmailVerified());
        d.twoFactorEnabled = null;
        assertFalse(d.isTwoFactorEnabled());
        d.twoFactorEnabled = false;
        assertFalse(d.isTwoFactorEnabled());
        d.twoFactorEnabled = true;
        assertTrue(d.isTwoFactorEnabled());
    }

    @Test
    @DisplayName("TrackingPlanDTO：updateEntity version/开关字段 null 跳过侧")
    void trackingPlanDtoSides() {
        TrackingPlanDTO dto = new TrackingPlanDTO();
        TrackingPlanEntity e = new TrackingPlanEntity();
        e.version = "1.0";
        e.enableAutoValidation = true;
        e.rejectUnknownEvents = true;
        dto.version = null;
        dto.enableAutoValidation = null;
        dto.rejectUnknownEvents = null;
        dto.updateEntity(e);
        assertEquals("1.0", e.version);          // 96 null 侧
        assertEquals(true, e.enableAutoValidation);   // 98 null 侧
        assertEquals(true, e.rejectUnknownEvents);    // 99 null 侧
        dto.version = "2.0";
        dto.enableAutoValidation = false;
        dto.rejectUnknownEvents = false;
        dto.updateEntity(e);
        assertEquals("2.0", e.version);
        assertEquals(false, e.enableAutoValidation);
        assertEquals(false, e.rejectUnknownEvents);
    }

    @Test
    @DisplayName("StorageProfileDTO：updateEntity name null 跳过侧、displayName 回落侧")
    void storageProfileDtoSides() {
        StorageProfileDTO dto = new StorageProfileDTO();
        StorageProfileEntity e = new StorageProfileEntity();
        e.name = "old";
        dto.name = null;
        dto.updateEntity(e);
        assertEquals("old", e.name);   // 89 null 侧

        dto.displayName = null;
        dto.name = "p1";
        assertEquals("p1", dto.getDisplayName());    // 119 null 侧
        dto.displayName = "  ";
        assertEquals("p1", dto.getDisplayName());    // 空白侧
        dto.displayName = "展示名";
        assertEquals("展示名", dto.getDisplayName());
    }

    @Test
    @DisplayName("GameDTO：isMultiplayer 装箱 Boolean 三态")
    void gameDtoSides() {
        GameDTO g = new GameDTO();
        g.hasMultiplayer = null;
        assertFalse(g.isMultiplayer());
        g.hasMultiplayer = false;
        assertFalse(g.isMultiplayer());
        g.hasMultiplayer = true;
        assertTrue(g.isMultiplayer());
    }

    @Test
    @DisplayName("EventDefinitionDTO：updateEntity displayName null 跳过侧")
    void eventDefinitionDtoSides() {
        EventDefinitionDTO dto = new EventDefinitionDTO();
        EventDefinitionEntity e = new EventDefinitionEntity();
        e.displayName = "old";
        dto.displayName = null;
        dto.updateEntity(e);
        assertEquals("old", e.displayName);   // 109 null 侧
        dto.displayName = "new";
        dto.updateEntity(e);
        assertEquals("new", e.displayName);
    }
}
